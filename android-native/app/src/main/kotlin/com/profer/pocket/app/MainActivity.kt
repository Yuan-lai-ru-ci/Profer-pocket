package com.profer.pocket.app

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.profer.pocket.chat.ChatScreen
import com.profer.pocket.chat.ConversationScreen
import com.profer.pocket.chat.rememberPocketDrawerState
import com.profer.pocket.chat.ModelOption
import com.profer.pocket.connect.ConnectionPhase
import com.profer.pocket.connect.ConnectionScreen
import com.profer.pocket.connect.QrScannerScreen
import com.profer.pocket.designsystem.theme.ProferTheme
import com.profer.pocket.sessions.DrawerMode
import com.profer.pocket.sessions.SessionListContent
import com.profer.pocket.sessions.SessionStressScreen
import com.profer.pocket.settings.SettingsScreen
import com.profer.pocket.model.HomeMode
import com.profer.pocket.settings.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val viewModel: PocketViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleNotificationIntent(intent)
        handleDebugConnectIntent(intent)
        setContent { ProferApp(viewModel) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
        handleDebugConnectIntent(intent)
    }

    /** 仅 debug：adb 启动时用 --es debug_endpoint / --es debug_token 直接配好连接。 */
    private fun handleDebugConnectIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val endpoint = intent?.getStringExtra("debug_endpoint")?.takeIf { it.isNotBlank() } ?: return
        val token = intent.getStringExtra("debug_token").orEmpty()
        viewModel.connectWith(endpoint, token)
    }

    override fun onStart() {
        super.onStart()
        AppForeground.isForeground = true
        viewModel.onForeground()
    }

    override fun onStop() {
        AppForeground.isForeground = false
        super.onStop()
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val sessionId = intent?.getStringExtra(PocketNotifications.EXTRA_SESSION_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return
        viewModel.openFromNotification(sessionId)
    }
}

