package org.opencall.archive

import android.app.Application
import org.opencall.archive.data.ArchiveRepository
import org.opencall.archive.data.BackupStore
import org.opencall.archive.data.RestoreController

class OpenCallApp : Application() {
    lateinit var repository: ArchiveRepository
        private set
    lateinit var restoreController: RestoreController
        private set

    override fun onCreate() {
        super.onCreate()
        repository = ArchiveRepository(this, BackupStore(filesDir.resolve("backups")))
        restoreController = RestoreController(this, repository)
    }
}
