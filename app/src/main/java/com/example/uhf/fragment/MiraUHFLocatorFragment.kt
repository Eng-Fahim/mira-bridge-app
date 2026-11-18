package com.example.uhf.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.api.MiraApiClient
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.manager.PermissionManager
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import com.rscja.deviceapi.interfaces.IUHF
import com.rscja.deviceapi.interfaces.IUHFLocationCallback
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

class MiraUHFLocatorFragment : KeyDwonFragment() {

    companion object {
        private const val TAG = "MiraUHFLocator"

        // KeyCodes للزناد الفيزيائي
        private const val KEYCODE_TRIGGER_1 = 139
        private const val KEYCODE_TRIGGER_2 = 280
        private const val KEYCODE_TRIGGER_3 = 293

        // Geiger Feedback
        private const val BEEP_MIN_INTERVAL = 80L
        private const val BEEP_MAX_INTERVAL = 800L

        // Signal Smoothing (EMA)
        private const val SMOOTHING_FACTOR = 0.15f

        // Median Filter — نافذة 7 قراءات
        private const val MEDIAN_WINDOW = 7

        // Auto-Snap
        private const val SNAP_THRESHOLD = 0.85f
        private const val SNAP_HOLD_MS = 2000L

        // Reader Settings
        private const val READER_POWER_DBM = 20
        private const val DYNAMIC_DISTANCE = 15
    }

    data class HistoryEntry(
        val epc: String,
        val title: String,
        val time: String,
        val found: Boolean
    )

    private data class LocatedItemInfo(
        val title: String,
        val epc: String,
        val karat: String,
        val weight: String,
        val location: String,
        val statusText: String,
        val statusColor: Color
    )

    private val rawValueWindow = IntArray(MEDIAN_WINDOW)
    private var windowIndex = 0
    private var windowFill = 0

    @Volatile
    private var isLocating = false
    private var isArabic = false
    private var currentValue = 100

    private var smoothedProximity = 0f
    private var smoothingInitialized = false

    private var snapStartTime = 0L
    private var snapOffered = false

    private var sessionStartTime = 0L
    private var totalReadings = 0
    private var bestProximity = 0f
    private var timeToFindMs = 0L

    private var mainActivity: UHFMainActivity? = null
    private var permissionManager: PermissionManager? = null
    private var branchesManager: MiraBranchesManager? = null
    private var vibrator: Vibrator? = null
    private var mainHandler: Handler? = null
    private var executor: ExecutorService? = null
    private var toneGenerator: ToneGenerator? = null

    private var lastBeepTime = 0L
    private var currentEpc = ""
    private var currentTitle = ""
    private var backCallback: OnBackPressedCallback? = null

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose Reactive States
    // ═══════════════════════════════════════════════════════════════════

    private var epcInputState by mutableStateOf("")
    private var isLocatingState by mutableStateOf(false)
    private var isReaderConnectedState by mutableStateOf(false)

    private var proximityState by mutableFloatStateOf(0f)
    private var bestProximityState by mutableFloatStateOf(0f)
    private var flashFoundTriggerState by mutableIntStateOf(0)

    private var statusTextState by mutableStateOf("")
    private var statusColorState by mutableStateOf(Color(0xFF94A3B8))
    private var distanceTextState by mutableStateOf("—")
    private var debugTextState by mutableStateOf("🔬 جاهز")
    private var showMarkFoundState by mutableStateOf(false)

    private var itemInfoState by mutableStateOf<LocatedItemInfo?>(null)
    private val historyListState = mutableStateListOf<HistoryEntry>()

    private var statsDialogMessageState by mutableStateOf<String?>(null)
    private var exportDialogCsvState by mutableStateOf<String?>(null)

    // ═══════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MiraJewelryTheme {
                    UhfLocatorScreen()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            mainActivity = activity as? UHFMainActivity
            if (mainActivity == null) return

            isArabic = AppCompatDelegate.getApplicationLocales()
                .toLanguageTags().contains("ar") ||
                resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

