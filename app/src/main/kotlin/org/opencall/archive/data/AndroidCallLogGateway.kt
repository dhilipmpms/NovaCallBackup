package org.opencall.archive.data

import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import android.provider.CallLog
import org.opencall.archive.core.CallRecord
import org.opencall.archive.core.Columns
import org.opencall.archive.core.FieldValue
import org.opencall.archive.core.ReadResult
import org.opencall.archive.core.RecordReader
import org.opencall.archive.core.restore.CallLogGateway

/**
 * Reads and writes Android's real call log through [CallLog.Calls.CONTENT_URI].
 * Requires READ_CALL_LOG for reads and WRITE_CALL_LOG for inserts; callers check first,
 * but a SecurityException is still possible (permission revoked mid-operation).
 */
data class AccountRowCount(val hasAccount: Boolean, val rows: Int)

class AndroidCallLogGateway(private val resolver: ContentResolver) : CallLogGateway {

    override fun providerColumns(): Set<String> {
        val uri = CallLog.Calls.CONTENT_URI.buildUpon()
            .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "1")
            .build()
        resolver.query(uri, null, null, null, null)?.use { return it.columnNames.toSet() }
        throw IllegalStateException("Call log provider returned no cursor")
    }

    override fun readAll(): List<CallRecord> = readAllWithStats().records

    /** Reads every row (no selection, limit or offset) and counts each stage. */
    fun readAllWithStats(): ReadResult {
        val cursor = resolver.query(CallLog.Calls.CONTENT_URI, null, null, null, "${CallLog.Calls.DATE} DESC")
            ?: throw IllegalStateException("Call log provider returned no cursor")
        return cursor.use { c ->
            val result = RecordReader.collect(c.count, generateSequence { if (c.moveToNext()) readColumns(c) else null })
            Log.i(TAG, "call log read: cursor=${result.stats.cursorCount} iterated=${result.stats.iterated} " +
                "converted=${result.stats.converted} skipped=${result.stats.skippedTotal}")
            result
        }
    }

    /**
     * Rows per phone account, largest first. Accounts are identified only by whether one is set,
     * never by their IDs, so the result is safe to display or log.
     */
    fun countByPhoneAccount(): List<AccountRowCount> {
        val cursor = resolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME, CallLog.Calls.PHONE_ACCOUNT_ID), null, null, null,
        ) ?: throw IllegalStateException("Call log provider returned no cursor")
        val counts = LinkedHashMap<String?, Int>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val component = c.getString(0)
                val id = c.getString(1)
                val key = if (component.isNullOrEmpty() && id.isNullOrEmpty()) null else "$component|$id"
                counts.merge(key, 1, Int::plus)
            }
        }
        return counts.map { (key, n) -> AccountRowCount(hasAccount = key != null, rows = n) }.sortedByDescending { it.rows }
    }

    fun count(): Int {
        val cursor = resolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID), null, null, null)
            ?: throw IllegalStateException("Call log provider returned no cursor")
        return cursor.use { it.count }
    }

    override fun insert(values: Map<String, FieldValue>): String? {
        val cv = ContentValues(values.size)
        for ((k, v) in values) {
            when (v) {
                is FieldValue.Text -> cv.put(k, v.value)
                is FieldValue.Integer -> cv.put(k, v.value)
                is FieldValue.Real -> cv.put(k, v.value)
            }
        }
        return resolver.insert(CallLog.Calls.CONTENT_URI, cv)?.toString()
    }

    override fun readBack(handle: String): CallRecord? {
        val uri = Uri.parse(handle)
        return resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) CallRecord.fromColumns(readColumns(c)) else null
        }
    }

    private fun readColumns(c: Cursor): Map<String, FieldValue> {
        val map = HashMap<String, FieldValue>(c.columnCount)
        for (i in 0 until c.columnCount) {
            val name = c.getColumnName(i)
            if (name == Columns.ID) continue
            when (c.getType(i)) {
                Cursor.FIELD_TYPE_INTEGER -> map[name] = FieldValue.Integer(c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> map[name] = FieldValue.Real(c.getDouble(i))
                Cursor.FIELD_TYPE_STRING -> {
                    val s = c.getString(i)
                    // Some providers store numeric columns as text; keep core ones typed.
                    map[name] = if (name in NUMERIC_CORE) s.toLongOrNull()?.let { FieldValue.Integer(it) } ?: FieldValue.Text(s)
                    else FieldValue.Text(s)
                }
                // FIELD_TYPE_NULL is skipped; FIELD_TYPE_BLOB is not used by public call-log columns.
            }
        }
        return map
    }

    private companion object {
        const val TAG = "OpenCallArchive"
        val NUMERIC_CORE = setOf(Columns.DATE, Columns.DURATION, Columns.TYPE, Columns.PRESENTATION)
    }
}
