package com.fieldphoto.app.sync

import android.content.Context
import android.net.Uri
import com.fieldphoto.app.data.AppDao
import com.fieldphoto.app.data.PendingPhoto
import com.fieldphoto.app.data.PendingFolder
import com.fieldphoto.app.data.PendingDocument
import com.fieldphoto.app.data.PendingNote
import com.fieldphoto.app.data.UploadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.source
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class SyncResult(
    val uploaded: Int, val skipped: Int, val failed: Int, val folders: Int, val folderFailed: Int,
    val errors: List<String>,
)
data class SyncProgress(
    val current: Int, val total: Int, val label: String,
    val uploaded: Int, val skipped: Int, val failed: Int, val folders: Int,
)

class SyncClient(private val context: Context, private val dao: AppDao) {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    suspend fun sync(
        serverUrl: String,
        statuses: List<UploadStatus>,
        onProgress: (SyncProgress) -> Unit = {},
    ): SyncResult = withContext(Dispatchers.IO) {
        val base = serverUrl.trimEnd('/')
        val folders = dao.syncFolders()
        val pending = dao.syncCandidates(statuses)
        val documents = dao.syncDocuments(statuses)
        val notes = dao.syncNotes(statuses)
        val total = folders.size + pending.size + documents.size + notes.size
        var current = 0; var uploaded = 0; var skipped = 0; var failed = 0; var folderCreated = 0; var folderFailed = 0
        val errors = mutableListOf<String>()
        onProgress(SyncProgress(0, total, "กำลังเตรียมข้อมูล", 0, 0, 0, 0))
        folders.forEach { folder ->
            try {
                createFolder(base, folder)
                folderCreated++
            } catch (error: Throwable) {
                folderFailed++
                errors += "โฟลเดอร์ ${folder.jobName}/${folder.locationName}: ${error.message ?: "ไม่ทราบสาเหตุ"}"
            }
            current++
            onProgress(SyncProgress(current, total, "โฟลเดอร์ ${folder.jobName}/${folder.locationName}", uploaded, skipped, failed, folderCreated))
        }
        documents.forEach { document ->
            try { uploadDocument(base, document); uploaded++; dao.setDocumentStatus(document.id, UploadStatus.UPLOADED) }
            catch (error: Throwable) { failed++; dao.setDocumentStatus(document.id, UploadStatus.ERROR, error.message?.take(500)); errors += "${document.filename}: ${error.message}" }
            current++; onProgress(SyncProgress(current, total, document.filename, uploaded, skipped, failed, folderCreated))
        }
        notes.forEach { note ->
            try { uploadNote(base, note); uploaded++; dao.setNoteStatus(note.id, UploadStatus.UPLOADED) }
            catch (error: Throwable) { failed++; dao.setNoteStatus(note.id, UploadStatus.ERROR, error.message?.take(500)); errors += "โน้ต ${note.title}: ${error.message}" }
            current++; onProgress(SyncProgress(current, total, "โน้ต ${note.title}", uploaded, skipped, failed, folderCreated))
        }
        pending.forEach { item ->
            try {
                val currentHash = currentSha256(item)
                if (currentHash != item.sha256) runCatching { dao.updatePhotoHash(item.id, currentHash) }
                val currentItem = if (currentHash == item.sha256) item else item.copy(sha256 = currentHash)
                val status = try {
                    upload(base, currentItem)
                } catch (error: IOException) {
                    if (!error.message.orEmpty().contains("SHA-256 mismatch", ignoreCase = true)) throw error
                    Thread.sleep(500)
                    val retryHash = currentSha256(item)
                    runCatching { dao.updatePhotoHash(item.id, retryHash) }
                    upload(base, item.copy(sha256 = retryHash))
                }
                if (status == "uploaded") uploaded++ else skipped++
                dao.setStatus(item.id, UploadStatus.UPLOADED)
            } catch (error: Throwable) {
                failed++
                dao.setStatus(item.id, UploadStatus.ERROR, error.message?.take(500))
                errors += "${item.filename}: ${error.message ?: "ไม่ทราบสาเหตุ"}"
            }
            current++
            onProgress(SyncProgress(current, total, item.filename, uploaded, skipped, failed, folderCreated))
        }
        SyncResult(uploaded, skipped, failed, folderCreated, folderFailed, errors)
    }

