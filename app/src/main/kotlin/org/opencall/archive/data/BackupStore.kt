package org.opencall.archive.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.opencall.archive.core.BackupJson
import org.opencall.archive.core.BackupOrigin
import org.opencall.archive.core.BackupSnapshot
import java.io.File
import java.io.IOException

data class BackupMeta(
    val id: String,
    val label: String,
    val createdAt: Long,
    val savedAt: Long,
    val origin: BackupOrigin,
    val recordCount: Int,
    val contentHash: String,
    val sourceSdk: Int?,
    val importedFrom: String?,
    val sizeBytes: Long,
)

class StorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Snapshot storage in app-private internal storage (`files/backups`). Each snapshot is a
 * JSON backup file plus a small sidecar with metadata for fast listing.
 *
 * Files are written to a temporary name, re-read and validated, and only then renamed into
 * place, so a failed or interrupted write never replaces or corrupts an existing backup.
 * Snapshots are never modified after saving and are only deleted on explicit user request.
 */
class BackupStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = object : LinkedHashMap<String, BackupSnapshot>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BackupSnapshot>?) = size > 6
    }

    private fun dataFile(id: String) = File(dir, "$id.json")
    private fun metaFile(id: String) = File(dir, "$id.meta")

    @Synchronized
    fun list(): List<BackupMeta> {
        val files = dir.listFiles { f -> f.name.endsWith(".meta") } ?: return emptyList()
        return files.mapNotNull { readMeta(it) }
            .filter { dataFile(it.id).exists() }
            .sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun meta(id: String): BackupMeta? = readMeta(metaFile(id))

    @Synchronized
    fun findByContentHash(hash: String): BackupMeta? = list().firstOrNull { it.contentHash == hash }

    @Synchronized
    fun load(id: String): BackupSnapshot {
        cache[id]?.let { return it }
        val file = dataFile(id)
        if (!file.exists()) throw StorageException("Backup file is missing.")
        val text = try { file.readText(Charsets.UTF_8) } catch (e: IOException) {
            throw StorageException("Could not read the backup file.", e)
        }
        val result = BackupJson.decode(text)
        val snapshot = result.snapshot ?: throw StorageException("Stored backup is damaged: ${result.fatalError}")
        cache[id] = snapshot
        return snapshot
    }

    @Synchronized
    fun save(snapshot: BackupSnapshot, importedFrom: String? = null): BackupMeta {
        if (!dir.exists() && !dir.mkdirs()) throw StorageException("Could not create the backup folder.")
        var id = snapshot.id
        if (dataFile(id).exists()) id = java.util.UUID.randomUUID().toString()
        val toSave = snapshot.copy(id = id)
        val tmp = File(dir, "$id.json.tmp")
        try {
            tmp.bufferedWriter(Charsets.UTF_8).use { BackupJson.encode(toSave, it) }
            // Validate what actually landed on disk before accepting it.
            val check = BackupJson.decode(tmp.readText(Charsets.UTF_8))
            val expectedHash = BackupJson.contentHash(toSave.records)
            val actualHash = check.snapshot?.records?.let { BackupJson.contentHash(it) }
            if (!check.isValid || check.snapshot!!.records.size != toSave.records.size || actualHash != expectedHash) {
                throw StorageException("Backup verification failed after writing; nothing was saved.")
            }
            val meta = BackupMeta(
                id = id,
                label = toSave.label,
                createdAt = toSave.createdAt,
                savedAt = System.currentTimeMillis(),
                origin = toSave.origin,
                recordCount = toSave.records.size,
                contentHash = expectedHash,
                sourceSdk = toSave.sourceSdk,
                importedFrom = importedFrom,
                sizeBytes = tmp.length(),
            )
            if (!tmp.renameTo(dataFile(id))) throw StorageException("Could not finalize the backup file.")
            writeMeta(meta)
            cache[id] = toSave
            return meta
        } catch (e: StorageException) {
            tmp.delete()
            throw e
        } catch (e: IOException) {
            tmp.delete()
            throw StorageException(
                if (dir.usableSpace < 1_000_000) "Not enough storage space to save the backup."
                else "Could not write the backup: ${e.javaClass.simpleName}", e,
            )
        }
    }

    @Synchronized
    fun delete(id: String): Boolean {
        cache.remove(id)
        val okData = !dataFile(id).exists() || dataFile(id).delete()
        val okMeta = !metaFile(id).exists() || metaFile(id).delete()
        return okData && okMeta
    }

    /** Raw file for export (copied byte-for-byte so the exported file equals the stored one). */
    fun file(id: String): File = dataFile(id)

    private fun writeMeta(m: BackupMeta) {
        val obj = buildJsonObject {
            put("id", m.id); put("label", m.label); put("createdAt", m.createdAt); put("savedAt", m.savedAt)
            put("origin", m.origin.wire); put("recordCount", m.recordCount); put("contentHash", m.contentHash)
            m.sourceSdk?.let { put("sourceSdk", it) }
            m.importedFrom?.let { put("importedFrom", it) }
            put("sizeBytes", m.sizeBytes)
        }
        val tmp = File(dir, "${m.id}.meta.tmp")
        tmp.writeText(obj.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(metaFile(m.id))) throw StorageException("Could not save backup details.")
    }

    private fun readMeta(f: File): BackupMeta? = try {
        val o = json.parseToJsonElement(f.readText(Charsets.UTF_8)).jsonObject
        val id = o.s("id")
        if (id == null) null else BackupMeta(
            id = id,
            label = o.s("label").orEmpty(),
            createdAt = o.l("createdAt") ?: 0L,
            savedAt = o.l("savedAt") ?: 0L,
            origin = BackupOrigin.fromWire(o.s("origin")),
            recordCount = o.i("recordCount") ?: 0,
            contentHash = o.s("contentHash").orEmpty(),
            sourceSdk = o.i("sourceSdk"),
            importedFrom = o.s("importedFrom"),
            sizeBytes = o.l("sizeBytes") ?: 0L,
        )
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
}
