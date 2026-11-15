package app.flacplayer.webdav

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Speichert die komplette Warteschlange (alle Titel, aktueller Titel, Position, Shuffle/Repeat)
 * in einer kleinen JSON-Datei, damit sie nach dem Schließen der App wieder da ist.
 */
object QueueStore {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "flac-queue-store").apply { isDaemon = true }
    }

    private fun file() = File(FlacApp.instance.filesDir, "queue.json")

    /** Muss auf dem Player-Thread (Main) aufgerufen werden; das Schreiben läuft im Hintergrund. */
    fun save(player: Player) {
        val count = player.mediaItemCount
        val target = file()
        if (count == 0) {
            executor.execute { runCatching { target.delete() } }
            return
        }
        val items = JSONArray()
        for (i in 0 until count) {
            val item = player.getMediaItemAt(i)
            val md = item.mediaMetadata
            items.put(
                JSONObject()
                    .put("id", item.mediaId)
                    .put("title", md.title?.toString().orEmpty())
                    .put("artist", md.artist?.toString().orEmpty())
                    .put("album", md.albumTitle?.toString().orEmpty()),
            )
        }
        val text = JSONObject()
            .put("items", items)
            .put("index", player.currentMediaItemIndex)
            .put("pos", player.currentPosition.coerceAtLeast(0))
            .put("shuffle", player.shuffleModeEnabled)
            .put("repeat", player.repeatMode)
            .toString()
        executor.execute {
            runCatching {
                val tmp = File(target.path + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(target)) {
                    target.writeText(text)
                    tmp.delete()
                }
            }
        }
    }

    /** Stellt die gespeicherte Warteschlange her (pausiert). Gibt true zurück, wenn etwas geladen wurde. */
    fun restore(player: Player): Boolean {
        return try {
            val f = file()
            if (!f.exists()) return false
            val root = JSONObject(f.readText())
            val arr = root.getJSONArray("items")
            val items = ArrayList<MediaItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                val metadata = MediaMetadata.Builder()
                    .setTitle(o.optString("title").ifEmpty { id.substringAfterLast('/').substringBeforeLast('.') })
                    .setArtist(o.optString("artist").ifEmpty { null })
                    .setAlbumTitle(o.optString("album").ifEmpty { null })
                    .build()
                items += MediaItem.Builder().setMediaId(id).setUri(id).setMediaMetadata(metadata).build()
            }
            if (items.isEmpty()) return false
            player.shuffleModeEnabled = root.optBoolean("shuffle", false)
            player.repeatMode = root.optInt("repeat", Player.REPEAT_MODE_OFF)
            val index = root.optInt("index", 0).coerceIn(0, items.size - 1)
            player.setMediaItems(items, index, root.optLong("pos", 0L))
            player.playWhenReady = false
            player.prepare()
            true
        } catch (e: Exception) {
            false
        }
    }
}
