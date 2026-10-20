package org.opencall.archive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencall.archive.TestData
import org.opencall.archive.TestData.BASE
import org.opencall.archive.TestData.call
import java.io.StringWriter
import java.time.ZoneOffset

class CsvExporterTest {
    @Test fun `escapes quotes commas and newlines per RFC 4180`() {
        assertEquals("plain", CsvExporter.escape("plain"))
        assertEquals("\"a,b\"", CsvExporter.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvExporter.escape("say \"hi\""))
        assertEquals("\"line1\nline2\"", CsvExporter.escape("line1\nline2"))
        assertEquals("\" padded \"", CsvExporter.escape(" padded "))
    }

    @Test fun `neutralises spreadsheet formulas but keeps phone numbers intact`() {
        assertEquals("'=HYPERLINK(1)", CsvExporter.escape("=HYPERLINK(1)"))
        assertEquals("'@SUM(A1)", CsvExporter.escape("@SUM(A1)"))
        assertEquals("'+cmd", CsvExporter.escape("+cmd"))
        assertEquals("\"'=1,2\"", CsvExporter.escape("=1,2"))
        assertEquals("+91 98765 43210", CsvExporter.escape("+91 98765 43210"))
        assertEquals("+15550100", CsvExporter.escape("+15550100"))
    }

    @Test fun `writes header and one row per record`() {
        val out = StringWriter()
        CsvExporter.write(listOf(call(name = "Doe, Jane"), call(number = "", type = CallTypes.MISSED)), out, ZoneOffset.UTC)
        val lines = out.toString().split("\r\n").filter { it.isNotEmpty() }
        assertEquals(3, lines.size)
        assertEquals(CsvExporter.HEADER.joinToString(","), lines[0])
        assertEquals("2024-01-15T10:00:00Z,1705312800000,+15550100,\"Doe, Jane\",incoming,60,allowed,,", lines[1])
        assertTrue(lines[2].contains(",missed,"))
    }

    @Test fun `empty export has only the header`() {
        val out = StringWriter()
        CsvExporter.write(emptyList(), out)
        assertEquals(CsvExporter.HEADER.joinToString(",") + "\r\n", out.toString())
    }
}

class SearchEngineTest {
    private val day = 86_400_000L
    private val older = TestData.snapshot(
        listOf(
            call(number = "+91 98765 43210", name = "Arun", type = CallTypes.OUTGOING, date = BASE),
            call(number = "+91 90000 11111", name = "Bala", type = CallTypes.INCOMING, date = BASE + day),
            call(number = "+15550100", name = null, type = CallTypes.MISSED, date = BASE + 40 * day, duration = 0),
        ),
        id = "old",
    )
    private val newer = TestData.snapshot(
        listOf(
            call(number = "+91 98765 43210", name = "Arun", type = CallTypes.OUTGOING, date = BASE), // same call again
            call(number = "+91 98765 43210", name = "Arun", type = CallTypes.INCOMING, date = BASE + 2 * day, duration = 600),
        ),
        id = "new",
    )
    private val all = listOf(newer, older)

    @Test fun `search by name is case insensitive and deduplicated across backups`() {
        val hits = SearchEngine.search(all, SearchCriteria(text = "arun"))
        assertEquals(2, hits.size)
        val repeated = hits.first { it.record.date == BASE }
        assertEquals(listOf("new", "old"), repeated.backupIds)
    }

    @Test fun `partial number search ignores formatting`() {
        assertEquals(2, SearchEngine.search(all, SearchCriteria(text = "98765")).size)
        assertEquals(2, SearchEngine.search(all, SearchCriteria(text = "765 432")).size)
        assertEquals(1, SearchEngine.search(all, SearchCriteria(text = "555-01")).size)
        assertEquals(0, SearchEngine.search(all, SearchCriteria(text = "424242")).size)
    }

    @Test fun `combined name date range and type filter`() {
        val hits = SearchEngine.search(
            all,
            SearchCriteria(text = "Arun", from = BASE - day, until = BASE + day, types = setOf(CallTypes.OUTGOING)),
        )
        assertEquals(1, hits.size)
        assertEquals(CallTypes.OUTGOING, hits[0].record.type)
    }

    @Test fun `date range bounds are inclusive start exclusive end`() {
        assertEquals(1, SearchEngine.search(all, SearchCriteria(from = BASE + day, until = BASE + day + 1)).size)
        assertEquals(0, SearchEngine.search(all, SearchCriteria(from = BASE + day + 1, until = BASE + 2 * day)).size)
    }

    @Test fun `type and duration filters`() {
        assertEquals(1, SearchEngine.search(all, SearchCriteria(types = setOf(CallTypes.MISSED))).size)
        assertEquals(2, SearchEngine.search(all, SearchCriteria(types = setOf(CallTypes.INCOMING))).size)
        assertEquals(1, SearchEngine.search(all, SearchCriteria(minDurationSeconds = 300)).size)
        assertEquals(1, SearchEngine.search(all, SearchCriteria(maxDurationSeconds = 0)).size)
    }

    @Test fun `restricting to one backup`() {
        val hits = SearchEngine.search(all, SearchCriteria(backupId = "old"))
        assertEquals(3, hits.size)
        assertTrue(hits.all { it.backupIds == listOf("old") })
    }

    @Test fun `results are newest first and empty archives give no results`() {
        val hits = SearchEngine.search(all, SearchCriteria())
        assertEquals(hits.sortedByDescending { it.record.date }, hits)
        assertEquals(0, SearchEngine.search(emptyList(), SearchCriteria(text = "x")).size)
    }
}

class CompareEngineTest {
    @Test fun `classifies both, only-left, only-right and probable`() {
        val shared = call(date = BASE)
        val probableLeft = call(number = "+15550199", date = BASE + 100_000)
        val probableRight = probableLeft.copy(date = probableLeft.date + 300)
        val onlyLeft = call(number = "+15550111", date = BASE + 200_000)
        val onlyRight = call(number = "+15550122", date = BASE + 300_000)
        val r = CompareEngine.compare(
            listOf(shared, probableLeft, onlyLeft),
            listOf(shared.copy(cachedName = "New name"), probableRight, onlyRight),
        )
        assertEquals(1, r.confirmed.size)
        assertEquals(1, r.confirmedWithDifferences)
        assertEquals("cached name", r.confirmed[0].differences.single().field)
        assertEquals(1, r.probable.size)
        assertEquals(listOf(onlyLeft), r.onlyLeft)
        assertEquals(listOf(onlyRight), r.onlyRight)
    }

    @Test fun `matching is one to one for repeated identical calls`() {
        val c = call()
        val r = CompareEngine.compare(listOf(c, c), listOf(c))
        assertEquals(1, r.confirmed.size)
        assertEquals(1, r.onlyLeft.size)
        assertEquals(0, r.onlyRight.size)
    }

    @Test fun `confirmed matches are not stolen by probable ones`() {
        val a = call(date = BASE)
        val nearA = a.copy(date = BASE + 500)
        // Left order puts the near-duplicate first; it must not take the exact partner of `a`.
        val r = CompareEngine.compare(listOf(nearA, a), listOf(a))
        assertEquals(1, r.confirmed.size)
        assertEquals(a, r.confirmed[0].left)
        assertEquals(listOf(nearA), r.onlyLeft)
    }

    @Test fun `comparing empty datasets`() {
        val r = CompareEngine.compare(emptyList(), TestData.calls(3))
        assertEquals(3, r.onlyRight.size)
        assertEquals(0, r.confirmed.size)
    }
}
