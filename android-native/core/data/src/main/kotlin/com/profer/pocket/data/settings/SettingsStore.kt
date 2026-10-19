package com.profer.pocket.data.settings

import android.content.Context
import com.profer.pocket.model.PocketSettings
import com.profer.pocket.model.ThemeMode

interface SettingsStore {
    fun load(): PocketSettings

    fun save(settings: PocketSettings)
}

class AndroidSettingsStore(context: Context) : SettingsStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): PocketSettings = PocketSettings(
        theme = enumValueOfOr(prefs.getString(KEY_THEME, null), ThemeMode.System),
        notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS, true),
        permissionAlertsEnabled = prefs.getBoolean(KEY_PERMISSION_ALERTS, true),
        autoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, true),
        backgroundConnection = prefs.getBoolean(KEY_BACKGROUND, true),
    )

    override fun save(settings: PocketSettings) {
        prefs.edit()
            .putString(KEY_THEME, settings.theme.name)
            .putBoolean(KEY_NOTIFICATIONS, settings.notificationsEnabled)
            .putBoolean(KEY_PERMISSION_ALERTS, settings.permissionAlertsEnabled)
            .putBoolean(KEY_AUTO_RECONNECT, settings.autoReconnect)
            .putBoolean(KEY_BACKGROUND, settings.backgroundConnection)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOfOr(raw: String?, fallback: T): T = try {
        if (raw == null) fallback else enumValueOf<T>(raw)
    } catch (_: IllegalArgumentException) {
        fallback
    }

    private companion object {
        const val PREFS_NAME = "profer_pocket_settings"
        const val KEY_THEME = "theme"
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_PERMISSION_ALERTS = "permission_alerts"
        const val KEY_AUTO_RECONNECT = "auto_reconnect"
        const val KEY_BACKGROUND = "background_connection"
    }
}
