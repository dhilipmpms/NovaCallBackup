package org.opencall.archive.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import com.nova.callbackup.BuildConfig
import org.opencall.archive.core.BackupJson
import org.opencall.archive.core.BackupOrigin
import org.opencall.archive.core.BackupSnapshot
import org.opencall.archive.core.CallRecord
import org.opencall.archive.core.CsvExporter
import org.opencall.archive.core.DecodeResult
import org.opencall.archive.core.ReadStats
import org.opencall.archive.core.canonicalOrder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.UUID

sealed class BackupResult {
    data class Saved(val meta: BackupMeta, val stats: ReadStats) : BackupResult()
    data class Unchanged(val existing: BackupMeta) : BackupResult()
    data object EmptyCallLog : BackupResult()
    data object PermissionMissing : BackupResult()
    data object ProviderUnavailable : BackupResult()
    data class Failed(val message: String) : BackupResult()
}

data class OpenedDocument(val displayName: String?, val result: DecodeResult)

sealed class ImportOutcome {
    data class Saved(val meta: BackupMeta) : ImportOutcome()
    data class AlreadyArchived(val existing: BackupMeta) : ImportOutcome()
    data class Failed(val message: String) : ImportOutcome()
}

data class CallLogDiagnostics(
    val stats: ReadStats,
    /** Rows remaining after merging exact duplicates (same number, time, duration, type, account). */
    val distinctCalls: Int,
    val accounts: List<AccountRowCount>,
    val oldestDate: Long?,
    val latestBackupRecords: Int?,
    val latestBackupProviderRows: Int?,
    val latestBackupAt: Long?,
)

class ArchiveRepository(private val context: Context, val store: BackupStore) {
    private val resolver = context.contentResolver
    val gateway = AndroidCallLogGateway(resolver)