            view.layoutDirection = if (isArabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

            statusTextState = if (isArabic) "جاهز" else "Ready"
            debugTextState = if (isArabic) "🔬 متوقف" else "🔬 Idle"

            mainHandler = Handler(Looper.getMainLooper())
            executor = Executors.newSingleThreadExecutor()

            @Suppress("DEPRECATION")
            vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
            } catch (e: Exception) {
                Log.w(TAG, "ToneGenerator init failed", e)
            }

            permissionManager = PermissionManager.getInstance(requireContext())
            branchesManager = MiraBranchesManager.getInstance(requireContext())

            setupBackPress()
            updateReaderStatus()
        } catch (e: Exception) {
            Log.e(TAG, "onViewCreated error", e)
        }
    }

    override fun onResume() {
        super.onResume()
        updateReaderStatus()
        mainActivity?.setActiveRfidListener { epc -> onTagScanned(epc) }
    }

    override fun onPause() {
        super.onPause()
        stopLocating()
        mainActivity?.setActiveRfidListener(null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopLocating()
        backCallback?.remove()
        backCallback = null
        executor?.shutdownNow()
        toneGenerator?.let {
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        toneGenerator = null
    }

    private fun setupBackPress() {
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isLocating) {
                    stopLocating()
                } else {
                    navigateAway()
                }
            }
        }
        requireActivity().onBackPressedDispatcher
            .addCallback(viewLifecycleOwner, backCallback!!)
    }

    override fun myOnKeyDwon(keyCode: Int, event: KeyEvent): Boolean {
        val isTrigger = keyCode == KEYCODE_TRIGGER_1 ||
            keyCode == KEYCODE_TRIGGER_2 ||
            keyCode == KEYCODE_TRIGGER_3

        if (!isTrigger) return false

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (isLocating) {
                stopLocating()
            } else {
                startLocating()
            }
            return true
        }

        return event.action == KeyEvent.ACTION_UP
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Start / Stop Locating
    // ═══════════════════════════════════════════════════════════════════

    private fun startLocating() {
        if (isLocating) return

        val act = mainActivity
        if (act == null || act.mReader == null) {
            toast(if (isArabic) "⚠️ القارئ غير متصل" else "⚠️ Reader not connected")
            return
        }

        val epc = epcInputState.trim()
        if (epc.isEmpty()) {
            toast(if (isArabic) "⚠️ أدخل EPC أولاً" else "⚠️ Enter EPC first")
            return
        }

        currentEpc = epc
        resetSessionState()

        try {
            try {
                act.mReader.setPower(READER_POWER_DBM)
                Log.d(TAG, "✅ setPower($READER_POWER_DBM)")
            } catch (e: Exception) {
                Log.w(TAG, "setPower failed: ${e.message}")
            }

            try {
                act.mReader.setDynamicDistance(DYNAMIC_DISTANCE)
                Log.d(TAG, "✅ setDynamicDistance($DYNAMIC_DISTANCE)")
            } catch (e: Exception) {
                Log.w(TAG, "setDynamicDistance failed: ${e.message}")
            }

            val started = act.mReader.startLocation(
                act,
                epc,
                IUHF.Bank_EPC,
                32,
                object : IUHFLocationCallback {
                    override fun getLocationValue(value: Int, valid: Boolean) {
                        handleLocationValue(value, valid)
                    }
                }
            )

            if (!started) {
                toast(if (isArabic) "❌ فشل بدء التتبع" else "❌ Failed to start")
                return
            }

            isLocating = true
            isLocatingState = true
            sessionStartTime = System.currentTimeMillis()
            onStartUI()
            queryMiraItem(epc)

            Log.i(TAG, "✅ Locator started for: $epc")
        } catch (e: Exception) {
            Log.e(TAG, "startLocating error", e)
            toast(if (isArabic) "❌ خطأ" else "❌ Error")
        }
    }

    private fun stopLocating() {
        if (!isLocating) return

        try {
            mainActivity?.mReader?.stopLocation()
        } catch (e: Exception) {
            Log.w(TAG, "stopLocation error", e)
        }

        isLocating = false
        isLocatingState = false
        onStopUI()
        Log.i(TAG, "⏹️ Locator stopped")
    }

    private fun resetSessionState() {
        smoothedProximity = 0f
        smoothingInitialized = false
        snapStartTime = 0L
        snapOffered = false
        totalReadings = 0
        bestProximity = 0f
        bestProximityState = 0f
        timeToFindMs = 0L

        Arrays.fill(rawValueWindow, 0)
        windowIndex = 0
        windowFill = 0

        debugTextState = if (isArabic) "🔬 جاري البدء..." else "🔬 Starting..."
        proximityState = 0f
    }

    private fun onStartUI() {
        statusTextState = if (isArabic) "📡 جاري البحث..." else "📡 Searching..."
        statusColorState = Color(0xFF38BDF8)
        showMarkFoundState = false
    }

    private fun onStopUI() {
        proximityState = 0f
        statusTextState = if (isArabic) "جاهز" else "Ready"
        statusColorState = Color(0xFF94A3B8)
        distanceTextState = "—"
        showMarkFoundState = false
        debugTextState = if (isArabic) "🔬 متوقف" else "🔬 Stopped"
        resetAutoSnap()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Core: Median Filter + EMA Smoothing + Geiger Feedback
    // ═══════════════════════════════════════════════════════════════════

    private fun handleLocationValue(value: Int, valid: Boolean) {
        if (!valid) return

        totalReadings++
        currentValue = value

        // 1. Median Filter (يرفض القيم الشاذة)
        rawValueWindow[windowIndex] = value
        windowIndex = (windowIndex + 1) % MEDIAN_WINDOW
        if (windowFill < MEDIAN_WINDOW) windowFill++

        val filteredValue = medianOfWindow()

        // 2. تحويل القيمة المفلترة إلى نسبة قرب
        val rawProximity = ((80f - filteredValue) / 60f).coerceIn(0f, 1f)

        // 3. EMA Smoothing
        if (!smoothingInitialized) {
            smoothedProximity = rawProximity
            smoothingInitialized = true
        } else {
            smoothedProximity = (SMOOTHING_FACTOR * rawProximity) +
                ((1f - SMOOTHING_FACTOR) * smoothedProximity)
        }

        if (smoothedProximity > bestProximity) {
            bestProximity = smoothedProximity
        }

        val finalRaw = value
        val finalFiltered = filteredValue
        val finalCount = totalReadings
        val finalProximity = smoothedProximity
        val finalBest = bestProximity

        mainHandler?.post {
            debugTextState = "🔬 $finalCount | خام: $finalRaw | فلتر: $finalFiltered"

            if (!isLocating) return@post

            proximityState = finalProximity
            bestProximityState = finalBest
            distanceTextState = estimateDistance(finalFiltered)
            updateStatusText(finalProximity)
            handleAutoSnap(finalProximity)
            geigerFeedback(finalFiltered)
        }
    }

    private fun medianOfWindow(): Int {
        if (windowFill == 0) return 100
        val copy = IntArray(windowFill)
        System.arraycopy(rawValueWindow, 0, copy, 0, windowFill)
        Arrays.sort(copy)
        return copy[copy.size / 2]
    }

    private fun updateStatusText(proximity: Float) {
        when {
            proximity >= 0.75f -> {
                statusTextState = if (isArabic) {
                    "🔥 ملاصق جداً — $currentTitle"
                } else {
                    "🔥 Extremely Close — $currentTitle"
                }
                statusColorState = Color(0xFF22C55E)
                showMarkFoundState = true
            }
            proximity >= 0.50f -> {
                statusTextState = if (isArabic) {
                    "📍 قريب — $currentTitle"
                } else {
                    "📍 Near — $currentTitle"
                }
                statusColorState = Color(0xFFEAB308)
                showMarkFoundState = false
            }
            proximity >= 0.25f -> {
                statusTextState = if (isArabic) {
                    "🎯 متوسط — $currentTitle"
                } else {
                    "🎯 Medium — $currentTitle"
                }
                statusColorState = Color(0xFFF97316)
                showMarkFoundState = false
            }
            else -> {
                statusTextState = if (isArabic) {
                    "🌐 بعيد — $currentTitle"
                } else {
                    "🌐 Far — $currentTitle"
                }
                statusColorState = Color(0xFFEF4444)
                showMarkFoundState = false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Auto-Snap
    // ═══════════════════════════════════════════════════════════════════

    private fun handleAutoSnap(proximity: Float) {
        if (snapOffered) return

        if (proximity >= SNAP_THRESHOLD) {
            val now = System.currentTimeMillis()
            if (snapStartTime == 0L) {
                snapStartTime = now
            } else if (now - snapStartTime >= SNAP_HOLD_MS) {
                triggerAutoSnap()
            }
        } else {
            snapStartTime = 0L
        }
    }

    private fun triggerAutoSnap() {
        snapOffered = true
        flashFoundTriggerState += 1
        performHapticCelebration()
        playBeep(ToneGenerator.TONE_CDMA_HIGH_L, 150)

        toast(
            if (isArabic) "🎯 إشارة قوية! اضغط ✅ للتأكيد"
            else "🎯 Strong signal! Tap ✅ to confirm"
        )
    }

    private fun resetAutoSnap() {
        snapStartTime = 0L
        snapOffered = false
    }

    private fun performHapticCelebration() {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        val pattern = longArrayOf(0, 80, 40, 80, 40, 200)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
        }
    }

    private fun estimateDistance(value: Int): String {
        if (value < 25) return if (isArabic) "📏 أقل من 10 سم" else "📏 < 10 cm"
        if (value < 35) return if (isArabic) "📏 10-30 سم" else "📏 10-30 cm"
        if (value < 45) return if (isArabic) "📏 30-60 سم" else "📏 30-60 cm"
        if (value < 55) return if (isArabic) "📏 0.6-1 متر" else "📏 0.6-1 m"
        if (value < 65) return if (isArabic) "📏 1-2 متر" else "📏 1-2 m"
        if (value < 75) return if (isArabic) "📏 2-4 متر" else "📏 2-4 m"
        return if (isArabic) "📏 > 4 متر" else "📏 > 4 m"
    }

    private fun geigerFeedback(value: Int) {
        val now = System.currentTimeMillis()
        val interval = (BEEP_MIN_INTERVAL +
            (BEEP_MAX_INTERVAL - BEEP_MIN_INTERVAL) *
            Math.min(1.0, value / 80.0)).toLong()

        if (now - lastBeepTime < interval) return
        lastBeepTime = now

        val tone: Int
        val duration: Int
        when {
            value < 30 -> {
                tone = ToneGenerator.TONE_CDMA_HIGH_L
                duration = 80
            }
            value < 45 -> {
                tone = ToneGenerator.TONE_PROP_BEEP
                duration = 60
            }
            value < 60 -> {
                tone = ToneGenerator.TONE_PROP_BEEP
                duration = 40
            }
            else -> {
                tone = ToneGenerator.TONE_PROP_BEEP
                duration = 25
            }
        }
        playBeep(tone, duration)

        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        try {
            val pattern = when {
                value < 30 -> longArrayOf(0, 60, 30, 60, 30, 60)
                value < 45 -> longArrayOf(0, 50, 50, 50)
                value < 60 -> longArrayOf(0, 40)
                else -> longArrayOf(0, 15)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
        }
    }

    private fun playBeep(toneType: Int, durationMs: Int) {
        try {
            toneGenerator?.startTone(toneType, durationMs)
        } catch (e: Exception) {
            Log.w(TAG, "playBeep failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  MIRA Integration
    // ═══════════════════════════════════════════════════════════════════

    private fun searchMiraByInput() {
        val input = epcInputState.trim()
        if (input.isEmpty()) {
            toast(if (isArabic) "أدخل EPC / Serial" else "Enter EPC / Serial")
            return
        }
        currentEpc = input
        queryMiraItem(input)
    }

    private fun queryMiraItem(epc: String) {
        val exec = executor ?: return
        if (exec.isShutdown) return

        exec.execute {
            try {
                val ctx = context ?: return@execute
                val api = MiraApiClient.getInstance(ctx) ?: return@execute

                val extra = JSONObject()
                branchesManager?.let { bm ->
                    extra.put("branch_id", bm.activeBranchId)
                }

                val resp = api.authorize(epc, "locator", extra)

                if (resp != null && resp.isSuccess && resp.data != null) {
                    val item = resp.data!!.optJSONObject("item")
                    if (item != null) {
                        val title = item.optString("title", if (isArabic) "قطعة" else "Item")
                        val karat = item.optString("karat", "")
                        val weight = item.optString("weight", "")
                        val location = item.optString("location", "—")
                        val status = item.optString("status", "")

                        mainHandler?.post {
                            if (!isAdded) return@post
                            currentTitle = title
                            val (statusLabel, statusCol) = mapStatus(status)
                            itemInfoState = LocatedItemInfo(
                                title = title,
                                epc = epc,
                                karat = karat,
                                weight = weight,
                                location = location,
                                statusText = statusLabel,
                                statusColor = statusCol
                            )
                        }
                    }
                } else {
                    mainHandler?.post {
                        if (!isAdded) return@post
                        currentTitle = if (isArabic) "غير مسجلة" else "Unregistered"
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "queryMiraItem error", e)
            }
        }
    }

    private fun mapStatus(status: String?): Pair<String, Color> {
        return when (status) {
            "in_stock", "available" -> (if (isArabic) "✅ متاح" else "✅ Available") to Color(0xFF22C55E)
            "sold" -> (if (isArabic) "🔴 مباع" else "🔴 Sold") to Color(0xFFEF4444)
            "display" -> (if (isArabic) "👁️ معروض" else "👁️ On Display") to Color(0xFF06B6D4)
            "reserved" -> (if (isArabic) "⏳ محجوز" else "⏳ Reserved") to Color(0xFFF59E0B)
            else -> (status ?: "") to Color(0xFF22C55E)
        }
    }

    private fun markAsFound() {
        if (currentEpc.isEmpty()) return

        if (sessionStartTime > 0) {
            timeToFindMs = System.currentTimeMillis() - sessionStartTime
        }

        addHistory(currentEpc, currentTitle, true)
        prepareSessionStats()

        toast(if (isArabic) "✅ تم تسجيل القطعة" else "✅ Item marked")
        stopLocating()
    }

    private fun prepareSessionStats() {
        val seconds = timeToFindMs / 1000
        val timeStr = if (seconds < 60) {
            seconds.toString() + (if (isArabic) " ثانية" else " sec")
        } else {
            (seconds / 60).toString() + (if (isArabic) " د " else " min ") +
                (seconds % 60) + (if (isArabic) " ث" else " sec")
        }

        val bestPct = (bestProximity * 100).toInt()
        val readings = totalReadings

        statsDialogMessageState = if (isArabic) {
            "⏱️ وقت البحث: $timeStr\n" +
                "📊 عدد القراءات: $readings\n" +
                "🏆 أفضل إشارة: $bestPct%\n" +
                "🎯 تم العثور على: $currentTitle"
        } else {
            "⏱️ Search time: $timeStr\n" +
                "📊 Total readings: $readings\n" +
                "🏆 Best signal: $bestPct%\n" +
                "🎯 Found: $currentTitle"
        }
    }

    private fun onTagScanned(epc: String?) {
        if (epc.isNullOrEmpty()) return
        if (isLocating) return
        if (epc == currentEpc) return

        mainHandler?.post {
            if (!isLocating) {
                epcInputState = epc
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  History & Export
    // ═══════════════════════════════════════════════════════════════════

    private fun addHistory(epc: String, title: String, found: Boolean) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        historyListState.add(0, HistoryEntry(epc, title, time, found))
        if (historyListState.size > 20) {
            historyListState.removeAt(historyListState.size - 1)
        }
    }

    private fun exportHistory() {
        if (historyListState.isEmpty()) {
            toast(if (isArabic) "لا توجد سجلات" else "No records")
            return
        }

        val sb = StringBuilder("EPC,Title,Time,Found\n")
        for (h in historyListState) {
            sb.append(h.epc).append(",")
                .append(h.title).append(",")
                .append(h.time).append(",")
                .append(if (h.found) "YES" else "NO").append("\n")
        }
        exportDialogCsvState = sb.toString()
    }

    private fun updateReaderStatus() {
        isReaderConnectedState = mainActivity?.mReader != null
    }

    private fun navigateAway() {
        try {
            stopLocating()
            mainActivity?.navigationManager?.goToDashboard()
        } catch (_: Exception) {
        }
    }

    private fun toast(msg: String) {
        if (isAdded && context != null) {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose UI
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun UhfLocatorScreen() {
        val colors = MiraTheme.colors
        val isAr = isArabic
        val keyboardController = LocalSoftwareKeyboardController.current

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.backgroundTop
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(colors.backgroundTop, colors.backgroundMid, colors.backgroundTop)
                        )
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    // 1. الشريط العلوي
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clickable { navigateAway() },
                                shape = RoundedCornerShape(12.dp),
                                color = colors.cardSurface,
                                border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.2f))
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = null,
                                        tint = colors.textPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = if (isAr) "رادار تحديد القطعة" else "UHF Item Locator",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = if (isReaderConnectedState) {
                                        if (isAr) "📡 القارئ: متصل ✓" else "📡 Reader: Connected ✓"
                                    } else {
                                        if (isAr) "📡 القارئ: غير متصل" else "📡 Reader: Disconnected"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isReaderConnectedState) Color(0xFF22C55E) else Color(0xFFEF4444)
                                )
                            }
                        }

                        // شارة التشخيص المباشر (Debug Telemetry)
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = colors.cardSurface,
                            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = debugTextState,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                maxLines = 1
                            )
                        }
                    }

                    // 2. المحتوى القابل للتمرير
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // أ. بطاقة الرادار التفاعلي ومقياس القرب
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                            border = BorderStroke(
                                1.5.dp,
                                if (isLocatingState) statusColorState.copy(alpha = 0.55f)
                                else colors.goldPrimary.copy(alpha = 0.3f)
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                MiraProximityRadarCanvas(
                                    proximity = proximityState,
                                    bestProximity = bestProximityState,
                                    isActive = isLocatingState,
                                    flashTrigger = flashFoundTriggerState,
                                    distanceLabel = distanceTextState,
                                    modifier = Modifier.size(220.dp)
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = statusTextState,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = statusColorState,
                                    textAlign = TextAlign.Center
                                )

                                // زر "تم العثور" يظهر عند الاقتراب الشديد (>= 75%)
                                AnimatedVisibility(visible = showMarkFoundState) {
                                    Button(
                                        onClick = { markAsFound() },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 12.dp)
                                            .height(46.dp),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF22C55E),
                                            contentColor = Color(0xFF0A0F1D)
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (isAr) "✅ تم العثور على القطعة" else "✅ Mark Item Found",
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            }
                        }

                        // ب. بطاقة إدخال EPC وأزرار التحكم
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                            border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.16f))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = epcInputState,
                                    onValueChange = { epcInputState = it },
                                    enabled = !isLocatingState,
                                    singleLine = true,
                                    label = {
                                        Text(if (isAr) "أدخل EPC أو Serial" else "Enter EPC / Serial")
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Contactless,
                                            contentDescription = null,
                                            tint = colors.goldPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    trailingIcon = {
                                        if (epcInputState.isNotEmpty() && !isLocatingState) {
                                            IconButton(onClick = { epcInputState = "" }) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Clear,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = {
                                        keyboardController?.hide()
                                        searchMiraByInput()
                                    }),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                // أزرار التشغيل والإيقاف والبحث
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // زر البدء
                                    Button(
                                        onClick = {
                                            keyboardController?.hide()
                                            startLocating()
                                        },
                                        enabled = !isLocatingState,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = colors.goldPrimary,
                                            contentColor = Color(0xFF0A0F1D)
                                        )
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isAr) "بدء" else "Start",
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }

                                    // زر الإيقاف
                                    Button(
                                        onClick = { stopLocating() },
                                        enabled = isLocatingState,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFFEF4444),
                                            contentColor = Color.White
                                        )
                                    ) {
                                        Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isAr) "إيقاف" else "Stop",
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }

                                    // زر الاستعلام من MIRA
                                    OutlinedButton(
                                        onClick = {
                                            keyboardController?.hide()
                                            searchMiraByInput()
                                        },
                                        enabled = !isLocatingState,
                                        modifier = Modifier.height(46.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.5f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF06B6D4))
                                    ) {
                                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(19.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isAr) "بحث" else "Lookup",
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        // ج. بطاقة بيانات القطعة المستعلم عنها من MIRA
                        AnimatedVisibility(visible = itemInfoState != null) {
                            itemInfoState?.let { info ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                                    border = BorderStroke(1.dp, info.statusColor.copy(alpha = 0.45f))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Inventory2,
                                                    contentDescription = null,
                                                    tint = colors.goldPrimary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = info.title,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = colors.textPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = info.statusColor.copy(alpha = 0.16f)
                                            ) {
                                                Text(
                                                    text = info.statusText,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = info.statusColor,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                )
                                            }
                                        }

                                        Text(
                                            text = buildString {
                                                append("🏷️ ").append(info.epc)
                                                if (info.karat.isNotEmpty()) append("  •  💎 ").append(info.karat)
                                                if (info.weight.isNotEmpty()) append("  •  ⚖️ ").append(info.weight).append("g")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.textSecondary
                                        )

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Rounded.LocationOn,
                                                contentDescription = null,
                                                tint = Color(0xFF06B6D4),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = info.location,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF06B6D4)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // د. سجل جلسات التتبع والتصدير
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                            border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.16f))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Rounded.History,
                                            contentDescription = null,
                                            tint = colors.goldPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isAr) "سجل التتبع (${historyListState.size})"
                                            else "Tracking History (${historyListState.size})",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.textPrimary
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = { exportHistory() },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.45f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.goldPrimary)
                                    ) {
                                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isAr) "تصدير" else "Export",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                if (historyListState.isEmpty()) {
                                    Text(
                                        text = if (isAr) "لا توجد سجلات تتبع في هذه الجلسة" else "No tracking records yet",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                } else {
                                    historyListState.forEach { entry ->
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(enabled = !isLocatingState) {
                                                    epcInputState = entry.epc
                                                },
                                            shape = RoundedCornerShape(12.dp),
                                            color = colors.backgroundMid
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text(
                                                        text = if (entry.found) "✅" else "🔍",
                                                        fontSize = 16.sp
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Column {
                                                        Text(
                                                            text = entry.title.ifEmpty { entry.epc },
                                                            style = MaterialTheme.typography.bodySmall,
                                                            fontWeight = FontWeight.Bold,
                                                            color = colors.textPrimary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            text = entry.epc,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = colors.textSecondary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = entry.time,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = colors.goldPrimary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }

        // نافذة إحصائيات الجلسة
        statsDialogMessageState?.let { message ->
            AlertDialog(
                onDismissRequest = { statsDialogMessageState = null },
                containerColor = colors.cardSurface,
                title = {
                    Text(
                        text = if (isAr) "📈 إحصائيات الجلسة" else "📈 Session Stats",
                        color = colors.goldPrimary,
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                text = {
                    Text(
                        text = message,
                        color = colors.textPrimary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { statsDialogMessageState = null },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.goldPrimary,
                            contentColor = Color(0xFF0A0F1D)
                        )
                    ) {
                        Text(
                            text = if (isAr) "رائع!" else "Great!",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            )
        }

        // نافذة تصدير السجل ونسخه
        exportDialogCsvState?.let { csv ->
            val preview = if (csv.length > 500) csv.substring(0, 500) + "..." else csv
            AlertDialog(
                onDismissRequest = { exportDialogCsvState = null },
                containerColor = colors.cardSurface,
                title = {
                    Text(
                        text = if (isAr) "📤 تصدير السجل" else "📤 Export History",
                        color = colors.goldPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textPrimary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("MIRA History", csv))
                            toast(if (isAr) "✅ تم النسخ" else "✅ Copied")
                            exportDialogCsvState = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.goldPrimary,
                            contentColor = Color(0xFF0A0F1D)
                        )
                    ) {
                        Text(
                            text = if (isAr) "نسخ" else "Copy",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { exportDialogCsvState = null }) {
                        Text(
                            text = if (isAr) "إغلاق" else "Close",
                            color = colors.textSecondary
                        )
                    }
                }
            )
        }
    }

    @Composable
    private fun MiraProximityRadarCanvas(
        proximity: Float,
        bestProximity: Float,
        isActive: Boolean,
        flashTrigger: Int,
        distanceLabel: String,
        modifier: Modifier = Modifier
    ) {
        val colors = MiraTheme.colors

        val animatedProximity by animateFloatAsState(
            targetValue = proximity.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 160, easing = LinearOutSlowInEasing),
            label = "proximity_arc"
        )

        val infiniteTransition = rememberInfiniteTransition(label = "radar_sweep")
        val sweepAngle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "sweep_angle"
        )

        val activeColor = when {
            !isActive -> colors.textSecondary.copy(alpha = 0.45f)
            animatedProximity >= 0.75f -> Color(0xFF22C55E)
            animatedProximity >= 0.50f -> Color(0xFFEAB308)
            animatedProximity >= 0.25f -> Color(0xFFF97316)
            else -> Color(0xFFEF4444)
        }

        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxRadius = (size.minDimension / 2f) - 12.dp.toPx()

                // 1. حلقات الرادار المتحدة المركز
                val rings = listOf(1.0f, 0.75f, 0.5f, 0.25f)
                rings.forEach { factor ->
                    drawCircle(
                        color = activeColor.copy(alpha = if (isActive) 0.16f else 0.08f),
                        radius = maxRadius * factor,
                        center = center,
                        style = Stroke(width = 1.2.dp.toPx())
                    )
                }

                // 2. شعاع المسح الدوار عند التشغيل
                if (isActive) {
                    rotate(degrees = sweepAngle, pivot = center) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    activeColor.copy(alpha = 0.28f)
                                ),
                                center = center
                            ),
                            startAngle = 0f,
                            sweepAngle = 90f,
                            useCenter = true,
                            topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                            size = Size(maxRadius * 2f, maxRadius * 2f)
                        )
                    }
                }

                // 3. المسار الدائري الخلفي لقوس القرب
                val strokePx = 14.dp.toPx()
                drawArc(
                    color = colors.backgroundMid,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                    size = Size(maxRadius * 2f, maxRadius * 2f),
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )

                // 4. قوس القرب النشط
                if (animatedProximity > 0.01f) {
                    drawArc(
                        color = activeColor,
                        startAngle = 135f,
                        sweepAngle = 270f * animatedProximity,
                        useCenter = false,
                        topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                        size = Size(maxRadius * 2f, maxRadius * 2f),
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                }

                // 5. مؤشر الذروة (Peak Marker)
                if (isActive && bestProximity > 0.05f) {
                    val peakAngleRad = Math.toRadians((135f + 270f * bestProximity.coerceIn(0f, 1f)).toDouble())
                    val markerPos = Offset(
                        x = center.x + (maxRadius * cos(peakAngleRad)).toFloat(),
                        y = center.y + (maxRadius * sin(peakAngleRad)).toFloat()
                    )
                    drawCircle(
                        color = colors.goldPrimary,
                        radius = 5.dp.toPx(),
                        center = markerPos
                    )
                }

                // 6. وميض عند Auto-Snap
                if (flashTrigger > 0 && isActive && animatedProximity >= SNAP_THRESHOLD) {
                    drawCircle(
                        color = Color(0xFF22C55E).copy(alpha = 0.22f),
                        radius = maxRadius * 0.85f,
                        center = center
                    )
                }
            }

            // النصوص المركزية داخل الرادار
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.MyLocation,
                    contentDescription = null,
                    tint = activeColor,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${(animatedProximity * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.textPrimary
                )
                Text(
                    text = distanceLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = activeColor
                )
            }
        }
    }
}
