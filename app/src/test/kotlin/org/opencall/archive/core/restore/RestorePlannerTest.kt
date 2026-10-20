package org.opencall.archive.core.restore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencall.archive.TestData
import org.opencall.archive.TestData.BASE
import org.opencall.archive.TestData.call
import org.opencall.archive.core.CallTypes
import org.opencall.archive.core.FieldValue

class RestorePlannerTest {
    private val now = BASE + 365L * 86_400_000

    @Test fun `classifies every record`() {
        val fresh = call(number = "+15550001", date = BASE + 1_000_000)
        val exact = call(number = "+15550002", date = BASE + 2_000_000)
        val near = call(number = "+15550003", date = BASE + 3_000_000)
        val restoredBefore = call(number = "+15550004", date = BASE + 4_000_000)
        val repeated = call(number = "+15550005", date = BASE + 5_000_000)
        val backup = TestData.snapshot(
            listOf(
                fresh, exact, near, restoredBefore, repeated, repeated,
                call(date = 0),
                call(date = now + 2 * 86_400_000),
                call(duration = -1),
                call(type = CallTypes.VOICEMAIL),
                call(type = 42),
            ),
        )
        val live = listOf(exact, near.copy(date = near.date + 700), restoredBefore)
        val plan = RestorePlanner.plan(backup, live, setOf(restoredBefore.fingerprint), now)
        val statuses = plan.items.map { it.status }
        assertEquals(
            listOf(
                PlanStatus.ELIGIBLE, PlanStatus.CONFIRMED_DUPLICATE, PlanStatus.PROBABLE_DUPLICATE,
                PlanStatus.ALREADY_RESTORED, PlanStatus.ELIGIBLE, PlanStatus.DUPLICATE_IN_BACKUP,
                PlanStatus.INVALID, PlanStatus.INVALID, PlanStatus.INVALID,
                PlanStatus.NOT_ELIGIBLE, PlanStatus.NOT_ELIGIBLE,
            ),
            statuses,
        )
        assertTrue(plan.items[2].detail!!.contains("timestamps differ"))
        assertTrue(plan.items[9].detail!!.contains("Voicemail"))
        assertEquals(3, plan.liveRecordCount)
    }

    @Test fun `policy decides what is inserted`() {
        fun inserts(s: PlanStatus) = DuplicatePolicy.entries.map { RestorePlan.willInsert(s, it) }
        assertEquals(listOf(true, true, true), inserts(PlanStatus.ELIGIBLE))
        assertEquals(listOf(false, true, true), inserts(PlanStatus.PROBABLE_DUPLICATE))
        assertEquals(listOf(false, false, true), inserts(PlanStatus.CONFIRMED_DUPLICATE))
        assertEquals(listOf(false, false, true), inserts(PlanStatus.DUPLICATE_IN_BACKUP))
        assertEquals(listOf(false, false, false), inserts(PlanStatus.ALREADY_RESTORED))
        assertEquals(listOf(false, false, false), inserts(PlanStatus.INVALID))
        assertEquals(listOf(false, false, false), inserts(PlanStatus.NOT_ELIGIBLE))
    }

    @Test fun `previously restored record that was later removed becomes eligible again`() {
        val c = call()
        val plan = RestorePlanner.plan(TestData.snapshot(listOf(c)), emptyList(), setOf(c.fingerprint), now)
        assertEquals(PlanStatus.ELIGIBLE, plan.items.single().status)
    }

    @Test fun `empty call history means everything valid is eligible`() {
        val plan = RestorePlanner.plan(TestData.snapshot(TestData.calls(50)), emptyList(), emptySet(), now)
        assertEquals(50, plan.count(PlanStatus.ELIGIBLE))
    }

    @Test fun `column decisions respect sdk level, device columns and options`() {
        val device = RestoreColumns.WRITABLE.keys
        assertEquals(ColumnDisposition.RESTORED, RestoreColumns.decide("block_reason", 29, device, true).disposition)
        assertEquals(ColumnDisposition.NOT_SUPPORTED_ON_THIS_ANDROID_VERSION, RestoreColumns.decide("block_reason", 28, device, true).disposition)
        assertEquals(ColumnDisposition.NOT_SUPPORTED_ON_THIS_ANDROID_VERSION, RestoreColumns.decide("is_business_call", 34, device, true).disposition)
        assertEquals(ColumnDisposition.NOT_PRESENT_ON_THIS_DEVICE, RestoreColumns.decide("missed_reason", 35, device - "missed_reason", true).disposition)
        assertEquals(ColumnDisposition.EXCLUDED_BY_OPTION, RestoreColumns.decide("subscription_id", 35, device, false).disposition)
        assertEquals(ColumnDisposition.PROVIDER_COMPUTED, RestoreColumns.decide("countryiso", 35, device, true).disposition)
        assertEquals(ColumnDisposition.CONTACT_CACHE, RestoreColumns.decide("photo_uri", 35, device, true).disposition)
        assertEquals(ColumnDisposition.SYSTEM_MANAGED, RestoreColumns.decide("last_modified", 35, device, true).disposition)
        assertEquals(ColumnDisposition.VOICEMAIL_ONLY, RestoreColumns.decide("voicemail_uri", 35, device, true).disposition)
        assertEquals(ColumnDisposition.UNDOCUMENTED, RestoreColumns.decide("oem_secret_column", 35, device, true).disposition)
        // Unknown provider columns (permission not granted yet): only the SDK gate applies.
        assertEquals(ColumnDisposition.RESTORED, RestoreColumns.decide("features", 26, null, true).disposition)
    }

    @Test fun `column report counts how many records carry each field`() {
        val records = listOf(
            call(extras = mapOf("countryiso" to FieldValue.Text("IN"), "features" to FieldValue.Integer(0))),
            call(date = BASE + 9_000, extras = mapOf("countryiso" to FieldValue.Text("IN"))),
        )
        val report = RestorePlanner.columnReport(records, 35, null, RestoreOptions())
        val byColumn = report.associate { it.first.column to it }
        assertEquals(2, byColumn.getValue("countryiso").second)
        assertEquals(1, byColumn.getValue("features").second)
        assertFalse(byColumn.getValue("countryiso").first.disposition == ColumnDisposition.RESTORED)
        // Restored columns are listed first.
        assertEquals(ColumnDisposition.RESTORED, report.first().first.disposition)
    }
}
