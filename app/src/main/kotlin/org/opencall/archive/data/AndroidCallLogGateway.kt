package org.opencall.archive.data

import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.CallLog
import org.opencall.archive.core.CallRecord
import org.opencall.archive.core.Columns
import org.opencall.archive.core.FieldValue
import org.opencall.archive.core.restore.CallLogGateway

/**
 * Reads and writes Android's real call log through [CallLog.Calls.CONTENT_URI].
 * Requires READ_CALL_LOG for reads and WRITE_CALL_LOG for inserts; callers check first,
 * but a SecurityException is still possible (permission revoked mid-operation).
 */
class AndroidCallLogGateway(private val resolver: ContentResolver) : CallLogGateway {

    override fun providerColumns(): Set<String> {
        val uri = CallLog.Calls.CONTENT_URI.buildUpon()
            .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "1")
            .build()
        resolver.query(uri, null, null, null, null)?.use { return it.columnNames.toSet() }
        throw IllegalStateException("Call log provider returned no cursor")
    }

    override fun readAll(): List<CallRecord> {
        val cursor = resolver.query(CallLog.Calls.CONTENT_URI, null, null, null, "${CallLog.Calls.DATE} DESC")
            ?: throw IllegalStateException("Call log provider returned no cursor")
        return cursor.use { c ->
            val out = ArrayList<CallRecord>(c.count)
            while (c.moveToNext()) readRow(c)?.let { out += it }
            out
        }
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
            if (c.moveToFirst()) readRow(c) else null
        }
    }

    private fun readRow(c: Cursor): CallRecord? {
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
        return CallRecord.fromColumns(map)
    }

    private companion object {
        val NUMERIC_CORE = setOf(Columns.DATE, Columns.DURATION, Columns.TYPE, Columns.PRESENTATION)
    }
}
