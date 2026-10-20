package org.opencall.archive.core.restore

import org.opencall.archive.core.BackupSnapshot
import org.opencall.archive.core.CallRecord
import org.opencall.archive.core.CallTypes
import org.opencall.archive.core.MatchLevel
import org.opencall.archive.core.RecordIndex

enum class DuplicatePolicy {
    /** Restore only records with no confirmed or probable match (recommended). */
    SKIP_LIKELY_DUPLICATES,

    /** Skip only exact matches; probable matches are restored. */
    SKIP_CONFIRMED_ONLY,

    /** Restore every eligible record, even exact matches. Can create duplicate entries. */
    RESTORE_ALL,
}

data class RestoreOptions(
    val policy: DuplicatePolicy = DuplicatePolicy.SKIP_LIKELY_DUPLICATES,
    /** Write SIM / phone-account columns. Disable if the original SIM is not on this device. */
    val keepPhoneAccount: Boolean = true,
    /** Store restored calls as already seen so old missed calls are not shown as new. */
    val markAsRead: Boolean = true,
)

enum class PlanStatus {
    ELIGIBLE,
    CONFIRMED_DUPLICATE,
    PROBABLE_DUPLICATE,
    DUPLICATE_IN_BACKUP,
    /** Restored by this app earlier and still present: never inserted again. */
    ALREADY_RESTORED,
    INVALID,
    NOT_ELIGIBLE,
}

data class PlanItem(val index: Int, val record: CallRecord, val status: PlanStatus, val detail: String? = null)

data class RestorePlan(
    val backupId: String,
    val backupLabel: String,
    val items: List<PlanItem>,
    val liveRecordCount: Int,
) {
    fun count(status: PlanStatus) = items.count { it.status == status }

    fun toInsert(policy: DuplicatePolicy): List<PlanItem> = items.filter { willInsert(it.status, policy) }

    companion object {
        fun willInsert(status: PlanStatus, policy: DuplicatePolicy): Boolean = when (status) {
            PlanStatus.ELIGIBLE -> true
            PlanStatus.PROBABLE_DUPLICATE -> policy != DuplicatePolicy.SKIP_LIKELY_DUPLICATES
            PlanStatus.CONFIRMED_DUPLICATE, PlanStatus.DUPLICATE_IN_BACKUP -> policy == DuplicatePolicy.RESTORE_ALL
            PlanStatus.ALREADY_RESTORED, PlanStatus.INVALID, PlanStatus.NOT_ELIGIBLE -> false
        }
    }
}

object RestoreEligibility {
    private const val FUTURE_TOLERANCE_MS = 24L * 60 * 60 * 1000
    private const val MAX_DURATION_S = 31L * 24 * 60 * 60

    sealed class Verdict {
        data object Ok : Verdict()
        data class Invalid(val reason: String) : Verdict()
        data class NotEligible(val reason: String) : Verdict()
    }

    fun check(record: CallRecord, now: Long): Verdict = when {
        record.date <= 0L -> Verdict.Invalid("Missing or invalid call timestamp.")
        record.date > now + FUTURE_TOLERANCE_MS -> Verdict.Invalid("Call timestamp is in the future.")
        record.duration < 0L -> Verdict.Invalid("Negative call duration.")
        record.duration > MAX_DURATION_S -> Verdict.Invalid("Implausible call duration.")
        record.type == CallTypes.VOICEMAIL -> Verdict.NotEligible("Voicemail entries can only be written by the device's voicemail app.")
        record.type !in CallTypes.KNOWN -> Verdict.NotEligible("Call type ${record.type} is not supported by Android's call log.")
        else -> Verdict.Ok
    }
}

/** Builds a read-only restore preview. Does not touch the provider. */
object RestorePlanner {

    /**
     * @param live current device call log.
     * @param previouslyRestored fingerprints this app has inserted (or may have inserted) in
     *        earlier restore sessions; see [RestoreJournalStore.restoredFingerprints].
     */
    fun plan(
        backup: BackupSnapshot,
        live: List<CallRecord>,
        previouslyRestored: Set<String>,
        now: Long,
    ): RestorePlan {
        val index = RecordIndex(live)
        val seenInBackup = HashSet<String>()
        val items = backup.records.mapIndexed { i, record ->
            when (val verdict = RestoreEligibility.check(record, now)) {
                is RestoreEligibility.Verdict.Invalid -> return@mapIndexed PlanItem(i, record, PlanStatus.INVALID, verdict.reason)
                is RestoreEligibility.Verdict.NotEligible -> return@mapIndexed PlanItem(i, record, PlanStatus.NOT_ELIGIBLE, verdict.reason)
                RestoreEligibility.Verdict.Ok -> Unit
            }
            if (!seenInBackup.add(record.fingerprint)) {
                return@mapIndexed PlanItem(i, record, PlanStatus.DUPLICATE_IN_BACKUP, "Appears more than once in this backup.")
            }
            val match = index.bestMatch(record)
            when (match?.second?.level) {
                MatchLevel.CONFIRMED ->
                    if (record.fingerprint in previouslyRestored) {
                        PlanItem(i, record, PlanStatus.ALREADY_RESTORED, "Restored earlier by this app and still present.")
                    } else {
                        PlanItem(i, record, PlanStatus.CONFIRMED_DUPLICATE, "Identical call already in call history.")
                    }
                MatchLevel.PROBABLE ->
                    PlanItem(i, record, PlanStatus.PROBABLE_DUPLICATE, "Similar call exists: " + match.second.reasons.joinToString("; "))
                else -> PlanItem(i, record, PlanStatus.ELIGIBLE)
            }
        }
        return RestorePlan(backup.id, backup.label, items, live.size)
    }

    /** Per-column report: how each field present in the backup will be handled. */
    fun columnReport(
        records: List<CallRecord>,
        sdkInt: Int,
        providerColumns: Set<String>?,
        options: RestoreOptions,
    ): List<Pair<ColumnDecision, Int>> {
        val counts = LinkedHashMap<String, Int>()
        for (r in records) for (c in r.allColumns().keys) counts[c] = (counts[c] ?: 0) + 1
        return counts.map { (column, n) ->
            RestoreColumns.decide(column, sdkInt, providerColumns, options.keepPhoneAccount) to n
        }.sortedWith(compareBy({ it.first.disposition != ColumnDisposition.RESTORED }, { it.first.column }))
    }
}
