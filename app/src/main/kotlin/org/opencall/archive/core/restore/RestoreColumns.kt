package org.opencall.archive.core.restore

import org.opencall.archive.core.Columns

enum class ColumnDisposition {
    RESTORED,
    EXCLUDED_BY_OPTION,
    PROVIDER_COMPUTED,
    CONTACT_CACHE,
    SYSTEM_MANAGED,
    VOICEMAIL_ONLY,
    CALL_COMPOSER_ATTACHMENT,
    NOT_SUPPORTED_ON_THIS_ANDROID_VERSION,
    NOT_PRESENT_ON_THIS_DEVICE,
    UNDOCUMENTED,
}

data class ColumnDecision(val column: String, val disposition: ColumnDisposition, val explanation: String)

/**
 * Which CallLog columns this app writes during restore, and why the rest are not written.
 *
 * Writable columns are the public, documented CallLog.Calls columns an ordinary app may
 * set on insert, each gated on the API level that introduced it. In addition, a column is
 * only written when the device's provider actually reports it, because the provider
 * rejects unknown columns with IllegalArgumentException.
 */
object RestoreColumns {
    /** Column -> minimum SDK level (from the platform's api-versions data). */
    val WRITABLE: Map<String, Int> = linkedMapOf(
        Columns.NUMBER to 1,
        Columns.DATE to 1,
        Columns.DURATION to 1,
        Columns.TYPE to 1,
        Columns.PRESENTATION to 19,
        Columns.CACHED_NAME to 1,
        Columns.CACHED_NUMBER_TYPE to 1,
        Columns.CACHED_NUMBER_LABEL to 1,
        Columns.NEW to 1,
        Columns.IS_READ to 14,
        Columns.PHONE_ACCOUNT_COMPONENT to 21,
        Columns.PHONE_ACCOUNT_ID to 21,
        "features" to 21,
        "data_usage" to 21,
        "post_dial_digits" to 24,
        "via_number" to 24,
        "block_reason" to 29,
        "call_screening_app_name" to 29,
        "call_screening_component_name" to 29,
        "missed_reason" to 31,
        "priority" to 31,
        "subject" to 31,
        "is_business_call" to 35,
        "asserted_display_name" to 35,
    )

    /** Minimal set used when the provider rejects a full insert. */
    val ESSENTIAL = listOf(Columns.NUMBER, Columns.DATE, Columns.DURATION, Columns.TYPE, Columns.PRESENTATION)

    val PHONE_ACCOUNT_COLUMNS = setOf(Columns.PHONE_ACCOUNT_COMPONENT, Columns.PHONE_ACCOUNT_ID)

    private val NOT_WRITTEN: Map<String, Pair<ColumnDisposition, String>> = buildMap {
        put(Columns.ID, ColumnDisposition.SYSTEM_MANAGED to "Row IDs are assigned by Android.")
        put("last_modified", ColumnDisposition.SYSTEM_MANAGED to "Set by Android to the time of insertion.")
        put("countryiso", ColumnDisposition.PROVIDER_COMPUTED to "Android computes the country from the current network when the entry is added.")
        put("geocoded_location", ColumnDisposition.PROVIDER_COMPUTED to "Android recomputes the location label from the number.")
        put("phone_account_address", ColumnDisposition.SYSTEM_MANAGED to "Hidden column managed by the telephony system.")
        put("phone_account_hidden", ColumnDisposition.SYSTEM_MANAGED to "Managed by the telephony system.")
        put("is_phone_account_migration_pending", ColumnDisposition.SYSTEM_MANAGED to "Managed by the telephony system.")
        put("is_call_log_phone_account_migration_pending", ColumnDisposition.SYSTEM_MANAGED to "Managed by the telephony system.")
        put("add_for_all_users", ColumnDisposition.SYSTEM_MANAGED to "Managed by the telephony system.")
        put("sub_id", ColumnDisposition.SYSTEM_MANAGED to "Hidden column managed by the telephony system.")
        put("voicemail_uri", ColumnDisposition.VOICEMAIL_ONLY to "Voicemail data can only be written by the voicemail app.")
        put("transcription", ColumnDisposition.VOICEMAIL_ONLY to "Voicemail data can only be written by the voicemail app.")
        put("transcription_state", ColumnDisposition.VOICEMAIL_ONLY to "Voicemail data can only be written by the voicemail app.")
        for (c in listOf("lookup_uri", "matched_number", "normalized_number", "photo_id", "photo_uri", "formatted_number")) {
            put(c, ColumnDisposition.CONTACT_CACHE to "Contact cache rebuilt by the phone app from this device's contacts.")
        }
        put("composer_photo_uri", ColumnDisposition.CALL_COMPOSER_ATTACHMENT to "Points to a file on the original device.")
        put("location", ColumnDisposition.CALL_COMPOSER_ATTACHMENT to "Call-composer attachment managed by Android.")
        put("latitude", ColumnDisposition.CALL_COMPOSER_ATTACHMENT to "Call-composer attachment managed by Android.")
        put("longitude", ColumnDisposition.CALL_COMPOSER_ATTACHMENT to "Call-composer attachment managed by Android.")
    }

    /**
     * @param providerColumns columns the device's provider reports, or null if unknown
     *        (e.g. permission not granted yet), in which case only the SDK gate applies.
     */
    fun decide(
        column: String,
        sdkInt: Int,
        providerColumns: Set<String>?,
        keepPhoneAccount: Boolean,
    ): ColumnDecision {
        NOT_WRITTEN[column]?.let { (d, why) -> return ColumnDecision(column, d, why) }
        val minSdk = WRITABLE[column]
            ?: return ColumnDecision(column, ColumnDisposition.UNDOCUMENTED, "Not a documented writable call-log column; kept in the archive only.")
        if (sdkInt < minSdk) {
            return ColumnDecision(column, ColumnDisposition.NOT_SUPPORTED_ON_THIS_ANDROID_VERSION, "Requires Android API $minSdk or newer.")
        }
        if (providerColumns != null && column !in providerColumns) {
            return ColumnDecision(column, ColumnDisposition.NOT_PRESENT_ON_THIS_DEVICE, "This device's call log does not have this column.")
        }
        if (!keepPhoneAccount && column in PHONE_ACCOUNT_COLUMNS) {
            return ColumnDecision(column, ColumnDisposition.EXCLUDED_BY_OPTION, "Excluded because you chose not to restore SIM / account information.")
        }
        return ColumnDecision(column, ColumnDisposition.RESTORED, "Restored as backed up.")
    }

    fun writableColumns(sdkInt: Int, providerColumns: Set<String>?, keepPhoneAccount: Boolean): Set<String> =
        WRITABLE.keys.filterTo(LinkedHashSet()) {
            decide(it, sdkInt, providerColumns, keepPhoneAccount).disposition == ColumnDisposition.RESTORED
        }
}
