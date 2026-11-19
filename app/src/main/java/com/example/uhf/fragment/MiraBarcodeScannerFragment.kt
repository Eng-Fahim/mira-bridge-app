package com.example.uhf.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.YuvImage
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.uhf.R
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.manager.MiraOrgProfileManager
import com.example.uhf.navigation.NavigationManager
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayOutputStream
import java.util.EnumMap
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  MiraBarcodeScannerFragment v7.0 — Kotlin Ultra-Micro & Harsh Conditions
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * هندسة احترافية لقراءة الأكواد المجهرية (≤ 5mm × 5mm) وباركود المجوهرات والمنتجات:
 *   1. True Pixel-Level ROI Cropping (44%) + 2.2x Upscaling
 *   2. Integral-Image Adaptive Thresholding للطباعة الباهتة أو غير المتجانسة
 *   3. 4-Stage Pipeline: ML Kit + ZXing TRY_HARDER + Adaptive Binarized + Inverted
 *   4. Anti-Glare Exposure Control لمنع انعكاس الذهب والبطاقات اللامعة
 *   5. Hardware 2D Scanner Broadcast + HID Keyboard Wedge Support
 *   6. Continuous Batch Mode للمسح المتتالي السريع
 */
class MiraBarcodeScannerFragment : Fragment() {

    fun interface OnBarcodeScannedListener {
        fun onBarcodeScanned(barcodeData: String, format: String)
    }

    // ==================== UI ====================
    private var previewView: PreviewView? = null
    private var scannerOverlay: View? = null
    private var scanLine: View? = null
    private var tvScanStatus: TextView? = null
    private var tvScannedData: TextView? = null
    private var tvZoomLevel: TextView? = null
    private var btnClose: ImageButton? = null
    private var btnFlash: ImageButton? = null
    private var btnAntiGlareChip: TextView? = null

    private val zoomChipViews = mutableListOf<TextView>()
    private val mainHandler = Handler(Looper.getMainLooper())

    // ==================== Camera ====================
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cameraControl: CameraControl? = null

    @Volatile
    private var isFlashOn = false
    private var exposureLevelStep = 1 // 0 = عادي، 1 = ضد الوهج (-1 EV)، 2 = إزالة انعكاس الذهب (-2 EV)
    private var cameraExecutor: ExecutorService? = null

    // ==================== Dual Scanner Engines ====================
    private var barcodeScanner: BarcodeScanner? = null
    private var zxingReader: MultiFormatReader? = null
    private var toneGenerator: ToneGenerator? = null

    @Volatile
    private var isScanning = true

    @Volatile
    private var isViewDestroyed = false
    private var lastScanTime = 0L
    private var frameCounter = 0L

    // ==================== Hardware 2D & HID Buffer ====================
    private val hidBuffer = StringBuilder()
    private var lastHidKeyTime = 0L
    private var isHardwareReceiverRegistered = false

