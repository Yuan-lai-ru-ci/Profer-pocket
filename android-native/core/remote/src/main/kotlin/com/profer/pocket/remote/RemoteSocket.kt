package com.profer.pocket.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * 远程 socket 抽象：配对流程只依赖这个接口，单测用假实现驱动状态机。
 *
 * 与 `SocketTransport` 的区别是这里带端点信任上下文：pinned 端点必须用该端点
 * 自己的 client（自定义 TrustManager），system 端点用系统默认校验。
 */
interface RemoteSocket {
    fun send(text: String): Boolean

    fun close(code: Int = 1000)

    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onClosed(code: Int, reason: String)
        fun onFailure(error: String)
    }
}

fun interface RemoteSocketFactory {
    fun open(endpoint: RemoteEndpoint, listener: RemoteSocket.Listener): RemoteSocket
}

/** OkHttp 实现：v1 握手必须带 `Sec-WebSocket-Protocol: profer.remote.v1`。 */
class OkHttpRemoteSocketFactory internal constructor(
    private val clientFactory: (RemoteEndpoint) -> OkHttpClient,
) : RemoteSocketFactory {

    /** 公开构造不暴露 OkHttp 类型。 */
    constructor() : this(RemoteTls::clientFor)

    override fun open(endpoint: RemoteEndpoint, listener: RemoteSocket.Listener): RemoteSocket {
        val client = clientFactory(endpoint)
        val request = Request.Builder()
            .url(endpoint.url)
            .header("Sec-WebSocket-Protocol", REMOTE_PROTOCOL)
            .build()
        return OkHttpRemoteSocket(client, request, listener)
    }
}

private class OkHttpRemoteSocket(
    client: OkHttpClient,
    request: Request,
    private val listener: RemoteSocket.Listener,
) : RemoteSocket {

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var closed = false

    init {
        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!closed) listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!closed) listener.onText(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (closed) return
                    closed = true
                    listener.onClosed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (closed) return
                    closed = true
                    listener.onFailure(t.message ?: "WebSocket 失败")
                }
            },
        )
    }

    override fun send(text: String): Boolean = try {
        socket?.send(text) == true
    } catch (_: Exception) {
        false
    }

    override fun close(code: Int) {
        if (closed) return
        closed = true
        try {
            socket?.close(code, "client close")
        } catch (_: Exception) {
            // ignore
        }
        try {
            socket?.cancel()
        } catch (_: Exception) {
            // ignore
        }
    }
}
