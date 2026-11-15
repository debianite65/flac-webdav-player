package app.flacplayer.webdav

import android.app.Application
import android.content.ComponentName
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class Screen { Browser, Player, Queue, Settings }

data class QueueItemUi(
    val mediaId: String,
    val title: String,
    val artist: String,
    val hasCover: Boolean,
)

data class PlayerUi(
    val hasItem: Boolean = false,
    val isPlaying: Boolean = false,
    val mediaId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val settings get() = FlacApp.instance.settings

    var screen by mutableStateOf(if (settings.isConfigured) Screen.Browser else Screen.Settings)

    /** Ordner-Stapel: [0] = Wurzel, letztes Element = aktueller Ordner. */
    val path = mutableStateListOf<String>()
    var entries by mutableStateOf<List<DavEntry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val meta = mutableStateMapOf<String, TrackMeta>()

    var player by mutableStateOf(PlayerUi())
        private set
    var lastTitle by mutableStateOf(settings.lastTrackTitle)
        private set

    var queue by mutableStateOf<List<QueueItemUi>>(emptyList())
        private set
    var queueIndex by mutableStateOf(-1)
        private set

    var settingsBusy by mutableStateOf(false)
        private set
    var settingsMessage by mutableStateOf<String?>(null)
        private set
    var settingsOk by mutableStateOf(false)
        private set

    private var controller: MediaController? = null
    private var loadJob: Job? = null
    private var pendingPlay: String? = null

    val canGoBack: Boolean
        get() = (screen != Screen.Browser && settings.isConfigured) || (screen == Screen.Browser && path.size > 1)

    init {
        connectController()
        viewModelScope.launch {
            while (isActive) {
                delay(500)
                val c = controller ?: continue
                if (player.hasItem && player.isPlaying) {
                    player = player.copy(
                        positionMs = c.currentPosition.coerceAtLeast(0),
                        durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: player.durationMs,
                    )
                }
            }
        }
        if (settings.isConfigured) openRoot()
    }

    // ------------------------------------------------------------------ Navigation

    fun folderTitle(): String {
        val url = path.lastOrNull() ?: return "FlacPlayer"
        if (path.size <= 1) return "FlacPlayer"
        return url.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotEmpty() } ?: "FlacPlayer"
    }

    fun back() {
        if (screen == Screen.Queue) {
            screen = Screen.Player
        } else if (screen != Screen.Browser) {
            screen = Screen.Browser
        } else {
            up()
        }
    }

    fun openRoot() {
        val base = settings.serverUrl
        if (base.isEmpty()) return
        path.clear()
        path.add(base)
        load(base)
    }

    fun open(folderUrl: String) {
        path.add(folderUrl)
        load(folderUrl)
    }

    fun up() {
        if (path.size > 1) {
            path.removeAt(path.lastIndex)
            load(path.last())
        }
    }

    fun reload() {
        path.lastOrNull()?.let { load(it) }
    }

    /** Öffnet einen beliebigen Ordner unterhalb der Wurzel und baut den Pfad-Stapel dafür auf. */
    private fun openPath(folderUrl: String) {
        val base = settings.serverUrl
        if (base.isEmpty()) return
        if (!folderUrl.startsWith(base)) {
            openRoot()
            return
        }
        path.clear()
        path.add(base)
        var current = base
        for (seg in folderUrl.removePrefix(base).trim('/').split('/').filter { it.isNotEmpty() }) {
            current = "$current$seg/"
            path.add(current)
        }
        load(path.last())
    }

    private fun load(url: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loading = true
            error = null
            try {
                val list = WebDav.list(url)
                val dirs = list.filter { it.isDir }.sortedWith { a, b -> naturalCompare(a.name, b.name) }
                val tracks = list.filter { it.isFlac }.sortedWith { a, b -> naturalCompare(a.name, b.name) }
                entries = dirs + tracks
                loading = false

                val folderCover = list.filter { it.isCoverImage }
                    .minByOrNull { coverPriority(it.name) }?.url
                loadMetas(tracks, folderCover)
                resortTracks()

                pendingPlay?.let { wanted ->
                    pendingPlay = null
                    entries.firstOrNull { it.url == wanted }?.let { play(it, forceRestart = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                entries = emptyList()
                error = e.message?.takeIf { it.isNotBlank() } ?: "Verbindung fehlgeschlagen"
                loading = false
                pendingPlay = null
            }
        }
    }

    private fun coverPriority(name: String): Int {
        val base = name.lowercase().substringBeforeLast('.')
        return when (base) {
            "cover" -> 0
            "folder" -> 1
            "front" -> 2
            else -> 3
        }
    }

    private suspend fun loadMetas(tracks: List<DavEntry>, folderCover: String?) = coroutineScope {
        tracks.map { track ->
            async {
                val m = Library.loadMeta(track.url, folderCover)
                meta[track.url] = m
            }
        }.awaitAll()
    }

    /** Sortiert die Titel nach Disc/Tracknummer (aus den Tags), sonst nach Dateiname. */
    private fun resortTracks() {
        val dirs = entries.filter { it.isDir }
        val tracks = entries.filter { it.isFlac }
        val sorted = tracks.sortedWith(
            compareBy<DavEntry>(
                { meta[it.url]?.disc ?: 0 },
                { meta[it.url]?.track ?: Int.MAX_VALUE },
            ).thenComparator { a, b -> naturalCompare(a.name, b.name) },
        )
        entries = dirs + sorted
    }

    fun hasCover(trackUrl: String): Boolean =
        (meta[trackUrl] ?: Library.cachedMeta(trackUrl))?.hasCover == true

    // ------------------------------------------------------------------ Wiedergabe

    fun play(track: DavEntry, forceRestart: Boolean = false) {
        val c = controller ?: return
        if (!forceRestart && track.url == player.mediaId) {
            screen = Screen.Player
            return
        }
        val tracks = entries.filter { it.isFlac }
        val index = tracks.indexOfFirst { it.url == track.url }
        if (index < 0) return
        val items = tracks.map { buildItem(it) }
        c.setMediaItems(items, index, 0L)
        c.prepare()
        c.play()
    }

    private fun buildItem(
        entry: DavEntry,
        m: TrackMeta? = meta[entry.url] ?: Library.cachedMeta(entry.url),
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(m?.title ?: entry.name.substringBeforeLast('.'))
            .setArtist(m?.artist)
            .setAlbumTitle(m?.album)
            .build()
        return MediaItem.Builder()
            .setMediaId(entry.url)
            .setUri(entry.url)
            .setMediaMetadata(metadata)
            .build()
    }

    // ------------------------------------------------------------------ Warteschlange

    private fun toast(text: String) {
        Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
    }

    /** Fügt einen einzelnen Titel hinzu: direkt nach dem aktuellen ([next]) oder ans Ende. */
    fun enqueueTrack(entry: DavEntry, next: Boolean) {
        addItems(listOf(buildItem(entry)), next, entry.name.substringBeforeLast('.'))
    }

    /** Fügt alle FLAC-Dateien eines Ordners (ohne Unterordner) in Track-Reihenfolge hinzu. */
    fun enqueueFolder(folder: DavEntry, next: Boolean) {
        viewModelScope.launch {
            try {
                toast("Ordner wird gelesen …")
                val list = WebDav.list(folder.url)
                val tracks = list.filter { it.isFlac }
                if (tracks.isEmpty()) {
                    toast("Keine FLAC-Dateien in diesem Ordner")
                    return@launch
                }
                val cover = list.filter { it.isCoverImage }.minByOrNull { coverPriority(it.name) }?.url
                val withMeta = coroutineScope {
                    tracks.map { t -> async { t to Library.loadMeta(t.url, cover) } }.awaitAll()
                }
                val sorted = withMeta.sortedWith(
                    compareBy<Pair<DavEntry, TrackMeta>>(
                        { it.second.disc ?: 0 },
                        { it.second.track ?: Int.MAX_VALUE },
                    ).thenComparator { a, b -> naturalCompare(a.first.name, b.first.name) },
                )
                addItems(sorted.map { buildItem(it.first, it.second) }, next, folder.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Hinzufügen fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun addItems(items: List<MediaItem>, next: Boolean, label: String) {
        val c = controller ?: return
        if (items.isEmpty()) return
        if (c.mediaItemCount == 0) {
            c.setMediaItems(items)
            c.prepare()
            c.play()
            toast("${items.size} Titel: Wiedergabe gestartet")
            return
        }
        if (next) {
            c.addMediaItems(c.currentMediaItemIndex + 1, items)
        } else {
            c.addMediaItems(items)
        }
        val where = if (next) "als Nächstes" else "ans Ende"
        toast(if (items.size == 1) "„$label“ $where hinzugefügt" else "${items.size} Titel $where hinzugefügt")
    }

    fun jumpTo(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekTo(index, 0L)
        c.play()
    }

    fun removeFromQueue(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.removeMediaItem(index)
    }

    fun moveInQueue(from: Int, to: Int) {
        val c = controller ?: return
        val count = c.mediaItemCount
        if (from !in 0 until count || to !in 0 until count || from == to) return
        c.moveMediaItem(from, to)
    }

    fun clearQueue() {
        controller?.let {
            it.stop()
            it.clearMediaItems()
        }
        screen = Screen.Browser
    }

    private fun syncQueue() {
        val c = controller ?: return
        queue = List(c.mediaItemCount) { i ->
            val item = c.getMediaItemAt(i)
            QueueItemUi(
                mediaId = item.mediaId,
                title = item.mediaMetadata.title?.toString().orEmpty(),
                artist = item.mediaMetadata.artist?.toString().orEmpty(),
                hasCover = Library.cachedMeta(item.mediaId)?.hasCover == true,
            )
        }
        queueIndex = c.currentMediaItemIndex
        if (queue.isEmpty() && screen == Screen.Queue) screen = Screen.Browser
    }

    /** "Zuletzt gespielt": Ordner öffnen und den Titel dort starten. */
    fun resumeLast() {
        // Ist die Warteschlange (wiederhergestellt oder laufend) vorhanden, einfach dort weiterspielen,
        // statt sie durch den letzten Ordner zu ersetzen.
        if (player.hasItem) {
            controller?.play()
            screen = Screen.Player
            return
        }
        val folder = settings.lastFolder
        val track = settings.lastTrackUrl
        if (folder.isEmpty() || track.isEmpty()) return
        pendingPlay = track
        openPath(folder)
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.playWhenReady && c.playbackState != Player.STATE_ENDED) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
            c.play()
        }
    }

    fun next() {
        controller?.seekToNext()
    }

    fun previous() {
        controller?.seekToPrevious()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        player = player.copy(positionMs = ms)
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    private fun connectController() {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        viewModelScope.launch {
            try {
                val c = MediaController.Builder(app, token).buildAsync().await()
                controller = c
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        syncPlayer()
                        if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                            syncQueue()
                        }
                    }
                })
                syncPlayer()
                syncQueue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Player-Dienst nicht erreichbar: ${e.message}"
            }
        }
    }

    private fun syncPlayer() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val md = item?.mediaMetadata
        val title = md?.title?.toString().orEmpty()
        if (item != null && title.isNotEmpty()) lastTitle = title
        if (item == null && (screen == Screen.Player || screen == Screen.Queue)) screen = Screen.Browser
        player = PlayerUi(
            hasItem = item != null,
            isPlaying = c.playWhenReady &&
                c.playbackState != Player.STATE_IDLE &&
                c.playbackState != Player.STATE_ENDED,
            mediaId = item?.mediaId.orEmpty(),
            title = title,
            artist = md?.artist?.toString().orEmpty(),
            album = md?.albumTitle?.toString().orEmpty(),
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
        )
    }

    // ------------------------------------------------------------------ Einstellungen

    /** Speichert die Server-Daten und prüft die Verbindung mit einer Ordner-Abfrage. */
    fun saveAndTest(rawUrl: String, user: String, pass: String) {
        var url = rawUrl.trim()
        if (url.isEmpty()) {
            settingsOk = false
            settingsMessage = "Bitte die Server-Adresse eintragen."
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        viewModelScope.launch {
            settingsBusy = true
            settingsMessage = null
            settings.serverUrl = url
            settings.username = user
            settings.password = pass
            try {
                WebDav.list(settings.serverUrl)
                settingsOk = true
                settingsMessage = "Verbindung erfolgreich."
                screen = Screen.Browser
                openRoot()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                settingsOk = false
                settingsMessage = "Verbindung fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                settingsBusy = false
            }
        }
    }

    fun clearCache() {
        controller?.let {
            it.stop()
            it.clearMediaItems()
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { FlacApp.instance.clearAllCaches() }
            meta.clear()
            settingsOk = true
            settingsMessage = "Cache geleert."
            reload()
        }
    }

    fun cacheUsedMb(): Long = FlacApp.instance.audioCache.cacheSpace / (1024L * 1024L)

    override fun onCleared() {
        controller?.release()
        controller = null
        super.onCleared()
    }
}
