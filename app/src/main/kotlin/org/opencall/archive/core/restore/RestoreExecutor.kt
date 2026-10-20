package org.opencall.archive.core.restore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.opencall.archive.core.CallRecord
import org.opencall.archive.core.Columns
import org.opencall.archive.core.FieldValue
import org.opencall.archive.core.MatchLevel
import org.opencall.archive.core.PhoneNumbers
import org.opencall.archive.core.RecordIndex

/** Abstraction over Android's CallLog provider so the restore logic can be unit tested. */
interface CallLogGateway {
    /** Column names the provider exposes on this device. */
    fun providerColumns(): Set<String>

    fun readAll(): List<CallRecord>

    /** @return an opaque handle (the row URI) or null when the provider rejected the insert. */
    fun insert(values: Map<String, FieldValue>): String?

    /** Re-reads a row inserted by [insert], or null when it does not exist. */
    fun readBack(handle: String): CallRecord?
}

enum class EntryOutcome {
    RESTORED,
    RESTORED_REDUCED,
    FAILED_INSERT,
    FAILED_VERIFICATION,
    ;

    val isRestored get() = this == RESTORED || this == RESTORED_REDUCED
}

enum class SessionStatus { RUNNING, COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED, ABORTED, INTERRUPTED }

data class FailureDetail(val index: Int, val record: CallRecord, val outcome: EntryOutcome, val reason: String)

data class RestoreReport(
    val sessionId: String,
    val backupId: String,
    val backupLabel: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val status: SessionStatus,
    val options: RestoreOptions,
    val totalInBackup: Int,
    val planned: Int,
    val restored: Int,
    val restoredReduced: Int,
    val failedInsert: Int,
    val failedVerification: Int,
    val skippedConfirmed: Int,
    val skippedProbable: Int,
    val skippedInBackup: Int,
    val skippedAlreadyRestored: Int,
    val invalid: Int,
    val notEligible: Int,
    val notAttempted: Int,
    /** Of the verified entries, how many a final full re-query still found (null if not run). */
    val presentInFinalCheck: Int?,
    val failures: List<FailureDetail>,
    val abortReason: String?,
) {
    val successfullyRestored get() = restored + restoredReduced
    val failed get() = failedInsert + failedVerification
    val skipped get() = skippedConfirmed + skippedProbable + skippedInBackup + skippedAlreadyRestored
}

data class RestoreProgress(val done: Int, val total: Int, val restored: Int, val failed: Int)

class PermissionLostException(message: String) : Exception(message)

/**
 * Writes planned records into the call log, one insert at a time, verifying each insert
 * by reading the row back. Every attempt is journaled *before* the insert so that an
 * interruption can never leave an unaccounted insert that a retry might duplicate.
 */
