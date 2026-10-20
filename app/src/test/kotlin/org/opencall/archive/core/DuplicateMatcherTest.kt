package org.opencall.archive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencall.archive.TestData.BASE
import org.opencall.archive.TestData.call

class DuplicateMatcherTest {

    @Test fun `normalize keeps leading plus and service characters only`() {
        assertEquals("+919876543210", PhoneNumbers.normalize(" +91 98765-43210 "))
        assertEquals("02012345678", PhoneNumbers.normalize("(020) 1234 5678"))
        assertEquals("*121#", PhoneNumbers.normalize("*121#"))
        assertEquals("15550100", PhoneNumbers.normalize("1+555-0100"))
    }

    @Test fun `original number is preserved separately from normalized form`() {
        val r = call(number = "+1 (555) 010-0")
        assertEquals("+1 (555) 010-0", r.number)
        assertEquals("+15550100", r.normalizedNumber)
    }

    @Test fun `number match levels`() {
        assertEquals(NumberMatch.EXACT, PhoneNumbers.match("+91 98765 43210", "+919876543210"))
        assertEquals(NumberMatch.LOOSE, PhoneNumbers.match("+919876543210", "09876543210"))
        assertEquals(NumberMatch.LOOSE, PhoneNumbers.match("+15550100", "15550100"))
        assertEquals(NumberMatch.NONE, PhoneNumbers.match("+919876543210", "+919876543211"))
        // Short numbers never match by suffix.
        assertEquals(NumberMatch.NONE, PhoneNumbers.match("12345", "9912345"))
    }

    @Test fun `identical call is a confirmed duplicate`() {
        val a = call()
        assertEquals(MatchLevel.CONFIRMED, DuplicateMatcher.compare(a, a.copy(cachedName = "Arun")).level)
    }

    @Test fun `phone number alone is not a duplicate`() {
        val a = call()
        assertEquals(MatchLevel.NONE, DuplicateMatcher.compare(a, a.copy(date = BASE + 60_000)).level)
        assertEquals(MatchLevel.NONE, DuplicateMatcher.compare(a, a.copy(type = CallTypes.OUTGOING)).level)
        assertEquals(MatchLevel.NONE, DuplicateMatcher.compare(a, a.copy(duration = 120)).level)
    }

    @Test fun `near matches are probable with reasons`() {
        val a = call()
        val truncated = DuplicateMatcher.compare(a, a.copy(date = BASE + 400))
        assertEquals(MatchLevel.PROBABLE, truncated.level)
        assertTrue(truncated.reasons.single().contains("timestamps"))

        assertEquals(MatchLevel.PROBABLE, DuplicateMatcher.compare(a, a.copy(duration = 61)).level)
        assertEquals(MatchLevel.PROBABLE, DuplicateMatcher.compare(a, a.copy(number = "15550100")).level)
        val sim = DuplicateMatcher.compare(a.copy(phoneAccountId = "1"), a.copy(phoneAccountId = "2"))
        assertEquals(MatchLevel.PROBABLE, sim.level)
    }

    @Test fun `missing phone account on one side does not block a confirmed match`() {
        val a = call(account = "sim1")
        assertEquals(MatchLevel.CONFIRMED, DuplicateMatcher.compare(a, a.copy(phoneAccountId = null)).level)
    }

    @Test fun `private numbers match only with same presentation`() {
        val a = call(number = "").copy(presentation = Presentation.RESTRICTED)
        assertEquals(MatchLevel.CONFIRMED, DuplicateMatcher.compare(a, a).level)
        assertEquals(MatchLevel.NONE, DuplicateMatcher.compare(a, a.copy(presentation = Presentation.UNKNOWN)).level)
        assertEquals(MatchLevel.NONE, DuplicateMatcher.compare(a, a.copy(number = "+15550100")).level)
    }

    @Test fun `index finds matches across second boundaries and prefers confirmed`() {
        val target = call(date = BASE + 999)
        val index = RecordIndex(listOf(target.copy(date = BASE + 1_500), target))
        val m = index.bestMatch(target)
        assertNotNull(m)
        assertEquals(MatchLevel.CONFIRMED, m!!.second.level)
        assertEquals(1, m.first.index)
        assertNull(RecordIndex(listOf(target.copy(date = BASE + 5_000))).bestMatch(target))
    }

    @Test fun `fingerprint does not depend on row id or contact name`() {
        val a = call(name = "Arun")
        val b = a.copy(cachedName = "Someone else", extras = mapOf("_id_like" to FieldValue.Integer(5)))
        assertEquals(a.fingerprint, b.fingerprint)
    }
}
