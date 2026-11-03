package com.profer.pocket.settings

import android.content.res.Configuration
import android.os.Build
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.profer.pocket.designsystem.adaptive.rememberProferWindowSize
import com.profer.pocket.designsystem.component.ProferBackdrop
import com.profer.pocket.designsystem.component.ProferDivider
import com.profer.pocket.designsystem.component.ProferHeaderRow
import com.profer.pocket.designsystem.component.ProferIconAction
import com.profer.pocket.designsystem.component.ProferMark
import com.profer.pocket.designsystem.component.ProferSectionLabel
import com.profer.pocket.designsystem.theme.ProferSizes
import com.profer.pocket.designsystem.theme.ProferSpacing
import com.profer.pocket.designsystem.theme.ProferTheme

enum class ThemePreference(val label: String) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色"),
}

/** 设置分组。平板左侧导航按这个顺序排，手机仍是一列长滚动。 */
private enum class SettingsSection(val label: String) {
    Appearance("外观"),
    Update("更新"),
    Notifications("通知"),
    Connection("连接"),
    About("关于"),
    Developer("开发者"),
}

/** 设置页参数集合：两栏时右侧只渲染一个分组，参数要能整体传给分组卡片。 */
private data class SettingsParams(
    val themePreference: ThemePreference,
    val onThemePreferenceChange: (ThemePreference) -> Unit,
    val updateLabel: String?,
    val onCheckUpdate: () -> Unit,
    val internalUpdateLabel: String?,
    val notificationsEnabled: Boolean,
    val onNotificationsChange: (Boolean) -> Unit,
    /** 系统是否允许本应用发布提升通知；null 表示系统不支持（API < 36）。 */
    val liveUpdateStatus: Boolean?,
    /** 本机对这个功能的叫法（流体云 / 超级岛 / 原子岛…），只用于文案。 */
    val liveUpdateName: String,
    val onOpenLiveUpdateSettings: () -> Unit,
    val permissionAlertsEnabled: Boolean,
    val onPermissionAlertsChange: (Boolean) -> Unit,
    val autoReconnect: Boolean,
    val onAutoReconnectChange: (Boolean) -> Unit,
    val backgroundConnection: Boolean,
    val onBackgroundConnectionChange: (Boolean) -> Unit,
    /** 是否已在系统电池白名单里；false 时后台会被冻结。 */
    val batteryUnrestricted: Boolean,
    val onOpenBatterySettings: () -> Unit,
    val connected: Boolean,
    val deviceLabel: String,
    val onOpenConnection: () -> Unit,
    val versionLabel: String,
    val onVersionTap: () -> Unit,
    val showDeveloperTools: Boolean,
    val onOpenStressTest: () -> Unit,
)

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
    // 从系统设置返回时要重新读：用户可能刚打开「实时活动」开关。
    var liveUpdateStatus by remember { mutableStateOf(LiveUpdateEntry.canPostPromoted(context)) }
    val liveUpdateName = remember(context) { LiveUpdateEntry.capability(context).displayName }
    var batteryUnrestricted by remember { mutableStateOf(BatteryOptimizationEntry.isUnrestricted(context)) }
    LifecycleResumeEffect(Unit) {
        liveUpdateStatus = LiveUpdateEntry.canPostPromoted(context)
        batteryUnrestricted = BatteryOptimizationEntry.isUnrestricted(context)
        onPauseOrDispose { }
    }
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
    val params = SettingsParams(
        themePreference = themePreference,
        onThemePreferenceChange = onThemePreferenceChange,
        updateLabel = updateLabel,
        onCheckUpdate = onCheckUpdate,
        internalUpdateLabel = internalUpdateLabel,
        notificationsEnabled = notificationsEnabled,
        onNotificationsChange = onNotificationsChange,
        liveUpdateStatus = liveUpdateStatus,
        liveUpdateName = liveUpdateName,
        onOpenLiveUpdateSettings = { LiveUpdateEntry.openSettings(context) },
        permissionAlertsEnabled = permissionAlertsEnabled,
        onPermissionAlertsChange = onPermissionAlertsChange,
        autoReconnect = autoReconnect,
        onAutoReconnectChange = onAutoReconnectChange,
        backgroundConnection = backgroundConnection,
        onBackgroundConnectionChange = onBackgroundConnectionChange,
        batteryUnrestricted = batteryUnrestricted,
        onOpenBatterySettings = { BatteryOptimizationEntry.openSettings(context) },
        connected = connected,
        deviceLabel = deviceLabel,
        onOpenConnection = onOpenConnection,
        versionLabel = versionLabel,
        onVersionTap = {
            // 连点 10 次才走 AList 测试通道；平时只是普通点击。
            versionTapCount += 1
            if (versionTapCount >= 10) {
                versionTapCount = 0
                onCheckInternalUpdate()
            }
        },
        showDeveloperTools = showDeveloperTools,
        onOpenStressTest = onOpenStressTest,
    )
    val sections = remember(showDeveloperTools) {
        SettingsSection.entries.filter { it != SettingsSection.Developer || showDeveloperTools }
    }
    var selected by rememberSaveable { mutableStateOf(SettingsSection.Appearance) }
    val twoPane = rememberProferWindowSize().isTwoPane

    ProferBackdrop(modifier = modifier) {
        // 平板/桌面下顶栏与内容一起居中；两栏时整页放宽到 1120dp。
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Scaffold(
                modifier = Modifier
                    .widthIn(max = if (twoPane) 1120.dp else ProferSizes.contentMaxWidth)
                    .fillMaxHeight(),
                containerColor = Color.Transparent,
                topBar = {
                    ProferHeaderRow {
                        ProferIconAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onBack)
                        Text(
                            "设置",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(horizontal = ProferSpacing.md),
                        )
                    }
                },
            ) { innerPadding ->
                if (twoPane) {
                    SettingsTwoPane(
                        sections = sections,
                        selected = selected,
                        onSelect = { selected = it },
                        params = params,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding),
                    )
                } else {
                    SettingsSinglePane(
                        sections = sections,
                        params = params,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding),
                    )
                }
            }
        }
    }
}

