package com.profer.pocket.connect

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.profer.pocket.designsystem.component.ProferBackdrop
import com.profer.pocket.designsystem.component.ProferDivider
import com.profer.pocket.designsystem.component.ProferHeaderRow
import com.profer.pocket.designsystem.component.ProferIconAction
import com.profer.pocket.designsystem.component.ProferSectionLabel
import com.profer.pocket.designsystem.component.ProferStatusDot
import com.profer.pocket.designsystem.theme.ProferSizes
import com.profer.pocket.designsystem.theme.ProferSpacing
import com.profer.pocket.designsystem.theme.ProferTheme
import com.profer.pocket.designsystem.theme.ProferThemeExtras

/**
 * 连接页：以「设备」为中心，而不是以「连接阶段」为中心。
 *
 * - 任何阶段都能添加设备：扫码与手动地址 + Token 两条入口并列（见 [AddDeviceCard]）；
 * - 已绑定设备列表常驻，可直接切换或移除，不必先断开；
 * - 阶段只决定状态卡片形态，不决定用户还能做什么。
 */
@Composable
fun ConnectionScreen(
    state: ConnectionUiState,
    onBack: () -> Unit,
    onUrlChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onPinChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCancel: () -> Unit,
    onScan: () -> Unit,
    onPairingText: (String) -> Unit,
    onClearForm: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onRemoveDevice: (String) -> Unit,
    onDismissPairing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // 粘贴：换设备或连不上时最常用的一条动作，扫码与手动表单共用一份实现。
    // 剪贴板里是配对二维码 JSON 时直接走配对，等同扫码，不必再找摄像头。
    val pasteConnection: () -> Unit = {
        val text = clipboard.getText()?.text?.trim().orEmpty()
        when {
            text.isBlank() ->
                Toast.makeText(context, "剪贴板里没有可用地址", Toast.LENGTH_SHORT).show()

            ConnectionPaste.isPairingPayload(text) -> {
                onPairingText(text)
                Toast.makeText(context, "已粘贴配对信息，正在配对…", Toast.LENGTH_SHORT).show()
            }

            else -> {
                onUrlChange(text)
                Toast.makeText(context, "已粘贴连接地址", Toast.LENGTH_SHORT).show()
            }
        }
    }

    ProferBackdrop(modifier = modifier) {
        // 平板/桌面下顶栏与内容一起限制在 760dp 居中，避免标题贴在屏幕最左、内容却缩在中间。
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Scaffold(
            modifier = Modifier
                .widthIn(max = ProferSizes.contentMaxWidth)
                .fillMaxHeight(),
            containerColor = Color.Transparent,
            topBar = {
                ProferHeaderRow {
                    ProferIconAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onBack)
                    Text("连接", style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).padding(horizontal = ProferSpacing.md))
                }
            },
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier
                        .widthIn(max = ProferSizes.contentMaxWidth)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                        .padding(horizontal = ProferSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(ProferSpacing.md),
                ) {
                    Spacer(modifier = Modifier.height(ProferSpacing.md))

                    // 区块顺序与默认展开状态由 ConnectionLayout 单点描述，测试断言的就是这里渲染的顺序。
                    ConnectionLayout.sections(state.phase).forEach { section ->
                        when (section) {
                            ConnectionSection.Status -> StatusSection(
                                state = state,
                                onDisconnect = onDisconnect,
                                onCancel = onCancel,
                                onRetry = onConnect,
                            )

                            ConnectionSection.AddDevice -> AddDeviceCard(
                                phase = state.phase,
                                deviceCount = state.devices.size,
                                pairing = state.pairing,
                                url = state.url,
                                port = state.port,
                                token = state.token,
                                pin = state.pin,
                                hasSavedToken = state.hasSavedToken,
                                // 失败原因已由上方 FailedPanel 展示，卡片里不重复一遍。
                                errorMessage = state.errorMessage.takeIf { state.phase != ConnectionPhase.Failed },
                                onScan = onScan,
                                onUrlChange = onUrlChange,
                                onPortChange = onPortChange,
                                onTokenChange = onTokenChange,
                                onPinChange = onPinChange,
                                onConnect = onConnect,
                                onPaste = pasteConnection,
                                onClearForm = onClearForm,
                                onDismissPairing = onDismissPairing,
                            )

                            ConnectionSection.Devices -> DeviceSection(
                                state = state,
                                onSelectDevice = onSelectDevice,
                                onRemoveDevice = onRemoveDevice,
                            )

                            ConnectionSection.EndpointFailures -> if (state.endpointFailures.isNotEmpty()) {
                                EndpointFailureCard(state.endpointFailures)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(ProferSpacing.xxl))
                }
            }
        }
    }
        }
}

