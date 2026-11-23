package app.apexview

import android.os.PowerManager

/** Visible-tile quality policy applies ONLY to multiple-decoder playback.
 * Single-decoder atlas source quality is never modified by this budget. */
internal object VideoThermalBudget {
    fun multipleDecoderScale(status: Int): Float = when {
        status >= PowerManager.THERMAL_STATUS_SEVERE -> 0.5f
        status >= PowerManager.THERMAL_STATUS_MODERATE -> 0.75f
        else -> 1f
    }
    fun mainVideoOnly(status: Int): Boolean =
        status >= PowerManager.THERMAL_STATUS_CRITICAL

    fun transitionNotice(status: Int, previous: Int): String? = when {
        status >= PowerManager.THERMAL_STATUS_CRITICAL &&
            previous < PowerManager.THERMAL_STATUS_CRITICAL ->
            "Device temperature is critical. In multiple-decoder mode only the main video remains visible; audio and timing continue."
        status >= PowerManager.THERMAL_STATUS_SEVERE &&
            previous < PowerManager.THERMAL_STATUS_SEVERE ->
            "Device temperature is severe. Multiple-decoder feeds may use smaller renditions within the requested quality caps. Single-decoder atlas quality is unchanged."
        status >= PowerManager.THERMAL_STATUS_MODERATE &&
            previous < PowerManager.THERMAL_STATUS_MODERATE ->
            "Device is warming up. Multiple-decoder video may use smaller renditions within your quality caps; single-decoder atlas quality is unchanged."
        status < PowerManager.THERMAL_STATUS_MODERATE &&
            previous >= PowerManager.THERMAL_STATUS_MODERATE ->
            "Device has cooled. Multiple-decoder video can return to its normal visible-tile quality budget."
        else -> null
    }
    fun startupNotice(status: Int): String? =
        if (status >= PowerManager.THERMAL_STATUS_MODERATE)
            transitionNotice(status, PowerManager.THERMAL_STATUS_NONE)
        else null
}
