package org.opencall.archive.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencall.archive.core.BackupSnapshot
import org.opencall.archive.core.restore.FileRestoreJournalStore
import org.opencall.archive.core.restore.JournalSummary
import org.opencall.archive.core.restore.RestoreExecutor
import org.opencall.archive.core.restore.RestoreOptions
import org.opencall.archive.core.restore.RestorePlan
import org.opencall.archive.core.restore.RestorePlanner
import org.opencall.archive.core.restore.RestoreProgress
import org.opencall.archive.core.restore.RestoreReport
import java.util.UUID

sealed class RestoreState {
    data object Idle : RestoreState()
    data class Running(val sessionId: String, val backupLabel: String, val progress: RestoreProgress) : RestoreState()
    data class Finished(val report: RestoreReport) : RestoreState()
    data class Failed(val message: String) : RestoreState()
}

/**
 * Runs restores in an application-scoped coroutine so they survive configuration changes
 * and leaving the restore screen. If the process is killed, the journal shows the session
 * as interrupted and a retry skips everything already inserted.
 */
class RestoreController(private val context: Context, private val repository: ArchiveRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val journal = FileRestoreJournalStore(context.filesDir.resolve("restore_journals"))
    private var job: Job? = null

    private val _state = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val state: StateFlow<RestoreState> = _state.asStateFlow()

    val isRunning get() = _state.value is RestoreState.Running

    /** Builds a preview against the current call log. Read-only. */
    suspend fun plan(snapshot: BackupSnapshot): RestorePlan = withContext(Dispatchers.IO) {
        val live = repository.gateway.readAll()
        RestorePlanner.plan(snapshot, live, journal.restoredFingerprints(), System.currentTimeMillis())
    }

    suspend fun providerColumns(): Set<String>? = withContext(Dispatchers.IO) {
        try { repository.gateway.providerColumns() } catch (e: Exception) { null }
    }

    fun start(plan: RestorePlan, options: RestoreOptions): Boolean {
        if (isRunning) return false
        if (!CallLogAccess.hasWrite(context) || !CallLogAccess.hasRead(context)) {
            _state.value = RestoreState.Failed("Call log permission is not granted.")
            return false
        }
        val sessionId = UUID.randomUUID().toString()
        val total = plan.toInsert(options.policy).size
        _state.value = RestoreState.Running(sessionId, plan.backupLabel, RestoreProgress(0, total, 0, 0))
        job = scope.launch {
            val executor = RestoreExecutor(repository.gateway, journal, Build.VERSION.SDK_INT)
            try {
                val report = executor.execute(sessionId, plan, options) { p ->
                    _state.value = RestoreState.Running(sessionId, plan.backupLabel, p)
                }
                _state.value = RestoreState.Finished(report)
            } catch (e: kotlinx.coroutines.CancellationException) {
                val summary = journal.sessions().firstOrNull { it.header.sessionId == sessionId }
                _state.value = RestoreState.Failed(
                    "Restore cancelled. ${summary?.restored ?: 0} entries had already been restored and verified; " +
                        "retrying will skip them.",
                )
            } catch (e: Exception) {
                _state.value = RestoreState.Failed("Restore could not start: ${e.javaClass.simpleName}")
            }
        }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    /** Clears a finished/failed state once the user has seen it. */
    fun acknowledge() {
        if (!isRunning) _state.value = RestoreState.Idle
    }

    fun sessions(): List<JournalSummary> = journal.sessions()
}
