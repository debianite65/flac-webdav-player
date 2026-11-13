package app.flacplayer.webdav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Metadaten- und Cover-Verwaltung: liest Tags/Cover aus dem FLAC-Header, legt verkleinerte
 * Cover auf der Platte ab (cacheDir/covers) und merkt sich die Tags dauerhaft.
 */
object Library {

    private const val COVER_MAX_PX = 600
    private const val SEP = "\u0001"

    private val app get() = FlacApp.instance
    private val prefs get() = app.getSharedPreferences("meta_cache", Context.MODE_PRIVATE)

    private val mem = ConcurrentHashMap<String, TrackMeta>()
    private val gate = Semaphore(4)

    private val bitmaps = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun coversDir(): File = File(app.cacheDir, "covers").apply { mkdirs() }

    fun coverFile(trackUrl: String): File = File(coversDir(), sha1(trackUrl) + ".jpg")

    // ---------------------------------------------------------------- Metadaten

    fun cachedMeta(url: String): TrackMeta? {
        mem[url]?.let { return it }
        val raw = prefs.getString(url, null) ?: return null
        return decode(raw)?.also { mem[url] = it }
    }

    /**
     * Liefert Tags + legt das Cover an. [folderCoverUrl] ist ein Bild im selben Ordner
     * (cover.jpg o. ä.) und dient als Ersatz, wenn die Datei kein eingebettetes Cover hat.
     */
    suspend fun loadMeta(url: String, folderCoverUrl: String?): TrackMeta {
        cachedMeta(url)?.let { if (!it.hasCover || coverFile(url).exists()) return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                val header = FlacReader.read(url)
                    ?: return@withContext TrackMeta(null, null, null, null, null, false)
                val target = coverFile(url)
                var hasCover = false
                if (header.picture != null) hasCover = saveScaled(header.picture, target)
                if (!hasCover && folderCoverUrl != null) hasCover = copyFolderCover(folderCoverUrl, target)
                val meta = header.meta.copy(hasCover = hasCover)
                mem[url] = meta
                prefs.edit().putString(url, encode(meta)).apply()
                meta
            }
        }
    }

    // ---------------------------------------------------------------- Cover

    /** Cover als Bitmap (höchstens ca. [maxPx] Pixel), oder null wenn keines vorhanden ist. */
    suspend fun bitmap(trackUrl: String, maxPx: Int): Bitmap? {
        val key = "$trackUrl@$maxPx"
        bitmaps.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = coverFile(trackUrl)
            if (!file.exists()) {
                null
            } else {
                decodeFile(file, maxPx)?.also { bitmaps.put(key, it) }
            }
        }
    }

    private fun decodeFile(file: File, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
        }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    private fun sampleSize(w: Int, h: Int, target: Int): Int {
        var sample = 1
        val longest = maxOf(w, h)
        while (longest / (sample * 2) >= target) sample *= 2
        return sample
    }

    private fun saveScaled(bytes: ByteArray, target: File): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, COVER_MAX_PX)
            }
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return false
            val longest = maxOf(bmp.width, bmp.height)
            if (longest > COVER_MAX_PX) {
                val f = COVER_MAX_PX.toFloat() / longest
                bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
            }
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            tmp.renameTo(target) || run { tmp.copyTo(target, overwrite = true); tmp.delete(); true }
        } catch (e: Exception) {
            false
        }
    }

    private fun copyFolderCover(coverUrl: String, target: File): Boolean {
        return try {
            val folderFile = File(coversDir(), "f_" + sha1(coverUrl) + ".jpg")
            if (!folderFile.exists()) {
                val request = Request.Builder().url(coverUrl).build()
                val bytes = Net.client.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) return false
                    val body = r.body ?: return false
                    if (body.contentLength() > 12L * 1024 * 1024) return false
                    body.bytes()
                }
                if (!saveScaled(bytes, folderFile)) return false
            }
            folderFile.copyTo(target, overwrite = true)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------- Aufräumen

    fun clear() {
        mem.clear()
        prefs.edit().clear().apply()
        bitmaps.evictAll()
        File(app.cacheDir, "covers").deleteRecursively()
    }

    // ---------------------------------------------------------------- Hilfen

    private fun encode(m: TrackMeta): String = listOf(
        m.title.orEmpty(), m.artist.orEmpty(), m.album.orEmpty(),
        m.track?.toString().orEmpty(), m.disc?.toString().orEmpty(), if (m.hasCover) "1" else "0",
    ).joinToString(SEP)

    private fun decode(raw: String): TrackMeta? {
        val p = raw.split(SEP)
        if (p.size < 6) return null
        return TrackMeta(
            title = p[0].ifEmpty { null },
            artist = p[1].ifEmpty { null },
            album = p[2].ifEmpty { null },
            track = p[3].toIntOrNull(),
            disc = p[4].toIntOrNull(),
            hasCover = p[5] == "1",
        )
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Sortierung "natürlich": Track2 vor Track10. */
fun naturalCompare(a: String, b: String): Int {
    val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
    val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(ra.size, rb.size)) {
        val x = ra[i]
        val y = rb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) {
            val nx = x.trimStart('0').ifEmpty { "0" }
            val ny = y.trimStart('0').ifEmpty { "0" }
            if (nx.length != ny.length) nx.length.compareTo(ny.length) else nx.compareTo(ny)
        } else {
            x.compareTo(y)
        }
        if (c != 0) return c
    }
    return ra.size.compareTo(rb.size)
}
