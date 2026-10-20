package org.opencall.archive.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.opencall.archive.OpenCallApp
import org.opencall.archive.R
import org.opencall.archive.core.CallRecord
import org.opencall.archive.core.CallTypes
import org.opencall.archive.core.PresentationNames
import org.opencall.archive.data.CallLogAccess
import org.opencall.archive.databinding.ItemRecordBinding
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

val Context.app: OpenCallApp get() = applicationContext as OpenCallApp

/** Pads [this] by the system bar / cutout insets (the app targets SDK 36, so edge-to-edge is enforced). */
fun View.applySystemBarPadding() {
    val start = paddingStart
    val top = paddingTop
    val end = paddingEnd
    val bottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
        v.updatePadding(left = start + bars.left, top = top + bars.top, right = end + bars.right, bottom = bottom + bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
}

object Fmt {
    private val number = NumberFormat.getIntegerInstance()

    fun count(n: Int): String = number.format(n)
    fun date(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
    fun day(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

    fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "%dh %02dm %02ds".format(h, m, s)
            m > 0 -> "%dm %02ds".format(m, s)
            else -> "${s}s"
        }
    }

    fun bytes(b: Long): String = when {
        b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
        b >= 1024 -> "%.1f KB".format(b / 1024.0)
        else -> "$b B"
    }

    fun typeLabel(ctx: Context, type: Int): String = ctx.getString(
        when (type) {
            CallTypes.INCOMING -> R.string.type_incoming
            CallTypes.OUTGOING -> R.string.type_outgoing
            CallTypes.MISSED -> R.string.type_missed
            CallTypes.REJECTED -> R.string.type_rejected
            CallTypes.BLOCKED -> R.string.type_blocked
            CallTypes.VOICEMAIL -> R.string.type_voicemail
            CallTypes.ANSWERED_EXTERNALLY -> R.string.type_answered_externally
            else -> R.string.type_unknown
        },
    )

    fun typeIcon(type: Int): Int = when (type) {
        CallTypes.INCOMING, CallTypes.ANSWERED_EXTERNALLY -> R.drawable.ic_call_received
        CallTypes.OUTGOING -> R.drawable.ic_call_made
        CallTypes.MISSED -> R.drawable.ic_call_missed
        CallTypes.REJECTED, CallTypes.BLOCKED -> R.drawable.ic_block
        else -> R.drawable.ic_phone
    }

    fun typeColor(type: Int): Int = when (type) {
        CallTypes.INCOMING, CallTypes.ANSWERED_EXTERNALLY -> R.color.call_incoming
        CallTypes.OUTGOING -> R.color.call_outgoing
        CallTypes.MISSED, CallTypes.REJECTED, CallTypes.BLOCKED -> R.color.call_missed
        else -> R.color.call_other
    }

    fun displayNumber(ctx: Context, r: CallRecord): String =
        r.number.ifBlank { ctx.getString(R.string.private_number) }

    fun title(ctx: Context, r: CallRecord): String = r.cachedName ?: displayNumber(ctx, r)
}

/** Adds a "label ........ value" row to a vertical LinearLayout. */
fun LinearLayout.addRow(label: String, value: String, emphasize: Boolean = false) {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 6, 0, 6)
    }
    val appearance = if (emphasize) com.google.android.material.R.attr.textAppearanceTitleSmall
    else com.google.android.material.R.attr.textAppearanceBodyMedium
    fun tv(text: String, weight: Float) = TextView(context).apply {
        this.text = text
        val ta = android.util.TypedValue()
        context.theme.resolveAttribute(appearance, ta, true)
        setTextAppearance(ta.resourceId)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
    }
    row.addView(tv(label, 1f))
    row.addView(tv(value, 0f).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_END
    })
    addView(row)
}

