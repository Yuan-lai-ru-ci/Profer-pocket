package com.profer.pocket.app.debug

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.profer.pocket.chat.ChatPreviewData
import com.profer.pocket.chat.ChatScreen
import com.profer.pocket.chat.ChatUiState
import com.profer.pocket.chat.ConversationMessage
import com.profer.pocket.chat.ConversationScreen
import com.profer.pocket.chat.ConversationUiState
import com.profer.pocket.chat.ModelOption
import com.profer.pocket.connect.ConnectionPhase
import com.profer.pocket.connect.ConnectionPreviewData
import com.profer.pocket.connect.ConnectionScreen
import com.profer.pocket.connect.ConnectionUiState
import com.profer.pocket.designsystem.adaptive.ProferWindowSizeProvider
import com.profer.pocket.designsystem.theme.ProferTheme
import com.profer.pocket.model.ScreenOrientation
import com.profer.pocket.sessions.SessionListContent
import com.profer.pocket.sessions.SessionPreviewData
import com.profer.pocket.settings.SettingsScreen
import com.profer.pocket.settings.ThemePreference

/**
 * 仅 debug 的真实 Compose 验收入口。样本只在内存中，不读写仓库、凭据或连接状态。
 * adb shell am start -n com.profer.pocket/.app.debug.UiGalleryActivity --es page chat --ez dark true
 * page: chat / empty / offline / permission / turn / rich / conversation / long / sessions / settings / connection / connection-empty / failure / loading
 */
class UiGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("dark", false)
        enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(Color.Transparent.toArgb()) else SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb()),
            navigationBarStyle = if (dark) SystemBarStyle.dark(Color.Transparent.toArgb()) else SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb()),
        )
        setContent {
            var page by remember { mutableStateOf(intent.getStringExtra("page") ?: "chat") }
            var draft by remember { mutableStateOf("") }
            // 主题与方向用真实状态驱动：调试页也得能点得动，否则验收不出真实行为。
            // 必须 rememberSaveable：改 requestedOrientation 会重建 Activity，普通 remember 会被重置回「自动」。
            var theme by rememberSaveable { mutableStateOf(if (dark) ThemePreference.Dark else ThemePreference.Light) }
            var orientation by rememberSaveable { mutableStateOf(ScreenOrientation.System) }
            val activity = LocalContext.current as? Activity
            LaunchedEffect(orientation) {
                activity?.requestedOrientation = when (orientation) {
                    ScreenOrientation.System -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    ScreenOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    ScreenOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
            val darkTheme = when (theme) {
                ThemePreference.System -> isSystemInDarkTheme()
                ThemePreference.Light -> false
                ThemePreference.Dark -> true
            }
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(Color.Transparent.toArgb())
                    } else {
                        SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb())
                    },
                    navigationBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(Color.Transparent.toArgb())
                    } else {
                        SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb())
                    },
                )
            }
            val models = remember { listOf(ModelOption("sample", "sample-model", "Profer", "示例模型")) }
            ProferWindowSizeProvider {
            ProferTheme(darkTheme = darkTheme) {
                when (page) {
                    "settings" -> SettingsScreen(
                        themePreference = theme,
                        onThemePreferenceChange = { theme = it }, notificationsEnabled = true, onNotificationsChange = {},
                        permissionAlertsEnabled = true, onPermissionAlertsChange = {}, autoReconnect = true,
                        onAutoReconnectChange = {}, backgroundConnection = true, onBackgroundConnectionChange = {},
                        screenOrientation = orientation,
                        onScreenOrientationChange = { orientation = it },
                        connected = true, deviceLabel = "MacBook Pro", onOpenConnection = { page = "connection" }, onBack = { page = "chat" },
                    )
                    "connection", "connection-empty", "failure", "loading" -> ConnectionScreen(
                        state = when (page) {
                            "connection-empty" -> ConnectionUiState(phase = ConnectionPhase.Disconnected)
                            "failure" -> ConnectionUiState(phase = ConnectionPhase.Failed, errorMessage = "连接超时。请确认电脑已开启远程连接，并检查网络。")
                            "loading" -> ConnectionUiState(phase = ConnectionPhase.Connecting)
                            else -> ConnectionPreviewData.connected
                        },
                        onBack = { page = "settings" }, onUrlChange = {}, onPortChange = {}, onTokenChange = {}, onPinChange = {},
                        onConnect = {}, onDisconnect = {}, onCancel = { page = "connection" }, onScan = {},
                        onPairingText = {}, onClearForm = {},
                        onSelectDevice = {}, onRemoveDevice = {}, onDismissPairing = {},
                    )
                    "sessions" -> SessionListContent(
                        state = SessionPreviewData.state, onQueryChange = {}, onSelectSession = { page = "chat" }, onNewSession = { page = "empty" },
                        onOpenConnection = { page = "connection" }, onOpenSettings = { page = "settings" }, modifier = Modifier.fillMaxSize(),
                    )
                    "conversation", "long" -> {
                        val messages = remember(page) {
                            if (page == "long") List(240) { index ->
                                if (index % 2 == 0) ConversationMessage.User("msg-$index", "第 $index 条：整理界面规则", "")
                                else ConversationMessage.Assistant("msg-$index", "第 $index 条回复：统一色彩、排版、形状与交互，让每个页面遵循同一套规则。")
                            } else listOf(
                                ConversationMessage.User("u", "一起梳理这次 Android 界面的改造", ""),
                                ConversationMessage.Assistant(
                                    "a",
                                    "## 让界面有共同语言\n\n- 画布保持安静\n- 操作面与内容面分层\n- 所有控件沿用同一组形状与字号\n\n细节服务于阅读，不和内容抢注意力。",
                                    reasoning = "先对齐设计 token，再改页面。",
                                    tools = listOf(
                                        com.profer.pocket.chat.ToolBlock(
                                            id = "t1",
                                            kind = com.profer.pocket.chat.ToolKind.Search,
                                            title = "搜索内容",
                                            subtitle = "ProferSpacing",
                                            status = com.profer.pocket.designsystem.component.ToolStatus.Success,
                                            output = "12 处匹配 · 6 个文件",
                                        ),
                                        com.profer.pocket.chat.ToolBlock(
                                            id = "t2",
                                            kind = com.profer.pocket.chat.ToolKind.Edit,
                                            title = "修改文件",
                                            subtitle = "Dimens.kt · +8 −2",
                                            status = com.profer.pocket.designsystem.component.ToolStatus.Success,
                                        ),
                                    ),
                                ),
                            )
                        }
                        ConversationScreen(
                            state = ConversationUiState(conversationId = "gallery", title = "梳理设计系统", messages = messages),
                            draft = draft,
                            // 双栏验收需要左栏有真实内容；Chat 模式下列表由对话目录填充。
                            drawerContent = { _ ->
                                SessionListContent(
                                    state = SessionPreviewData.state,
                                    onQueryChange = {}, onSelectSession = {}, onNewSession = { page = "empty" },
                                    onOpenConnection = { page = "connection" }, onOpenSettings = { page = "settings" },
                                    modifier = Modifier.fillMaxSize(),
                                    mode = com.profer.pocket.sessions.DrawerMode.Chat,
                                )
                            },
                            modelOptions = models, onInputChange = { draft = it },
                            onSend = { draft = "" }, onStop = {}, onNewConversation = { page = "empty" },
                        )
                    }
                    else -> {
                        var permissionVisible by remember { mutableStateOf(page == "permission") }
                        val state: ChatUiState = when (page) {
                            "empty" -> ChatPreviewData.empty.copy(sessionId = "gallery-draft", offline = false, offlineLabel = null)
                            "offline" -> ChatPreviewData.offline.copy(offlineLabel = "未连接到电脑 · 消息会在重连后发送")
                            "permission" -> ChatPreviewData.connected.copy(pendingPermission = if (permissionVisible) ChatPreviewData.connected.pendingPermission else null)
                            // 一轮六步：整轮只该有一行「执行过程」。
                            "turn" -> ChatPreviewData.multiStepTurn
                            "rich" -> ChatPreviewData.richText
                            else -> ChatPreviewData.connected.copy(streaming = false, pendingPermission = null)
                        }
                        ChatScreen(
                            state = state, draft = draft, drawerContent = { _ ->
                                SessionListContent(
                                    state = SessionPreviewData.state, onQueryChange = {}, onSelectSession = {}, onNewSession = { page = "empty" },
                                    onOpenConnection = { page = "connection" }, onOpenSettings = { page = "settings" }, modifier = Modifier.fillMaxSize(),
                                )
                            },
                            modelOptions = models, onInputChange = { draft = it }, onSend = { draft = "" }, onStop = {}, onAttach = {},
                            onAllowPermission = { permissionVisible = false }, onDenyPermission = { permissionVisible = false },
                            onOpenSettings = { page = "settings" }, onOpenConnection = { page = "connection" }, presetLabel = "移动版 UI 开发",
                        )
                    }
                }
            }
            }
        }
    }
}
