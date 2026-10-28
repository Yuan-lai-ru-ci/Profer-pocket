package com.profer.pocket.data.store

import android.content.Context

/**
 * 常用项目的本地置顶。
 *
 * 只影响手机端抽屉里项目的排列顺序，不写回桌面、不进入远程协议：
 * 桌面的项目顺序是产品结构，手机端需要的只是「我先看哪几个」。
 * 项目被删除后这里会残留 id，读取方按当前项目列表过滤即可。
 */
interface ProjectPinStore {
    fun load(): Set<String>

    fun save(ids: Set<String>)
}

class AndroidProjectPinStore(context: Context) : ProjectPinStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): Set<String> =
        prefs.getStringSet(KEY_PINNED, emptySet())
            .orEmpty()
            .filter { it.isNotBlank() }
            .toSet()

    override fun save(ids: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_PINNED, ids.filter { it.isNotBlank() }.toSet())
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "profer_pocket_project_pins"
        const val KEY_PINNED = "pinned_project_ids"
    }
}
