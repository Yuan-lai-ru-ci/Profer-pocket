package com.profer.pocket.data

import com.profer.pocket.data.net.PocketCommandException
import com.profer.pocket.data.net.PocketDisconnectedException
import com.profer.pocket.data.net.PocketTimeoutException

/** 数据层异常 → 可直接展示给用户的短句。 */
fun Throwable.toPocketMessage(): String = when (this) {
    is PocketDisconnectedException -> "连接已断开"
    is PocketTimeoutException -> "指令超时"
    is PocketCommandException -> message ?: "指令失败"
    else -> message ?: "未知错误"
}
