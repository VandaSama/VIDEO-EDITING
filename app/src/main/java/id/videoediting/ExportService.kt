@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package id.videoediting

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ExportService : Service() {
    sealed interface State {
        object Idle : State
        data class Running(val progress: Int?) : State
        data class Completed(val uri: Uri) : State
        data class Failed(val message: String) : State
        object Cancelled : State
    }

    companion object {
        private const val START = "id.videoediting.EXPORT_START"
        private const val CANCEL = "id.videoediting.EXPORT_CANCEL"
        private const val CHANNEL = "videoediting_export"
        private const val NOTIFICATION = 41
        private val mutableState = MutableStateFlow<State>(State.Idle)
        val state = mutableState.asStateFlow()
        private var pending: Project? = null

        fun start(context: Context, project: Project): Boolean {
            check(Looper.myLooper() == Looper.getMainLooper()) { "Start export on the main thread." }
            if (mutableState.value is State.Running) return false
            pending = project.copy(media = project.media.toList(), clips = project.clips.map { it.copy(keys = it.keys.toList()) })
            mutableState.value = State.Running(null)
            return try {
                val intent = Intent(context, ExportService::class.java).setAction(START)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
                true
            } catch (e: Exception) {
                pending = null
                mutableState.value = State.Failed(e.message ?: "Could not start export.")
                false
            }
        }

        fun cancel(context: Context) {
            if (mutableState.value is State.Running) {
                context.startService(Intent(context, ExportService::class.java).setAction(CANCEL))
            }
        }
    }

    private class Job(val file: File, val audioOnly: Boolean) {
        val cancelled = AtomicBoolean()
        var publishing = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private var job: Job? = null
    private var transformer: Transformer? = null
    private val poll = object : Runnable {
        override fun run() {
            val active = transformer ?: return
            val holder = ProgressHolder()
            val progress = if (active.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                holder.progress.coerceIn(0, 99)
            } else null
            val next = State.Running(progress)
            if (mutableState.value != next) {
                mutableState.value = next
                notifications.notify(NOTIFICATION, notification(progress))
            }
            handler.postDelayed(this, 350)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CANCEL -> if (job != null || pending != null) finish(State.Cancelled) else stopSelf()
            START -> if (job == null) begin()
            else -> if (job == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        val project = pending
        pending = null
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Media export", NotificationManager.IMPORTANCE_LOW))
            }
            startForeground(NOTIFICATION, notification(null))
            requireNotNull(project) { "Export request was lost; start it again from the editor." }
            check(Build.VERSION.SDK_INT >= 29) { "Saving exports to MediaStore requires Android 10 or later." }
            val audioOnly = project.clips.all { clip -> project.media.any { it.id == clip.asset && it.kind == Kind.AUDIO } }
            val active = Job(File.createTempFile("export-", ".mp4", cacheDir), audioOnly)
            job = active
            Thread {
                val result = runCatching { Engine.composition(applicationContext, project) }
                handler.post {
                    if (job === active) {
                        result.fold({ render(active, it) }, { fail(it) })
                    }
                }
            }.start()
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun render(active: Job, composition: Composition) {
        try {
            val builder = Transformer.Builder(this).setAudioMimeType(MimeTypes.AUDIO_AAC)
            if (!active.audioOnly) builder.setVideoMimeType(MimeTypes.VIDEO_H264)
            val instance = builder.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (job === active) publish(active)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (job === active) {
                        transformer = null
                        fail(exportException)
                    }
                }
            }).build()
            transformer = instance
            instance.start(composition, active.file.absolutePath)
            handler.post(poll)
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun publish(active: Job) {
        transformer = null
        handler.removeCallbacks(poll)
        active.publishing = true
        mutableState.value = State.Running(99)
        notifications.notify(NOTIFICATION, notification(99))
        Thread {
            var inserted: Uri? = null
            val result = runCatching {
                check(active.file.length() > 0) { "Export produced an empty file." }
                val collection = if (active.audioOnly) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val values = ContentValues().apply {
                    val extension = if (active.audioOnly) "m4a" else "mp4"
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "VideoEditing-${System.currentTimeMillis()}.$extension")
                    put(MediaStore.MediaColumns.MIME_TYPE, if (active.audioOnly) "audio/mp4" else "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, (if (active.audioOnly) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/VideoEditing")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                check(!active.cancelled.get())
                val uri = contentResolver.insert(collection, values) ?: throw IOException("Could not create MediaStore entry.")
                inserted = uri
                val stream = contentResolver.openOutputStream(uri) ?: throw IOException("Could not open MediaStore entry.")
                stream.use { output ->
                    active.file.inputStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            check(!active.cancelled.get())
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                uri
            }
            active.file.delete()
            handler.post {
                if (job !== active) {
                    inserted?.let(::deleteEntry)
                } else {
                    val committed = result.mapCatching { uri ->
                        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        check(contentResolver.update(uri, values, null, null) == 1) { "Could not publish export." }
                        uri
                    }
                    committed.fold({ finish(State.Completed(it)) }, {
                        inserted?.let(::deleteEntry)
                        fail(it)
                    })
                }
            }
        }.start()
    }

    private fun deleteEntry(uri: Uri) {
        runCatching { contentResolver.delete(uri, null, null) }
    }

    private fun fail(e: Throwable) = finish(State.Failed(e.message ?: "Media export failed."))

    private fun clear() {
        handler.removeCallbacks(poll)
        val active = job
        job = null
        active?.cancelled?.set(true)
        val instance = transformer
        transformer = null
        runCatching { instance?.cancel() }
        if (active != null && !active.publishing) active.file.delete()
    }

    private fun finish(result: State) {
        pending = null
        clear()
        mutableState.value = result
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (job != null) {
            clear()
            mutableState.value = State.Cancelled
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun notification(progress: Int?): Notification {
        val cancel = PendingIntent.getService(this, 0, Intent(this, ExportService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Exporting media")
            .setContentText(progress?.let { "$it%" } ?: "Preparing export")
            .setProgress(100, progress ?: 0, progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build()
    }
}
