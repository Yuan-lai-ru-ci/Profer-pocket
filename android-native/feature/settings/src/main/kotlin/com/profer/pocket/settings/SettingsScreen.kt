package com.profer.pocket.settings

import android.os.Build
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.component.ProferDivider
import com.profer.pocket.designsystem.component.ProferIconAction
import com.profer.pocket.designsystem.component.ProferMark
import com.profer.pocket.designsystem.component.ProferSectionLabel
import com.profer.pocket.designsystem.component.ProferStatusDot
import com.profer.pocket.designsystem.theme.ProferSpacing
import com.profer.pocket.designsystem.theme.ProferThemeExtras

enum class ThemePreference(val label: String) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit,
    notificationsEnabled: Boolean,
    onNotificationsChange: (Boolean) -> Unit,
    permissionAlertsEnabled: Boolean,
    onPermissionAlertsChange: (Boolean) -> Unit,
    autoReconnect: Boolean,
    onAutoReconnectChange: (Boolean) -> Unit,
    backgroundConnection: Boolean,
    onBackgroundConnectionChange: (Boolean) -> Unit,
    connected: Boolean,
    deviceLabel: String,
    onOpenConnection: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showDeveloperTools: Boolean = false,
    onOpenStressTest: () -> Unit = {},
    updateLabel: String? = null,
    onCheckUpdate: () -> Unit = {},
    internalUpdateLabel: String? = null,
    onCheckInternalUpdate: () -> Unit = {},
) {
    var versionTapCount by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val versionLabel = remember(context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val name = info.versionName.orEmpty().ifBlank { "未知" }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "$name ($code)"
        }.getOrNull() ?: "未知"
    }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                navigationIcon = {
                    ProferIconAction(
                        icon = Icons.Outlined.ArrowBack,
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
                .navigationBarsPadding()
                .padding(horizontal = ProferSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
        ) {
            SectionTitle("外观")
            SettingsCard {
                Column(modifier = Modifier.padding(ProferSpacing.md)) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ThemePreference.entries.forEachIndexed { index, preference ->
                            SegmentedButton(
                                selected = preference == themePreference,
                                onClick = { onThemePreferenceChange(preference) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = ThemePreference.entries.size,
                                ),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            ) {
                                Text(
                                    text = preference.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Text(
                        text = "浅色是云朵舞者的暖纸底，深色是同色相的暖墨；两套都用同一支砖红主操作。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = ProferSpacing.sm, start = 2.dp, end = 2.dp),
                    )
                }
            }

            SectionTitle("更新")
            SettingsCard {
                SettingsActionRow(
                    title = "检查更新",
                    subtitle = updateLabel ?: "从 GitHub 检查正式版",
                    onClick = onCheckUpdate,
                )
                if (internalUpdateLabel != null) {
                    ProferDivider(insetStart = 16.dp)
                    SettingsValueRow(title = "测试通道", value = internalUpdateLabel)
                }
            }

            SectionTitle("通知")
            SettingsCard {
                SettingsSwitchRow(
                    title = "任务完成通知",
                    subtitle = "会话在后台跑完时提醒我",
                    checked = notificationsEnabled,
                    onCheckedChange = onNotificationsChange,
                )
                ProferDivider(insetStart = 16.dp)
                SettingsSwitchRow(
                    title = "需要你决定时提醒",
                    subtitle = "权限请求、计划确认不会被静音",
                    checked = permissionAlertsEnabled,
                    onCheckedChange = onPermissionAlertsChange,
                )
            }

            SectionTitle("连接")
            SettingsCard {
                SettingsSwitchRow(
                    title = "自动重连",
                    subtitle = "断网或网络恢复时立即重试；连不上电脑时最长隔 60 秒重试一次",
                    checked = autoReconnect,
                    onCheckedChange = onAutoReconnectChange,
                )
                ProferDivider(insetStart = 16.dp)
                SettingsSwitchRow(
                    title = "后台保持连接",
                    subtitle = "常驻通知栏，锁屏后仍能接收结果",
                    checked = backgroundConnection,
                    onCheckedChange = onBackgroundConnectionChange,
                )
                ProferDivider(insetStart = 16.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onOpenConnection)
                        .heightIn(min = 60.dp)
                        .padding(horizontal = ProferSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ProferSpacing.md),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DesktopWindows,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (connected) "已连接的设备" else "未连接",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ProferStatusDot(
                                color = if (connected) {
                                    ProferThemeExtras.semantic.success
                                } else {
                                    ProferThemeExtras.semantic.warning
                                },
                                size = 5.dp,
                            )
                            Text(
                                text = if (connected) deviceLabel else "去连接一台 Mac",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            SectionTitle("关于")
            SettingsCard {
                SettingsValueRow(
                    title = "版本",
                    value = versionLabel,
                    onClick = {
                        // 连点 10 次才走 AList 测试通道；平时只是普通点击。
                        versionTapCount += 1
                        if (versionTapCount >= 10) {
                            versionTapCount = 0
                            onCheckInternalUpdate()
                        }
                    },
                )
                ProferDivider(insetStart = 16.dp)
                SettingsValueRow(title = "协议", value = "Pocket WS v1")
            }

            if (showDeveloperTools) {
                SectionTitle("开发者")
                SettingsCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onOpenStressTest)
                            .heightIn(min = 60.dp)
                            .padding(horizontal = ProferSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "长列表滚动检查",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "240 条会话，验证滚动不跳、不丢位置",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(ProferSpacing.lg))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = ProferSpacing.xxl),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProferMark(size = 18.dp)
                Text(
                    text = "Profer 安卓原生版",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    ProferSectionLabel(
        text = text,
        modifier = Modifier.padding(
            start = 2.dp,
            end = 2.dp,
            top = ProferSpacing.md,
        ),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = ProferSpacing.md, vertical = ProferSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null)
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = ProferSpacing.md, vertical = ProferSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(modifier = Modifier.padding(start = ProferSpacing.sm)) {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onTertiary,
                    checkedTrackColor = MaterialTheme.colorScheme.tertiary,
                    checkedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                ),
            )
        }
    }
}

@Composable
private fun SettingsValueRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = ProferSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
