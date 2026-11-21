package com.example.uhf.ui.inventory

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun MicroOpticalTagScannerDialog(
    isArabic: Boolean,
    onCodeCaptured: (ParsedOpticalCode) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // تكبير افتراضي 3.0x مثالي لقراءة أكواد 3مم × 3مم عن مسافة 8-12 سم
    var zoomRatio by remember { mutableFloatStateOf(3.0f) }
    var maxZoom by remember { mutableFloatStateOf(5.0f) }
    var torchEnabled by remember { mutableStateOf(false) }
    var manualCode by remember { mutableStateOf("") }
    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    val alreadyCaptured = remember { AtomicBoolean(false) }

    // إعادة ضبط التركيز الماكرو المركزي تلقائياً كل 1.4 ثانية لمنع تغبيش الكود الصغير
    LaunchedEffect(cameraRef, previewViewRef) {
        while (true) {
            delay(1400L)
            val cam = cameraRef ?: continue
            val pv = previewViewRef ?: continue
            if (pv.width > 0 && pv.height > 0) {
                val point = pv.meteringPointFactory.createPoint(pv.width / 2f, pv.height / 2f)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(2, TimeUnit.SECONDS)
                    .build()
                runCatching { cam.cameraControl.startFocusAndMetering(action) }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF0B152C),
            border = BorderStroke(1.dp, Color(0xFFD4AF37))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // شريط العنوان
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.QrCodeScanner,
                            contentDescription = null,
                            tint = Color(0xFFD4AF37)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (isArabic) "الماسح المجهري البديل (3×3 مم)" else "Micro Optical Tag Scanner",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color(0xFFD4AF37)
                            )
                            Text(
                                text = if (isArabic) "GS1 DataMatrix • MIRA QR • Barcode" else "GS1 DataMatrix • MIRA QR",
                                fontSize = 11.sp,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }

                    Row {
                        IconButton(onClick = {
                            torchEnabled = !torchEnabled
                            cameraRef?.cameraControl?.enableTorch(torchEnabled)
                        }) {
                            Icon(
                                imageVector = if (torchEnabled) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                                contentDescription = "Torch",
                                tint = if (torchEnabled) Color(0xFFFACC15) else Color.White
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }
                }

                // عدسة الكاميرا الماكرو مع إطار التصويب الدقيق
                if (hasCameraPermission) {
                    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
                    DisposableEffect(Unit) {
                        onDispose { analysisExecutor.shutdown() }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black)
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    val cam = cameraRef ?: return@detectTapGestures
                                    val pv = previewViewRef ?: return@detectTapGestures
                                    val pt = pv.meteringPointFactory.createPoint(offset.x, offset.y)
                                    val action = FocusMeteringAction.Builder(pt)
                                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                        .build()
                                    runCatching { cam.cameraControl.startFocusAndMetering(action) }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                val previewView = PreviewView(ctx).apply {
                                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                }
                                previewViewRef = previewView

                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                cameraProviderFuture.addListener({
                                    val cameraProvider = cameraProviderFuture.get()

                                    // دقة عالية 1080p لضمان وضوح خلايا كود 3×3 مم
                                    val resSelector = ResolutionSelector.Builder()
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                Size(1920, 1080),
                                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                            )
                                        )
                                        .build()

                                    val preview = Preview.Builder()
                                        .setResolutionSelector(resSelector)
                                        .build()
                                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                                    val analyzer = ImageAnalysis.Builder()
                                        .setResolutionSelector(resSelector)
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()

                                    analyzer.setAnalyzer(
                                        analysisExecutor,
                                        DualMicroCodeAnalyzer { rawText ->
                                            if (alreadyCaptured.compareAndSet(false, true)) {
                                                val parsed = MiraOpticalCodeParser.parse(rawText)
                                                previewView.post { onCodeCaptured(parsed) }
                                            }
                                        }
                                    )

                                    runCatching {
                                        cameraProvider.unbindAll()
                                        val cam = cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            analyzer
                                        )
                                        cameraRef = cam
                                        val zState = cam.cameraInfo.zoomState.value
                                        maxZoom = (zState?.maxZoomRatio ?: 5.0f).coerceAtMost(6.0f)
                                        val initialZoom = 3.0f.coerceIn(zState?.minZoomRatio ?: 1.0f, maxZoom)
                                        zoomRatio = initialZoom
                                        cam.cameraControl.setZoomRatio(initialZoom)

                                        // تقليل التعرض الضوئي قليلاً لمنع لمعان الذهب والليبل
                                        val expRange = cam.cameraInfo.exposureState.exposureCompensationRange
                                        if (expRange.contains(-2)) {
                                            cam.cameraControl.setExposureCompensationIndex(-2)
                                        }
                                    }
                                }, ContextCompat.getMainExecutor(ctx))

                                previewView
                            }
                        )

                        // مربع التصويب المجهري المركزي
                        Canvas(modifier = Modifier.size(116.dp)) {
                            val stroke = 3.dp.toPx()
                            val corner = 20.dp.toPx()
                            val gold = Color(0xFFD4AF37)

                            // زوايا ذهبية دقيقة
                            drawLine(gold, Offset(0f, 0f), Offset(corner, 0f), stroke, StrokeCap.Round)
                            drawLine(gold, Offset(0f, 0f), Offset(0f, corner), stroke, StrokeCap.Round)

                            drawLine(gold, Offset(size.width, 0f), Offset(size.width - corner, 0f), stroke, StrokeCap.Round)
                            drawLine(gold, Offset(size.width, 0f), Offset(size.width, corner), stroke, StrokeCap.Round)

                            drawLine(gold, Offset(0f, size.height), Offset(corner, size.height), stroke, StrokeCap.Round)
                            drawLine(gold, Offset(0f, size.height), Offset(0f, size.height - corner), stroke, StrokeCap.Round)

                            drawLine(gold, Offset(size.width, size.height), Offset(size.width - corner, size.height), stroke, StrokeCap.Round)
                            drawLine(gold, Offset(size.width, size.height), Offset(size.width, size.height - corner), stroke, StrokeCap.Round)
                        }

                        // شريط إرشاد المسافة
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(8.dp),
                            color = Color.Black.copy(alpha = 0.68f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CenterFocusStrong,
                                    contentDescription = null,
                                    tint = Color(0xFFD4AF37),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = if (isArabic) "قرّب الكود 3×3 مم إلى مسافة 10 سم داخل المربع"
                                    else "Hold 3×3mm code ~10cm inside frame",
                                    fontSize = 11.sp,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    // شريط التحكم بالتكبير الماكرو (Macro Zoom)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = String.format(java.util.Locale.US, "%.1fx", zoomRatio),
                            color = Color(0xFFD4AF37),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Slider(
                            value = zoomRatio,
                            onValueChange = { newZoom ->
                                zoomRatio = newZoom
                                cameraRef?.cameraControl?.setZoomRatio(newZoom)
                            },
                            valueRange = 1.0f..maxZoom,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFD4AF37),
                                activeTrackColor = Color(0xFFD4AF37)
                            )
                        )
                    }
                }

                // إدخال يدوي احتياطي (Serial / GTIN / EPC)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = manualCode,
                        onValueChange = { manualCode = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        placeholder = {
                            Text(
                                text = if (isArabic) "أو أدخل الرقم التسلسلي / GTIN يدوياً..."
                                else "Or type Serial / GTIN...",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFD4AF37),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    Button(
                        onClick = {
                            val clean = manualCode.trim()
                            if (clean.isNotEmpty() && alreadyCaptured.compareAndSet(false, true)) {
                                onCodeCaptured(MiraOpticalCodeParser.parse(clean))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD4AF37),
                            contentColor = Color(0xFF040814)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (isArabic) "تأكيد" else "Add", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * محلل ثنائي المحرك (ZXing Macro ROI + ML Kit) مصمم للأكواد المجهرية 3×3 مم
 */
private class DualMicroCodeAnalyzer(
    private val onDecoded: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val mlScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_CODE_128
            )
            .build()
    )

    private val zxingReader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.DATA_MATRIX,
                    BarcodeFormat.QR_CODE,
                    BarcodeFormat.EAN_13,
                    BarcodeFormat.CODE_128
                ),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.ALSO_INVERTED to true
            )
        )
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        // 1. محاولة ZXing السريعة على المربع المركزي المقتطع (ROI 36%) بدقة البكسل الكاملة
        val zxingHit = decodeCenterRoiWithZxing(imageProxy)
        if (!zxingHit.isNullOrBlank()) {
            imageProxy.close()
            onDecoded(zxingHit)
            return
        }

        // 2. تمرير الإطار إلى ML Kit بالتوازي
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        mlScanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val hit = barcodes.firstNotNullOfOrNull { it.rawValue?.trim()?.takeIf(String::isNotEmpty) }
                if (hit != null) {
                    onDecoded(hit)
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun decodeCenterRoiWithZxing(imageProxy: ImageProxy): String? {
        return try {
            val plane = imageProxy.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val width = imageProxy.width
            val height = imageProxy.height

            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            // قص 36% من منتصف الصورة فقط لتركيز المعالجة على الكود المجهري 3×3 مم
            val cropW = (width * 0.36f).toInt().coerceAtLeast(120)
            val cropH = (height * 0.36f).toInt().coerceAtLeast(120)
            val left = ((width - cropW) / 2).coerceAtLeast(0)
            val top = ((height - cropH) / 2).coerceAtLeast(0)

            val source = PlanarYUVLuminanceSource(
                data,
                rowStride,
                height,
                left,
                top,
                cropW,
                cropH,
                false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            zxingReader.decodeWithState(bitmap).text
        } catch (_: Exception) {
            null
        } finally {
            zxingReader.reset()
        }
    }
}
