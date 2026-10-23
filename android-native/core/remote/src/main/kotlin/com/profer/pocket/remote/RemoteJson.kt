package com.profer.pocket.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * 连接资料的 JSON 编解码。
 *
 * 解析使用 [RemoteValidation] 的严格规则：未知字段、类型不符、多余 pin 一律拒绝；
 * 这里只负责把已校验的模型重新序列化成桌面认识的形状。
 */
internal object RemoteJson {

    fun encodeProfile(profile: RemoteProfile): String = JSONObject()
        .put("schemaVersion", profile.schemaVersion)
        .put("deviceId", profile.deviceId)
        .put("deviceName", profile.deviceName)
        .put("updatedAt", profile.updatedAt)
        .put("endpoints", JSONArray().apply { profile.endpoints.forEach { put(encodeEndpoint(it)) } })
        .toString()

    fun encodeEndpoint(endpoint: RemoteEndpoint): JSONObject = JSONObject().apply {
        put("url", endpoint.url)
        put("scope", endpoint.scope.wire)
        endpoint.trustMode?.let { put("trustMode", it.wire) }
        endpoint.pin?.let { pin ->
            put(
                "pin",
                JSONObject()
                    .put("algorithm", pin.algorithm)
                    .put("value", pin.value),
            )
        }
    }
}
