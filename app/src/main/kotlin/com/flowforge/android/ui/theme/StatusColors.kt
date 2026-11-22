package com.flowforge.android.ui.theme

import androidx.compose.ui.graphics.Color

/** Maps the app's status strings to a fixed OpenBullet-style color, everywhere a status is shown. */
object StatusColors {
    fun forStatus(status: String): Color = when (status.uppercase()) {
        "SUCCESS", "COMPLETED", "VALID", "MATCH" -> FFColors.success
        "FAILED", "FAIL", "INVALID", "ERROR" -> FFColors.fail
        "BAN", "BLOCKED" -> FFColors.ban
        "RETRY" -> FFColors.retry
        "CANCELLED", "STOPPED" -> FFColors.warn
        "SKIPPED", "PENDING", "RUNNING" -> FFColors.skip
        else -> FFColors.neutral
    }

    fun forHttp(code: Int): Color = when (code / 100) {
        2 -> FFColors.success
        3 -> FFColors.blue
        4 -> FFColors.warn
        5 -> FFColors.fail
        else -> FFColors.neutral
    }
}
