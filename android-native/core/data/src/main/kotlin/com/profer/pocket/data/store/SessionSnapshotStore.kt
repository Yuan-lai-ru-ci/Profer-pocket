package com.profer.pocket.data.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 会话列表离线快照：冷启动没连上时也能看到上次的会话。 */
interface SessionSnapshotStore {
    data class Snapshot(
        val sessions: List<com.profer.pocket.model.SessionSummary> = emptyList(),
        val workspaces: List<com.profer.pocket.model.WorkspaceSummary> = emptyList(),
    )

    fun load(): Snapshot

    fun save(snapshot: Snapshot)
}

/**
 * 会话目录快照落独立文件，不再塞进 SharedPreferences。
 *
 * 875 条会话的 JSON 放在 SharedPreferences 里会让每次进程启动解析一个几百 KB 的 XML，
 * 且 apply() 的异步写会与主线程抢锁；文件读写 + 后台调度才是这个数据量该有的方式。
 * 旧版 SharedPreferences 数据在首次读取时自动迁移一次。
 */
class AndroidSessionSnapshotStore(context: Context) : SessionSnapshotStore {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)

    override fun load(): SessionSnapshotStore.Snapshot {
        val raw = try {
            if (file.isFile) file.readText() else legacyRaw()
        } catch (_: Exception) {
            null
        } ?: return SessionSnapshotStore.Snapshot()
        return try {
            val json = JSONObject(raw)
            SessionSnapshotStore.Snapshot(
                sessions = parseSessions(json.optJSONArray("sessions")),
                workspaces = parseWorkspaces(json.optJSONArray("workspaces")),
            ).also {
                // 从旧 SharedPreferences 迁移过来的数据立刻落到文件，下次启动不再解析 XML。
                if (!file.isFile) {
                    runCatching { file.parentFile?.mkdirs(); file.writeText(raw) }
                }
            }
        } catch (_: Exception) {
            SessionSnapshotStore.Snapshot()
        }
    }

    private fun legacyRaw(): String? {
        val prefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(LEGACY_KEY_SNAPSHOT, null) ?: return null
        prefs.edit().remove(LEGACY_KEY_SNAPSHOT).apply()
        return raw
    }

    override fun save(snapshot: SessionSnapshotStore.Snapshot) {
        val json = JSONObject().apply {
            put("sessions", JSONArray().apply {
                snapshot.sessions.forEach { session ->
                    put(JSONObject().apply {
                        put("id", session.id)
                        put("title", session.title)
                        put("workspaceId", session.workspaceId ?: JSONObject.NULL)
                        put("channelId", session.channelId ?: JSONObject.NULL)
                        put("modelId", session.modelId ?: JSONObject.NULL)
                        put("presetId", session.presetId ?: JSONObject.NULL)
                        put("permissionMode", session.permissionMode ?: JSONObject.NULL)
                        put("createdAt", session.createdAt)
                        put("updatedAt", session.updatedAt)
                        put("revision", session.revision)
                        session.active?.let { put("active", it) }
                        session.unread?.let { put("unread", it) }
                        session.archived?.let { put("archived", it) }
                        session.pinned?.let { put("pinned", it) }
                        session.draft?.let { put("draft", it) }
                    })
                }
            })
            put("workspaces", JSONArray().apply {
                snapshot.workspaces.forEach { workspace ->
                    put(JSONObject().apply {
                        put("id", workspace.id)
                        put("name", workspace.name)
                        put("slug", workspace.slug ?: JSONObject.NULL)
                        put("type", workspace.type ?: JSONObject.NULL)
                        put("archived", workspace.archived)
                        put("createdAt", workspace.createdAt)
                        put("updatedAt", workspace.updatedAt)
                    })
                }
            })
        }
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) {
                file.writeText(json.toString())
                temp.delete()
            }
        } catch (_: Exception) {
            // 快照只是离线兜底：写失败不能让目录操作失败。
        }
    }

    private fun parseSessions(array: JSONArray?): List<com.profer.pocket.model.SessionSummary> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id", "")
                if (id.isBlank()) continue
                add(
                    com.profer.pocket.model.SessionSummary(
                        id = id,
                        title = item.optString("title", ""),
                        workspaceId = item.optStringOrNull("workspaceId"),
                        channelId = item.optStringOrNull("channelId"),
                        modelId = item.optStringOrNull("modelId"),
                        presetId = item.optStringOrNull("presetId"),
                        permissionMode = item.optStringOrNull("permissionMode"),
                        createdAt = item.optLong("createdAt", 0L),
                        updatedAt = item.optLong("updatedAt", 0L),
                        revision = item.optInt("revision", 0),
                        active = item.optBooleanOrNull("active"),
                        unread = item.optBooleanOrNull("unread"),
                        archived = item.optBooleanOrNull("archived"),
                        pinned = item.optBooleanOrNull("pinned"),
                        draft = item.optBooleanOrNull("draft"),
                    ),
                )
            }
        }
    }

    private fun parseWorkspaces(array: JSONArray?): List<com.profer.pocket.model.WorkspaceSummary> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id", "")
                if (id.isBlank()) continue
                add(
                    com.profer.pocket.model.WorkspaceSummary(
                        id = id,
                        name = item.optString("name", ""),
                        slug = item.optStringOrNull("slug"),
                        type = item.optStringOrNull("type"),
                        archived = item.optBoolean("archived", false),
                        createdAt = item.optLong("createdAt", 0L),
                        updatedAt = item.optLong("updatedAt", 0L),
                    ),
                )
            }
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
        if (!has(key) || isNull(key)) null else optBoolean(key, false)

    private companion object {
        const val FILE_NAME = "catalog-snapshot.json"
        const val LEGACY_PREFS_NAME = "profer_pocket_state"
        const val LEGACY_KEY_SNAPSHOT = "session_snapshot"
    }
}
