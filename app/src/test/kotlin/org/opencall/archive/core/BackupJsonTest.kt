package org.opencall.archive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencall.archive.TestData
import org.opencall.archive.TestData.call

class BackupJsonTest {
    private val rich = call(
        number = "+91 98765 43210", name = "Arun \"A\" Kumar", account = "89910000000000000001",
        extras = mapOf(
            "numbertype" to FieldValue.Integer(2),
            "numberlabel" to FieldValue.Text("Work, main"),
            "features" to FieldValue.Integer(1),
            "data_usage" to FieldValue.Integer(123456789012),
            "geocoded_location" to FieldValue.Text("Chennai\nTN"),
            "some_real" to FieldValue.Real(1.5),
        ),
    ).copy(phoneAccountComponent = "com.android.phone/com.android.services.telephony.TelephonyConnectionService")

    @Test fun `round trip preserves every field`() {
        val snap = TestData.snapshot(listOf(rich, call(number = "", type = CallTypes.MISSED).copy(presentation = Presentation.RESTRICTED)))
        val text = BackupJson.encodeToString(snap)
        val result = BackupJson.decode(text)
        assertTrue(result.isValid)
        assertEquals(1, result.formatVersion)
        assertEquals(emptyList<String>(), result.warnings)
        val decoded = result.snapshot!!
        assertEquals(snap.id, decoded.id)
        assertEquals(snap.label, decoded.label)
        assertEquals(snap.createdAt, decoded.createdAt)
        assertEquals(snap.sourceSdk, decoded.sourceSdk)
        assertEquals(snap.records.canonicalOrder(), decoded.records)
        assertEquals(FieldValue.Text("Work, main"), decoded.records.first { it.number.isNotEmpty() }.extras["numberlabel"])
        assertEquals(FieldValue.Real(1.5), decoded.records.first { it.number.isNotEmpty() }.extras["some_real"])
    }

    @Test fun `content hash is order independent`() {
        val a = TestData.calls(20)
        assertEquals(BackupJson.contentHash(a), BackupJson.contentHash(a.reversed()))
        assertFalse(BackupJson.contentHash(a) == BackupJson.contentHash(a.drop(1)))
    }

    @Test fun `rejects non json and foreign json`() {
        assertEquals("File is not valid JSON.", BackupJson.decode("not json {").fatalError)
        assertTrue(BackupJson.decode("[1,2]").fatalError!!.contains("expected a JSON object"))
        assertTrue(BackupJson.decode("""{"format":"other","formatVersion":1,"records":[]}""").fatalError!!.contains("unknown format"))
        assertFalse(BackupJson.decode("").isValid)
    }

    @Test fun `rejects unsupported and missing versions`() {
        val newer = BackupJson.decode("""{"format":"${BackupJson.FORMAT}","formatVersion":2,"records":[]}""")
        assertNull(newer.snapshot)
        assertEquals(2, newer.formatVersion)
        assertTrue(newer.fatalError!!.contains("newer version"))
        assertTrue(BackupJson.decode("""{"format":"${BackupJson.FORMAT}","records":[]}""").fatalError!!.contains("format version"))
        assertTrue(BackupJson.decode("""{"format":"${BackupJson.FORMAT}","formatVersion":"1","records":[]}""").fatalError!!.contains("format version"))
        assertTrue(BackupJson.decode("""{"format":"${BackupJson.FORMAT}","formatVersion":1}""").fatalError!!.contains("no records"))
    }

    @Test fun `malformed entries are reported individually and the rest is kept`() {
        val json = """
            {"format":"${BackupJson.FORMAT}","formatVersion":1,"recordCount":6,"records":[
              {"number":"+15550100","date":1705312800000,"duration":5,"type":1},
              "oops",
              {"number":"+15550101","duration":5,"type":1},
              {"number":12345,"date":1705312800000,"duration":5,"type":1},
              {"number":"+15550102","date":"yesterday","duration":5,"type":1},
              {"number":"+15550103","date":1705312800000,"duration":5,"type":2,"extra":{"BAD NAME":1}}
            ]}
        """.trimIndent()
        val r = BackupJson.decode(json)
        assertTrue(r.isValid)
        assertEquals(6, r.totalEntries)
        assertEquals(1, r.snapshot!!.records.size)
        assertEquals(listOf(1, 2, 3, 4, 5), r.invalidEntries.map { it.index })
        assertEquals("entry is not an object", r.invalidEntries[0].reason)
        assertEquals("missing or invalid date", r.invalidEntries[1].reason)
        assertEquals("number is not text", r.invalidEntries[2].reason)
        assertTrue(r.warnings.any { it.contains("5 entries are malformed") })
        assertTrue(r.warnings.any { it.contains("no checksum") })
    }

    @Test fun `malformed entries in a checksummed file are flagged as integrity problems`() {
        val text = BackupJson.encodeToString(TestData.snapshot(TestData.calls(2)))
            .replace("\n]}", ",\n\"garbage\"\n]}")
        val r = BackupJson.decode(text)
        assertTrue(r.warnings.any { it.contains("Checksum cannot be verified") })
    }

    @Test fun `nested values and oversized strings are rejected`() {
        val nested = """{"format":"${BackupJson.FORMAT}","formatVersion":1,"records":[
            {"number":"1","date":1,"duration":0,"type":1,"extra":{"x":{"y":1}}},
            {"number":"${"9".repeat(300)}","date":1,"duration":0,"type":1},
            {"number":"1","date":1,"duration":0,"type":1,"extra":{"x":"${"a".repeat(BackupJson.MAX_STRING_LENGTH + 1)}"}}
        ]}"""
        val r = BackupJson.decode(nested)
        assertEquals(3, r.invalidEntries.size)
        assertEquals(0, r.snapshot!!.records.size)
    }

    @Test fun `checksum mismatch and count mismatch produce warnings`() {
        val snap = TestData.snapshot(TestData.calls(3))
        val tampered = BackupJson.encodeToString(snap).replace("\"duration\":1,", "\"duration\":2,")
        val r = BackupJson.decode(tampered)
        assertTrue(r.isValid)
        assertTrue(r.warnings.any { it.contains("Checksum does not match") })
        val miscount = BackupJson.encodeToString(snap).replace("\"recordCount\":3", "\"recordCount\":9")
        assertTrue(BackupJson.decode(miscount).warnings.any { it.contains("Header says 9") })
    }

    @Test fun `duplicate entries inside a file are counted`() {
        val c = call()
        val r = BackupJson.decode(BackupJson.encodeToString(TestData.snapshot(listOf(c, c, c))))
        assertEquals(2, r.duplicateEntries)
        assertEquals(3, r.snapshot!!.records.size)
    }

    @Test fun `too many records is fatal`() {
        val sb = StringBuilder("""{"format":"${BackupJson.FORMAT}","formatVersion":1,"records":[""")
        repeat(BackupJson.MAX_RECORDS + 1) { if (it > 0) sb.append(','); sb.append("0") }
        sb.append("]}")
        assertNotNull(BackupJson.decode(sb.toString()).fatalError)
    }

    @Test fun `deeply nested input fails gracefully`() {
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        assertFalse(BackupJson.decode(deep).isValid)
    }

    @Test fun `empty backup is valid`() {
        val r = BackupJson.decode(BackupJson.encodeToString(TestData.snapshot(emptyList())))
        assertTrue(r.isValid)
        assertEquals(0, r.snapshot!!.records.size)
    }
}
