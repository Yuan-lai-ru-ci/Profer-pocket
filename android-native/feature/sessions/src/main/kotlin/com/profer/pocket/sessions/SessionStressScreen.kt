package com.profer.pocket.sessions

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.profer.pocket.designsystem.component.ProferIconAction

/**
 * 仅 debug 使用：240 条会话的滚动检查页。
 * 用来验证 LazyColumn 的 key、contentType 与滚动位置在长列表下稳定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionStressScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val projects = remember {
        List(24) { projectIndex ->
            ProjectGroup(
                id = "stress-$projectIndex",
                name = "project-${projectIndex + 1}",
                branchLabel = "main",
                sessions = List(10) { index ->
                    val ordinal = projectIndex * 10 + index
                    SessionItem(
                        id = "stress-$projectIndex-$index",
                        title = "压力测试会话 ${ordinal + 1}",
                        timeLabel = "${index + 1} 小时前",
                        running = ordinal % 17 == 0,
                    )
                },
            )
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "长列表 · 240 条",
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
        SessionListContent(
            state = SessionListState(
                projects = projects,
                connected = true,
                currentSessionId = "stress-0-0",
            ),
            onQueryChange = {},
            onSelectSession = {},
            onNewSession = {},
            onOpenConnection = {},
            onOpenSettings = {},
            showHeader = false,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
