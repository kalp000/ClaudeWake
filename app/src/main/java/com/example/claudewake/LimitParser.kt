package com.example.claudewake

import java.util.Calendar

/**
 * Finds the reset time in text such as:
 *   "Your limit will reset at 3:00 PM"   "resets 3pm"   "resets at 15:30"
 *   "resets in 2 hours 15 minutes"       "reset in 45 minutes"
 * Returns epoch millis, or null if no limit message is present.
 */
object LimitParser {
    private val relHM = Regex("reset[^\\d]{0,25}in\\s+(\\d+)\\s*(?:hours?|hrs?|h)\\b(?:\\s*(?:and)?\\s*(\\d+)\\s*(?:minutes?|mins?|m)\\b)?", RegexOption.IGNORE_CASE)
    private val relM = Regex("reset[^\\d]{0,25}in\\s+(\\d+)\\s*(?:minutes?|mins?|m)\\b", RegexOption.IGNORE_CASE)
    private val abs = Regex("reset[^\\d]{0,25}(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE)

    fun parse(text: String, nowMs: Long = System.currentTimeMillis()): Long? {
        if (!text.contains("limit", true) && !text.contains("reset", true)) return null

        relHM.find(text)?.let {
            val h = it.groupValues[1].toLong()
            val m = it.groupValues[2].toLongOrNull() ?: 0L
            return nowMs + (h * 60 + m) * 60_000
        }
        relM.find(text)?.let { return nowMs + it.groupValues[1].toLong() * 60_000 }

        abs.find(text)?.let {
            var h = it.groupValues[1].toInt()
            val min = it.groupValues[2].ifEmpty { "0" }.toInt()
            val ampm = it.groupValues[3].lowercase()
            if (ampm == "pm" && h < 12) h += 12
            if (ampm == "am" && h == 12) h = 0
            if (h > 23 || min > 59) return null
            val cal = Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= nowMs) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }
        return null
    }
}
