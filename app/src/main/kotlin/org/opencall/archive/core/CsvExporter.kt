package org.opencall.archive.core

import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * CSV export for spreadsheets and reporting. CSV is lossy (extra provider metadata is
 * not exported) and the app does not offer restoring from CSV.
 */
object CsvExporter {
    val HEADER = listOf(
        "date_local", "date_epoch_ms", "number", "cached_name", "type", "duration_seconds",
        "presentation", "phone_account_id", "phone_account_component",
    )

    fun write(records: List<CallRecord>, writer: Writer, zone: ZoneId = ZoneId.systemDefault()) {
        val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone)
        writer.write(HEADER.joinToString(",") { escape(it) })
        writer.write("\r\n")
        for (r in records) {
            val row = listOf(
                formatter.format(Instant.ofEpochMilli(r.date)),
                r.date.toString(),
                r.number,
                r.cachedName.orEmpty(),
                CallTypeNames.wireName(r.type),
                r.duration.toString(),
                r.presentation?.let { PresentationNames.wireName(it) }.orEmpty(),
                r.phoneAccountId.orEmpty(),
                r.phoneAccountComponent.orEmpty(),
            )
            writer.write(row.joinToString(",") { escape(it) })
            writer.write("\r\n")
        }
        writer.flush()
    }

    private val PHONE_LIKE = Regex("^\\+?[0-9 ()\\-.*#]+$")

    /**
     * RFC 4180 escaping plus spreadsheet formula-injection protection: cells starting with
     * = + - @ (or a tab/CR) are prefixed with an apostrophe, except values that are plainly
     * phone numbers such as "+15550100" so numbers are not altered.
     */
    fun escape(value: String): String {
        var v = value
        if (v.isNotEmpty() && v[0] in "=+-@\t\r" && !PHONE_LIKE.matches(v)) v = "'$v"
        val needsQuotes = v.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
            v.startsWith(' ') || v.endsWith(' ')
        return if (needsQuotes) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }
}

object CallTypeNames {
    fun wireName(type: Int): String = when (type) {
        CallTypes.INCOMING -> "incoming"
        CallTypes.OUTGOING -> "outgoing"
        CallTypes.MISSED -> "missed"
        CallTypes.VOICEMAIL -> "voicemail"
        CallTypes.REJECTED -> "rejected"
        CallTypes.BLOCKED -> "blocked"
        CallTypes.ANSWERED_EXTERNALLY -> "answered_externally"
        else -> "unknown($type)"
    }
}

object PresentationNames {
    fun wireName(p: Int): String = when (p) {
        Presentation.ALLOWED -> "allowed"
        Presentation.RESTRICTED -> "restricted"
        Presentation.UNKNOWN -> "unknown"
        Presentation.PAYPHONE -> "payphone"
        Presentation.UNAVAILABLE -> "unavailable"
        else -> "other($p)"
    }
}
