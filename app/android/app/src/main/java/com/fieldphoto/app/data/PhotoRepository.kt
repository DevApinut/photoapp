package com.fieldphoto.app.data

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.ContentValues
import android.app.RecoverableSecurityException
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.graphics.pdf.PdfRenderer
import android.graphics.Bitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.os.Environment
import android.net.Uri
import com.fieldphoto.app.media.MediaStoreManager
import com.fieldphoto.app.sync.CloudClient
import com.fieldphoto.app.sync.CloudPhoto
import com.fieldphoto.app.sync.CloudCatalog
import com.fieldphoto.app.sync.CloudDocument
import com.fieldphoto.app.sync.CloudNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.time.OffsetDateTime
import java.util.UUID

class PhotoRepository(private val context: Context, val dao: AppDao) {
    val media = MediaStoreManager(context)
    private fun now() = OffsetDateTime.now().toString()

    suspend fun cleanupLegacyEmptyTestJob() = withContext(Dispatchers.IO) {
        val legacyId = "9a1428d9-81a3-4a23-88ae-ac233d5bd809"
        val job = dao.jobOrNull(legacyId) ?: return@withContext
        val locations = dao.locationsNow(legacyId)
        val hasContent = locations.any { location ->
            dao.photosNow(location.id).isNotEmpty() || dao.documentsNow(location.id).isNotEmpty() || dao.notesNow(location.id).isNotEmpty()
        }
        if (!hasContent && job.name == "ทดสอบ" && locations.all { it.name.isBlank() || it.name == "AA" }) dao.deleteJob(legacyId)
    }

    suspend fun movePhotos(photoIds: List<String>, targetLocationId: String) = withContext(Dispatchers.IO) {
        if (photoIds.isNotEmpty()) dao.movePhotos(photoIds, targetLocationId)
    }

    suspend fun moveFolder(source: LocationEntity, target: LocationEntity) = withContext(Dispatchers.IO) {
        require(source.name.isNotBlank()) { "ย้ายโฟลเดอร์หลักทั้งงานไม่ได้" }
        require(source.jobId != target.jobId || !target.name.startsWith("${source.name}/")) { "ย้ายเข้าโฟลเดอร์ย่อยของตัวเองไม่ได้" }
        val sourceFolders = dao.folderTree(source.jobId, source.name, "${source.name}/%").sortedBy { it.name.length }
        val baseName = source.name.substringAfterLast('/')
        sourceFolders.forEach { old ->
            val suffix = old.name.removePrefix(source.name).trimStart('/')
            val newName = listOf(target.name, baseName, suffix).filter { it.isNotBlank() }.joinToString("/")
            val destination = dao.locationByName(target.jobId, newName)
                ?: LocationEntity(UUID.randomUUID().toString(), target.jobId, newName, now()).also { dao.insertLocation(it) }
            val photos = dao.photosNow(old.id)
            if (photos.isNotEmpty()) dao.movePhotos(photos.map { it.id }, destination.id)
            dao.documentsNow(old.id).forEach { dao.moveDocument(it.id, destination.id) }
            dao.notesNow(old.id).forEach { dao.moveNote(it.id, destination.id) }
        }
        sourceFolders.sortedByDescending { it.name.length }.forEach { dao.deleteLocation(it.id) }
    }

    private suspend fun cloudRestoreJob(jobId: String, clientName: String, jobName: String): JobEntity {
        val key = if (jobId.isNotBlank()) jobId else "$clientName/$jobName"
        val preferences = context.getSharedPreferences("cloud_restore_jobs", Context.MODE_PRIVATE)
        preferences.getString(key, null)?.let { stored -> runCatching { return dao.job(stored) } }
        val client = dao.clientByName(clientName) ?: ClientEntity(UUID.randomUUID().toString(), clientName, now()).also { dao.insertClient(it) }
        val localName = uniqueJobName(client.id, jobName)
        return JobEntity(UUID.randomUUID().toString(), client.id, localName, now()).also {
            dao.insertJob(it); dao.insertLocation(LocationEntity(UUID.randomUUID().toString(), it.id, "", now()))
            preferences.edit().putString(key, it.id).apply()
        }
    }

