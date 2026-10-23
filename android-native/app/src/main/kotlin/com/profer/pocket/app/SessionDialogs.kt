package com.profer.pocket.app

import androidx.compose.runtime.Composable
import com.profer.pocket.sessions.DeleteSessionDialog
import com.profer.pocket.sessions.MoveSessionSheet
import com.profer.pocket.sessions.NewSessionSheet
import com.profer.pocket.sessions.ProjectOption
import com.profer.pocket.sessions.RenameSessionDialog

/** 会话/项目系统的弹层状态：只由 :app 持有，展示层不保存业务状态。 */
sealed interface SessionDialog {
    data object NewSession : SessionDialog

    data class Rename(val sessionId: String, val title: String) : SessionDialog

    data class Move(
        val sessionId: String,
        val title: String,
        val workspaceId: String?,
    ) : SessionDialog

    data class Delete(val sessionId: String, val title: String) : SessionDialog
}

@Composable
fun SessionDialogHost(
    dialog: SessionDialog?,
    projects: List<ProjectOption>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onNewSession: (String?) -> Unit,
    onRename: (String, String) -> Unit,
    onMove: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    when (dialog) {
        null -> Unit

        SessionDialog.NewSession -> NewSessionSheet(
            projects = projects,
            onSelect = onNewSession,
            onDismiss = onDismiss,
        )

        is SessionDialog.Rename -> RenameSessionDialog(
            initialTitle = dialog.title,
            busy = busy,
            onConfirm = { title -> onRename(dialog.sessionId, title) },
            onDismiss = onDismiss,
        )

        is SessionDialog.Move -> MoveSessionSheet(
            projects = projects,
            currentWorkspaceId = dialog.workspaceId,
            onSelect = { workspaceId -> onMove(dialog.sessionId, workspaceId) },
            onDismiss = onDismiss,
        )

        is SessionDialog.Delete -> DeleteSessionDialog(
            title = dialog.title,
            busy = busy,
            onConfirm = { onDelete(dialog.sessionId) },
            onDismiss = onDismiss,
        )
    }
}
