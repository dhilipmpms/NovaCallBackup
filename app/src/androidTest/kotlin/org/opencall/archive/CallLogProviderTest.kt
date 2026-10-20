package org.opencall.archive

import android.Manifest
import android.content.Context
import android.os.Build
import android.provider.CallLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencall.archive.core.BackupJson
import org.opencall.archive.core.BackupOrigin
import org.opencall.archive.core.BackupSnapshot
import org.opencall.archive.core.CallRecord
import org.opencall.archive.core.CallTypes
import org.opencall.archive.core.Columns
import org.opencall.archive.core.DuplicateMatcher
import org.opencall.archive.core.FieldValue
import org.opencall.archive.core.MatchLevel
import org.opencall.archive.core.Presentation
import org.opencall.archive.core.restore.CallLogGateway
import org.opencall.archive.core.restore.DuplicatePolicy
import org.opencall.archive.core.restore.FileRestoreJournalStore
import org.opencall.archive.core.restore.RestoreColumns
import org.opencall.archive.core.restore.RestoreExecutor
import org.opencall.archive.core.restore.RestoreOptions
import org.opencall.archive.core.restore.RestorePlanner
import org.opencall.archive.core.restore.RestoreReport
import org.opencall.archive.core.restore.SessionStatus
import org.opencall.archive.data.AndroidCallLogGateway
import org.opencall.archive.data.CallLogAccess
import java.io.File

/**
 * Device tests against Android's real CallLog provider.
 *
 * They insert synthetic calls using fictional 555-01xx numbers and remove exactly those rows
 * afterwards (test cleanup only; the app itself never deletes call history). To avoid touching
 * a personal phone by accident they only run on emulators unless the instrumentation argument
 * `allowRealDevice=true` is passed.
 */