    private val hardware2DReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || !isScanning || isViewDestroyed) return
            val code = extractBarcodeFromHardwareIntent(intent)
            if (!code.isNullOrBlank()) {
                onBarcodeDetected(code.trim(), Barcode.FORMAT_DATA_MATRIX, true)
            }
        }
    }

    // ==================== Listeners & Mode ====================
    private var barcodeListener: OnBarcodeScannedListener? = null
    private var continuousBatchMode = false
    private var batchScannedCount = 0
    private var isArabic = false
    private var orgProfile: MiraOrgProfileManager? = null
    private var appContext: Context? = null

    private var backCallback: OnBackPressedCallback? = null

    @Volatile
    private var isCompleted = false

    @Volatile
    private var isCameraBound = false

    // ==================== Zoom & Focus ====================
    @Volatile
    private var currentZoom = 1.0f

    @Volatile
    private var maxSupportedZoom = MAX_ZOOM_FALLBACK
    private var scaleDetector: ScaleGestureDetector? = null

    // ==================== Confirmation ====================
    @Volatile
    private var candidateCode: String? = null

    @Volatile
    private var candidateTime = 0L

    @Volatile
    private var candidateConfirmations = 0

    private val periodicFocusRunnable = object : Runnable {
        override fun run() {
            if (!isViewDestroyed && isScanning && isCameraBound) {
                triggerCenterAutoFocus()
                mainHandler.postDelayed(this, 2200L)
            }
        }
    }

    fun setOnBarcodeScannedListener(listener: OnBarcodeScannedListener?) {
        this.barcodeListener = listener
    }

    fun setContinuousBatchMode(continuous: Boolean) {
        this.continuousBatchMode = continuous
    }

    // ==================== Lifecycle ====================

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_barcode_scanner, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        isViewDestroyed = false
        isCompleted = false
        batchScannedCount = 0
        appContext = requireContext().applicationContext

        try {
            isArabic = "ar".equals(Locale.getDefault().language, ignoreCase = true)
            orgProfile = MiraOrgProfileManager.getInstance(requireContext().applicationContext)

            previewView = view.findViewById(R.id.previewView)
            scannerOverlay = view.findViewById(R.id.scannerOverlay)
            scanLine = view.findViewById(R.id.scanLine)
            tvScanStatus = view.findViewById(R.id.tvScanStatus)
            tvScannedData = view.findViewById(R.id.tvScannedData)
            tvZoomLevel = view.findViewById(R.id.tvZoomLevel)
            btnClose = view.findViewById(R.id.btnCloseScanner)
            btnFlash = view.findViewById(R.id.btnToggleFlash)

            setupBackHandler()
            setupHidKeyboardCapture(view)

            if (previewView == null) {
                Log.e(TAG, "previewView not found in layout!")
                showErrorAndClose()
                return
            }

            applyLanguageAndDirection(view)
            injectMicroControlOverlay(view)

            btnClose?.setOnClickListener { closeScanner() }
            btnFlash?.setOnClickListener { toggleFlash() }

            if (cameraExecutor == null || cameraExecutor?.isShutdown == true) {
                cameraExecutor = Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "MiraKotlinMicroAnalyzer").apply {
                        priority = Thread.MAX_PRIORITY
                        isDaemon = true
                    }
                }
            }

            if (toneGenerator == null) {
                try {
                    toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                } catch (e: Exception) {
                    Log.w(TAG, "ToneGenerator init failed: ${e.message}")
                }
            }

            registerHardware2DScannerReceiver()

            if (initDualBarcodeEngines()) {
                if (hasCameraPermission()) {
                    startCamera()
                } else {
                    requestCameraPermission()
                }
                startScanAnimation()
            } else {
                showErrorAndClose()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onViewCreated: ${e.message}", e)
            showErrorAndClose()
        }
    }

    private fun injectMicroControlOverlay(root: View) {
        val container = root as? ViewGroup ?: return
        try {
            val ctx = requireContext()
            zoomChipViews.clear()

            val barWrapper = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }

            val antiGlareChip = TextView(ctx).apply {
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#CC0F172A"))
                    cornerRadius = dp(20).toFloat()
                    setStroke(dp(1), Color.parseColor("#8838BDF8"))
                }
                setTextColor(Color.parseColor("#38BDF8"))
                setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    exposureLevelStep = (exposureLevelStep + 1) % 3
                    applyExposureStep()
                    updateAntiGlareButtonText()
                }
            }
            btnAntiGlareChip = antiGlareChip
            updateAntiGlareButtonText()

            val glareLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            barWrapper.addView(antiGlareChip, glareLp)

            val hintLabel = TextView(ctx).apply {
                text = if (isArabic) {
                    if (continuousBatchMode) {
                        "مسح متتالي للأكواد المجهرية (≤5مم) • امسح القطع تباعاً"
                    } else {
                        "عدسة الأكواد المجهرية (≤5مم) • اختر مستوى التقريب أو المس للتركيز"
                    }
                } else {
                    if (continuousBatchMode) {
                        "Batch Micro-Code Scan (≤5mm) • Scan items continuously"
                    } else {
                        "Extreme Micro Lens (≤5mm) • Select Zoom or Tap to Focus"
                    }
                }
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(2), dp(12), dp(6))
            }
            barWrapper.addView(hintLabel)

            val pillRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#CC0A1124"))
                    cornerRadius = dp(28).toFloat()
                    setStroke(dp(1), Color.parseColor("#66D4AF37"))
                }
            }

            for (zoomFactor in ZOOM_PRESETS) {
                val chip = TextView(ctx).apply {
                    text = String.format(Locale.US, "%.1fx", zoomFactor).replace(".0x", "x")
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(dp(3), 0, dp(3), 0)
                    }
                    setOnClickListener { v ->
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        applyZoomRatio(zoomFactor, true)
                        triggerCenterAutoFocus()
                    }
                }
                zoomChipViews.add(chip)
                pillRow.addView(chip)
            }

            barWrapper.addView(pillRow)

            if (container is FrameLayout) {
                val params = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(32)
                }
                container.addView(barWrapper, params)
            } else {
                container.addView(barWrapper)
            }

            updateZoomChipsSelection(DEFAULT_MICRO_ZOOM)
        } catch (e: Exception) {
            Log.w(TAG, "injectMicroControlOverlay error: ${e.message}")
        }
    }

    private fun updateAntiGlareButtonText() {
        val chip = btnAntiGlareChip ?: return
        chip.text = when (exposureLevelStep) {
            1 -> if (isArabic) "🌗 مضاد وهج البطاقات (نشط)" else "🌗 Anti-Glare Mode (Active)"
            2 -> if (isArabic) "🌑 إزالة انعكاس الذهب والليزر" else "🌑 Gold/Laser Glare Shield"
            else -> if (isArabic) "☀️ إضاءة قياسية (للظلام)" else "☀️ Standard Exposure (Low Light)"
        }
    }

    private fun applyExposureStep() {
        try {
            val cam = camera ?: return
            val ctrl = cameraControl ?: return
            val expState = cam.cameraInfo.exposureState
            if (!expState.isExposureCompensationSupported) return

            val range = expState.getExposureCompensationRange()
            val target = when (exposureLevelStep) {
                0 -> 0
                1 -> max(range.lower, -1)
                else -> max(range.lower, -2)
            }
            ctrl.setExposureCompensationIndex(target)
        } catch (_: Exception) {
        }
    }

    private fun updateZoomChipsSelection(activeZoom: Float) {
        try {
            val count = min(zoomChipViews.size, ZOOM_PRESETS.size)
            for (i in 0 until count) {
                val chip = zoomChipViews[i]
                val preset = ZOOM_PRESETS[i]
                val isSelected = kotlin.math.abs(activeZoom - preset) < 0.55f

                chip.background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    if (isSelected) {
                        setColor(COLOR_GOLD)
                    } else {
                        setColor(Color.TRANSPARENT)
                    }
                }
                chip.setTextColor(
                    if (isSelected) Color.parseColor("#060B19")
                    else Color.parseColor("#CBD5E1")
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun dp(value: Int): Int {
        return try {
            Math.round(value * resources.displayMetrics.density)
        } catch (_: Exception) {
            value
        }
    }

    // ==================== Hardware 2D Scanner & HID Support ====================

    private fun registerHardware2DScannerReceiver() {
        val ctx = appContext ?: return
        if (isHardwareReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction("com.scanner.broadcast")
                addAction("com.android.server.scannerservice.broadcast")
                addAction("android.intent.ACTION_DECODE_DATA")
                addAction("com.rscja.scanner.action.BARCODE")
                addAction("nlscan.action.SCANNER_RESULT")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(hardware2DReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(hardware2DReceiver, filter)
            }
            isHardwareReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Hardware 2D receiver registration skipped: ${e.message}")
        }
    }

    private fun unregisterHardware2DScannerReceiver() {
        val ctx = appContext ?: return
        if (!isHardwareReceiverRegistered) return
        try {
            ctx.unregisterReceiver(hardware2DReceiver)
        } catch (_: Exception) {
        }
        isHardwareReceiverRegistered = false
    }

    private fun extractBarcodeFromHardwareIntent(intent: Intent): String? {
        val keys = arrayOf("data", "scannerdata", "barcode_string", "barcode", "SCAN_BARCODE1", "value")
        for (key in keys) {
            val value = intent.getStringExtra(key)
            if (!value.isNullOrBlank()) return value.trim()
        }
        val rawBytes = intent.getByteArrayExtra("barcode")
        if (rawBytes != null && rawBytes.isNotEmpty()) {
            return String(rawBytes).trim()
        }
        return null
    }

    private fun setupHidKeyboardCapture(root: View) {
        root.isFocusableInTouchMode = true
        root.requestFocus()
        root.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val now = System.currentTimeMillis()
            if (now - lastHidKeyTime > 250L) {
                hidBuffer.setLength(0)
            }
            lastHidKeyTime = now

            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_TAB) {
                if (hidBuffer.length >= 3) {
                    val scanned = hidBuffer.toString().trim()
                    hidBuffer.setLength(0)
                    onBarcodeDetected(scanned, Barcode.FORMAT_DATA_MATRIX, true)
                    return@setOnKeyListener true
                }
                hidBuffer.setLength(0)
                return@setOnKeyListener false
            }

            val unicode = event.unicodeChar
            if (unicode > 31) {
                hidBuffer.append(unicode.toChar())
                return@setOnKeyListener true
            }
            false
        }
    }

    private fun setupBackHandler() {
        val act = activity ?: return
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closeScanner()
            }
        }.also { callback ->
            act.onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        }
    }

    override fun onResume() {
        super.onResume()
        isViewDestroyed = false

        try {
            if (isCompleted && !continuousBatchMode) return

            isScanning = true
            if (hasCameraPermission() && !isCameraBound) {
                startCamera()
            }
            if (scanLine != null && isScanning) {
                startScanAnimation()
            }
            mainHandler.removeCallbacks(periodicFocusRunnable)
            mainHandler.postDelayed(periodicFocusRunnable, 1200L)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onResume: ${e.message}", e)
        }
    }

    override fun onPause() {
        super.onPause()
        isScanning = false
        mainHandler.removeCallbacks(periodicFocusRunnable)

        if (isCameraBound && cameraProvider != null) {
            try {
                cameraProvider?.unbindAll()
                isCameraBound = false
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding camera: ${e.message}")
            }
        }

        try {
            scanLine?.animate()?.cancel()
        } catch (_: Exception) {
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                showToastSafe(
                    if (isArabic) "❌ صلاحية الكاميرا مطلوبة" else "❌ Camera permission required",
                    Toast.LENGTH_LONG
                )
                closeScanner()
            }
        }
    }

    private fun applyLanguageAndDirection(root: View) {
        try {
            root.layoutDirection = if (isArabic) {
                View.LAYOUT_DIRECTION_RTL
            } else {
                View.LAYOUT_DIRECTION_LTR
            }
            tvScanStatus?.apply {
                text = if (isArabic) {
                    "🎯 ضع الكود المجهري (≤5×5 مم) داخل المربع"
                } else {
                    "🎯 Center micro-code (≤5×5 mm) in box"
                }
                setTextColor(COLOR_READY)
            }
            btnFlash?.contentDescription = if (isArabic) "فلاش" else "Flash"
            btnClose?.contentDescription = if (isArabic) "إغلاق" else "Close"
        } catch (e: Exception) {
            Log.e(TAG, "Error applying language: ${e.message}")
        }
    }

    private fun initDualBarcodeEngines(): Boolean {
        return try {
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_AZTEC
                )
                .enableAllPotentialBarcodes()
                .build()
            barcodeScanner = BarcodeScanning.getClient(options)

            zxingReader = MultiFormatReader().apply {
                val hints: MutableMap<DecodeHintType, Any> = EnumMap(DecodeHintType::class.java)
                hints[DecodeHintType.TRY_HARDER] = java.lang.Boolean.TRUE
                hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(
                    BarcodeFormat.DATA_MATRIX,
                    BarcodeFormat.QR_CODE,
                    BarcodeFormat.CODE_128,
                    BarcodeFormat.EAN_13,
                    BarcodeFormat.CODE_39,
                    BarcodeFormat.AZTEC
                )
                setHints(hints)
            }

            barcodeScanner != null
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing barcode engines: ${e.message}")
            false
        }
    }

    private fun hasCameraPermission(): Boolean {
        return try {
            val ctx = appContext ?: return false
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    private fun requestCameraPermission() {
        try {
            val act = activity ?: return
            if (isAdded) {
                ActivityCompat.requestPermissions(
                    act,
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_CODE
                )
            }
        } catch (_: Exception) {
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        try {
            val pv = previewView
            val exec = cameraExecutor
            if (pv == null || exec == null || exec.isShutdown) {
                showErrorAndClose()
                return
            }

            val future = ProcessCameraProvider.getInstance(requireContext())
            future.addListener({
                try {
                    if (isViewDestroyed || previewView == null) return@addListener

                    val provider = future.get()
                    cameraProvider = provider

                    val preview = Preview.Builder()
                        .setTargetResolution(Size(1280, 720))
                        .build()
                    preview.setSurfaceProvider(pv.surfaceProvider)

                    val analysisBuilder = ImageAnalysis.Builder()
                        .setTargetResolution(Size(1920, 1080))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

                    try {
                        val ext = Camera2Interop.Extender(analysisBuilder)
                        ext.setCaptureRequestOption(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        )
                        ext.setCaptureRequestOption(
                            CaptureRequest.EDGE_MODE,
                            CaptureRequest.EDGE_MODE_HIGH_QUALITY
                        )
                        ext.setCaptureRequestOption(
                            CaptureRequest.NOISE_REDUCTION_MODE,
                            CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL
                        )
                        ext.setCaptureRequestOption(
                            CaptureRequest.CONTROL_SCENE_MODE,
                            CaptureRequest.CONTROL_SCENE_MODE_BARCODE
                        )
                    } catch (_: Exception) {
                    }

                    val imageAnalysis = analysisBuilder.build().apply {
                        setAnalyzer(exec) { proxy -> analyzeMicroFrame(proxy) }
                    }

                    val selector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build()

                    provider.unbindAll()
                    val boundCamera = provider.bindToLifecycle(
                        this@MiraBarcodeScannerFragment,
                        selector,
                        preview,
                        imageAnalysis
                    )
                    camera = boundCamera
                    cameraControl = boundCamera.cameraControl

                    try {
                        val zoomRatioMax = boundCamera.cameraInfo.zoomState.value?.maxZoomRatio
                        if (zoomRatioMax != null) {
                            maxSupportedZoom = min(8.5f, zoomRatioMax)
                        }
                    } catch (_: Exception) {
                    }

                    applyExposureStep()

                    isCameraBound = true
                    isScanning = true

                    setupFocusAndZoom()
                    applyZoomRatio(DEFAULT_MICRO_ZOOM, false)

                    mainHandler.removeCallbacks(periodicFocusRunnable)
                    mainHandler.postDelayed(periodicFocusRunnable, 1000L)
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting camera: ${e.message}", e)
                    showErrorAndClose()
                }
            }, ContextCompat.getMainExecutor(requireContext()))
        } catch (e: Exception) {
            Log.e(TAG, "Error getting camera provider: ${e.message}", e)
            showErrorAndClose()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFocusAndZoom() {
        val ctrl = cameraControl ?: return
        val pv = previewView ?: return

        try {
            triggerCenterAutoFocus()

            scaleDetector = ScaleGestureDetector(
                requireContext(),
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        try {
                            val newZoom = currentZoom * detector.scaleFactor
                            applyZoomRatio(newZoom, true)
                        } catch (_: Exception) {
                        }
                        return true
                    }
                }
            )

            pv.setOnTouchListener { _, event ->
                scaleDetector?.onTouchEvent(event)
                if (event.action == MotionEvent.ACTION_UP && event.pointerCount == 1) {
                    try {
                        val focus = FocusMeteringAction.Builder(
                            pv.meteringPointFactory.createPoint(event.x, event.y),
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                        ctrl.startFocusAndMetering(focus)
                        showHintSafe(
                            if (isArabic) "🎯 تم قفل البؤرة المجهرية" else "🎯 Micro Focus Locked",
                            COLOR_READY
                        )
                    } catch (_: Exception) {
                    }
                }
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "setupFocusAndZoom error: ${e.message}")
        }
    }

    private fun triggerCenterAutoFocus() {
        val ctrl = cameraControl ?: return
        val pv = previewView ?: return
        try {
            val autofocus = FocusMeteringAction.Builder(
                pv.meteringPointFactory.createPoint(0.5f, 0.5f),
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            ).setAutoCancelDuration(2, TimeUnit.SECONDS).build()
            ctrl.startFocusAndMetering(autofocus)
        } catch (_: Exception) {
        }
    }

    private fun showHintSafe(hint: String, color: Int) {
        safeRun {
            try {
                if (isScanning) {
                    tvScanStatus?.apply {
                        text = hint
                        setTextColor(color)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  4-Stage Extreme Micro Image Pipeline
    // ═══════════════════════════════════════════════════════════════════

    @Suppress("UnsafeOptInUsageError")
    private fun analyzeMicroFrame(imageProxy: ImageProxy) {
        val scanner = barcodeScanner
        val mediaImage = imageProxy.image
        if (scanner == null || !isScanning || isViewDestroyed || mediaImage == null) {
            closeImageProxySafely(imageProxy)
            return
        }

        val closed = AtomicBoolean(false)
        val closeOnce = {
            if (closed.compareAndSet(false, true)) {
                closeImageProxySafely(imageProxy)
            }
        }

        try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val currentFrame = ++frameCounter

            val roiBitmap = extractCenterMicroBitmap(imageProxy, rotation)
            if (roiBitmap != null) {
                try {
                    val enhancedBitmap = enhanceMicroContrast(roiBitmap)
                    try {
                        val mlResults = Tasks.await(
                            scanner.process(InputImage.fromBitmap(enhancedBitmap, 0))
                        )
                        for (b in mlResults) {
                            val raw = b.rawValue
                            if (!raw.isNullOrEmpty()) {
                                closeOnce()
                                onBarcodeDetected(raw, b.format, true)
                                return
                            }
                        }

                        val zxingResult = decodeWithZxing(enhancedBitmap)
                        val zxingText = zxingResult?.text
                        if (!zxingText.isNullOrEmpty()) {
                            val fmt = mapZxingFormatToMlKit(zxingResult.barcodeFormat)
                            closeOnce()
                            onBarcodeDetected(zxingText, fmt, true)
                            return
                        }

                        if (currentFrame % 2 == 0L) {
                            val binarizedBitmap = applyAdaptiveBinarization(roiBitmap)
                            try {
                                val binMl = Tasks.await(
                                    scanner.process(InputImage.fromBitmap(binarizedBitmap, 0))
                                )
                                for (b in binMl) {
                                    val raw = b.rawValue
                                    if (!raw.isNullOrEmpty()) {
                                        closeOnce()
                                        onBarcodeDetected(raw, b.format, true)
                                        return
                                    }
                                }

                                val binZxing = decodeWithZxing(binarizedBitmap)
                                val binText = binZxing?.text
                                if (!binText.isNullOrEmpty()) {
                                    val fmt = mapZxingFormatToMlKit(binZxing.barcodeFormat)
                                    closeOnce()
                                    onBarcodeDetected(binText, fmt, true)
                                    return
                                }
                            } finally {
                                if (!binarizedBitmap.isRecycled) binarizedBitmap.recycle()
                            }
                        }

                        if (currentFrame % 3 == 0L) {
                            val invertedBitmap = invertBitmapColors(enhancedBitmap)
                            try {
                                val invMl = Tasks.await(
                                    scanner.process(InputImage.fromBitmap(invertedBitmap, 0))
                                )
                                for (b in invMl) {
                                    val raw = b.rawValue
                                    if (!raw.isNullOrEmpty()) {
                                        closeOnce()
                                        onBarcodeDetected(raw, b.format, true)
                                        return
                                    }
                                }
                            } finally {
                                if (!invertedBitmap.isRecycled) invertedBitmap.recycle()
                            }
                        }
                    } finally {
                        if (!enhancedBitmap.isRecycled) enhancedBitmap.recycle()
                    }
                } finally {
                    if (!roiBitmap.isRecycled) roiBitmap.recycle()
                }
            }

            if (currentFrame % 2 == 1L) {
                val fullImage = InputImage.fromMediaImage(mediaImage, rotation)
                val fullResults = Tasks.await(scanner.process(fullImage))
                for (b in fullResults) {
                    val raw = b.rawValue
                    if (!raw.isNullOrEmpty()) {
                        closeOnce()
                        onBarcodeDetected(raw, b.format, false)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Frame analyze pass: ${e.message}")
        } finally {
            closeOnce()
        }
    }

    @Suppress("UnsafeOptInUsageError")
    private fun extractCenterMicroBitmap(imageProxy: ImageProxy, rotationDegrees: Int): Bitmap? {
        return try {
            val image = imageProxy.image ?: return null
            val planes = image.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val width = image.width
            val height = image.height

            val cropW = (width * 0.44f).toInt()
            val cropH = (height * 0.44f).toInt()
            val left = (width - cropW) / 2
            val top = (height - cropH) / 2
            val cropRect = Rect(left, top, left + cropW, top + cropH)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(cropRect, 98, out)
            val jpegBytes = out.toByteArray()

            val cropped = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null

            val matrix = Matrix().apply {
                if (rotationDegrees != 0) {
                    postRotate(rotationDegrees.toFloat())
                }
                postScale(2.2f, 2.2f)
            }

            val upscaled = Bitmap.createBitmap(
                cropped, 0, 0, cropped.width, cropped.height, matrix, true
            )
            if (upscaled != cropped) {
                cropped.recycle()
            }
            upscaled
        } catch (_: Exception) {
            null
        }
    }

    private fun applyAdaptiveBinarization(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val gray = IntArray(w * h)
        val integral = LongArray(w * h)

        for (y in 0 until h) {
            var rowSum = 0L
            val rowOffset = y * w
            for (x in 0 until w) {
                val c = pixels[rowOffset + x]
                val lum = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
                gray[rowOffset + x] = lum
                rowSum += lum.toLong()
                integral[rowOffset + x] = if (y == 0) rowSum else (integral[(y - 1) * w + x] + rowSum)
            }
        }

        val s = max(8, w / 16)
        val s2 = s / 2
        val thresholdPercent = 88L

        for (y in 0 until h) {
            val y1 = max(0, y - s2)
            val y2 = min(h - 1, y + s2)
            val rowOffset = y * w

            for (x in 0 until w) {
                val x1 = max(0, x - s2)
                val x2 = min(w - 1, x + s2)
                val count = ((x2 - x1 + 1) * (y2 - y1 + 1)).toLong()

                val sum = integral[y2 * w + x2] -
                    (if (y1 > 0) integral[(y1 - 1) * w + x2] else 0L) -
                    (if (x1 > 0) integral[y2 * w + (x1 - 1)] else 0L) +
                    (if (y1 > 0 && x1 > 0) integral[(y1 - 1) * w + (x1 - 1)] else 0L)

                val idx = rowOffset + x
                pixels[idx] = if (gray[idx].toLong() * count * 100L < sum * thresholdPercent) {
                    Color.BLACK
                } else {
                    Color.WHITE
                }
            }
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    private fun enhanceMicroContrast(src: Bitmap): Bitmap {
        val dest = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dest)
        val contrast = 1.75f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val cm = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return dest
    }

    private fun invertBitmapColors(src: Bitmap): Bitmap {
        val dest = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dest)
        val cm = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return dest
    }

    private fun decodeWithZxing(bitmap: Bitmap): Result? {
        val reader = zxingReader ?: return null
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            val source = RGBLuminanceSource(w, h, pixels)
            try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            } catch (_: Exception) {
                reader.reset()
                reader.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source)))
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                reader.reset()
            } catch (_: Exception) {
            }
        }
    }

    private fun mapZxingFormatToMlKit(fmt: BarcodeFormat?): Int {
        return when (fmt) {
            BarcodeFormat.DATA_MATRIX -> Barcode.FORMAT_DATA_MATRIX
            BarcodeFormat.QR_CODE -> Barcode.FORMAT_QR_CODE
            BarcodeFormat.CODE_128 -> Barcode.FORMAT_CODE_128
            BarcodeFormat.EAN_13 -> Barcode.FORMAT_EAN_13
            BarcodeFormat.EAN_8 -> Barcode.FORMAT_EAN_8
            BarcodeFormat.CODE_39 -> Barcode.FORMAT_CODE_39
            else -> Barcode.FORMAT_UNKNOWN
        }
    }

    private fun applyZoomRatio(requestedZoom: Float, showHudHint: Boolean) {
        try {
            val ctrl = cameraControl ?: return
            val finalZoom = max(MIN_ZOOM, min(maxSupportedZoom, requestedZoom))
            currentZoom = finalZoom
            ctrl.setZoomRatio(finalZoom)

            mainHandler.post {
                if (isViewDestroyed) return@post
                tvZoomLevel?.text = String.format(Locale.US, "%.1fx", finalZoom)
                updateZoomChipsSelection(finalZoom)
                if (showHudHint && isScanning) {
                    tvScanStatus?.apply {
                        text = if (isArabic) {
                            String.format(Locale.US, "🔍 تكبير %.1fx — وضع العدسة المجهرية", finalZoom)
                        } else {
                            String.format(Locale.US, "🔍 Zoom %.1fx — Extreme Micro Lens", finalZoom)
                        }
                        setTextColor(COLOR_READY)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyZoomRatio error: ${e.message}")
        }
    }

    private fun closeImageProxySafely(imageProxy: ImageProxy?) {
        try {
            imageProxy?.close()
        } catch (_: Exception) {
        }
    }

    // ==================== Detection ====================

    private fun onBarcodeDetected(rawData: String?, format: Int, fromHighPrecisionRoi: Boolean) {
        if (rawData.isNullOrBlank()) return
        val cleanData = extractCleanCodePayload(rawData.trim())

        val now = System.currentTimeMillis()
        val cooldown = if (continuousBatchMode) BATCH_SCAN_COOLDOWN_MS else SINGLE_SCAN_COOLDOWN_MS

        if (now - lastScanTime < cooldown) return
        if (isCompleted && !continuousBatchMode) return

        val requiredHits = if (fromHighPrecisionRoi) 1 else 2

        if (candidateCode == null || candidateCode != cleanData || now - candidateTime > 320L) {
            candidateCode = cleanData
            candidateTime = now
            candidateConfirmations = 1
        } else {
            candidateConfirmations++
        }

        if (candidateConfirmations < requiredHits) return

        lastScanTime = now
        candidateCode = null
        candidateConfirmations = 0

        if (!continuousBatchMode) {
            isCompleted = true
            isScanning = false
        } else {
            batchScannedCount++
        }

        Log.i(TAG, "✅ KOTLIN ULTRA-MICRO CONFIRMED ($format): $cleanData")

        playBeepSafe()
        val formatName = getBarcodeFormatName(format)
        val currentBatchCount = batchScannedCount

        safeRun {
            try {
                tvScanStatus?.apply {
                    text = if (continuousBatchMode) {
                        if (isArabic) {
                            "✅ تم مسح ($currentBatchCount) — جاهز للقطعة التالية"
                        } else {
                            "✅ Scanned ($currentBatchCount) — Ready for next"
                        }
                    } else {
                        if (isArabic) "✅ تم التقاط الكود المجهري بنجاح" else "✅ Micro-Code Captured"
                    }
                    setTextColor(COLOR_SUCCESS)
                }
                tvScannedData?.apply {
                    text = cleanData
                    visibility = View.VISIBLE
                }
            } catch (_: Exception) {
            }
        }

        var delivered = false
        val listener = barcodeListener
        if (listener != null) {
            try {
                listener.onBarcodeScanned(cleanData, formatName)
                delivered = true
            } catch (e: Exception) {
                Log.e(TAG, "barcodeListener error: ${e.message}")
            }
        }

        if (!delivered && !continuousBatchMode) {
            forwardToPOS(cleanData)
        }

        if (!continuousBatchMode) {
            mainHandler.postDelayed({
                if (isAdded && !isViewDestroyed) {
                    closeScanner()
                }
            }, 420L)
        }
    }

    private fun extractCleanCodePayload(raw: String): String {
        return try {
            if (raw.startsWith("http://") || raw.startsWith("https://")) {
                val queryIdx = raw.indexOf("code=")
                if (queryIdx != -1) {
                    val sub = raw.substring(queryIdx + 5)
                    val amp = sub.indexOf('&')
                    return if (amp != -1) sub.substring(0, amp) else sub
                }
                val lastSlash = raw.lastIndexOf('/')
                if (lastSlash != -1 && lastSlash < raw.length - 1) {
                    val tail = raw.substring(lastSlash + 1).trim()
                    if (tail.isNotEmpty() && !tail.equals("verify", ignoreCase = true)) {
                        return tail
                    }
                }
            }
            val ai21 = raw.indexOf("(21)")
            if (ai21 != -1) {
                val after21 = raw.substring(ai21 + 4)
                val nextParen = after21.indexOf('(')
                return if (nextParen != -1) after21.substring(0, nextParen).trim() else after21.trim()
            }
            raw
        } catch (_: Exception) {
            raw
        }
    }

    private fun forwardToPOS(data: String) {
        try {
            if (!isAdded) return
            val act = activity as? UHFMainActivity ?: return
            val pos = act.supportFragmentManager.findFragmentByTag(NavigationManager.TAG_POS)

            if (pos is MiraPosFragment) {
                try {
                    pos.onBarcodeScanned(data)
                } catch (_: Exception) {
                    act.onTagRead(data)
                }
            } else {
                act.onTagRead(data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error forwarding to POS: ${e.message}")
        }
    }

    private fun playBeepSafe() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 130)
        } catch (_: Exception) {
        }
    }

    private fun closeScanner() {
        if (isViewDestroyed) return
        isViewDestroyed = true
        isScanning = false
        isCompleted = true
        mainHandler.removeCallbacks(periodicFocusRunnable)
        unregisterHardware2DScannerReceiver()

        try {
            scanLine?.animate()?.cancel()
        } catch (_: Exception) {
        }

        try {
            cameraControl?.enableTorch(false)
            isFlashOn = false
        } catch (_: Exception) {
        }

        if (isCameraBound && cameraProvider != null) {
            try {
                cameraProvider?.unbindAll()
                isCameraBound = false
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding: ${e.message}")
            }
        }

        backCallback?.let {
            try {
                it.isEnabled = false
                it.remove()
            } catch (_: Exception) {
            }
            backCallback = null
        }

        if (!isAdded) return

        val fm = parentFragmentManager
        if (fm.backStackEntryCount > 0) {
            try {
                fm.popBackStack()
            } catch (_: Exception) {
                fallbackClose()
            }
        } else {
            fallbackClose()
        }
    }

    private fun fallbackClose() {
        try {
            if (isAdded) {
                activity?.onBackPressedDispatcher?.onBackPressed()
            }
        } catch (_: Exception) {
        }
    }

    private fun showErrorAndClose() {
        safeRun {
            showToastSafe(
                if (isArabic) "❌ تعذر تشغيل الكاميرا" else "❌ Failed to start camera",
                Toast.LENGTH_LONG
            )
            closeScanner()
        }
    }

    private fun startScanAnimation() {
        val line = scanLine ?: return
        val overlay = scannerOverlay ?: return
        if (isViewDestroyed || !isScanning) return

        try {
            line.animate().cancel()
            line.translationY = 0f

            line.post {
                if (isViewDestroyed || !isScanning) return@post
                val activeLine = scanLine ?: return@post
                val activeOverlay = scannerOverlay ?: return@post

                val height = activeOverlay.height
                if (height <= 0) {
                    activeLine.postDelayed({ startScanAnimation() }, 100L)
                    return@post
                }

                activeLine.animate()
                    .translationY((height - 2).toFloat())
                    .setDuration(1500L)
                    .withEndAction {
                        if (scanLine == null || isViewDestroyed || !isScanning) return@withEndAction
                        scanLine?.translationY = 0f
                        startScanAnimation()
                    }
                    .start()
            }
        } catch (_: Exception) {
        }
    }

    fun toggleFlash() {
        try {
            val ctrl = cameraControl ?: return
            isFlashOn = !isFlashOn
            ctrl.enableTorch(isFlashOn)
            if (isFlashOn && exposureLevelStep == 0) {
                exposureLevelStep = 1
                applyExposureStep()
                updateAntiGlareButtonText()
            }
            showToastSafe(
                if (isFlashOn) {
                    if (isArabic) "🔦 تم تشغيل الإضاءة + حماية الوهج" else "🔦 Flash On + Anti-Glare"
                } else {
                    if (isArabic) "🔌 تم إطفاء الإضاءة" else "🔌 Flash Off"
                },
                Toast.LENGTH_SHORT
            )
        } catch (_: Exception) {
        }
    }

    private fun safeRun(r: () -> Unit) {
        try {
            val act = activity
            if (isAdded && act != null && !isViewDestroyed) {
                act.runOnUiThread {
                    try {
                        if (isAdded && !isViewDestroyed) r()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun showToastSafe(msg: String, duration: Int) {
        try {
            val ctx = appContext
            if (isAdded && ctx != null) {
                Toast.makeText(ctx, msg, duration).show()
            }
        } catch (_: Exception) {
        }
    }

    private fun getBarcodeFormatName(format: Int): String {
        return when (format) {
            Barcode.FORMAT_DATA_MATRIX -> "GS1 DataMatrix"
            Barcode.FORMAT_QR_CODE -> "QR Code"
            Barcode.FORMAT_EAN_13 -> "EAN-13"
            Barcode.FORMAT_EAN_8 -> "EAN-8"
            Barcode.FORMAT_CODE_128 -> "Code-128"
            Barcode.FORMAT_CODE_39 -> "Code-39"
            else -> "Barcode"
        }
    }

    fun pauseScanning() {
        isScanning = false
        try {
            scanLine?.animate()?.cancel()
        } catch (_: Exception) {
        }
    }

    fun resumeScanning() {
        if (isViewDestroyed) return
        isScanning = true
        lastScanTime = 0L
        candidateCode = null
        candidateConfirmations = 0
        startScanAnimation()
    }

    override fun onDestroyView() {
        isViewDestroyed = true
        isScanning = false
        mainHandler.removeCallbacksAndMessages(null)
        unregisterHardware2DScannerReceiver()
        zoomChipViews.clear()

        try {
            scanLine?.animate()?.cancel()
        } catch (_: Exception) {
        }

        backCallback?.let {
            try {
                it.isEnabled = false
                it.remove()
            } catch (_: Exception) {
            }
            backCallback = null
        }

        if (cameraProvider != null) {
            try {
                cameraProvider?.unbindAll()
                cameraProvider = null
            } catch (_: Exception) {
            }
        }
        cameraControl = null
        camera = null

        scaleDetector = null
        currentZoom = 1.0f
        candidateCode = null
        candidateConfirmations = 0

        previewView = null
        scannerOverlay = null
        scanLine = null
        tvScanStatus = null
        tvScannedData = null
        tvZoomLevel = null
        btnClose = null
        btnFlash = null
        btnAntiGlareChip = null

        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            cameraControl?.enableTorch(false)
        } catch (_: Exception) {
        }

        try {
            cameraExecutor?.shutdown()
        } catch (_: Exception) {
        }
        cameraExecutor = null

        try {
            barcodeScanner?.close()
        } catch (_: Exception) {
        }
        barcodeScanner = null

        try {
            toneGenerator?.release()
        } catch (_: Exception) {
        }
        toneGenerator = null

        barcodeListener = null
        appContext = null
    }

    companion object {
        private const val TAG = "MiraKotlinMicro"
        private const val CAMERA_PERMISSION_CODE = 1001

        private const val SINGLE_SCAN_COOLDOWN_MS = 700L
        private const val BATCH_SCAN_COOLDOWN_MS = 1050L

        private val COLOR_READY = Color.parseColor("#38BDF8")
        private val COLOR_SUCCESS = Color.parseColor("#10B981")
        private val COLOR_GOLD = Color.parseColor("#D4AF37")

        private const val MIN_ZOOM = 1.0f
        private const val DEFAULT_MICRO_ZOOM = 2.3f
        private const val MAX_ZOOM_FALLBACK = 6.5f

        private val ZOOM_PRESETS = floatArrayOf(1.0f, 2.3f, 3.6f, 5.2f)
    }
}
