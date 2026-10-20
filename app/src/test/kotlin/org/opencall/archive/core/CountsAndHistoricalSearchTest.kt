package org.opencall.archive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.opencall.archive.TestData
import org.opencall.archive.TestData.BASE
import org.opencall.archive.TestData.call
import org.opencall.archive.data.BackupStore

/** Regression tests for record counts and historical (cross-device) contact search. */
class CountsAndHistoricalSearchTest {
    @get:Rule val tmp = TemporaryFolder()

    private val mother = "+91 90870 54184"
    private val day = 86_400_000L

    private fun providerRow(i: Int, number: String = "+1555012%05d".format(i)): Map<String, FieldValue> = mapOf(
        Columns.NUMBER to FieldValue.Text(number),
        Columns.DATE to FieldValue.Integer(BASE + i * 60_000L),
        Columns.DURATION to FieldValue.Integer((i % 400).toLong()),
        Columns.TYPE to FieldValue.Integer(((i % 3) + 1).toLong()),
    )

    // ---- 1, 2, 3, 12, 13: retrieval and backup counts --------------------------------------

    @Test fun `reader keeps every row beyond any previously observed count`() {
        val n = 25_000
        val result = RecordReader.collect(n, (0 until n).asSequence().map { providerRow(it) })
        assertEquals(n, result.stats.cursorCount)
        assertEquals(n, result.stats.iterated)
        assertEquals(n, result.stats.converted)
        assertEquals(0, result.stats.skippedTotal)
        assertEquals(n, result.records.size)
    }

    @Test fun `optional metadata never decides inclusion, invalid rows are counted with reasons`() {
        val rows = listOf(
            providerRow(1),
            providerRow(2) - Columns.NUMBER, // private number: kept
            mapOf(Columns.DATE to FieldValue.Integer(BASE), Columns.TYPE to FieldValue.Integer(1)), // no duration/name/account: kept
            providerRow(3) - Columns.DATE,
            providerRow(4) + (Columns.DATE to FieldValue.Text("yesterday")),
            providerRow(5) - Columns.TYPE,
            emptyMap(),
        )
        val r = RecordReader.collect(rows.size, rows.asSequence())
        assertEquals(7, r.stats.iterated)
        assertEquals(3, r.stats.converted)
        assertEquals(mapOf("missing date" to 2, "date is not numeric" to 1, "missing call type" to 1), r.stats.skipped)
        assertEquals(r.stats.iterated, r.stats.converted + r.stats.skippedTotal)
    }

    @Test fun `a new call appears in the next backup and older records remain`() {
        val before = RecordReader.collect(20_508, (0 until 20_508).asSequence().map { providerRow(it) }).records
        val after = RecordReader.collect(20_509, (0..20_508).asSequence().map { providerRow(it) }).records
        assertEquals(20_508, before.size)
        assertEquals(20_509, after.size)
        assertTrue(after.containsAll(before))
        assertTrue(after.any { it.number == "+1555012%05d".format(20_508) })
    }

    @Test fun `backup of 25,000 records with duplicates keeps every record through save, load and JSON`() {
        val records = TestData.calls(24_990) + List(10) { call(number = mother, name = "Amma") } // 10 identical rows
        val store = BackupStore(tmp.root.resolve("b"))
        val meta = store.save(TestData.snapshot(records, id = "big"))
        assertEquals(25_000, meta.recordCount)
        val loaded = BackupStore(tmp.root.resolve("b")).load("big")
        assertEquals(25_000, loaded.records.size)
        val decoded = BackupJson.decode(BackupJson.encodeToString(loaded))
        assertEquals(25_000, decoded.snapshot!!.records.size)
        assertEquals(9, decoded.duplicateEntries)
    }

    // ---- 4, 5, 11, 14: search completeness and counts ------------------------------------------

    private fun motherCalls(n: Int, name: String? = "Amma", number: String = mother, start: Long = BASE) =
        (0 until n).map { i -> call(number = number, name = name, date = start + i * 3_600_000L, duration = (i % 50).toLong(), type = (i % 3) + 1) }

    @Test fun `search returns every match in a large backup with an accurate total`() {
        val records = TestData.calls(20_000) + motherCalls(1_300)
        val result = SearchEngine.search(listOf(TestData.snapshot(records)), SearchCriteria(text = "amma"))
        assertEquals(1_300, result.total)
        assertEquals(1_300, result.hits.size)
        assertEquals(21_300, result.recordsSearched)
        // The oldest matching call (end of the backup) is included, not just a first page.
        assertTrue(result.hits.any { it.record.date == BASE })
    }

