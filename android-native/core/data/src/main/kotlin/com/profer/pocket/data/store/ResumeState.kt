package com.profer.pocket.data.store

/**
 * 事件游标：重连后按游标补发，避免丢事件。
 * 与服务端 instance 绑定：服务进程重启后游标失效，必须回到快照。
 */
interface ResumeStateStore {
    data class Cursor(
        val lastEventId: Long,
        val serverInstanceId: String?,
    )

    fun load(endpoint: String): Cursor?

    fun save(endpoint: String, lastEventId: Long, serverInstanceId: String?)

    fun clear(endpoint: String)
}
