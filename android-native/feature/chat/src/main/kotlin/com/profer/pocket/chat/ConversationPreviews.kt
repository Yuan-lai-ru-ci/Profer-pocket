package com.profer.pocket.chat

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.profer.pocket.designsystem.theme.ProferTheme

/**
 * Chat（普通对话）页预览：模型胶囊、消息复制、回到最新都在真实页面里，
 * 这里只提供稳定的浅色/深色/大字号样本，避免预览依赖真实连接。
 */
private val PreviewModels = listOf(
    ModelOption(
        channelId = "ch-deepseek",
        modelId = "deepseek-v4.1-flash",
        label = "deepseek-v4.1-flash",
        channelLabel = "DeepSeek",
    ),
    ModelOption(
        channelId = "ch-openai",
        modelId = "gpt-5.6-sol",
        label = "gpt-5.6-sol",
        channelLabel = "OpenAI 兼容格式",
    ),
)

private val PreviewConversation = ConversationUiState(
    conversationId = "conversation-preview",
    title = "整理这周的工作",
    channelId = "ch-deepseek",
    modelId = "deepseek-v4.1-flash",
    messages = listOf(
        ConversationMessage.User(
            id = "u1",
            text = "把这几条零散记录整理成一份周报，语气平实一点。",
            meta = "01:12",
        ),
        ConversationMessage.Assistant(
            id = "a1",
            text = "可以。我先把记录按「进展 / 问题 / 下周」三块归并，再压掉重复的措辞。",
        ),
        ConversationMessage.User(
            id = "u2",
            text = "好，先出结构，不用写完整正文。",
            meta = "01:13",
        ),
        ConversationMessage.Assistant(
            id = "a2",
            text = "结构如下：\n\n**进展**：完成移动端 Chat 页的阅读细节；补齐模型切换。\n\n**问题**：离线条没有做真实断网复现。\n\n**下周**：补 Chat 的分支重发协议。",
            reasoning = "用户只要结构，不需要展开正文。",
        ),
    ),
    draft = "",
)

@Preview(name = "对话 · 浅色", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ConversationLightPreview() {
    ProferTheme(darkTheme = false) {
        ConversationScreen(
            state = PreviewConversation,
            drawerContent = { _ -> },
            onInputChange = {},
            onSend = {},
            onStop = {},
            onNewConversation = {},
            modelOptions = PreviewModels,
        )
    }
}

@Preview(
    name = "对话 · 深色",
    showBackground = true,
    widthDp = 360,
    heightDp = 780,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ConversationDarkPreview() {
    ProferTheme(darkTheme = true) {
        ConversationScreen(
            state = PreviewConversation,
            drawerContent = { _ -> },
            onInputChange = {},
            onSend = {},
            onStop = {},
            onNewConversation = {},
            modelOptions = PreviewModels,
        )
    }
}

@Preview(name = "对话 · 大字号 1.3", showBackground = true, widthDp = 360, heightDp = 780, fontScale = 1.3f)
@Composable
private fun ConversationLargeFontPreview() {
    ProferTheme(darkTheme = false) {
        ConversationScreen(
            state = PreviewConversation,
            drawerContent = { _ -> },
            onInputChange = {},
            onSend = {},
            onStop = {},
            onNewConversation = {},
            modelOptions = PreviewModels,
        )
    }
}

@Preview(name = "对话 · 离线", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ConversationOfflinePreview() {
    ProferTheme(darkTheme = false) {
        ConversationScreen(
            state = PreviewConversation.copy(offline = true),
            drawerContent = { _ -> },
            onInputChange = {},
            onSend = {},
            onStop = {},
            onNewConversation = {},
            modelOptions = PreviewModels,
        )
    }
}
