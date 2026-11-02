package com.profer.pocket.chat

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.profer.pocket.designsystem.theme.ProferTheme

@Preview(name = "聊天 · 平板双栏", showBackground = true, widthDp = 800, heightDp = 1000)
@Composable
private fun ChatTabletPreview() {
    ProferTheme(darkTheme = false) {
        ChatScreen(
            state = ChatPreviewData.connected,
            draft = "",
            // 预览不跨 feature 依赖，左栏用占位内容，验证的是分栏几何与内容区宽度。
            drawerContent = { _ ->
                Text("会话列表", modifier = Modifier.fillMaxSize().padding(16.dp))
            },
            onInputChange = {},
            onSend = {},
            onStop = {},
            onAllowPermission = {},
            onDenyPermission = {},
            onOpenSettings = {},
        )
    }
}

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

@Preview(name = "聊天 · 大字号 2.0", showBackground = true, widthDp = 360, heightDp = 780, fontScale = 2.0f)
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
