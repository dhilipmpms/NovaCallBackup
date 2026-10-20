package org.opencall.archive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.opencall.archive.TestData
import org.opencall.archive.core.BackupJson
import org.opencall.archive.core.canonicalOrder

class BackupStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `save, list, load and delete snapshots`() {
        val store = BackupStore(tmp.root.resolve("backups"))
        val a = TestData.snapshot(TestData.calls(30), id = "a", label = "A")
        val b = TestData.snapshot(TestData.calls(5), id = "b", label = "B").copy(createdAt = a.createdAt + 1)
        val metaA = store.save(a)
        store.save(b, importedFrom = "export.json")
        assertEquals(listOf("b", "a"), store.list().map { it.id })
        assertEquals(30, metaA.recordCount)
        assertEquals(BackupJson.contentHash(a.records), metaA.contentHash)
        assertEquals("export.json", store.meta("b")!!.importedFrom)

        // A fresh instance (no cache) reads the same records back from disk.
        val reopened = BackupStore(tmp.root.resolve("backups"))
        assertEquals(a.records.canonicalOrder(), reopened.load("a").records)
        assertEquals(metaA.contentHash, reopened.findByContentHash(metaA.contentHash)!!.contentHash)

        assertTrue(reopened.delete("a"))
        assertEquals(listOf("b"), reopened.list().map { it.id })
        assertNull(reopened.meta("a"))
    }

    @Test fun `saving never overwrites an existing snapshot`() {
        val store = BackupStore(tmp.root)
        val first = store.save(TestData.snapshot(TestData.calls(3), id = "same"))
        val second = store.save(TestData.snapshot(TestData.calls(4), id = "same"))
        assertNotEquals(first.id, second.id)
        assertEquals(3, store.load("same").records.size)
        assertEquals(2, store.list().size)
    }

    @Test fun `storage failure is reported and leaves nothing behind`() {
        val blocker = tmp.newFile("not-a-dir")
        val store = BackupStore(blocker.resolve("backups"))
        try {
            store.save(TestData.snapshot(TestData.calls(3)))
            fail("expected StorageException")
        } catch (e: StorageException) {
            assertTrue(e.message!!.isNotBlank())
        }
        assertEquals(emptyList<BackupMeta>(), store.list())
    }

    @Test fun `damaged stored file is reported, not silently loaded`() {
        val dir = tmp.root.resolve("b")
        val store = BackupStore(dir)
        store.save(TestData.snapshot(TestData.calls(3), id = "x"))
        dir.resolve("x.json").writeText("{ truncated")
        try {
            BackupStore(dir).load("x")
            fail("expected StorageException")
        } catch (e: StorageException) {
            assertTrue(e.message!!.contains("damaged"))
        }
    }

    @Test fun `stored file is the versioned JSON format`() {
        val dir = tmp.root.resolve("c")
        BackupStore(dir).save(TestData.snapshot(TestData.calls(2), id = "v"))
        val result = BackupJson.decode(dir.resolve("v.json").readText())
        assertEquals(BackupJson.CURRENT_VERSION, result.formatVersion)
        assertEquals(emptyList<String>(), result.warnings)
    }
}
