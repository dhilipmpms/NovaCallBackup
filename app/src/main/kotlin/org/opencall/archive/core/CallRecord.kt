package org.opencall.archive.core

/**
 * Column names used by Android's CallLog provider (android.provider.CallLog.Calls).
 * They are duplicated here as plain strings so the core logic stays free of
 * Android dependencies and can be unit tested on the JVM.
 */
object Columns {
    const val ID = "_id"
    const val NUMBER = "number"
    const val DATE = "date"
    const val DURATION = "duration"
    const val TYPE = "type"
    const val PRESENTATION = "presentation"
    const val CACHED_NAME = "name"
    const val CACHED_NUMBER_TYPE = "numbertype"
    const val CACHED_NUMBER_LABEL = "numberlabel"
    const val PHONE_ACCOUNT_COMPONENT = "subscription_component_name"
    const val PHONE_ACCOUNT_ID = "subscription_id"
    const val NEW = "new"
    const val IS_READ = "is_read"

    /** Columns modelled as typed properties of [CallRecord]; everything else goes to extras. */
    val CORE = setOf(
        NUMBER, DATE, DURATION, TYPE, PRESENTATION, CACHED_NAME,
        PHONE_ACCOUNT_COMPONENT, PHONE_ACCOUNT_ID,
    )
}

/** Values of CallLog.Calls.TYPE. */
object CallTypes {
    const val INCOMING = 1
    const val OUTGOING = 2
    const val MISSED = 3
    const val VOICEMAIL = 4
    const val REJECTED = 5
    const val BLOCKED = 6
    const val ANSWERED_EXTERNALLY = 7

    val KNOWN = setOf(INCOMING, OUTGOING, MISSED, VOICEMAIL, REJECTED, BLOCKED, ANSWERED_EXTERNALLY)
}

/** Values of CallLog.Calls.NUMBER_PRESENTATION. */
object Presentation {
    const val ALLOWED = 1
    const val RESTRICTED = 2
    const val UNKNOWN = 3
    const val PAYPHONE = 4
    const val UNAVAILABLE = 5
}

/** A typed, JSON-friendly representation of a single provider column value. */
sealed class FieldValue {
    data class Text(val value: String) : FieldValue()
    data class Integer(val value: Long) : FieldValue()
    data class Real(val value: Double) : FieldValue()

    fun asText(): String = when (this) {
        is Text -> value
        is Integer -> value.toString()
        is Real -> value.toString()
    }
}

/**
 * One call-history entry as archived by the app.
 *
 * The original phone number is kept verbatim; matching uses [normalizedNumber]
 * instead. The provider row ID is deliberately not part of the record because it
 * is meaningless on another device or after the call log is rebuilt.
 */
data class CallRecord(
    val number: String,
    val date: Long,
    val duration: Long,
    val type: Int,
    val presentation: Int? = null,
    val cachedName: String? = null,
    val phoneAccountComponent: String? = null,
    val phoneAccountId: String? = null,
    /** Every other column the provider returned, keyed by its provider column name. */
    val extras: Map<String, FieldValue> = emptyMap(),
) {
    val normalizedNumber: String get() = PhoneNumbers.normalize(number)

    /**
     * Stable content-based identity used for journals and de-duplication within the
     * archive. It is NOT a guarantee of uniqueness on its own; see [DuplicateMatcher].
     */
    val fingerprint: String
        get() = "$normalizedNumber|$date|$duration|$type|${phoneAccountId.orEmpty()}"

    /** All columns of this record as a provider-column map, core columns included. */
    fun allColumns(): Map<String, FieldValue> {
        val map = LinkedHashMap<String, FieldValue>()
        map[Columns.NUMBER] = FieldValue.Text(number)
        map[Columns.DATE] = FieldValue.Integer(date)
        map[Columns.DURATION] = FieldValue.Integer(duration)
        map[Columns.TYPE] = FieldValue.Integer(type.toLong())
        presentation?.let { map[Columns.PRESENTATION] = FieldValue.Integer(it.toLong()) }
        cachedName?.let { map[Columns.CACHED_NAME] = FieldValue.Text(it) }
        phoneAccountComponent?.let { map[Columns.PHONE_ACCOUNT_COMPONENT] = FieldValue.Text(it) }
        phoneAccountId?.let { map[Columns.PHONE_ACCOUNT_ID] = FieldValue.Text(it) }
        map.putAll(extras)
        return map
    }

    companion object {
        /** Builds a record from a raw column map (as read from a cursor). */
        fun fromColumns(columns: Map<String, FieldValue>): CallRecord? {
            val date = (columns[Columns.DATE] as? FieldValue.Integer)?.value ?: return null
            val type = (columns[Columns.TYPE] as? FieldValue.Integer)?.value?.toInt() ?: return null
            val duration = (columns[Columns.DURATION] as? FieldValue.Integer)?.value ?: 0L
            return CallRecord(
                number = columns[Columns.NUMBER]?.asText().orEmpty(),
                date = date,
                duration = duration,
                type = type,
                presentation = (columns[Columns.PRESENTATION] as? FieldValue.Integer)?.value?.toInt(),
                cachedName = columns[Columns.CACHED_NAME]?.asText()?.takeIf { it.isNotEmpty() },
                phoneAccountComponent = columns[Columns.PHONE_ACCOUNT_COMPONENT]?.asText()?.takeIf { it.isNotEmpty() },
                phoneAccountId = columns[Columns.PHONE_ACCOUNT_ID]?.asText()?.takeIf { it.isNotEmpty() },
                extras = columns.filterKeys { it !in Columns.CORE && it != Columns.ID },
            )
        }
    }
}

/** Sorts records into the canonical order used for storage and checksums. */
fun List<CallRecord>.canonicalOrder(): List<CallRecord> =
    sortedWith(compareByDescending<CallRecord> { it.date }.thenBy { it.fingerprint })
