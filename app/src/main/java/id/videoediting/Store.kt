package id.videoediting

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Store {
    private const val MANIFEST = "project.json"
    private const val JSON_LIMIT = 2 * 1024 * 1024
    private const val MEDIA_LIMIT = 512L * 1024 * 1024
    private const val TOTAL_LIMIT = 2L * 1024 * 1024 * 1024
    private const val ASSET_LIMIT = 256
    private const val CLIP_LIMIT = 4096
    private const val KEY_LIMIT = 16384
    private val safeId = Regex("[A-Za-z0-9_-]{1,128}")

    fun file(context: Context, assetId: String): File {
        require(safeId.matches(assetId)) { "Invalid asset ID" }
        return File(File(context.filesDir, "project-media"), "asset-$assetId")
    }

    fun add(context: Context, uri: Uri, kind: Kind): Asset {
        val name = context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }?.take(512) ?: kind.name
        val (id, target) = reserve(context)
        try {
            input(context, uri).use { source ->
                target.outputStream().buffered().use { copy(source, it, MEDIA_LIMIT) }
            }
            return Asset(id, name, kind, duration(target, kind))
        } catch (failure: Exception) {
            target.delete()
            throw failure
        }
    }

    fun save(context: Context, uri: Uri, project: Project) {
        val manifest = encode(project).toByteArray(Charsets.UTF_8)
        val sources = project.media.map { file(context, it.id) }
        var remaining = TOTAL_LIMIT
        for (source in sources) {
            val length = source.length()
            if (!source.isFile || length > MEDIA_LIMIT || length > remaining) {
                throw IOException("Missing media or archive size limit exceeded")
            }
            remaining -= length
        }
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Cannot open export URI")
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest)
            zip.closeEntry()
            remaining = TOTAL_LIMIT
            for ((index, source) in sources.withIndex()) {
                zip.putNextEntry(ZipEntry(entryName(project.media[index].id)))
                remaining -= source.inputStream().buffered().use {
                    copy(it, zip, minOf(MEDIA_LIMIT, remaining))
                }
                zip.closeEntry()
            }
        }
    }

    fun open(context: Context, uri: Uri): Project {
        val created = mutableListOf<File>()
        try {
            return ZipInputStream(BoundedInput(input(context, uri).buffered())).use { zip ->
                val first = zip.nextEntry
                if (first?.name != MANIFEST) throw IOException("Missing first project manifest")
                val bytes = java.io.ByteArrayOutputStream()
                copy(zip, bytes, JSON_LIMIT.toLong())
                zip.closeEntry()
                val project = decode(Charsets.UTF_8.newDecoder().decode(
                    java.nio.ByteBuffer.wrap(bytes.toByteArray())
                ).toString())
                val pending = project.media.associateBy { entryName(it.id) }.toMutableMap()
                val ids = mutableMapOf<String, String>()
                var remaining = TOTAL_LIMIT
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val asset = pending.remove(entry.name)
                        ?: throw IOException("Unexpected or duplicate ZIP entry")
                    val (id, target) = reserve(context)
                    created.add(target)
                    remaining -= target.outputStream().buffered().use {
                        copy(zip, it, minOf(MEDIA_LIMIT, remaining))
                    }
                    zip.closeEntry()
                    ids[asset.id] = id
                }
                if (pending.isNotEmpty()) throw IOException("Archive is missing media")
                project.copy(
                    media = project.media.map { it.copy(id = ids.getValue(it.id)) },
                    clips = project.clips.map { it.copy(asset = ids.getValue(it.asset)) }
                )
            }
        } catch (failure: Exception) {
            created.forEach { it.delete() }
            throw failure
        }
    }

    fun encode(project: Project): String {
        validate(project)
        val media = JSONArray()
        for (asset in project.media) {
            media.put(JSONObject().put("id", asset.id).put("name", asset.name)
                .put("kind", asset.kind.name).put("duration", asset.duration))
        }
        val clips = JSONArray()
        for (clip in project.clips) {
            val keys = JSONArray()
            for (key in clip.keys) {
                keys.put(JSONObject().put("time", key.time).put("volume", key.volume))
            }
            clips.put(JSONObject().put("id", clip.id).put("asset", clip.asset)
                .put("at", clip.at).put("start", clip.start).put("end", clip.end)
                .put("volume", clip.volume).put("keys", keys).put("reverse", clip.reverse)
                .put("x", clip.x.toDouble()).put("y", clip.y.toDouble())
                .put("scale", clip.scale.toDouble()).put("endX", clip.endX.toDouble())
                .put("endY", clip.endY.toDouble()).put("endScale", clip.endScale.toDouble()))
        }
        return JSONObject().put("portrait", project.portrait).put("media", media)
            .put("clips", clips).toString().also { checkJsonSize(it) }
    }

    fun decode(json: String): Project {
        checkJsonSize(json)
        checkJsonDepth(json)
        val parser = org.json.JSONTokener(json)
        val root = parser.nextValue() as? JSONObject ?: throw IOException("Expected project object")
        require(parser.nextClean() == '\u0000') { "Trailing project data" }
        val assets = root.array("media", ASSET_LIMIT)
        val clips = root.array("clips", CLIP_LIMIT)
        var keyBudget = KEY_LIMIT
        val project = Project(
            portrait = root.optBoolean("portrait", false),
            media = List(assets.length()) { index ->
                val asset = assets.getJSONObject(index)
                Asset(asset.getString("id"), asset.getString("name"),
                    Kind.valueOf(asset.getString("kind")), asset.integer("duration"))
            },
            clips = List(clips.length()) { index ->
                val clip = clips.getJSONObject(index)
                val keys = clip.array("keys", keyBudget)
                keyBudget -= keys.length()
                Clip(
                    id = clip.getString("id"), asset = clip.getString("asset"),
                    at = clip.integer("at"), start = clip.integer("start"), end = clip.integer("end"),
                    volume = clip.int("volume", 100),
                    keys = List(keys.length()) { keyIndex ->
                        val key = keys.getJSONObject(keyIndex)
                        VolumeKey(key.integer("time"), key.int("volume"))
                    },
                    reverse = clip.optBoolean("reverse", false),
                    x = clip.float("x", .5f), y = clip.float("y", .5f),
                    scale = clip.float("scale", 1f), endX = clip.float("endX", .5f),
                    endY = clip.float("endY", .5f), endScale = clip.float("endScale", 1f)
                )
            }
        )
        validate(project)
        return project
    }

    private fun validate(project: Project) {
        require(project.media.size <= ASSET_LIMIT && project.clips.size <= CLIP_LIMIT) { "Too many items" }
        val assets = HashSet<String>()
        for (asset in project.media) {
            require(safeId.matches(asset.id) && assets.add(asset.id)) { "Invalid or duplicate asset ID" }
            require(asset.name.length <= 512 && asset.duration >= 0) { "Invalid asset metadata" }
        }
        val clips = HashSet<String>()
        var keyBudget = KEY_LIMIT
        for (clip in project.clips) {
            require(clip.id.isNotEmpty() && clip.id.length <= 128 && clips.add(clip.id)) { "Invalid or duplicate clip ID" }
            require(clip.asset in assets) { "Clip refers to missing media" }
            require(clip.keys.size <= keyBudget) { "Too many volume keys" }
            keyBudget -= clip.keys.size
            require(listOf(clip.x, clip.y, clip.scale, clip.endX, clip.endY, clip.endScale)
                .all { it.isFinite() }) { "Non-finite clip transform" }
        }
    }

    private fun JSONObject.array(name: String, limit: Int): JSONArray {
        val array = if (has(name)) getJSONArray(name) else JSONArray()
        require(array.length() <= limit) { "Too many $name items" }
        return array
    }

    private fun JSONObject.integer(name: String): Long = when (val value = get(name)) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw IOException("Expected integer: $name")
    }

    private fun JSONObject.int(name: String, default: Int? = null): Int {
        if (!has(name) && default != null) return default
        val value = integer(name)
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Integer out of range: $name" }
        return value.toInt()
    }

    private fun JSONObject.float(name: String, default: Float): Float =
        if (!has(name)) default else (get(name) as? Number)?.toFloat()
            ?: throw IOException("Expected number: $name")

    private fun checkJsonSize(json: String) {
        require(json.length <= JSON_LIMIT && json.toByteArray(Charsets.UTF_8).size <= JSON_LIMIT) {
            "Project manifest too large"
        }
    }

    private fun checkJsonDepth(json: String) {
        var depth = 0
        var quoted = false
        var escape = false
        for (c in json) {
            if (quoted) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') quoted = false
            } else {
                when (c) {
                    '"' -> quoted = true
                    '{', '[' -> require(++depth <= 16) { "Project JSON is too deeply nested" }
                    '}', ']' -> require(--depth >= 0) { "Unbalanced project JSON" }
                    '/', '#', '\'', '\u0000' -> throw IOException("Invalid project JSON token")
                }
            }
        }
        require(!quoted && depth == 0) { "Incomplete project JSON" }
    }

    private fun reserve(context: Context): Pair<String, File> {
        val id = UUID.randomUUID().toString()
        val target = file(context, id)
        val directory = target.parentFile ?: throw IOException("Missing private media directory")
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Cannot create private media directory")
        if (!target.createNewFile()) throw IOException("Media ID collision")
        return id to target
    }

    private fun entryName(id: String): String = "media/$id"

    private fun input(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open input URI")

    private fun duration(file: File, kind: Kind): Long {
        if (kind == Kind.PHOTO) return 0L
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        } catch (_: RuntimeException) {
            0L
        } finally {
            retriever.release()
        }
    }

    private fun copy(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            if (total > limit) throw IOException("Size limit exceeded")
            output.write(buffer, 0, count)
        }
    }

    private class BoundedInput(source: InputStream) : FilterInputStream(source) {
        private var remaining = TOTAL_LIMIT + 16L * 1024 * 1024

        override fun read(): Int {
            if (remaining == 0L) throw IOException("ZIP input size limit exceeded")
            return super.read().also { if (it >= 0) remaining-- }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) throw IOException("ZIP input size limit exceeded")
            return super.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
                .also { if (it > 0) remaining -= it }
        }
    }
}