    suspend fun restoreCloudPhoto(serverUrl: String, cloud: CloudPhoto): PhotoEntity = withContext(Dispatchers.IO) {
        val job = cloudRestoreJob(cloud.jobId, cloud.clientName, cloud.jobName)
        val client = dao.client(job.clientId)
        val root = dao.locationByName(job.id, "") ?: LocationEntity(UUID.randomUUID().toString(), job.id, "", now()).also { dao.insertLocation(it) }
        val location = if (cloud.locationName.isBlank()) root else dao.locationByName(job.id, cloud.locationName)
            ?: LocationEntity(UUID.randomUUID().toString(), job.id, cloud.locationName, now()).also { dao.insertLocation(it) }
        val capturedAt = OffsetDateTime.parse(cloud.capturedAt)
        val (uri, relative, filename) = media.newDestination(client.name, job.name, location.name, capturedAt)
        try {
            CloudClient().openPhoto(serverUrl, cloud.hash).use { response ->
                if (!response.isSuccessful) error("ดาวน์โหลดไม่สำเร็จ: Server ${response.code}")
                context.contentResolver.openOutputStream(uri).use { output ->
                    requireNotNull(output); response.body?.byteStream().use { input -> requireNotNull(input); input.copyTo(output) }
                }
            }
            media.finish(uri)
            check(media.sha256(uri) == cloud.hash) { "SHA-256 ของไฟล์ที่ดาวน์โหลดไม่ตรง" }
            PhotoEntity(UUID.randomUUID().toString(), location.id, cloud.hash, uri.toString(), relative, filename,
                cloud.capturedAt, cloud.latitude, cloud.longitude, cloud.accuracy, UploadStatus.UPLOADED).also { dao.insertPhoto(it) }
        } catch (error: Throwable) {
            media.cancel(uri)
            throw error
        }
    }

    private suspend fun restoreCloudNote(cloud: CloudNote) {
        val job = cloudRestoreJob(cloud.jobId, cloud.clientName, cloud.jobName)
        val location = dao.locationByName(job.id, cloud.locationName)
            ?: LocationEntity(UUID.randomUUID().toString(), job.id, cloud.locationName, now()).also { dao.insertLocation(it) }
        dao.insertNote(NoteEntity(UUID.randomUUID().toString(), location.id, cloud.title, cloud.content, cloud.updatedAt, UploadStatus.UPLOADED))
    }

    private suspend fun restoreCloudDocument(serverUrl: String, cloud: CloudDocument) {
        val job = cloudRestoreJob(cloud.jobId, cloud.clientName, cloud.jobName); val client = dao.client(job.clientId)
        val location = dao.locationByName(job.id, cloud.locationName)
            ?: LocationEntity(UUID.randomUUID().toString(), job.id, cloud.locationName, now()).also { dao.insertLocation(it) }
        val photoPath = media.relativePath(client.name, job.name, location.name)
        val isVideo = cloud.mimeType.startsWith("video/")
        val relative = "${if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_DOWNLOADS}/${photoPath.substringAfter('/').trimStart('/')}"
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val target = context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, cloud.filename); put(MediaStore.MediaColumns.MIME_TYPE, cloud.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error(if (isVideo) "สร้างไฟล์วิดีโอไม่สำเร็จ" else "สร้าง PDF ไม่สำเร็จ")
        try {
            CloudClient().openDocument(serverUrl, cloud.id).use { response ->
                if (!response.isSuccessful) error("ดาวน์โหลดไฟล์ไม่สำเร็จ: ${response.code}")
                context.contentResolver.openOutputStream(target).use { output -> response.body?.byteStream().use { input -> requireNotNull(output); requireNotNull(input); input.copyTo(output) } }
            }
            context.contentResolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            dao.insertDocument(DocumentEntity(id = UUID.randomUUID().toString(), locationId = location.id, contentUri = target.toString(), filename = cloud.filename,
                sha256 = media.sha256(target), pageCount = cloud.pageCount, createdAt = cloud.createdAt, status = UploadStatus.UPLOADED, mimeType = cloud.mimeType))
        } catch (error: Throwable) { context.contentResolver.delete(target, null, null); throw error }
    }

