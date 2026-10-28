package com.aodcards

import android.content.Context

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    init {
        if (prefs.contains("system_grouping")) prefs.edit().remove("system_grouping").apply()
        // The previous filter was an allowlist. Start the new blacklist empty instead of
        // treating previously allowed packages as packages to hide.
        if (!prefs.contains("blocked_packages")) {
            prefs.edit().putStringSet("blocked_packages", emptySet()).remove("allowed_packages").apply()
        }
    }
    val showText: Boolean
        get() = prefs.getBoolean("show_text", true)
    var contentMode: Int
        get() = prefs.getInt("content_mode", if (showText) 0 else 1).coerceIn(0, 2)
        set(value) { prefs.edit().putInt("content_mode", value.coerceIn(0, 2)).putBoolean("show_text", value == 0).apply() }
    var compactMode: Boolean
        get() = prefs.getBoolean("compact_mode", false)
        set(value) { prefs.edit().putBoolean("compact_mode", value).apply() }
    var widgetScale: Int
        get() {
            val stored = prefs.getInt("widget_scale", 1)
            if (stored == 3) {
                prefs.edit().putInt("widget_scale", 0).apply()
                return 0
            }
            return stored.coerceIn(0, 2)
        }
        set(value) { prefs.edit().putInt("widget_scale", value.coerceIn(0, 2)).apply() }
    var maxTextLines: Int
        get() = prefs.getInt("max_text_lines", 2).coerceIn(2, 3)
        set(value) { prefs.edit().putInt("max_text_lines", value.coerceIn(2, 3)).apply() }
    var ongoingMode: Int
        get() = prefs.getInt("ongoing_mode", if (prefs.getBoolean("show_ongoing", false)) 0 else 1).coerceIn(0, 2)
        set(value) { prefs.edit().putInt("ongoing_mode", value.coerceIn(0, 2)).apply() }
    var musicPillEnabled: Boolean
        get() = prefs.getBoolean("music_pill_enabled", false)
        set(value) { prefs.edit().putBoolean("music_pill_enabled", value).apply() }
    var showWhenScreenOn: Boolean
        get() = prefs.getBoolean("show_when_screen_on", false)
        set(value) { prefs.edit().putBoolean("show_when_screen_on", value).apply() }
    var overflowBadgePosition: Int
        get() = prefs.getInt("overflow_badge_position", 1).coerceIn(0, 2)
        set(value) { prefs.edit().putInt("overflow_badge_position", value.coerceIn(0, 2)).apply() }
    var blockedPackages: Set<String>
        get() = prefs.getStringSet("blocked_packages", emptySet())?.toSet() ?: emptySet()
        set(value) { prefs.edit().putStringSet("blocked_packages", value).apply() }
}
