package com.profer.pocket.sessions

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.profer.pocket.designsystem.theme.ProferTheme

@Preview(name = "会话列表 · 浅色", showBackground = true, widthDp = 320, heightDp = 780)
@Composable
private fun SessionListLightPreview() {
    ProferTheme(darkTheme = false) {
        SessionListContent(
            state = SessionPreviewData.state,
            onQueryChange = {},
            onSelectSession = {},
            onNewSession = {},
            onOpenConnection = {},
            onOpenSettings = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(
    name = "会话列表 · 深色",
    showBackground = true,
    widthDp = 320,
    heightDp = 780,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SessionListDarkPreview() {
    ProferTheme(darkTheme = true) {
        SessionListContent(
            state = SessionPreviewData.state,
            onQueryChange = {},
            onSelectSession = {},
            onNewSession = {},
            onOpenConnection = {},
            onOpenSettings = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(name = "会话列表 · 空", showBackground = true, widthDp = 320, heightDp = 780)
@Composable
private fun SessionListEmptyPreview() {
    ProferTheme(darkTheme = false) {
        SessionListContent(
            state = SessionPreviewData.empty,
            onQueryChange = {},
            onSelectSession = {},
            onNewSession = {},
            onOpenConnection = {},
            onOpenSettings = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(name = "会话列表 · 大字号", showBackground = true, widthDp = 320, heightDp = 780, fontScale = 1.3f)
@Composable
private fun SessionListLargeFontPreview() {
    ProferTheme(darkTheme = false) {
        SessionListContent(
            state = SessionPreviewData.state,
            onQueryChange = {},
            onSelectSession = {},
            onNewSession = {},
            onOpenConnection = {},
            onOpenSettings = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}
