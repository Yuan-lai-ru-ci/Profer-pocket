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

@Preview(name = "会话列表 · 2× 字号", showBackground = true, widthDp = 320, heightDp = 780, fontScale = 2f)
@Composable
private fun SessionListExtraLargeFontPreview() {
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

private val conversationPreviewState = ConversationListState(
    items = listOf(
        ConversationItem(
            id = "chat-1",
            title = "Android 会话恢复方案对比与边界检查",
            timeLabel = "12 分钟前",
            selected = true,
            preview = "比较本地快照与增量同步的恢复行为。",
        ),
        ConversationItem(
            id = "chat-2",
            title = "整理本周的发布清单",
            timeLabel = "昨天",
            pinned = true,
        ),
    ),
)

@Preview(name = "Chat 抽屉 · 浅色", showBackground = true, widthDp = 320, heightDp = 780)
@Preview(
    name = "Chat 抽屉 · 深色",
    showBackground = true,
    widthDp = 320,
    heightDp = 780,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "Chat 抽屉 · 2× 字号", showBackground = true, widthDp = 320, heightDp = 780, fontScale = 2f)
@Composable
private fun ChatDrawerPreview() {
    ProferTheme(darkTheme = false) {
        SessionListContent(
            state = SessionPreviewData.state,
            onQueryChange = {},
            onSelectSession = {},
            onNewSession = {},
            onOpenConnection = {},
            onOpenSettings = {},
            mode = DrawerMode.Chat,
            conversations = conversationPreviewState,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(name = "会话列表 · 初次加载", showBackground = true, widthDp = 320, heightDp = 780)
@Composable
private fun SessionListLoadingPreview() {
    ProferTheme(darkTheme = false) {
        SessionListContent(
            state = SessionPreviewData.empty.copy(refreshing = true),
            onQueryChange = {},
            onSelectSession = {},
            onNewSession = {},
            onOpenConnection = {},
            onOpenSettings = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(name = "会话列表 · 未连接", showBackground = true, widthDp = 320, heightDp = 780)
@Composable
private fun SessionListOfflinePreview() {
    ProferTheme(darkTheme = false) {
        SessionListContent(
            state = SessionPreviewData.state.copy(connected = false),
            onQueryChange = {},
            onSelectSession = {},
            onNewSession = {},
            onOpenConnection = {},
            onOpenSettings = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private val projectPreviewOptions = listOf(
    ProjectOption(id = "p1", name = "Profer 安卓原生客户端", sessionCount = 12),
    ProjectOption(id = "p2", name = "网站与发布资料", sessionCount = 5),
)

@Preview(name = "新建会话 · 项目选择", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun NewSessionSheetPreview() {
    ProferTheme(darkTheme = false) {
        NewSessionSheet(projects = projectPreviewOptions, onSelect = {}, onDismiss = {})
    }
}

@Preview(name = "移动会话 · 2× 字号", showBackground = true, widthDp = 360, heightDp = 780, fontScale = 2f)
@Composable
private fun MoveSessionSheetPreview() {
    ProferTheme(darkTheme = false) {
        MoveSessionSheet(
            projects = projectPreviewOptions,
            currentWorkspaceId = "p1",
            onSelect = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "重命名会话", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun RenameSessionDialogPreview() {
    ProferTheme(darkTheme = false) {
        RenameSessionDialog(
            initialTitle = "Android 会话恢复方案",
            busy = false,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "删除会话", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun DeleteSessionDialogPreview() {
    ProferTheme(darkTheme = false) {
        DeleteSessionDialog(
            title = "Android 会话恢复方案",
            busy = false,
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "会话列表 · 240 条", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun SessionStressScreenPreview() {
    ProferTheme(darkTheme = false) {
        SessionStressScreen(onBack = {})
    }
}
