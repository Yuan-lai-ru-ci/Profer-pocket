package com.profer.pocket.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.profer.pocket.designsystem.component.ProferComposer
import com.profer.pocket.designsystem.component.ProferIconAction
import com.profer.pocket.designsystem.theme.ProferTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 主路径的最小 Compose UI 回归：输入 → 发送，以及 48dp 触控热区。
 *
 * 这两条是 UI 规范里最容易回退的部分：输入框状态写错就发不出去，
 * 热区被顺手改小 8dp 也没人会在单测里发现。
 */
@RunWith(AndroidJUnit4::class)
class ComposerInteractionTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun typing_then_send_dispatches_once() {
        var draft by mutableStateOf("")
        var sent = 0

        rule.setContent {
            ProferTheme(darkTheme = false) {
                ProferComposer(
                    value = draft,
                    onValueChange = { draft = it },
                    onSend = { sent += 1 },
                    onStop = {},
                    streaming = false,
                    modelLabel = "Sonnet",
                    modeLabel = "默认",
                    onModelClick = {},
                    onModeClick = {},
                )
            }
        }

        rule.onNodeWithText("给 Profer 发消息…").assertExists()
        rule.onNode(hasSetTextAction()).performTextInput("你好")
        rule.onNodeWithContentDescription("发送").performClick()

        rule.runOnIdle { assertEquals(1, sent) }
    }

    @Test
    fun streaming_replaces_send_with_stop_in_place() {
        var stopped = 0

        rule.setContent {
            ProferTheme(darkTheme = false) {
                ProferComposer(
                    value = "",
                    onValueChange = {},
                    onSend = {},
                    onStop = { stopped += 1 },
                    streaming = true,
                    modelLabel = "Sonnet",
                    modeLabel = "默认",
                    onModelClick = {},
                    onModeClick = {},
                )
            }
        }

        // 流式中不允许多出一颗按钮：发送键原位变成停止键，且空输入也能点。
        rule.onNodeWithContentDescription("发送").assertDoesNotExist()
        rule.onNodeWithContentDescription("停止").performClick()

        rule.runOnIdle { assertEquals(1, stopped) }
    }

    @Test
    fun icon_action_keeps_minimum_touch_target() {
        rule.setContent {
            ProferTheme(darkTheme = false) {
                ProferIconAction(
                    icon = Icons.Outlined.Menu,
                    contentDescription = "打开会话列表",
                    onClick = {},
                )
            }
        }

        rule.onNodeWithContentDescription("打开会话列表")
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
    }
}