class RestoreExecutor(
    private val gateway: CallLogGateway,
    private val journal: RestoreJournalStore,
    private val sdkInt: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(
        sessionId: String,
        plan: RestorePlan,
        options: RestoreOptions,
        onProgress: (RestoreProgress) -> Unit = {},
    ): RestoreReport {
        val startedAt = clock()
        val toInsert = plan.toInsert(options.policy)
        val providerColumns = gateway.providerColumns()
        val writable = RestoreColumns.writableColumns(sdkInt, providerColumns, options.keepPhoneAccount)

        journal.begin(JournalHeader(sessionId, plan.backupId, plan.backupLabel, startedAt, options, plan.items.size, toInsert.size))

        val outcomes = ArrayList<Pair<PlanItem, EntryOutcome>>(toInsert.size)
        val failures = mutableListOf<FailureDetail>()
        var abortReason: String? = null
        var status = SessionStatus.COMPLETED
        var restored = 0
        var failed = 0

        try {
            for ((n, item) in toInsert.withIndex()) {
                currentCoroutineContext().ensureActive()
                val fp = item.record.fingerprint
                journal.attempt(sessionId, fp)
                val (outcome, reason) = try {
                    insertAndVerify(item.record, writable, options)
                } catch (e: SecurityException) {
                    throw PermissionLostException("Android denied write access to the call log (permission revoked or restricted).")
                }
                journal.outcome(sessionId, fp, outcome, reason)
                outcomes += item to outcome
                if (outcome.isRestored) restored++ else {
                    failed++
                    failures += FailureDetail(item.index, item.record, outcome, reason ?: "Unknown error")
                }
                onProgress(RestoreProgress(n + 1, toInsert.size, restored, failed))
            }
        } catch (e: CancellationException) {
            status = SessionStatus.CANCELLED
            abortReason = "Restore was cancelled."
            val report = buildReport(sessionId, plan, options, startedAt, status, toInsert, outcomes, failures, null, abortReason)
            journal.finish(sessionId, report)
            throw e
        } catch (e: PermissionLostException) {
            status = SessionStatus.ABORTED
            abortReason = e.message
        } catch (e: Exception) {
            status = SessionStatus.ABORTED
            abortReason = "Call log provider error: ${e.javaClass.simpleName}"
        }

        // Final check: re-query the whole provider and confirm verified rows are still there
        // (some devices trim very old entries or a sync app may rewrite the log).
        val verified = outcomes.filter { it.second.isRestored }.map { it.first.record }
        val present = if (verified.isEmpty()) 0 else try {
            val index = RecordIndex(gateway.readAll())
            val used = HashSet<Int>()
            verified.count { r ->
                val m = index.bestMatch(r, used)
                if (m != null && m.second.level == MatchLevel.CONFIRMED) { used += m.first.index; true } else false
            }
        } catch (e: Exception) {
            null
        }

        if (status == SessionStatus.COMPLETED && (failures.isNotEmpty() || (present != null && present < verified.size))) {
            status = SessionStatus.COMPLETED_WITH_ERRORS
        }
        val report = buildReport(sessionId, plan, options, startedAt, status, toInsert, outcomes, failures, present, abortReason)
        journal.finish(sessionId, report)
        return report
    }

    private fun insertAndVerify(
        record: CallRecord,
        writable: Set<String>,
        options: RestoreOptions,
    ): Pair<EntryOutcome, String?> {
        val full = buildValues(record, writable, options)
        var reduced = false
        val handle = try {
            gateway.insert(full)
        } catch (e: IllegalArgumentException) {
            // Some OEM providers reject a column they nominally expose. Retry with the
            // essential columns only and report the entry as restored with fewer fields.
            reduced = true
            try {
                gateway.insert(full.filterKeys { it in RestoreColumns.ESSENTIAL })
            } catch (e2: IllegalArgumentException) {
                return EntryOutcome.FAILED_INSERT to "Provider rejected the entry's values."
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            return EntryOutcome.FAILED_INSERT to "Provider error: ${e.javaClass.simpleName}"
        } ?: return EntryOutcome.FAILED_INSERT to "Provider did not accept the entry (no row created)."

        val stored = try {
            gateway.readBack(handle)
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return EntryOutcome.FAILED_VERIFICATION to "Entry could not be found when read back after insertion."

        val mismatches = buildList {
            if (PhoneNumbers.normalize(stored.number) != PhoneNumbers.normalize(record.number)) add("number")
            if (stored.date != record.date) add("date")
            if (stored.duration != record.duration) add("duration")
            if (stored.type != record.type) add("type")
        }
        if (mismatches.isNotEmpty()) {
            return EntryOutcome.FAILED_VERIFICATION to "Stored entry differs from backup in: ${mismatches.joinToString()}."
        }
        return if (reduced) EntryOutcome.RESTORED_REDUCED to "Restored with essential fields only; the provider rejected some metadata."
        else EntryOutcome.RESTORED to null
    }

    private fun buildValues(record: CallRecord, writable: Set<String>, options: RestoreOptions): Map<String, FieldValue> {
        val values = LinkedHashMap<String, FieldValue>()
        for ((k, v) in record.allColumns()) if (k in writable) values[k] = v
        if (options.markAsRead) {
            if (Columns.NEW in writable) values[Columns.NEW] = FieldValue.Integer(0)
            if (Columns.IS_READ in writable) values[Columns.IS_READ] = FieldValue.Integer(1)
        }
        return values
    }

    private fun buildReport(
        sessionId: String,
        plan: RestorePlan,
        options: RestoreOptions,
        startedAt: Long,
        status: SessionStatus,
        toInsert: List<PlanItem>,
        outcomes: List<Pair<PlanItem, EntryOutcome>>,
        failures: List<FailureDetail>,
        present: Int?,
        abortReason: String?,
    ): RestoreReport {
        fun skipped(s: PlanStatus) = if (RestorePlan.willInsert(s, options.policy)) 0 else plan.count(s)
        return RestoreReport(
            sessionId = sessionId,
            backupId = plan.backupId,
            backupLabel = plan.backupLabel,
            startedAt = startedAt,
            finishedAt = clock(),
            status = status,
            options = options,
            totalInBackup = plan.items.size,
            planned = toInsert.size,
            restored = outcomes.count { it.second == EntryOutcome.RESTORED },
            restoredReduced = outcomes.count { it.second == EntryOutcome.RESTORED_REDUCED },
            failedInsert = outcomes.count { it.second == EntryOutcome.FAILED_INSERT },
            failedVerification = outcomes.count { it.second == EntryOutcome.FAILED_VERIFICATION },
            skippedConfirmed = skipped(PlanStatus.CONFIRMED_DUPLICATE),
            skippedProbable = skipped(PlanStatus.PROBABLE_DUPLICATE),
            skippedInBackup = skipped(PlanStatus.DUPLICATE_IN_BACKUP),
            skippedAlreadyRestored = plan.count(PlanStatus.ALREADY_RESTORED),
            invalid = plan.count(PlanStatus.INVALID),
            notEligible = plan.count(PlanStatus.NOT_ELIGIBLE),
            notAttempted = toInsert.size - outcomes.size,
            presentInFinalCheck = present,
            failures = failures,
            abortReason = abortReason,
        )
    }
}
