package org.opencall.archive.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat

/**
 * Looks up the phone numbers of current contacts by name, so archived calls whose cached name
 * is missing or outdated (e.g. migrated from another phone) can still be found. Contact IDs are
 * never stored or compared; only the numbers are used, and only in memory.
 */
object ContactNumbers {
    const val PERMISSION = Manifest.permission.READ_CONTACTS

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** All numbers of contacts whose display name contains [name] (case-insensitive). */
    fun numbersForName(context: Context, name: String): Set<String> {
        val q = name.trim()
        if (q.isEmpty() || !hasPermission(context)) return emptySet()
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val cursor = context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.NUMBER),
            "${Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
            arrayOf("%$escaped%"),
            null,
        ) ?: return emptySet()
        return cursor.use { c ->
            buildSet { while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { add(it) } }
        }
    }
}
