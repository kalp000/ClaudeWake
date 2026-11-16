package com.example.claudewake

import android.content.Context

object Prefs {
    const val NONE = "none"
    const val PIN = "pin"
    const val PATTERN = "pattern"

    private fun sp(c: Context) = c.getSharedPreferences("claudewake", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    fun resetAt(c: Context) = sp(c).getLong("resetAt", 0L)
    fun setResetAt(c: Context, v: Long) = sp(c).edit().putLong("resetAt", v).apply()

    fun status(c: Context) = sp(c).getString("status", "Idle") ?: "Idle"
    fun setStatus(c: Context, v: String) = sp(c).edit().putString("status", v).apply()

    /** "none", "pin" or "pattern". Migrates the pre-pattern release's boolean flag. */
    fun unlockMode(c: Context): String =
        sp(c).getString("unlockMode", null)
            ?: if (sp(c).getBoolean("unlock", false)) PIN else NONE

    fun setUnlockMode(c: Context, v: String) =
        sp(c).edit().putString("unlockMode", v).putBoolean("unlock", v != NONE).apply()

    // ---------- What gets sent when the limit resets ----------
    const val DEFAULT_MESSAGE = "continue"

    fun message(c: Context): String =
        sp(c).getString("message", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MESSAGE

    fun setMessage(c: Context, v: String) =
        sp(c).edit().putString("message", v.trim().ifEmpty { DEFAULT_MESSAGE }).apply()

    /** Empty means "the most recent chat/project", which is the default. */
    fun project(c: Context): String = sp(c).getString("project", "") ?: ""

    fun setProject(c: Context, v: String) = sp(c).edit().putString("project", v).apply()

    /** Project names recorded from Claude's sidebar, most recent first. */
    fun projects(c: Context): List<String> =
        (sp(c).getString("projects", "") ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    fun setProjects(c: Context, names: List<String>) =
        sp(c).edit().putString("projects", names.joinToString("\n")).apply()

    fun projectScanAt(c: Context) = sp(c).getLong("projectScanAt", 0L)
    fun setProjectScanAt(c: Context, v: Long) = sp(c).edit().putLong("projectScanAt", v).apply()

    // ---------- Launcher icon theme ----------
    const val ICON_DARK = "dark"
    const val ICON_LIGHT = "light"
    const val ICON_MONO = "mono"

    /** "dark" (coral on black), "light" (orange on white) or "mono" (grey on charcoal). */
    fun icon(c: Context): String = sp(c).getString("icon", ICON_DARK) ?: ICON_DARK

    fun setIcon(c: Context, v: String) = sp(c).edit().putString("icon", v).apply()
}
