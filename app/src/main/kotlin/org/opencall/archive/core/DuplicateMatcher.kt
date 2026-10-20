package org.opencall.archive.core

import kotlin.math.abs

object PhoneNumbers {
    /**
     * Matching representation of a phone number: digits only, keeping a leading '+'.
     * Separators such as spaces, dashes, dots and brackets are dropped. Characters
     * like '*' and '#' are kept because they make service codes distinct.
     */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        val sb = StringBuilder(trimmed.length)
        trimmed.forEachIndexed { i, c ->
            when {
                c.isDigit() -> sb.append(c)
                c == '+' && i == 0 -> sb.append(c)
                c == '*' || c == '#' -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun digitsOnly(raw: String): String = raw.filter { it.isDigit() }

    const val MIN_SUFFIX_DIGITS = 7
    private const val MAX_PREFIX_DIFFERENCE = 4

    /**
     * Number equivalence used for duplicate detection.
     *  - [NumberMatch.EXACT]: normalized forms are identical.
     *  - [NumberMatch.LOOSE]: same digits, or one is a suffix of the other with at most a
     *    short prefix difference (e.g. "+91 98765 43210" vs "098765 43210"). Loose matches
     *    can only ever produce a *probable* duplicate.
     */
    fun match(a: String, b: String): NumberMatch {
        val na = normalize(a)
        val nb = normalize(b)
        if (na == nb) return NumberMatch.EXACT
        val da = digitsOnly(na)
        val db = digitsOnly(nb)
        if (da.isEmpty() || db.isEmpty()) return NumberMatch.NONE
        if (da == db) return NumberMatch.LOOSE
        // National trunk prefixes ("0" in "098765 43210") are not part of the subscriber number.
        val ta = da.trimStart('0')
        val tb = db.trimStart('0')
        val (shorter, longer) = if (ta.length <= tb.length) ta to tb else tb to ta
        if (shorter.length >= MIN_SUFFIX_DIGITS &&
            longer.length - shorter.length <= MAX_PREFIX_DIFFERENCE &&
            longer.endsWith(shorter)
        ) return NumberMatch.LOOSE
        return NumberMatch.NONE
    }
}

enum class NumberMatch { EXACT, LOOSE, NONE }

enum class MatchLevel {
    /** Same normalized number, timestamp, duration, type and compatible phone account. */
    CONFIRMED,

    /** Very likely the same call, but at least one field differs or matching was loose. */
    PROBABLE,
    NONE,
}

data class MatchResult(val level: MatchLevel, val reasons: List<String>)

/**
 * Conservative duplicate detection between two call records.
 *
 * A call has no globally unique identifier, so "duplicate" is always an inference.
 * Only an exact match on number, millisecond timestamp, duration, type and phone
 * account is treated as confirmed. Near-matches (timestamp truncated to seconds,
 * duration off by one second, differently formatted number, differing SIM info)
 * are reported as probable so the user decides what to do with them.
 */
object DuplicateMatcher {
    const val PROBABLE_DATE_TOLERANCE_MS = 1_000L
    const val PROBABLE_DURATION_TOLERANCE_S = 1L

    fun compare(a: CallRecord, b: CallRecord): MatchResult {
        if (a.type != b.type) return MatchResult(MatchLevel.NONE, emptyList())
        val dateDelta = abs(a.date - b.date)
        if (dateDelta > PROBABLE_DATE_TOLERANCE_MS) return MatchResult(MatchLevel.NONE, emptyList())
        val durationDelta = abs(a.duration - b.duration)
        if (durationDelta > PROBABLE_DURATION_TOLERANCE_S) return MatchResult(MatchLevel.NONE, emptyList())

        val numberMatch = numberMatch(a, b)
        if (numberMatch == NumberMatch.NONE) return MatchResult(MatchLevel.NONE, emptyList())

        val reasons = mutableListOf<String>()
        if (numberMatch == NumberMatch.LOOSE) reasons += "phone number formatted differently"
        if (dateDelta != 0L) reasons += "timestamps differ by ${dateDelta} ms"
        if (durationDelta != 0L) reasons += "durations differ by ${durationDelta} s"
        if (!accountsCompatible(a, b)) reasons += "different phone account / SIM"

        return if (reasons.isEmpty()) MatchResult(MatchLevel.CONFIRMED, emptyList())
        else MatchResult(MatchLevel.PROBABLE, reasons)
    }

    private fun numberMatch(a: CallRecord, b: CallRecord): NumberMatch {
        // Hidden/unknown callers usually have an empty number. Two such calls only
        // match when their presentation also agrees; the timestamp does the rest.
        if (a.number.isBlank() || b.number.isBlank()) {
            return if (a.number.isBlank() && b.number.isBlank() && a.presentation == b.presentation) {
                NumberMatch.EXACT
            } else NumberMatch.NONE
        }
        return PhoneNumbers.match(a.number, b.number)
    }

    private fun accountsCompatible(a: CallRecord, b: CallRecord): Boolean {
        val ia = a.phoneAccountId
        val ib = b.phoneAccountId
        return ia == null || ib == null || ia == ib
    }
}

/**
 * Index of existing records bucketed by second, for fast duplicate lookups.
 * Lookups only scan the neighbouring buckets inside the probable-match tolerance.
 */
class RecordIndex(records: Iterable<CallRecord>) {
    private val buckets = HashMap<Long, MutableList<IndexedValue<CallRecord>>>()

    init {
        records.forEachIndexed { i, r -> buckets.getOrPut(bucket(r.date)) { mutableListOf() } += IndexedValue(i, r) }
    }

    /** Returns the strongest match for [record], preferring confirmed over probable. */
    fun bestMatch(record: CallRecord, exclude: Set<Int> = emptySet()): Pair<IndexedValue<CallRecord>, MatchResult>? {
        var best: Pair<IndexedValue<CallRecord>, MatchResult>? = null
        val center = bucket(record.date)
        for (b in (center - 1)..(center + 1)) {
            val list = buckets[b] ?: continue
            for (candidate in list) {
                if (candidate.index in exclude) continue
                val result = DuplicateMatcher.compare(record, candidate.value)
                if (result.level == MatchLevel.CONFIRMED) return candidate to result
                if (result.level == MatchLevel.PROBABLE && best == null) best = candidate to result
            }
        }
        return best
    }

    private fun bucket(date: Long) = Math.floorDiv(date, 1_000L)
}
