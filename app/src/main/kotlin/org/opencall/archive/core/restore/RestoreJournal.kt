package org.opencall.archive.core.restore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

data class JournalHeader(
    val sessionId: String,
    val backupId: String,
    val backupLabel: String,
    val startedAt: Long,
    val options: RestoreOptions,
    val totalInBackup: Int,
    val planned: Int,
)

/** Summary of a past or interrupted restore session, reconstructed from its journal. */
data class JournalSummary(
    val header: JournalHeader,
    val status: SessionStatus,
    val attempted: Int,
    val restored: Int,
    val failed: Int,
    /** Inserts started but without a recorded outcome (process died mid-insert). */
    val unresolved: Int,
    val finishedAt: Long?,
    val report: RestoreReportSummary?,
)

/** The subset of a [RestoreReport] that is persisted at the end of a session. */
data class RestoreReportSummary(
    val restored: Int,
    val restoredReduced: Int,
    val failedInsert: Int,
    val failedVerification: Int,
    val skipped: Int,
    val invalid: Int,
    val notEligible: Int,
    val notAttempted: Int,
    val presentInFinalCheck: Int?,
    val abortReason: String?,
)

interface RestoreJournalStore {
    fun begin(header: JournalHeader)
    fun attempt(sessionId: String, fingerprint: String)
    fun outcome(sessionId: String, fingerprint: String, outcome: EntryOutcome, detail: String?)
    fun finish(sessionId: String, report: RestoreReport)

    /**
     * Fingerprints this app inserted in earlier sessions, including unresolved attempts
     * (which may or may not have reached the provider). Used to avoid re-inserting.
     */
    fun restoredFingerprints(): Set<String>
    fun sessions(): List<JournalSummary>
}

/**
 * Append-only JSON-lines journal, one file per session, in app-private storage.
 * Each line is flushed immediately so the journal survives the process being killed.
 * Journals hold fingerprints (which include phone numbers), so they never leave the app.
 */
