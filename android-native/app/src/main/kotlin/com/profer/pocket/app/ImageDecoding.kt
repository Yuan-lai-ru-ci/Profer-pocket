package com.profer.pocket.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

/** 消息图片解码上限（px）：按屏宽铺满再留一点余量，不把相机原图整张塞进堆。 */
internal const val MESSAGE_IMAGE_MAX_DIMENSION = 1440

/** 附件大图预览解码上限（px）：预览最大 460dp 高，2048 足够清晰又不至于 OOM。 */
internal const val ATTACHMENT_PREVIEW_MAX_DIMENSION = 2048

/**
 * 解码时先读尺寸再按 [maxDimension] 降采样。
 *
 * 原实现直接把 4000×3000 的原图解码成 ARGB_8888（≈48MB），几张图同时可见就会
 * 把堆顶满，滚动/侧滑时频繁 GC；降采样后单张 ≈4–12MB，配合字节上限的 LruCache 才可控。
 */
internal fun decodeImageBitmap(
    dataUrl: String,
    maxDimension: Int = MESSAGE_IMAGE_MAX_DIMENSION,
): Bitmap? {
    if (!dataUrl.startsWith("data:image/")) return null
    val encoded = dataUrl.substringAfter("base64,", "")
    if (encoded.isEmpty()) return null
    return runCatching {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()
}

/**
 * 2 的幂降采样倍数（BitmapFactory 只认 2 的幂）。
 * 保证降采样后长边仍 ≥ [maxDimension]，不把图缩得比显示尺寸还小。
 */
internal fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
    var sample = 1
    while (width / (sample * 2) >= maxDimension || height / (sample * 2) >= maxDimension) {
        sample *= 2
    }
    return sample
}
