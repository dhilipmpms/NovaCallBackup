package org.opencall.archive.data

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CallLog
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

/**
 * Describes what this device and the current permission state allow.
 *
 * Findings that drive the restore design (verified against AOSP ContactsProvider):
 *  - Inserting into CallLog.Calls is guarded by WRITE_CALL_LOG only. Holding the
 *    default dialer role is NOT required, so this app never asks for it (doing so would
 *    also require implementing a full in-call UI, which is out of scope).
 *  - READ_CALL_LOG / WRITE_CALL_LOG are "hard restricted" since Android 10: the installer
 *    must allow-list them. Sideloaded or adb installs normally are; if not, the request
 *    is refused without a dialog and the user must allow it from App info.
 */
data class CallLogCapabilities(
    val providerAvailable: Boolean,
    val canRead: Boolean,
    val canWrite: Boolean,
    val sdkInt: Int,
    val isDefaultDialer: Boolean,
    val defaultDialerPackage: String?,
) {
    val canRestore: Boolean get() = providerAvailable && canWrite && canRead
}

object CallLogAccess {
    const val READ = Manifest.permission.READ_CALL_LOG
    const val WRITE = Manifest.permission.WRITE_CALL_LOG

    fun hasRead(context: Context) = granted(context, READ)
    fun hasWrite(context: Context) = granted(context, WRITE)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun providerAvailable(context: Context): Boolean =
        context.packageManager.resolveContentProvider(CallLog.AUTHORITY, 0) != null

    fun capabilities(context: Context): CallLogCapabilities {
        val telecom = context.getSystemService(TelecomManager::class.java)
        val defaultDialer = try { telecom?.defaultDialerPackage } catch (e: SecurityException) { null }
        val isDefault = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        } else defaultDialer == context.packageName
        return CallLogCapabilities(
            providerAvailable = providerAvailable(context),
            canRead = hasRead(context),
            canWrite = hasWrite(context),
            sdkInt = Build.VERSION.SDK_INT,
            isDefaultDialer = isDefault,
            defaultDialerPackage = defaultDialer,
        )
    }
}
