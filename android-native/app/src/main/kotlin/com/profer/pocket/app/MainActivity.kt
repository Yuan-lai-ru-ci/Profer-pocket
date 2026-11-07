package com.profer.pocket.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.profer.pocket.designsystem.adaptive.ProferWindowSizeProvider
import com.profer.pocket.designsystem.theme.ProferDarkColors
import com.profer.pocket.designsystem.theme.ProferLightColors
import com.profer.pocket.designsystem.theme.ProferTheme
import com.profer.pocket.sessions.DrawerMode
import com.profer.pocket.sessions.SessionListContent
import com.profer.pocket.sessions.SessionStressScreen
import com.profer.pocket.settings.SettingsScreen
import com.profer.pocket.model.HomeMode
import com.profer.pocket.model.ScreenOrientation
import com.profer.pocket.settings.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val viewModel: PocketViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 鸿蒙卓易通（iSulad 里的 Android 容器）会给透明导航栏叠一层系统「对比度 scrim」：
        // 表现就是底部一条比手势条略高、颜色跟随主题但和主题有色差的色带，容器里所有
        // edge-to-edge 的 App 都有。关掉对比度强制，导航栏才是真透明、由 App 背景铺满。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        handleNotificationIntent(intent)
        handleDebugConnectIntent(intent)
        handleDebugLiveIntent(intent)
        setContent {
            // 根部注入窗口尺寸：设置、连接这些独立路由页也要能判断单栏/双栏。
            ProferWindowSizeProvider {
                ProferApp(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
        handleDebugConnectIntent(intent)
        handleDebugLiveIntent(intent)
    }

    /** 仅 debug：不连电脑也能验证实时活动通知的构建与降级。 */
    private fun handleDebugLiveIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        when {
            intent?.getBooleanExtra("debug_live", false) == true -> {
                // 追问文案：胶囊最多 3 个汉字，真机上要确认不被截断。
                val (text, short) = when (intent.getStringExtra("debug_live_kind")) {
                    "permission" -> "等待你确认权限" to "待确认"
                    "question" -> "等待你的回答" to "待回答"
                    "plan" -> "等待你审批计划" to "待审批"
                    "others" -> "正在回复…，另有 2 个会话" to "回复中"
                    "done" -> "已完成" to "已完成"
                    "failed" -> "已结束，有错误" to "有错误"
                    else -> "正在执行 Bash" to "执行中"
                }
                PocketNotifications.postLiveUpdate(
                    this,
                    sessionId = "debug-session",
                    title = "Profer · 调试会话",
                    text = text,
                    shortText = short,
                )
            }

            intent?.getBooleanExtra("debug_live_cancel", false) == true ->
                PocketNotifications.cancelLiveUpdate(this)
        }
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
        // 手机横屏进入沉浸式：导航栏在 360dp 高里要吃掉 48–56dp，会变成输入条下方
        // 一整条与窗口卡无关的底色（桌面版没有）。收起后上滑即可唤出，胶囊提示保留。
        val windowSize = com.profer.pocket.designsystem.adaptive.rememberProferWindowSize()
        ProferImmersiveNavigationBars(enabled = windowSize.isShort && windowSize.isLandscape)
        val activity = LocalContext.current as? Activity
        LaunchedEffect(settings.screenOrientation) {
            activity?.requestedOrientation = when (settings.screenOrientation) {
                ScreenOrientation.System -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                ScreenOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                ScreenOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }

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
        PromptLiveUpdateIfNeeded(enabled = settings.notificationsEnabled)

        LaunchedEffect(pendingNavigation) {
            val sessionId = pendingNavigation ?: return@LaunchedEffect
            viewModel.selectSession(sessionId)
            navController.navigateToChat()
            viewModel.consumeNavigation()
        }

        // 双栏列表栏姿态提到 NavHost 外面：Agent / Chat 两个模式共用一份，
        // 切换模式或进出设置页回来时不会自己弹回来。
        var listPaneCollapsed by rememberSaveable { mutableStateOf(false) }

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
                        onOpenConnection = { navController.navigate("connect") },
                        modelOptions = modelOptions,
                        onSelectModel = { option ->
                            viewModel.selectConversationModel(option.channelId, option.modelId)
                        },
                        onRetryConnection = viewModel::retryConnection,
                        onOpenFilePath = { path ->
                            val filename = path.substringAfterLast('/').ifBlank { path }
                            previewFile = com.profer.pocket.model.AgentAttachment(filename, path)
                        },
                        drawerState = drawerState,
                        chatTools = chatTools,
                        onToggleTool = viewModel::toggleChatTool,
                        listPaneCollapsed = listPaneCollapsed,
                        onToggleListPane = { listPaneCollapsed = !listPaneCollapsed },
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
                        onOpenConnection = { navController.navigate("connect") },
                        onNewSession = { dialog = SessionDialog.NewSession },
                        // 子会话顶栏面包屑：回母会话，不新开页面。
                        onOpenParentSession = {
                            chatState.parentSessionId?.let(viewModel::selectSession)
                        },
                        presetLabel = chatState.presetLabel,
                        presets = presets,
                        onSelectPreset = viewModel::selectPreset,
                        onRefreshPresets = viewModel::refreshCurrentPresets,
                        onSelectThinking = viewModel::selectThinking,
                        onRefreshThinking = viewModel::refreshReasoningCapability,
                        onRewriteMessage = viewModel::rewriteAndResend,
                        onRetrySend = viewModel::retrySend,
                        onOpenAttachment = { previewFile = it },
                        onOpenFilePath = { path ->
                            val filename = path.substringAfterLast('/').ifBlank { path }
                            previewFile = com.profer.pocket.model.AgentAttachment(filename, path)
                        },
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
                        listPaneCollapsed = listPaneCollapsed,
                        onToggleListPane = { listPaneCollapsed = !listPaneCollapsed },
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
                    // 粘贴到配对二维码 JSON 时等同扫码，直接走配对。
                    onPairingText = viewModel::pairWithQr,
                    onClearForm = viewModel::clearManualForm,
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
                    screenOrientation = settings.screenOrientation,
                    onScreenOrientationChange = viewModel::updateScreenOrientation,
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

/**
 * 首次检测到「流体云显示实时活动」关闭时提示一次。
 *
 * 这个开关是系统 per-app 设置（POST_PROMOTED_NOTIFICATIONS 是 normal|appop 权限），
 * 没有运行时弹窗可用，只能把用户送到系统开关页；设置页里也有常驻入口。
 */
@Composable
private fun PromptLiveUpdateIfNeeded(enabled: Boolean) {
    if (!enabled || Build.VERSION.SDK_INT < 36) return
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("profer_live_prompt", Context.MODE_PRIVATE) }
    val capability = remember(context) { PocketNotifications.capability(context) }
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val prompted = prefs.getBoolean("prompted", false)
        Log.i(
            "ProferLive",
            "prompt: prompted=$prompted supported=${capability.supported} " +
                "needSwitch=${capability.requiresUserSwitch} surface=${capability.surface} enabled=$enabled",
        )
        if (prompted) return@LaunchedEffect
        // 只有「系统支持、且明确不让发」才提示；探测失败或已开启都不打扰。
        if (!capability.supported || !capability.requiresUserSwitch) return@LaunchedEffect
        show = true
    }
    if (!show) return
    fun dismiss() {
        prefs.edit().putBoolean("prompted", true).apply()
        show = false
    }
    AlertDialog(
        // 外部点击 / 返回键关闭时不写标记：首次启动常被系统通知权限弹窗挤掉，
        // 那时用户根本没看到内容，写标记就再也不会提示了。
        onDismissRequest = { show = false },
        title = { Text("开启状态栏实时活动") },
        text = {
            Text(
                "开启后，对话运行时会在状态栏显示进度。" +
                    "本机叫「${capability.displayName}」，需要到系统设置里打开。",
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    dismiss()
                    // 分身/多用户下系统设置页可能拉不起来，至少告诉用户手动路径。
                    if (!PocketNotifications.openPromotionSettings(context)) {
                        Toast.makeText(
                            context,
                            "请到系统设置 → 通知 → Profer → ${capability.displayName}中开启",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
            ) { Text("去开启") }
        },
        dismissButton = {
            TextButton(onClick = { dismiss() }) { Text("稍后") }
        },
    )
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

/**
 * 沉浸式导航栏：只在「短屏 + 横屏」时收起系统导航栏。
 *
 * 手机横屏的导航栏（三键 48–56dp）不透明、且强制对比度关不掉，会把输入条下方
 * 填成一整条系统色。收起后内容一直铺到窗口卡底边，观感和桌面版一致；
 * 用户上滑唤出时按 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` 临时浮起，不打断输入。
 */
@Composable
private fun ProferImmersiveNavigationBars(enabled: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current
    LaunchedEffect(enabled) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (enabled) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
    }
    // 离开组合（切到竖屏路由、退出 App）时必须显式恢复，否则导航栏会一直藏着。
    DisposableEffect(Unit) {
        onDispose {
            val window = (context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }
}

@Composable
private fun ApplySystemBarStyle(darkTheme: Boolean) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity ?: return
    LaunchedEffect(darkTheme) {
        val transparent = Color.Transparent.toArgb()
        // 必须用 light()/dark()，不能用 auto()：androidx 的 EdgeToEdgeApi29 里
        // nightMode == MODE_NIGHT_AUTO 会重新打开导航栏对比度强制，那层 scrim 就是
        // 卓易通底部那条色带。light()/dark() 的 nightMode 非 AUTO，强制会被关掉。
        val style = if (darkTheme) {
            SystemBarStyle.dark(transparent)
        } else {
            SystemBarStyle.light(transparent, transparent)
        }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isNavigationBarContrastEnforced = false
        }
        // 容器若没把内容铺到导航栏底下，露出来的是窗口底色：跟 App 主题对齐，
        // 避免「系统亮色 + App 深色」时再出现一条浅色边（胶囊壳当年就踩过这个坑）。
        val background = if (darkTheme) ProferDarkColors.background else ProferLightColors.background
        activity.window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
    }
}
