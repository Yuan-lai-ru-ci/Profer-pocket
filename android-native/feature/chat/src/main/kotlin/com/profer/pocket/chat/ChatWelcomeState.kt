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
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.adaptive.rememberProferWindowSize
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
    // 手机横屏只有 360dp 高，按竖屏的 32dp 上下留白 + 24dp 组间距，内容会超出可视区，
    // 滚到中间时按钮正好被输入条挡住。短屏换成紧凑档，一屏内放得下完整内容。
    val window = rememberProferWindowSize()
    val shortScreen = window.isShort
    // 建议卡：短屏一律铺成一行，两行会把第二排压到输入条下面（真机 781dp 横屏复现）。
    // 双栏时对话列只有 420–500dp，卡片更窄，字号降一档并强制单行，否则「排查一个问题」换行、
    // 四张卡高低不齐。竖屏仍是 2×2。
    val suggestionColumns = if (shortScreen) 4 else 2
    val tightSuggestions = shortScreen && window.isTwoPane
    Box(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = ProferSizes.contentMaxWidth)
                .padding(
                    horizontal = ProferSpacing.xl,
                    vertical = if (shortScreen) ProferSpacing.md else ProferSpacing.xxl,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(
                if (shortScreen) ProferSpacing.sm else ProferSpacing.md,
            ),
        ) {
            ProferMark(modifier = Modifier.size(if (shortScreen) 32.dp else ProferSizes.actionVisual))
            Text(
                text = if (offline) "让 Profer 与你同行" else "有什么想一起完成？",
                style = if (shortScreen) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.headlineSmall
                },
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (offline) "连接电脑后，在手机上继续对话与任务。" else if (agent) {
                    "说说目标，剩下的我们一起推进。"
                } else "一个问题，一个灵感，都可以从这里开始。",
                style = if (shortScreen) {
                    MaterialTheme.typography.bodySmall
                } else {
                    MaterialTheme.typography.bodyMedium
                },
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
                    modifier = Modifier.padding(top = if (shortScreen) 0.dp else ProferSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
                ) {
                    suggestions.chunked(suggestionColumns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm)) {
                            row.forEach { (label, prompt) ->
                                Surface(
                                    onClick = { onSuggestion(prompt) },
                                    modifier = Modifier.weight(1f).heightIn(min = ProferSizes.minTouchTarget),
                                    shape = MaterialTheme.shapes.extraLarge,
                                    color = proferFloatingSurface(),
                                    border = BorderStroke(ProferSizes.hairline, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Box(
                                        Modifier.padding(if (shortScreen) ProferSpacing.sm else ProferSpacing.md),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            label,
                                            style = if (tightSuggestions) {
                                                MaterialTheme.typography.labelMedium
                                            } else {
                                                MaterialTheme.typography.labelLarge
                                            },
                                            maxLines = 1,
                                            textAlign = TextAlign.Center,
                                        )
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
