package org.opencall.archive.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.util.Pair
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nova.callbackup.R
import org.opencall.archive.core.BackupSnapshot
import org.opencall.archive.core.CallTypes
import org.opencall.archive.core.MatchSource
import org.opencall.archive.core.SearchCriteria
import org.opencall.archive.core.SearchEngine
import org.opencall.archive.data.BackupMeta
import org.opencall.archive.data.ContactNumbers
import com.nova.callbackup.databinding.FragmentSearchBinding
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Offline search across archived snapshots. Never queries the live call log. */
class SearchFragment : Fragment() {
    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private var snapshots: List<BackupSnapshot> = emptyList()
    private var metas: List<BackupMeta> = emptyList()
    private var selectedBackupId: String? = null
    private var fromDay: LocalDate? = null
    private var toDay: LocalDate? = null
    private var searchJob: Job? = null
    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) binding.useContacts.isChecked = false
        scheduleSearch(0)
    }
    private val adapter = RecordAdapter { row ->
        showRecordDetails(requireContext(), row.record, "Archived call (not necessarily in your current call history). Found in: ${row.note ?: row.badge}")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.results.layoutManager = LinearLayoutManager(requireContext())
        binding.results.adapter = adapter
        binding.query.doAfterTextChanged { scheduleSearch() }
        binding.minDuration.doAfterTextChanged { scheduleSearch() }
        binding.maxDuration.doAfterTextChanged { scheduleSearch() }
        binding.typeChips.setOnCheckedStateChangeListener { _, _ -> scheduleSearch(0) }
        binding.dateRange.setOnClickListener { pickDates() }
        binding.clearDates.setOnClickListener {
            fromDay = null; toDay = null
            updateDateLabel(); scheduleSearch(0)
        }
        binding.useContacts.setOnCheckedChangeListener { _, on ->
            if (on && !ContactNumbers.hasPermission(requireContext())) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.search_use_contacts)
                    .setMessage(R.string.contacts_rationale)
                    .setPositiveButton(R.string.perm_continue) { _, _ -> contactsPermission.launch(ContactNumbers.PERMISSION) }
                    .setNegativeButton(R.string.cancel) { _, _ -> binding.useContacts.isChecked = false }
                    .setOnCancelListener { binding.useContacts.isChecked = false }
                    .show()
            } else scheduleSearch(0)
        }
        binding.backupSelector.setOnItemClickListener { _, _, position, _ ->
            selectedBackupId = if (position == 0) null else metas.getOrNull(position - 1)?.id
            scheduleSearch(0)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                requireContext().app.repository.backups.collect { list ->
                    metas = list
                    if (selectedBackupId != null && list.none { it.id == selectedBackupId }) selectedBackupId = null
                    val labels = listOf(getString(R.string.search_all_backups)) +
                        list.map { "${it.label} (${Fmt.count(it.recordCount)})" }
                    binding.backupSelector.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels))
                    val idx = selectedBackupId?.let { id -> list.indexOfFirst { it.id == id } + 1 } ?: 0
                    binding.backupSelector.setText(labels[idx], false)
                    snapshots = requireContext().app.repository.loadAllSnapshots()
                    scheduleSearch(0)
                }
            }
        }
    }

    private fun pickDates() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Date range")
            .apply {
                val f = fromDay; val t = toDay
                if (f != null && t != null) setSelection(Pair(utcMillis(f), utcMillis(t)))
            }
            .build()
        picker.addOnPositiveButtonClickListener { sel ->
            // The picker reports UTC midnight of the chosen days.
            fromDay = Instant.ofEpochMilli(sel.first).atZone(ZoneOffset.UTC).toLocalDate()
            toDay = Instant.ofEpochMilli(sel.second).atZone(ZoneOffset.UTC).toLocalDate()
            updateDateLabel(); scheduleSearch(0)
        }
        picker.show(childFragmentManager, "dates")
    }

    private fun utcMillis(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun updateDateLabel() {
        val f = fromDay; val t = toDay
        binding.dateRange.text = if (f != null && t != null) {
            val zone = ZoneId.systemDefault()
            "${Fmt.day(f.atStartOfDay(zone).toInstant().toEpochMilli())} – ${Fmt.day(t.atStartOfDay(zone).toInstant().toEpochMilli())}"
        } else getString(R.string.search_date_any)
        binding.clearDates.isVisible = f != null
    }

    private fun criteria(): SearchCriteria {
        val zone = ZoneId.systemDefault()
        val types = buildSet {
            if (binding.chipIncoming.isChecked) { add(CallTypes.INCOMING); add(CallTypes.ANSWERED_EXTERNALLY) }
            if (binding.chipOutgoing.isChecked) add(CallTypes.OUTGOING)
            if (binding.chipMissed.isChecked) add(CallTypes.MISSED)
            if (binding.chipRejected.isChecked) add(CallTypes.REJECTED)
            if (binding.chipBlocked.isChecked) add(CallTypes.BLOCKED)
            if (binding.chipVoicemail.isChecked) add(CallTypes.VOICEMAIL)
        }
        return SearchCriteria(
            text = binding.query.text?.toString().orEmpty(),
            from = fromDay?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
            until = toDay?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
            types = types,
            minDurationSeconds = binding.minDuration.text?.toString()?.toLongOrNull(),
            maxDurationSeconds = binding.maxDuration.text?.toString()?.toLongOrNull(),
            backupId = selectedBackupId,
        )
    }

    private fun scheduleSearch(delayMs: Long = 250) {
        if (_binding == null) return
        searchJob?.cancel()
        val base = criteria()
        val data = snapshots
        val useContacts = binding.useContacts.isChecked
        val ctx = requireContext().applicationContext
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            if (delayMs > 0) delay(delayMs)
            val result = withContext(Dispatchers.Default) {
                val contactNumbers = if (useContacts && base.text.isNotBlank()) {
                    withContext(Dispatchers.IO) { ContactNumbers.numbersForName(ctx, base.text) }
                } else emptySet()
                SearchEngine.search(data, base.copy(contactNumbers = contactNumbers))
            }
            val labelOf = metas.associate { it.id to it.label }
            val rows = result.hits.mapIndexed { i, hit ->
                val badge = getString(R.string.archived_badge) +
                    if (hit.backupIds.size > 1) " · ${hit.backupIds.size} backups" else ""
                val via = when (hit.matchedBy) {
                    MatchSource.NAME_VIA_NUMBER -> "Matched by number: this number has that name on other calls\n"
                    MatchSource.CONTACT_NUMBER -> "Matched by number of a current contact\n"
                    else -> ""
                }
                RecordRow(hit.record, badge, via + hit.backupIds.joinToString { labelOf[it] ?: "backup" }, i)
            }
            adapter.submitList(rows)
            binding.resultCount.text = when {
                metas.isEmpty() -> getString(R.string.search_no_backups)
                else -> buildString {
                    append(Fmt.count(result.total)).append(" matching calls")
                    append(if (base.backupId == null) " across ${metas.size} backups" else " in the selected backup")
                    append(" (").append(Fmt.count(result.recordsSearched)).append(" records searched)")
                    val viaNumber = result.count(MatchSource.NAME_VIA_NUMBER) + result.count(MatchSource.CONTACT_NUMBER)
                    if (viaNumber > 0) {
                        append("\n").append(Fmt.count(result.count(MatchSource.NAME))).append(" by saved name, ")
                        append(Fmt.count(viaNumber)).append(" by ").append(result.linkedNumbers).append(" linked numbers")
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
