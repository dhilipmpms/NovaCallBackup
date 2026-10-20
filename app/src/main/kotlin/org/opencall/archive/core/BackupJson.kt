package org.opencall.archive.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.Writer
import java.security.MessageDigest

enum class BackupOrigin(val wire: String) {
    MANUAL("manual"), AUTOMATIC("automatic"), IMPORTED("imported");

    companion object {
        fun fromWire(value: String?): BackupOrigin = entries.firstOrNull { it.wire == value } ?: IMPORTED
    }
}

data class BackupSnapshot(
    val id: String,
    val label: String,
    val createdAt: Long,
    val origin: BackupOrigin,
    val sourceSdk: Int?,
    val appVersion: String?,
    val records: List<CallRecord>,
)

data class InvalidEntry(val index: Int, val reason: String)

/** Outcome of parsing and validating a backup file. Nothing is saved or restored here. */
data class DecodeResult(
    val snapshot: BackupSnapshot?,
    val fatalError: String?,
    val formatVersion: Int?,
    val totalEntries: Int,
    val invalidEntries: List<InvalidEntry>,
    val duplicateEntries: Int,
    val warnings: List<String>,
) {
    val isValid: Boolean get() = snapshot != null && fatalError == null
}

/**
 * Versioned JSON backup format (the app's full-fidelity format).
 *
 * Version 1 layout:
 * ```
 * {
 *   "format": "org.opencall.archive.backup",
 *   "formatVersion": 1,
 *   "backupId": "...", "label": "...", "createdAt": <epoch ms>, "origin": "manual",
 *   "source": { "app": "OpenCall Archive", "appVersion": "1.0.0", "androidSdk": 35 },
 *   "recordCount": 2,
 *   "contentSha256": "<hex>",
 *   "records": [ { "number": "+15550100", "date": <epoch ms>, "duration": <s>, "type": 1,
 *                  "presentation": 1, "cachedName": "...", "phoneAccountComponent": "...",
 *                  "phoneAccountId": "...", "extra": { "<provider column>": <value> } } ]
 * }
 * ```
 */
object BackupJson {
    const val FORMAT = "org.opencall.archive.backup"
    const val CURRENT_VERSION = 1
    const val MAX_FILE_BYTES = 32L * 1024 * 1024
    const val MAX_RECORDS = 200_000
    const val MAX_STRING_LENGTH = 2_048
    const val MAX_NUMBER_LENGTH = 256
    const val MAX_EXTRAS = 100
    private val COLUMN_NAME = Regex("^[a-z_][a-z0-9_]{0,63}$")

    private val json = Json { prettyPrint = false }

    // ---- Encoding ---------------------------------------------------------------

    fun encode(snapshot: BackupSnapshot, writer: Writer) {
        val records = snapshot.records.canonicalOrder()
        val header = buildJsonObject {
            put("format", FORMAT)
            put("formatVersion", CURRENT_VERSION)
            put("backupId", snapshot.id)
            put("label", snapshot.label)
            put("createdAt", snapshot.createdAt)
            put("origin", snapshot.origin.wire)
            put("source", buildJsonObject {
                put("app", "OpenCall Archive")
                snapshot.appVersion?.let { put("appVersion", it) }
                snapshot.sourceSdk?.let { put("androidSdk", it) }
            })
            put("recordCount", records.size)
            put("contentSha256", contentHash(records))
        }
        // Stream the records so large backups never exist as one big string.
        val headerText = header.toString()
        writer.write(headerText, 0, headerText.length - 1)
        writer.write(",\"records\":[")
        records.forEachIndexed { i, r ->
            if (i > 0) writer.write(",")
            writer.write("\n")
            writer.write(recordToJson(r).toString())
        }
        writer.write("\n]}\n")
        writer.flush()
    }

    fun encodeToString(snapshot: BackupSnapshot): String =
        java.io.StringWriter().also { encode(snapshot, it) }.toString()

