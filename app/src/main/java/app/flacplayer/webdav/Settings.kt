package app.flacplayer.webdav

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Alle Einstellungen. Die Datei ist über den Android Keystore verschlüsselt
 * (EncryptedSharedPreferences), damit das Server-Passwort nicht im Klartext liegt.
 */
class Settings(private val context: Context) {

    private val prefs: SharedPreferences = createPrefs()

    private fun createPrefs(): SharedPreferences {
        fun create(): SharedPreferences {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        return try {
            create()
        } catch (e: Exception) {
            // Beschädigte/nicht entschlüsselbare Datei (z. B. nach Backup-Restore): neu anlegen.
            context.deleteSharedPreferences(FILE)
            create()
        }
    }

    /** Basis-URL des WebDAV-Servers, immer mit abschließendem "/" (oder leer). */
    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(v) {
            val t = v.trim()
            prefs.edit().putString("server_url", if (t.isEmpty() || t.endsWith("/")) t else "$t/").apply()
        }

    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(v) = prefs.edit().putString("username", v.trim()).apply()

    var password: String
        get() = prefs.getString("password", "") ?: ""
        set(v) = prefs.edit().putString("password", v).apply()

    /** Obergrenze des Audio-Caches in MB. */
    var cacheMb: Int
        get() = prefs.getInt("cache_mb", 2048)
        set(v) = prefs.edit().putInt("cache_mb", v).apply()

    var preloadEnabled: Boolean
        get() = prefs.getBoolean("preload_enabled", true)
        set(v) = prefs.edit().putBoolean("preload_enabled", v).apply()

    /** Wenn true: nur über ungetaktete Netze (WLAN) vorladen. Standard: aus (auch mobil vorladen). */
    var preloadWifiOnly: Boolean
        get() = prefs.getBoolean("preload_wifi_only", false)
        set(v) = prefs.edit().putBoolean("preload_wifi_only", v).apply()

    var lastFolder: String
        get() = prefs.getString("last_folder", "") ?: ""
        set(v) = prefs.edit().putString("last_folder", v).apply()

    var lastTrackUrl: String
        get() = prefs.getString("last_track_url", "") ?: ""
        set(v) = prefs.edit().putString("last_track_url", v).apply()

    var lastTrackTitle: String
        get() = prefs.getString("last_track_title", "") ?: ""
        set(v) = prefs.edit().putString("last_track_title", v).apply()

    val isConfigured: Boolean
        get() = serverUrl.isNotEmpty()

    companion object {
        private const val FILE = "flacplayer_secure_prefs"
    }
}
