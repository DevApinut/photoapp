package com.fieldphoto.app.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class CloudPhoto(
    val hash: String, val jobId: String, val clientName: String, val jobName: String, val locationName: String,
    val filename: String, val capturedAt: String, val latitude: Double?, val longitude: Double?,
    val accuracy: Float?, val sizeBytes: Long,
)
data class CloudFolder(val jobId: String, val clientName: String, val jobName: String, val locationName: String)
data class CloudDocument(val id: String, val jobId: String, val clientName: String, val jobName: String, val locationName: String, val filename: String, val pageCount: Int, val createdAt: String)
data class CloudNote(val id: String, val jobId: String, val clientName: String, val jobName: String, val locationName: String, val title: String, val content: String, val updatedAt: String)
data class CloudCatalog(val folders: List<CloudFolder> = emptyList(), val photos: List<CloudPhoto> = emptyList(), val documents: List<CloudDocument> = emptyList(), val notes: List<CloudNote> = emptyList())

class CloudClient {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    suspend fun catalog(serverUrl: String): CloudCatalog = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${serverUrl.trimEnd('/')}/catalog").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server ${response.code}")
            val root = JSONObject(response.body?.string().orEmpty())
            val rows = root.getJSONArray("photos")
            fun jobId(item: JSONObject) = item.optString("job_id").takeUnless { it.equals("null", true) } ?: ""
            val photos = buildList {
                for (index in 0 until rows.length()) rows.getJSONObject(index).let { item ->
                    add(CloudPhoto(
                        item.getString("hash"), jobId(item), item.getString("client_name"), item.getString("job_name"),
                        item.optString("location_name"), item.getString("filename"), item.getString("captured_at"),
                        item.optDouble("latitude").takeUnless { it.isNaN() },
                        item.optDouble("longitude").takeUnless { it.isNaN() },
                        item.optDouble("accuracy").takeUnless { it.isNaN() }?.toFloat(), item.optLong("size_bytes")
                    ))
                }
            }
            fun identity(item: JSONObject) = Triple(item.optString("client_name"), item.optString("job_name"), item.optString("location_name"))
            val folders = buildList { root.optJSONArray("folders")?.let { array -> for (i in 0 until array.length()) array.getJSONObject(i).let { item -> val id=identity(item); add(CloudFolder(jobId(item), id.first,id.second,id.third)) } } }
            val documents = buildList { root.optJSONArray("documents")?.let { array -> for (i in 0 until array.length()) array.getJSONObject(i).let { item -> val id = identity(item); add(CloudDocument(item.optString("document_id"), jobId(item), id.first, id.second, id.third, item.optString("filename"), item.optInt("page_count"), item.optString("created_at"))) } } }
            val notes = buildList { root.optJSONArray("notes")?.let { array -> for (i in 0 until array.length()) array.getJSONObject(i).let { item -> val id = identity(item); add(CloudNote(item.optString("note_id"), jobId(item), id.first, id.second, id.third, item.optString("title"), item.optString("content"), item.optString("updated_at"))) } } }
            CloudCatalog(folders, photos, documents, notes)
        }
    }

    fun photoUrl(serverUrl: String, hash: String) = "${serverUrl.trimEnd('/')}/photo/$hash"
    fun documentUrl(serverUrl: String, id: String) = "${serverUrl.trimEnd('/')}/document/$id"

    fun openPhoto(serverUrl: String, hash: String) =
        http.newCall(Request.Builder().url(photoUrl(serverUrl, hash)).get().build()).execute()
    fun openDocument(serverUrl: String, id: String) =
        http.newCall(Request.Builder().url(documentUrl(serverUrl, id)).get().build()).execute()
}