@Composable
private fun StatusSection(
    state: ConnectionUiState,
    onDisconnect: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    when (state.phase) {
        ConnectionPhase.Connected -> ConnectedCard(state = state, onDisconnect = onDisconnect)

        ConnectionPhase.Disconnected -> Text(
            "尚未连接电脑",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ConnectionPhase.Connecting -> ConnectingPanel(onCancel = onCancel)

        ConnectionPhase.Failed -> FailedPanel(
            message = state.errorMessage.orEmpty(),
            onRetry = onRetry,
        )
    }
}

@Composable
private fun ConnectedCard(
    state: ConnectionUiState,
    onDisconnect: () -> Unit,
) {
    val semantic = ProferThemeExtras.semantic
    // 大字号下三行信息比 48dp 图标高得多，居中会让图标夹在两行中间；回到顶部对齐。
    val largeFont = LocalDensity.current.fontScale >= 1.3f
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(ProferSpacing.lg),
                verticalAlignment = if (largeFont) Alignment.Top else Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ProferSpacing.md),
            ) {
                Box(
                    modifier = Modifier
                        .size(ProferSizes.minTouchTarget)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DesktopWindows,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(ProferSizes.icon),
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ProferSpacing.sm)) {
                    Text(state.deviceName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    // 空地址仍会占一行行高，大字号下变成一块莫名其妙的空白。
                    if (state.deviceAddress.isNotBlank()) {
                        Text(state.deviceAddress, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm)) {
                        ProferStatusDot(color = semantic.success, size = ProferSpacing.sm)
                        Text(if (state.latencyMs > 0) "在线 · ${state.latencyMs} ms" else "已连接",
                            style = MaterialTheme.typography.labelLarge, color = semantic.success)
                    }
                }
            }
            ProferDivider()
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ProferSpacing.lg, vertical = ProferSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(ProferSpacing.xs),
            ) {
                Text(
                    text = "Profer ${state.daemonVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onDisconnect,
                    modifier = Modifier.heightIn(min = ProferSizes.minTouchTarget),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("断开连接") }
            }
        }
    }
}

/** 已绑定设备常驻：断线时是恢复入口，已连接时是切换入口。 */
@Composable
private fun DeviceSection(
    state: ConnectionUiState,
    onSelectDevice: (String) -> Unit,
    onRemoveDevice: (String) -> Unit,
) {
    if (state.devices.isEmpty()) {
        Text(
            "还没有绑定设备。扫码配对或手动连接后，设备会保存在这里，随时切换。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ProferSpacing.xs),
        )
    } else {
        DeviceList(
            devices = state.devices,
            onSelectDevice = onSelectDevice,
            onRemoveDevice = onRemoveDevice,
        )
    }
}

