package com.profer.pocket.app

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** 电脑端图片 data URL（base64）→ ImageBitmap；非图片或解码失败返回 null。 */
internal fun decodeImageDataUrl(dataUrl: String): ImageBitmap? {
    if (!dataUrl.startsWith("data:image/")) return null
    val encoded = dataUrl.substringAfter("base64,", "")
    if (encoded.isEmpty()) return null
    return runCatching {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}