class FileRestoreJournalStore(private val dir: File) : RestoreJournalStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val writers = HashMap<String, OutputStreamWriter>()

    /** Sessions running in this process; anything else without an end line was interrupted. */
    private val active = HashSet<String>()

    private fun file(sessionId: String) = File(dir, "$sessionId.jsonl")

    @Synchronized
    private fun append(sessionId: String, line: JsonObject) {
        val w = writers.getOrPut(sessionId) {
            dir.mkdirs()
            OutputStreamWriter(FileOutputStream(file(sessionId), true), Charsets.UTF_8)
        }
        w.write(line.toString())
        w.write("\n")
        w.flush()
    }

    override fun begin(header: JournalHeader) {
        synchronized(this) { active += header.sessionId }
        append(header.sessionId, buildJsonObject {
            put("kind", "begin")
            put("sessionId", header.sessionId)
            put("backupId", header.backupId)
            put("backupLabel", header.backupLabel)
            put("startedAt", header.startedAt)
            put("policy", header.options.policy.name)
            put("keepPhoneAccount", header.options.keepPhoneAccount)
            put("markAsRead", header.options.markAsRead)
            put("totalInBackup", header.totalInBackup)
            put("planned", header.planned)
        })
    }

    override fun attempt(sessionId: String, fingerprint: String) =
        append(sessionId, buildJsonObject { put("kind", "attempt"); put("fp", fingerprint) })

    override fun outcome(sessionId: String, fingerprint: String, outcome: EntryOutcome, detail: String?) =
        append(sessionId, buildJsonObject {
            put("kind", "outcome"); put("fp", fingerprint); put("outcome", outcome.name)
            detail?.let { put("detail", it) }
        })

    override fun finish(sessionId: String, report: RestoreReport) {
        append(sessionId, buildJsonObject {
            put("kind", "end")
            put("status", report.status.name)
            put("finishedAt", report.finishedAt ?: System.currentTimeMillis())
            put("restored", report.restored)
            put("restoredReduced", report.restoredReduced)
            put("failedInsert", report.failedInsert)
            put("failedVerification", report.failedVerification)
            put("skipped", report.skipped)
            put("invalid", report.invalid)
            put("notEligible", report.notEligible)
            put("notAttempted", report.notAttempted)
            report.presentInFinalCheck?.let { put("presentInFinalCheck", it) }
            report.abortReason?.let { put("abortReason", it) }
        })
        synchronized(this) {
            writers.remove(sessionId)?.close()
            active -= sessionId
        }
    }

    override fun restoredFingerprints(): Set<String> {
        val result = HashSet<String>()
        for (f in journalFiles()) {
            val pending = HashSet<String>()
            readLines(f) { obj ->
                val fp = obj.str("fp") ?: return@readLines
                when (obj.str("kind")) {
                    "attempt" -> pending += fp
                    "outcome" -> {
                        pending -= fp
                        val outcome = obj.str("outcome")?.let { runCatching { EntryOutcome.valueOf(it) }.getOrNull() }
                        if (outcome?.isRestored == true) result += fp
                    }
                }
            }
            result += pending
        }
        return result
    }

    override fun sessions(): List<JournalSummary> =
        journalFiles().mapNotNull { summarize(it) }.sortedByDescending { it.header.startedAt }

    private fun summarize(f: File): JournalSummary? {
        var header: JournalHeader? = null
        var end: JsonObject? = null
        val pending = HashSet<String>()
        var attempted = 0
        var restored = 0
        var failed = 0
        readLines(f) { obj ->
            when (obj.str("kind")) {
                "begin" -> header = JournalHeader(
                    sessionId = obj.str("sessionId") ?: return@readLines,
                    backupId = obj.str("backupId").orEmpty(),
                    backupLabel = obj.str("backupLabel").orEmpty(),
                    startedAt = obj.long("startedAt") ?: 0L,
                    options = RestoreOptions(
                        policy = obj.str("policy")?.let { runCatching { DuplicatePolicy.valueOf(it) }.getOrNull() }
                            ?: DuplicatePolicy.SKIP_LIKELY_DUPLICATES,
                        keepPhoneAccount = (obj["keepPhoneAccount"] as? JsonPrimitive)?.booleanOrNull ?: true,
                        markAsRead = (obj["markAsRead"] as? JsonPrimitive)?.booleanOrNull ?: true,
                    ),
                    totalInBackup = obj.int("totalInBackup") ?: 0,
                    planned = obj.int("planned") ?: 0,
                )
                "attempt" -> { attempted++; obj.str("fp")?.let { pending += it } }
                "outcome" -> {
                    obj.str("fp")?.let { pending -= it }
                    val o = obj.str("outcome")?.let { runCatching { EntryOutcome.valueOf(it) }.getOrNull() }
                    if (o?.isRestored == true) restored++ else failed++
                }
                "end" -> end = obj
            }
        }
        val h = header ?: return null
        val e = end
        val status = when {
            e != null -> e.str("status")?.let { runCatching { SessionStatus.valueOf(it) }.getOrNull() } ?: SessionStatus.COMPLETED
            synchronized(this) { h.sessionId in active } -> SessionStatus.RUNNING
            else -> SessionStatus.INTERRUPTED
        }
        val summary = e?.let {
            RestoreReportSummary(
                restored = it.int("restored") ?: 0,
                restoredReduced = it.int("restoredReduced") ?: 0,
                failedInsert = it.int("failedInsert") ?: 0,
                failedVerification = it.int("failedVerification") ?: 0,
                skipped = it.int("skipped") ?: 0,
                invalid = it.int("invalid") ?: 0,
                notEligible = it.int("notEligible") ?: 0,
                notAttempted = it.int("notAttempted") ?: 0,
                presentInFinalCheck = it.int("presentInFinalCheck"),
                abortReason = it.str("abortReason"),
            )
        }
        return JournalSummary(h, status, attempted, restored, failed, pending.size, e?.long("finishedAt"), summary)
    }

    private fun journalFiles(): List<File> = dir.listFiles { f -> f.name.endsWith(".jsonl") }?.toList().orEmpty()

    private inline fun readLines(f: File, crossinline onLine: (JsonObject) -> Unit) {
        try {
            f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    // A torn final line (crash mid-write) is ignored rather than failing the journal.
                    val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                    onLine(obj)
                }
            }
        } catch (e: java.io.IOException) {
            // Unreadable journal: treated as empty.
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
}
