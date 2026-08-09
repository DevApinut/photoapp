package com.fieldphoto.app.data

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.ContentValues
import android.app.RecoverableSecurityException
import android.os.Build
import android.provider.MediaStore
import android.os.Environment
import android.net.Uri
import com.fieldphoto.app.media.MediaStoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.util.UUID

class PhotoRepository(private val context: Context, val dao: AppDao) {
    val media = MediaStoreManager(context)
    private fun now() = OffsetDateTime.now().toString()

    suspend fun addClient(name: String) { dao.insertClient(ClientEntity(UUID.randomUUID().toString(), name.trim(), now())) }
    suspend fun addJob(clientId: String, name: String) = dao.insertJob(JobEntity(UUID.randomUUID().toString(), clientId, name.trim(), now()))
    suspend fun addLocation(jobId: String, name: String) = dao.insertLocation(LocationEntity(UUID.randomUUID().toString(), jobId, name.trim(), now()))

    suspend fun createQuickJob(name: String): LocationEntity {
        val defaultClient = dao.clientByName("งานทั่วไป") ?: ClientEntity(UUID.randomUUID().toString(), "งานทั่วไป", now()).also {
            dao.insertClient(it)
        }
        val job = JobEntity(UUID.randomUUID().toString(), defaultClient.id, name.trim(), now())
        dao.insertJob(job)
        return LocationEntity(UUID.randomUUID().toString(), job.id, "", now()).also { dao.insertLocation(it) }
    }

    suspend fun createQuickJobFromTemplate(defaultName: String, folderPaths: List<String>): Pair<LocationEntity, String> {
        val defaultClient = dao.clientByName("งานทั่วไป") ?: ClientEntity(UUID.randomUUID().toString(), "งานทั่วไป", now()).also {
            dao.insertClient(it)
        }
        val name = uniqueJobName(defaultClient.id, defaultName.ifBlank { "งานใหม่" })
        val root = createQuickJob(name)
        applyFolderTemplate(root, folderPaths)
        return root to name
    }

    suspend fun renameJob(job: JobEntity, requestedName: String): String = withContext(Dispatchers.IO) {
        val name = uniqueJobName(job.clientId, requestedName.trim().ifBlank { job.name }, job.id)
        dao.renameJob(job.id, name)
        name
    }

    private suspend fun uniqueJobName(clientId: String, requested: String, excludeId: String = ""): String {
        val base = requested.trim()
        if (!dao.jobNameExists(clientId, base, excludeId)) return base
        var number = 2
        while (dao.jobNameExists(clientId, "$base ($number)", excludeId)) number++
        return "$base ($number)"
    }

    suspend fun createQuickJobs(names: List<String>, folderPaths: List<String>): Int = withContext(Dispatchers.IO) {
        var created = 0
        names.map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach { name ->
            runCatching {
                val root = createQuickJob(name)
                applyFolderTemplate(root, folderPaths)
            }.onSuccess { created++ }
        }
        created
    }

    suspend fun applyFolderTemplate(root: LocationEntity, folderPaths: List<String>) = withContext(Dispatchers.IO) {
        val expanded = folderPaths.flatMap { raw ->
            val parts = raw.trim().trim('/').split('/').map { it.trim() }.filter { it.isNotBlank() }
            parts.indices.map { index -> parts.take(index + 1).joinToString("/") }
        }.distinct()
        expanded.forEach { path ->
            runCatching { dao.insertLocation(LocationEntity(UUID.randomUUID().toString(), root.jobId, path, now())) }
        }
    }

    suspend fun addFolder(parent: LocationEntity, name: String): LocationEntity {
        val fullPath = listOf(parent.name, name.trim()).filter { it.isNotBlank() }.joinToString("/")
        return LocationEntity(UUID.randomUUID().toString(), parent.jobId, fullPath, now()).also { dao.insertLocation(it) }
    }

    suspend fun rootForJob(jobId: String): LocationEntity = dao.rootLocation(jobId)
        ?: LocationEntity(UUID.randomUUID().toString(), jobId, "", now()).also { dao.insertLocation(it) }

