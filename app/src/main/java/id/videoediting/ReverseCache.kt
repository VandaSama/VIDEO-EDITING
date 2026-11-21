package id.videoediting

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object ReverseCache {
    private const val MAX_DURATION_MS = 30_000L

    fun get(context: Context, asset: Asset): File {
        require(asset.duration in 1..MAX_DURATION_MS) {
            "Reverse hanya untuk klip maksimum ${MAX_DURATION_MS / 1000} detik."
        }
        val dir = File(context.cacheDir, "reverse").apply { mkdirs() }
        val source = Store.file(context, asset.id)
        val target = File(dir, "${asset.id}-${source.length()}.mp4")
        if (target.isFile && target.length() > 0) return target
        val temp = File(dir, "${asset.id}-${source.length()}.tmp.mp4")
        temp.delete()
        try {
            reverseFile(source, temp)
            if (!temp.renameTo(target)) throw IllegalStateException("Gagal membuat cache reverse")
            return target
        } catch (e: Throwable) {
            temp.delete()
            throw IllegalStateException("Format video tidak mendukung reverse", e)
        }
    }

        private fun reverseFile(source: File, target: File) {
        val probe = MediaExtractor()
        try {
            probe.setDataSource(source.absolutePath)
            val selectedTracks = mutableListOf<Int>()
            val formats = mutableMapOf<Int, MediaFormat>()
            for (i in 0 until probe.trackCount) {
                val format = probe.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    probe.selectTrack(i)
                    selectedTracks += i
                    formats[i] = format
                }
            }
            require(selectedTracks.size <= 2) { "Format tidak didukung" }
            require(selectedTracks.any { formats.getValue(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }) {
                "Reverse audio tidak didukung"
            }
            val input = ByteBuffer.allocate(2 * 1024 * 1024)
            val packets = selectedTracks.associateWith { mutableListOf<Pair<ByteArray, Long>>() }
            val flags = selectedTracks.associateWith { mutableListOf<Int>() }
            while (true) {
                input.clear()
                val size = probe.readSampleData(input, 0)
                if (size < 0) break
                val trackIndex = probe.sampleTrackIndex
                if (trackIndex !in packets) { probe.advance(); continue }
                val data = ByteArray(size)
                input.flip()
                input.get(data)
                packets.getValue(trackIndex) += data to probe.sampleTime
                flags.getValue(trackIndex) += probe.sampleFlags
                probe.advance()
            }
            val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val ids = selectedTracks.associateWith { muxer.addTrack(formats.getValue(it)) }
                muxer.start()
                val info = MediaCodec.BufferInfo()
                for (trackIndex in selectedTracks) {
                    val id = ids.getValue(trackIndex)
                    val trackPackets = packets.getValue(trackIndex)
                    val ordered = trackPackets.indices.sortedByDescending { trackPackets[it].second }
                    val video = formats.getValue(trackIndex).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                    var time = 0L
                    for (index in ordered) {
                        val packet = trackPackets[index]
                        info.set(0, packet.first.size, time, if (video) MediaCodec.BUFFER_FLAG_KEY_FRAME else flags.getValue(trackIndex)[index])
                        muxer.writeSampleData(id, ByteBuffer.wrap(packet.first), info)
                        time += 33_000
                    }
                }
                muxer.stop()
            } finally {
                muxer.release()
            }
        } finally {
            probe.release()
        }
    }
}
