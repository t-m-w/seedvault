package com.stevesoltys.seedvault.storage

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.stevesoltys.seedvault.transport.requestBackup
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.calyxos.backup.storage.api.BackupObserver
import org.calyxos.backup.storage.api.RestoreObserver
import org.calyxos.backup.storage.api.StorageBackup
import org.calyxos.backup.storage.backup.BackupJobService
import org.calyxos.backup.storage.backup.BackupService
import org.calyxos.backup.storage.backup.NotificationBackupObserver
import org.calyxos.backup.storage.restore.NotificationRestoreObserver
import org.calyxos.backup.storage.restore.RestoreService
import org.koin.android.ext.android.inject

/*
test and debug with

  adb shell dumpsys jobscheduler |
  grep -A 23 -B 4 "Service: com.stevesoltys.seedvault/.storage.StorageBackupJobService"

force running with:

  adb shell cmd jobscheduler run -f com.stevesoltys.seedvault 0

 */
internal class StorageBackupJobService : BackupJobService(StorageBackupService::class.java)

internal class StorageBackupService : BackupService() {

    companion object {
        internal const val EXTRA_START_APP_BACKUP = "startAppBackup"
    }

    override val storageBackup: StorageBackup by inject()

    // use lazy delegate because context isn't available during construction time
    override val backupObserver: BackupObserver by lazy {
        NotificationBackupObserver(applicationContext)
    }

    override fun onBackupFinished(intent: Intent, success: Boolean) {
        if (intent.getBooleanExtra(EXTRA_START_APP_BACKUP, false)) {
            requestBackup(applicationContext)
        }
    }
}

internal class StorageRestoreService : RestoreService() {
    override val storageBackup: StorageBackup by inject()

    // use lazy delegate because context isn't available during construction time
    override val restoreObserver: RestoreObserver by lazy {
        NotificationRestoreObserver(applicationContext)
    }
}

// TODO: Refactor this to be outside of the storage backup package
internal class AppDataBackupJobService : BackupJobService(AppDataBackupService::class.java)

internal class AppDataBackupService : Service() {
    companion object {
        private const val TAG = "AppDataBackupService"
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand $intent $flags $startId")
        GlobalScope.launch {
            requestBackup(applicationContext)
            stopSelf(startId)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
    }
}
