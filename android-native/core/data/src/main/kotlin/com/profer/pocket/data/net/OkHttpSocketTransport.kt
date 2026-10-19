package com.profer.pocket.data.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * OkHttp WebSocket 实现。
 *
 * 关键点：readTimeout 置 0。默认 10s 会误杀 25s 心跳间隔的空闲连接；
 * 假死检测交给 [PocketConnection] 的 75s 无入站判定。
 */
class OkHttpSocketTransport(
    private val client: OkHttpClient = defaultClient(),
) : SocketTransport {

    private var webSocket: WebSocket? = null

    override fun connect(url: String, listener: SocketTransport.Listener) {
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onText(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                closeQuietly(webSocket, code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onFailure(t.message ?: "WebSocket 失败")
            }
        })
    }

    override fun send(text: String): Boolean = try {
        webSocket?.send(text) == true
    } catch (_: Exception) {
        false
    }

    override fun close(code: Int, reason: String) {
        webSocket?.let { closeQuietly(it, code, reason) }
        webSocket = null
    }

    private fun closeQuietly(socket: WebSocket, code: Int, reason: String) {
        try {
            socket.close(code, reason)
        } catch (_: Exception) {
            // 关闭失败不阻断后续重连
        }
        try {
            socket.cancel()
        } catch (_: Exception) {
            // ignore
        }
    }

    companion object {
        /** 供 :app 构造传输层；OkHttp 类型不越过模块边界。 */
        fun factory(): SocketTransportFactory = SocketTransportFactory { OkHttpSocketTransport() }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
