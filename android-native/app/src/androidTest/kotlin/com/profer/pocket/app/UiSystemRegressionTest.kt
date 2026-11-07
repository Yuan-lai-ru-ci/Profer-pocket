package com.profer.pocket.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.profer.pocket.chat.ChatPreviewData
import com.profer.pocket.chat.ChatScreen
import com.profer.pocket.chat.ChatMessage
import com.profer.pocket.chat.ContentBlock
import com.profer.pocket.chat.ThinkingBlock
import com.profer.pocket.chat.ToolBlock
import com.profer.pocket.chat.ToolKind
import com.profer.pocket.chat.ConversationMessage
import com.profer.pocket.chat.ConversationScreen
import com.profer.pocket.chat.ConversationUiState
import com.profer.pocket.designsystem.adaptive.ProferWindowSizeProvider
import com.profer.pocket.designsystem.component.ProferComposer
import com.profer.pocket.designsystem.component.ToolStatus
import com.profer.pocket.designsystem.theme.ProferTheme
import com.profer.pocket.settings.SettingsScreen
import com.profer.pocket.settings.ThemePreference
import com.profer.pocket.model.ContextUsage
import com.profer.pocket.model.ScreenOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiSystemRegressionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun empty_draft_has_disabled_send() {
        rule.setContent {
            ProferTheme(darkTheme = false) {
                ProferComposer(value = "", onValueChange = {}, onSend = {}, onStop = {}, streaming = false,
                    modelLabel = "Profer", modeLabel = "执行", onModelClick = {}, onModeClick = {})
            }
        }
        rule.onNodeWithContentDescription("发送").assertIsNotEnabled()
    }

    @Test fun multiline_composer_keeps_actions_at_bottom_at_double_font() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ProferTheme(darkTheme = true) {
                    ProferComposer(value = "第一行\n第二行\n第三行", onValueChange = {}, onSend = {}, onStop = {}, streaming = false,
                        modelLabel = "很长的模型名字", modeLabel = "执行", onModelClick = {}, onModeClick = {}, onAttach = {})
                }
            }
        }
        // 端键按参考实现固定 40dp 视觉，整条胶囊 56dp；多行时两者底边必须对齐。
        val attach = rule.onNodeWithContentDescription("更多选项").assertIsDisplayed().assertHeightIsAtLeast(40.dp).fetchSemanticsNode().boundsInRoot
        val send = rule.onNodeWithContentDescription("发送").assertIsDisplayed().assertHeightIsAtLeast(40.dp).fetchSemanticsNode().boundsInRoot
        assertEquals(attach.bottom, send.bottom, 1f)
    }

    @Test fun composer_menu_holds_context_selectors() {
        rule.setContent {
            ProferTheme(darkTheme = false) {
                ProferComposer(value = "", onValueChange = {}, onSend = {}, onStop = {}, streaming = false,
                    modelLabel = "Opus 4.6", modeLabel = "执行", onModelClick = {}, onModeClick = {},
                    presetLabel = "移动版 UI 开发", onPresetClick = {}, onAttach = {})
            }
        }
        // 三个选择器不再单独占一行，而是收进「+」菜单里。
        rule.onNodeWithContentDescription("更多选项").performClick()
        rule.onNodeWithText("模型").assertIsDisplayed()
        rule.onNodeWithText("Opus 4.6").assertIsDisplayed()
        rule.onNodeWithText("预设").assertIsDisplayed()
        rule.onNodeWithText("权限").assertIsDisplayed()
    }

    @Test fun welcome_suggestion_only_fills_draft() {
        var draft by mutableStateOf("")
        var sends = 0
        rule.setContent {
            ProferTheme(darkTheme = false) {
                ChatScreen(state = ChatPreviewData.empty.copy(sessionId = "draft-session", offline = false, offlineLabel = null), draft = draft,
                    drawerContent = { _ -> }, onInputChange = { draft = it }, onSend = { sends++ }, onStop = {},
                    onAllowPermission = {}, onDenyPermission = {}, onOpenSettings = {})
            }
        }
        rule.onNodeWithText("了解工作区").performClick()
        rule.runOnIdle { assertTrue(draft.contains("工作区")); assertEquals(0, sends) }
    }

    @Test fun offline_welcome_has_one_recovery_action() {
        var opened = 0
        rule.setContent {
            ProferTheme(darkTheme = false) {
                ChatScreen(state = ChatPreviewData.empty.copy(offline = true, offlineLabel = "未配置连接"), draft = "",
                    drawerContent = { _ -> }, onInputChange = {}, onSend = {}, onStop = {},
                    onAllowPermission = {}, onDenyPermission = {}, onOpenSettings = { error("不应打开设置") },
                    onOpenConnection = { opened++ })
            }
        }
        rule.onNodeWithText("连接电脑").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, opened) }
        rule.onNodeWithText("重连").assertDoesNotExist()
    }

    @Test fun missing_agent_session_opens_creation_without_discarding_a_draft() {
        var creates = 0
        rule.setContent {
            ProferTheme(darkTheme = false) {
                ChatScreen(state = ChatPreviewData.empty, draft = "", drawerContent = { _ -> },
                    onInputChange = { error("尚无会话时不能写草稿") }, onSend = {}, onStop = {},
                    onAllowPermission = {}, onDenyPermission = {}, onOpenSettings = {},
                    onNewSession = { creates++ })
            }
        }
        rule.onNodeWithText("新建会话").performClick()
        rule.runOnIdle { assertEquals(1, creates) }
        rule.onNodeWithText("了解工作区").assertDoesNotExist()
    }

    @Test fun missing_chat_conversation_starts_creation_without_writing_a_premature_draft() {
        var creates = 0
        rule.setContent {
            ProferTheme(darkTheme = false) {
                ConversationScreen(state = ConversationUiState(), draft = "", drawerContent = { _ -> },
                    onInputChange = { error("新建完成前不能写草稿") }, onSend = {}, onStop = {},
                    onNewConversation = { creates++ })
            }
        }
        rule.onNodeWithText("新建对话").performClick()
        rule.runOnIdle { assertEquals(1, creates) }
    }

    @Test fun chat_tool_activity_is_folded_and_expands() {
        // Chat 页与桌面一致：工具行逐条折叠，点开才看参数与结果。
        val state = ConversationUiState(
            conversationId = "c1",
            title = "对话",
            messages = listOf(
                ConversationMessage.Assistant(
                    id = "a1",
                    text = "好了",
                    tools = listOf(
                        ToolBlock(
                            id = "t1",
                            kind = ToolKind.Read,
                            title = "读取文件",
                            subtitle = "src/a.kt",
                            status = ToolStatus.Success,
                            input = """{"file_path":"src/a.kt"}""",
                            output = "fun main() {}",
                        ),
                    ),
                ),
            ),
        )
        rule.setContent {
            ProferTheme(darkTheme = false) {
                ConversationScreen(
                    state = state, draft = "", drawerContent = { _ -> },
                    onInputChange = {}, onSend = {}, onStop = {}, onNewConversation = {},
                )
            }
        }
        rule.onNodeWithText("读取文件", substring = true).assertIsDisplayed()
        rule.onNodeWithText("fun main()", substring = true).assertDoesNotExist()
        rule.onNodeWithText("读取文件", substring = true).performClick()
        rule.onNodeWithText("fun main()", substring = true).assertIsDisplayed()
    }

    @Test fun tool_only_turn_has_no_copy_action() {
        // 纯工具回合没有正文：复制按钮不能变成一个复制空串的死图标。
        // 回合结束后与桌面一样折成一行「执行过程」，工具卡展开后才可见。
        val state = ChatPreviewData.connected.copy(
            streaming = false,
            pendingPermission = null,
            messages = listOf(
                ChatMessage.Assistant(
                    id = "tool-only",
                    tools = listOf(
                        ToolBlock("t1", ToolKind.Read, "读取 ws-client.ts", "src/ws-client.ts", ToolStatus.Success),
                    ),
                ),
            ),
        )
        rule.setContent {
            ProferTheme(darkTheme = false) {
                ChatScreen(state = state, draft = "", drawerContent = { _ -> }, onInputChange = {}, onSend = {}, onStop = {},
                    onAllowPermission = {}, onDenyPermission = {}, onOpenSettings = {})
            }
        }
        rule.onNodeWithText("执行过程：1 次工具调用").assertIsDisplayed()
        rule.onNodeWithText("读取 ws-client.ts", substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("复制回复").assertDoesNotExist()
        // 展开过程组后工具卡回到原位。
        rule.onNodeWithText("执行过程：1 次工具调用").performClick()
        rule.onNodeWithText("读取 ws-client.ts", substring = true).assertIsDisplayed()
    }

    @Test fun completed_turn_folds_thinking_and_tools_into_one_row() {
        val state = ChatPreviewData.connected.copy(
            streaming = false,
            pendingPermission = null,
            messages = listOf(
                ChatMessage.Assistant(
                    id = "folded",
                    thinking = ThinkingBlock("18 秒", "先确认游标是在哪一步推进的。"),
                    tools = listOf(
                        ToolBlock("t1", ToolKind.Read, "读取 ws-client.ts", "src/ws-client.ts", ToolStatus.Success),
                    ),
                    blocks = listOf(ContentBlock.Paragraph("结论：游标推进得太早。")),
                ),
            ),
        )
        rule.setContent {
            ProferTheme(darkTheme = false) {
                ChatScreen(state = state, draft = "", drawerContent = { _ -> }, onInputChange = {}, onSend = {}, onStop = {},
                    onAllowPermission = {}, onDenyPermission = {}, onOpenSettings = {})
            }
        }
        rule.onNodeWithText("执行过程：1 次工具调用，1 段思考").assertIsDisplayed()
        rule.onNodeWithText("读取 ws-client.ts", substring = true).assertDoesNotExist()
        rule.onNodeWithText("先确认游标", substring = true).assertDoesNotExist()
        rule.onNodeWithText("执行过程：1 次工具调用，1 段思考").performClick()
        rule.onNodeWithText("读取 ws-client.ts", substring = true).assertIsDisplayed()
        rule.onNodeWithText("先确认游标", substring = true).assertIsDisplayed()
    }

    @Test fun streaming_process_expands_then_folds_when_turn_completes() {
        var streaming by mutableStateOf(true)
        val message = ChatMessage.Assistant(
            id = "live",
            thinking = ThinkingBlock("18 秒", "先确认游标是在哪一步推进的。"),
            tools = listOf(
                ToolBlock("t1", ToolKind.Read, "读取 ws-client.ts", "src/ws-client.ts", ToolStatus.Running),
            ),
            blocks = listOf(ContentBlock.Paragraph("结论：游标推进得太早。")),
            streaming = true,
        )
        rule.setContent {
            ProferTheme(darkTheme = false) {
                ChatScreen(
                    state = ChatPreviewData.connected.copy(
                        streaming = streaming,
                        pendingPermission = null,
                        messages = listOf(message.copy(streaming = streaming)),
                    ),
                    draft = "", drawerContent = { _ -> }, onInputChange = {}, onSend = {}, onStop = {},
                    onAllowPermission = {}, onDenyPermission = {}, onOpenSettings = {},
                )
            }
        }
        // 流式中默认展开：正在做什么要看得见。
        rule.onNodeWithText("读取 ws-client.ts", substring = true).assertIsDisplayed()
        rule.runOnIdle { streaming = false }
        rule.mainClock.advanceTimeBy(3_000)
        // 回合结束后自动收起，只留一行摘要；用户点开仍能看到过程。
        rule.onNodeWithText("读取 ws-client.ts", substring = true).assertDoesNotExist()
        rule.onNodeWithText("执行过程：1 次工具调用，1 段思考").assertIsDisplayed()
    }

    @Test fun thinking_without_tools_still_folds_into_process_group() {
        // 桌面口径：只要还有正文，思考就属于「执行过程」，不单独铺一张思考卡。
        val state = ChatPreviewData.connected.copy(
            streaming = false,
            pendingPermission = null,
            messages = listOf(
                ChatMessage.Assistant(
                    id = "thinking-with-text",
                    thinking = ThinkingBlock("18 秒", "先确认游标是在哪一步推进的。"),
                    blocks = listOf(ContentBlock.Paragraph("结论：游标推进得太早。")),
                ),
            ),
        )
        rule.setContent {
            ProferTheme(darkTheme = false) {
                ChatScreen(state = state, draft = "", drawerContent = { _ -> }, onInputChange = {}, onSend = {}, onStop = {},
                    onAllowPermission = {}, onDenyPermission = {}, onOpenSettings = {})
            }
        }
        rule.onNodeWithText("执行过程：1 段思考").assertIsDisplayed()
        rule.onNodeWithText("先确认游标", substring = true).assertDoesNotExist()
        // 正文在过程组外面，不被一起折进去。
        rule.onNodeWithText("结论：游标推进得太早。").assertIsDisplayed()
        rule.onNodeWithText("执行过程：1 段思考").performClick()
        rule.onNodeWithText("先确认游标", substring = true).assertIsDisplayed()
    }

    @Test fun multi_step_turn_folds_into_one_process_row() {
        // 一轮七八步（每步一段思考 + 一次工具调用）必须收成一行过程摘要；
        // 逐步一行是「非常幽默」的那版：手机上一屏全是重复的「执行过程：1 次工具调用，1 段思考」。
        val steps = (1..4).map { index ->
            ChatMessage.Assistant(
                id = "step-$index",
                thinking = ThinkingBlock("3 秒", "第 $index 步：先确认第 $index 处调用。"),
                tools = listOf(
                    ToolBlock("t$index", ToolKind.Read, "读取 file$index.kt", "src/file$index.kt", ToolStatus.Success),
                ),
            )
        }
        val state = ChatPreviewData.connected.copy(
            streaming = false,
            pendingPermission = null,
            messages = listOf(ChatMessage.Turn("step-1", steps)),
        )
        rule.setContent {
            ProferTheme(darkTheme = false) {
                ChatScreen(state = state, draft = "", drawerContent = { _ -> }, onInputChange = {}, onSend = {}, onStop = {},
                    onAllowPermission = {}, onDenyPermission = {}, onOpenSettings = {})
            }
        }
        rule.onNodeWithText("执行过程：4 次工具调用，4 段思考").assertIsDisplayed()
        rule.onNodeWithText("执行过程：1 次工具调用，1 段思考").assertDoesNotExist()
        rule.onNodeWithText("读取 file4.kt", substring = true).assertDoesNotExist()

        // 展开：默认只铺最近三步，更早的折在「还有 1 步过程」里。
        rule.onNodeWithText("执行过程：4 次工具调用，4 段思考").performClick()
        rule.onNodeWithText("读取 file4.kt", substring = true).assertIsDisplayed()
        rule.onNodeWithText("读取 file1.kt", substring = true).assertDoesNotExist()
        rule.onNodeWithText("还有 1 步过程").assertIsDisplayed()
        rule.onNodeWithText("还有 1 步过程").performClick()
        rule.onNodeWithText("读取 file1.kt", substring = true).assertIsDisplayed()
    }

    @Test fun multi_step_turn_ends_with_answer_outside_group() {
        // 中间步骤的说明跟着过程折叠，最后一步的正文留在组外——答案不能被折进去。
        val state = ChatPreviewData.connected.copy(
            streaming = false,
            pendingPermission = null,
            messages = listOf(
                ChatMessage.Turn(
                    id = "answer-1",
                    steps = listOf(
                        ChatMessage.Assistant(
                            id = "answer-1",
                            thinking = ThinkingBlock("3 秒", "先读配置。"),
                            tools = listOf(ToolBlock("t1", ToolKind.Read, "读取 config.kt", "src/config.kt", ToolStatus.Success)),
                            blocks = listOf(ContentBlock.Paragraph("我先读一下配置。")),
                        ),
                        ChatMessage.Assistant(
                            id = "answer-2",
                            thinking = ThinkingBlock("2 秒", "可以回答了。"),
                            blocks = listOf(ContentBlock.Paragraph("结论：游标推进得太早。")),
                        ),
                    ),
                ),
            ),
        )
        rule.setContent {
            ProferTheme(darkTheme = false) {
                ChatScreen(state = state, draft = "", drawerContent = { _ -> }, onInputChange = {}, onSend = {}, onStop = {},
                    onAllowPermission = {}, onDenyPermission = {}, onOpenSettings = {})
            }
        }
        rule.onNodeWithText("执行过程：1 次工具调用，2 段思考").assertIsDisplayed()
        rule.onNodeWithText("结论：游标推进得太早。").assertIsDisplayed()
        rule.onNodeWithText("我先读一下配置。").assertDoesNotExist()
    }

    @Test fun thinking_only_turn_is_promoted_and_visible() {
        // 思考是本轮唯一产出：不能折成一行「执行过程：1 段思考」了事。
        val state = ChatPreviewData.connected.copy(
            streaming = false,
            pendingPermission = null,
            messages = listOf(
                ChatMessage.Assistant(
                    id = "thinking-only-reply",
                    thinking = ThinkingBlock("18 秒", "模型只回了一段思考。"),
                ),
            ),
        )
        rule.setContent {
            ProferTheme(darkTheme = false) {
                ChatScreen(state = state, draft = "", drawerContent = { _ -> }, onInputChange = {}, onSend = {}, onStop = {},
                    onAllowPermission = {}, onDenyPermission = {}, onOpenSettings = {})
            }
        }
        rule.onNodeWithText("执行过程：1 段思考").assertDoesNotExist()
        rule.onNodeWithText("思考摘要").assertIsDisplayed()
        rule.onNodeWithText("模型只回了一段思考。", substring = true).assertIsDisplayed()
    }

    @Test fun context_usage_ring_needs_data() {
        // 新会话还没有 usage 时顶栏不能出现一个没有含义的灰环。
        val base = ChatPreviewData.connected.copy(
            sessionId = "s1",
            streaming = false,
            pendingPermission = null,
            messages = emptyList(),
        )
        var withData by mutableStateOf(false)
        rule.setContent {
            ProferTheme(darkTheme = false) {
                val state = if (withData) {
                    base.copy(contextUsage = ContextUsage(inputTokens = 1_000), contextWindow = 200_000)
                } else {
                    base.copy(contextUsage = null, contextWindow = null)
                }
                ChatScreen(state = state, draft = "", drawerContent = { _ -> }, onInputChange = {}, onSend = {}, onStop = {},
                    onAllowPermission = {}, onDenyPermission = {}, onOpenSettings = {})
            }
        }
        rule.onNodeWithContentDescription("上下文用量", substring = true).assertDoesNotExist()
        rule.runOnIdle { withData = true }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("上下文用量", substring = true).assertIsDisplayed()
    }

    @Test fun wide_window_keeps_session_list_and_drops_drawer_button() {
        // 平板/折叠展开：左栏常驻，顶栏不再需要「打开会话列表」。
        rule.setContent {
            ProferTheme(darkTheme = false) {
                Box(Modifier.requiredWidth(800.dp).height(1000.dp)) {
                    ChatScreen(
                        state = ChatPreviewData.connected,
                        draft = "",
                        drawerContent = { _ -> androidx.compose.material3.Text("会话目录样本") },
                        onInputChange = {}, onSend = {}, onStop = {},
                        onAllowPermission = {}, onDenyPermission = {}, onOpenSettings = {},
                    )
                }
            }
        }
        // 测试窗口只有 360dp 宽，800dp 的布局会超出屏幕；这里验证「已组合」而不是「在屏幕内可见」。
        rule.onNodeWithText("会话目录样本").assertExists()
        rule.onNodeWithContentDescription("打开会话列表").assertDoesNotExist()
    }

    @Test fun compact_window_keeps_drawer_button() {
        rule.setContent {
            ProferTheme(darkTheme = false) {
                Box(Modifier.width(360.dp).height(800.dp)) {
                    ChatScreen(
                        state = ChatPreviewData.connected,
                        draft = "",
                        drawerContent = { _ -> androidx.compose.material3.Text("会话目录样本") },
                        onInputChange = {}, onSend = {}, onStop = {},
                        onAllowPermission = {}, onDenyPermission = {}, onOpenSettings = {},
                    )
                }
            }
        }
        rule.onNodeWithContentDescription("打开会话列表").assertIsDisplayed()
        rule.onNodeWithText("会话目录样本").assertDoesNotExist()
    }

    @Test fun tablet_settings_uses_section_navigation() {
        // 平板设置是「左导航 + 右内容」：默认只渲染外观，点「通知」才切过去。
        rule.setContent {
            ProferTheme(darkTheme = false) {
                Box(Modifier.requiredWidth(900.dp).height(1000.dp)) {
                    ProferWindowSizeProvider {
                        SettingsScreen(
                            themePreference = ThemePreference.System, onThemePreferenceChange = {},
                            notificationsEnabled = true, onNotificationsChange = {},
                            permissionAlertsEnabled = true, onPermissionAlertsChange = {},
                            autoReconnect = true, onAutoReconnectChange = {},
                            backgroundConnection = false, onBackgroundConnectionChange = {},
                            screenOrientation = ScreenOrientation.System, onScreenOrientationChange = {},
                            connected = false, deviceLabel = "Mac", onOpenConnection = {}, onBack = {},
                        )
                    }
                }
            }
        }
        rule.onNodeWithText("外观").assertExists()
        rule.onNodeWithText("任务完成通知").assertDoesNotExist()
        rule.onNodeWithText("通知").performClick()
        rule.onNodeWithText("任务完成通知").assertExists()
    }

    @Test fun new_message_does_not_steal_position_in_240_message_history() {
        val history = List(240) { index ->
            ConversationMessage.Assistant("message-$index", "第 $index 条：统一设计规则，保持阅读位置稳定。")
        }
        var state by mutableStateOf(ConversationUiState(conversationId = "history", title = "长列表", messages = history))
        rule.setContent {
            ProferTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    ConversationScreen(state = state, draft = "", drawerContent = { _ -> }, onInputChange = {},
                        onSend = {}, onStop = {}, onNewConversation = {})
                }
            }
        }
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToIndex(60)
        rule.onNodeWithText("第 60 条：统一设计规则，保持阅读位置稳定。").assertIsDisplayed()
        rule.runOnIdle {
            state = state.copy(messages = history + ConversationMessage.Assistant("message-240", "最新消息，不应抢滚动"))
        }
        rule.waitForIdle()
        rule.onNodeWithText("第 60 条：统一设计规则，保持阅读位置稳定。").assertIsDisplayed()
        rule.onNodeWithContentDescription("回到最新").assertIsDisplayed()
    }
}