@RunWith(AndroidJUnit4::class)
class CallLogProviderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val gateway = AndroidCallLogGateway(context.contentResolver)
    private lateinit var journalDir: File
    private val base = System.currentTimeMillis() - 30L * 86_400_000

    private fun testNumber(i: Int) = "+1555010%04d".format(i)

    @Before fun setUp() {
        val emulator = Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") || Build.PRODUCT.contains("sdk")
        val allowed = InstrumentationRegistry.getArguments().getString("allowRealDevice") == "true"
        assumeTrue("Call-log write tests run only on emulators unless allowRealDevice=true", emulator || allowed)
        val ui = instrumentation.uiAutomation
        ui.grantRuntimePermission(context.packageName, Manifest.permission.READ_CALL_LOG)
        ui.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CALL_LOG)
        assumeTrue("Device has no call-log provider", CallLogAccess.providerAvailable(context))
        journalDir = File(context.cacheDir, "test-journal-" + System.nanoTime())
        cleanUp()
    }

    @After fun cleanUp() {
        // Skipped tests never granted permissions or touched the call log.
        if (!::journalDir.isInitialized) return
        context.contentResolver.delete(
            CallLog.Calls.CONTENT_URI, "${CallLog.Calls.NUMBER} LIKE ?", arrayOf("+1555010%"),
        )
        journalDir.deleteRecursively()
    }

    private fun testRecords(n: Int, offset: Int = 0): List<CallRecord> = (0 until n).map { i ->
        val k = i + offset
        CallRecord(
            number = testNumber(k),
            date = base + k * 3_600_000L + 123, // non-round millis to prove exact timestamps survive
            duration = (k * 7L) % 900,
            type = listOf(CallTypes.INCOMING, CallTypes.OUTGOING, CallTypes.MISSED, CallTypes.REJECTED, CallTypes.BLOCKED)[k % 5],
            presentation = Presentation.ALLOWED,
            cachedName = "Test Contact $k",
        )
    }

    private fun ourRows(): List<CallRecord> = gateway.readAll().filter { it.number.startsWith("+1555010") }

    private fun snapshot(records: List<CallRecord>) =
        BackupSnapshot("device-test", "Device test", System.currentTimeMillis(), BackupOrigin.MANUAL, Build.VERSION.SDK_INT, "test", records)

    private fun restore(
        records: List<CallRecord>,
        options: RestoreOptions = RestoreOptions(),
        g: CallLogGateway = gateway,
        journal: FileRestoreJournalStore = FileRestoreJournalStore(journalDir),
        onProgress: (Int) -> Unit = {},
    ): RestoreReport = runBlocking {
        val plan = RestorePlanner.plan(snapshot(records), g.readAll(), journal.restoredFingerprints(), System.currentTimeMillis())
        RestoreExecutor(g, journal, Build.VERSION.SDK_INT).execute("s-" + System.nanoTime(), plan, options) { onProgress(it.done) }
    }

    @Test fun providerReportsCoreColumns() {
        val columns = gateway.providerColumns()
        listOf(Columns.NUMBER, Columns.DATE, Columns.DURATION, Columns.TYPE, Columns.PRESENTATION, Columns.CACHED_NAME)
            .forEach { assertTrue("missing column $it", it in columns) }
        val writable = RestoreColumns.writableColumns(Build.VERSION.SDK_INT, columns, true)
        assertTrue(writable.containsAll(RestoreColumns.ESSENTIAL))
    }

    @Test fun restoreInsertsVerifiesAndPreservesFields() {
        val records = testRecords(25)
        val report = restore(records)
        assertEquals(SessionStatus.COMPLETED, report.status)
        assertEquals(25, report.successfullyRestored)
        assertEquals(0, report.failed)
        assertEquals(25, report.presentInFinalCheck)

        val rows = ourRows()
        assertEquals(25, rows.size)
        for (r in records) {
            val stored = rows.single { it.number == r.number }
            assertEquals(r.date, stored.date)
            assertEquals(r.duration, stored.duration)
            assertEquals(r.type, stored.type)
            assertEquals(r.presentation, stored.presentation)
            assertEquals(r.cachedName, stored.cachedName)
            // markAsRead (default) stores restored calls as already seen.
            assertEquals(FieldValue.Integer(0), stored.extras[Columns.NEW])
        }
    }

    @Test fun everyWritableColumnRoundTrips() {
        val columns = gateway.providerColumns()
        val writable = RestoreColumns.writableColumns(Build.VERSION.SDK_INT, columns, true)
        val extras = buildMap<String, FieldValue> {
            if ("numbertype" in writable) put("numbertype", FieldValue.Integer(2))
            if ("numberlabel" in writable) put("numberlabel", FieldValue.Text("Work"))
            if ("features" in writable) put("features", FieldValue.Integer(1))
            if ("data_usage" in writable) put("data_usage", FieldValue.Integer(4096))
            if ("post_dial_digits" in writable) put("post_dial_digits", FieldValue.Text(",123"))
            if ("via_number" in writable) put("via_number", FieldValue.Text("+15550109999"))
            if ("block_reason" in writable) put("block_reason", FieldValue.Integer(0))
            if ("call_screening_app_name" in writable) put("call_screening_app_name", FieldValue.Text("Screen"))
            if ("missed_reason" in writable) put("missed_reason", FieldValue.Integer(0))
            if ("priority" in writable) put("priority", FieldValue.Integer(0))
            if ("subject" in writable) put("subject", FieldValue.Text("Subject line"))
        }
        val record = testRecords(1).single().copy(
            extras = extras,
            phoneAccountComponent = "com.android.phone/com.android.services.telephony.TelephonyConnectionService",
            phoneAccountId = "test-account-1",
        )
        val report = restore(listOf(record), RestoreOptions(markAsRead = false))
        assertEquals(1, report.restored)
        val stored = ourRows().single()
        assertEquals(record.phoneAccountId, stored.phoneAccountId)
        assertEquals(record.phoneAccountComponent, stored.phoneAccountComponent)
        for ((k, v) in extras) assertEquals("column $k", v.asText(), stored.extras[k]?.asText())
    }

    @Test fun backupOfLiveCallLogRoundTripsThroughJson() {
        restore(testRecords(10))
        val live = ourRows()
        val decoded = BackupJson.decode(BackupJson.encodeToString(snapshot(live))).snapshot!!
        assertEquals(live.size, decoded.records.size)
        for (r in live) {
            assertTrue(decoded.records.any { DuplicateMatcher.compare(it, r).level == MatchLevel.CONFIRMED && it.extras == r.extras })
        }
    }

    @Test fun retryDoesNotCreateDuplicates() {
        val records = testRecords(12)
        assertEquals(12, restore(records).successfullyRestored)
        val again = restore(records)
        assertEquals(0, again.successfullyRestored)
        assertEquals(12, again.skippedAlreadyRestored)
        // Even with the most permissive policy, rows this app already restored are not re-added.
        val all = restore(records, RestoreOptions(policy = DuplicatePolicy.RESTORE_ALL))
        assertEquals(0, all.successfullyRestored)
        assertEquals(12, ourRows().size)
    }

    @Test fun interruptedRestoreCanBeRetried() {
        val records = testRecords(20)
        val journal = FileRestoreJournalStore(journalDir)
        try {
            restore(records, journal = journal) { done -> if (done == 8) throw CancellationException("stop") }
        } catch (e: CancellationException) {
            // expected
        }
        assertEquals(8, ourRows().size)
        assertEquals(SessionStatus.CANCELLED, journal.sessions().first().status)
        val retry = restore(records, journal = FileRestoreJournalStore(journalDir))
        assertEquals(12, retry.successfullyRestored)
        assertEquals(8, retry.skippedAlreadyRestored)
        assertEquals(20, ourRows().size)
    }

    @Test fun partialFailureIsReportedAccurately() {
        val records = testRecords(6)
        val rejecting = object : CallLogGateway by gateway {
            override fun insert(values: Map<String, FieldValue>): String? =
                if ((values[Columns.NUMBER] as FieldValue.Text).value == testNumber(2)) null else gateway.insert(values)
        }
        val report = restore(records, g = rejecting)
        assertEquals(SessionStatus.COMPLETED_WITH_ERRORS, report.status)
        assertEquals(5, report.successfullyRestored)
        assertEquals(1, report.failedInsert)
        assertEquals(5, ourRows().size)
        // Retrying with the real provider adds only the failed one.
        val retry = restore(records)
        assertEquals(1, retry.successfullyRestored)
        assertEquals(6, ourRows().size)
    }

    @Test fun probableDuplicatesFollowThePolicy() {
        val original = testRecords(1).single()
        restore(listOf(original))
        // Same call as exported by a tool that truncates timestamps to whole seconds.
        val truncated = original.copy(date = original.date / 1000 * 1000)
        val skip = restore(listOf(truncated))
        assertEquals(1, skip.skippedProbable)
        assertEquals(1, ourRows().size)
        val include = restore(listOf(truncated), RestoreOptions(policy = DuplicatePolicy.SKIP_CONFIRMED_ONLY))
        assertEquals(1, include.successfullyRestored)
        assertEquals(2, ourRows().size)
    }

    @Test fun voicemailAndInvalidRecordsAreNotWritten() {
        val bad = listOf(
            testRecords(1).single().copy(type = CallTypes.VOICEMAIL),
            testRecords(1, offset = 1).single().copy(duration = -5),
        )
        val report = restore(bad, RestoreOptions(policy = DuplicatePolicy.RESTORE_ALL))
        assertEquals(1, report.notEligible)
        assertEquals(1, report.invalid)
        assertEquals(0, ourRows().size)
    }

    @Test fun readBackOfMissingRowReturnsNull() {
        val uri = gateway.insert(
            mapOf(
                Columns.NUMBER to FieldValue.Text(testNumber(9000)),
                Columns.DATE to FieldValue.Integer(base),
                Columns.DURATION to FieldValue.Integer(1),
                Columns.TYPE to FieldValue.Integer(CallTypes.OUTGOING.toLong()),
            ),
        )
        assertNotNull(uri)
        cleanUp()
        assertEquals(null, gateway.readBack(uri!!))
    }
}
