package com.profer.pocket.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.profer.pocket.designsystem.component.ProferMark
import com.profer.pocket.designsystem.component.proferFloatingSurface
import com.profer.pocket.designsystem.theme.ProferSizes
import com.profer.pocket.designsystem.theme.ProferSpacing

/** 新对话只有输入一个主路径；未连接时原位替换为恢复操作，不同时展示多个警告。 */
@Composable
internal fun ChatWelcomeState(
    offline: Boolean,
    onConnect: () -> Unit,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
    agent: Boolean = true,
    hasDraftTarget: Boolean = true,
    onStartConversation: () -> Unit = {},
) {
    Box(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = ProferSizes.contentMaxWidth)
                .padding(horizontal = ProferSpacing.xl, vertical = ProferSpacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ProferSpacing.md),
        ) {
            ProferMark(modifier = Modifier.size(ProferSizes.actionVisual))
            Text(
                text = if (offline) "让 Profer 与你同行" else "有什么想一起完成？",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (offline) "连接电脑后，在手机上继续对话与任务。" else if (agent) {
                    "说说目标，剩下的我们一起推进。"
                } else "一个问题，一个灵感，都可以从这里开始。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (offline) {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.heightIn(min = ProferSizes.minTouchTarget),
                    shape = MaterialTheme.shapes.extraLarge,
                ) { Text("连接电脑") }
            } else if (!hasDraftTarget) {
                Button(
                    onClick = onStartConversation,
                    modifier = Modifier.heightIn(min = ProferSizes.minTouchTarget),
                    shape = MaterialTheme.shapes.extraLarge,
                ) { Text(if (agent) "新建会话" else "新建对话") }
            } else {
                val suggestions = listOf(
                    "了解工作区" to "帮我了解当前工作区的结构和运行方式",
                    "排查一个问题" to "帮我排查当前项目的问题，先梳理现状",
                    "梳理待办" to "帮我梳理当前项目的待办和优先级",
                    "整理思路" to "我们先一起梳理这件事的目标与实现思路",
                )
                Column(
                    modifier = Modifier.padding(top = ProferSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
                ) {
                    suggestions.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm)) {
                            row.forEach { (label, prompt) ->
                                Surface(
                                    onClick = { onSuggestion(prompt) },
                                    modifier = Modifier.weight(1f).heightIn(min = ProferSizes.minTouchTarget),
                                    shape = MaterialTheme.shapes.extraLarge,
                                    color = proferFloatingSurface(),
                                    border = BorderStroke(ProferSizes.hairline, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Box(Modifier.padding(ProferSpacing.md), contentAlignment = Alignment.Center) {
                                        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "欢迎 · 浅色", widthDp = 360, heightDp = 640)
@androidx.compose.ui.tooling.preview.Preview(name = "欢迎 · 大字号", widthDp = 360, heightDp = 640, fontScale = 2f)
@Composable
private fun WelcomePreview() {
    com.profer.pocket.designsystem.theme.ProferTheme(darkTheme = false) {
        ChatWelcomeState(offline = false, onConnect = {}, onSuggestion = {})
    }
}
