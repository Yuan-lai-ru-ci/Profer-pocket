package com.profer.pocket.chat

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.profer.pocket.designsystem.theme.ProferTheme

@Preview(name = "聊天 · 浅色", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ChatLightPreview() {
    ProferTheme(darkTheme = false) {
        ChatScreen(
            state = ChatPreviewData.connected,
            draft = "",
            drawerContent = { _ -> },
            onInputChange = {},
            onSend = {},
            onStop = {},
            onAllowPermission = {},
            onDenyPermission = {},
            onOpenSettings = {},
        )
    }
}

@Preview(
    name = "聊天 · 深色",
    showBackground = true,
    widthDp = 360,
    heightDp = 780,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ChatDarkPreview() {
    ProferTheme(darkTheme = true) {
        ChatScreen(
            state = ChatPreviewData.connected,
            draft = "",
            drawerContent = { _ -> },
            onInputChange = {},
            onSend = {},
            onStop = {},
            onAllowPermission = {},
            onDenyPermission = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "聊天 · 大字号 1.3", showBackground = true, widthDp = 360, heightDp = 780, fontScale = 1.3f)
@Composable
private fun ChatLargeFontPreview() {
    ProferTheme(darkTheme = false) {
        ChatScreen(
            state = ChatPreviewData.offline,
            draft = "",
            drawerContent = { _ -> },
            onInputChange = {},
            onSend = {},
            onStop = {},
            onAllowPermission = {},
            onDenyPermission = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "聊天 · 空态", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ChatEmptyPreview() {
    ProferTheme(darkTheme = false) {
        ChatScreen(
            state = ChatPreviewData.empty,
            draft = "",
            drawerContent = { _ -> },
            onInputChange = {},
            onSend = {},
            onStop = {},
            onAllowPermission = {},
            onDenyPermission = {},
            onOpenSettings = {},
        )
    }
}
