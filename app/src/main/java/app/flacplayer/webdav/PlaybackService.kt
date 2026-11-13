package app.flacplayer.webdav

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Hintergrund-Wiedergabe (Media3). Streamt FLAC über WebDAV, schreibt alles in den
 * gemeinsamen Cache und lässt den [Preloader] die nächsten 15 Titel vorladen.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var preloader: Preloader? = null

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

        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
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
            }
        })

        preloader = Preloader(this, player, dataSourceFactory).also { it.start() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player != null) QueueStore.save(player)
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.player?.let { QueueStore.save(it) }
        preloader?.release()
        preloader = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private class SessionCallback : MediaSession.Callback {
        /** Der Controller schickt Items mit mediaId = URL; hier wird daraus die abspielbare URI. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { it.buildUpon().setUri(it.mediaId).build() }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }
}
