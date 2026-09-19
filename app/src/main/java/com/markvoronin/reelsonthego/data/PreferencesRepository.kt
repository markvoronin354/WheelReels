package com.markvoronin.reelsonthego.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    var isGlobalSwipeEnabled: Boolean
        get() = prefs.getBoolean(KEY_GLOBAL_SWIPE, false)
        set(value) = prefs.edit().putBoolean(KEY_GLOBAL_SWIPE, value).apply()

    var isPrevButtonDoubleTap: Boolean
        get() = prefs.getBoolean(KEY_PREV_DOUBLE_TAP, false)
        set(value) {
            val editor = prefs.edit().putBoolean(KEY_PREV_DOUBLE_TAP, value)
            val targetAction = if (value) PrevAction.LIKE else PrevAction.SWIPE_DOWN
            SUPPORTED_APPS.forEach { app ->
                editor.putString(KEY_PREV_ACTION_PREFIX + app.packageName, targetAction.name)
            }
            editor.apply()
        }

    var swipeDurationMs: Long
        get() = prefs.getLong(KEY_SWIPE_DURATION, DEFAULT_SWIPE_DURATION_MS)
        set(value) = prefs.edit().putLong(KEY_SWIPE_DURATION, value).apply()

    var enabledPackages: Set<String>
        get() = prefs.getStringSet(KEY_ENABLED_PACKAGES, DEFAULT_PACKAGES) ?: DEFAULT_PACKAGES
        set(value) = prefs.edit().putStringSet(KEY_ENABLED_PACKAGES, value).apply()

    fun isPackageEnabled(packageName: String): Boolean {
        if (isGlobalSwipeEnabled) return true
        return enabledPackages.contains(packageName)
    }

    fun togglePackage(packageName: String, enabled: Boolean) {
        val current = enabledPackages.toMutableSet()
        if (enabled) {
            current.add(packageName)
        } else {
            current.remove(packageName)
        }
        enabledPackages = current
    }

    fun getPrevActionForPackage(packageName: String): PrevAction {
        if (packageName.isEmpty()) {
            return if (isPrevButtonDoubleTap) PrevAction.LIKE else PrevAction.SWIPE_DOWN
        }
        val key = KEY_PREV_ACTION_PREFIX + packageName
        val saved = prefs.getString(key, null)
        if (saved != null) {
            PrevAction.fromString(saved)?.let { return it }
        }
        return if (isPrevButtonDoubleTap) PrevAction.LIKE else PrevAction.SWIPE_DOWN
    }

    fun setPrevActionForPackage(packageName: String, action: PrevAction) {
        val key = KEY_PREV_ACTION_PREFIX + packageName
        prefs.edit().putString(key, action.name).apply()
    }

    var themeMode: AppThemeMode
        get() {
            val str = prefs.getString(KEY_THEME_MODE, AppThemeMode.DARK.name)
            return AppThemeMode.fromString(str)
        }
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value.name).apply()

    companion object {
        private const val PREFS_NAME = "reels_control_prefs"
        private const val KEY_SERVICE_ENABLED = "key_service_enabled"
        private const val KEY_GLOBAL_SWIPE = "key_global_swipe"
        private const val KEY_PREV_DOUBLE_TAP = "key_prev_double_tap"
        private const val KEY_SWIPE_DURATION = "key_swipe_duration"
        private const val KEY_ENABLED_PACKAGES = "key_enabled_packages"
        private const val KEY_PREV_ACTION_PREFIX = "key_prev_action_"
        private const val KEY_THEME_MODE = "key_theme_mode"

        const val DEFAULT_SWIPE_DURATION_MS = 80L // Fast 80ms snap scroll

        val SWIPE_SPEED_OPTIONS = listOf(
            SwipeSpeedOption("Fast (80 ms)", 80L),
            SwipeSpeedOption("Medium (180 ms)", 180L),
            SwipeSpeedOption("Slow (250 ms)", 250L)
        )

        val DEFAULT_PACKAGES = setOf(
            "com.instagram.android",        // Instagram
            "com.facebook.katana",          // Facebook
            "com.facebook.lite",            // Facebook Lite
            "com.zhiliaoapp.musically",     // TikTok (Global)
            "com.ss.android.ugc.trill",      // TikTok (Regional)
            "com.google.android.youtube",   // YouTube / Shorts
            "app.morphe.android.youtube",   // YouTube (Morphe)
            "com.snapchat.android"          // Snapchat
        )

        val SUPPORTED_APPS = listOf(
            SupportedApp("Instagram", "com.instagram.android"),
            SupportedApp("Facebook", "com.facebook.katana"),
            SupportedApp("Facebook Lite", "com.facebook.lite"),
            SupportedApp("TikTok", "com.zhiliaoapp.musically"),
            SupportedApp("TikTok (Alt)", "com.ss.android.ugc.trill"),
            SupportedApp("YouTube / Shorts", "com.google.android.youtube"),
            SupportedApp("YouTube (Morphe)", "app.morphe.android.youtube"),
            SupportedApp("Snapchat", "com.snapchat.android")
        )
    }
}

data class SupportedApp(
    val displayName: String,
    val packageName: String
)

data class SwipeSpeedOption(
    val label: String,
    val durationMs: Long
)

enum class PrevAction(val label: String) {
    SWIPE_DOWN("Prev Video"),
    LIKE("Like ❤️");

    companion object {
        fun fromString(value: String?): PrevAction? {
            return entries.firstOrNull { it.name == value }
        }
    }
}

enum class AppThemeMode(val label: String) {
    DARK("Dark Mode"),
    LIGHT("Light Mode"),
    SYSTEM("System Default");

    fun next(): AppThemeMode {
        return when (this) {
            DARK -> LIGHT
            LIGHT -> SYSTEM
            SYSTEM -> DARK
        }
    }

    companion object {
        fun fromString(value: String?): AppThemeMode {
            return entries.firstOrNull { it.name == value } ?: DARK
        }
    }
}
