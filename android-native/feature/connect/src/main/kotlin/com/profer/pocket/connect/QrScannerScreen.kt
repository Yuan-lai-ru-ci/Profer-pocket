package com.profer.pocket.connect

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.profer.pocket.designsystem.component.ProferBackdrop
import com.profer.pocket.designsystem.component.ProferHeaderRow
import com.profer.pocket.designsystem.component.ProferIconAction
import com.profer.pocket.designsystem.component.proferFloatingSurface
import com.profer.pocket.designsystem.theme.ProferSizes
import com.profer.pocket.designsystem.theme.ProferSpacing
import com.profer.pocket.designsystem.theme.ProferTheme
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/**
 * 扫码页：CameraX 预览 + ZXing core 本地解码（不依赖 Google Play Services）。
 *
 * 相机权限缺失时只显示说明与授权按钮，不崩溃；解码成功只回调一次，
 * 配对失败由连接页展示，用户可以返回重扫。
 */
@Composable
fun QrScannerScreen(
    onBack: () -> Unit,
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        denied = !result
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    ScannerContent(
        granted = granted,
        denied = denied,
        onBack = onBack,
        onRequestPermission = { launcher.launch(Manifest.permission.CAMERA) },
        modifier = modifier,
    ) { CameraScanner(onScanned = onScanned) }
}

@Composable
private fun ScannerContent(
    granted: Boolean,
    denied: Boolean,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
    cameraContent: @Composable () -> Unit = {},
) {
    ProferBackdrop(modifier = modifier) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                ProferHeaderRow {
                    ProferIconAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onBack)
                    Text("扫描二维码", style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).padding(horizontal = ProferSpacing.md))
                }
            },
        ) { innerPadding ->
            if (granted) {
                Column(
                    Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = ProferSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(ProferSpacing.lg),
                ) {
                    Box(
                        Modifier.weight(1f).fillMaxWidth().clip(MaterialTheme.shapes.extraLarge)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) { cameraContent() }
                    Surface(modifier = Modifier.weight(1f, fill = false), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(ProferSpacing.lg),
                            verticalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
                        ) {
                            Text("对准电脑上的配对二维码", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text("在电脑端 Profer 中打开配对二维码。扫不到时可调亮屏幕、稍微拉远，或返回手动连接。",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = ProferSizes.minTouchTarget)) { Text("返回手动连接") }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                        .padding(ProferSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(ProferSpacing.lg),
                ) {
                    ScannerPermissionCard(denied, onRequestPermission, onBack)
                }
            }
        }
    }
}

@Composable
private fun ScannerPermissionCard(denied: Boolean, onRequestPermission: () -> Unit, onBack: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(ProferSpacing.xl), verticalArrangement = Arrangement.spacedBy(ProferSpacing.lg)) {
            Text(if (denied) "需要相机权限" else "用相机快速配对", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                if (denied) "相机权限未开启。允许访问后即可扫码；若系统不再询问，可到应用设置中开启，或返回手动连接。"
                else "相机仅用于扫描电脑端 Profer 的配对二维码，也可以返回使用地址手动连接。",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRequestPermission, shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth().heightIn(min = ProferSizes.minTouchTarget)) { Text("允许使用相机") }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = ProferSizes.minTouchTarget)) { Text("返回手动连接") }
        }
    }
}

@Composable
private fun CameraScanner(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var torchOn by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
    }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    LaunchedEffect(Unit) {
        try {
            val provider = ProcessCameraProvider.getInstance(context).get()
            val preview = CameraPreview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            var delivered = false
            analysis.setAnalyzer(executor) { image ->
                try {
                    if (!delivered) {
                        val text = decodeQr(image, reader)
                        if (text != null) {
                            delivered = true
                            previewView.post { onScanned(text) }
                        }
                    }
                } finally {
                    image.close()
                }
            }
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            cameraFailed = true
        }
    }

    LaunchedEffect(torchOn, camera) {
        camera?.cameraControl?.enableTorch(torchOn)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(ProferSpacing.md)
                .heightIn(min = ProferSizes.minTouchTarget)
                .background(proferFloatingSurface(), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            ProferIconAction(
                icon = if (torchOn) Icons.Outlined.FlashOff else Icons.Outlined.FlashOn,
                contentDescription = if (torchOn) "关闭手电筒" else "打开手电筒",
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = { torchOn = !torchOn },
            )
        }
        if (cameraFailed) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(ProferSpacing.lg),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text("无法打开相机，请返回重试或使用手动连接。", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(ProferSpacing.lg))
            }
        }
    }
}

/** YUV 平面直接交给 ZXing，避免每帧转 Bitmap。 */
private fun decodeQr(image: ImageProxy, reader: MultiFormatReader): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)
    val width = image.width
    val height = image.height
    val source = PlanarYUVLuminanceSource(
        data,
        plane.rowStride,
        height,
        0,
        0,
        width,
        height,
        false,
    )
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}

@Preview(name = "扫码 · 浅色", showBackground = true)
@Preview(name = "扫码 · 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "扫码 · 大字号", showBackground = true, fontScale = 2f)
@Composable
private fun ScannerPreview() {
    ProferTheme(darkTheme = isSystemInDarkTheme()) {
        ScannerContent(granted = false, denied = true, onBack = {}, onRequestPermission = {})
    }
}
