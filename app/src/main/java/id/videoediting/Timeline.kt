package id.videoediting

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToLong

@Composable
fun Timeline(
    project: Project,
    position: Long,
    selected: String?,
    seek: (Long) -> Unit,
    select: (String) -> Unit,
    change: (Clip) -> Unit
) {
    val assets = remember(project.media) { project.media.associateBy { it.id } }
    val lanes = remember(project.clips, assets) { timelineLanes(project.clips, assets) }
    val duration = project.clips.maxOfOrNull { it.timelineEnd } ?: 0L
    val current by rememberUpdatedState(TimelineInput(lanes, assets, duration, selected, seek, select, change))
    var zoom by remember { mutableStateOf(1f) }
    var origin by remember { mutableStateOf(0.0) }
    var vertical by remember { mutableStateOf(0f) }

    Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
        val ruler = 32.dp.toPx()
        val row = 56.dp.toPx()
        val base = 80.dp.toPx() / 1000.0
        val handle = 12.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            val input = current
            val scale = base * zoom
            val originAtDown = origin
            val lane = if (down.position.y < ruler) null else
                input.lanes.getOrNull(((down.position.y - ruler + vertical) / row).toInt())
            val time = originAtDown + down.position.x / scale
            val clip = lane?.lastOrNull { time >= it.at && time < it.timelineEnd }
            val edge = if (clip != null && clip.id == input.selected) {
                val zone = minOf(handle / scale, clip.length / 3.0)
                when {
                    time - clip.at <= zone -> -1
                    clip.timelineEnd - time <= zone -> 1
                    else -> 0
                }
            } else 0
            var pinched = false
            var moved = false
            var scrolling = false
            var lastEdit = clip
            var lastY = down.position.y
            val seekAt: (Float) -> Unit = { x ->
                current.seek((originAtDown + x / scale).roundToLong().coerceIn(0L, current.duration))
            }
            do {
                val event = awaitPointerEvent()
                val count = event.changes.count { it.pressed }
                if (count >= 2) {
                    pinched = true
                    val centroid = event.calculateCentroid(useCurrent = false)
                    if (centroid != Offset.Unspecified) {
                        val pan = event.calculatePan()
                        val anchor = origin + centroid.x / (base * zoom)
                        zoom = (zoom * event.calculateZoom()).coerceIn(0.125f, 32f)
                        val visible = size.width / (base * zoom)
                        origin = (anchor - (centroid.x + pan.x) / (base * zoom))
                            .coerceIn(0.0, (current.duration - visible * 0.5).coerceAtLeast(0.0))
                        vertical = (vertical - pan.y).coerceIn(0f, (ruler + current.lanes.size * row - size.height).coerceAtLeast(0f))
                    }
                } else if (!pinched) {
                    val pointer = event.changes.firstOrNull { it.id == down.id }
                    if (pointer != null) {
                        val delta = pointer.position - down.position
                        if (!moved && delta.getDistance() > viewConfiguration.touchSlop) {
                            moved = true
                            scrolling = clip == null && down.position.y >= ruler && abs(delta.y) > abs(delta.x)
                            if (clip != null) current.select(clip.id)
                        }
                        if (moved) {
                            if (scrolling) {
                                vertical = (vertical - (pointer.position.y - lastY))
                                    .coerceIn(0f, (ruler + current.lanes.size * row - size.height).coerceAtLeast(0f))
                            } else if (clip == null) {
                                seekAt(pointer.position.x)
                            } else {
                                val updated = timelineEdit(clip, edge, (delta.x / scale).roundToLong(), input.assets[clip.asset])
                                if (updated != lastEdit) {
                                    current.change(updated)
                                    lastEdit = updated
                                }
                            }
                        } else if (!pointer.pressed) {
                            if (clip != null) current.select(clip.id) else seekAt(pointer.position.x)
                        }
                        lastY = pointer.position.y
                    }
                }
                event.changes.forEach { it.consume() }
            } while (count > 0)
        }
    }) {
        val ruler = 32.dp.toPx()
        val row = 56.dp.toPx()
        val scale = 80.dp.toPx() * zoom / 1000.0
        fun x(time: Long) = ((time - origin) * scale).toFloat()
        drawRect(Color(0xff14181f))
        drawRect(Color(0xff232933), size = Size(size.width, ruler))
        var step = 100L
        while (step * scale < 32.dp.toPx()) step *= 2
        var tick = origin.toLong() / step * step
        while (x(tick) < size.width) {
            drawLine(Color.Gray, Offset(x(tick), ruler * 0.65f), Offset(x(tick), ruler))
            tick += step
        }
        clipRect(top = ruler) {
            lanes.forEachIndexed { index, lane ->
                val y = ruler + index * row - vertical
                if (y + row <= ruler || y >= size.height) return@forEachIndexed
                drawLine(Color(0xff343c48), Offset(0f, y + row), Offset(size.width, y + row))
                lane.forEach { clip ->
                    val left = x(clip.at)
                    val right = x(clip.timelineEnd)
                    if (right < 0 || left > size.width) return@forEach
                    val top = y + 5.dp.toPx()
                    val height = row - 10.dp.toPx()
                    val width = (right - left).coerceAtLeast(1f)
                    val active = clip.id == selected
                    val kind = assets[clip.asset]?.kind
                    val color = when {
                        active -> Color(0xff346cd0)
                        kind == Kind.AUDIO -> Color(0xff286957)
                        else -> Color(0xff414e66)
                    }
                    drawRect(color, Offset(left, top), Size(width, height))
                    if (kind != Kind.PHOTO) {
                        val keys = timelineKeys(clip)
                        val points = keys + VolumeKey(clip.length, keys.last().volume)
                        fun point(key: VolumeKey) = Offset(
                            left + (key.time * scale).toFloat(),
                            top + height * (0.85f - 0.7f * key.volume.coerceIn(0, 100) / 100f)
                        )
                        points.zipWithNext().forEach { (a, b) ->
                            drawLine(Color(0xffc7eee5), point(a), point(b), 1.dp.toPx())
                        }
                        clip.keys.filter { it.time in 0..clip.length }.forEach {
                            drawCircle(Color(0xffc7eee5), 2.dp.toPx(), point(it))
                        }
                    }
                    if (active) {
                        val grip = minOf(4.dp.toPx(), width / 3)
                        drawRect(Color.White, Offset(left, top), Size(grip, height))
                        drawRect(Color.White, Offset(left + width - grip, top), Size(grip, height))
                    }
                }
            }
        }
        val playhead = x(position)
        drawLine(Color(0xffff805b), Offset(playhead, 0f), Offset(playhead, size.height), 2.dp.toPx())
    }
}

