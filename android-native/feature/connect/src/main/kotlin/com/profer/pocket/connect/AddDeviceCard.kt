package com.profer.pocket.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.profer.pocket.designsystem.component.ProferIconAction
import com.profer.pocket.designsystem.theme.ProferSizes
import com.profer.pocket.designsystem.theme.ProferSpacing
import com.profer.pocket.designsystem.theme.ProferThemeExtras

/**
 * 添加设备的唯一入口：扫码与手动输入并列，任何连接阶段都可达。
 *
 * 之前已连接状态只给了「扫码连接另一台电脑」，手动地址 + Token 藏在未连接页，
 * 于是连第二台电脑时输入入口直接消失。这里两个方式同级常驻，扫码不必先选模式。
 */
@Composable
internal fun AddDeviceCard(
    phase: ConnectionPhase,
    deviceCount: Int,
    pairing: PairingUi,
    url: String,
    port: String,
    token: String,
    pin: String,
    hasSavedToken: Boolean,
    errorMessage: String?,
    onScan: () -> Unit,
    onUrlChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onPinChange: (String) -> Unit,
    onConnect: () -> Unit,
    onPaste: () -> Unit,
    onClearForm: () -> Unit,
    onDismissPairing: () -> Unit,
) {
    // 阶段变化（连上 / 失败）重新按该阶段的默认展开状态排版，避免表单在切机后留在屏上。
    var manualOpen by rememberSaveable(phase.name) {
        mutableStateOf(ConnectionLayout.manualExpandedByDefault(phase))
    }
    LaunchedEffect(errorMessage) {
        if (!errorMessage.isNullOrBlank()) manualOpen = true
    }

    val connected = phase == ConnectionPhase.Connected
    val formDirty = url.isNotBlank() || port.isNotBlank() || token.isNotBlank() || pin.isNotBlank()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(ProferSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(ProferSpacing.md),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(ProferSpacing.xs)) {
                Text(
                    text = if (deviceCount == 0) "连接电脑" else "添加另一台电脑",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (deviceCount == 0) {
                        "扫码配对，或输入电脑地址与 Token。"
                    } else {
                        "扫码配对，或输入地址与 Token；连接后可直接切换，不必先断开当前设备。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 首次连接把扫码当主动作；已经有设备时两个入口同级，避免把手动输入压成次要选项。
            // 2× 字号下两个半宽按钮会把文案挤成三行，此时改为上下通栏。
            val stackActions = deviceCount > 0 && LocalDensity.current.fontScale >= 1.5f
            if (deviceCount == 0) {
                Button(
                    onClick = onScan,
                    enabled = pairing != PairingUi.Pairing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = ProferSizes.minTouchTarget),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(ProferSizes.icon))
                    Text("扫描二维码", modifier = Modifier.padding(start = ProferSpacing.sm))
                }
                OutlinedButton(
                    onClick = { manualOpen = !manualOpen },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ProferSizes.minTouchTarget)
                        .semantics { contentDescription = if (manualOpen) "收起手动输入" else "展开手动输入" },
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Icon(Icons.Outlined.Keyboard, contentDescription = null, modifier = Modifier.size(ProferSizes.icon))
                    Text("手动输入地址与 Token", modifier = Modifier.padding(start = ProferSpacing.sm))
                }
            } else if (stackActions) {
                Column(verticalArrangement = Arrangement.spacedBy(ProferSpacing.sm)) {
                    ScanDeviceButton(
                        pairing = pairing,
                        onScan = onScan,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ManualToggleButton(
                        manualOpen = manualOpen,
                        onToggle = { manualOpen = !manualOpen },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm)) {
                    ScanDeviceButton(
                        pairing = pairing,
                        onScan = onScan,
                        modifier = Modifier.weight(1f),
                    )
                    ManualToggleButton(
                        manualOpen = manualOpen,
                        onToggle = { manualOpen = !manualOpen },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (manualOpen) {
                if (!errorMessage.isNullOrBlank()) {
                    Text(errorMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                ManualConnectionFields(
                    url = url,
                    port = port,
                    token = token,
                    pin = pin,
                    hasSavedToken = hasSavedToken,
                    onUrlChange = onUrlChange,
                    onPortChange = onPortChange,
                    onTokenChange = onTokenChange,
                    onPinChange = onPinChange,
                    onPaste = onPaste,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // 凭据只按地址复用：换到另一台电脑必须重新填 Token，不能拿当前设备的凭据顶上。
                        text = if (hasSavedToken && token.isBlank() && url.isNotBlank()) "Token 留空时沿用该地址已保存的凭据" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (formDirty) {
                        TextButton(
                            onClick = onClearForm,
                            modifier = Modifier.heightIn(min = ProferSizes.minTouchTarget),
                        ) { Text("清空") }
                    }
                }
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth().heightIn(min = ProferSizes.minTouchTarget),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text(if (connected) "连接并切换到此设备" else "连接电脑") }
            }

            PairingStatus(pairing, onDismissPairing)
        }
    }
}

@Composable
private fun ScanDeviceButton(pairing: PairingUi, onScan: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onScan,
        enabled = pairing != PairingUi.Pairing,
        modifier = modifier.heightIn(min = ProferSizes.minTouchTarget),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(ProferSizes.iconSmall))
        Text("扫码配对", modifier = Modifier.padding(start = ProferSpacing.xs))
    }
}

@Composable
private fun ManualToggleButton(manualOpen: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onToggle,
        modifier = modifier
            .heightIn(min = ProferSizes.minTouchTarget)
            .semantics { contentDescription = if (manualOpen) "收起手动输入" else "展开手动输入" },
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Icon(Icons.Outlined.Keyboard, contentDescription = null, modifier = Modifier.size(ProferSizes.iconSmall))
        Text("手动输入", modifier = Modifier.padding(start = ProferSpacing.xs))
    }
}

