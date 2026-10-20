package org.opencall.archive.core

/** A call record together with the archived snapshots it was found in. */
data class ArchivedHit(
    val record: CallRecord,
    /** Snapshot IDs containing this call, newest first. */
    val backupIds: List<String>,
)

data class SearchCriteria(
    /** Matches the cached contact name (case-insensitive) or any part of the number. */
    val text: String = "",
    /** Inclusive lower bound, epoch ms. */
    val from: Long? = null,
    /** Exclusive upper bound, epoch ms. */
    val until: Long? = null,
    /** Empty means all types. */
    val types: Set<Int> = emptySet(),
    val minDurationSeconds: Long? = null,
    val maxDurationSeconds: Long? = null,
    /** Null searches all backups. */
    val backupId: String? = null,
)

/** Offline search over archived snapshots (never the live provider). */
object SearchEngine {

    /**
     * @param snapshots snapshots to search, newest first. When several snapshots contain the
     * same call (same fingerprint) it is returned once, listing every snapshot it occurs in.
     */
    fun search(snapshots: List<BackupSnapshot>, criteria: SearchCriteria): List<ArchivedHit> {
        val selected = if (criteria.backupId == null) snapshots else snapshots.filter { it.id == criteria.backupId }
        val hits = LinkedHashMap<String, Pair<CallRecord, MutableList<String>>>()
        for (snapshot in selected) {
            for (record in snapshot.records) {
                if (!matches(record, criteria)) continue
                val entry = hits.getOrPut(record.fingerprint) { record to mutableListOf() }
                if (snapshot.id !in entry.second) entry.second += snapshot.id
            }
        }
        return hits.values
            .map { (record, ids) -> ArchivedHit(record, ids) }
            .sortedByDescending { it.record.date }
    }

    fun matches(record: CallRecord, c: SearchCriteria): Boolean {
        if (c.from != null && record.date < c.from) return false
        if (c.until != null && record.date >= c.until) return false
        if (c.types.isNotEmpty() && record.type !in c.types) return false
        if (c.minDurationSeconds != null && record.duration < c.minDurationSeconds) return false
        if (c.maxDurationSeconds != null && record.duration > c.maxDurationSeconds) return false
        return matchesText(record, c.text)
    }

    private fun matchesText(record: CallRecord, rawQuery: String): Boolean {
        val query = rawQuery.trim()
        if (query.isEmpty()) return true
        if (record.cachedName?.contains(query, ignoreCase = true) == true) return true
        if (record.number.contains(query, ignoreCase = true)) return true
        // Partial number search ignores formatting: "98765" finds "+91 98765-43210".
        val queryDigits = PhoneNumbers.digitsOnly(query)
        val looksNumeric = queryDigits.isNotEmpty() && query.all { it.isDigit() || it in " +-().#*" }
        if (looksNumeric && PhoneNumbers.digitsOnly(record.number).contains(queryDigits)) return true
        return false
    }
}