private data class TimelineInput(
    val lanes: List<List<Clip>>,
    val assets: Map<String, Asset>,
    val duration: Long,
    val selected: String?,
    val seek: (Long) -> Unit,
    val select: (String) -> Unit,
    val change: (Clip) -> Unit
)

private val Clip.length: Long get() = end - start
private val Clip.timelineEnd: Long get() = at + length

private fun timelineLanes(clips: List<Clip>, assets: Map<String, Asset>): List<List<Clip>> {
    fun pack(group: List<Clip>): List<List<Clip>> {
        val lanes = mutableListOf<MutableList<Clip>>()
        group.sortedBy { it.at }.forEach { clip ->
            val lane = lanes.firstOrNull { it.last().timelineEnd <= clip.at }
                ?: mutableListOf<Clip>().also { lanes.add(it) }
            lane.add(clip)
        }
        return lanes
    }
    val (audio, visual) = clips.partition { assets[it.asset]?.kind == Kind.AUDIO }
    return pack(visual) + pack(audio)
}

private fun timelineKeys(clip: Clip): List<VolumeKey> =
    (listOf(VolumeKey(0, clip.volume)) + clip.keys.filter { it.time in 0..clip.length })
        .associateBy { it.time }.values.sortedBy { it.time }

private fun timelineEdit(clip: Clip, edge: Int, delta: Long, asset: Asset?): Clip {
    if (delta == 0L) return clip
    if (edge == 0) return clip.copy(at = (clip.at + delta).coerceIn(0L, Long.MAX_VALUE - clip.length))
    val edited = if (edge < 0) {
        val shift = delta.coerceIn(-minOf(clip.start, clip.at), clip.length - 1)
        clip.copy(at = clip.at + shift, start = clip.start + shift)
    } else {
        val limit = if (asset?.kind == Kind.PHOTO) Long.MAX_VALUE / 1000 else asset?.duration ?: clip.end
        clip.copy(end = (clip.end + delta).coerceIn(clip.start + 1, maxOf(clip.end, limit)))
    }
    if (edited == clip) return clip
    val shift = edited.start - clip.start
    val keys = timelineKeys(clip)
    fun volumeAt(time: Long): Int {
        val right = keys.indexOfFirst { it.time > time }
        if (right < 0) return keys.last().volume
        if (right == 0) return keys.first().volume
        val a = keys[right - 1]
        val b = keys[right]
        return (a.volume + (b.volume - a.volume) * ((time - a.time).toDouble() / (b.time - a.time))).roundToLong().toInt()
    }
    val mapped = keys.map { it.copy(time = it.time - shift) }.filter { it.time in 1..edited.length }
    val endKey = if (edited.length + shift < clip.length && mapped.none { it.time == edited.length })
        listOf(VolumeKey(edited.length, volumeAt(edited.length + shift))) else emptyList()
    return edited.copy(volume = volumeAt(shift), keys = if (clip.keys.isEmpty()) emptyList() else mapped + endKey)
}