/**
 * 平板两栏：左侧分组导航（220dp），右侧只渲染当前分组。
 * 手机是一列长滚动，平板再照搬会让人滚十屏找「通知」。
 */
@Composable
private fun SettingsTwoPane(
    sections: List<SettingsSection>,
    selected: SettingsSection,
    onSelect: (SettingsSection) -> Unit,
    params: SettingsParams,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        Column(
            modifier = Modifier
                .width(220.dp)
                .fillMaxHeight()
                .padding(horizontal = ProferSpacing.sm, vertical = ProferSpacing.md),
            verticalArrangement = Arrangement.spacedBy(ProferSpacing.xxs),
        ) {
            sections.forEach { section ->
                SettingsNavItem(
                    label = section.label,
                    selected = section == selected,
                    onClick = { onSelect(section) },
                )
            }
        }
        Box(
            Modifier
                .width(ProferSizes.hairline)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            // 宽屏下右栏比内容宽：居中，避免内容贴在分隔线、右边空一大块。
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ProferSpacing.xl, vertical = ProferSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(ProferSpacing.md),
            ) {
                Text(
                    text = "让 Profer 按你的习惯工作。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingsSectionCard(selected, params)
            }
        }
    }
}

@Composable
private fun SettingsNavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ProferSizes.minTouchTarget)
            .clip(MaterialTheme.shapes.large)
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            )
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = ProferSpacing.md, vertical = ProferSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun SettingsSinglePane(
    sections: List<SettingsSection>,
    params: SettingsParams,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ProferSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
    ) {
        Text(
            "让 Profer 按你的习惯工作。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        sections.forEach { section ->
            SectionTitle(section.label)
            SettingsSectionCard(section, params)
        }
        Spacer(modifier = Modifier.height(ProferSpacing.lg))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = ProferSpacing.xxl),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProferMark(size = ProferSizes.iconSmall)
            Text(
                text = "Profer 安卓原生版",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = ProferSpacing.sm),
            )
        }
    }
}

