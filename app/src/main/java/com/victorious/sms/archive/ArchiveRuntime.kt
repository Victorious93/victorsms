package com.victorious.sms.archive

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.victorious.comms.Archive
import com.victorious.comms.DirOriginalStore
import com.victorious.comms.ImportEngine
import java.io.File

/**
 * Victorious Framework: the one archive of this install (app-private storage; not separately encrypted).
 * Original export files are kept content-addressed under `files/originals`. Both are excluded from cloud
 * backup in the manifest: an archive of private conversations must not leave the phone by default.
 */
object ArchiveRuntime {
    class Handle(val archive: Archive, val engine: ImportEngine)

    @Volatile private var instance: Handle? = null

    fun get(context: Context): Handle = instance ?: synchronized(this) {
        instance ?: open(context.applicationContext).also { instance = it }
    }

    private fun open(app: Context): Handle {
        val file = app.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.enableWriteAheadLogging()
        val archive = Archive(AndroidSqlDb(db), DirOriginalStore(File(app.filesDir, "originals")))
        return Handle(archive, ImportEngine(archive))
    }

    const val DB_NAME = "archive.db"
}