    suspend fun restoreCloudGroup(serverUrl: String, catalog: CloudCatalog, folderPrefix: String?): Int = withContext(Dispatchers.IO) {
        fun included(path: String) = folderPrefix == null || path == folderPrefix || path.startsWith("$folderPrefix/")
        val folders = catalog.folders.filter { included(it.locationName) }
        if (folders.isNotEmpty()) {
            val first = folders.first(); val job = cloudRestoreJob(first.jobId, first.clientName, first.jobName)
            folders.forEach { if (dao.locationByName(job.id, it.locationName) == null) dao.insertLocation(LocationEntity(UUID.randomUUID().toString(), job.id, it.locationName, now())) }
        }
        var count = 0
        catalog.photos.filter { included(it.locationName) }.forEach { restoreCloudPhoto(serverUrl, it); count++ }
        catalog.documents.filter { included(it.locationName) }.forEach { restoreCloudDocument(serverUrl, it); count++ }
        catalog.notes.filter { included(it.locationName) }.forEach { restoreCloudNote(it); count++ }
        count
    }

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
        val documents = dao.locationsNow(jobId).flatMap { dao.documentsNow(it.id) }
        val notes = dao.locationsNow(jobId).flatMap { dao.notesNow(it.id) }
        BackupSummary(
            waiting = photos.count { it.status == UploadStatus.WAITING } + documents.count { it.status == UploadStatus.WAITING } + notes.count { it.status == UploadStatus.WAITING },
            failed = photos.count { it.status == UploadStatus.ERROR } + documents.count { it.status == UploadStatus.ERROR } + notes.count { it.status == UploadStatus.ERROR },
            uploaded = photos.count { it.status == UploadStatus.UPLOADED } + documents.count { it.status == UploadStatus.UPLOADED } + notes.count { it.status == UploadStatus.UPLOADED },
        )
    }

    suspend fun jobDeleteApproval(job: JobEntity): IntentSender? = withContext(Dispatchers.IO) {
        val folders = dao.locationsNow(job.id)
        val uris = folders.flatMap { dao.photosNow(it.id).map { photo -> Uri.parse(photo.contentUri) } +
            dao.documentsNow(it.id).map { document -> Uri.parse(document.contentUri) } }.filter { media.hasContent(it) }
        createBatchDeleteApproval(uris)
    }

    suspend fun jobsDeleteApproval(jobs: List<JobEntity>): IntentSender? = withContext(Dispatchers.IO) {
        val uris = jobs.flatMap { job -> dao.locationsNow(job.id).flatMap { folder ->
            dao.photosNow(folder.id).map { Uri.parse(it.contentUri) } + dao.documentsNow(folder.id).map { Uri.parse(it.contentUri) }
        } }.filter { media.hasContent(it) }
        createBatchDeleteApproval(uris)
    }

    suspend fun forgetJobs(ids: List<String>) = withContext(Dispatchers.IO) {
        ids.forEach { dao.deleteJob(it) }
    }

    suspend fun folderDeleteApproval(folder: LocationEntity): IntentSender? = withContext(Dispatchers.IO) {
        val uris = dao.folderTree(folder.jobId, folder.name, "${folder.name}/%").flatMap { item ->
            dao.photosNow(item.id).map { Uri.parse(it.contentUri) } + dao.documentsNow(item.id).map { Uri.parse(it.contentUri) }
        }.filter { media.hasContent(it) }
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
        val storedGpsIsValid = photo.latitude != null && photo.longitude != null &&
            photo.latitude in -90.0..90.0 && photo.longitude in -180.0..180.0 &&
            !(photo.latitude == 0.0 && photo.longitude == 0.0)
        createTimestampFromSource(photo.locationId, source, photo.filename, capturedAt,
            exif.latitude ?: photo.latitude?.takeIf { storedGpsIsValid },
            exif.longitude ?: photo.longitude?.takeIf { storedGpsIsValid }, photo.accuracy)
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

    suspend fun importPdf(locationId: String, source: Uri) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val originalName = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.trim()?.takeIf { it.endsWith(".pdf", ignoreCase = true) }
            ?: "PDF_${OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}.pdf"
        val pageCount = resolver.openFileDescriptor(source, "r")?.use { descriptor ->
            PdfRenderer(descriptor).use { it.pageCount }
        } ?: error("อ่านไฟล์ PDF ไม่สำเร็จ")
        val location = dao.location(locationId); val job = dao.job(location.jobId); val client = dao.client(job.clientId)
        val appHierarchy = media.relativePath(client.name, job.name, location.name).substringAfter('/').trimStart('/')
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, originalName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$appHierarchy")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("สร้างไฟล์ PDF ไม่สำเร็จ")
        try {
            resolver.openInputStream(source).use { input ->
                requireNotNull(input) { "อ่านไฟล์ PDF ไม่สำเร็จ" }
                resolver.openOutputStream(target).use { output -> requireNotNull(output); input.copyTo(output) }
            }
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            dao.insertDocument(DocumentEntity(UUID.randomUUID().toString(), locationId, target.toString(), originalName,
                media.sha256(target), pageCount, now()))
            pageCount
        } catch (error: Throwable) {
            resolver.delete(target, null, null)
            throw error
        }
    }

    suspend fun importVideo(locationId: String, source: Uri) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(source)?.takeIf { it.startsWith("video/") } ?: "video/mp4"
        val originalName = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.trim()?.takeIf { it.isNotBlank() } ?: "VIDEO_${OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}.mp4"
        val location = dao.location(locationId); val job = dao.job(location.jobId); val client = dao.client(job.clientId)
        val hierarchy = media.relativePath(client.name, job.name, location.name).substringAfter('/').trimStart('/')
        val target = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, originalName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$hierarchy")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("สร้างไฟล์วิดีโอไม่สำเร็จ")
        try {
            resolver.openInputStream(source).use { input ->
                requireNotNull(input) { "อ่านวิดีโอไม่สำเร็จ" }
                resolver.openOutputStream(target).use { output -> requireNotNull(output); input.copyTo(output) }
            }
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            dao.insertDocument(DocumentEntity(
                id = UUID.randomUUID().toString(), locationId = locationId, contentUri = target.toString(), filename = originalName,
                sha256 = media.sha256(target), pageCount = 0, createdAt = now(), mimeType = mimeType
            ))
        } catch (error: Throwable) { resolver.delete(target, null, null); throw error }
    }

    suspend fun attachVideo(locationId: String, source: Uri) = withContext(Dispatchers.IO) {
        if (dao.documentUriExists(source.toString())) return@withContext
        val resolver = context.contentResolver
        val mimeType = resolver.getType(source)?.takeIf { it.startsWith("video/") } ?: "video/mp4"
        val filename = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }?.takeIf { it.isNotBlank() } ?: "VIDEO_${OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}.mp4"
        dao.insertDocument(DocumentEntity(
            id = UUID.randomUUID().toString(), locationId = locationId, contentUri = source.toString(), filename = filename,
            sha256 = media.sha256(source), pageCount = 0, createdAt = now(), mimeType = mimeType
        ))
    }

    suspend fun addNote(locationId: String, title: String, content: String) = withContext(Dispatchers.IO) {
        dao.insertNote(NoteEntity(UUID.randomUUID().toString(), locationId, title.trim().ifBlank { "โน้ต" }, content.trim(), now()))
    }

    suspend fun updateNote(id: String, title: String, content: String) = withContext(Dispatchers.IO) {
        dao.updateNote(id, title.trim().ifBlank { "โน้ต" }, content.trim(), now())
    }

    suspend fun removeDocument(document: DocumentEntity) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(document.contentUri)
        try {
            val deleted = context.contentResolver.delete(uri, null, null)
            if (deleted <= 0 && uri.authority == MediaStore.AUTHORITY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                throw MediaDeleteApproval(MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender)
            }
        } catch (error: android.app.RecoverableSecurityException) {
            throw MediaDeleteApproval(error.userAction.actionIntent.intentSender)
        } catch (error: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uri.authority == MediaStore.AUTHORITY) {
                throw MediaDeleteApproval(MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender)
            }
            throw error
        }
        dao.deleteDocument(document.id)
    }

    private fun cleanFilename(raw: String, extension: String): String {
        val base = raw.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").removeSuffix(extension).removeSuffix(extension.uppercase())
            .ifBlank { "FILE_${System.currentTimeMillis()}" }
        return "$base$extension"
    }

    suspend fun renamePhoto(photo: PhotoEntity, requested: String) = withContext(Dispatchers.IO) {
        val extension = photo.filename.substringAfterLast('.', "jpg").let { ".$it" }
        val filename = cleanFilename(requested, extension)
        context.contentResolver.update(Uri.parse(photo.contentUri), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        }, null, null)
        dao.renamePhoto(photo.id, filename)
    }

    suspend fun renameDocument(document: DocumentEntity, requested: String) = withContext(Dispatchers.IO) {
        val extension = document.filename.substringAfterLast('.', if (document.mimeType.startsWith("video/")) "mp4" else "pdf").let { ".$it" }
        val filename = cleanFilename(requested, extension)
        context.contentResolver.update(Uri.parse(document.contentUri), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        }, null, null)
        dao.renameDocument(document.id, filename)
    }

    suspend fun searchPdf(document: DocumentEntity, query: String): List<Int> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        PDFBoxResourceLoader.init(context)
        val textMatches = context.contentResolver.openInputStream(Uri.parse(document.contentUri)).use { input ->
            PDDocument.load(requireNotNull(input)).use { pdf ->
                (1..pdf.numberOfPages).filter { page ->
                    PDFTextStripper().apply { startPage = page; endPage = page }.getText(pdf)
                        .contains(query, ignoreCase = true)
                }
            }
        }
        if (textMatches.isNotEmpty()) return@withContext textMatches

        // Scanned PDFs have no text layer. Fall back to on-device OCR page by page.
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val matches = mutableListOf<Int>()
            context.contentResolver.openFileDescriptor(Uri.parse(document.contentUri), "r")?.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    for (index in 0 until renderer.pageCount) renderer.openPage(index).use { page ->
                        val width = 1600
                        val height = (page.height * width.toFloat() / page.width).toInt()
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val recognized = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text
                        bitmap.recycle()
                        if (recognized.contains(query, ignoreCase = true)) matches += index + 1
                    }
                }
            } ?: error("เปิด PDF เพื่อค้นหาไม่ได้")
            matches
        } finally { recognizer.close() }
    }

    suspend fun pdfPageText(document: DocumentEntity, page: Int): String = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        context.contentResolver.openInputStream(Uri.parse(document.contentUri)).use { input ->
            PDDocument.load(requireNotNull(input)).use { pdf ->
                val actualPage = page.coerceIn(1, pdf.numberOfPages)
                PDFTextStripper().apply { startPage = actualPage; endPage = actualPage }.getText(pdf).trim()
            }
        }
    }

    suspend fun savePdfRange(document: DocumentEntity, firstPage: Int, lastPage: Int): DocumentEntity = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        val resolver = context.contentResolver
        val first = firstPage.coerceIn(1, document.pageCount)
        val last = lastPage.coerceIn(first, document.pageCount)
        val filename = cleanFilename("${document.filename.substringBeforeLast('.')}_p${first}-${last}", ".pdf")
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename); put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DN"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("สร้าง PDF ไม่สำเร็จ")
        try {
            resolver.openInputStream(Uri.parse(document.contentUri)).use { input ->
                PDDocument.load(requireNotNull(input)).use { source ->
                    PDDocument().use { output ->
                        for (index in first - 1 until last) output.importPage(source.getPage(index))
                        resolver.openOutputStream(target).use { output.save(requireNotNull(it)) }
                    }
                }
            }
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            DocumentEntity(UUID.randomUUID().toString(), document.locationId, target.toString(), filename,
                media.sha256(target), last - first + 1, now()).also { dao.insertDocument(it) }
        } catch (error: Throwable) { resolver.delete(target, null, null); throw error }
    }

    suspend fun exportPdfPagesAsImages(document: DocumentEntity, firstPage: Int, lastPage: Int): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val first = firstPage.coerceIn(1, document.pageCount); val last = lastPage.coerceIn(first, document.pageCount)
        val capturedAt = now()
        var saved = 0
        resolver.openFileDescriptor(Uri.parse(document.contentUri), "r")?.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                for (index in first - 1 until last) renderer.openPage(index).use { page ->
                    val scale = (1800f / page.width).coerceAtLeast(1f)
                    val bitmap = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val filename = "${document.filename.substringBeforeLast('.')}_p${index + 1}.png"
                    val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename); put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MyPhotoApp/PDF"); put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }) ?: error("สร้างรูปหน้า ${index + 1} ไม่สำเร็จ")
                    resolver.openOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, requireNotNull(it)) }
                    bitmap.recycle(); resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    dao.insertPhoto(PhotoEntity(UUID.randomUUID().toString(), document.locationId, media.sha256(target), target.toString(),
                        Environment.DIRECTORY_PICTURES + "/MyPhotoApp/PDF", filename, capturedAt, null, null, null))
                    saved++
                }
            }
        } ?: error("เปิด PDF ไม่สำเร็จ")
        saved
    }
}

class MediaDeleteApproval(val sender: IntentSender) : Exception("Media deletion needs user approval")
