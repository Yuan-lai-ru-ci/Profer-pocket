package com.profer.pocket.settings

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.profer.pocket.designsystem.theme.ProferTheme

@Composable
private fun PreviewSettings(darkTheme: Boolean) {
    ProferTheme(darkTheme = darkTheme) {
        SettingsScreen(
            themePreference = if (darkTheme) ThemePreference.Dark else ThemePreference.System,
            onThemePreferenceChange = {},
            notificationsEnabled = true,
            onNotificationsChange = {},
            permissionAlertsEnabled = true,
            onPermissionAlertsChange = {},
            autoReconnect = true,
            onAutoReconnectChange = {},
            backgroundConnection = true,
            onBackgroundConnectionChange = {},
            connected = true,
            deviceLabel = "MacBook Pro · 24ms",
            onOpenConnection = {},
            onBack = {},
            showDeveloperTools = true,
            onOpenStressTest = {},
        )
    }
}

@Preview(name = "设置 · 浅色", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun SettingsLightPreview() {
    PreviewSettings(darkTheme = false)
}

@Preview(
    name = "设置 · 深色",
    showBackground = true,
    widthDp = 360,
    heightDp = 780,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SettingsDarkPreview() {
    PreviewSettings(darkTheme = true)
}

@Preview(name = "设置 · 大字号", showBackground = true, widthDp = 360, heightDp = 780, fontScale = 1.3f)
@Composable
private fun SettingsLargeFontPreview() {
    PreviewSettings(darkTheme = false)
}
