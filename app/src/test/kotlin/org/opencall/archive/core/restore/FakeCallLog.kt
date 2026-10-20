package org.opencall.archive.core.restore

import org.opencall.archive.core.CallRecord
import org.opencall.archive.core.FieldValue

/** Simulates process death: an Error escapes every `catch (Exception)`. */
class SimulatedProcessDeath : Error("killed")

/** In-memory stand-in for Android's CallLog provider with failure injection. */
class FakeCallLog(initial: List<CallRecord> = emptyList()) : CallLogGateway {
    private val rows = LinkedHashMap<Long, CallRecord>()
    private var nextId = 1L
    val insertedValues = mutableListOf<Map<String, FieldValue>>()

    var columns: Set<String> = RestoreColumns.WRITABLE.keys + setOf("_id", "countryiso", "geocoded_location", "last_modified")

    /** Returns true to make the provider return null (rejected insert). */
    var rejectIf: (Map<String, FieldValue>) -> Boolean = { false }

    /** Returns true to throw IllegalArgumentException (e.g. OEM rejects a column). */
    var illegalArgumentIf: (Map<String, FieldValue>) -> Boolean = { false }

    /** Throw SecurityException once this many inserts have succeeded. */
    var revokePermissionAfter: Int? = null

    /** Insert the row, then die before the caller can record the outcome. */
    var dieAfterInsertNumber: Int? = null

    /** Store a different duration than requested (verification must catch it). */
    var corruptDurationIf: (Map<String, FieldValue>) -> Boolean = { false }

    /** Rows that vanish right after insertion succeeds and is read back. */
    var trimAfterReadBack = false
    private val toTrim = mutableListOf<Long>()

    init {
        initial.forEach { rows[nextId++] = it }
    }

    val records: List<CallRecord> get() = rows.values.toList()

    override fun providerColumns(): Set<String> = columns

    override fun readAll(): List<CallRecord> {
        toTrim.forEach { rows.remove(it) }
        toTrim.clear()
        return rows.values.toList()
    }

    override fun insert(values: Map<String, FieldValue>): String? {
        revokePermissionAfter?.let { if (insertedValues.size >= it) throw SecurityException("Permission Denial") }
        require(values.keys.all { it in columns }) { "Unknown column" }
        if (illegalArgumentIf(values)) throw IllegalArgumentException("column rejected")
        if (rejectIf(values)) return null
        insertedValues += values
        var stored = CallRecord.fromColumns(values)!!
        if (corruptDurationIf(values)) stored = stored.copy(duration = stored.duration + 7)
        val id = nextId++
        rows[id] = stored
        if (dieAfterInsertNumber == insertedValues.size) throw SimulatedProcessDeath()
        return "content://call_log/calls/$id"
    }

    override fun readBack(handle: String): CallRecord? {
        val id = handle.substringAfterLast('/').toLong()
        if (trimAfterReadBack) toTrim += id
        return rows[id]
    }
}
