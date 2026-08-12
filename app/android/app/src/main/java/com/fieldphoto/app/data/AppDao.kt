package com.fieldphoto.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM clients ORDER BY name") fun clients(): Flow<List<ClientEntity>>
    @Query("SELECT j.* FROM jobs j JOIN clients c ON c.id = j.clientId WHERE c.name = 'งานทั่วไป' ORDER BY j.createdAt DESC")
    fun quickJobs(): Flow<List<JobEntity>>
    @Query("""
        SELECT j.* FROM jobs j JOIN clients c ON c.id = j.clientId
        LEFT JOIN locations l ON l.jobId=j.id LEFT JOIN photos p ON p.locationId=l.id
        WHERE c.name='งานทั่วไป' GROUP BY j.id
        ORDER BY MAX(p.capturedAt) IS NULL ASC, COALESCE(MAX(p.capturedAt), j.createdAt) DESC
    """)
    fun quickJobsByLatestPhoto(): Flow<List<JobEntity>>
    @Query("""
        SELECT j.* FROM jobs j JOIN clients c ON c.id=j.clientId
        LEFT JOIN locations l ON l.jobId=j.id LEFT JOIN photos p ON p.locationId=l.id
        WHERE c.name='งานทั่วไป' AND (
            j.name LIKE '%' || :query || '%' OR EXISTS (
                SELECT 1 FROM notes n JOIN locations nl ON nl.id=n.locationId
                WHERE nl.jobId=j.id AND (n.title LIKE '%' || :query || '%' OR n.content LIKE '%' || :query || '%')
            )
        )
        GROUP BY j.id
        ORDER BY MAX(p.capturedAt) IS NULL ASC, COALESCE(MAX(p.capturedAt), j.createdAt) DESC
    """)
    fun searchQuickJobs(query: String): Flow<List<JobEntity>>
    @Query("""
        SELECT * FROM (
            SELECT p.id AS id, 'PHOTO' AS kind, p.contentUri AS contentUri, p.filename AS filename,
                   'image/jpeg' AS mimeType, j.name AS jobName, l.name AS locationName, p.capturedAt AS capturedAt
            FROM photos p JOIN locations l ON l.id=p.locationId JOIN jobs j ON j.id=l.jobId
            UNION ALL
            SELECT d.id AS id, 'DOCUMENT' AS kind, d.contentUri AS contentUri, d.filename AS filename,
                   d.mimeType AS mimeType, j.name AS jobName, l.name AS locationName, d.createdAt AS capturedAt
            FROM documents d JOIN locations l ON l.id=d.locationId JOIN jobs j ON j.id=l.jobId
        ) WHERE filename LIKE '%' || replace(trim(:query), ' ', '%') || '%' COLLATE NOCASE
        ORDER BY capturedAt DESC LIMIT 200
    """)
    fun searchLocalFiles(query: String): Flow<List<LocalFileSearchResult>>
    @Query("""
        SELECT * FROM (
            SELECT p.id AS id, 'PHOTO' AS kind, p.contentUri AS contentUri, p.filename AS filename,
                   'image/jpeg' AS mimeType, j.name AS jobName, l.name AS locationName, p.capturedAt AS capturedAt
            FROM photos p JOIN locations l ON l.id=p.locationId JOIN jobs j ON j.id=l.jobId
            UNION ALL
            SELECT d.id AS id, 'DOCUMENT' AS kind, d.contentUri AS contentUri, d.filename AS filename,
                   d.mimeType AS mimeType, j.name AS jobName, l.name AS locationName, d.createdAt AS capturedAt
            FROM documents d JOIN locations l ON l.id=d.locationId JOIN jobs j ON j.id=l.jobId
        ) ORDER BY capturedAt DESC LIMIT 5000
    """)
    fun allLocalFiles(): Flow<List<LocalFileSearchResult>>
    @Query("""
        SELECT j.id AS jobId, MAX(p.capturedAt) AS lastPhotoAt
        FROM jobs j LEFT JOIN locations l ON l.jobId=j.id LEFT JOIN photos p ON p.locationId=l.id
        GROUP BY j.id
    """)
    fun jobActivity(): Flow<List<JobActivity>>
    @Query("SELECT j.* FROM jobs j JOIN clients c ON c.id = j.clientId WHERE c.name = 'งานทั่วไป' ORDER BY j.name COLLATE NOCASE ASC")
    fun quickJobsByName(): Flow<List<JobEntity>>
    @Query("SELECT * FROM jobs WHERE clientId = :id ORDER BY createdAt DESC") fun jobs(id: String): Flow<List<JobEntity>>
    @Query("SELECT * FROM jobs ORDER BY name COLLATE NOCASE") suspend fun allJobsNow(): List<JobEntity>
    @Query("SELECT * FROM locations ORDER BY jobId, name") suspend fun allLocationsNow(): List<LocationEntity>
    @Query("SELECT * FROM locations WHERE jobId = :id ORDER BY createdAt") fun locations(id: String): Flow<List<LocationEntity>>
    @Query("SELECT * FROM locations WHERE jobId = :id") suspend fun locationsNow(id: String): List<LocationEntity>
    @Query("SELECT * FROM photos WHERE locationId = :id ORDER BY capturedAt DESC") fun photos(id: String): Flow<List<PhotoEntity>>
    @Query("SELECT * FROM photos WHERE locationId = :id") suspend fun photosNow(id: String): List<PhotoEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertClient(value: ClientEntity): Long
    @Insert suspend fun insertJob(value: JobEntity)
    @Insert suspend fun insertLocation(value: LocationEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPhoto(value: PhotoEntity): Long
    @Insert suspend fun insertDocument(value: DocumentEntity)
    @Insert suspend fun insertNote(value: NoteEntity)

    @Query("SELECT * FROM clients WHERE id = :id") suspend fun client(id: String): ClientEntity
    @Query("SELECT * FROM clients WHERE name = :name LIMIT 1") suspend fun clientByName(name: String): ClientEntity?
    @Query("SELECT * FROM jobs WHERE id = :id") suspend fun job(id: String): JobEntity
    @Query("SELECT * FROM jobs WHERE clientId=:clientId AND name=:name LIMIT 1") suspend fun jobByName(clientId: String, name: String): JobEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM jobs WHERE clientId = :clientId AND name = :name AND id != :excludeId)")
    suspend fun jobNameExists(clientId: String, name: String, excludeId: String): Boolean
    @Query("UPDATE jobs SET name = :name WHERE id = :id") suspend fun renameJob(id: String, name: String)
    @Query("SELECT * FROM locations WHERE id = :id") suspend fun location(id: String): LocationEntity
    @Query("SELECT * FROM locations WHERE jobId=:jobId AND name=:name LIMIT 1") suspend fun locationByName(jobId: String, name: String): LocationEntity?
    @Query("SELECT * FROM locations WHERE jobId = :jobId ORDER BY createdAt LIMIT 1")
    suspend fun firstLocation(jobId: String): LocationEntity?
    @Query("SELECT * FROM locations WHERE jobId = :jobId AND name = '' LIMIT 1")
    suspend fun rootLocation(jobId: String): LocationEntity?
    @Query("SELECT * FROM photos WHERE id = :id") suspend fun photo(id: String): PhotoEntity
    @Query("SELECT EXISTS(SELECT 1 FROM photos WHERE contentUri=:contentUri)") suspend fun photoUriExists(contentUri: String): Boolean
    @Query("UPDATE photos SET status = :status, lastError = :error WHERE id = :id")
    suspend fun setStatus(id: String, status: UploadStatus, error: String? = null)
    @Query("UPDATE photos SET locationId=:locationId, status='WAITING', lastError=NULL WHERE id IN (:ids)")
    suspend fun movePhotos(ids: List<String>, locationId: String)
    @Query("UPDATE documents SET locationId=:locationId, status='WAITING', lastError=NULL WHERE id=:id")
    suspend fun moveDocument(id: String, locationId: String)
    @Query("UPDATE notes SET locationId=:locationId, status='WAITING', lastError=NULL WHERE id=:id")
    suspend fun moveNote(id: String, locationId: String)
    @Query("SELECT * FROM documents WHERE locationId=:locationId") suspend fun documentsNow(locationId: String): List<DocumentEntity>
    @Query("SELECT * FROM notes WHERE locationId=:locationId") suspend fun notesNow(locationId: String): List<NoteEntity>
    @Query("UPDATE photos SET sha256 = :sha256 WHERE id = :id")
    suspend fun updatePhotoHash(id: String, sha256: String)
    @Query("UPDATE photos SET filename=:filename, status='WAITING', lastError=NULL WHERE id=:id")
    suspend fun renamePhoto(id: String, filename: String)
    @Query("SELECT * FROM documents WHERE locationId=:locationId ORDER BY createdAt DESC") fun documents(locationId: String): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM notes WHERE locationId=:locationId ORDER BY updatedAt DESC") fun notes(locationId: String): Flow<List<NoteEntity>>
    @Query("DELETE FROM documents WHERE id=:id") suspend fun deleteDocument(id: String)
    @Query("UPDATE documents SET filename=:filename, status='WAITING', lastError=NULL WHERE id=:id")
    suspend fun renameDocument(id: String, filename: String)
    @Query("SELECT EXISTS(SELECT 1 FROM documents WHERE contentUri=:contentUri)") suspend fun documentUriExists(contentUri: String): Boolean
    @Query("DELETE FROM notes WHERE id=:id") suspend fun deleteNote(id: String)
    @Query("UPDATE notes SET title=:title, content=:content, updatedAt=:updatedAt, status='WAITING', lastError=NULL WHERE id=:id")
    suspend fun updateNote(id: String, title: String, content: String, updatedAt: String)
    @Query("UPDATE documents SET status=:status, lastError=:error WHERE id=:id") suspend fun setDocumentStatus(id: String, status: UploadStatus, error: String? = null)
    @Query("UPDATE notes SET status=:status, lastError=:error WHERE id=:id") suspend fun setNoteStatus(id: String, status: UploadStatus, error: String? = null)
    @Query("DELETE FROM photos WHERE id = :id") suspend fun deletePhotoRow(id: String)
    @Query("DELETE FROM locations WHERE id = :id") suspend fun deleteLocation(id: String)
    @Query("DELETE FROM jobs WHERE id = :id") suspend fun deleteJob(id: String)
    @Query("SELECT * FROM jobs WHERE id = :id LIMIT 1") suspend fun jobOrNull(id: String): JobEntity?
    @Query("SELECT * FROM locations WHERE jobId = :jobId AND (name = :path OR name LIKE :prefix) ORDER BY length(name) DESC")
    suspend fun folderTree(jobId: String, path: String, prefix: String): List<LocationEntity>

    @Query("""
        SELECT p.id, j.id AS jobId, p.sha256, p.contentUri, p.filename, p.capturedAt, p.latitude, p.longitude, p.accuracy,
               l.name AS locationName, j.name AS jobName, c.name AS clientName
        FROM photos p JOIN locations l ON l.id=p.locationId JOIN jobs j ON j.id=l.jobId JOIN clients c ON c.id=j.clientId
        WHERE p.status IN (:statuses) ORDER BY p.capturedAt
    """)
    suspend fun syncCandidates(statuses: List<UploadStatus>): List<PendingPhoto>

    @Query("""
        SELECT j.id AS jobId, c.name AS clientName, j.name AS jobName, l.name AS locationName
        FROM locations l JOIN jobs j ON j.id=l.jobId JOIN clients c ON c.id=j.clientId
        ORDER BY c.name, j.name, l.name
    """)
    suspend fun syncFolders(): List<PendingFolder>

    @Query("""
        SELECT d.id,j.id AS jobId,d.contentUri,d.filename,d.sha256,d.pageCount,d.createdAt,d.mimeType,
               l.name AS locationName,j.name AS jobName,c.name AS clientName
        FROM documents d JOIN locations l ON l.id=d.locationId JOIN jobs j ON j.id=l.jobId JOIN clients c ON c.id=j.clientId
        WHERE d.status IN (:statuses) ORDER BY d.createdAt
    """) suspend fun syncDocuments(statuses: List<UploadStatus>): List<PendingDocument>

    @Query("""
        SELECT n.id,j.id AS jobId,n.title,n.content,n.updatedAt,l.name AS locationName,j.name AS jobName,c.name AS clientName
        FROM notes n JOIN locations l ON l.id=n.locationId JOIN jobs j ON j.id=l.jobId JOIN clients c ON c.id=j.clientId
        WHERE n.status IN (:statuses) ORDER BY n.updatedAt
    """) suspend fun syncNotes(statuses: List<UploadStatus>): List<PendingNote>
}