    suspend fun deleteFolder(folder: LocationEntity): Int = withContext(Dispatchers.IO) {
        require(folder.name.isNotBlank()) { "Cannot delete the job root" }
        val folders = dao.folderTree(folder.jobId, folder.name, "${folder.name}/%")
        var deletedPhotos = 0
        folders.forEach { current ->
            dao.photosNow(current.id).forEach { photo ->
                context.contentResolver.delete(Uri.parse(photo.contentUri), null, null)
                deletedPhotos++
            }
            dao.deleteLocation(current.id)
        }
        deletedPhotos
    }

    suspend fun deleteJob(job: JobEntity): Int = withContext(Dispatchers.IO) {
        var deletedPhotos = 0
        dao.locationsNow(job.id).forEach { folder ->
            dao.photosNow(folder.id).forEach { photo ->
                context.contentResolver.delete(Uri.parse(photo.contentUri), null, null)
                deletedPhotos++
            }
        }
        dao.deleteJob(job.id)
        deletedPhotos
    }

    suspend fun jobBackupSummary(jobId: String): BackupSummary = withContext(Dispatchers.IO) {
        val photos = dao.locationsNow(jobId).flatMap { dao.photosNow(it.id) }
        BackupSummary(
            waiting = photos.count { it.status == UploadStatus.WAITING },
            failed = photos.count { it.status == UploadStatus.ERROR },
            uploaded = photos.count { it.status == UploadStatus.UPLOADED },
        )
    }

    suspend fun jobDeleteApproval(job: JobEntity): IntentSender? = withContext(Dispatchers.IO) {
        val uris = dao.locationsNow(job.id).flatMap { dao.photosNow(it.id) }
            .map { Uri.parse(it.contentUri) }.filter { media.hasContent(it) }
        createBatchDeleteApproval(uris)
    }

    suspend fun jobsDeleteApproval(jobs: List<JobEntity>): IntentSender? = withContext(Dispatchers.IO) {
        val uris = jobs.flatMap { job -> dao.locationsNow(job.id).flatMap { dao.photosNow(it.id) } }
            .map { Uri.parse(it.contentUri) }.filter { media.hasContent(it) }
        createBatchDeleteApproval(uris)
    }

    suspend fun forgetJobs(ids: List<String>) = withContext(Dispatchers.IO) {
        ids.forEach { dao.deleteJob(it) }
    }

    suspend fun folderDeleteApproval(folder: LocationEntity): IntentSender? = withContext(Dispatchers.IO) {
        val uris = dao.folderTree(folder.jobId, folder.name, "${folder.name}/%")
            .flatMap { dao.photosNow(it.id) }.map { Uri.parse(it.contentUri) }.filter { media.hasContent(it) }
        createBatchDeleteApproval(uris)
    }

    suspend fun photosDeleteApproval(photos: List<PhotoEntity>): IntentSender? = withContext(Dispatchers.IO) {
        createBatchDeleteApproval(photos.map { Uri.parse(it.contentUri) }.filter { media.hasContent(it) })
    }

    suspend fun forgetPhotos(ids: List<String>) = withContext(Dispatchers.IO) {
        ids.forEach { dao.deletePhotoRow(it) }
    }