    fun recordToJson(r: CallRecord): JsonObject = buildJsonObject {
        put("number", r.number)
        put("date", r.date)
        put("duration", r.duration)
        put("type", r.type)
        r.presentation?.let { put("presentation", it) }
        r.cachedName?.let { put("cachedName", it) }
        r.phoneAccountComponent?.let { put("phoneAccountComponent", it) }
        r.phoneAccountId?.let { put("phoneAccountId", it) }
        if (r.extras.isNotEmpty()) {
            put("extra", JsonObject(r.extras.toSortedMap().mapValues { (_, v) -> v.toJson() }))
        }
    }

    private fun FieldValue.toJson(): JsonPrimitive = when (this) {
        is FieldValue.Text -> JsonPrimitive(value)
        is FieldValue.Integer -> JsonPrimitive(value)
        is FieldValue.Real -> JsonPrimitive(value)
    }

    /** SHA-256 over the canonical encoding of [records]; independent of input order. */
    fun contentHash(records: List<CallRecord>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (r in records.canonicalOrder()) {
            digest.update(recordToJson(r).toString().toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- Decoding & validation ----------------------------------------------------

    fun decode(text: String): DecodeResult {
        fun fatal(msg: String, version: Int? = null) =
            DecodeResult(null, msg, version, 0, emptyList(), 0, emptyList())

        if (text.length > MAX_FILE_BYTES) return fatal("File is larger than the supported maximum.")
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return fatal("File is not valid JSON.")
        } catch (e: StackOverflowError) {
            return fatal("File is nested too deeply to be a backup.")
        }
        if (root !is JsonObject) return fatal("File is not an OpenCall Archive backup (expected a JSON object).")
        if (root.string("format") != FORMAT) return fatal("File is not an OpenCall Archive backup (unknown format).")
        val version = root.long("formatVersion")?.toInt()
            ?: return fatal("Backup is missing its format version.")
        if (version < 1) return fatal("Backup has an invalid format version ($version).", version)
        if (version > CURRENT_VERSION) {
            return fatal(
                "Backup format version $version was created by a newer version of the app. " +
                    "This version supports up to $CURRENT_VERSION.", version,
            )
        }
        val recordsElement = root["records"] as? JsonArray
            ?: return fatal("Backup has no records list.", version)
        if (recordsElement.size > MAX_RECORDS) {
            return fatal("Backup contains ${recordsElement.size} entries; the maximum supported is $MAX_RECORDS.", version)
        }

        val warnings = mutableListOf<String>()
        val invalid = mutableListOf<InvalidEntry>()
        val records = ArrayList<CallRecord>(recordsElement.size)
        recordsElement.forEachIndexed { index, element ->
            when (val parsed = parseRecord(element)) {
                is ParsedRecord.Ok -> records += parsed.record
                is ParsedRecord.Bad -> invalid += InvalidEntry(index, parsed.reason)
            }
        }

        val declaredCount = root.long("recordCount")
        if (declaredCount != null && declaredCount != recordsElement.size.toLong()) {
            warnings += "Header says $declaredCount records but the file contains ${recordsElement.size}."
        }
        val declaredHash = root.string("contentSha256")
        if (declaredHash == null) {
            warnings += "Backup has no checksum, so corruption cannot be detected."
        } else if (invalid.isNotEmpty()) {
            warnings += "Checksum cannot be verified because some entries are malformed; the file was modified or corrupted."
        } else if (declaredHash != contentHash(records)) {
            warnings += "Checksum does not match: the file was modified or corrupted after it was created."
        }
        val duplicates = records.size - records.mapTo(HashSet()) { it.fingerprint }.size
        if (duplicates > 0) warnings += "$duplicates entries appear more than once in this file."
        if (invalid.isNotEmpty()) warnings += "${invalid.size} entries are malformed and were not read."

        val source = root["source"] as? JsonObject
        val snapshot = BackupSnapshot(
            id = root.string("backupId")?.takeIf { it.isNotBlank() && it.length <= 64 } ?: java.util.UUID.randomUUID().toString(),
            label = root.string("label")?.take(200) ?: "Imported backup",
            createdAt = root.long("createdAt") ?: 0L,
            origin = BackupOrigin.fromWire(root.string("origin")),
            sourceSdk = source?.long("androidSdk")?.toInt(),
            appVersion = source?.string("appVersion")?.take(64),
            records = records,
        )
        return DecodeResult(snapshot, null, version, recordsElement.size, invalid, duplicates, warnings)
    }

    private sealed class ParsedRecord {
        data class Ok(val record: CallRecord) : ParsedRecord()
        data class Bad(val reason: String) : ParsedRecord()
    }

    private fun parseRecord(element: JsonElement): ParsedRecord {
        if (element !is JsonObject) return ParsedRecord.Bad("entry is not an object")
        val numberEl = element["number"]
        val number = when {
            numberEl == null || numberEl is JsonNull -> ""
            numberEl is JsonPrimitive && numberEl.isString -> numberEl.content
            else -> return ParsedRecord.Bad("number is not text")
        }
        if (number.length > MAX_NUMBER_LENGTH) return ParsedRecord.Bad("number is too long")
        val date = element.strictLong("date") ?: return ParsedRecord.Bad("missing or invalid date")
        val duration = element.strictLong("duration") ?: return ParsedRecord.Bad("missing or invalid duration")
        val type = element.strictLong("type") ?: return ParsedRecord.Bad("missing or invalid call type")
        if (type !in Int.MIN_VALUE..Int.MAX_VALUE) return ParsedRecord.Bad("call type out of range")

        val presentation = element.optionalLong("presentation") { return ParsedRecord.Bad("presentation is not a number") }
        val name = element.optionalString("cachedName") { return ParsedRecord.Bad("cachedName is not text") }
        val component = element.optionalString("phoneAccountComponent") { return ParsedRecord.Bad("phoneAccountComponent is not text") }
        val accountId = element.optionalString("phoneAccountId") { return ParsedRecord.Bad("phoneAccountId is not text") }

        val extras = LinkedHashMap<String, FieldValue>()
        when (val extraEl = element["extra"]) {
            null, is JsonNull -> Unit
            is JsonObject -> {
                if (extraEl.size > MAX_EXTRAS) return ParsedRecord.Bad("too many extra fields")
                for ((key, value) in extraEl) {
                    if (!COLUMN_NAME.matches(key)) return ParsedRecord.Bad("invalid field name")
                    if (key in Columns.CORE || key == Columns.ID) continue
                    if (value is JsonNull) continue
                    if (value !is JsonPrimitive) return ParsedRecord.Bad("extra field '$key' is not a simple value")
                    extras[key] = when {
                        value.isString -> {
                            if (value.content.length > MAX_STRING_LENGTH) return ParsedRecord.Bad("extra field '$key' is too long")
                            FieldValue.Text(value.content)
                        }
                        value.longOrNull != null -> FieldValue.Integer(value.longOrNull!!)
                        value.doubleOrNull != null -> FieldValue.Real(value.doubleOrNull!!)
                        else -> return ParsedRecord.Bad("extra field '$key' has an unsupported value")
                    }
                }
            }
            else -> return ParsedRecord.Bad("extra is not an object")
        }
        if (listOfNotNull(name, component, accountId).any { it.length > MAX_STRING_LENGTH }) {
            return ParsedRecord.Bad("text field is too long")
        }
        return ParsedRecord.Ok(
            CallRecord(
                number = number,
                date = date,
                duration = duration,
                type = type.toInt(),
                presentation = presentation?.toInt(),
                cachedName = name?.takeIf { it.isNotEmpty() },
                phoneAccountComponent = component?.takeIf { it.isNotEmpty() },
                phoneAccountId = accountId?.takeIf { it.isNotEmpty() },
                extras = extras,
            ),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun JsonObject.strictLong(key: String): Long? = long(key)

    private inline fun JsonObject.optionalLong(key: String, onError: () -> Nothing): Long? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        return (el as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: onError()
    }

    private inline fun JsonObject.optionalString(key: String, onError: () -> Nothing): String? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        return (el as? JsonPrimitive)?.takeIf { it.isString }?.content ?: onError()
    }
}
