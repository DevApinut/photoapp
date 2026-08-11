package com.fieldphoto.app.media

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.media.ExifInterface
import android.location.Geocoder
import java.io.IOException
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class StoredPhoto(val uri: Uri, val relativePath: String, val filename: String, val sha256: String)
data class RecentImage(val uri: Uri, val capturedAtMillis: Long)
data class RecentVideo(val uri: Uri, val capturedAtMillis: Long)
data class GalleryImageInfo(val filename: String, val relativePath: String)
data class PhotoExif(val capturedAt: OffsetDateTime?, val latitude: Double?, val longitude: Double?)

class MediaStoreManager(private val context: Context) {
    private val resolver = context.contentResolver

    fun newDestination(client: String, job: String, location: String, capturedAt: OffsetDateTime): Triple<Uri, String, String> {
        val relative = relativePath(client, job, location)
        val base = capturedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US))
        var sequence = 0
        while (true) {
            val name = if (sequence == 0) "$base.jpg" else "%s_%03d.jpg".format(base, sequence)
            val exists = resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} = ?",
                arrayOf(relative, name), null
            )?.use { it.moveToFirst() } == true
            if (exists) { sequence++; continue }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, relative)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Cannot create MediaStore item")
            return Triple(uri, relative, name)
        }
    }

    fun relativePath(client: String, job: String, location: String): String {
        val hierarchy = if (client == "งานทั่วไป") {
            listOf(safe(job), safePath(location)).filter { it.isNotBlank() }.joinToString("/")
        } else {
            "${safe(client)}/${safe(job)}/${safe(location)}"
        }
        return "${Environment.DIRECTORY_PICTURES}/MyPhotoApp/$hierarchy/"
    }

    fun readExif(uri: Uri): PhotoExif = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val exif = ExifInterface(descriptor.fileDescriptor)
            val dateText = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            val offsetText = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
            val local = dateText?.let { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)) }
            val captured = local?.let {
                if (!offsetText.isNullOrBlank()) OffsetDateTime.parse("${it}${offsetText}")
                else it.atZone(ZoneId.systemDefault()).toOffsetDateTime()
            }
            val coordinates = FloatArray(2)
            val hasGps = exif.getLatLong(coordinates)
            val latitude = coordinates[0].toDouble()
            val longitude = coordinates[1].toDouble()
            val validGps = hasGps && latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
                !(latitude == 0.0 && longitude == 0.0)
            PhotoExif(captured, latitude.takeIf { validGps }, longitude.takeIf { validGps })
        }
    }.getOrNull() ?: PhotoExif(null, null, null)

    fun finish(uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }

    fun cancel(uri: Uri) { resolver.delete(uri, null, null) }

    fun hasContent(uri: Uri): Boolean = runCatching {
        if (!canRequestDelete(uri)) {
            return@runCatching resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length > 0L) true
                else resolver.openInputStream(uri)?.use { it.read() >= 0 } == true
            } == true
        }
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.IS_PENDING)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.Images.Media.IS_TRASHED)
        }.toTypedArray()
        val visible = resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            val pending = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING)) != 0
            val trashed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_TRASHED)) != 0
            } else false
            !pending && !trashed
        } == true
        if (!visible) return@runCatching false
        resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            if (descriptor.length > 0L) true
            else resolver.openInputStream(uri)?.use { it.read() >= 0 } == true
        } == true
    }.getOrDefault(false)

    fun canRequestDelete(uri: Uri): Boolean {
        val parts = uri.pathSegments
        return uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY &&
            parts.none { it.equals("picker", ignoreCase = true) } &&
            parts.size == 4 && parts[1] == "images" && parts[2] == "media" && parts[3].toLongOrNull() != null
    }

    fun replaceContent(source: Uri, target: Uri) {
        resolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Cannot read camera result" }
            resolver.openOutputStream(target, "w").use { output ->
                requireNotNull(output) { "Cannot write camera result" }
                input.copyTo(output, 1024 * 1024)
            }
        }
    }

    fun imagesAddedSince(startedAtMillis: Long): List<RecentImage> {
        val results = mutableListOf<RecentImage>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        val startedSeconds = (startedAtMillis / 1000L) - 2L
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.IS_PENDING} = 0",
            arrayOf(startedSeconds.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} ASC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val path = cursor.getString(pathColumn).orEmpty()
                if (path.contains("MyPhotoApp", ignoreCase = true)) continue
                val id = cursor.getLong(idColumn)
                val addedMillis = cursor.getLong(addedColumn) * 1000L
                val takenMillis = cursor.getLong(takenColumn).takeIf { it > 0L } ?: addedMillis
                results += RecentImage(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), takenMillis)
            }
        }
        return results
    }

    fun videosAddedSince(startedAtMillis: Long): List<RecentVideo> {
        val results = mutableListOf<RecentVideo>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.RELATIVE_PATH,
        )
        val startedSeconds = (startedAtMillis / 1000L) - 2L
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Video.Media.DATE_ADDED} >= ? AND ${MediaStore.Video.Media.IS_PENDING} = 0",
            arrayOf(startedSeconds.toString()), "${MediaStore.Video.Media.DATE_ADDED} ASC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                if (cursor.getString(pathColumn).orEmpty().contains("MyPhotoApp", ignoreCase = true)) continue
                val addedMillis = cursor.getLong(addedColumn) * 1000L
                val takenMillis = cursor.getLong(takenColumn).takeIf { it > 0L } ?: addedMillis
                results += RecentVideo(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn)), takenMillis)
            }
        }
        return results
    }

    fun galleryInfo(uri: Uri): GalleryImageInfo {
        if (canRequestDelete(uri)) {
            runCatching {
                resolver.query(
                    uri,
                    arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                        val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)).orEmpty()
                        return GalleryImageInfo(name, path)
                    }
                }
            }
        }
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    return GalleryImageInfo(name, "Gallery")
                }
            }
        }
        return GalleryImageInfo(uri.lastPathSegment ?: "gallery-image.jpg", "Gallery")
    }

    fun createStampedCopy(
        source: Uri,
        relativePath: String,
        originalFilename: String,
        client: String,
        job: String,
        location: String,
        capturedAt: OffsetDateTime,
        latitude: Double?,
        longitude: Double?,
        accuracy: Float?,
    ): StoredPhoto {
        val requested = originalFilename.substringBeforeLast('.') + "_STAMP.jpg"
        val (target, filename) = createNamedDestination(relativePath, requested)
        try {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, source)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
            }
            drawStamp(bitmap, client, job, location, capturedAt, latitude, longitude, accuracy)
            resolver.openOutputStream(target, "w").use { output ->
                requireNotNull(output) { "Cannot create stamped photo" }
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)) { "Cannot save stamped photo" }
            }
            bitmap.recycle()
            finish(target)
            resolver.update(target, ContentValues().apply {
                put(MediaStore.Images.Media.DATE_TAKEN, capturedAt.toInstant().toEpochMilli())
                put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
            }, null, null)
            resolver.notifyChange(target, null)
            return StoredPhoto(target, relativePath, filename, sha256(target))
        } catch (error: Throwable) {
            cancel(target)
            throw error
        }
    }

    private fun createNamedDestination(relative: String, requested: String): Pair<Uri, String> {
        val stem = requested.substringBeforeLast('.')
        var sequence = 0
        while (true) {
            val name = if (sequence == 0) requested else "%s_%03d.jpg".format(stem, sequence)
            val exists = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} = ?",
                arrayOf(relative, name), null)?.use { it.moveToFirst() } == true
            if (exists) { sequence++; continue }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, relative)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }) ?: throw IOException("Cannot create stamped MediaStore item")
            return uri to name
        }
    }

    private fun drawStamp(
        bitmap: Bitmap, client: String, job: String, location: String, capturedAt: OffsetDateTime,
        latitude: Double?, longitude: Double?, accuracy: Float?
    ) {
        val canvas = Canvas(bitmap)
        val preferences = context.getSharedPreferences("timestamp_settings", Context.MODE_PRIVATE)
        val fontScale = preferences.getInt("font_percent", 100).coerceIn(60, 180) / 100f
        val size = ((bitmap.width / 38f).coerceIn(28f, 72f) * fontScale).coerceIn(18f, 130f)
        val padding = size * 0.55f
        val rawLines = buildList {
            if (preferences.getBoolean("show_work", true)) add(
                if (client == "งานทั่วไป") listOf(job, location).filter { it.isNotBlank() }.joinToString(" / ")
                else listOf(client, job, location).filter { it.isNotBlank() }.joinToString(" / ")
            )
            val showDate = preferences.getBoolean("show_date", true)
            val showTime = preferences.getBoolean("show_time", true)
            if (showDate || showTime) add(listOfNotNull(
                capturedAt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)).takeIf { showDate },
                capturedAt.format(DateTimeFormatter.ofPattern("HH:mm:ss XXX", Locale.US)).takeIf { showTime }
            ).joinToString("  "))
            if (preferences.getBoolean("show_coordinates", true) && latitude != null && longitude != null)
                add("Lat %.6f   Lon %.6f".format(Locale.US, latitude, longitude))
            if (preferences.getBoolean("show_accuracy", true))
                add(if (accuracy != null) "Accuracy +/- %.1f m".format(Locale.US, accuracy) else "GPS unavailable")
            if (preferences.getBoolean("show_address", false)) {
                add(
                    when {
                        latitude == null || longitude == null -> "ที่อยู่: ไม่มีพิกัด GPS ในรูป"
                        else -> reverseAddress(latitude, longitude)?.let { "ที่อยู่: $it" }
                            ?: "ที่อยู่: ค้นหาชื่อสถานที่ไม่สำเร็จ"
                    }
                )
            }
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer((size * 0.12f).coerceAtLeast(3f), 1.5f, 1.5f, Color.BLACK)
        }
        val maxTextWidth = bitmap.width - padding * 2
        val lines = rawLines.flatMap { line -> wrapStampLine(line, textPaint, maxTextWidth) }
        val lineHeight = size * 1.3f
        val boxHeight = padding * 2 + lineHeight * lines.size
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, padding, bitmap.height - boxHeight + padding + size + index * lineHeight, textPaint)
        }
    }

    private fun wrapStampLine(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (paint.measureText(text) <= maxWidth) return listOf(text)
        val result = mutableListOf<String>(); var current = ""
        text.split(Regex("\\s+")).forEach { word ->
            val candidate = if (current.isBlank()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) current = candidate
            else { if (current.isNotBlank()) result += current; current = word }
        }
        if (current.isNotBlank()) result += current
        return result.ifEmpty { listOf(text) }
    }

    @Suppress("DEPRECATION")
    private fun reverseAddress(latitude: Double, longitude: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return@runCatching null
        val address = Geocoder(context, Locale("th", "TH")).getFromLocation(latitude, longitude, 1)?.firstOrNull()
            ?: return@runCatching null
        address.getAddressLine(0)?.trim()?.takeIf { it.isNotBlank() } ?: listOf(
            address.featureName, address.subThoroughfare, address.thoroughfare,
            address.subLocality, address.locality, address.subAdminArea, address.adminArea,
            address.postalCode, address.countryName
        ).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }.distinct().joinToString(" ").ifBlank { null }
    }.getOrNull()

    fun copyOriginal(source: Uri, client: String, job: String, location: String, capturedAt: OffsetDateTime): StoredPhoto {
        val (target, relative, filename) = newDestination(client, job, location, capturedAt)
        try {
            resolver.openInputStream(source).use { input ->
                requireNotNull(input) { "Cannot read selected image" }
                resolver.openOutputStream(target, "w").use { output ->
                    requireNotNull(output) { "Cannot create destination image" }
                    input.copyTo(output, 1024 * 1024)
                }
            }
            finish(target)
            return StoredPhoto(target, relative, filename, sha256(target))
        } catch (error: Throwable) {
            cancel(target)
            throw error
        }
    }

    fun sha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot read saved image" }
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun safe(value: String) = value.trim().replace(Regex("[<>:\"/\\\\|?*]"), "_").trimEnd('.', ' ').ifBlank { "Unnamed" }
    private fun safePath(value: String) = value.split('/').map { safe(it) }.filter { it != "Unnamed" }.joinToString("/")
}
