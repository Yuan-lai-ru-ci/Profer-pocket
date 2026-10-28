package com.profer.pocket.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.component.ProferDivider
import com.profer.pocket.designsystem.component.ProferIconAction
import com.profer.pocket.designsystem.component.ProferStatusDot
import com.profer.pocket.designsystem.theme.ProferCodeTextStyle
import com.profer.pocket.designsystem.theme.ProferSizes
import com.profer.pocket.designsystem.theme.ProferSpacing
import com.profer.pocket.designsystem.theme.ProferThemeExtras

@OptIn(ExperimentalMaterial3Api::class)
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
    onSelectDevice: (String) -> Unit,
    onRemoveDevice: (String) -> Unit,
    onDismissPairing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // 粘贴地址：连不上时最常用的一条恢复动作，连接页两个入口共用一份实现。
    val pasteAddress: () -> Unit = {
        val text = clipboard.getText()?.text?.trim().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(context, "剪贴板里没有可用地址", Toast.LENGTH_SHORT).show()
        } else {
            onUrlChange(text)
            Toast.makeText(context, "已粘贴连接地址", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "连接",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                navigationIcon = {
                    ProferIconAction(
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                        onClick = onBack,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = ProferSpacing.lg),
        ) {
            Spacer(modifier = Modifier.height(ProferSpacing.md))

            when (state.phase) {
                ConnectionPhase.Connected -> Column(
                    verticalArrangement = Arrangement.spacedBy(ProferSpacing.md),
                ) {
                    ConnectedCard(
                        state = state,
                        onDisconnect = onDisconnect,
                    )
                    ConnectionDetails(state = state)
                    ScanAnotherDeviceCard(
                        pairing = state.pairing,
                        onScan = onScan,
                        onDismiss = onDismissPairing,
                    )
                }

                ConnectionPhase.Disconnected -> Column(
                    verticalArrangement = Arrangement.spacedBy(ProferSpacing.md),
                ) {
                    ScanCard(pairing = state.pairing, onScan = onScan, onDismiss = onDismissPairing)
                    ManualConnectPanel(
                        url = state.url,
                        port = state.port,
                        token = state.token,
                        pin = state.pin,
                        hasSavedToken = state.hasSavedToken,
                        errorMessage = state.errorMessage,
                        onUrlChange = onUrlChange,
                        onPortChange = onPortChange,
                        onTokenChange = onTokenChange,
                        onPinChange = onPinChange,
                        onConnect = onConnect,
                        onPaste = pasteAddress,
                    )
                    RecoveryList(
                        state = state,
                        onSelectDevice = onSelectDevice,
                        onRemoveDevice = onRemoveDevice,
                    )
                }

                ConnectionPhase.Connecting -> ConnectingPanel(onCancel = onCancel)

                ConnectionPhase.Failed -> Column(
                    verticalArrangement = Arrangement.spacedBy(ProferSpacing.md),
                ) {
                    FailedPanel(
                        message = state.errorMessage.orEmpty(),
                        onRetry = onConnect,
                    )
                    // 失败后同样要能改地址重连：只留「重试 + 扫码」会把用户锁死在同一个错误地址上。
                    // 失败原因已由 FailedPanel 展示，这里默认展开表单直接给出修正入口。
                    ManualConnectPanel(
                        url = state.url,
                        port = state.port,
                        token = state.token,
                        pin = state.pin,
                        hasSavedToken = state.hasSavedToken,
                        errorMessage = null,
                        initiallyExpanded = true,
                        onUrlChange = onUrlChange,
                        onPortChange = onPortChange,
                        onTokenChange = onTokenChange,
                        onPinChange = onPinChange,
                        onConnect = onConnect,
                        onPaste = pasteAddress,
                    )
                    ScanAnotherDeviceCard(
                        pairing = state.pairing,
                        onScan = onScan,
                        onDismiss = onDismissPairing,
                    )
                    RecoveryList(
                        state = state,
                        onSelectDevice = onSelectDevice,
                        onRemoveDevice = onRemoveDevice,
                    )
                }
            }

            Spacer(modifier = Modifier.height(ProferSpacing.xxl))
        }
    }
}

