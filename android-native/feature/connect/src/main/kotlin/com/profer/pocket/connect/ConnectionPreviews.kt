package com.profer.pocket.connect

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.profer.pocket.designsystem.theme.ProferTheme

@Preview(name = "连接 · 已连接", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ConnectConnectedPreview() {
    ProferTheme(darkTheme = false) {
        ConnectionScreen(
            state = ConnectionPreviewData.connected,
            onBack = {},
            onUrlChange = {},
            onTokenChange = {},
            onConnect = {},
            onDisconnect = {},
            onCancel = {},
            onScan = {},
            onSelectDevice = {},
            onRemoveDevice = {},
            onDismissPairing = {},
        )
    }
}

@Preview(
    name = "连接 · 未连接（深色）",
    showBackground = true,
    widthDp = 360,
    heightDp = 780,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ConnectDisconnectedDarkPreview() {
    ProferTheme(darkTheme = true) {
        ConnectionScreen(
            state = ConnectionPreviewData.disconnected,
            onBack = {},
            onUrlChange = {},
            onTokenChange = {},
            onConnect = {},
            onDisconnect = {},
            onCancel = {},
            onScan = {},
            onSelectDevice = {},
            onRemoveDevice = {},
            onDismissPairing = {},
        )
    }
}

@Preview(name = "连接 · 连接中", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ConnectConnectingPreview() {
    ProferTheme(darkTheme = false) {
        ConnectionScreen(
            state = ConnectionPreviewData.connecting,
            onBack = {},
            onUrlChange = {},
            onTokenChange = {},
            onConnect = {},
            onDisconnect = {},
            onCancel = {},
            onScan = {},
            onSelectDevice = {},
            onRemoveDevice = {},
            onDismissPairing = {},
        )
    }
}

@Preview(name = "连接 · 失败", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ConnectFailedPreview() {
    ProferTheme(darkTheme = false) {
        ConnectionScreen(
            state = ConnectionPreviewData.failed,
            onBack = {},
            onUrlChange = {},
            onTokenChange = {},
            onConnect = {},
            onDisconnect = {},
            onCancel = {},
            onScan = {},
            onSelectDevice = {},
            onRemoveDevice = {},
            onDismissPairing = {},
        )
    }
}

@Preview(name = "连接 · 配对失败", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ConnectPairingFailedPreview() {
    ProferTheme(darkTheme = true) {
        ConnectionScreen(
            state = ConnectionPreviewData.pairingFailed,
            onBack = {},
            onUrlChange = {},
            onTokenChange = {},
            onConnect = {},
            onDisconnect = {},
            onCancel = {},
            onScan = {},
            onSelectDevice = {},
            onRemoveDevice = {},
            onDismissPairing = {},
        )
    }
}
