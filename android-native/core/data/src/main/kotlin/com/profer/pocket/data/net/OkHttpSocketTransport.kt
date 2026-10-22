package com.profer.pocket.data.net

import com.profer.pocket.remote.RemoteEndpoint
import com.profer.pocket.remote.RemoteTls
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * OkHttp WebSocket 实现。
 *
 * 关键点：
 * - readTimeout 置 0：默认 10s 会误杀 25s 心跳间隔的空闲连接，假死检测交给
 *   [PocketConnection] 的 75s 无入站判定；
 * - v1 端点按 `trustMode` 取 client：pinned 只给该 client 挂自定义 TrustManager，
 *   system 继续走系统默认校验；
 * - v1 握手必须带 `Sec-WebSocket-Protocol: profer.remote.v1`，服务端据此区分
 *   扫码绑定连接与旧 `?token=` 私网连接。
 */
class OkHttpSocketTransport internal constructor(
    private val clientFactory: (RemoteEndpoint?) -> OkHttpClient,
) : SocketTransport {

    /** 公开构造不暴露 OkHttp 类型，:app 不需要依赖 okhttp。 */
    constructor() : this(RemoteTls::clientFor)

    private var webSocket: WebSocket? = null

    override fun connect(target: ConnectTarget, listener: SocketTransport.Listener) {
        val client = clientFactory(target.endpoint)
        val request = Request.Builder()
            .url(target.url)
            .apply {
                if (target.subprotocols.isNotEmpty()) {
                    header("Sec-WebSocket-Protocol", target.subprotocols.joinToString(", "))
                }
            }
            .build()
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
    }
}
