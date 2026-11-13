package app.flacplayer.webdav

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlin.math.roundToInt

@Composable
fun AppRoot(vm: MainViewModel) {
    BackHandler(enabled = vm.canGoBack) { vm.back() }
    when (vm.screen) {
        Screen.Browser -> BrowserScreen(vm)
        Screen.Player -> PlayerScreen(vm)
        Screen.Queue -> QueueScreen(vm)
        Screen.Settings -> SettingsScreen(vm)
    }
}

// ============================================================================ Bausteine

@Composable
fun CoverImage(
    trackUrl: String,
    hasCover: Boolean,
    modifier: Modifier = Modifier,
    maxPx: Int = 200,
    corner: Dp = 6.dp,
) {
    val bitmap by produceState<Bitmap?>(null, trackUrl, hasCover) {
        value = if (hasCover) Library.bitmap(trackUrl, maxPx) else null
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxSize(0.5f),
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

// ============================================================================ Browser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: MainViewModel) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(vm.folderTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (vm.path.size > 1) {
                        IconButton(onClick = { vm.up() }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Neu laden")
                    }
                    IconButton(onClick = { vm.screen = Screen.Settings }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.primary,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        bottomBar = { if (vm.player.hasItem) MiniPlayer(vm) },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            when {
                vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                vm.error != null -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        vm.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = { vm.reload() }) { Text("Erneut versuchen") }
                    OutlinedButton(onClick = { vm.screen = Screen.Settings }) { Text("Einstellungen") }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    if (vm.path.size <= 1 && vm.lastTitle.isNotEmpty()) {
                        item { ResumeCard(vm) }
                    }
                    if (vm.entries.isEmpty()) {
                        item {
                            Text(
                                "Keine Ordner oder FLAC-Dateien in diesem Ordner.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                    items(vm.entries, key = { it.url }) { entry ->
                        if (entry.isDir) FolderRow(entry, vm) else TrackRow(entry, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResumeCard(vm: MainViewModel) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable { vm.resumeLast() },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Zuletzt gespielt", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(vm.lastTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FolderRow(entry: DavEntry, vm: MainViewModel) {
    ListItem(
        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(40.dp),
            )
        },
        trailingContent = {
            QueueMenu(
                nextLabel = "Alle Titel als Nächstes spielen",
                endLabel = "Alle Titel ans Ende der Warteschlange",
                onNext = { vm.enqueueFolder(entry, next = true) },
                onEnd = { vm.enqueueFolder(entry, next = false) },
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { vm.open(entry.url) },
    )
}

@Composable
private fun TrackRow(entry: DavEntry, vm: MainViewModel) {
    val m = vm.meta[entry.url]
    val isCurrent = vm.player.mediaId == entry.url
    val subtitle = listOfNotNull(m?.artist, m?.album).joinToString(" · ")
    ListItem(
        headlineContent = {
            Text(
                m?.title ?: entry.name.substringBeforeLast('.'),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
        },
        supportingContent = if (subtitle.isNotEmpty()) {
            { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        leadingContent = {
            CoverImage(entry.url, m?.hasCover == true, Modifier.size(48.dp))
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = "Läuft", tint = MaterialTheme.colorScheme.primary)
                }
                QueueMenu(
                    nextLabel = "Als Nächstes spielen",
                    endLabel = "Ans Ende der Warteschlange",
                    onNext = { vm.enqueueTrack(entry, next = true) },
                    onEnd = { vm.enqueueTrack(entry, next = false) },
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { vm.play(entry) },
    )
}

@Composable
private fun QueueMenu(nextLabel: String, endLabel: String, onNext: () -> Unit, onEnd: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Mehr")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(nextLabel) },
                leadingIcon = { Icon(Icons.Filled.PlaylistPlay, contentDescription = null) },
                onClick = {
                    open = false
                    onNext()
                },
            )
            DropdownMenuItem(
                text = { Text(endLabel) },
                leadingIcon = { Icon(Icons.Filled.PlaylistAdd, contentDescription = null) },
                onClick = {
                    open = false
                    onEnd()
                },
            )
        }
    }
}

@Composable
private fun MiniPlayer(vm: MainViewModel) {
    val p = vm.player
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable { vm.screen = Screen.Player },
    ) {
        Column {
            LinearProgressIndicator(
                progress = {
                    if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f
                },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverImage(p.mediaId, vm.hasCover(p.mediaId), Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (p.artist.isNotEmpty()) {
                        Text(
                            p.artist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { vm.previous() }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Zurück")
                }
                IconButton(onClick = { vm.togglePlay() }) {
                    Icon(
                        if (p.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (p.isPlaying) "Pause" else "Wiedergabe",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = { vm.next() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Weiter")
                }
            }
        }
    }
}

// ============================================================================ Player

@Composable
fun PlayerScreen(vm: MainViewModel) {
    val p = vm.player
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = p.durationMs.coerceAtLeast(1L).toFloat()
    val shownPos = if (dragging) dragValue else p.positionMs.toFloat().coerceIn(0f, duration)

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.screen = Screen.Browser }) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Schließen", modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.screen = Screen.Queue }) {
                Icon(
                    Icons.Filled.QueueMusic,
                    contentDescription = "Warteschlange",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        CoverImage(
            trackUrl = p.mediaId,
            hasCover = vm.hasCover(p.mediaId),
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            maxPx = 800,
            corner = 16.dp,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            p.title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        val sub = listOf(p.artist, p.album).filter { it.isNotEmpty() }.joinToString(" · ")
        if (sub.isNotEmpty()) {
            Text(
                sub,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
        Slider(
            value = shownPos,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                vm.seekTo(dragValue.toLong())
                dragging = false
            },
            valueRange = 0f..duration,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(shownPos.toLong()), style = MaterialTheme.typography.labelMedium)
            Text(formatTime(p.durationMs), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.toggleShuffle() }) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = "Zufällig",
                    tint = if (p.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { vm.previous() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Zurück", modifier = Modifier.size(36.dp))
            }
            FilledIconButton(onClick = { vm.togglePlay() }, modifier = Modifier.size(72.dp)) {
                Icon(
                    if (p.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (p.isPlaying) "Pause" else "Wiedergabe",
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(onClick = { vm.next() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Weiter", modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = { vm.cycleRepeat() }) {
                Icon(
                    if (p.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = "Wiederholen",
                    tint = if (p.repeatMode != Player.REPEAT_MODE_OFF) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

// ============================================================================ Warteschlange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(vm: MainViewModel) {
    val rowHeight = 64.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (vm.queueIndex - 2).coerceAtLeast(0),
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Warteschlange (${vm.queue.size})") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.Player }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.clearQueue() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Warteschlange leeren")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.primary,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
    ) { inner ->
        LazyColumn(Modifier.padding(inner).fillMaxSize(), state = listState) {
            itemsIndexed(vm.queue, key = { index, q -> "$index:${q.mediaId}" }) { index, q ->
                QueueRow(index, q, index == vm.queueIndex, rowHeight, rowPx, vm)
            }
        }
    }
}

@Composable
private fun QueueRow(
    index: Int,
    item: QueueItemUi,
    isCurrent: Boolean,
    rowHeight: Dp,
    rowPx: Float,
    vm: MainViewModel,
) {
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = dragY }
            .background(if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable { vm.jumpTo(index) }
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(item.mediaId, item.hasCover, Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title.ifEmpty { item.mediaId.substringAfterLast('/').substringBeforeLast('.') },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
            if (item.artist.isNotEmpty()) {
                Text(
                    item.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isCurrent) {
            Icon(Icons.Filled.GraphicEq, contentDescription = "Läuft", tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = { vm.removeFromQueue(index) }) {
            Icon(Icons.Filled.Close, contentDescription = "Entfernen")
        }
        Icon(
            Icons.Filled.DragHandle,
            contentDescription = "Verschieben",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(44.dp)
                .padding(10.dp)
                .pointerInput(index) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            val target = (index + (dragY / rowPx).roundToInt())
                                .coerceIn(0, (vm.queue.size - 1).coerceAtLeast(0))
                            dragging = false
                            dragY = 0f
                            vm.moveInQueue(index, target)
                        },
                        onDragCancel = {
                            dragging = false
                            dragY = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragY += amount.y
                        },
                    )
                },
        )
    }
}

// ============================================================================ Einstellungen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val settings = FlacApp.instance.settings
    var url by remember { mutableStateOf(settings.serverUrl) }
    var user by remember { mutableStateOf(settings.username) }
    var pass by remember { mutableStateOf(settings.password) }
    var preload by remember { mutableStateOf(settings.preloadEnabled) }
    var wifiOnly by remember { mutableStateOf(settings.preloadWifiOnly) }
    var cacheMb by remember { mutableStateOf(settings.cacheMb) }
    val configured = settings.isConfigured

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    if (configured) {
                        IconButton(onClick = { vm.screen = Screen.Browser }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("WebDAV-Server", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Adresse (z. B. https://server/remote.php/dav/files/name/Musik/)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (url.trim().startsWith("http://")) {
                Text(
                    "Achtung: http:// ist unverschlüsselt. Benutzername und Passwort werden im Klartext übertragen.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Benutzername") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("Passwort") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.saveAndTest(url, user, pass) },
                enabled = !vm.settingsBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (vm.settingsBusy) "Prüfe …" else "Verbindung testen und speichern")
            }
            vm.settingsMessage?.let {
                Text(
                    it,
                    color = if (vm.settingsOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Vorladen und Cache", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            SwitchRow(
                title = "Nächste 15 Titel vorladen",
                subtitle = "Lädt die folgenden Titel der Warteschlange im Hintergrund in den Cache.",
                checked = preload,
                onChange = {
                    preload = it
                    settings.preloadEnabled = it
                },
            )
            SwitchRow(
                title = "Nur im WLAN vorladen",
                subtitle = "Aus = auch über mobile Daten vorladen (verbraucht Datenvolumen).",
                checked = wifiOnly,
                onChange = {
                    wifiOnly = it
                    settings.preloadWifiOnly = it
                },
                enabled = preload,
            )
            Text("Cache-Größe (Obergrenze)", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1024 to "1 GB", 2048 to "2 GB", 5120 to "5 GB", 10240 to "10 GB").forEach { (mb, label) ->
                    FilterChip(
                        selected = cacheMb == mb,
                        onClick = {
                            cacheMb = mb
                            settings.cacheMb = mb
                        },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                "Die neue Obergrenze gilt nach einem Neustart der App. Belegt: ${vm.cacheUsedMb()} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { vm.clearCache() }, modifier = Modifier.fillMaxWidth()) {
                Text("Cache leeren (stoppt die Wiedergabe)")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