    private val _backups = MutableStateFlow<List<BackupMeta>>(emptyList())
    val backups: StateFlow<List<BackupMeta>> = _backups.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) { _backups.value = store.list() }

    suspend fun loadSnapshot(id: String): BackupSnapshot = withContext(Dispatchers.IO) { store.load(id) }

    suspend fun loadAllSnapshots(): List<BackupSnapshot> = withContext(Dispatchers.IO) {
        store.list().mapNotNull { runCatching { store.load(it.id) }.getOrNull() }
    }

    /** Number of entries in the live call log, or null when it cannot be read. */
    suspend fun liveCount(): Int? = withContext(Dispatchers.IO) {
        if (!CallLogAccess.hasRead(context) || !CallLogAccess.providerAvailable(context)) return@withContext null
        try { gateway.count() } catch (e: Exception) { null }
    }

    suspend fun liveRecords(): List<CallRecord> = withContext(Dispatchers.IO) { gateway.readAll() }

    /** Read-only, counts-only diagnostics of the live provider and the latest backup. */
    suspend fun diagnostics(): CallLogDiagnostics = withContext(Dispatchers.IO) {
        val read = gateway.readAllWithStats()
        val accounts = gateway.countByPhoneAccount()
        val latest = store.list().firstOrNull()
        CallLogDiagnostics(
            stats = read.stats,
            distinctCalls = read.records.mapTo(HashSet()) { it.fingerprint }.size,
            accounts = accounts,
            oldestDate = read.records.minOfOrNull { it.date },
            latestBackupRecords = latest?.recordCount,
            latestBackupProviderRows = latest?.providerRowCount,
            latestBackupAt = latest?.createdAt,
        )
    }

    suspend fun createBackup(origin: BackupOrigin, skipIfUnchanged: Boolean = false): BackupResult =
        withContext(Dispatchers.IO) {
            if (!CallLogAccess.providerAvailable(context)) return@withContext BackupResult.ProviderUnavailable
            if (!CallLogAccess.hasRead(context)) return@withContext BackupResult.PermissionMissing
            val read = try {
                gateway.readAllWithStats()
            } catch (e: SecurityException) {
                return@withContext BackupResult.PermissionMissing
            } catch (e: Exception) {
                return@withContext BackupResult.Failed("Could not read the call log: ${e.javaClass.simpleName}")
            }
            val records = read.records
            if (records.isEmpty() && read.stats.cursorCount == 0) return@withContext BackupResult.EmptyCallLog
            if (records.isEmpty()) {
                return@withContext BackupResult.Failed(
                    "The call log returned ${read.stats.cursorCount} rows but none could be read: ${read.stats.skipped}",
                )
            }
            if (skipIfUnchanged) {
                val hash = BackupJson.contentHash(records)
                store.findByContentHash(hash)?.let { return@withContext BackupResult.Unchanged(it) }
            }
            val now = System.currentTimeMillis()
            val prefix = if (origin == BackupOrigin.AUTOMATIC) "Automatic backup" else "Backup"
            val snapshot = BackupSnapshot(
                id = UUID.randomUUID().toString(),
                label = "$prefix ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(now))}",
                createdAt = now,
                origin = origin,
                sourceSdk = Build.VERSION.SDK_INT,
                appVersion = BuildConfig.VERSION_NAME,
                records = records.canonicalOrder(),
            )
            try {
                val meta = store.save(snapshot, readStats = read.stats)
                _backups.value = store.list()
                BackupResult.Saved(meta, read.stats)
            } catch (e: IOException) {
                BackupResult.Failed(e.message ?: "Could not save the backup.")
            }
        }

    /** Reads and validates a user-selected document. Never saves or restores anything. */
    suspend fun openDocument(uri: Uri): OpenedDocument = withContext(Dispatchers.IO) {
        var name: String? = null
        var size: Long? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (e: Exception) {
            // Metadata is optional.
        }
        fun fatal(msg: String) = OpenedDocument(name, DecodeResult(null, msg, null, 0, emptyList(), 0, emptyList()))
        if ((size ?: 0L) > BackupJson.MAX_FILE_BYTES) return@withContext fatal("File is too large to be a supported backup.")
        val bytes = try {
            resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > BackupJson.MAX_FILE_BYTES) return@withContext fatal("File is too large to be a supported backup.")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: return@withContext fatal("The selected file could not be opened.")
        } catch (e: SecurityException) {
            return@withContext fatal("Access to the selected file was denied.")
        } catch (e: IOException) {
            return@withContext fatal("The selected file could not be read.")
        }
        OpenedDocument(name, BackupJson.decode(String(bytes, Charsets.UTF_8)))
    }

    /** Saves a validated document as an archived snapshot. Does not restore it. */
    suspend fun importDocument(doc: OpenedDocument): ImportOutcome = withContext(Dispatchers.IO) {
        val snapshot = doc.result.snapshot ?: return@withContext ImportOutcome.Failed(doc.result.fatalError ?: "Invalid backup.")
        val hash = BackupJson.contentHash(snapshot.records)
        store.findByContentHash(hash)?.let { return@withContext ImportOutcome.AlreadyArchived(it) }
        try {
            val meta = store.save(snapshot, importedFrom = doc.displayName ?: "file")
            _backups.value = store.list()
            ImportOutcome.Saved(meta)
        } catch (e: IOException) {
            ImportOutcome.Failed(e.message ?: "Could not save the imported backup.")
        }
    }

    /** Exports the stored JSON byte-for-byte to a user-chosen document. */
    suspend fun exportJson(id: String, target: Uri) = withContext(Dispatchers.IO) {
        val source = store.file(id)
        if (!source.exists()) throw StorageException("Backup file is missing.")
        val out = resolver.openOutputStream(target, "wt") ?: throw StorageException("Could not open the destination.")
        out.use { o -> source.inputStream().use { it.copyTo(o) } }
    }

    suspend fun exportCsv(records: List<CallRecord>, target: Uri) = withContext(Dispatchers.IO) {
        val out = resolver.openOutputStream(target, "wt") ?: throw StorageException("Could not open the destination.")
        out.bufferedWriter(Charsets.UTF_8).use { CsvExporter.write(records, it) }
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val ok = store.delete(id)
        _backups.value = store.list()
        ok
    }
}