    @Test fun `filtered totals are exact`() {
        val records = motherCalls(1_200)
        val outgoing = SearchEngine.search(listOf(TestData.snapshot(records)), SearchCriteria(text = "Amma", types = setOf(CallTypes.OUTGOING)))
        assertEquals(records.count { it.type == CallTypes.OUTGOING }, outgoing.total)
        val range = SearchEngine.search(listOf(TestData.snapshot(records)), SearchCriteria(text = "Amma", from = BASE, until = BASE + 10 * day))
        assertEquals(records.count { it.date < BASE + 10 * day }, range.total)
    }

    @Test fun `identical rows in one backup are all counted, the same call across backups once`() {
        val c = call(number = mother, name = "Amma")
        val older = TestData.snapshot(listOf(c, c, c), id = "old")
        val newer = TestData.snapshot(listOf(c, c), id = "new")
        val inOld = SearchEngine.search(listOf(newer, older), SearchCriteria(text = "Amma", backupId = "old"))
        assertEquals(3, inOld.total)
        val all = SearchEngine.search(listOf(newer, older), SearchCriteria(text = "Amma"))
        assertEquals(3, all.total)
        assertEquals(2, all.hits.count { it.backupIds == listOf("new", "old") })
        assertEquals(1, all.hits.count { it.backupIds == listOf("old") })
    }

    // ---- 6, 7, 8, 9, 10: contact matching for migrated records ------------------------------------

    @Test fun `calls with a null or outdated cached name are found through the number`() {
        val records = motherCalls(800) + motherCalls(300, name = null, start = BASE - 400 * day) +
            motherCalls(150, name = "Mom (Redmi)", number = "09087054184", start = BASE - 800 * day)
        val r = SearchEngine.search(listOf(TestData.snapshot(records)), SearchCriteria(text = "Amma"))
        assertEquals(1_250, r.total)
        assertEquals(800, r.count(MatchSource.NAME))
        assertEquals(450, r.count(MatchSource.NAME_VIA_NUMBER))
    }

    @Test fun `current contact numbers find calls cached under an old name and all of the contact's numbers`() {
        val work = "+91 44 2345 6789"
        val records = motherCalls(5, name = "Mother old") + motherCalls(4, name = null, number = work, start = BASE + 99 * day)
        val withoutContacts = SearchEngine.search(listOf(TestData.snapshot(records)), SearchCriteria(text = "Amma"))
        assertEquals(0, withoutContacts.total)
        val withContacts = SearchEngine.search(
            listOf(TestData.snapshot(records)),
            SearchCriteria(text = "Amma", contactNumbers = setOf("9087054184", "04423456789")),
        )
        assertEquals(9, withContacts.total)
        assertEquals(9, withContacts.count(MatchSource.CONTACT_NUMBER))
    }

    @Test fun `every common format of a number finds the same calls`() {
        val records = listOf(
            call(number = "+91 90870 54184", date = BASE),
            call(number = "9087054184", date = BASE + 1 * day),
            call(number = "+919087054184", date = BASE + 2 * day),
            call(number = "09087054184", date = BASE + 3 * day),
            call(number = "+91 90870 54185", date = BASE + 4 * day), // different person
        )
        val s = listOf(TestData.snapshot(records))
        for (q in listOf("+91 90870 54184", "9087054184", "+919087054184", "09087054184")) {
            assertEquals("query $q", 4, SearchEngine.search(s, SearchCriteria(text = q)).total)
        }
        assertEquals(5, SearchEngine.search(s, SearchCriteria(text = "90870")).total) // partial
    }

    @Test fun `records imported from another device stay searchable and keep their original numbers`() {
        val redmi = motherCalls(20, name = null, number = "09087054184").map { it.copy(phoneAccountId = "old-redmi-sim", extras = mapOf("lookup_uri" to FieldValue.Text("content://old/1"))) }
        val imported = TestData.snapshot(redmi, id = "redmi").copy(origin = BackupOrigin.IMPORTED)
        val moto = TestData.snapshot(motherCalls(3, start = BASE + 500 * day), id = "moto")
        val r = SearchEngine.search(listOf(moto, imported), SearchCriteria(text = "Amma"))
        assertEquals(23, r.total)
        assertTrue(r.hits.filter { "redmi" in it.backupIds }.all { it.record.number == "09087054184" })
    }

    @Test fun `people who only share a name are not merged by number linking`() {
        val records = listOf(
            call(number = "+15550100001", name = "Arun", date = BASE),
            call(number = "+15550100002", name = "Arun K", date = BASE + day),
            call(number = "+15550100003", name = null, date = BASE + 2 * day), // never named Arun
            call(number = "+15550100004", name = "Ravi", date = BASE + 3 * day),
        )
        val r = SearchEngine.search(listOf(TestData.snapshot(records)), SearchCriteria(text = "Arun"))
        assertEquals(2, r.total)
        assertFalse(r.hits.any { it.record.number.endsWith("3") || it.record.number.endsWith("4") })
    }
}
