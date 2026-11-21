package id.videoediting

import java.util.UUID

data class Project(
    val portrait: Boolean = false,
    val media: List<Asset> = emptyList(),
    val clips: List<Clip> = emptyList()
)

data class Asset(val id: String, val name: String, val kind: Kind, val duration: Long)

enum class Kind { VIDEO, AUDIO, PHOTO }

data class Clip(
    val id: String,
    val asset: String,
    val at: Long,
    val start: Long,
    val end: Long,
    val volume: Int = 100,
    val keys: List<VolumeKey> = emptyList(),
    val reverse: Boolean = false,
    val x: Float = .5f,
    val y: Float = .5f,
    val scale: Float = 1f,
    val endX: Float = .5f,
    val endY: Float = .5f,
    val endScale: Float = 1f
) {
    companion object {
        fun new(asset: Asset, at: Long): Clip = Clip(UUID.randomUUID().toString(), asset.id, at, 0, asset.duration)
        fun photo(asset: Asset, at: Long, duration: Long): Clip = Clip(UUID.randomUUID().toString(), asset.id, at, 0, duration)
    }
}

data class VolumeKey(val time: Long, val volume: Int)
