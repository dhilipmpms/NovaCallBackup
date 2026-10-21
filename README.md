# Nova Call Backup

Free, open-source, offline Android app to **back up, search, import, export, compare and
restore** call history. No account, ads, analytics, tracking or network access — the app
does not request the `INTERNET` permission.

- Kotlin, XML layouts + ViewBinding, native Android APIs only
- minSdk 26 (Android 8.0), targetSdk / compileSdk 36
- Dependencies: AndroidX (core, appcompat, fragment, lifecycle, recyclerview, constraintlayout,
  WorkManager), Material Components, kotlinx-coroutines, kotlinx-serialization-json (tree API only)

## Features

| Area | What it does |
| --- | --- |
| Backup | Reads every column the device's `CallLog.Calls` provider exposes and stores a versioned JSON snapshot in app-private storage. Each write is re-read and checked against a SHA-256 content hash before it is accepted. |
| Restore | Writes eligible entries into the real Android call log with `ContentResolver.insert`, reads each row back to verify it, and re-queries the whole log at the end. See [Restore](#restore). |
| Search | Offline search across all snapshots (or one): name, full/partial number (formatting-insensitive), date range, call type, duration range, combined filters. Results are labelled ARCHIVED. |
| Import / export | JSON export/import and CSV export via the Storage Access Framework (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`); no storage permission. Import validates and archives only — restoring is a separate action. |
| Compare | Read-only: backup vs current call log, or backup vs backup. Shows in both / possible duplicates / only in A / only in B and metadata differences. |
| Automatic backup | Optional WorkManager periodic job (daily / 3 days / weekly, optional "only while charging", "skip if unchanged"). Never deletes older backups; Android decides the exact run time. |

## Restore

### What Android requires (investigated for API 26–36)

- `CallLogProvider` (AOSP ContactsProvider) guards inserts with **`WRITE_CALL_LOG`** only. The
  **default dialer role is not required**, so the app never requests it (that would also need a full
  `InCallService` dialer, which is out of scope). The restore preview and Settings show the role
  status and explain this.
- `READ_CALL_LOG`/`WRITE_CALL_LOG` are **hard-restricted** since Android 10: the installer must
  allow-list them. adb installs and normal sideloads are allow-listed (`RESTRICTION_INSTALLER_EXEMPT`).
  If Android refuses without a dialog, the app explains how to allow it from App info
  (including "Allow restricted settings").
- Android 15's Enhanced Confirmation Mode protects SMS permissions and the dialer/SMS roles for
  sideloaded apps, but not the call-log permissions — another reason not to rely on the dialer role.
- Voicemail rows need the voicemail app's permissions and an `allow_voicemail` URI, so voicemail
  entries are archived but reported as *not eligible* for restore.
- The provider throws `IllegalArgumentException` for unknown columns, so the app writes only columns
  that are (a) on its documented allow-list, (b) introduced at or below the running API level, and
  (c) actually reported by the device's provider.

### Workflow

1. Choose a saved backup or a backup file.
2. The file is validated (format, version, per-entry checks, checksum, size limits).
3. A preview against the current call log shows the totals: eligible, exact duplicates, possible
   duplicates, repeated within backup, restored earlier, invalid, not eligible, permission/role
   status, and per-field handling.
4. Policy: **skip likely duplicates** (default), **skip exact duplicates only**, or **restore
   everything eligible**, plus options for SIM/account info and marking calls as read.
5. Explicit confirmation ("Add N entries").
6. Insert one row at a time. The journal entry is written *before* each insert, the outcome after it.
7. Each row is read back and compared (number, date, duration, type); a final full re-query
   confirms the rows are still present.
8. The report shows: restored (verified), restored with essential fields only, skipped, failed
   insert, failed verification, not eligible / invalid, not attempted.

Retry-safety: fingerprints of everything this app inserted (including inserts whose outcome
was never recorded because the process died) are kept in app-private journals. A retry never
re-inserts those while they are still present, even under "restore everything". Interrupted
sessions are shown on Home with a retry action. Existing call history is never modified or
deleted, and backups are never modified by a restore.

### Fields

| Restored as backed up (when supported by the device) | Not written, and why |
| --- | --- |
| number, date (ms), duration, type, presentation, cached name, cached number type/label, phone account component + id, new, is_read, features, data_usage, post_dial_digits (24+), via_number (24+), block_reason, call_screening_app_name/component (29+), missed_reason, priority, subject (31+), is_business_call, asserted_display_name (35+) | `_id`, `last_modified` (assigned by Android); `countryiso`, `geocoded_location` (computed by the provider on insert); `phone_account_address`, `phone_account_hidden`, migration flags, `add_for_all_users` (system-managed); voicemail URI/transcription (voicemail app only); lookup/photo/normalized/formatted caches (rebuilt from the device's contacts); call-composer attachments (point to files on the original device) |

All of these fields are still kept in the archive. The restore preview lists each one with how
many records carry it and the reason it isn't written.

## Duplicate detection

- The original number is stored verbatim; matching uses a normalized form (digits, leading `+`, `*`, `#`).
- **Confirmed:** identical normalized number, millisecond timestamp, duration, type, and a
  compatible phone account (equal, or missing on one side).
- **Probable:** same type, and within 1 s and 1 s duration, with a differently formatted number
  (country code / trunk prefix), a differing SIM, or a near timestamp. The reasons are shown.
- The phone number alone, a row ID, or a contact name are never used as identity. Nothing is
  ever deleted automatically.

## Android's per-SIM call-log limit

Each time Telecom logs a call, `CallLog.addCall` deletes the oldest rows of **that SIM / phone
account** beyond a per-SIM cap: 500 in AOSP, configurable by the manufacturer via
`config_maximumCallLogEntriesPerSim` (one Motorola device measured at 3,000). Rows with other or no
account IDs, such as calls migrated from an old phone, are not trimmed. As a result the provider's total can stay
constant while new calls arrive, and old calls on the current SIM disappear from Android itself.
Only backups made before that deletion still contain them. Back up regularly (or enable automatic
backup). **Settings → Call log diagnostics** shows read counts and rows per SIM account (counts only).

## Historical search

Search runs over every record of the selected backups, with no pagination or result cap, and the
count shown is the full number of matches. A name query matches a call's own cached name, and also
every call to a number that carried that name on any searched call. With the optional "current
contacts" switch (READ_CONTACTS, requested on first use), it also matches all numbers of matching
contacts. Numbers match across formats (`+91 98765 54321 `, `9876554321`, `09876554321`). Linking
is by number only, so people who merely share a name are never merged. Identical rows inside one
backup are all counted. A call present in several backups is shown once.

## Privacy and security

- Backups live in `files/backups` (app-private, mode 600) and are excluded from cloud backup and
  device transfer (`allowBackup=false`, data extraction rules).
- **Backups are not encrypted.** Exported files are plain JSON/CSV at a location the user picks.
- The app never logs phone numbers, names or backup contents.
- Input limits: 32 MiB per file, 200,000 records, bounded string lengths, flat values only.
- CSV export escapes per RFC 4180 and neutralises spreadsheet formulas (phone numbers like
  `+15550100` are kept intact). CSV cannot be imported or restored.

## Building and testing

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug            # APK in app/build/outputs/apk/debug
./gradlew testDebugUnitTest        # JVM unit tests (no device)
./gradlew connectedDebugAndroidTest  # device tests against the real CallLog provider
```

- **Unit tests** (`app/src/test`) cover JSON serialization and validation, malformed/unsupported
  files, CSV escaping, search (partial number, date range, type, duration, combined), duplicate
  detection, comparison, restore planning and policies, column gating per API level, and the
  restore executor against a fake provider. The fake injects rejected inserts, verification
  mismatches, IllegalArgumentException fallbacks, permission revocation, cancellation, simulated
  process death and trimmed rows. Storage tests cover the backup store, including storage
  failures and damaged files.
- **Instrumented tests** (`app/src/androidTest`) insert synthetic calls with fictional
  `+1555010xxxx` numbers into the real provider, verify fields and read-back, test retry,
  interrupted and partial restores, and remove exactly those rows afterwards. They run only on
  emulators unless `-Pandroid.testInstrumentationRunnerArguments.allowRealDevice=true` is passed.

