package app.flacplayer.webdav

import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class TrackMeta(
    val title: String?,
    val artist: String?,
    val album: String?,
    val track: Int?,
    val disc: Int?,
    val hasCover: Boolean,
)

class FlacHeader(val meta: TrackMeta, val picture: ByteArray?)

/**
 * Liest nur die Metadaten-Blöcke am Anfang einer FLAC-Datei per HTTP (nicht die ganze Datei):
 * Vorbis-Kommentare (Titel, Künstler, Album, Tracknummer) und das eingebettete Cover (PICTURE).
 */
object FlacReader {

    private const val TYPE_VORBIS_COMMENT = 4
    private const val TYPE_PICTURE = 6
    private const val MAX_TOTAL_BYTES = 24L * 1024 * 1024
    private const val MAX_COMMENT_BYTES = 1L * 1024 * 1024
    private const val MAX_PICTURE_BYTES = 16L * 1024 * 1024

    fun read(url: String): FlacHeader? {
        return try {
            val request = Request.Builder().url(url).header("Range", "bytes=0-").build()
            Net.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val source = response.body?.source() ?: return null
                if (source.readUtf8(4) != "fLaC") return null

                var title: String? = null
                var artist: String? = null
                var album: String? = null
                var track: Int? = null
                var disc: Int? = null
                var picture: ByteArray? = null
                var pictureType = -1
                var total = 4L

                while (total < MAX_TOTAL_BYTES) {
                    val header = source.readByte().toInt() and 0xFF
                    val last = (header and 0x80) != 0
                    val type = header and 0x7F
                    val length = ((source.readByte().toInt() and 0xFF) shl 16) or
                        ((source.readByte().toInt() and 0xFF) shl 8) or
                        (source.readByte().toInt() and 0xFF)
                    total += 4 + length

                    when {
                        type == TYPE_VORBIS_COMMENT && length <= MAX_COMMENT_BYTES -> {
                            val c = parseVorbisComment(source.readByteArray(length.toLong()))
                            title = c["TITLE"] ?: title
                            artist = c["ARTIST"] ?: c["ALBUMARTIST"] ?: artist
                            album = c["ALBUM"] ?: album
                            track = leadingInt(c["TRACKNUMBER"]) ?: track
                            disc = leadingInt(c["DISCNUMBER"]) ?: disc
                        }
                        type == TYPE_PICTURE && length <= MAX_PICTURE_BYTES -> {
                            val parsed = parsePicture(source.readByteArray(length.toLong()))
                            // Vorderseiten-Cover (Typ 3) hat Vorrang, sonst das erste Bild.
                            if (parsed != null && (picture == null || (parsed.first == 3 && pictureType != 3))) {
                                picture = parsed.second
                                pictureType = parsed.first
                            }
                        }
                        else -> source.skip(length.toLong())
                    }
                    if (last) break
                }
                FlacHeader(
                    TrackMeta(title, artist, album, track, disc, picture != null),
                    picture,
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun leadingInt(value: String?): Int? =
        value?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()

    private fun parseVorbisComment(data: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val vendorLen = buf.int
            buf.position(buf.position() + vendorLen)
            val count = buf.int
            repeat(count) {
                val len = buf.int
                if (len < 0 || len > buf.remaining()) return out
                val bytes = ByteArray(len)
                buf.get(bytes)
                val text = String(bytes, Charsets.UTF_8)
                val eq = text.indexOf('=')
                if (eq > 0) {
                    val key = text.substring(0, eq).uppercase()
                    if (!out.containsKey(key)) out[key] = text.substring(eq + 1)
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Liefert (Bildtyp, Bilddaten) oder null. Aufbau: FLAC-PICTURE-Block, Big Endian. */
    private fun parsePicture(data: ByteArray): Pair<Int, ByteArray>? {
        return try {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val type = buf.int
            val mimeLen = buf.int
            buf.position(buf.position() + mimeLen)
            val descLen = buf.int
            buf.position(buf.position() + descLen)
            buf.position(buf.position() + 16) // Breite, Höhe, Farbtiefe, Farbanzahl
            val dataLen = buf.int
            if (dataLen <= 0 || dataLen > buf.remaining()) return null
            val bytes = ByteArray(dataLen)
            buf.get(bytes)
            Pair(type, bytes)
        } catch (_: Exception) {
            null
        }
    }
}
