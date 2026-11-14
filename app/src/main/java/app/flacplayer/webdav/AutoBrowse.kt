package app.flacplayer.webdav

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Browse-Baum für Android Auto (und andere Media-Browser):
 *
 *   root
 *    ├─ "resume"  Weiterspielen (gespeicherte Warteschlange)
 *    └─ "d:<Server-URL>"  Musik → Ordner ("d:<URL>") und Titel ("t:<URL>")
 *
 * Ids mit Präfix gelten nur für den Browse-Baum. Spielbare Titel in der Warteschlange
 * haben als mediaId die reine URL (wie in der Handy-App), damit Cache und Speicherung gleich bleiben.
 */
object AutoBrowse {
    const val ROOT = "root"
    const val RESUME = "resume"
    const val DIR = "d:"
    const val TRACK = "t:"

    private const val MAX_THUMBS = 30

    /** Größe der Cover-Bilder für Android Auto (Bitmaps über Binder müssen klein bleiben). */
    const val ART_PX = 320
    private const val LIST_THUMB_PX = 128

    fun rootItem(): MediaItem = MediaItem.Builder()
        .setMediaId(ROOT)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("FlacPlayer")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    fun folderItem(folderUrl: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(DIR + folderUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    fun resumeItem(currentTitle: String?): MediaItem = MediaItem.Builder()
        .setMediaId(RESUME)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Weiterspielen")
                .setSubtitle(currentTitle?.takeIf { it.isNotEmpty() })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()

    private fun displayTitle(entry: DavEntry, m: TrackMeta?) =
        m?.title ?: entry.name.substringBeforeLast('.')

    /** Titel im Browse-Baum (noch nicht abspielbar; wird beim Antippen zur Warteschlange erweitert). */
    fun browseTrackItem(entry: DavEntry, m: TrackMeta?, thumb: ByteArray?): MediaItem {
        val md = MediaMetadata.Builder()
            .setTitle(displayTitle(entry, m))
            .setArtist(m?.artist)
            .setAlbumTitle(m?.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        if (thumb != null) md.setArtworkData(thumb, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        return MediaItem.Builder().setMediaId(TRACK + entry.url).setMediaMetadata(md.build()).build()
    }

    /** Abspielbarer Titel für die Warteschlange (mediaId = reine URL). */
    fun playableItem(entry: DavEntry, m: TrackMeta?): MediaItem {
        val artwork = if (m?.hasCover == true && Library.coverFile(entry.url).exists()) {
            Uri.fromFile(Library.coverFile(entry.url))
        } else {
            null
        }
        return MediaItem.Builder()
            .setMediaId(entry.url)
            .setUri(entry.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(displayTitle(entry, m))
                    .setArtist(m?.artist)
                    .setAlbumTitle(m?.album)
                    .setArtworkUri(artwork)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            )
            .build()
    }

    class FolderListing(val dirs: List<DavEntry>, val tracks: List<DavEntry>, val coverUrl: String?)

    /** Bild im Ordner (cover.jpg o. ä.), das als Ersatz für fehlende eingebettete Cover dient. */
    fun pickCover(list: List<DavEntry>): String? =
        list.filter { it.isCoverImage }.minByOrNull {
            when (it.name.lowercase().substringBeforeLast('.')) {
                "cover" -> 0
                "folder" -> 1
                "front" -> 2
                else -> 3
            }
        }?.url

    /** Ordner lesen: Unterordner und sortierte FLAC-Titel (Sortierung nach Tags, soweit bekannt). */
    suspend fun listFolder(folderUrl: String): FolderListing {
        val list = WebDav.list(folderUrl)
        val dirs = list.filter { it.isDir }.sortedWith { a, b -> naturalCompare(a.name, b.name) }
        val tracks = list.filter { it.isFlac }.sortedWith(
            compareBy<DavEntry>(
                { Library.cachedMeta(it.url)?.disc ?: 0 },
                { Library.cachedMeta(it.url)?.track ?: Int.MAX_VALUE },
            ).thenComparator { a, b -> naturalCompare(a.name, b.name) },
        )
        return FolderListing(dirs, tracks, pickCover(list))
    }

    /**
     * Cover als kleine JPEG-Bytes für einen Titel; lädt Tags/Cover bei Bedarf nach.
     * Gibt null zurück, wenn der Titel kein Cover hat.
     */
    suspend fun coverBytesFor(trackUrl: String, maxPx: Int = ART_PX): ByteArray? {
        var meta = Library.cachedMeta(trackUrl)
        if (meta == null || (meta.hasCover && !Library.coverFile(trackUrl).exists())) {
            val folderCover = try {
                pickCover(WebDav.list(trackUrl.substringBeforeLast('/') + "/"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            meta = Library.loadMeta(trackUrl, folderCover)
        }
        if (!meta.hasCover) return null
        return withContext(Dispatchers.IO) { Library.thumbnailBytes(trackUrl, maxPx) }
    }

    /** Kinder eines Browse-Knotens; null = unbekannte Id. */
    suspend fun children(parentId: String, hasQueue: Boolean, currentTitle: String?): List<MediaItem>? {
        val settings = FlacApp.instance.settings
        return when {
            parentId == ROOT -> buildList {
                if (hasQueue) add(resumeItem(currentTitle))
                if (settings.serverUrl.isNotEmpty()) add(folderItem(settings.serverUrl, "Musik"))
            }
            parentId.startsWith(DIR) -> {
                val listing = listFolder(parentId.removePrefix(DIR))
                withContext(Dispatchers.IO) {
                    // Für Titel ohne bekannte Tags wird nur der erste Titel gelesen (ein kurzer Header-
                    // Abruf); sein Cover dient als Album-Cover für die übrigen Titel des Ordners.
                    var albumThumb: ByteArray? = null
                    val firstUncached = listing.tracks.firstOrNull { Library.cachedMeta(it.url) == null }
                    if (firstUncached != null) {
                        withTimeoutOrNull(8000) { Library.loadMeta(firstUncached.url, listing.coverUrl) }
                        albumThumb = Library.thumbnailBytes(firstUncached.url, LIST_THUMB_PX)
                    }
                    listing.dirs.map { folderItem(it.url, it.name) } +
                        listing.tracks.mapIndexed { i, t ->
                            val own = Library.thumbnailBytes(t.url, LIST_THUMB_PX)
                            val thumb = when {
                                i >= MAX_THUMBS -> null
                                own != null -> own
                                Library.cachedMeta(t.url) == null -> albumThumb
                                else -> null
                            }
                            browseTrackItem(t, Library.cachedMeta(t.url), thumb)
                        }
                }
            }
            else -> null
        }
    }

    /** Warteschlange für einen Ordner (alle FLAC-Dateien darin). */
    suspend fun queueForFolder(folderUrl: String): List<MediaItem> =
        listFolder(folderUrl).tracks.map { playableItem(it, Library.cachedMeta(it.url)) }

    /** Warteschlange für einen angetippten Titel: alle FLAC-Dateien seines Ordners, Start beim Titel. */
    suspend fun queueForTrack(trackUrl: String): Pair<List<MediaItem>, Int> {
        val items = queueForFolder(trackUrl.substringBeforeLast('/') + "/")
        val index = items.indexOfFirst { it.mediaId == trackUrl }.coerceAtLeast(0)
        return items to index
    }
}
