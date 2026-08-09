package com.fieldphoto.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter fun fromStatus(value: UploadStatus) = value.name
    @TypeConverter fun toStatus(value: String) = UploadStatus.valueOf(value)
}

@Database(entities = [ClientEntity::class, JobEntity::class, LocationEntity::class, PhotoEntity::class, DocumentEntity::class, NoteEntity::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS documents (id TEXT NOT NULL PRIMARY KEY, locationId TEXT NOT NULL, contentUri TEXT NOT NULL, filename TEXT NOT NULL, sha256 TEXT NOT NULL, pageCount INTEGER NOT NULL, createdAt TEXT NOT NULL, status TEXT NOT NULL, lastError TEXT, FOREIGN KEY(locationId) REFERENCES locations(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_locationId ON documents(locationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_status ON documents(status)")
                db.execSQL("CREATE TABLE IF NOT EXISTS notes (id TEXT NOT NULL PRIMARY KEY, locationId TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, updatedAt TEXT NOT NULL, status TEXT NOT NULL, lastError TEXT, FOREIGN KEY(locationId) REFERENCES locations(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_locationId ON notes(locationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_status ON notes(status)")
            }
        }
        fun create(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "photo-work.sqlite3")
            .addMigrations(MIGRATION_1_2).fallbackToDestructiveMigration().build()
    }
}
