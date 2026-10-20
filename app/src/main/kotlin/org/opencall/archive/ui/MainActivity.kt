package org.opencall.archive.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import com.nova.callbackup.R
import org.opencall.archive.data.ImportOutcome
import org.opencall.archive.data.OpenedDocument
import com.nova.callbackup.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handleImport(uri)
    }
    private val restoreFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            startActivity(
                Intent(this, RestoreActivity::class.java)
                    .putExtra(Extras.DOCUMENT_URI, uri.toString())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding()

        binding.bottomNav.setOnItemSelectedListener { item ->
            show(item.itemId)
            true
        }
        if (savedInstanceState == null) binding.bottomNav.selectedItemId = R.id.nav_home
        else show(binding.bottomNav.selectedItemId)
        lifecycleScope.launch { app.repository.refresh() }
    }

    private fun show(itemId: Int) {
        val tag = "tab_$itemId"
        val fm = supportFragmentManager
        val existing = fm.findFragmentByTag(tag)
        val tx = fm.beginTransaction()
        fm.fragments.forEach { if (it != existing) tx.hide(it) }
        if (existing != null) tx.show(existing) else tx.add(R.id.container, create(itemId), tag)
        tx.commit()
        binding.toolbar.title = when (itemId) {
            R.id.nav_search -> getString(R.string.nav_search)
            R.id.nav_backups -> getString(R.string.nav_backups)
            R.id.nav_settings -> getString(R.string.nav_settings)
            else -> getString(R.string.app_name)
        }
    }

    private fun create(itemId: Int): Fragment = when (itemId) {
        R.id.nav_search -> SearchFragment()
        R.id.nav_backups -> BackupsFragment()
        R.id.nav_settings -> SettingsFragment()
        else -> HomeFragment()
    }

    fun selectTab(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
    }

    // ---- Import -------------------------------------------------------------------

    fun startImport() = importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))

    private fun handleImport(uri: Uri) {
        lifecycleScope.launch {
            val doc = app.repository.openDocument(uri)
            showValidation(doc)
        }
    }

    private fun showValidation(doc: OpenedDocument) {
        val r = doc.result
        if (!r.isValid) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Cannot import this file")
                .setMessage((doc.displayName?.let { "$it\n\n" } ?: "") + (r.fatalError ?: "Unknown problem.") +
                    "\n\nNothing was imported. CSV exports cannot be imported because they do not contain the full call metadata.")
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        val snapshot = r.snapshot!!
        val msg = buildString {
            doc.displayName?.let { append("File: ").append(it).append('\n') }
            append("Backup: ").append(snapshot.label).append('\n')
            if (snapshot.createdAt > 0) append("Created: ").append(Fmt.date(snapshot.createdAt)).append('\n')
            append("Format version: ").append(r.formatVersion).append('\n')
            append("Entries in file: ").append(Fmt.count(r.totalEntries)).append('\n')
            append("Readable records: ").append(Fmt.count(snapshot.records.size)).append('\n')
            if (r.invalidEntries.isNotEmpty()) append("Malformed entries (skipped): ").append(Fmt.count(r.invalidEntries.size)).append('\n')
            if (r.warnings.isNotEmpty()) {
                append("\nWarnings:\n")
                r.warnings.forEach { append("• ").append(it).append('\n') }
            }
            append("\nImporting saves an archived copy inside the app. It does not change your call history; restoring is a separate step.")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Import backup?")
            .setMessage(msg)
            .setPositiveButton("Save as archived backup") { _, _ -> saveImport(doc) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveImport(doc: OpenedDocument) {
        lifecycleScope.launch {
            when (val outcome = app.repository.importDocument(doc)) {
                is ImportOutcome.Saved -> MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Backup imported")
                    .setMessage("${Fmt.count(outcome.meta.recordCount)} records were archived as \"${outcome.meta.label}\". Your call history was not changed.")
                    .setPositiveButton("Open backup") { _, _ -> openBackup(outcome.meta.id) }
                    .setNegativeButton(R.string.close, null)
                    .show()
                is ImportOutcome.AlreadyArchived -> MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Already archived")
                    .setMessage("A backup with exactly the same records is already saved: \"${outcome.existing.label}\". No duplicate copy was created.")
                    .setPositiveButton("Open backup") { _, _ -> openBackup(outcome.existing.id) }
                    .setNegativeButton(R.string.close, null)
                    .show()
                is ImportOutcome.Failed -> MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Import failed")
                    .setMessage(outcome.message)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }

    fun openBackup(id: String) {
        startActivity(Intent(this, BackupDetailActivity::class.java).putExtra(Extras.BACKUP_ID, id))
    }

    // ---- Restore entry point -------------------------------------------------------------

    /** Step 1 of restore: choose a saved backup or a backup file. */
    fun chooseBackupToRestore() {
        val backups = app.repository.backups.value
        val labels = backups.map { "${it.label} (${Fmt.count(it.recordCount)} records)" } + getString(R.string.restore_from_file)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_backup)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == backups.size) {
                    restoreFileLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                } else {
                    startActivity(Intent(this, RestoreActivity::class.java).putExtra(Extras.BACKUP_ID, backups[which].id))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