    private fun currentSha256(photo: PendingPhoto): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(Uri.parse(photo.contentUri)).use { input ->
            requireNotNull(input) { "ไม่พบไฟล์รูปในมือถือ" }
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun createFolder(base: String, folder: PendingFolder) {
        val json = JSONObject().apply {
            put("job_id", folder.jobId)
            put("client_name", folder.clientName)
            put("job_name", folder.jobName)
            put("location_name", folder.locationName)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        http.newCall(Request.Builder().url("$base/folder").post(json).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("สร้างโฟลเดอร์ไม่สำเร็จ: ${response.code} ${response.body?.string().orEmpty()}")
        }
    }

    private fun uploadDocument(base: String, document: PendingDocument) {
        val fileBody = object : RequestBody() {
            override fun contentType() = document.mimeType.toMediaType()
            override fun writeTo(sink: okio.BufferedSink) {
                context.contentResolver.openInputStream(Uri.parse(document.contentUri)).use { input -> requireNotNull(input); sink.writeAll(input.source()) }
            }
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("document", document.filename, fileBody).addFormDataPart("document_id", document.id)
            .addFormDataPart("sha256", document.sha256).addFormDataPart("client_name", document.clientName)
            .addFormDataPart("job_id", document.jobId)
            .addFormDataPart("job_name", document.jobName).addFormDataPart("location_name", document.locationName)
            .addFormDataPart("filename", document.filename).addFormDataPart("page_count", document.pageCount.toString())
            .addFormDataPart("mime_type", document.mimeType)
            .addFormDataPart("created_at", document.createdAt).build()
        http.newCall(Request.Builder().url("$base/upload-document").post(body).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server ${response.code}: ${response.body?.string().orEmpty()}")
        }
    }

    private fun uploadNote(base: String, note: PendingNote) {
        val body = JSONObject().apply {
            put("job_id", note.jobId)
            put("note_id", note.id); put("client_name", note.clientName); put("job_name", note.jobName)
            put("location_name", note.locationName); put("title", note.title); put("content", note.content); put("updated_at", note.updatedAt)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        http.newCall(Request.Builder().url("$base/note").post(body).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server ${response.code}: ${response.body?.string().orEmpty()}")
        }
    }

    private fun upload(base: String, p: PendingPhoto): String {
        val fileBody = object : RequestBody() {
            override fun contentType() = "image/jpeg".toMediaType()
            override fun writeTo(sink: okio.BufferedSink) {
                context.contentResolver.openInputStream(Uri.parse(p.contentUri)).use { input ->
                    requireNotNull(input) { "Photo is missing from device" }
                    sink.writeAll(input.source())
                }
            }
        }
        fun String.form() = toRequestBody("text/plain; charset=utf-8".toMediaType())
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("photo", p.filename, fileBody)
            .addFormDataPart("photo_id", p.id).addFormDataPart("sha256", p.sha256)
            .addFormDataPart("job_id", p.jobId)
            .addFormDataPart("client_name", p.clientName).addFormDataPart("job_name", p.jobName)
            .addFormDataPart("location_name", p.locationName).addFormDataPart("filename", p.filename)
            .addFormDataPart("captured_at", p.capturedAt)
            .apply {
                p.latitude?.let { addFormDataPart("latitude", it.toString()) }
                p.longitude?.let { addFormDataPart("longitude", it.toString()) }
                p.accuracy?.let { addFormDataPart("accuracy", it.toString()) }
            }.build()
        http.newCall(Request.Builder().url("$base/upload").post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Server ${response.code}: $text")
            val json = JSONObject(text)
            if (json.getString("hash") != p.sha256) throw IOException("Server hash confirmation mismatch")
            return json.getString("status")
        }
    }
}
