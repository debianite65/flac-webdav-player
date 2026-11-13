package app.flacplayer.webdav

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream

class DavException(message: String) : IOException(message)

data class DavEntry(
    val url: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
) {
    val isFlac: Boolean get() = !isDir && name.endsWith(".flac", ignoreCase = true)

    val isCoverImage: Boolean
        get() {
            if (isDir) return false
            val lower = name.lowercase()
            val ext = lower.substringAfterLast('.', "")
            if (ext != "jpg" && ext != "jpeg" && ext != "png") return false
            val base = lower.substringBeforeLast('.')
            return base in COVER_NAMES
        }

    companion object {
        private val COVER_NAMES = listOf("cover", "folder", "front", "album", "albumart", "albumartsmall")
    }
}

object WebDav {

    private val propfindBody = (
        """<?xml version="1.0" encoding="utf-8"?>""" +
            """<d:propfind xmlns:d="DAV:"><d:prop>""" +
            """<d:displayname/><d:resourcetype/><d:getcontentlength/>""" +
            """</d:prop></d:propfind>"""
        ).toRequestBody("application/xml".toMediaType())

    /** Listet den Inhalt eines Ordners (Depth: 1). Unterordner, Dateien; der Ordner selbst wird ausgelassen. */
    suspend fun list(folderUrl: String): List<DavEntry> = withContext(Dispatchers.IO) {
        val folder = try {
            folderUrl.toHttpUrl()
        } catch (e: IllegalArgumentException) {
            throw DavException("Ungültige Server-Adresse")
        }
        val request = Request.Builder()
            .url(folder)
            .method("PROPFIND", propfindBody)
            .header("Depth", "1")
            .build()
        Net.client.newCall(request).execute().use { response ->
            when {
                response.code == 401 -> throw DavException("Anmeldung fehlgeschlagen (401) – Benutzername/Passwort prüfen")
                response.code == 403 -> throw DavException("Zugriff verweigert (403)")
                response.code == 404 -> throw DavException("Ordner nicht gefunden (404)")
                !response.isSuccessful -> throw DavException("Serverfehler ${response.code}")
            }
            val body = response.body ?: throw DavException("Leere Antwort vom Server")
            parse(body.byteStream(), folder)
        }
    }

    private fun parse(input: InputStream, folder: HttpUrl): List<DavEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)

        val result = mutableListOf<DavEntry>()
        var href: String? = null
        var isCollection = false
        var size = -1L

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "response" -> {
                        href = null
                        isCollection = false
                        size = -1L
                    }
                    "href" -> href = parser.nextText().trim()
                    "collection" -> isCollection = true
                    "getcontentlength" -> size = parser.nextText().trim().toLongOrNull() ?: -1L
                }
                XmlPullParser.END_TAG -> if (parser.name == "response") {
                    val h = href
                    if (h != null) toEntry(h, isCollection, size, folder)?.let { result += it }
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun toEntry(href: String, collection: Boolean, size: Long, folder: HttpUrl): DavEntry? {
        val resolved = folder.resolve(href) ?: return null
        val segments = resolved.pathSegments.filter { it.isNotEmpty() }
        val folderSegments = folder.pathSegments.filter { it.isNotEmpty() }
        if (segments == folderSegments) return null // der Ordner selbst
        val name = segments.lastOrNull() ?: return null
        if (name.startsWith(".")) return null
        val isDir = collection || href.endsWith("/")
        val url = resolved.toString().let { if (isDir && !it.endsWith("/")) "$it/" else it }
        return DavEntry(url = url, name = name, isDir = isDir, size = size)
    }
}
