package com.fieldphoto.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "clients", indices = [Index(value = ["name"], unique = true)])
data class ClientEntity(@PrimaryKey val id: String, val name: String, val createdAt: String)

@Entity(
    tableName = "jobs",
    foreignKeys = [ForeignKey(entity = ClientEntity::class, parentColumns = ["id"], childColumns = ["clientId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("clientId"), Index(value = ["clientId", "name"], unique = true)]
)
data class JobEntity(@PrimaryKey val id: String, val clientId: String, val name: String, val createdAt: String)

@Entity(
    tableName = "locations",
    foreignKeys = [ForeignKey(entity = JobEntity::class, parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("jobId"), Index(value = ["jobId", "name"], unique = true)]
)
data class LocationEntity(@PrimaryKey val id: String, val jobId: String, val name: String, val createdAt: String)

enum class UploadStatus { WAITING, UPLOADED, ERROR }
data class BackupSummary(val waiting: Int, val failed: Int, val uploaded: Int) {
    val needsAttention get() = waiting + failed
}

@Entity(
    tableName = "photos",
    foreignKeys = [ForeignKey(entity = LocationEntity::class, parentColumns = ["id"], childColumns = ["locationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("locationId"), Index(value = ["sha256"], unique = true), Index("status")]
)
data class PhotoEntity(
    @PrimaryKey val id: String,
    val locationId: String,
    val sha256: String,
    val contentUri: String,
    val relativePath: String,
    val filename: String,
    val capturedAt: String,
    val latitude: Double?,
    val longitude: Double?,
    val accuracy: Float?,
    val status: UploadStatus = UploadStatus.WAITING,
    val lastError: String? = null,
)

data class PendingPhoto(
    val id: String, val jobId: String, val sha256: String, val contentUri: String, val filename: String,
    val capturedAt: String, val latitude: Double?, val longitude: Double?, val accuracy: Float?,
    val locationName: String, val jobName: String, val clientName: String,
)

data class PendingFolder(val jobId: String, val clientName: String, val jobName: String, val locationName: String)

@Entity(
    tableName = "documents",
    foreignKeys = [ForeignKey(entity = LocationEntity::class, parentColumns = ["id"], childColumns = ["locationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("locationId"), Index("status")]
)
data class DocumentEntity(
    @PrimaryKey val id: String, val locationId: String, val contentUri: String, val filename: String,
    val sha256: String, val pageCount: Int, val createdAt: String,
    val status: UploadStatus = UploadStatus.WAITING, val lastError: String? = null,
)

@Entity(
    tableName = "notes",
    foreignKeys = [ForeignKey(entity = LocationEntity::class, parentColumns = ["id"], childColumns = ["locationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("locationId"), Index("status")]
)
data class NoteEntity(
    @PrimaryKey val id: String, val locationId: String, val title: String, val content: String,
    val updatedAt: String, val status: UploadStatus = UploadStatus.WAITING, val lastError: String? = null,
)

data class PendingDocument(
    val id: String, val jobId: String, val contentUri: String, val filename: String, val sha256: String, val pageCount: Int,
    val createdAt: String, val locationName: String, val jobName: String, val clientName: String,
)

data class PendingNote(
    val id: String, val jobId: String, val title: String, val content: String, val updatedAt: String,
    val locationName: String, val jobName: String, val clientName: String,
)

data class JobActivity(val jobId: String, val lastPhotoAt: String?)
