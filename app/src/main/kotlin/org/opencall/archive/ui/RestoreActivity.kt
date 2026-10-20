package org.opencall.archive.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.opencall.archive.R
import org.opencall.archive.core.BackupSnapshot
import org.opencall.archive.core.DecodeResult
import org.opencall.archive.core.restore.ColumnDisposition
import org.opencall.archive.core.restore.DuplicatePolicy
import org.opencall.archive.core.restore.EntryOutcome
import org.opencall.archive.core.restore.PlanStatus
import org.opencall.archive.core.restore.RestoreEligibility
import org.opencall.archive.core.restore.RestoreOptions
import org.opencall.archive.core.restore.RestorePlan
import org.opencall.archive.core.restore.RestorePlanner
import org.opencall.archive.core.restore.RestoreReport
import org.opencall.archive.core.restore.SessionStatus
import org.opencall.archive.data.CallLogAccess
import org.opencall.archive.data.RestoreState
import org.opencall.archive.databinding.ActivityRestoreBinding

/**
 * Restore workflow: select → validate → preview → choose policy → confirm → restore →
 * verify → report. Only the confirm step writes to the call log.
 */
class RestoreActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRestoreBinding
    private lateinit var permissions: CallLogPermissionRequester

    private var snapshot: BackupSnapshot? = null
    private var fileValidation: DecodeResult? = null
    private var plan: RestorePlan? = null
    private var providerColumns: Set<String>? = null

    /** True once this screen has shown a preview or started a run, so results belong here. */
    private var ownsSession = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRestoreBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding()
        binding.toolbar.setNavigationOnClickListener { finish() }
        permissions = CallLogPermissionRequester(this, { this }) { buildPreview() }

        binding.errorClose.setOnClickListener { finish() }
        binding.cancelPreview.setOnClickListener { finish() }
        binding.grantButton.setOnClickListener { permissions.request() }
        binding.policyGroup.setOnCheckedChangeListener { _, _ -> updateWillInsert() }
        binding.keepAccount.setOnCheckedChangeListener { _, _ -> updateFields(); updateWillInsert() }
        binding.markRead.setOnCheckedChangeListener { _, _ -> updateFields() }
        binding.startRestore.setOnClickListener { confirmAndStart() }
        binding.cancelRestore.setOnClickListener { confirmCancel() }
        binding.doneButton.setOnClickListener { app.restoreController.acknowledge(); finish() }

        val controller = app.restoreController
        val hasSource = intent.hasExtra(Extras.BACKUP_ID) || intent.hasExtra(Extras.DOCUMENT_URI)
        when {
            controller.isRunning -> ownsSession = true
            controller.state.value is RestoreState.Finished && !hasSource -> ownsSession = true
            !hasSource -> return finish()
            else -> {
                controller.acknowledge()
                loadSource()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state.collect { render(it) }
            }
        }
    }

    // ---- Steps 1 & 2: select and validate ------------------------------------------

    private fun loadSource(backupId: String? = null) {
        showOnly(binding.loadingGroup)
        binding.loadingText.text = "Reading and validating the backup…"
        lifecycleScope.launch {
            val id = backupId ?: intent.getStringExtra(Extras.BACKUP_ID)
            val uri = if (backupId != null) null else intent.getStringExtra(Extras.DOCUMENT_URI)
            if (id == null && uri == null) {
                showError("No backup was selected. Your call history was not changed.")
                return@launch
            }
            try {
                if (id != null) {
                    snapshot = app.repository.loadSnapshot(id)
                } else if (uri != null) {
                    val doc = app.repository.openDocument(Uri.parse(uri))
                    fileValidation = doc.result
                    if (!doc.result.isValid) {
                        showError("This file cannot be restored: ${doc.result.fatalError}\n\nYour call history was not changed.")
                        return@launch
                    }
                    snapshot = doc.result.snapshot
                }
            } catch (e: Exception) {
                showError(
                    "The backup could not be opened: ${e.message ?: e.javaClass.simpleName}\n\n" +
                        "If it was restored directly from a file, open that file again. Your call history was not changed.",
                )
                return@launch
            }
            if (snapshot != null) buildPreview()
        }
    }

    // ---- Step 3: preview -------------------------------------------------------------

    private fun buildPreview() {
        val s = snapshot ?: return loadSource()
        val caps = CallLogAccess.capabilities(this)
        if (!caps.providerAvailable) {
            showError(
                "This device does not have an Android call log, so call history cannot be restored here. " +
                    "Your backups remain available for searching, comparing and exporting.",
            )
            return
        }
        ownsSession = true
        showOnly(binding.loadingGroup)
        binding.loadingText.text = "Checking your current call history for duplicates…"
        lifecycleScope.launch {
            if (caps.canRead && caps.canWrite) {
                try {
                    plan = app.restoreController.plan(s)
                    providerColumns = app.restoreController.providerColumns()
                } catch (e: SecurityException) {
                    plan = null
                } catch (e: Exception) {
                    showError("Could not read the current call history: ${e.javaClass.simpleName}. Nothing was changed.")
                    return@launch
                }
            } else {
                plan = null
            }
            showPreview(s, caps.canRead && caps.canWrite, caps.sdkInt, caps.isDefaultDialer)
        }
    }

    private fun showPreview(s: BackupSnapshot, permitted: Boolean, sdk: Int, isDefaultDialer: Boolean) {
        showOnly(binding.previewGroup)
        binding.backupDetails.text = buildString {
            append("\"").append(s.label).append("\"")
            if (s.createdAt > 0) append(" · created ").append(Fmt.date(s.createdAt))
            s.sourceSdk?.let { append(" · from Android API ").append(it) }
            fileValidation?.let { v ->
                append("\nRestoring directly from a file (it is not saved in the app).")
                v.warnings.forEach { append("\n⚠ ").append(it) }
            }
        }

        val counts = binding.previewCounts
        counts.removeAllViews()
        val malformed = fileValidation?.invalidEntries?.size ?: 0
        counts.addRow("Records in backup", Fmt.count(s.records.size + malformed), emphasize = true)
        val p = plan
        if (p != null) {
            counts.addRow("Eligible, not in call history", Fmt.count(p.count(PlanStatus.ELIGIBLE)))
            counts.addRow("Already in call history (exact match)", Fmt.count(p.count(PlanStatus.CONFIRMED_DUPLICATE)))
            counts.addRow("Possible duplicates (similar entry exists)", Fmt.count(p.count(PlanStatus.PROBABLE_DUPLICATE)))
            if (p.count(PlanStatus.DUPLICATE_IN_BACKUP) > 0) counts.addRow("Repeated within this backup", Fmt.count(p.count(PlanStatus.DUPLICATE_IN_BACKUP)))
            if (p.count(PlanStatus.ALREADY_RESTORED) > 0) counts.addRow("Restored earlier by this app", Fmt.count(p.count(PlanStatus.ALREADY_RESTORED)))
            counts.addRow("Invalid records", Fmt.count(p.count(PlanStatus.INVALID) + malformed))
            counts.addRow("Cannot be restored on Android", Fmt.count(p.count(PlanStatus.NOT_ELIGIBLE)))
            val reasons = p.items.filter { it.status == PlanStatus.INVALID || it.status == PlanStatus.NOT_ELIGIBLE }
                .groupingBy { it.detail ?: "" }.eachCount()
            reasons.forEach { (reason, n) -> counts.addRow("   • $reason", Fmt.count(n)) }
            if (malformed > 0) counts.addRow("   • Malformed in file", Fmt.count(malformed))
        } else {
            val now = System.currentTimeMillis()
            val verdicts = s.records.map { RestoreEligibility.check(it, now) }
            counts.addRow("Invalid records", Fmt.count(verdicts.count { it is RestoreEligibility.Verdict.Invalid } + malformed))
            counts.addRow("Cannot be restored on Android", Fmt.count(verdicts.count { it is RestoreEligibility.Verdict.NotEligible }))
            counts.addRow("Duplicates", "grant permission to check")
        }

        binding.requirements.text = buildString {
            append("Call log permission (read + write): ").append(if (permitted) "granted ✓" else "NOT granted").append('\n')
            append("Default phone app role: not required. Android allows apps holding the call log permission to add entries, ")
            append("so this app does not ask to replace your phone app")
            append(if (isDefaultDialer) " (it currently is the default)." else ".")
            append("\nAndroid API level: ").append(sdk)
            if (!permitted) append("\n\nGrant the permission to check for duplicates and enable restore.")
        }
        binding.grantButton.isVisible = !permitted
        binding.startRestore.isEnabled = permitted && p != null
        updateFields()
        updateWillInsert()
    }

    private fun options() = RestoreOptions(
        policy = when (binding.policyGroup.checkedRadioButtonId) {
            R.id.policySkipConfirmed -> DuplicatePolicy.SKIP_CONFIRMED_ONLY
            R.id.policyRestoreAll -> DuplicatePolicy.RESTORE_ALL
            else -> DuplicatePolicy.SKIP_LIKELY_DUPLICATES
        },
        keepPhoneAccount = binding.keepAccount.isChecked,
        markAsRead = binding.markRead.isChecked,
    )

    private fun updateFields() {
        val s = snapshot ?: return
        val opts = options()
        val report = RestorePlanner.columnReport(s.records, android.os.Build.VERSION.SDK_INT, providerColumns, opts)
        val restored = report.filter { it.first.disposition == ColumnDisposition.RESTORED }.map { it.first.column }
        val notRestored = report.filter { it.first.disposition != ColumnDisposition.RESTORED }
        binding.fieldsReport.text = buildString {
            append("Restored as backed up: ").append(restored.joinToString()).append('\n')
            if (opts.markAsRead && restored.any { it == "new" || it == "is_read" }) {
                append("(\"new\" and \"is_read\" are set to read because of the option above.)\n")
            }
            if (providerColumns == null) append("(The exact list is confirmed against this device's call log once permission is granted.)\n")
            if (notRestored.isNotEmpty()) {
                append("\nKept in the backup but not written to the call log:\n")
                notRestored.forEach { (d, n) -> append("• ").append(d.column).append(" (").append(n).append(" records): ").append(d.explanation).append('\n') }
            }
        }.trimEnd()
    }

    private fun updateWillInsert() {
        val p = plan
        binding.willInsert.text = if (p == null) "" else {
            val n = p.toInsert(options().policy).size
            if (n == 0) getString(R.string.restore_nothing) else "$n entries will be added to your call history."
        }
        binding.startRestore.isEnabled = p != null && p.toInsert(options().policy).isNotEmpty()
    }

    // ---- Steps 4 & 5: policy and explicit confirmation ------------------------------------

    private fun confirmAndStart() {
        val p = plan ?: return
        val opts = options()
        val n = p.toInsert(opts.policy).size
        if (n == 0) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_confirm_title)
            .setMessage(getString(R.string.restore_confirm_text, n))
            .setPositiveButton(getString(R.string.restore_confirm_button, n)) { _, _ ->
                if (!app.restoreController.start(p, opts) && app.restoreController.isRunning) {
                    showError(getString(R.string.restore_another_running))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmCancel() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Stop restoring?")
            .setMessage("Entries already added stay in your call history. You can retry later; entries already added will be skipped.")
            .setPositiveButton(R.string.restore_cancel) { _, _ -> app.restoreController.cancel() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Steps 6–8: progress, verification and report ------------------------------

    private fun render(state: RestoreState) {
        if (!ownsSession) return
        when (state) {
            RestoreState.Idle -> Unit
            is RestoreState.Running -> {
                showOnly(binding.progressGroup)
                val pr = state.progress
                binding.progressBar.max = maxOf(pr.total, 1)
                binding.progressBar.setProgressCompat(pr.done, true)
                binding.progressText.text = "${pr.done} of ${pr.total} processed · ${pr.restored} added and verified · ${pr.failed} failed\n\n" +
                    "You can leave this screen; the restore continues while the app is running."
            }
            is RestoreState.Finished -> showReport(state.report)
            is RestoreState.Failed -> {
                showError(state.message)
                binding.errorAction.isVisible = snapshot != null || intent.hasExtra(Extras.BACKUP_ID)
                binding.errorAction.text = getString(R.string.restore_retry)
                binding.errorAction.setOnClickListener { retry(intent.getStringExtra(Extras.BACKUP_ID)) }
            }
        }
    }

    private fun showReport(r: RestoreReport) {
        showOnly(binding.resultGroup)
        binding.resultTitle.text = when (r.status) {
            SessionStatus.COMPLETED -> "Restore completed"
            SessionStatus.COMPLETED_WITH_ERRORS -> "Restore completed with problems"
            SessionStatus.ABORTED -> "Restore stopped"
            SessionStatus.CANCELLED -> "Restore cancelled"
            else -> "Restore ${r.status.name.lowercase()}"
        }
        val c = binding.resultCounts
        c.removeAllViews()
        c.addRow("Records in backup", Fmt.count(r.totalInBackup), emphasize = true)
        c.addRow("Successfully restored (verified)", Fmt.count(r.successfullyRestored), emphasize = true)
        if (r.restoredReduced > 0) c.addRow("   of which with essential fields only", Fmt.count(r.restoredReduced))
        c.addRow("Skipped as duplicates", Fmt.count(r.skipped))
        if (r.skippedAlreadyRestored > 0) c.addRow("   restored earlier by this app", Fmt.count(r.skippedAlreadyRestored))
        c.addRow("Failed", Fmt.count(r.failed))
        if (r.failedVerification > 0) c.addRow("   inserted but failed verification", Fmt.count(r.failedVerification))
        c.addRow("Not eligible / invalid", Fmt.count(r.notEligible + r.invalid))
        if (r.notAttempted > 0) c.addRow("Not attempted (stopped early)", Fmt.count(r.notAttempted))

        binding.resultNotes.text = buildString {
            append("Verification: every entry counted as restored was read back from Android's call log and matched the backup's number, date, duration and type.")
            r.presentInFinalCheck?.let {
                append(" A final re-check of the whole call log found ").append(it).append(" of ").append(r.successfullyRestored).append(" restored entries.")
                if (it < r.successfullyRestored) append(" Some entries disappeared after insertion — the system or another app may limit or rewrite call history.")
            }
            r.abortReason?.let { append("\n\n").append(it) }
            if (r.failures.isNotEmpty()) {
                append("\n\nFailures:")
                r.failures.take(25).forEach { f ->
                    append("\n• ").append(Fmt.date(f.record.date)).append(" — ")
                        .append(if (f.outcome == EntryOutcome.FAILED_VERIFICATION) "verification: " else "").append(f.reason)
                }
                if (r.failures.size > 25) append("\n… and ").append(r.failures.size - 25).append(" more")
            }
            append("\n\nThe backup itself was not modified.")
        }
        binding.retryButton.isVisible = r.failed > 0 || r.notAttempted > 0 || r.status == SessionStatus.ABORTED
        binding.retryButton.setOnClickListener { retry(r.backupId) }
    }

    /** Re-plans against the current call log; entries restored earlier are recognised and skipped. */
    private fun retry(backupId: String?) {
        app.restoreController.acknowledge()
        if (snapshot != null) buildPreview() else loadSource(backupId)
    }

    private fun showError(message: String) {
        showOnly(binding.errorGroup)
        binding.errorText.text = message
        binding.errorAction.isVisible = false
    }

    private fun showOnly(group: View) {
        listOf(binding.loadingGroup, binding.errorGroup, binding.previewGroup, binding.progressGroup, binding.resultGroup)
            .forEach { it.isVisible = it == group }
    }
}
