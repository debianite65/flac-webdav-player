package app.flacplayer.webdav

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Hintergrund-Wiedergabe (Media3). Streamt FLAC über WebDAV, schreibt alles in den
 * gemeinsamen Cache und lässt den [Preloader] die nächsten 15 Titel vorladen.
 *
 * Als MediaLibraryService bietet der Dienst zusätzlich einen Browse-Baum an, den
 * Android Auto im Fahrzeug-Display anzeigt (siehe [AutoBrowse]).
 */
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private var preloader: Preloader? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val app = FlacApp.instance

        val dataSourceFactory = CacheDataSource.Factory()
            .setCache(app.audioCache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(Net.client))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_stat_music)
            },
        )

        // Gespeicherte Warteschlange wiederherstellen (pausiert), bevor Listener hängen.
        QueueStore.restore(player)

        // Warteschlange bei jeder Änderung speichern.
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_IS_PLAYING_CHANGED,
                    )
                ) {
                    QueueStore.save(player)
                }
            }
        })

        // "Zuletzt gespielt" merken
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val item = mediaItem ?: return
                val settings = app.settings
                settings.lastTrackUrl = item.mediaId
                settings.lastFolder = item.mediaId.substringBeforeLast('/') + "/"
                settings.lastTrackTitle = item.mediaMetadata.title?.toString().orEmpty()
                enrichCurrent(player)
            }
        })

        preloader = Preloader(this, player, dataSourceFactory).also { it.start() }
        enrichCurrent(player)
    }

    /**
     * Android Auto kann Dateipfade vom Handy nicht lesen. Deshalb bekommt der aktuelle Titel sein Cover
     * als Bilddaten (artworkData) mit. Fehlt das Cover noch, wird es hier nachgeladen. Das Ersetzen des
     * MediaItems ändert nur die Metadaten; die Wiedergabe läuft unterbrechungsfrei weiter.
     */
    private fun enrichCurrent(player: Player) {
        val item = player.currentMediaItem ?: return
        if (item.mediaMetadata.artworkData != null) return
        val url = item.mediaId
        scope.launch {
            val bytes = try {
                AutoBrowse.coverBytesFor(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return@launch
            val current = player.currentMediaItem ?: return@launch
            if (current.mediaId != url || current.mediaMetadata.artworkData != null) return@launch
            val index = player.currentMediaItemIndex
            val metadata = current.mediaMetadata.buildUpon()
                .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .build()
            player.replaceMediaItem(index, current.buildUpon().setMediaMetadata(metadata).build())
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player != null) QueueStore.save(player)
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.player?.let { QueueStore.save(it) }
        scope.cancel()
        preloader?.release()
        preloader = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Wandelt Ids aus dem Browse-Baum bzw. aus der Handy-App in abspielbare Items (mediaId = URL). */
    private fun resolve(item: MediaItem): MediaItem {
        val url = item.mediaId.removePrefix(AutoBrowse.TRACK)
        return item.buildUpon().setMediaId(url).setUri(url).build()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(AutoBrowse.rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            scope.future<LibraryResult<ImmutableList<MediaItem>>> {
                try {
                    val p = session.player
                    val kids = AutoBrowse.children(
                        parentId,
                        p.mediaItemCount > 0,
                        p.currentMediaItem?.mediaMetadata?.title?.toString(),
                    )
                    if (kids == null) {
                        LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                    } else {
                        val from = minOf(kids.size.toLong(), page.toLong() * pageSize.coerceAtLeast(0)).toInt()
                        val to = if (pageSize > 0) {
                            minOf(kids.size.toLong(), from.toLong() + pageSize).toInt()
                        } else {
                            kids.size
                        }
                        LibraryResult.ofItemList(ImmutableList.copyOf(kids.subList(from, to)), params)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
                }
            }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            scope.future<LibraryResult<MediaItem>> {
                fun lastSegment(url: String, fallback: String) =
                    url.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotEmpty() } ?: fallback

                val item: MediaItem? = when {
                    mediaId == AutoBrowse.ROOT -> AutoBrowse.rootItem()
                    mediaId == AutoBrowse.RESUME ->
                        AutoBrowse.resumeItem(session.player.currentMediaItem?.mediaMetadata?.title?.toString())
                    mediaId.startsWith(AutoBrowse.DIR) -> {
                        val url = mediaId.removePrefix(AutoBrowse.DIR)
                        AutoBrowse.folderItem(url, lastSegment(url, "Musik"))
                    }
                    mediaId.startsWith(AutoBrowse.TRACK) -> {
                        val url = mediaId.removePrefix(AutoBrowse.TRACK)
                        AutoBrowse.browseTrackItem(
                            DavEntry(url, lastSegment(url, url), false, -1L),
                            Library.cachedMeta(url),
                            null,
                        )
                    }
                    else -> null
                }
                if (item != null) {
                    LibraryResult.ofItem(item, null)
                } else {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                }
            }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { resolve(it) }.toMutableList())

        /**
         * Android Auto schickt beim Antippen einen einzelnen Browse-Eintrag. Daraus wird hier die
         * Warteschlange (alle FLAC-Dateien des Ordners, Start beim Titel) bzw. "Weiterspielen".
         * Die Handy-App schickt ihre Warteschlange fertig mit URLs; sie läuft über den Standardpfad.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            scope.future<MediaSession.MediaItemsWithStartPosition> {
                val id = if (mediaItems.size == 1) mediaItems[0].mediaId else ""
                when {
                    id == AutoBrowse.RESUME -> {
                        val p = mediaSession.player
                        if (p.mediaItemCount == 0) throw IllegalStateException("Keine gespeicherte Warteschlange")
                        MediaSession.MediaItemsWithStartPosition(
                            List(p.mediaItemCount) { p.getMediaItemAt(it) },
                            p.currentMediaItemIndex,
                            p.currentPosition,
                        )
                    }
                    id.startsWith(AutoBrowse.TRACK) -> {
                        val (items, index) = AutoBrowse.queueForTrack(id.removePrefix(AutoBrowse.TRACK))
                        if (items.isEmpty()) throw IllegalStateException("Keine FLAC-Dateien gefunden")
                        MediaSession.MediaItemsWithStartPosition(items, index, C.TIME_UNSET)
                    }
                    id.startsWith(AutoBrowse.DIR) -> {
                        val items = AutoBrowse.queueForFolder(id.removePrefix(AutoBrowse.DIR))
                        if (items.isEmpty()) throw IllegalStateException("Keine FLAC-Dateien gefunden")
                        MediaSession.MediaItemsWithStartPosition(items, 0, C.TIME_UNSET)
                    }
                    else -> MediaSession.MediaItemsWithStartPosition(
                        mediaItems.map { resolve(it) },
                        startIndex,
                        startPositionMs,
                    )
                }
            }
    }
}
