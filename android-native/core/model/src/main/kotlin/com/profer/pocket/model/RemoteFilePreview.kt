package com.profer.pocket.model

/** 电脑端文件预览结果：图片走 data URL，文本走正文。 */
data class RemoteFilePreview(
    val filename: String,
    val isImage: Boolean,
    val dataUrl: String? = null,
    val text: String? = null,
)