/** 单个分组的内容面；手机与平板共用，避免两套设置项各写一遍。 */
@Composable
private fun SettingsSectionCard(section: SettingsSection, params: SettingsParams) {
    when (section) {
        SettingsSection.Appearance -> SettingsCard {
            Column(Modifier.selectableGroup()) {
                ThemePreference.entries.forEachIndexed { index, preference ->
                    if (index > 0) ProferDivider(insetStart = ProferSpacing.lg)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = ProferSizes.listItemHeight)
                            .selectable(
                                selected = preference == params.themePreference,
                                role = Role.RadioButton,
                                onClick = { params.onThemePreferenceChange(preference) },
                            )
                            .padding(horizontal = ProferSpacing.lg, vertical = ProferSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ProferSpacing.md),
                    ) {
                        Text(
                            preference.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        RadioButton(selected = preference == params.themePreference, onClick = null)
                    }
                }
            }
        }

        SettingsSection.Update -> SettingsCard {
            SettingsActionRow(
                title = "检查更新",
                subtitle = params.updateLabel ?: "获取最新版本与改进",
                onClick = params.onCheckUpdate,
            )
            if (params.internalUpdateLabel != null) {
                ProferDivider(insetStart = ProferSpacing.lg)
                SettingsValueRow(title = "测试通道", value = params.internalUpdateLabel)
            }
        }

        SettingsSection.Notifications -> SettingsCard {
            SettingsSwitchRow(
                title = "任务完成通知",
                checked = params.notificationsEnabled,
                onCheckedChange = params.onNotificationsChange,
            )
            ProferDivider(insetStart = ProferSpacing.lg)
            SettingsSwitchRow(
                title = "需要你决定时提醒",
                checked = params.permissionAlertsEnabled,
                onCheckedChange = params.onPermissionAlertsChange,
            )
            if (params.liveUpdateStatus != null) {
                ProferDivider(insetStart = ProferSpacing.lg)
                SettingsActionRow(
                    title = "状态栏实时活动",
                    subtitle = if (params.liveUpdateStatus) {
                        "${params.liveUpdateName} · 已开启"
                    } else {
                        "开启后对话进度显示在${params.liveUpdateName}"
                    },
                    onClick = params.onOpenLiveUpdateSettings,
                )
            }
        }

        SettingsSection.Connection -> SettingsCard {
            SettingsSwitchRow(
                title = "自动重连",
                checked = params.autoReconnect,
                onCheckedChange = params.onAutoReconnectChange,
            )
            ProferDivider(insetStart = ProferSpacing.lg)
            SettingsSwitchRow(
                title = "后台保持连接",
                checked = params.backgroundConnection,
                onCheckedChange = params.onBackgroundConnectionChange,
            )
            ProferDivider(insetStart = ProferSpacing.lg)
            SettingsActionRow(
                title = "后台运行白名单",
                subtitle = if (params.batteryUnrestricted) {
                    "已允许后台运行"
                } else {
                    "系统会冻结后台进程，状态栏可能停在「执行中」"
                },
                onClick = params.onOpenBatterySettings,
            )
            ProferDivider(insetStart = ProferSpacing.lg)
            SettingsActionRow(
                title = if (params.connected) "已连接的设备" else "连接电脑",
                subtitle = if (params.connected) params.deviceLabel else "当前离线，扫码或手动连接以继续",
                onClick = params.onOpenConnection,
            )
        }

        SettingsSection.About -> SettingsCard {
            SettingsValueRow(
                title = "版本",
                value = params.versionLabel,
                onClick = params.onVersionTap,
            )
            ProferDivider(insetStart = ProferSpacing.lg)
            SettingsValueRow(title = "协议", value = "Pocket WS v1")
        }

        SettingsSection.Developer -> SettingsCard {
            SettingsActionRow(
                title = "长列表滚动检查",
                subtitle = "240 条消息的滚动位置与性能",
                onClick = params.onOpenStressTest,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    ProferSectionLabel(
        text = text,
        modifier = Modifier.padding(
            start = ProferSpacing.xs,
            end = ProferSpacing.xs,
            top = ProferSpacing.lg,
        ),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ProferSizes.listItemHeight)
            .clip(MaterialTheme.shapes.large)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = ProferSpacing.md, vertical = ProferSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null)
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ProferSizes.listItemHeight)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = ProferSpacing.lg, vertical = ProferSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(modifier = Modifier.padding(start = ProferSpacing.sm)) {
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
private fun SettingsValueRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ProferSizes.listItemHeight)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = ProferSpacing.lg, vertical = ProferSpacing.md),
        verticalArrangement = Arrangement.spacedBy(ProferSpacing.xs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "设置 · 浅色", showBackground = true)
@Preview(name = "设置 · 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "设置 · 大字号", showBackground = true, fontScale = 2f)
@Composable
private fun SettingsPreview() {
    ProferTheme(darkTheme = isSystemInDarkTheme()) {
        SettingsScreen(
            themePreference = ThemePreference.System, onThemePreferenceChange = {},
            notificationsEnabled = true, onNotificationsChange = {},
            permissionAlertsEnabled = true, onPermissionAlertsChange = {},
            autoReconnect = true, onAutoReconnectChange = {},
            backgroundConnection = false, onBackgroundConnectionChange = {},
            connected = false, deviceLabel = "MacBook Pro", onOpenConnection = {}, onBack = {},
        )
    }
}
