package com.example.callagent

import android.content.Context

/** User preferences, stored locally. */
object Settings {
    private const val PREFS = "agent"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_OWNER = "owner_name"

    /** Seconds the phone rings before the agent picks up, so the owner can still answer first. */
    const val RING_DELAY_MS = 4_000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Auto-answer is off until the user switches it on. */
    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun ownerName(context: Context): String =
        prefs(context).getString(KEY_OWNER, null)?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.default_owner_name)

    fun setOwnerName(context: Context, name: String) =
        prefs(context).edit().putString(KEY_OWNER, name.trim()).apply()
}