/** Shows every archived field of a record. */
fun showRecordDetails(ctx: Context, record: CallRecord, sourceLabel: String) {
    val sb = StringBuilder()
    sb.append(sourceLabel).append("\n\n")
    sb.append("Number: ").append(Fmt.displayNumber(ctx, record)).append('\n')
    record.cachedName?.let { sb.append("Name (cached): ").append(it).append('\n') }
    sb.append("Date: ").append(Fmt.date(record.date)).append('\n')
    sb.append("Type: ").append(Fmt.typeLabel(ctx, record.type)).append('\n')
    sb.append("Duration: ").append(Fmt.duration(record.duration)).append('\n')
    record.presentation?.let { sb.append("Presentation: ").append(PresentationNames.wireName(it)).append('\n') }
    record.phoneAccountId?.let { sb.append("Phone account: ").append(it).append('\n') }
    if (record.extras.isNotEmpty()) {
        sb.append("\nOther archived fields:\n")
        record.extras.toSortedMap().forEach { (k, v) -> sb.append("  ").append(k).append(" = ").append(v.asText()).append('\n') }
    }
    MaterialAlertDialogBuilder(ctx)
        .setTitle(Fmt.title(ctx, record))
        .setMessage(sb.toString().trimEnd())
        .setPositiveButton(R.string.close, null)
        .show()
}

data class RecordRow(val record: CallRecord, val badge: String, val note: String? = null) {
    val key: String get() = record.fingerprint + "|" + badge
}

class RecordAdapter(private val onClick: (RecordRow) -> Unit) :
    ListAdapter<RecordRow, RecordAdapter.VH>(object : DiffUtil.ItemCallback<RecordRow>() {
        override fun areItemsTheSame(a: RecordRow, b: RecordRow) = a.key == b.key
        override fun areContentsTheSame(a: RecordRow, b: RecordRow) = a == b
    }) {

    class VH(val b: ItemRecordBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        val r = row.record
        val ctx = holder.itemView.context
        with(holder.b) {
            typeIcon.setImageResource(Fmt.typeIcon(r.type))
            typeIcon.setColorFilter(ContextCompat.getColor(ctx, Fmt.typeColor(r.type)))
            typeIcon.contentDescription = Fmt.typeLabel(ctx, r.type)
            title.text = Fmt.title(ctx, r)
            val parts = mutableListOf<String>()
            if (r.cachedName != null) parts += Fmt.displayNumber(ctx, r)
            parts += Fmt.typeLabel(ctx, r.type)
            parts += Fmt.date(r.date)
            subtitle.text = parts.joinToString(" · ") + (row.note?.let { "\n$it" } ?: "")
            duration.text = Fmt.duration(r.duration)
            badge.text = row.badge
            root.setOnClickListener { onClick(row) }
        }
    }
}

/**
 * Explains, requests and diagnoses the call-log permissions. Must be constructed during
 * onCreate of the owning Activity/Fragment (ActivityResult registration rule).
 */
class CallLogPermissionRequester(
    caller: ActivityResultCaller,
    private val activity: () -> Activity,
    private val onResult: (Boolean) -> Unit,
) {
    private val launcher = caller.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.values.isNotEmpty() && result.values.all { it }
        if (!granted) {
            val act = activity()
            val canAskAgain = listOf(CallLogAccess.READ, CallLogAccess.WRITE).any { act.shouldShowRequestPermissionRationale(it) }
            if (!canAskAgain) showBlockedDialog(act)
        }
        onResult(granted)
    }

    fun request() {
        val act = activity()
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.perm_rationale_title)
            .setMessage(R.string.perm_rationale_text)
            .setPositiveButton(R.string.perm_continue) { _, _ -> launcher.launch(arrayOf(CallLogAccess.READ, CallLogAccess.WRITE)) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        fun showBlockedDialog(act: Activity) {
            MaterialAlertDialogBuilder(act)
                .setTitle(R.string.perm_blocked_title)
                .setMessage(R.string.perm_blocked_text)
                .setPositiveButton(R.string.open_app_info) { _, _ -> openAppInfo(act) }
                .setNegativeButton(R.string.close, null)
                .show()
        }

        fun openAppInfo(ctx: Context) {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)),
            )
        }
    }
}

fun Context.isPermissionHeld(): Boolean = CallLogAccess.hasRead(this) && CallLogAccess.hasWrite(this)

/** Intent extra keys shared by the activities. */
object Extras {
    const val BACKUP_ID = "backup_id"
    const val DOCUMENT_URI = "document_uri"
    const val LEFT_ID = "left_id"
    const val RIGHT_ID = "right_id"
    const val RETRY = "retry"
    const val LIVE = "__live__"
}