/** 设备列表：当前设备高亮，可切换或移除。 */
@Composable
private fun DeviceList(
    devices: List<DeviceUi>,
    onSelectDevice: (String) -> Unit,
    onRemoveDevice: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ProferSpacing.sm)) {
        ProferSectionLabel("已绑定设备", modifier = Modifier.padding(start = ProferSpacing.xs))
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column {
                devices.forEachIndexed { index, device ->
                    if (index > 0) ProferDivider(insetStart = ProferSpacing.lg)
                    Column(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = ProferSizes.listItemHeight)
                                .clickable(enabled = !device.isActive, role = Role.Button) { onSelectDevice(device.id) }
                                .padding(horizontal = ProferSpacing.lg, vertical = ProferSpacing.md),
                            verticalArrangement = Arrangement.spacedBy(ProferSpacing.xs),
                        ) {
                            Text(device.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            // 地址是区分两台手动设备（都叫「手动连接」）的唯一可靠字段。
                            if (device.address.isNotBlank()) {
                                Text(
                                    device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // 局域网 + 无信任信息时 scopeLabel 与 trustLabel 都是「局域网」，
                            // 拼起来就成了「局域网 · 局域网」。同一个词只说一次。
                            Text(
                                if (device.scopeLabel == device.trustLabel) {
                                    device.scopeLabel
                                } else {
                                    "${device.scopeLabel} · ${device.trustLabel}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(if (device.isActive) "当前设备" else "点按切换到此设备", style = MaterialTheme.typography.labelMedium,
                                color = if (device.isActive) ProferThemeExtras.semantic.success else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(
                            onClick = { onRemoveDevice(device.id) },
                            modifier = Modifier.padding(horizontal = ProferSpacing.sm).heightIn(min = ProferSizes.minTouchTarget),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("移除此设备") }
                    }
                }
            }
        }
    }
}

@Composable
private fun EndpointFailureCard(failures: List<String>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(ProferSpacing.md)) {
            Text(
                text = "端点尝试记录",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            failures.take(4).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = ProferSpacing.xxs),
                )
            }
        }
    }
}

@Composable
private fun ConnectingPanel(onCancel: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(ProferSpacing.lg), verticalArrangement = Arrangement.spacedBy(ProferSpacing.lg)) {
            Text("正在连接电脑", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("正在建立安全连接，连接完成后会自动同步会话。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 骨架与已连接卡保持相同层级；只消费 Connecting，不虚构分步进度。
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ProferSpacing.md)) {
                Box(Modifier.size(ProferSizes.minTouchTarget).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ProferSpacing.sm)) {
                    Box(Modifier.fillMaxWidth(0.65f).height(ProferSpacing.lg).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small))
                    Box(Modifier.fillMaxWidth().height(ProferSpacing.md).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small))
                }
            }
            Box(Modifier.fillMaxWidth().height(ProferSpacing.md).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small))
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = ProferSizes.minTouchTarget)) { Text("取消连接") }
        }
    }
}

@Composable
private fun FailedPanel(message: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(modifier = Modifier.padding(ProferSpacing.lg)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(ProferSizes.icon),
                )
                Text(
                    text = "连接失败",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                text = message.ifBlank { "暂时无法连接。请确认电脑端远程访问已开启，或修改下方地址后重试。" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = ProferSpacing.sm),
            )
            Row(
                modifier = Modifier
                    .padding(top = ProferSpacing.md)
                    .heightIn(min = ProferSizes.minTouchTarget)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onRetry),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(ProferSizes.iconSmall),
                )
                Text(
                    text = "重试",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Preview(name = "连接 · 浅色", showBackground = true)
@Preview(name = "连接 · 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "连接 · 大字号", showBackground = true, fontScale = 2f)
@Composable
private fun ConnectionPreview() {
    ConnectionPreviewContent(ConnectionPreviewData.disconnected)
}

@Composable
private fun ConnectionPreviewContent(state: ConnectionUiState) {
    ProferTheme(darkTheme = isSystemInDarkTheme()) {
        ConnectionScreen(
            state = state,
            onBack = {}, onUrlChange = {}, onPortChange = {}, onTokenChange = {}, onPinChange = {},
            onConnect = {}, onDisconnect = {}, onCancel = {}, onScan = {}, onPairingText = {},
            onClearForm = {}, onSelectDevice = {}, onRemoveDevice = {}, onDismissPairing = {},
        )
    }
}

@Preview(name = "连接 · 已连接", showBackground = true)
@Composable
private fun ConnectedPreview() {
    ConnectionPreviewContent(ConnectionPreviewData.connected)
}

@Preview(name = "连接 · 失败恢复", showBackground = true, fontScale = 1.3f)
@Composable
private fun ConnectionFailedPreview() {
    ConnectionPreviewContent(ConnectionPreviewData.failed)
}

@Preview(name = "连接 · 骨架屏", showBackground = true)
@Composable
private fun ConnectingPreview() {
    ConnectionPreviewContent(ConnectionPreviewData.connecting)
}