@Composable
private fun ManualConnectionFields(
    url: String,
    port: String,
    token: String,
    pin: String,
    hasSavedToken: Boolean,
    onUrlChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onPinChange: (String) -> Unit,
    onPaste: () -> Unit,
) {
    // 全宽输入与持久标签：地址、端口不再在窄屏/大字号下互相挤压。
    OutlinedTextField(
        value = url, onValueChange = onUrlChange,
        label = { Text("电脑地址") },
        placeholder = { Text("IP 或域名") },
        trailingIcon = { ProferIconAction(Icons.Outlined.ContentCopy, "粘贴地址", onPaste) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        singleLine = true, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = port, onValueChange = onPortChange,
        label = { Text("端口") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = token, onValueChange = onTokenChange,
        label = { Text("连接 Token") },
        supportingText = { if (hasSavedToken) Text("该地址已保存过 Token 时可留空") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = pin, onValueChange = onPinChange,
        label = { Text("证书指纹") },
        supportingText = { Text("自签证书必填；无 TLS 的隧道请用 ws:// 地址。") },
        singleLine = true, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 配对状态独占整行：大字号时不和恢复按钮抢空间。 */
@Composable
internal fun PairingStatus(pairing: PairingUi, onDismiss: () -> Unit) {
    when (pairing) {
        PairingUi.Idle -> Unit
        PairingUi.Pairing -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(ProferSizes.iconSmall), strokeWidth = ProferSizes.hairline)
            Text("正在配对…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is PairingUi.Succeeded -> Text("已绑定 ${pairing.deviceName}", style = MaterialTheme.typography.bodyMedium, color = ProferThemeExtras.semantic.success)
        is PairingUi.Failed -> Column(verticalArrangement = Arrangement.spacedBy(ProferSpacing.xs)) {
            Text(pairing.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = ProferSizes.minTouchTarget)) { Text("知道了") }
        }
    }
}
