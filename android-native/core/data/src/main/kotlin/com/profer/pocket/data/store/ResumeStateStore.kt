package com.profer.pocket.data.store

import android.content.Context

/** 事件游标持久化：与服务端实例绑定，进程重启后仍可增量补发。 */
class AndroidResumeStateStore(context: Context) : ResumeStateStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(endpoint: String): ResumeStateStore.Cursor? {
        val key = keyOf(endpoint)
        val lastEventId = prefs.getLong("$key.cursor", 0L)
        val instance = prefs.getString("$key.instance", null)
        if (lastEventId <= 0 && instance.isNullOrBlank()) return null
        return ResumeStateStore.Cursor(lastEventId = lastEventId, serverInstanceId = instance)
    }

    override fun save(endpoint: String, lastEventId: Long, serverInstanceId: String?) {
        val key = keyOf(endpoint)
        prefs.edit()
            .putLong("$key.cursor", lastEventId)
            .putString("$key.instance", serverInstanceId)
            .apply()
    }

    override fun clear(endpoint: String) {
        val key = keyOf(endpoint)
        prefs.edit().remove("$key.cursor").remove("$key.instance").apply()
    }

    private fun keyOf(endpoint: String): String = "resume." + endpoint.hashCode().toUInt().toString(16)

    private companion object {
        const val PREFS_NAME = "profer_pocket_state"
    }
}
