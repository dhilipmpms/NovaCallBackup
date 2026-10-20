package org.opencall.archive

import org.opencall.archive.core.BackupOrigin
import org.opencall.archive.core.BackupSnapshot
import org.opencall.archive.core.CallRecord
import org.opencall.archive.core.CallTypes
import org.opencall.archive.core.FieldValue

object TestData {
    /** 2024-01-15T10:00:00Z */
    const val BASE = 1_705_312_800_000L

    fun call(
        number: String = "+15550100",
        date: Long = BASE,
        duration: Long = 60,
        type: Int = CallTypes.INCOMING,
        name: String? = null,
        account: String? = null,
        extras: Map<String, FieldValue> = emptyMap(),
    ) = CallRecord(
        number = number, date = date, duration = duration, type = type, presentation = 1,
        cachedName = name, phoneAccountId = account, extras = extras,
    )

    fun snapshot(records: List<CallRecord>, id: String = "backup-1", label: String = "Test backup") =
        BackupSnapshot(id, label, BASE + 10_000_000, BackupOrigin.MANUAL, 34, "1.0.0", records)

    /** n distinct calls, one per minute. */
    fun calls(n: Int, start: Long = BASE) = (0 until n).map { i ->
        call(number = "+1555010%04d".format(i), date = start + i * 60_000L, duration = (i % 300).toLong(), type = CallTypes.KNOWN.filter { it != CallTypes.VOICEMAIL }[i % 6])
    }
}