@Composable
private fun ConnectedCard(
    state: ConnectionUiState,
    onDisconnect: () -> Unit,
) {
    val semantic = ProferThemeExtras.semantic
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(ProferSpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ProferSpacing.md),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DesktopWindows,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProferStatusDot(color = semantic.success, size = 6.dp)
                        Text(
                            text = state.deviceName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 7.dp),
                        )
                    }
                    Text(
                        text = state.deviceAddress,
                        style = ProferCodeTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = semantic.successContainer,
                ) {
                    Text(
                        text = if (state.latencyMs > 0) "${state.latencyMs}ms" else "在线",
                        style = MaterialTheme.typography.labelSmall,
                        color = semantic.onSuccessContainer,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            }
            ProferDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ProferSpacing.lg, vertical = ProferSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Profer ${state.daemonVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "断开连接",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .heightIn(min = ProferSizes.minTouchTarget)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onDisconnect)
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ConnectionDetails(state: ConnectionUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = ProferSpacing.lg, vertical = ProferSpacing.sm)) {
            DetailRow(label = "网络", value = state.networkLabel)
            ProferDivider()
            DetailRow(label = "协议", value = state.protocolLabel)
            ProferDivider()
            DetailRow(label = "最后同步", value = state.lastSyncLabel)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ManualConnectPanel(
    url: String,
    port: String,
    token: String,
    pin: String,
    hasSavedToken: Boolean,
    errorMessage: String?,
    onUrlChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onPinChange: (String) -> Unit,
    onConnect: () -> Unit,
    onPaste: () -> Unit,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    // 有错误就必须让用户看到原因：表单收起时错误信息等于被藏起来。
    LaunchedEffect(errorMessage) {
        if (!errorMessage.isNullOrBlank()) expanded = true
    }
    Column(verticalArrangement = Arrangement.spacedBy(ProferSpacing.md)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ProferSizes.minTouchTarget)
                .clickable { expanded = !expanded }
                .semantics {
                    contentDescription = if (expanded) "收起手动连接" else "展开手动连接"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "手动填写连接",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        if (!expanded) return@Column

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 52.dp)
                        .padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        BasicTextField(
                            value = url,
                            onValueChange = onUrlChange,
                            textStyle = ProferCodeTextStyle.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            singleLine = true,
                            decorationBox = { inner ->
                                if (url.isEmpty()) {
                                    Text(
                                        text = "电脑 IP 或域名",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            },
                        )
                    }
                    ProferIconAction(
                        icon = Icons.Outlined.ContentCopy,
                        contentDescription = "粘贴地址",
                        onClick = onPaste,
                    )
                }
            }
            Surface(
                modifier = Modifier.width(104.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(
                    modifier = Modifier
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = port,
                        onValueChange = onPortChange,
                        textStyle = ProferCodeTextStyle.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        decorationBox = { inner ->
                            if (port.isEmpty()) {
                                Text(
                                    text = "端口",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        },
                    )
                }
            }
        }

        Text(
            text = "自签证书填指纹；无 TLS 的隧道用 ws://",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Box(modifier = Modifier.weight(1f)) {
                    BasicTextField(
                        value = token,
                        onValueChange = onTokenChange,
                        textStyle = ProferCodeTextStyle.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        decorationBox = { inner ->
                            if (token.isEmpty()) {
                                Text(
                                    text = if (hasSavedToken) "Token 已保存，留空沿用" else "连接 Token",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        },
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Box(modifier = Modifier.weight(1f)) {
                    BasicTextField(
                        value = pin,
                        onValueChange = onPinChange,
                        textStyle = ProferCodeTextStyle.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        decorationBox = { inner ->
                            if (pin.isEmpty()) {
                                Text(
                                    text = "证书指纹（自签必填）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        },
                    )
                }
            }
        }

        Button(
            onClick = onConnect,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(text = "连接", fontWeight = FontWeight.SemiBold)
        }

    }
}

/** 扫码主入口：配对中显示进度，失败给出可操作原因。 */
@Composable
private fun ScanCard(
    pairing: PairingUi,
    onScan: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column {
        Button(
            onClick = onScan,
            enabled = pairing != PairingUi.Pairing,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = CircleShape,
        ) {
            Icon(
                imageVector = Icons.Outlined.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(text = "扫描二维码", modifier = Modifier.padding(start = 8.dp))
        }
        when (pairing) {
            PairingUi.Idle -> Unit
            PairingUi.Pairing -> Row(
                modifier = Modifier.padding(top = ProferSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.6.dp)
                Text(
                    text = "正在配对…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is PairingUi.Succeeded -> Text(
                text = "已绑定 ${pairing.deviceName}",
                style = MaterialTheme.typography.bodySmall,
                color = ProferThemeExtras.semantic.success,
                modifier = Modifier.padding(top = ProferSpacing.sm),
            )

            is PairingUi.Failed -> Row(
                modifier = Modifier
                    .padding(top = ProferSpacing.sm)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = pairing.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "知道了",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .heightIn(min = ProferSizes.minTouchTarget)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun EndpointFailureCard(failures: List<String>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
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
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 失败/断开时的排障区：端点尝试记录 + 已绑定设备，两个入口共用。 */
@Composable
private fun RecoveryList(
    state: ConnectionUiState,
    onSelectDevice: (String) -> Unit,
    onRemoveDevice: (String) -> Unit,
) {
    if (state.endpointFailures.isNotEmpty()) {
        EndpointFailureCard(state.endpointFailures)
    }
    if (state.devices.isNotEmpty()) {
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
    devices: List<DeviceUi>,    onSelectDevice: (String) -> Unit,
    onRemoveDevice: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ProferSpacing.xs)) {
        Text(
            text = "已绑定设备",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        devices.forEach { device ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ProferSizes.minTouchTarget)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !device.isActive) { onSelectDevice(device.id) },
                shape = RoundedCornerShape(12.dp),
                color = if (device.isActive) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = ProferSpacing.md, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${device.scopeLabel} · ${device.trustLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (device.isActive) {
                        Text(
                            text = "当前",
                            style = MaterialTheme.typography.labelSmall,
                            color = ProferThemeExtras.semantic.success,
                        )
                    }
                    Text(
                        text = "移除",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .heightIn(min = ProferSizes.minTouchTarget)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onRemoveDevice(device.id) }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectingPanel(onCancel: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(ProferSpacing.lg)) {
            Text(
                text = "正在连接",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(ProferSpacing.md))
            ConnectStep(label = "发现设备", state = StepState.Done)
            ConnectStep(label = "建立安全通道", state = StepState.Active)
            ConnectStep(label = "同步会话", state = StepState.Pending)
            Spacer(modifier = Modifier.height(ProferSpacing.md))
            Box(
                modifier = Modifier
                    .heightIn(min = ProferSizes.minTouchTarget)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "取消",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private enum class StepState { Done, Active, Pending }

@Composable
private fun ConnectStep(label: String, state: StepState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ProferSpacing.md),
    ) {
        Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            when (state) {
                StepState.Done -> Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = ProferThemeExtras.semantic.success,
                    modifier = Modifier.size(16.dp),
                )

                StepState.Active -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.6.dp,
                    color = MaterialTheme.colorScheme.tertiary,
                )

                StepState.Pending -> Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.outline, CircleShape),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state == StepState.Pending) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/**
 * 已连接或连接失败时的备用扫码入口：换一台电脑不必先手动断开。
 * 配对成功会直接切到新设备，当前链路由连接层切换。
 */
@Composable
private fun ScanAnotherDeviceCard(
    pairing: PairingUi,
    onScan: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(ProferSpacing.lg)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Outlined.QrCodeScanner,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "扫码连接另一台电脑",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            when (pairing) {
                PairingUi.Idle -> Unit
                PairingUi.Pairing -> Text(
                    text = "正在配对…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = ProferSpacing.sm),
                )

                is PairingUi.Succeeded -> Text(
                    text = "已绑定 ${pairing.deviceName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ProferThemeExtras.semantic.success,
                    modifier = Modifier.padding(top = ProferSpacing.sm),
                )

                is PairingUi.Failed -> Row(
                    modifier = Modifier
                        .padding(top = ProferSpacing.sm)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
                ) {
                    Text(
                        text = pairing.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "知道了",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .heightIn(min = ProferSizes.minTouchTarget)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                }
            }
            OutlinedButton(
                onClick = onScan,
                enabled = pairing != PairingUi.Pairing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = ProferSpacing.md)
                    .heightIn(min = 48.dp),
                shape = CircleShape,
            ) {
                Icon(
                    imageVector = Icons.Outlined.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(text = "扫描二维码", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun FailedPanel(message: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(modifier = Modifier.padding(ProferSpacing.lg)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "连接失败",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = ProferSpacing.sm),
            )
            Row(
                modifier = Modifier
                    .padding(top = ProferSpacing.md)
                    .heightIn(min = ProferSizes.minTouchTarget)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onRetry),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp),
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
