package org.opencall.archive.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nova.callbackup.BuildConfig
import com.nova.callbackup.R
import org.opencall.archive.data.CallLogAccess
import com.nova.callbackup.databinding.FragmentSettingsBinding
import org.opencall.archive.work.AutoBackupScheduler
import org.opencall.archive.work.AutoBackupSettings

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var permissions: CallLogPermissionRequester
    private lateinit var settings: AutoBackupSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions = CallLogPermissionRequester(this, { requireActivity() }) { refresh() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = AutoBackupSettings(requireContext())
        binding.grantButton.setOnClickListener { permissions.request() }
        binding.appInfoButton.setOnClickListener { CallLogPermissionRequester.openAppInfo(requireContext()) }

        binding.diagnosticsButton.setOnClickListener { runDiagnostics() }
        binding.autoEnabled.isChecked = settings.enabled
        binding.autoCharging.isChecked = settings.requireCharging
        binding.autoSkipUnchanged.isChecked = settings.skipUnchanged
        binding.autoInterval.check(
            when (settings.intervalDays) { 3 -> R.id.interval3; 7 -> R.id.interval7; else -> R.id.interval1 },
        )
        binding.autoEnabled.setOnCheckedChangeListener { _, on ->
            if (on && !CallLogAccess.hasRead(requireContext())) {
                binding.autoEnabled.isChecked = false
                permissions.request()
                return@setOnCheckedChangeListener
            }
            settings.enabled = on; reschedule()
        }
        binding.autoCharging.setOnCheckedChangeListener { _, on -> settings.requireCharging = on; reschedule() }
        binding.autoSkipUnchanged.setOnCheckedChangeListener { _, on -> settings.skipUnchanged = on }
        binding.autoInterval.setOnCheckedChangeListener { _, id ->
            settings.intervalDays = when (id) { R.id.interval3 -> 3; R.id.interval7 -> 7; else -> 1 }
            reschedule()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        if (!hidden) refresh()
    }

    private fun reschedule() {
        AutoBackupScheduler.apply(requireContext())
        refresh()
    }

    private fun refresh() {
        val ctx = context ?: return
        if (_binding == null) return
        val caps = CallLogAccess.capabilities(ctx)
        binding.permissionStatus.text = buildString {
            append("Call log on this device: ").append(if (caps.providerAvailable) "available" else "not available").append('\n')
            append("Read call log: ").append(if (caps.canRead) "granted" else "not granted").append('\n')
            append("Write call log (needed to restore): ").append(if (caps.canWrite) "granted" else "not granted").append('\n')
            append("Android version: API ").append(caps.sdkInt).append("\n\n")
            append("Restoring does not require this app to be your default phone app. Android lets any app ")
            append("that holds the \"Call logs\" permission add entries, so this app never asks to replace your dialer. ")
            append(if (caps.isDefaultDialer) "(This app is currently set as default phone app.)" else "Your default phone app is unchanged.")
        }
        binding.grantButton.isVisible = caps.providerAvailable && !(caps.canRead && caps.canWrite)

        val last = settings.lastRunAt
        binding.autoStatus.text = if (last == 0L) "Automatic backup has not run yet."
        else "Last automatic run: ${Fmt.date(last)} — ${settings.lastResult}"

        viewLifecycleOwner.lifecycleScope.launch {
            val (sessions, bytes) = withContext(Dispatchers.IO) {
                ctx.app.restoreController.sessions() to ctx.app.repository.store.list().sumOf { it.sizeBytes }
            }
            if (_binding == null) return@launch
            binding.restoreHistory.text = if (sessions.isEmpty()) "No restores yet." else sessions.take(10).joinToString("\n\n") { s ->
                val restored = s.report?.let { it.restored + it.restoredReduced } ?: s.restored
                val failed = s.report?.let { it.failedInsert + it.failedVerification } ?: s.failed
                "${Fmt.date(s.header.startedAt)} — \"${s.header.backupLabel}\"\n" +
                    "Status: ${s.status.name.lowercase().replace('_', ' ')} · restored $restored · failed $failed" +
                    (s.report?.let { " · skipped ${it.skipped}" } ?: "") +
                    (if (s.unresolved > 0) " · ${s.unresolved} unconfirmed" else "")
            }
            binding.storageInfo.text = "Backups use ${Fmt.bytes(bytes)} of app-private storage. They are not encrypted, " +
                "are excluded from Android cloud backup and device transfer, and are deleted if you uninstall the app " +
                "— export important backups to a location you choose. Full phone numbers and backup contents are never written to logs."
            binding.about.text = "${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}\nFree and open-source. No ads, analytics, accounts or network access."
        }
    }

    private fun runDiagnostics() {
        val ctx = requireContext()
        if (!CallLogAccess.hasRead(ctx)) {
            binding.diagnosticsText.text = "Call log permission is not granted, so the provider cannot be measured."
            return
        }
        binding.diagnosticsButton.isEnabled = false
        binding.diagnosticsText.text = "Reading the call log…"
        viewLifecycleOwner.lifecycleScope.launch {
            val text = try {
                val d = ctx.app.repository.diagnostics()
                buildString {
                    append("Provider rows (cursor count): ").append(Fmt.count(d.stats.cursorCount)).append('\n')
                    append("Rows iterated: ").append(Fmt.count(d.stats.iterated)).append('\n')
                    append("Rows converted to records: ").append(Fmt.count(d.stats.converted)).append('\n')
                    append("Rows that could not be read: ").append(Fmt.count(d.stats.skippedTotal))
                    d.stats.skipped.forEach { (reason, n) -> append("\n   • ").append(reason).append(": ").append(Fmt.count(n)) }
                    append("\nDistinct calls (exact duplicates merged, for information only): ").append(Fmt.count(d.distinctCalls))
                    d.oldestDate?.let { append("\nOldest call in provider: ").append(Fmt.date(it)) }
                    append("\n\nRows per SIM / phone account:")
                    d.accounts.forEachIndexed { i, a ->
                        append("\n   • ").append(if (a.hasAccount) "Account ${i + 1}" else "No account recorded")
                            .append(": ").append(Fmt.count(a.rows))
                    }
                    append("\n\nLatest backup: ")
                    if (d.latestBackupRecords == null) append("none") else {
                        append(Fmt.count(d.latestBackupRecords)).append(" records")
                        d.latestBackupAt?.let { append(", made ").append(Fmt.date(it)) }
                        d.latestBackupProviderRows?.let { append(" (provider had ").append(Fmt.count(it)).append(" rows then)") }
                    }
                    append("\n\nNote: when a new call is logged, Android deletes the oldest calls of that SIM account " +
                        "beyond a per-SIM limit (500 by default; some manufacturers change it). If the total stays the same " +
                        "after a new call, older entries are being removed by the system, not by this app.")
                }
            } catch (e: SecurityException) {
                "Android denied access to the call log."
            }
            if (_binding == null) return@launch
            binding.diagnosticsText.text = text
            binding.diagnosticsButton.isEnabled = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
