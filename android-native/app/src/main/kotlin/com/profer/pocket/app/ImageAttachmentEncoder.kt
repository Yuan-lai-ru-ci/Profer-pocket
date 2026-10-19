package com.profer.pocket.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * 手机图片附件编码。
 *
 * 远端会话目录有 10MB 上限，且图片最终走 WS base64 上传；这里先压到最长边 1600、
 * JPEG 85 起，超 2MB 继续降质量，避免一张原图把消息链路拖垮。
 */
object ImageAttachmentEncoder {
    private const val MAX_EDGE = 1600
    private const val MAX_BYTES = 2 * 1024 * 1024

    fun encode(context: Context, uri: Uri): Result<Pair<String, String>> = runCatching {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("无法读取图片")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("无法读取图片")

        val scaled = scaleToMaxEdge(decoded, MAX_EDGE)
        if (scaled !== decoded) decoded.recycle()

        var quality = 85
        var bytes = compress(scaled, quality)
        while (bytes.size > MAX_BYTES && quality > 40) {
            quality -= 10
            bytes = compress(scaled, quality)
        }
        scaled.recycle()
        if (bytes.size > MAX_BYTES) error("图片压缩后仍超过 2 MB，请换一张")

        val filename = "image-${System.currentTimeMillis()}.jpg"
        filename to Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
