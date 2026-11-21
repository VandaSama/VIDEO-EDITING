@file:androidx.annotation.OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.media3.common.util.ExperimentalApi::class
)

package id.videoediting

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.opengl.Matrix
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.GainProcessor
import androidx.media3.effect.GlMatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects

object Engine {
    fun duration(project: Project): Long = project.clips.maxOfOrNull {
        require(it.at >= 0 && it.start >= 0 && it.end > it.start) { "Invalid range for clip ${it.id}." }
        Math.addExact(it.at, it.end - it.start)
    } ?: 0

    fun composition(context: Context, project: Project): Composition = build(context, project, false)

    fun preview(context: Context, project: Project, positionMs: Long = 0): Player {
        val composition = build(context, project, true)
        val player = CompositionPlayer.Builder(context.applicationContext).build()
        try {
            if (duration(project) > 0) {
                player.setComposition(composition, positionMs.coerceIn(0, duration(project) - 1))
                player.prepare()
            }
            return player
        } catch (e: Exception) {
            player.release()
            throw e
        }
    }

    private data class Entry(val clip: Clip, val asset: Asset, val item: EditedMediaItem) {
        val end get() = clip.at + (clip.end - clip.start)
    }

    private fun build(context: Context, project: Project, preview: Boolean): Composition {
        val total = duration(project)
        require(total > 0 && total <= Long.MAX_VALUE / 1000) { "Invalid project duration." }
        val assets = project.media.associateBy { it.id }
        require(assets.size == project.media.size) { "Duplicate media IDs." }
        val entries = project.clips.sortedBy { it.at }.map { clip ->
            val asset = requireNotNull(assets[clip.asset]) { "Missing media for clip ${clip.id}." }
            Entry(clip, asset, edit(context, project.portrait, clip, asset))
        }
        val visual = entries.filter { it.asset.kind != Kind.AUDIO }
        val audio = entries.filter { it.asset.kind == Kind.AUDIO }
        val sequences = mutableListOf<EditedMediaItemSequence>()
        if (visual.isNotEmpty()) {
            var end = 0L
            for (entry in visual) {
                if (entry.clip.at < end) throw UnsupportedOperationException("Overlapping visual clips are not supported.")
                if (entry.clip.at > end) throw UnsupportedOperationException("Visual timeline gaps are not supported.")
                end = entry.end
            }
            if (end < total) throw UnsupportedOperationException("Audio cannot extend past the last visual clip.")
            val tracks = if (visual.any { it.asset.kind == Kind.VIDEO }) {
                setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)
            } else {
                setOf(C.TRACK_TYPE_VIDEO)
            }
            sequences += sequence(visual, tracks)
        }
        val lanes = mutableListOf<MutableList<Entry>>()
        for (entry in audio) {
            val lane = lanes.firstOrNull { it.last().end <= entry.clip.at }
                ?: mutableListOf<Entry>().also { lanes.add(it) }
            lane.add(entry)
        }
        if (preview && lanes.size > 1) {
            throw UnsupportedOperationException("Preview supports one background audio sequence; export supports overlapping audio clips.")
        }
        sequences += lanes.map { sequence(it, setOf(C.TRACK_TYPE_AUDIO)) }
        return Composition.Builder(sequences).build()
    }

    private fun sequence(entries: List<Entry>, tracks: Set<Int>): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder(tracks)
        var end = 0L
        for (entry in entries) {
            if (entry.clip.at > end) builder.addGap((entry.clip.at - end) * 1000)
            builder.addItem(entry.item)
            end = entry.end
        }
        return builder.build()
    }

    private fun edit(context: Context, portrait: Boolean, clip: Clip, asset: Asset): EditedMediaItem {
        val length = clip.end - clip.start
        val photo = asset.kind == Kind.PHOTO
        val visual = asset.kind != Kind.AUDIO
        val media = MediaItem.Builder().setMediaId(clip.id).setUri(Uri.fromFile(Store.file(context, asset.id)))
        if (clip.reverse) throw UnsupportedOperationException("Reverse belum didukung dengan aman.")
        val file = Store.file(context, asset.id)
        require(file.isFile && file.length() > 0) { "Media file is missing or empty: ${asset.name}." }
        if (photo) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            media.setMimeType(requireNotNull(options.outMimeType) { "Unsupported image: ${asset.name}." })
                .setImageDurationMs(length)
        } else {
            require(asset.duration in clip.end..(Long.MAX_VALUE / 1000)) { "Clip exceeds the source duration: ${asset.name}." }
            media.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(clip.start).setEndPositionMs(clip.end).build())
        }
        val videoEffects = framing(clip, length, portrait, visual)
        val audioEffects = if (photo) emptyList() else listOfNotNull(gain(clip, length))
        return EditedMediaItem.Builder(media.build())
            .setDurationUs((if (photo) length else asset.duration) * 1000)
            .setFrameRate(30)
            .setRemoveVideo(!visual)
            .setEffects(Effects(audioEffects, videoEffects))
            .build()
    }

    private fun framing(clip: Clip, length: Long, portrait: Boolean, visual: Boolean = true): List<androidx.media3.common.Effect> {
        if (!visual) return emptyList()
        require(listOf(clip.x, clip.y, clip.endX, clip.endY).all { it in 0f..1f } &&
            listOf(clip.scale, clip.endScale).all { it.isFinite() && it > 0f }) { "Area pan & zoom tidak valid." }
        val fit = Presentation.createForWidthAndHeight(
            if (portrait) 720 else 1280, if (portrait) 1280 else 720, Presentation.LAYOUT_SCALE_TO_FIT
        )
        if (clip.x == .5f && clip.y == .5f && clip.scale == 1f &&
            clip.endX == .5f && clip.endY == .5f && clip.endScale == 1f) return listOf(fit)
        return listOf(fit, GlMatrixTransformation { timeUs ->
            val t = (timeUs / (length * 1000.0)).coerceIn(0.0, 1.0).toFloat()
            val x = clip.x + (clip.endX - clip.x) * t
            val y = clip.y + (clip.endY - clip.y) * t
            val scale = clip.scale + (clip.endScale - clip.scale) * t
            FloatArray(16).also {
                Matrix.setIdentityM(it, 0)
                Matrix.translateM(it, 0, 2 * x - 1, 1 - 2 * y, 0f)
                Matrix.scaleM(it, 0, scale, scale, 1f)
            }
        })
    }

    private fun gain(clip: Clip, length: Long): GainProcessor? {
        require(clip.volume in 0..200 && clip.keys.all { it.time in 0..length && it.volume in 0..200 }) {
            "Volume harus 0–200 dan titik volume berada dalam klip ${clip.id}."
        }
        val keys = (listOf(VolumeKey(0, clip.volume)) + clip.keys).associateBy { it.time }.values.sortedBy { it.time }
        if (keys.all { it.volume == 100 }) return null
        return GainProcessor(object : GainProcessor.GainProvider {
            override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float {
                val time = samplePosition * 1000.0 / sampleRate
                val next = keys.indexOfFirst { it.time > time }
                if (next < 0) return keys.last().volume / 100f
                val a = keys[next - 1]
                val b = keys[next]
                return ((a.volume + (b.volume - a.volume) * (time - a.time) / (b.time - a.time)) / 100).toFloat()
            }

            override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long =
                if (getGainFactorAtSamplePosition(samplePosition, sampleRate) == 1f) samplePosition + 1 else C.TIME_UNSET
        })
    }
}