    private fun createBatchDeleteApproval(uris: List<Uri>): IntentSender? {
        // Android's Photo Picker returns proxy URIs (content://media/picker/...).
        // MediaStore.createDeleteRequest accepts only concrete .../images/media/<id> URIs.
        // Picker originals are therefore kept in Gallery while their DN database rows are removed.
        val deletableUris = uris.filter { media.canRequestDelete(it) }.distinct()
        if (deletableUris.isEmpty()) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw IllegalStateException("Android 10 ไม่รองรับการยืนยันลบหลายรูปพร้อมกัน กรุณาลบรูปก่อนลบงาน")
        }
        return MediaStore.createDeleteRequest(context.contentResolver, deletableUris).intentSender
    }

    suspend fun forgetJob(jobId: String) = withContext(Dispatchers.IO) { dao.deleteJob(jobId) }

    suspend fun forgetFolder(folder: LocationEntity) = withContext(Dispatchers.IO) {
        dao.folderTree(folder.jobId, folder.name, "${folder.name}/%").forEach { dao.deleteLocation(it.id) }
    }

    suspend fun importPhoto(
        locationId: String,
        source: Uri,
        capturedAt: OffsetDateTime = OffsetDateTime.now(),
        withStamp: Boolean = false,
        latitude: Double? = null,
        longitude: Double? = null,
        accuracy: Float? = null,
    ) = withContext(Dispatchers.IO) {
        val location = dao.location(locationId)
        val job = dao.job(location.jobId)
        val client = dao.client(job.clientId)
        val stored = media.copyOriginal(source, client.name, job.name, location.name, capturedAt)
        if (withStamp) {
            dao.insertPhoto(PhotoEntity(
                id = UUID.randomUUID().toString(), locationId = locationId, sha256 = stored.sha256,
                contentUri = stored.uri.toString(), relativePath = stored.relativePath, filename = stored.filename,
                capturedAt = capturedAt.toString(), latitude = latitude, longitude = longitude, accuracy = accuracy,
            ))
            val stamped = media.createStampedCopy(stored.uri, stored.relativePath, stored.filename, client.name, job.name,
                location.name, capturedAt, latitude, longitude, accuracy)
            val inserted = dao.insertPhoto(PhotoEntity(
                id = UUID.randomUUID().toString(), locationId = locationId, sha256 = stamped.sha256,
                contentUri = stamped.uri.toString(), relativePath = stamped.relativePath, filename = stamped.filename,
                capturedAt = capturedAt.toString(), latitude = latitude, longitude = longitude, accuracy = accuracy,
            ))
            if (inserted == -1L) context.contentResolver.delete(stamped.uri, null, null)
        } else {
            val inserted = dao.insertPhoto(PhotoEntity(
                id = UUID.randomUUID().toString(), locationId = locationId, sha256 = stored.sha256,
                contentUri = stored.uri.toString(), relativePath = stored.relativePath, filename = stored.filename,
                capturedAt = capturedAt.toString(), latitude = latitude, longitude = longitude, accuracy = accuracy,
            ))
            if (inserted == -1L) context.contentResolver.delete(stored.uri, null, null)
        }
    }

    suspend fun attachGalleryPhoto(
        locationId: String,
        source: Uri,
        capturedAt: OffsetDateTime,
        latitude: Double?,
        longitude: Double?,
        accuracy: Float?,
    ) = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.takePersistableUriPermission(source, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val info = media.galleryInfo(source)
        val hash = media.sha256(source)
        dao.insertPhoto(PhotoEntity(
            id = UUID.randomUUID().toString(), locationId = locationId, sha256 = hash,
            contentUri = source.toString(), relativePath = info.relativePath, filename = info.filename,
            capturedAt = capturedAt.toString(), latitude = latitude, longitude = longitude, accuracy = accuracy,
        ))
    }

    suspend fun createTimestampFromSource(
        locationId: String, source: Uri, originalFilename: String, capturedAt: OffsetDateTime,
        latitude: Double?, longitude: Double?, accuracy: Float?
    ) = withContext(Dispatchers.IO) {
        val location = dao.location(locationId)
        val job = dao.job(location.jobId)
        val client = dao.client(job.clientId)
        val relative = media.relativePath(client.name, job.name, location.name)
        val stamped = media.createStampedCopy(source, relative, originalFilename, client.name, job.name, location.name,
            capturedAt, latitude, longitude, accuracy)
        val inserted = dao.insertPhoto(PhotoEntity(UUID.randomUUID().toString(), locationId, stamped.sha256,
            stamped.uri.toString(), stamped.relativePath, stamped.filename, capturedAt.toString(), latitude, longitude, accuracy))
        if (inserted == -1L) context.contentResolver.delete(stamped.uri, null, null)
    }

    suspend fun createTimestampCopy(photoId: String) = withContext(Dispatchers.IO) {
        val photo = dao.photo(photoId)
        val source = Uri.parse(photo.contentUri)
        val exif = media.readExif(source)
        val capturedAt = exif.capturedAt ?: OffsetDateTime.parse(photo.capturedAt)
        createTimestampFromSource(photo.locationId, source, photo.filename, capturedAt,
            exif.latitude ?: photo.latitude, exif.longitude ?: photo.longitude, photo.accuracy)
    }

    suspend fun reconcileMissingPhotos(locationId: String): Int = withContext(Dispatchers.IO) {
        var removed = 0
        dao.photosNow(locationId).forEach { photo ->
            if (!media.hasContent(Uri.parse(photo.contentUri))) {
                dao.deletePhotoRow(photo.id)
                removed++
            }
        }
        removed
    }

    suspend fun removeIfMissing(photo: PhotoEntity): Boolean = withContext(Dispatchers.IO) {
        if (media.hasContent(Uri.parse(photo.contentUri))) false
        else {
            dao.deletePhotoRow(photo.id)
            true
        }
    }

    suspend fun registerCaptured(
        locationId: String, uri: Uri, relative: String, filename: String, capturedAt: OffsetDateTime,
        latitude: Double?, longitude: Double?, accuracy: Float?, withStamp: Boolean = false
    ) = withContext(Dispatchers.IO) {
        media.finish(uri)
        if (withStamp) {
            val location = dao.location(locationId)
            val job = dao.job(location.jobId)
            val client = dao.client(job.clientId)
            val stamped = media.createStampedCopy(uri, relative, filename, client.name, job.name, location.name,
                capturedAt, latitude, longitude, accuracy)
            dao.insertPhoto(PhotoEntity(UUID.randomUUID().toString(), locationId, media.sha256(uri), uri.toString(),
                relative, filename, capturedAt.toString(), latitude, longitude, accuracy))
            val inserted = dao.insertPhoto(PhotoEntity(UUID.randomUUID().toString(), locationId, stamped.sha256, stamped.uri.toString(),
                stamped.relativePath, stamped.filename, capturedAt.toString(), latitude, longitude, accuracy))
            if (inserted == -1L) context.contentResolver.delete(stamped.uri, null, null)
        } else {
            val hash = media.sha256(uri)
            val inserted = dao.insertPhoto(PhotoEntity(UUID.randomUUID().toString(), locationId, hash, uri.toString(),
                relative, filename, capturedAt.toString(), latitude, longitude, accuracy))
            if (inserted == -1L) context.contentResolver.delete(uri, null, null)
        }
    }

    suspend fun deletePhoto(id: String) = withContext(Dispatchers.IO) {
        val photo = dao.photo(id)
        val uri = Uri.parse(photo.contentUri)
        require(media.canRequestDelete(uri)) { "รูปจาก Photo Picker ต้องลบต้นฉบับผ่านแอป Gallery" }
        try {
            context.contentResolver.delete(uri, null, null)
            dao.deletePhotoRow(id)
        } catch (error: SecurityException) {
            val sender = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && error is RecoverableSecurityException ->
                    error.userAction.actionIntent.intentSender
                else -> throw error
            }
            throw MediaDeleteApproval(sender)
        }
    }

    suspend fun forgetPhoto(id: String) = withContext(Dispatchers.IO) { dao.deletePhotoRow(id) }

    suspend fun saveScannedPdf(locationId: String, source: Uri, pageCount: Int) = withContext(Dispatchers.IO) {
        val location = dao.location(locationId); val job = dao.job(location.jobId); val client = dao.client(job.clientId)
        val photoPath = media.relativePath(client.name, job.name, location.name)
        // MediaStore.Downloads only accepts paths rooted at Download on some OPPO/ColorOS versions.
        val appHierarchy = photoPath.substringAfter('/').trimStart('/')
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/$appHierarchy"
        val filename = "SCAN_${OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}.pdf"
        val target = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename); put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("สร้างไฟล์ PDF ไม่สำเร็จ")
        try {
            context.contentResolver.openInputStream(source).use { input ->
                requireNotNull(input)
                context.contentResolver.openOutputStream(target).use { output -> requireNotNull(output); input.copyTo(output) }
            }
            context.contentResolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            dao.insertDocument(DocumentEntity(UUID.randomUUID().toString(), locationId, target.toString(), filename,
                media.sha256(target), pageCount, now()))
        } catch (error: Throwable) { context.contentResolver.delete(target, null, null); throw error }
    }

    suspend fun addNote(locationId: String, title: String, content: String) = withContext(Dispatchers.IO) {
        dao.insertNote(NoteEntity(UUID.randomUUID().toString(), locationId, title.trim().ifBlank { "โน้ต" }, content.trim(), now()))
    }

    suspend fun updateNote(id: String, title: String, content: String) = withContext(Dispatchers.IO) {
        dao.updateNote(id, title.trim().ifBlank { "โน้ต" }, content.trim(), now())
    }

    suspend fun removeDocument(document: DocumentEntity) = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.delete(Uri.parse(document.contentUri), null, null) }
        dao.deleteDocument(document.id)
    }
}

class MediaDeleteApproval(val sender: IntentSender) : Exception("Media deletion needs user approval")
