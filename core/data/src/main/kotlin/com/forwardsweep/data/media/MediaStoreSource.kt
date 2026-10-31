package com.forwardsweep.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.forwardsweep.classifier.ImageMeta
import com.forwardsweep.classifier.MediaType
import com.forwardsweep.data.db.MediaSource

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val relativePath: String?,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateTaken: Long,
    val dateModified: Long,
    val mimeType: String?,
    val source: MediaSource,
    val mediaType: MediaType = MediaType.IMAGE,
    val durationMs: Long = 0,
) {
    fun toMeta() = ImageMeta(uri.toString(), displayName, width, height, sizeBytes, mimeType, relativePath, mediaType, durationMs)
}

enum class ScanScope { WHATSAPP_ONLY, ALL_IMAGES }

/**
 * Reads images and videos through MediaStore only (READ_MEDIA_IMAGES / READ_MEDIA_VIDEO /
 * READ_EXTERNAL_STORAGE). No MANAGE_EXTERNAL_STORAGE, so files in folders containing `.nomedia`
 * (WhatsApp's "Sent" and "Private" folders on most versions) are invisible to us. See README
 * "Known limitations". Without video permission the video query simply returns nothing.
 */
class MediaStoreSource(private val resolver: ContentResolver) {

    fun query(scope: ScanScope, includeVideos: Boolean = true): List<MediaItem> =
        query(MediaType.IMAGE, scope) + if (includeVideos) query(MediaType.VIDEO, scope) else emptyList()

    private fun query(type: MediaType, scope: ScanScope): List<MediaItem> {
        val collection = collection(type)
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH
        else @Suppress("DEPRECATION") MediaStore.MediaColumns.DATA
        val projection = listOfNotNull(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            pathColumn,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            if (type == MediaType.VIDEO) MediaStore.Video.VideoColumns.DURATION else null,
        ).toTypedArray()
        val (selection, args) = when (scope) {
            ScanScope.ALL_IMAGES -> null to null
            ScanScope.WHATSAPP_ONLY -> {
                val patterns = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type == MediaType.VIDEO -> VIDEO_RELATIVE_PATTERNS
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> RELATIVE_PATTERNS
                    type == MediaType.VIDEO -> VIDEO_DATA_PATTERNS
                    else -> DATA_PATTERNS
                }
                patterns.joinToString(" OR ") { "$pathColumn LIKE ?" } to patterns.toTypedArray()
            }
        }
        val out = ArrayList<MediaItem>()
        resolver.query(collection, projection, selection, args, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathCol = c.getColumnIndexOrThrow(pathColumn)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val wCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val modCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val durCol = if (type == MediaType.VIDEO) c.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION) else -1
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val path = c.getString(pathCol)
                out += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = c.getString(nameCol) ?: "${type.name.lowercase()}_$id",
                    relativePath = path,
                    sizeBytes = c.getLong(sizeCol),
                    width = c.getInt(wCol),
                    height = c.getInt(hCol),
                    dateTaken = c.getLong(takenCol),
                    dateModified = c.getLong(modCol),
                    mimeType = c.getString(mimeCol),
                    source = sourceOf(path),
                    mediaType = type,
                    durationMs = if (durCol >= 0) c.getLong(durCol) else 0,
                )
            }
        }
        return out
    }

    /**
     * URIs (of [uris]) that still exist and aren't trashed. Used to confirm deletions.
     * Queries each collection: a query on a single item's URI also returns trashed items.
     */
    fun stillPresent(uris: List<Uri>): Set<Uri> =
        uris.groupBy { typeOf(it) }.flatMapTo(HashSet()) { (type, group) -> stillPresent(collection(type), group) }

    private fun stillPresent(collection: Uri, uris: List<Uri>): Set<Uri> {
        val byId = uris.associateBy { ContentUris.parseId(it) }
        val present = HashSet<Uri>()
        for (chunk in byId.keys.chunked(500)) {
            val selection = "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",") { "?" }})"
            val args = chunk.map(Long::toString).toTypedArray()
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
                }, null)
            } else {
                resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
            }
            cursor?.use { c -> while (c.moveToNext()) byId[c.getLong(0)]?.let(present::add) }
        }
        return present
    }

    companion object {
        // Android 11+ location, older location (pre-scoped-storage WhatsApp), and WhatsApp Business.
        val RELATIVE_PATTERNS = listOf(
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/%",
            "WhatsApp/Media/WhatsApp Images/%",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images/%",
            "WhatsApp Business/Media/WhatsApp Business Images/%",
        )
        val DATA_PATTERNS = listOf(
            "%/WhatsApp/Media/WhatsApp Images/%",
            "%/WhatsApp Business/Media/WhatsApp Business Images/%",
        )

        /** Videos, plus GIFs, which WhatsApp stores as short MP4s in their own folder. */
        val VIDEO_RELATIVE_PATTERNS = listOf(
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/%",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Animated Gifs/%",
            "WhatsApp/Media/WhatsApp Video/%",
            "WhatsApp/Media/WhatsApp Animated Gifs/%",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Video/%",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Animated Gifs/%",
            "WhatsApp Business/Media/WhatsApp Business Video/%",
            "WhatsApp Business/Media/WhatsApp Business Animated Gifs/%",
        )
        val VIDEO_DATA_PATTERNS = listOf(
            "%/WhatsApp/Media/WhatsApp Video/%",
            "%/WhatsApp/Media/WhatsApp Animated Gifs/%",
            "%/WhatsApp Business/Media/WhatsApp Business Video/%",
            "%/WhatsApp Business/Media/WhatsApp Business Animated Gifs/%",
        )

        fun collection(type: MediaType): Uri = when (type) {
            MediaType.IMAGE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaType.VIDEO -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        /** content://media/external/video/media/123 → VIDEO. */
        fun typeOf(uri: Uri): MediaType = if (uri.pathSegments.contains("video")) MediaType.VIDEO else MediaType.IMAGE

        fun sourceOf(path: String?): MediaSource = when {
            path == null -> MediaSource.GALLERY
            path.contains("WhatsApp Business") -> MediaSource.WHATSAPP_BUSINESS
            path.contains("WhatsApp") -> MediaSource.WHATSAPP
            else -> MediaSource.GALLERY
        }
    }
}
