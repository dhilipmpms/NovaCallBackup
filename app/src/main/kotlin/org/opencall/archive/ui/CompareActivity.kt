package org.opencall.archive.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nova.callbackup.R
import org.opencall.archive.core.CallRecord
import org.opencall.archive.core.CompareEngine
import org.opencall.archive.core.CompareResult
import org.opencall.archive.core.MatchedPair
import org.opencall.archive.data.CallLogAccess
import com.nova.callbackup.databinding.ActivityCompareBinding

/** Read-only comparison. Nothing is restored or deleted from here. */
class CompareActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCompareBinding
    private var result: CompareResult? = null
    private var leftName = ""
    private var rightName = ""
    private val adapter = RecordAdapter { row -> showRecordDetails(this, row.record, row.badge + (row.note?.let { "\n$it" } ?: "")) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompareBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding()
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        val leftId = intent.getStringExtra(Extras.LEFT_ID) ?: return finish()
        val rightId = intent.getStringExtra(Extras.RIGHT_ID) ?: return finish()

        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        lifecycleScope.launch {
            try {
                val left = app.repository.loadSnapshot(leftId)
                leftName = "\"${left.label}\""
                val right: List<CallRecord> = if (rightId == Extras.LIVE) {
                    if (!CallLogAccess.hasRead(this@CompareActivity)) {
                        binding.loading.isVisible = false
                        binding.summary.text = "Comparing with the current call history requires call log permission. Grant it from Settings."
                        return@launch
                    }
                    rightName = "current call history"
                    app.repository.liveRecords()
                } else {
                    val s = app.repository.loadSnapshot(rightId)
                    rightName = "\"${s.label}\""
                    s.records
                }
                val r = withContext(Dispatchers.Default) { CompareEngine.compare(left.records, right) }
                result = r
                render(r)
            } catch (e: Exception) {
                binding.summary.text = "Comparison failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                binding.loading.isVisible = false
            }
        }
    }

    private fun render(r: CompareResult) {
        binding.toolbar.subtitle = "$leftName vs $rightName"
        binding.summary.text = buildString {
            append("A = ").append(leftName).append(": ").append(Fmt.count(r.leftTotal)).append(" records\n")
            append("B = ").append(rightName).append(": ").append(Fmt.count(r.rightTotal)).append(" records\n\n")
            append("In both (exact match): ").append(Fmt.count(r.confirmed.size))
            if (r.confirmedWithDifferences > 0) append(" — ").append(r.confirmedWithDifferences).append(" with metadata differences")
            append('\n')
            append("Possible duplicates (similar, not identical): ").append(Fmt.count(r.probable.size)).append('\n')
            append("Only in A: ").append(Fmt.count(r.onlyLeft.size)).append('\n')
            append("Only in B: ").append(Fmt.count(r.onlyRight.size)).append("\n\n")
            append("This comparison is read-only. Nothing was restored or deleted.")
        }
        binding.tabs.removeAllTabs()
        listOf(
            "Both (${r.confirmed.size})", "Possible (${r.probable.size})",
            "Only A (${r.onlyLeft.size})", "Only B (${r.onlyRight.size})",
        ).forEach { binding.tabs.addTab(binding.tabs.newTab().setText(it)) }
    }

    private fun showTab(position: Int) {
        val r = result ?: return
        val archived = getString(R.string.archived_badge)
        val rows = when (position) {
            0 -> r.confirmed.map { pairRow(it, "In both") }
            1 -> r.probable.map { pairRow(it, "Possible duplicate") }
            2 -> r.onlyLeft.map { RecordRow(it, "Only in A", "$archived · $leftName") }
            else -> r.onlyRight.map {
                RecordRow(it, "Only in B", if (rightName == "current call history") getString(R.string.live_badge) else "$archived · $rightName")
            }
        }
        adapter.submitList(rows)
    }

    private fun pairRow(p: MatchedPair, badge: String): RecordRow {
        val notes = buildList {
            if (p.reasons.isNotEmpty()) add("Why uncertain: " + p.reasons.joinToString("; "))
            if (p.differences.isNotEmpty()) add("Differences: " + p.differences.joinToString("; ") { "${it.field}: ${it.left ?: "—"} → ${it.right ?: "—"}" })
        }
        return RecordRow(p.left, badge, notes.joinToString("\n").ifEmpty { null })
    }
}
