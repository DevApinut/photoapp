package com.fieldphoto.app

import android.app.Application
import com.fieldphoto.app.data.AppDatabase
import com.fieldphoto.app.data.PhotoRepository

class PhotoApp : Application() {
    val database by lazy { AppDatabase.create(this) }
    val repository by lazy { PhotoRepository(this, database.dao()) }
}

