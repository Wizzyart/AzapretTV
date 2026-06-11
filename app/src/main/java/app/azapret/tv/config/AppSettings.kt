package app.azapret.tv.config

import android.content.Context

object AppSettings {
    val BASIC_BYPASS_PROFILES = listOf(0, 4, 1, 6, 7, 8)
    val BYPASS_PROFILES = listOf(0, 4, 1, 6, 7, 8, 10, 11, 12, 13, 14, 15)
    private const val PREFS = "azapret_tv_settings"
    private const val KEY_VPN_ENABLED = "vpn_enabled"
    private const val KEY_LAST_STATUS = "last_status"
    private const val KEY_HARD_STEP = "hard_step"
    private const val KEY_BYPASS_PROFILE = "bypass_profile"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_BEST_TEST_PROFILE = "best_test_profile"
    private const val KEY_BEST_TEST_SUMMARY = "best_test_summary"

    fun isVpnEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VPN_ENABLED, false)

    fun setVpnEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VPN_ENABLED, enabled)
            .apply()
    }

    fun getLastStatus(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_STATUS, "") ?: ""

    fun setLastStatus(context: Context, status: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_STATUS, status)
            .apply()
    }

    fun getHardStep(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_HARD_STEP, 0)

    fun setHardStep(context: Context, step: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_HARD_STEP, step)
            .apply()
    }

    fun getBypassProfile(context: Context): Int {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_BYPASS_PROFILE, 0)
        return if (saved in BYPASS_PROFILES) saved else 0
    }

    fun setBypassProfile(context: Context, profile: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BYPASS_PROFILE, if (profile in BYPASS_PROFILES) profile else 0)
            .apply()
    }

    fun getBestTestProfile(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_BEST_TEST_PROFILE, -1)

    fun setBestTestProfile(context: Context, profile: Int?, summary: String) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (profile != null && profile in BYPASS_PROFILES) {
            editor.putInt(KEY_BEST_TEST_PROFILE, profile)
            editor.putString(KEY_BEST_TEST_SUMMARY, summary)
        } else {
            editor.remove(KEY_BEST_TEST_PROFILE)
            editor.putString(KEY_BEST_TEST_SUMMARY, summary)
        }
        editor.apply()
    }

    fun getBestTestSummary(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BEST_TEST_SUMMARY, "") ?: ""

    fun getBypassProfileName(context: Context): String = when (getBypassProfile(context)) {
        1 -> "Orbit SIMPLE"
        4 -> "Orbit MSS88"
        6 -> "DPI YOUTUBE 1"
        7 -> "DPI YOUTUBE 2"
        8 -> "DPI GOOGLEVIDEO"
        10 -> "DPI YOUTUBE 3"
        11 -> "DPI GOOGLEVIDEO 2"
        12 -> "DPI YOUTUBE LIGHT"
        13 -> "DPI GOOGLEVIDEO HOSTLIST"
        14 -> "DPI HOSTS"
        15 -> "DPI DISOOB"
        else -> "Orbit MID"
    }

    fun isAutoStartEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_START, false)

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_START, enabled)
            .apply()
    }
}
