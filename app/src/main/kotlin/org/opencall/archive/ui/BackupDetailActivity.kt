package org.opencall.archive.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencall.archive.R
import org.opencall.archive.core.BackupJson
import org.opencall.archive.core.BackupSnapshot
import org.opencall.archive.core.SearchCriteria
import org.opencall.archive.core.SearchEngine
import org.opencall.archive.data.BackupMeta
import org.opencall.archive.databinding.ActivityBackupDetailBinding
import java.io.IOException

class BackupDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBackupDetailBinding
    private lateinit var backupId: String
    private var meta: BackupMeta? = null
    private var snapshot: BackupSnapshot? = null
    private val adapter = RecordAdapter { row ->
        showRecordDetails(this, row.record, "Archived in \"${meta?.label}\". This view shows the backup, not your current call history.")
    }

    private val exportJsonLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) export { app.repository.exportJson(backupId, uri) }
    }
    private val exportCsvLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val s = snapshot
        if (uri != null && s != null) export { app.repository.exportCsv(s.records, uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBackupDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding()
        binding.toolbar.setNavigationOnClickListener { finish() }
        backupId = intent.getStringExtra(Extras.BACKUP_ID) ?: run { finish(); return }

        binding.records.layoutManager = LinearLayoutManager(this)
        binding.records.adapter = adapter
        binding.filter.doAfterTextChanged { applyFilter() }

        binding.restoreButton.setOnClickListener {
            startActivity(Intent(this, RestoreActivity::class.java).putExtra(Extras.BACKUP_ID, backupId))
        }
        binding.compareButton.setOnClickListener { chooseComparison() }
        binding.exportJson.setOnClickListener { exportJsonLauncher.launch(fileName("json")) }
        binding.exportCsv.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.export_csv)
                .setMessage("CSV is meant for spreadsheets and reports. It contains the main call fields only and cannot be used to restore. Use JSON for a complete backup.")
                .setPositiveButton(R.string.export_csv) { _, _ -> exportCsvLauncher.launch(fileName("csv")) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        binding.deleteButton.setOnClickListener { confirmDelete() }
        load()
    }

    private fun fileName(ext: String): String {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(java.util.Date(meta?.createdAt ?: 0L))
        return "opencall-backup-$date.$ext"
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                val m = app.repository.store.meta(backupId)
                val s = app.repository.loadSnapshot(backupId)
                meta = m
                snapshot = s
                binding.toolbar.title = s.label
                val hashOk = withContext(Dispatchers.Default) { m?.contentHash == BackupJson.contentHash(s.records) }
                binding.info.text = buildString {
                    append("Created ").append(Fmt.date(s.createdAt)).append(" · ").append(Fmt.count(s.records.size)).append(" records\n")
                    m?.let { append("Source: ").append(originLabel(it)).append(" · ").append(Fmt.bytes(it.sizeBytes)).append('\n') }
                    s.sourceSdk?.let { append("Made on Android API ").append(it).append('\n') }
                    append(if (hashOk) "Integrity check passed (SHA-256)." else "Integrity check FAILED: the stored file does not match its checksum.")
                }
                applyFilter()
            } catch (e: IOException) {
                binding.info.text = "This backup could not be opened: ${e.message}"
                listOf(binding.restoreButton, binding.compareButton, binding.exportCsv).forEach { it.isEnabled = false }
            }
        }
    }

    private fun applyFilter() {
        val s = snapshot ?: return
        val q = binding.filter.text?.toString().orEmpty()
        val rows = s.records.asSequence()
            .filter { SearchEngine.matches(it, SearchCriteria(text = q)) }
            .map { RecordRow(it, getString(R.string.archived_badge)) }
            .toList()
        adapter.submitList(rows)
        binding.count.text = "${Fmt.count(rows.size)} of ${Fmt.count(s.records.size)} archived records"
    }

    private fun chooseComparison() {
        val others = app.repository.backups.value.filter { it.id != backupId }
        val labels = listOf(getString(R.string.compare_with_current)) + others.map { it.label }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.compare_with_title)
            .setItems(labels.toTypedArray()) { _, which ->
                val right = if (which == 0) Extras.LIVE else others[which - 1].id
                startActivity(
                    Intent(this, CompareActivity::class.java)
                        .putExtra(Extras.LEFT_ID, backupId)
                        .putExtra(Extras.RIGHT_ID, right),
                )
            }
            .show()
    }

    private fun export(block: suspend () -> Unit) {
        lifecycleScope.launch {
            val message = try {
                block()
                "Export complete."
            } catch (e: SecurityException) {
                "Export failed: access to the destination was denied."
            } catch (e: IOException) {
                "Export failed: ${e.message ?: "storage error"}"
            }
            Toast.makeText(this@BackupDetailActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete() {
        val m = meta ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_backup_title)
            .setMessage(getString(R.string.delete_backup_text, m.label, m.recordCount))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    if (app.repository.delete(backupId)) finish()
                    else Toast.makeText(this@BackupDetailActivity, "Could not delete the backup.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
