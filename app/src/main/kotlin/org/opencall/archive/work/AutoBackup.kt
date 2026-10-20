package org.opencall.archive.work

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.opencall.archive.OpenCallApp
import org.opencall.archive.core.BackupOrigin
import org.opencall.archive.data.BackupResult
import java.util.concurrent.TimeUnit

/** User-configurable automatic backup settings, stored in app-private preferences. */
class AutoBackupSettings(context: Context) {
    private val prefs = context.getSharedPreferences("auto_backup", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit { putBoolean("enabled", v) }

    var intervalDays: Int
        get() = prefs.getInt("interval_days", 1)
        set(v) = prefs.edit { putInt("interval_days", v) }

    var requireCharging: Boolean
        get() = prefs.getBoolean("require_charging", false)
        set(v) = prefs.edit { putBoolean("require_charging", v) }

    var skipUnchanged: Boolean
        get() = prefs.getBoolean("skip_unchanged", true)
        set(v) = prefs.edit { putBoolean("skip_unchanged", v) }

    val lastRunAt: Long get() = prefs.getLong("last_run_at", 0L)
    val lastResult: String? get() = prefs.getString("last_result", null)

    fun recordRun(result: String) = prefs.edit {
        putLong("last_run_at", System.currentTimeMillis())
        putString("last_result", result)
    }
}

object AutoBackupScheduler {
    private const val WORK_NAME = "auto_backup"

    fun apply(context: Context) {
        val settings = AutoBackupSettings(context)
        val wm = WorkManager.getInstance(context)
        if (!settings.enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .setRequiresCharging(settings.requireCharging)
            .build()
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(settings.intervalDays.toLong(), TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

/**
 * Periodic local backup. Android decides the exact run time (Doze, battery saver and
 * standby buckets can delay it). Never uploads anything and never deletes old backups.
 */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as OpenCallApp
        val settings = AutoBackupSettings(applicationContext)
        if (!settings.enabled) return Result.success()
        val message = when (val r = app.repository.createBackup(BackupOrigin.AUTOMATIC, settings.skipUnchanged)) {
            is BackupResult.Saved -> "Saved ${r.meta.recordCount} records"
            is BackupResult.Unchanged -> "Skipped: call history unchanged since the last backup"
            BackupResult.EmptyCallLog -> "Skipped: call history is empty"
            BackupResult.PermissionMissing -> "Failed: call log permission is no longer granted"
            BackupResult.ProviderUnavailable -> "Failed: this device has no call log"
            is BackupResult.Failed -> "Failed: ${r.message}"
        }
        settings.recordRun(message)
        // Permission problems will not fix themselves by retrying; the status is shown in Settings.
        return Result.success()
    }
}
