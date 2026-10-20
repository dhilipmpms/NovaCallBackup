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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.opencall.archive.R
import org.opencall.archive.core.BackupOrigin
import org.opencall.archive.data.BackupMeta
import org.opencall.archive.databinding.FragmentBackupsBinding
import org.opencall.archive.databinding.ItemBackupBinding

class BackupsFragment : Fragment() {
    private var _binding: FragmentBackupsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBackupsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val main = requireActivity() as MainActivity
        val adapter = BackupAdapter { main.openBackup(it.id) }
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.importButton.setOnClickListener { main.startImport() }
        binding.compareTwo.setOnClickListener { compareTwo() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                requireContext().app.repository.backups.collect { list ->
                    adapter.submitList(list)
                    binding.empty.isVisible = list.isEmpty()
                    binding.compareTwo.isEnabled = list.size >= 2
                }
            }
        }
    }

    private fun compareTwo() {
        val backups = requireContext().app.repository.backups.value
        if (backups.size < 2) return
        val labels = backups.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("First backup")
            .setItems(labels) { _, first ->
                val rest = backups.filterIndexed { i, _ -> i != first }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Compare with")
                    .setItems(rest.map { it.label }.toTypedArray()) { _, second ->
                        startActivity(
                            Intent(requireContext(), CompareActivity::class.java)
                                .putExtra(Extras.LEFT_ID, backups[first].id)
                                .putExtra(Extras.RIGHT_ID, rest[second].id),
                        )
                    }
                    .show()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class BackupAdapter(private val onClick: (BackupMeta) -> Unit) :
    ListAdapter<BackupMeta, BackupAdapter.VH>(object : DiffUtil.ItemCallback<BackupMeta>() {
        override fun areItemsTheSame(a: BackupMeta, b: BackupMeta) = a.id == b.id
        override fun areContentsTheSame(a: BackupMeta, b: BackupMeta) = a == b
    }) {
    class VH(val b: ItemBackupBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemBackupBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = getItem(position)
        holder.b.title.text = m.label
        holder.b.subtitle.text = listOf(
            Fmt.date(m.createdAt),
            "${Fmt.count(m.recordCount)} records",
            originLabel(m),
            Fmt.bytes(m.sizeBytes),
        ).joinToString(" · ")
        holder.b.root.setOnClickListener { onClick(m) }
    }
}

fun originLabel(m: BackupMeta): String = when {
    m.importedFrom != null -> "imported from ${m.importedFrom}"
    m.origin == BackupOrigin.AUTOMATIC -> "automatic"
    else -> "manual"
}
