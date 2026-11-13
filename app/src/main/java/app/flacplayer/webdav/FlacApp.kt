package app.flacplayer.webdav

import android.app.Application
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

class FlacApp : Application() {

    lateinit var settings: Settings
        private set

    /** Gemeinsamer Audio-Cache (Wiedergabe + Vorladen). Größe wirkt ab dem nächsten App-Start. */
    val audioCache: SimpleCache by lazy {
        SimpleCache(
            File(cacheDir, "audio"),
            LeastRecentlyUsedCacheEvictor(settings.cacheMb * 1024L * 1024L),
            StandaloneDatabaseProvider(this),
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
    }

    /** Leert Audio-Cache, Cover und Metadaten. */
    fun clearAllCaches() {
        val cache = audioCache
        for (key in cache.keys.toList()) {
            try {
                cache.removeResource(key)
            } catch (_: Exception) {
            }
        }
        Library.clear()
    }

    companion object {
        lateinit var instance: FlacApp
            private set
    }
}
