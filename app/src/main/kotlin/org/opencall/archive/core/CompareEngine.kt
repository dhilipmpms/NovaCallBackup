package org.opencall.archive.core

data class FieldDifference(val field: String, val left: String?, val right: String?)

data class MatchedPair(
    val left: CallRecord,
    val right: CallRecord,
    val level: MatchLevel,
    val reasons: List<String>,
    /** Differences in metadata that are not part of duplicate matching. */
    val differences: List<FieldDifference>,
)

data class CompareResult(
    val leftTotal: Int,
    val rightTotal: Int,
    val confirmed: List<MatchedPair>,
    val probable: List<MatchedPair>,
    val onlyLeft: List<CallRecord>,
    val onlyRight: List<CallRecord>,
) {
    val confirmedWithDifferences: Int get() = confirmed.count { it.differences.isNotEmpty() }
}

/**
 * Read-only comparison of two datasets (backup vs. backup or backup vs. live call log).
 * Matching is one-to-one: each record on the right is paired with at most one on the left,
 * so repeated identical calls are counted correctly.
 */
object CompareEngine {

    fun compare(left: List<CallRecord>, right: List<CallRecord>): CompareResult {
        val index = RecordIndex(right)
        val used = HashSet<Int>()
        val confirmed = mutableListOf<MatchedPair>()
        val probableCandidates = mutableListOf<CallRecord>()
        val onlyLeft = mutableListOf<CallRecord>()

        // First pass: confirmed matches only, so they are not stolen by probable ones.
        for (l in left) {
            val m = index.bestMatch(l, used)
            if (m != null && m.second.level == MatchLevel.CONFIRMED) {
                used += m.first.index
                confirmed += MatchedPair(l, m.first.value, MatchLevel.CONFIRMED, emptyList(), differences(l, m.first.value))
            } else {
                probableCandidates += l
            }
        }
        val probable = mutableListOf<MatchedPair>()
        for (l in probableCandidates) {
            val m = index.bestMatch(l, used)
            if (m != null && m.second.level != MatchLevel.NONE) {
                used += m.first.index
                probable += MatchedPair(l, m.first.value, m.second.level, m.second.reasons, differences(l, m.first.value))
            } else {
                onlyLeft += l
            }
        }
        val onlyRight = right.filterIndexed { i, _ -> i !in used }
        return CompareResult(left.size, right.size, confirmed, probable, onlyLeft, onlyRight)
    }

    /** Compares metadata of two records; core matching fields are covered by match reasons. */
    fun differences(a: CallRecord, b: CallRecord): List<FieldDifference> {
        val diffs = mutableListOf<FieldDifference>()
        fun check(field: String, x: String?, y: String?) {
            if ((x ?: "") != (y ?: "")) diffs += FieldDifference(field, x, y)
        }
        if (a.number != b.number) check("number (as written)", a.number, b.number)
        check("cached name", a.cachedName, b.cachedName)
        check("presentation", a.presentation?.toString(), b.presentation?.toString())
        check("phone account id", a.phoneAccountId, b.phoneAccountId)
        check("phone account component", a.phoneAccountComponent, b.phoneAccountComponent)
        for (key in COMPARED_EXTRAS) check(key, a.extras[key]?.asText(), b.extras[key]?.asText())
        return diffs
    }

    /** Extra columns worth surfacing; caches such as photo URIs are device-specific noise. */
    private val COMPARED_EXTRAS = listOf(
        "numbertype", "numberlabel", "features", "post_dial_digits", "via_number",
        "block_reason", "call_screening_app_name", "missed_reason", "priority", "subject",
        "is_business_call", "asserted_display_name",
    )
}
