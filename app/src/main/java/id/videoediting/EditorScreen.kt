package id.videoediting

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun EditorScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf(Project()) }
    var position by remember { mutableLongStateOf(0L) }
    var selected by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<androidx.media3.common.Player?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val kind = context.contentResolver.getType(uri).orEmpty().let {
                        when {
                            it.startsWith("audio/") -> Kind.AUDIO
                            it.startsWith("image/") -> Kind.PHOTO
                            else -> Kind.VIDEO
                        }
                    }
                    val asset = withContext(Dispatchers.IO) { Store.add(context, uri, kind) }
                    project = project.copy(media = project.media + asset, clips = project.clips +
                        if (kind == Kind.PHOTO) Clip.photo(asset, project.clips.maxOfOrNull { it.at + (it.end - it.start) } ?: 0L, 5_000L)
                        else Clip.new(asset, project.clips.maxOfOrNull { it.at + (it.end - it.start) } ?: 0L))
                }.onFailure { message = it.message ?: "Media gagal ditambahkan" }
            }
        }
    }
    val projectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { Store.open(context, uri) } }
                .onSuccess { project = it; position = 0L }
                .onFailure { message = it.message ?: "Proyek gagal dibuka" }
        }
    }
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { Store.save(context, uri, project) } }
                .onFailure { message = it.message ?: "Proyek gagal disimpan" }
        }
    }
    val duration = project.clips.maxOfOrNull { it.at + (it.end - it.start) } ?: 0L
    LaunchedEffect(project, position) {
        player?.release()
        player = if (project.clips.isNotEmpty()) runCatching { Engine.preview(context, project, position) }.getOrNull() else null
    }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("VIDEO EDITING", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!project.portrait, { project = project.copy(portrait = false) }, label = { Text("16:9") })
                FilterChip(project.portrait, { project = project.copy(portrait = true) }, label = { Text("9:16") })
            }
        }
        Spacer(Modifier.height(8.dp))
        AndroidView({ PlayerView(it).apply { useController = false } }, Modifier.fillMaxWidth().height(240.dp)) { it.player = player }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button({ mediaPicker.launch(arrayOf("video/*", "audio/*", "image/*")) }) { Text("Tambah media") }
            OutlinedButton({ projectPicker.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Buka") }
            OutlinedButton({ savePicker.launch("VIDEO-EDITING.zip") }) { Text("Simpan ZIP") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button({ playing = !playing; if (playing) player?.play() else player?.pause() }) { Text(if (playing) "Jeda" else "Putar") }
            OutlinedButton({ position = (position - 1).coerceAtLeast(0) }) { Text("-1 ms") }
            OutlinedButton({ position = (position + 1).coerceAtMost(duration) }) { Text("+1 ms") }
            Button({ if (ExportService.start(context, project)) message = "Export dimulai" }) { Text("Export") }
        }
        Slider(value = position.toFloat(), onValueChange = { position = it.toLong() }, valueRange = 0f..duration.coerceAtLeast(1L).toFloat())
        Text("${position} ms / ${duration} ms", modifier = Modifier.padding(bottom = 4.dp))
        Timeline(project, position, selected, { position = it }, { selected = it }) { changed ->
            project = project.copy(clips = project.clips.map { if (it.id == changed.id) changed else it })
        }
        val exportState by ExportService.state.collectAsState()
        if (exportState is ExportService.State.Running) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    message?.let { text ->
        AlertDialog(onDismissRequest = { message = null }, confirmButton = { TextButton({ message = null }) { Text("OK") } }, title = { Text("VIDEO EDITING") }, text = { Text(text) })
    }
}
