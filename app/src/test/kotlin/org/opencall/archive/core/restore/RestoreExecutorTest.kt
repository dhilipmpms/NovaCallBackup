package org.opencall.archive.core.restore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.opencall.archive.TestData
import org.opencall.archive.TestData.BASE
import org.opencall.archive.TestData.call
import org.opencall.archive.core.BackupSnapshot
import org.opencall.archive.core.CallRecord
import org.opencall.archive.core.Columns
import org.opencall.archive.core.FieldValue
import org.opencall.archive.core.MatchLevel
import org.opencall.archive.core.RecordIndex
import java.io.File

class RestoreExecutorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = BASE + 365L * 86_400_000

    private fun journal(dir: File = tmp.root.resolve("journal")) = FileRestoreJournalStore(dir)

    private suspend fun restore(
        backup: BackupSnapshot,
        log: FakeCallLog,
        store: RestoreJournalStore = journal(),
        options: RestoreOptions = RestoreOptions(),
        sdk: Int = 35,
        session: String = "s-" + System.nanoTime(),
    ): RestoreReport {
        val plan = RestorePlanner.plan(backup, log.readAll(), store.restoredFingerprints(), now)
        return RestoreExecutor(log, store, sdk) { now }.execute(session, plan, options)
    }

    private fun countConfirmed(log: FakeCallLog, r: CallRecord) =
        log.records.count { org.opencall.archive.core.DuplicateMatcher.compare(r, it).level == MatchLevel.CONFIRMED }

    @Test fun `restores eligible records, verifies them, and skips duplicates`() = runTest {
        val existing = TestData.calls(5)
        val backup = TestData.snapshot(existing + TestData.calls(10, start = BASE + 10_000_000))
        val log = FakeCallLog(existing)
        val report = restore(backup, log)
        assertEquals(SessionStatus.COMPLETED, report.status)
        assertEquals(15, report.totalInBackup)
        assertEquals(10, report.successfullyRestored)
        assertEquals(5, report.skippedConfirmed)
        assertEquals(0, report.failed)
        assertEquals(10, report.presentInFinalCheck)
        assertEquals(15, log.records.size)
        backup.records.forEach { assertEquals(1, countConfirmed(log, it)) }
    }

    @Test fun `writes original values and only writable columns`() = runTest {
        val c = call(
            number = "+91 98765 43210", name = "Arun", account = "sim-1",
            extras = mapOf(
                "countryiso" to FieldValue.Text("IN"),
                "photo_uri" to FieldValue.Text("content://contacts/1"),
                "features" to FieldValue.Integer(1),
                "block_reason" to FieldValue.Integer(0),
                Columns.NEW to FieldValue.Integer(1),
                Columns.IS_READ to FieldValue.Integer(0),
            ),
        ).copy(phoneAccountComponent = "com.android.phone/X")
        val log = FakeCallLog()
        restore(TestData.snapshot(listOf(c)), log, sdk = 28)
        val v = log.insertedValues.single()
        assertEquals(FieldValue.Text("+91 98765 43210"), v[Columns.NUMBER])
        assertEquals(FieldValue.Integer(BASE), v[Columns.DATE])
        assertEquals(FieldValue.Integer(60), v[Columns.DURATION])
        assertEquals(FieldValue.Text("Arun"), v[Columns.CACHED_NAME])
        assertEquals(FieldValue.Text("sim-1"), v[Columns.PHONE_ACCOUNT_ID])
        assertEquals(FieldValue.Integer(1), v["features"])
        assertFalse("provider-computed", "countryiso" in v)
        assertFalse("contact cache", "photo_uri" in v)
        assertFalse("API 29 column on API 28", "block_reason" in v)
        // markAsRead default overrides new/is_read.
        assertEquals(FieldValue.Integer(0), v[Columns.NEW])
        assertEquals(FieldValue.Integer(1), v[Columns.IS_READ])
    }

    @Test fun `options can drop phone account and keep original read state`() = runTest {
        val c = call(account = "sim-1", extras = mapOf(Columns.NEW to FieldValue.Integer(1), Columns.IS_READ to FieldValue.Integer(0)))
        val log = FakeCallLog()
        restore(TestData.snapshot(listOf(c)), log, options = RestoreOptions(keepPhoneAccount = false, markAsRead = false))
        val v = log.insertedValues.single()
        assertFalse(Columns.PHONE_ACCOUNT_ID in v)
        assertEquals(FieldValue.Integer(1), v[Columns.NEW])
    }

    @Test fun `columns missing on the device are never sent`() = runTest {
        val log = FakeCallLog().apply { columns = setOf(Columns.NUMBER, Columns.DATE, Columns.DURATION, Columns.TYPE) }
        val report = restore(TestData.snapshot(listOf(call(name = "X", account = "a"))), log)
        assertEquals(1, report.restored)
        assertEquals(setOf(Columns.NUMBER, Columns.DATE, Columns.DURATION, Columns.TYPE), log.insertedValues.single().keys)
    }

    @Test fun `partial failure is reported accurately`() = runTest {
        val calls = TestData.calls(10)
        val rejected = calls[3].number
        val corrupted = calls[6].number
        val log = FakeCallLog().apply {
            rejectIf = { (it[Columns.NUMBER] as FieldValue.Text).value == rejected }
            corruptDurationIf = { (it[Columns.NUMBER] as FieldValue.Text).value == corrupted }
        }
        val report = restore(TestData.snapshot(calls), log)
        assertEquals(SessionStatus.COMPLETED_WITH_ERRORS, report.status)
        assertEquals(8, report.restored)
        assertEquals(1, report.failedInsert)
        assertEquals(1, report.failedVerification)
        assertEquals(2, report.failures.size)
        assertTrue(report.failures.first { it.outcome == EntryOutcome.FAILED_VERIFICATION }.reason.contains("duration"))
    }

    @Test fun `provider rejecting metadata falls back to essential fields`() = runTest {
        val log = FakeCallLog().apply { illegalArgumentIf = { Columns.CACHED_NAME in it } }
        val report = restore(TestData.snapshot(listOf(call(name = "Arun"))), log)
        assertEquals(1, report.restoredReduced)
        assertEquals(1, report.successfullyRestored)
        assertFalse(Columns.CACHED_NAME in log.insertedValues.single())
    }

    @Test fun `permission revoked mid-restore aborts and reports remaining`() = runTest {
        val log = FakeCallLog().apply { revokePermissionAfter = 4 }
        val report = restore(TestData.snapshot(TestData.calls(10)), log)
        assertEquals(SessionStatus.ABORTED, report.status)
        assertEquals(4, report.restored)
        assertEquals(6, report.notAttempted)
        assertNotNull(report.abortReason)
        assertTrue(report.abortReason!!.contains("denied"))
    }

    @Test fun `entries disappearing after insertion are caught by the final check`() = runTest {
        val log = FakeCallLog().apply { trimAfterReadBack = true }
        val report = restore(TestData.snapshot(TestData.calls(3)), log)
        assertEquals(3, report.restored)
        assertEquals(0, report.presentInFinalCheck)
        assertEquals(SessionStatus.COMPLETED_WITH_ERRORS, report.status)
    }

    @Test fun `cancellation is journaled and retry restores only the rest`() = runTest {
        val backup = TestData.snapshot(TestData.calls(20))
        val log = FakeCallLog()
        val store = journal()
        val plan = RestorePlanner.plan(backup, log.readAll(), store.restoredFingerprints(), now)
        try {
            RestoreExecutor(log, store, 35) { now }.execute("first", plan, RestoreOptions()) { p ->
                if (p.done == 7) throw CancellationException("user cancelled")
            }
            fail("expected cancellation")
        } catch (e: CancellationException) {
            // expected
        }
        assertEquals(7, log.records.size)
        val session = store.sessions().single()
        assertEquals(SessionStatus.CANCELLED, session.status)
        assertEquals(7, session.restored)

        val retry = restore(backup, log, store, RestoreOptions(policy = DuplicatePolicy.RESTORE_ALL))
        assertEquals(13, retry.successfullyRestored)
        assertEquals(7, retry.skippedAlreadyRestored)
        assertEquals(20, log.records.size)
        backup.records.forEach { assertEquals(1, countConfirmed(log, it)) }
    }

    @Test fun `process death between insert and journal write does not cause duplicates on retry`() = runTest {
        val backup = TestData.snapshot(TestData.calls(10))
        val log = FakeCallLog().apply { dieAfterInsertNumber = 5 }
        val dir = tmp.root.resolve("journal-kill")
        try {
            restore(backup, log, journal(dir), session = "killed")
            fail("expected simulated death")
        } catch (e: SimulatedProcessDeath) {
            // The 5th row exists in the provider but its outcome was never journaled.
        }
        assertEquals(5, log.records.size)

        // A new process: fresh journal store instance over the same files.
        val reopened = journal(dir)
        val summary = reopened.sessions().single()
        assertEquals(SessionStatus.INTERRUPTED, summary.status)
        assertEquals(4, summary.restored)
        assertEquals(1, summary.unresolved)

        log.dieAfterInsertNumber = null
        // Even the most aggressive policy must not re-insert anything this app already wrote.
        val retry = restore(backup, log, reopened, RestoreOptions(policy = DuplicatePolicy.RESTORE_ALL))
        assertEquals(5, retry.skippedAlreadyRestored)
        assertEquals(5, retry.successfullyRestored)
        assertEquals(10, log.records.size)
        backup.records.forEach { assertEquals(1, countConfirmed(log, it)) }
    }

    @Test fun `restore all policy duplicates existing calls only when chosen`() = runTest {
        val c = call()
        val log = FakeCallLog(listOf(c))
        val skip = restore(TestData.snapshot(listOf(c)), log)
        assertEquals(0, skip.successfullyRestored)
        assertEquals(1, log.records.size)
        val all = restore(TestData.snapshot(listOf(c)), log, options = RestoreOptions(policy = DuplicatePolicy.RESTORE_ALL))
        assertEquals(1, all.successfullyRestored)
        assertEquals(2, log.records.size)
    }

    @Test fun `nothing to insert completes without touching the provider`() = runTest {
        val existing = TestData.calls(3)
        val log = FakeCallLog(existing)
        val report = restore(TestData.snapshot(existing), log)
        assertEquals(0, report.planned)
        assertEquals(SessionStatus.COMPLETED, report.status)
        assertTrue(log.insertedValues.isEmpty())
    }

    @Test fun `invalid and ineligible records are never written`() = runTest {
        val log = FakeCallLog()
        val report = restore(
            TestData.snapshot(listOf(call(date = -5), call(type = org.opencall.archive.core.CallTypes.VOICEMAIL), call(duration = -2))),
            log,
            options = RestoreOptions(policy = DuplicatePolicy.RESTORE_ALL),
        )
        assertEquals(2, report.invalid)
        assertEquals(1, report.notEligible)
        assertTrue(log.insertedValues.isEmpty())
    }

    @Test fun `journal ignores a torn final line`() {
        val dir = tmp.root.resolve("torn")
        val store = journal(dir)
        store.begin(JournalHeader("t", "b", "label", 1L, RestoreOptions(), 2, 2))
        store.attempt("t", "fp1")
        store.outcome("t", "fp1", EntryOutcome.RESTORED, null)
        dir.resolve("t.jsonl").appendText("{\"kind\":\"outc")
        val reopened = journal(dir)
        assertEquals(setOf("fp1"), reopened.restoredFingerprints())
        assertEquals(SessionStatus.INTERRUPTED, reopened.sessions().single().status)
    }

    @Test fun `bulk restore of a realistic call log`() = runTest {
        val backup = TestData.snapshot(TestData.calls(2_000))
        val log = FakeCallLog(backup.records.take(1_500))
        val report = restore(backup, log)
        assertEquals(500, report.successfullyRestored)
        assertEquals(1_500, report.skippedConfirmed)
        val index = RecordIndex(log.records)
        assertTrue(backup.records.all { index.bestMatch(it)?.second?.level == MatchLevel.CONFIRMED })
    }
}