@Composable
fun ProferApp(viewModel: PocketViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (settings.theme) {
        com.profer.pocket.model.ThemeMode.System -> systemDark
        com.profer.pocket.model.ThemeMode.Light -> false
        com.profer.pocket.model.ThemeMode.Dark -> true
    }

    ApplySystemBarStyle(darkTheme = darkTheme)

    ProferTheme(darkTheme = darkTheme) {
        // 首帧完成后才允许启动前台服务：冷启动期间主线程排不进服务回调。
        LaunchedEffect(Unit) { viewModel.onUiReady() }
        val navController = rememberNavController()
        val context = LocalContext.current
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        val chatState by viewModel.chatUiState.collectAsStateWithLifecycle()
        val channels by viewModel.channels.collectAsStateWithLifecycle()
        val presets by viewModel.presets.collectAsStateWithLifecycle()
        val chatTools by viewModel.chatTools.collectAsStateWithLifecycle()
        val attachments by viewModel.attachments.collectAsStateWithLifecycle()
        val readPositions by viewModel.readPositions.collectAsStateWithLifecycle()
        val modelOptions = remember(channels, chatState.channelId) {
            val enabled = channels.filter { it.enabled }
            // 当前渠道排在最前，其余渠道的模型跟在后面；模型必须和渠道一起提交。
            val options = enabled.sortedByDescending { it.id == chatState.channelId }
                .flatMap { channel ->
                    val channelLabel = channel.name.ifBlank { channel.id }
                    channel.models.map { model ->
                        val rawLabel = model.name.ifBlank { model.id }
                        ModelOption(
                            channelId = channel.id,
                            modelId = model.id,
                            label = rawLabel
                                .substringAfterLast('/')
                                .substringAfterLast(':')
                                .ifBlank { rawLabel },
                            channelLabel = channelLabel,
                            rawLabel = rawLabel,
                        )
                    }
                }
                .distinctBy { it.channelId to it.modelId }
            val labelCounts = options.groupingBy { it.label }.eachCount()
            options.map { option ->
                if ((labelCounts[option.label] ?: 0) > 1) {
                    option.copy(label = option.rawLabel)
                } else {
                    option
                }
            }
        }
        val sessionListState by viewModel.sessionListState.collectAsStateWithLifecycle()
        val homeMode by viewModel.homeMode.collectAsStateWithLifecycle()
        val conversationListState by viewModel.conversationListState.collectAsStateWithLifecycle()
        val conversationUiState by viewModel.conversationUiState.collectAsStateWithLifecycle()
        // 草稿单独收集：同步、最新，不经过 chatUiState 的后台映射，避免输入框被旧文本回写。
        val drafts by viewModel.drafts.collectAsStateWithLifecycle()
        val conversationDrafts by viewModel.conversationDrafts.collectAsStateWithLifecycle()
        val connectionState by viewModel.connectionUiState.collectAsStateWithLifecycle()
        val pendingNavigation by viewModel.pendingNavigation.collectAsStateWithLifecycle()
        var dialog by remember { mutableStateOf<SessionDialog?>(null) }

        val projectOptions by viewModel.projectOptions.collectAsStateWithLifecycle()
        val message by viewModel.message.collectAsStateWithLifecycle()
        val busy by viewModel.busy.collectAsStateWithLifecycle()

        val composeScope = rememberCoroutineScope()
        var previewFile by remember { mutableStateOf<com.profer.pocket.model.AgentAttachment?>(null) }
        var previewResult by remember { mutableStateOf<com.profer.pocket.model.RemoteFilePreview?>(null) }
        var previewLoading by remember { mutableStateOf(false) }
        LaunchedEffect(previewFile) {
            val file = previewFile ?: return@LaunchedEffect
            val sessionId = chatState.sessionId ?: return@LaunchedEffect
            previewLoading = true
            previewResult = null
            viewModel.loadFilePreview(sessionId, file.path, file.filename)
                .onSuccess { previewResult = it }
                .onFailure {
                    Toast.makeText(context, it.message ?: "文件读取失败", Toast.LENGTH_SHORT).show()
                    previewFile = null
                }
            previewLoading = false
        }
        val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val sessionId = chatState.sessionId
            if (uri == null || sessionId == null) return@rememberLauncherForActivityResult
            composeScope.launch {
                val encoded = withContext(Dispatchers.IO) {
                    ImageAttachmentEncoder.encode(context, uri)
                }
                encoded.onSuccess { (filename, data) ->
                    viewModel.attachImage(sessionId, filename, data)
                }.onFailure {
                    Toast.makeText(context, it.message ?: "图片读取失败", Toast.LENGTH_SHORT).show()
                }
            }
        }

        RequestNotificationPermissionIfNeeded(enabled = settings.notificationsEnabled)

        LaunchedEffect(pendingNavigation) {
            val sessionId = pendingNavigation ?: return@LaunchedEffect
            viewModel.selectSession(sessionId)
            navController.navigateToChat()
            viewModel.consumeNavigation()
        }

        NavHost(navController = navController, startDestination = "chat") {
            composable("chat") {
                val drawerState = rememberPocketDrawerState()
                val drawerContent: @Composable (onClose: () -> Unit) -> Unit = { closeDrawer ->
                    SessionListContent(
                        state = sessionListState,
                        mode = if (homeMode == HomeMode.Chat) DrawerMode.Chat else DrawerMode.Agent,
                        onModeChange = { drawerMode ->
                            viewModel.setHomeMode(
                                if (drawerMode == DrawerMode.Chat) HomeMode.Chat else HomeMode.Agent,
                            )
                        },
                        conversations = conversationListState,
                        onSelectConversation = { id ->
                            viewModel.openConversation(id)
                            closeDrawer()
                        },
                        onNewConversation = {
                            closeDrawer()
                            viewModel.newConversation()
                        },
                        onConversationQueryChange = viewModel::updateConversationQuery,
                        onRefreshConversations = viewModel::refreshConversations,
                        onDeleteConversation = viewModel::deleteConversation,
                        onQueryChange = viewModel::updateSessionQuery,
                        onSelectSession = { id ->
                            viewModel.selectSession(id)
                            closeDrawer()
                        },
                        onNewSession = {
                            closeDrawer()
                            dialog = SessionDialog.NewSession
                        },
                        onNewSessionInProject = { workspaceId ->
                            viewModel.newSession(workspaceId)
                            closeDrawer()
                        },
                        onRefresh = viewModel::refreshCatalog,
                        onRenameSession = { session ->
                            dialog = SessionDialog.Rename(session.id, session.title)
                        },
                        onTogglePin = viewModel::togglePin,
                        onToggleRead = viewModel::toggleRead,
                        onMoveSession = { session ->
                            dialog = SessionDialog.Move(
                                sessionId = session.id,
                                title = session.title,
                                workspaceId = session.workspaceId,
                            )
                        },
                        onDeleteSession = { session ->
                            dialog = SessionDialog.Delete(session.id, session.title)
                        },
                        onToggleProjectPin = viewModel::toggleProjectPin,
                        onOpenConnection = {
                            closeDrawer()
                            navController.navigate("connect")
                        },
                        onOpenSettings = {
                            closeDrawer()
                            navController.navigate("settings")
                        },
                    )
                }

                if (homeMode == HomeMode.Chat) {
                    ConversationScreen(
                        state = conversationUiState,
                        draft = conversationDrafts[conversationUiState.conversationId].orEmpty(),
                        drawerContent = drawerContent,
                        onInputChange = viewModel::onConversationInputChange,
                        onSend = viewModel::sendConversation,
                        onStop = viewModel::stopConversation,
                        onNewConversation = viewModel::newConversation,
                        modelOptions = modelOptions,
                        onSelectModel = { option ->
                            viewModel.selectConversationModel(option.channelId, option.modelId)
                        },
                        onRetryConnection = viewModel::retryConnection,
                        drawerState = drawerState,
                        chatTools = chatTools,
                        onToggleTool = viewModel::toggleChatTool,
                    )
                } else {
                    ChatScreen(
                        state = chatState,
                        draft = drafts[chatState.sessionId].orEmpty(),
                        modelOptions = modelOptions,
                        onSelectModel = { option -> viewModel.selectModel(option.channelId, option.modelId) },
                        onSelectMode = viewModel::selectPermissionMode,
                        drawerContent = drawerContent,
                        onInputChange = viewModel::onInputChange,
                        onSend = viewModel::send,
                        onCompactContext = viewModel::compactContext,
                        onResendText = viewModel::resend,
                        onStop = viewModel::stop,
                        onAllowPermission = viewModel::allowPermission,
                        onAlwaysAllowPermission = viewModel::alwaysAllowPermission,
                        onDenyPermission = viewModel::denyPermission,
                        onOpenSettings = { navController.navigate("settings") },
                        presetLabel = chatState.presetLabel,
                        presets = presets,
                        onSelectPreset = viewModel::selectPreset,
                        onRefreshPresets = viewModel::refreshCurrentPresets,
                        onRewriteMessage = viewModel::rewriteAndResend,
                        onRetrySend = viewModel::retrySend,
                        onOpenAttachment = { previewFile = it },
                        onOpenImage = { image ->
                            previewFile = com.profer.pocket.model.AgentAttachment(
                                filename = image.filename,
                                path = image.path,
                            )
                        },
                        imageLoader = { path ->
                            chatState.sessionId?.let { viewModel.loadMessageImage(it, path) }
                        },
                        lastReadMessageId = chatState.sessionId?.let { readPositions[it] },
                        onReadLatest = { messageId ->
                            chatState.sessionId?.let { viewModel.markReadUpTo(it, messageId) }
                        },
                        onAnswerQuestion = viewModel::answerQuestion,
                        attachments = chatState.sessionId?.let { attachments[it] }.orEmpty(),
                        onAttach = { imagePicker.launch("image/*") },
                        onRemoveAttachment = { path ->
                            chatState.sessionId?.let { viewModel.removeAttachment(it, path) }
                        },
                        onRetryConnection = viewModel::retryConnection,
                        message = message,
                        onMessageShown = viewModel::consumeMessage,
                        onRenameSession = {
                            chatState.sessionId?.let { dialog = SessionDialog.Rename(it, chatState.title) }
                        },
                        onTogglePin = {
                            chatState.sessionId?.let(viewModel::togglePin)
                        },
                        onDeleteSession = {
                            chatState.sessionId?.let { dialog = SessionDialog.Delete(it, chatState.title) }
                        },
                        drawerState = drawerState,
                    )
                }
            }

            composable("connect") {
                ConnectionScreen(
                    state = connectionState,
                    onBack = { navController.popBackStack() },
                    onUrlChange = viewModel::updateUrl,
                    onPortChange = viewModel::updatePort,
                    onTokenChange = viewModel::updateToken,
                    onPinChange = viewModel::updatePin,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                    onCancel = viewModel::disconnect,
                    onScan = { navController.navigate("scan") },
                    onSelectDevice = viewModel::selectDevice,
                    onRemoveDevice = viewModel::removeDevice,
                    onDismissPairing = viewModel::dismissPairing,
                )
            }

            composable("scan") {
                QrScannerScreen(
                    onBack = { navController.popBackStack() },
                    onScanned = { text ->
                        // 配对在后台进行，失败原因回到连接页展示；扫码页立即关闭。
                        viewModel.pairWithQr(text)
                        navController.popBackStack()
                    },
                )
            }

            composable("settings") {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                var updateLabel by remember { mutableStateOf<String?>(null) }
                var internalUpdateLabel by remember { mutableStateOf<String?>(null) }

                fun runUpdateCheck(
                    setLabel: (String?) -> Unit,
                    check: suspend () -> Result<NativePocketUpdater.UpdateInfo?>,
                ) {
                    setLabel("正在检查…")
                    scope.launch {
                        check().onSuccess { info ->
                            if (info == null) {
                                setLabel("已是最新版本")
                            } else {
                                setLabel("发现 ${info.versionName}，正在下载…")
                                NativePocketUpdater.downloadAndInstall(context, info)
                                    .onSuccess { setLabel("已触发安装 ${info.versionName}") }
                                    .onFailure { setLabel("更新失败：${it.message ?: "未知错误"}") }
                            }
                        }.onFailure { setLabel("检查失败：${it.message ?: "网络错误"}") }
                    }
                }
                SettingsScreen(
                    themePreference = settings.theme.toPreference(),
                    onThemePreferenceChange = viewModel::updateTheme,
                    notificationsEnabled = settings.notificationsEnabled,
                    onNotificationsChange = viewModel::updateNotifications,
                    permissionAlertsEnabled = settings.permissionAlertsEnabled,
                    onPermissionAlertsChange = viewModel::updatePermissionAlerts,
                    autoReconnect = settings.autoReconnect,
                    onAutoReconnectChange = viewModel::updateAutoReconnect,
                    backgroundConnection = settings.backgroundConnection,
                    onBackgroundConnectionChange = viewModel::updateBackgroundConnection,
                    connected = connectionState.phase == ConnectionPhase.Connected,
                    deviceLabel = if (connectionState.phase == ConnectionPhase.Connected) {
                        "${connectionState.deviceName} · 在线"
                    } else {
                        "未连接"
                    },
                    onOpenConnection = { navController.navigate("connect") },
                    onBack = { navController.popBackStack() },
                    showDeveloperTools = debuggable,
                    onOpenStressTest = { navController.navigate("stress") },
                    updateLabel = updateLabel,
                    onCheckUpdate = {
                        runUpdateCheck({ updateLabel = it }) { NativePocketUpdater.checkStable(context) }
                    },
                    internalUpdateLabel = internalUpdateLabel,
                    onCheckInternalUpdate = {
                        runUpdateCheck({ internalUpdateLabel = it }) { NativePocketUpdater.checkInternal(context) }
                    },
                )
            }

            composable("stress") {
                SessionStressScreen(onBack = { navController.popBackStack() })
            }
        }

        previewFile?.let { file ->
            AttachmentPreviewDialog(
                file = file,
                preview = previewResult,
                loading = previewLoading,
                onDismiss = {
                    previewFile = null
                    previewResult = null
                },
            )
        }

        SessionDialogHost(
            dialog = dialog,
            projects = projectOptions,
            busy = busy,
            onDismiss = { dialog = null },
            onNewSession = { workspaceId ->
                dialog = null
                viewModel.newSession(workspaceId)
            },
            onRename = { sessionId, title ->
                dialog = null
                viewModel.renameSession(sessionId, title)
            },
            onMove = { sessionId, workspaceId ->
                dialog = null
                viewModel.moveSession(sessionId, workspaceId)
            },
            onDelete = { sessionId ->
                dialog = null
                viewModel.deleteSession(sessionId)
            },
        )
    }
}

@Composable
private fun RequestNotificationPermissionIfNeeded(enabled: Boolean) {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(enabled, asked) {
        if (asked) return@LaunchedEffect
        asked = true
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun NavHostController.navigateToChat() {
    navigate("chat") {
        launchSingleTop = true
        popUpTo(graph.startDestinationId) { inclusive = false }
    }
}

private fun com.profer.pocket.model.ThemeMode.toPreference(): ThemePreference = when (this) {
    com.profer.pocket.model.ThemeMode.System -> ThemePreference.System
    com.profer.pocket.model.ThemeMode.Light -> ThemePreference.Light
    com.profer.pocket.model.ThemeMode.Dark -> ThemePreference.Dark
}

@Composable
private fun ApplySystemBarStyle(darkTheme: Boolean) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity ?: return
    LaunchedEffect(darkTheme) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = Color.Transparent.toArgb(),
                darkScrim = Color.Transparent.toArgb(),
            ) { darkTheme },
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = Color.Transparent.toArgb(),
                darkScrim = Color.Transparent.toArgb(),
            ) { darkTheme },
        )
    }
}
