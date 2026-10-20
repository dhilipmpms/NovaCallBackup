package org.opencall.archive.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencall.archive.R
import org.opencall.archive.core.BackupOrigin
import org.opencall.archive.core.restore.SessionStatus
import org.opencall.archive.data.BackupResult
import org.opencall.archive.data.CallLogAccess
import org.opencall.archive.data.RestoreState
import org.opencall.archive.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var permissions: CallLogPermissionRequester
    private var backupRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions = CallLogPermissionRequester(this, { requireActivity() }) { refreshLive() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val main = requireActivity() as MainActivity
        binding.grantButton.setOnClickListener { permissions.request() }
        binding.backupNow.setOnClickListener { backupNow() }
        binding.restoreButton.setOnClickListener {
            if (requireContext().app.repository.backups.value.isEmpty()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.restore_backup)
                    .setMessage("There are no saved backups yet. You can restore directly from a backup file instead.")
                    .setPositiveButton(R.string.restore_from_file) { _, _ -> main.chooseBackupToRestore() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else main.chooseBackupToRestore()
        }
        binding.searchButton.setOnClickListener { main.selectTab(R.id.nav_search) }
        binding.importButton.setOnClickListener { main.startImport() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    requireContext().app.repository.backups.collect { list ->
                        binding.backupCount.text = Fmt.count(list.size)
                        binding.latestBackup.text = list.maxByOrNull { it.createdAt }?.let { Fmt.date(it.createdAt) }
                            ?: getString(R.string.home_none)
                    }
                }
                launch {
                    requireContext().app.restoreController.state.collect { updateBanner(it) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLive()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        if (!hidden) refreshLive()
    }

    private fun refreshLive() {
        val ctx = context ?: return
        if (_binding == null) return
        val hasProvider = CallLogAccess.providerAvailable(ctx)
        val hasRead = CallLogAccess.hasRead(ctx)
        binding.permissionCard.isVisible = hasProvider && !ctx.isPermissionHeld()
        viewLifecycleOwner.lifecycleScope.launch {
            val count = ctx.app.repository.liveCount()
            if (_binding == null) return@launch
            binding.liveCount.text = when {
                !hasProvider -> getString(R.string.home_no_provider)
                !hasRead -> getString(R.string.home_unavailable)
                count == null -> "Could not read"
                else -> Fmt.count(count)
            }
            updateBanner(ctx.app.restoreController.state.value)
        }
    }

    private fun updateBanner(state: RestoreState) {
        val ctx = context ?: return
        when (state) {
            is RestoreState.Running -> {
                binding.restoreBanner.isVisible = true
                binding.restoreBannerText.text = "Restoring \"${state.backupLabel}\": ${state.progress.done} of ${state.progress.total}"
                binding.restoreBannerButton.setOnClickListener { startActivity(Intent(ctx, RestoreActivity::class.java)) }
            }
            else -> viewLifecycleOwner.lifecycleScope.launch {
                val interrupted = withContext(Dispatchers.IO) {
                    ctx.app.restoreController.sessions().firstOrNull()?.takeIf { it.status == SessionStatus.INTERRUPTED }
                }
                if (_binding == null) return@launch
                binding.restoreBanner.isVisible = interrupted != null
                if (interrupted != null) {
                    binding.restoreBannerText.text =
                        "The last restore (\"${interrupted.header.backupLabel}\") was interrupted after " +
                            "${interrupted.restored} verified entries. Review it and retry the remaining records; " +
                            "entries already added will not be added again."
                    binding.restoreBannerButton.setOnClickListener {
                        startActivity(
                            Intent(ctx, RestoreActivity::class.java)
                                .putExtra(Extras.BACKUP_ID, interrupted.header.backupId)
                                .putExtra(Extras.RETRY, true),
                        )
                    }
                }
            }
        }
    }

    private fun backupNow() {
        val ctx = requireContext()
        if (!CallLogAccess.providerAvailable(ctx)) {
            showStatus(getString(R.string.home_no_provider))
            return
        }
        if (!CallLogAccess.hasRead(ctx)) {
            permissions.request()
            return
        }
        if (backupRunning) return
        backupRunning = true
        binding.backupNow.isEnabled = false
        showStatus("Reading call history…")
        viewLifecycleOwner.lifecycleScope.launch {
            val result = ctx.app.repository.createBackup(BackupOrigin.MANUAL)
            backupRunning = false
            if (_binding == null) return@launch
            binding.backupNow.isEnabled = true
            showStatus(
                when (result) {
                    is BackupResult.Saved -> "Backup saved and verified: ${Fmt.count(result.meta.recordCount)} records (\"${result.meta.label}\")."
                    is BackupResult.Unchanged -> "No changes since \"${result.existing.label}\"."
                    BackupResult.EmptyCallLog -> "Your call history is empty, so there is nothing to back up. No backup was created."
                    BackupResult.PermissionMissing -> "Backup failed: call log permission is not granted."
                    BackupResult.ProviderUnavailable -> getString(R.string.home_no_provider)
                    is BackupResult.Failed -> "Backup failed: ${result.message}"
                },
            )
            refreshLive()
        }
    }

    private fun showStatus(text: String) {
        binding.backupStatus.isVisible = true
        binding.backupStatus.text = text
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
