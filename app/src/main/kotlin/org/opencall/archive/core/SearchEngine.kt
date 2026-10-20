package org.opencall.archive.core

/** How an archived call matched the text query. */
enum class MatchSource {
    /** No text query; only the other filters applied. */
    FILTERS_ONLY,

    /** The call's own cached contact name contains the query. */
    NAME,

    /** The call's number contains, or is a reformatted form of, the queried number. */
    NUMBER,

    /** The call has no matching cached name, but its number carried that name on other calls. */
    NAME_VIA_NUMBER,

    /** The call's number belongs to a current contact whose name matches. */
    CONTACT_NUMBER,
}

/** A call record together with the archived snapshots it was found in. */
data class ArchivedHit(
    val record: CallRecord,
    /** Snapshot IDs containing this call, newest first. */
    val backupIds: List<String>,
    val matchedBy: MatchSource = MatchSource.FILTERS_ONLY,
)

data class SearchCriteria(
    /** Matches a contact name (case-insensitive) or any part of a number. */
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
    /**
     * Phone numbers of current contacts whose name matches [text] (looked up by the caller).
     * Calls to these numbers match even if their cached name is missing or outdated.
     */
    val contactNumbers: Set<String> = emptySet(),
)

data class SearchResult(
    val hits: List<ArchivedHit>,
    /** Records examined across the selected snapshots. */
    val recordsSearched: Int,
    /** Distinct numbers linked to the queried name (via cached names or contacts). */
    val linkedNumbers: Int,
) {
    val total: Int get() = hits.size
    fun count(source: MatchSource) = hits.count { it.matchedBy == source }
}

/** Offline search over archived snapshots (never the live provider). Results are not paginated. */
object SearchEngine {

    /**
     * Every matching record is returned. Identical rows inside one snapshot are distinct calls
     * and are all kept; the same call present in several snapshots is returned once, listing every
     * snapshot it occurs in (the k-th copy in one snapshot pairs with the k-th copy in another).
     *
     * @param snapshots snapshots to search, newest first.
     */
    fun search(snapshots: List<BackupSnapshot>, criteria: SearchCriteria): SearchResult {
        val selected = if (criteria.backupId == null) snapshots else snapshots.filter { it.id == criteria.backupId }
        val query = Query.parse(criteria.text)
        val linked = if (query is Query.Name) linkedNumbers(selected, query.text, criteria.contactNumbers) else null

        val hits = LinkedHashMap<String, Triple<CallRecord, MutableList<String>, MatchSource>>()
        var searched = 0
        for (snapshot in selected) {
            val occurrence = HashMap<String, Int>()
            for (record in snapshot.records) {
                searched++
                if (!matchesFilters(record, criteria)) continue
                val source = matchText(record, query, linked) ?: continue
                val n = occurrence.merge(record.fingerprint, 1, Int::plus)!!
                val entry = hits.getOrPut("${record.fingerprint}#$n") { Triple(record, mutableListOf(), source) }
                if (snapshot.id !in entry.second) entry.second += snapshot.id
            }
        }
        val list = hits.values
            .map { (record, ids, source) -> ArchivedHit(record, ids, source) }
            .sortedByDescending { it.record.date }
        return SearchResult(list, searched, linked?.size ?: 0)
    }

    /** Direct match without name-to-number linking (used to filter a single backup's list). */
    fun matches(record: CallRecord, c: SearchCriteria): Boolean =
        matchesFilters(record, c) && matchText(record, Query.parse(c.text), null) != null

    private fun matchesFilters(record: CallRecord, c: SearchCriteria): Boolean {
        if (c.from != null && record.date < c.from) return false
        if (c.until != null && record.date >= c.until) return false
        if (c.types.isNotEmpty() && record.type !in c.types) return false
        if (c.minDurationSeconds != null && record.duration < c.minDurationSeconds) return false
        if (c.maxDurationSeconds != null && record.duration > c.maxDurationSeconds) return false
        return true
    }

    private sealed class Query {
        data object None : Query()
        data class Name(val text: String) : Query()
        data class Number(val text: String, val digits: String) : Query()

        companion object {
            fun parse(raw: String): Query {
                val q = raw.trim()
                if (q.isEmpty()) return None
                val digits = PhoneNumbers.digitsOnly(q)
                val numeric = digits.isNotEmpty() && q.all { it.isDigit() || it in " +-().#*" }
                return if (numeric) Number(q, digits) else Name(q)
            }
        }
    }

    private fun matchText(record: CallRecord, query: Query, linked: NumberSet?): MatchSource? = when (query) {
        Query.None -> MatchSource.FILTERS_ONLY
        is Query.Name -> when {
            record.cachedName?.contains(query.text, ignoreCase = true) == true -> MatchSource.NAME
            record.number.contains(query.text, ignoreCase = true) -> MatchSource.NUMBER
            linked != null -> linked.sourceOf(record.number)
            else -> null
        }
        is Query.Number -> {
            val recordDigits = PhoneNumbers.digitsOnly(record.number)
            when {
                record.number.contains(query.text) -> MatchSource.NUMBER
                recordDigits.contains(query.digits) -> MatchSource.NUMBER
                // Full numbers in another format: "+91 90870 54184" vs "09087054184".
                query.digits.length >= PhoneNumbers.MIN_SUFFIX_DIGITS && record.number.isNotBlank() &&
                    PhoneNumbers.match(record.number, query.text) != NumberMatch.NONE -> MatchSource.NUMBER
                else -> null
            }
        }
    }

    /**
     * Numbers associated with a name: every number that carried a matching cached name on any
     * searched record, plus [contactNumbers]. Linking is by phone number only, so two people who
     * merely share a name are never merged unless they share a number.
     */
    private fun linkedNumbers(snapshots: List<BackupSnapshot>, name: String, contactNumbers: Set<String>): NumberSet {
        val set = NumberSet()
        for (s in snapshots) for (r in s.records) {
            if (r.number.isNotBlank() && r.cachedName?.contains(name, ignoreCase = true) == true) set.add(r.number, MatchSource.NAME_VIA_NUMBER)
        }
        for (n in contactNumbers) if (n.isNotBlank()) set.add(n, MatchSource.CONTACT_NUMBER)
        return set
    }

    /** Numbers bucketed by their last digits; membership is confirmed with [PhoneNumbers.match]. */
    private class NumberSet {
        private val buckets = HashMap<String, MutableList<Pair<String, MatchSource>>>()
        private val seen = HashSet<String>()
        val size: Int get() = seen.size

        fun add(number: String, source: MatchSource) {
            val normalized = PhoneNumbers.normalize(number)
            if (!seen.add(normalized)) return
            buckets.getOrPut(key(normalized)) { mutableListOf() } += normalized to source
        }

        fun sourceOf(number: String): MatchSource? {
            if (number.isBlank()) return null
            val candidates = buckets[key(PhoneNumbers.normalize(number))] ?: return null
            return candidates.firstOrNull { PhoneNumbers.match(number, it.first) != NumberMatch.NONE }?.second
        }

        private fun key(normalized: String): String {
            val digits = PhoneNumbers.digitsOnly(normalized).trimStart('0')
            return if (digits.length >= PhoneNumbers.MIN_SUFFIX_DIGITS) digits.takeLast(PhoneNumbers.MIN_SUFFIX_DIGITS) else digits
        }
    }
}
