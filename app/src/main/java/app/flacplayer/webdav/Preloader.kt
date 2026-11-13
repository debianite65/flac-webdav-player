package app.flacplayer.webdav

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import java.util.concurrent.Executors

/**
 * Lädt die nächsten [WINDOW] Titel der Warteschlange vollständig in den Audio-Cache
 * ("gleitendes Fenster"): Wechselt der Titel, ändert sich das Fenster und der nächste
 * fehlende Titel kommt dazu. Berücksichtigt Shuffle und Repeat der Warteschlange.
 *
 * Alle Methoden außer dem Download selbst laufen auf dem Application-Thread des Players.
 */
class Preloader(
    private val context: Context,
    private val player: Player,
    private val dataSourceFactory: CacheDataSource.Factory,
) : Player.Listener {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "flac-preload").apply { isDaemon = true } }
    private val cache get() = FlacApp.instance.audioCache

    @Volatile private var generation = 0

    @Volatile private var currentWriter: CacheWriter? = null

    fun start() {
        player.addListener(this)
        schedule()
    }

    fun release() {
        player.removeListener(this)
        generation++
        currentWriter?.cancel()
        executor.shutdownNow()
    }

    // Player.Listener ---------------------------------------------------------

    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = schedule()
    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = schedule()
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = schedule()
    override fun onRepeatModeChanged(repeatMode: Int) = schedule()

    // -------------------------------------------------------------------------

    fun schedule() {
        val settings = FlacApp.instance.settings
        generation++
        currentWriter?.cancel()

        if (!settings.preloadEnabled) return
        if (settings.preloadWifiOnly && isMetered()) return

        val timeline = player.currentTimeline
        if (timeline.isEmpty) return

        val urls = ArrayList<String>(WINDOW)
        val seen = HashSet<Int>()
        val start = player.currentMediaItemIndex
        seen += start
        var index = start
        while (urls.size < WINDOW) {
            index = timeline.getNextWindowIndex(index, player.repeatMode, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET || !seen.add(index)) break
            urls += player.getMediaItemAt(index).mediaId
        }
        if (urls.isEmpty()) return

        val myGeneration = generation
        executor.execute { download(urls, myGeneration) }
    }

    private fun download(urls: List<String>, myGeneration: Int) {
        for (url in urls) {
            if (generation != myGeneration) return
            try {
                val length = ContentMetadata.getContentLength(cache.getContentMetadata(url))
                if (length != C.LENGTH_UNSET.toLong() && cache.isCached(url, 0, length)) continue

                val writer = CacheWriter(
                    dataSourceFactory.createDataSource(),
                    DataSpec.Builder().setUri(url).setKey(url).build(),
                    null,
                    null,
                )
                currentWriter = writer
                if (generation != myGeneration) {
                    return
                }
                writer.cache()
            } catch (e: Exception) {
                // Abbruch durch Fenster-Wechsel oder Netzfehler: beim nächsten Wechsel neu versuchen.
                Log.d(TAG, "Vorladen abgebrochen: ${e.javaClass.simpleName} ${e.message}")
                if (generation == myGeneration) return
            } finally {
                currentWriter = null
            }
        }
    }

    private fun isMetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.isActiveNetworkMetered
    }

    companion object {
        const val WINDOW = 15
        private const val TAG = "FlacPreloader"
    }
}
