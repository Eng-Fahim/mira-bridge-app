package com.example.uhf.fragment

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.example.uhf.MiraRfid.MiraFeedback
import com.example.uhf.MiraRfid.MiraReaderSettings
import com.example.uhf.R
import com.example.uhf.activity.BaseTabFragmentActivity
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import com.rscja.deviceapi.RFIDWithUHFUART
import java.util.Locale

class MiraReaderControlFragment : Fragment() {

    companion object {
        private const val TAG = "MiraReaderControl"
    }

    private data class ReaderControlUiState(
        val powerDbm: Int = 24,
        val freqIndex: Int = 0,
        val rfLink: Int = 0,
        val session: Int = 1,
        val target: Int = 0,
        val qValue: Int = 4,
        val fastId: Boolean = false,
        val tagFocus: Boolean = false,
        val fastInventory: Boolean = false,
        val readMode: Int = 0,
        val triggerMode: Int = 0,
        val soundEnabled: Boolean = true,
        val soundVolume: Int = 80,
        val vibrateEnabled: Boolean = true,
        val vibrateMs: Int = 50,
        val toneNew: Int = 0,
        val toneDuplicate: Int = 0,
        val toneError: Int = 0,
        val soundOnPos: Boolean = true,
        val soundOnInventory: Boolean = true,
        val filterDuplicates: Boolean = true,
        val dupFilterMs: Int = 2000,
        val singleTimeoutMs: Int = 2000,
        val maxTagsText: String = "0",
        val autoStopText: String = "0",
        val keepScreenOn: Boolean = true,
        val ledFlash: Boolean = true,
        val toastEnabled: Boolean = true,
        val autoSaveLog: Boolean = false,
        val summaryText: String = "",
        val isApplying: Boolean = false,
        val isLiveTesting: Boolean = false,
        val liveReadCount: Int = 0,
        val liveRateText: String = "0.0/s",
        val liveLastEpc: String = "—",
        val liveLastRssi: Int? = null
    )

    private var isArabic: Boolean = true
    private var settings: MiraReaderSettings? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var uiState by mutableStateOf(ReaderControlUiState())

    // Live Test State
    private var liveReadThread: Thread? = null
    private var liveRateUpdater: Runnable? = null
    private var liveTestStartTime: Long = 0L
    @Volatile private var liveCountRaw: Int = 0
    @Volatile private var liveLastEpcRaw: String? = null
    @Volatile private var liveLastRssiRaw: Int? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar") ||
            Locale.getDefault().language == "ar"
        settings = MiraReaderSettings.getInstance(ctx)
        syncStateFromSettings()

        return ComposeView(ctx).apply {
            layoutDirection = if (isArabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MiraJewelryTheme {
                    ReaderControlScreen(
                        state = uiState,
                        isArabic = isArabic,
                        onBack = { handleBack() },
                        onUpdateState = { mutator ->
                            val updated = mutator(uiState)
                            uiState = updated
                            persistStateToSettings(updated)
                        },
                        onApplyNow = { applyNow() },
                        onReadCurrent = { readCurrentFromHardware() },
                        onApplyYemenDefaults = { applyYemenDefaults() },
                        onResetDefaults = { resetToDefaults() },
                        onPreviewTone = { tone, label -> playTonePreview(tone, label) },
                        onToggleLiveTest = { toggleLiveTest() }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            (activity as? UHFMainActivity)?.let { act ->
                act.setBottomNavVisible(false)
                act.findViewById<View>(R.id.fabActionCenter)?.visibility = View.GONE
            }
        } catch (e: Exception) {
            Log.w(TAG, "onStart error", e)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            (activity as? UHFMainActivity)?.let { act ->
                act.setBottomNavVisible(true)
                act.findViewById<View>(R.id.fabActionCenter)?.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            Log.w(TAG, "onStop error", e)
        }
    }

    override fun onDestroyView() {
        if (uiState.isLiveTesting) {
            stopLiveTest(showToast = false)
        }
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    private fun syncStateFromSettings() {
        val s = settings ?: return
        var freqIdx = 0
        for (i in MiraReaderSettings.FREQ_MODES.indices) {
            if (MiraReaderSettings.FREQ_MODES[i][0] == s.freqMode) {
                freqIdx = i
                break
            }
        }
        uiState = uiState.copy(
            powerDbm = s.powerDbm.coerceIn(5, 33),
            freqIndex = freqIdx,
            rfLink = s.rfLink.coerceIn(0, (MiraReaderSettings.RFLINK_LABELS.size - 1).coerceAtLeast(0)),
            session = s.session.coerceIn(0, 3),
            target = s.target.coerceIn(0, 1),
            qValue = s.qValue.coerceIn(0, 15),
            fastId = s.isFastId,
            tagFocus = s.isTagFocus,
            fastInventory = s.isFastInventory,
            readMode = s.readMode.coerceIn(0, (MiraReaderSettings.READ_MODE_LABELS.size - 1).coerceAtLeast(0)),
            triggerMode = s.triggerMode.coerceIn(0, (MiraReaderSettings.TRIGGER_MODE_LABELS.size - 1).coerceAtLeast(0)),
            soundEnabled = s.isSoundEnabled,
            soundVolume = s.soundVolume.coerceIn(0, 100),
            vibrateEnabled = s.isVibrateEnabled,
            vibrateMs = s.vibrateMs.coerceIn(0, 500),
            toneNew = s.toneNewTag,
            toneDuplicate = s.toneDuplicate,
            toneError = s.toneError,
            soundOnPos = s.isSoundOnPos,
            soundOnInventory = s.isSoundOnInventory,
            filterDuplicates = s.isFilterDuplicates,
            dupFilterMs = s.duplicateFilterMs.coerceIn(0, 10000),
            singleTimeoutMs = s.singleTimeoutMs.coerceIn(0, 10000),
            maxTagsText = s.maxTagsPerSession.toString(),
            autoStopText = s.autoStopMs.toString(),
            keepScreenOn = s.isKeepScreenOn,
            ledFlash = s.isLedFlashEnabled,
            toastEnabled = s.isToastEnabled,
            autoSaveLog = s.isAutoSaveLog,
            summaryText = s.summary ?: ""
        )
    }

    private fun persistStateToSettings(st: ReaderControlUiState) {
        val s = settings ?: return
        s.powerDbm = st.powerDbm
        if (st.freqIndex in MiraReaderSettings.FREQ_MODES.indices) {
            s.freqMode = MiraReaderSettings.FREQ_MODES[st.freqIndex][0]
        }
        s.rfLink = st.rfLink
        s.session = st.session
        s.target = st.target
        s.qValue = st.qValue
        s.isFastId = st.fastId
        s.isTagFocus = st.tagFocus
        s.isFastInventory = st.fastInventory
        s.readMode = st.readMode
        s.triggerMode = st.triggerMode
        s.isSoundEnabled = st.soundEnabled
        s.soundVolume = st.soundVolume
        s.isVibrateEnabled = st.vibrateEnabled
        s.vibrateMs = st.vibrateMs
        s.toneNewTag = st.toneNew
        s.toneDuplicate = st.toneDuplicate
        s.toneError = st.toneError
        s.isSoundOnPos = st.soundOnPos
        s.isSoundOnInventory = st.soundOnInventory
        s.isFilterDuplicates = st.filterDuplicates
        s.duplicateFilterMs = st.dupFilterMs
        s.singleTimeoutMs = st.singleTimeoutMs
        st.maxTagsText.trim().toIntOrNull()?.let { s.maxTagsPerSession = it }
        st.autoStopText.trim().toIntOrNull()?.let { s.autoStopMs = it }
        s.isKeepScreenOn = st.keepScreenOn
        s.isLedFlashEnabled = st.ledFlash
        s.isToastEnabled = st.toastEnabled
        s.isAutoSaveLog = st.autoSaveLog

        val updatedSummary = s.summary ?: ""
        if (updatedSummary != st.summaryText) {
            uiState = st.copy(summaryText = updatedSummary)
        }
    }

    private fun applyNow() {
        val s = settings ?: return
        val reader: RFIDWithUHFUART? = BaseTabFragmentActivity.getSharedReader()
        if (reader == null) {
            toast(
                if (isArabic) "⚠️ القارئ غير متصل — سيُطبَّق عند الاتصال"
                else "⚠️ Reader not connected — will apply on connect"
            )
            return
        }

        uiState = uiState.copy(isApplying = true)
        Thread({
            val r = s.applyToReader(reader)
            uiHandler.post {
                uiState = uiState.copy(isApplying = false)
                val msg = if (r.isSuccess) {
                    if (isArabic) "✅ تم تطبيق ${r.applied} إعداد"
                    else "✅ Applied ${r.applied} settings"
                } else {
                    if (isArabic) "⚠️ نجح ${r.applied} / فشل ${r.failed}"
                    else "⚠️ OK ${r.applied} / Failed ${r.failed}"
                }
                toast(msg)
            }
        }, "ApplyMiraSettings").start()
    }

    private fun readCurrentFromHardware() {
        val s = settings ?: return
        val reader: RFIDWithUHFUART? = BaseTabFragmentActivity.getSharedReader()
        if (reader == null) {
            toast(if (isArabic) "⚠️ القارئ غير متصل" else "⚠️ Reader not connected")
            return
        }

        val sb = StringBuilder()
        try {
            sb.append("Power (real): ${reader.power} dBm\n")
        } catch (_: Exception) {
            sb.append("Power (real): ?\n")
        }

        try {
            val fm = reader.frequencyMode
            sb.append("Frequency (real): ${MiraReaderSettings.getFreqLabel(fm)} [0x${Integer.toHexString(fm)}]\n")
        } catch (_: Exception) {
            sb.append("Frequency (real): ?\n")
        }

        try {
            val rl = reader.rfLink
            val lbl = if (rl in MiraReaderSettings.RFLINK_LABELS.indices) MiraReaderSettings.RFLINK_LABELS[rl] else "?"
            sb.append("RFLink (real): $lbl [$rl]\n")
        } catch (_: Exception) {
            sb.append("RFLink (real): ?\n")
        }

        sb.append("\n── Saved in MIRA ──\n")
        sb.append("Power (saved): ${s.powerDbm} dBm\n")
        sb.append("Frequency (saved): ${MiraReaderSettings.getFreqLabel(s.freqMode)}\n")
        sb.append("Session (saved): S${s.session}\n")
        sb.append("Target (saved): ${if (s.target == 0) "A" else "B"}\n")
        sb.append("Q (saved): ${s.qValue}\n")
        sb.append("FastID (saved): ${if (s.isFastId) "ON" else "OFF"}\n")
        sb.append("TagFocus (saved): ${if (s.isTagFocus) "ON" else "OFF"}")

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(if (isArabic) "📖 الحالة الحالية" else "📖 Current State")
            .setMessage(sb.toString())
            .setPositiveButton(if (isArabic) "موافق" else "OK", null)
            .show()
    }

    private fun applyYemenDefaults() {
        val s = settings ?: return
        try {
            s.applyYemenJewelryDefaults()
            syncStateFromSettings()
            applyNow()
            toast(if (isArabic) "✅ تم تطبيق الإعدادات المثالية لليمن" else "✅ Yemen optimal defaults applied")
        } catch (e: Exception) {
            Log.e(TAG, "applyYemenDefaults error", e)
            toast(if (isArabic) "❌ فشل تطبيق الإعدادات" else "❌ Failed to apply settings")
        }
    }

    private fun resetToDefaults() {
        val s = settings ?: return
        s.resetToDefaults()
        syncStateFromSettings()
        applyNow()
    }

    private fun playTonePreview(toneType: Int, label: String) {
        try {
            val feedback = MiraFeedback.getInstance(requireContext())
            feedback?.playTone(toneType)
            feedback?.vibrate(uiState.vibrateMs)
            toast("▶️ $label")
        } catch (e: Exception) {
            Log.e(TAG, "playTonePreview error", e)
        }
    }

    private fun toggleLiveTest() {
        if (uiState.isLiveTesting) {
            stopLiveTest(showToast = true)
        } else {
            startLiveTest()
        }
    }

    private fun startLiveTest() {
        val s = settings ?: return
        val reader: RFIDWithUHFUART? = BaseTabFragmentActivity.getSharedReader()
        if (reader == null) {
            toast(if (isArabic) "⚠️ القارئ غير متصل" else "⚠️ Reader not connected")
            return
        }

        try {
            s.applyToReader(reader)
        } catch (e: Exception) {
            Log.e(TAG, "applyToReader failed before live test", e)
        }

        try {
            if (!reader.startInventoryTag()) {
                toast(if (isArabic) "❌ فشل بدء القراءة" else "❌ Failed to start")
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "startInventoryTag failed", e)
            toast(if (isArabic) "❌ خطأ في القارئ" else "❌ Reader error")
            return
        }

        liveCountRaw = 0
        liveLastEpcRaw = null
        liveLastRssiRaw = null
        liveTestStartTime = System.currentTimeMillis()
        uiState = uiState.copy(
            isLiveTesting = true,
            liveReadCount = 0,
            liveRateText = "0.0/s",
            liveLastEpc = "—",
            liveLastRssi = null
        )

        liveReadThread = Thread({
            while (uiState.isLiveTesting && !Thread.currentThread().isInterrupted) {
                try {
                    val r = BaseTabFragmentActivity.getSharedReader()
                    if (r == null) {
                        Thread.sleep(200)
                        continue
                    }
                    val tag = r.readTagFromBuffer()
                    if (tag != null) {
                        val epc = tag.epc
                        if (!epc.isNullOrEmpty()) {
                            liveCountRaw++
                            liveLastEpcRaw = epc
                            liveLastRssiRaw = parseRssi(tag.rssi)
                        }
                    } else {
                        Thread.sleep(40)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "liveReadLoop error: ${e.message}")
                    try {
                        Thread.sleep(100)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }, "LiveTest").apply {
            isDaemon = true
            start()
        }

        val updater = object : Runnable {
            override fun run() {
                if (!uiState.isLiveTesting || !isAdded) return
                val elapsed = (System.currentTimeMillis() - liveTestStartTime).coerceAtLeast(1L)
                val rate = liveCountRaw * 1000.0 / elapsed
                uiState = uiState.copy(
                    liveReadCount = liveCountRaw,
                    liveRateText = String.format(Locale.US, "%.1f/s", rate),
                    liveLastEpc = liveLastEpcRaw ?: "—",
                    liveLastRssi = liveLastRssiRaw
                )
                uiHandler.postDelayed(this, 250L)
            }
        }
        liveRateUpdater = updater
        uiHandler.post(updater)
    }

    private fun stopLiveTest(showToast: Boolean) {
        val total = liveCountRaw
        uiState = uiState.copy(isLiveTesting = false)

        liveReadThread?.interrupt()
        liveReadThread = null

        liveRateUpdater?.let { uiHandler.removeCallbacks(it) }
        liveRateUpdater = null

        Thread({
            try {
                BaseTabFragmentActivity.getSharedReader()?.stopInventory()
            } catch (e: Exception) {
                Log.w(TAG, "stopInventory error: ${e.message}")
            }
        }, "StopLiveTest").start()

        if (showToast) {
            toast(
                if (isArabic) "🛑 تم إيقاف الاختبار — $total قراءة"
                else "🛑 Test stopped — $total reads"
            )
        }
    }

    private fun parseRssi(rssiStr: String?): Int {
        if (rssiStr.isNullOrBlank()) return -50
        return try {
            val cleaned = rssiStr.replace("dBm", "", true).replace("dB", "", true).trim()
            val v = cleaned.toInt()
            if (v > 0) -v else v
        } catch (_: NumberFormatException) {
            -50
        }
    }

    private fun handleBack() {
        if (uiState.isLiveTesting) {
            stopLiveTest(showToast = false)
        }
        try {
            val main = activity as? UHFMainActivity
            if (main != null) {
                main.setBottomNavVisible(true)
                main.navigationManager?.goToDashboard() ?: main.onBackPressedDispatcher.onBackPressed()
            } else {
                activity?.onBackPressedDispatcher?.onBackPressed()
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleBack error", e)
        }
    }

    private fun toast(msg: String) {
        if (isAdded && context != null) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    @Composable
    private fun ReaderControlScreen(
        state: ReaderControlUiState,
        isArabic: Boolean,
        onBack: () -> Unit,
        onUpdateState: ((ReaderControlUiState) -> ReaderControlUiState) -> Unit,
        onApplyNow: () -> Unit,
        onReadCurrent: () -> Unit,
        onApplyYemenDefaults: () -> Unit,
        onResetDefaults: () -> Unit,
        onPreviewTone: (Int, String) -> Unit,
        onToggleLiveTest: () -> Unit
    ) {
        val colors = MiraTheme.colors
        var showYemenDialog by remember { mutableStateOf(false) }
        var showResetDialog by remember { mutableStateOf(false) }

        if (showYemenDialog) {
            AlertDialog(
                onDismissRequest = { showYemenDialog = false },
                containerColor = colors.cardSurface,
                titleContentColor = colors.goldPrimary,
                textContentColor = colors.textPrimary,
                title = {
                    Text(
                        if (isArabic) "الإعدادات المثالية لجرد المجوهرات"
                        else "Optimal Jewelry Settings",
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                text = {
                    Text(
                        if (isArabic) {
                            "سيتم تطبيق الإعدادات المحسّنة لجرد المجوهرات:\n" +
                                "• قوة الإرسال: 30 dBm\n" +
                                "• المنطقة: الشرق الأوسط (865–868)\n" +
                                "• TagFocus: مفعّل للمعادن\n" +
                                "• Miller 4 / Q: 5\n" +
                                "• فلتر التكرار: مفعّل (2.5 ثانية)\n\n" +
                                "هل تريد المتابعة؟"
                        } else {
                            "Apply optimal settings for jewelry inventory?\n• Power: 30 dBm\n• TagFocus: ON\n• Miller 4 / Q: 5"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showYemenDialog = false
                            onApplyYemenDefaults()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.goldPrimary,
                            contentColor = Color(0xFF0A0F1D)
                        )
                    ) {
                        Text(if (isArabic) "تطبيق" else "Apply", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showYemenDialog = false }) {
                        Text(if (isArabic) "إلغاء" else "Cancel", color = colors.textSecondary)
                    }
                }
            )
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                containerColor = colors.cardSurface,
                titleContentColor = colors.textPrimary,
                textContentColor = colors.textSecondary,
                title = {
                    Text(if (isArabic) "إعادة تعيين" else "Reset Defaults", fontWeight = FontWeight.ExtraBold)
                },
                text = {
                    Text(
                        if (isArabic) "سيتم إرجاع كل الإعدادات للقيم الافتراضية. هل أنت متأكد؟"
                        else "All settings will reset to defaults. Continue?"
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showResetDialog = false
                            onResetDefaults()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                    ) {
                        Text(if (isArabic) "نعم" else "Yes", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) {
                        Text(if (isArabic) "إلغاء" else "Cancel", color = colors.textSecondary)
                    }
                }
            )
        }

        Scaffold(
            containerColor = colors.backgroundTop,
            topBar = {
                Surface(color = colors.cardSurface, shadowElevation = 4.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(colors.backgroundMid)
                            ) {
                                Icon(
                                    imageVector = if (isArabic) Icons.Rounded.ArrowForward else Icons.Rounded.ArrowBack,
                                    contentDescription = null,
                                    tint = colors.textPrimary
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (isArabic) "لوحة التحكم بالقارئ" else "Reader Control",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colors.goldPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "UHF RFID • v5.0",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                            }
                        }

                        if (state.isApplying) {
                            CircularProgressIndicator(
                                color = colors.goldPrimary,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Summary Banner
                if (state.summaryText.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = colors.cardSurface,
                        border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = state.summaryText,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textPrimary,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                // 1) RF & Hardware Power
                SectionCard(
                    title = if (isArabic) "الطاقة والتردد اللاسلكي" else "RF Power & Frequency",
                    icon = Icons.Rounded.Sensors,
                    accent = colors.goldPrimary
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isArabic) "قوة الإرسال" else "TX Power",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = colors.goldPrimary.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = "${state.powerDbm} dBm",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = colors.goldPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Slider(
                        value = state.powerDbm.toFloat(),
                        onValueChange = { v ->
                            onUpdateState { it.copy(powerDbm = v.toInt().coerceIn(5, 33)) }
                        },
                        valueRange = 5f..33f,
                        steps = 27,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.goldPrimary,
                            activeTrackColor = colors.goldPrimary,
                            inactiveTrackColor = colors.backgroundMid
                        )
                    )

                    Text(
                        text = if (isArabic) {
                            "قوة أعلى = مدى أبعد للجرد، وقوة منخفضة (مثل 7 dBm) لتقليل قراءة القطع المجاورة أثناء البيع."
                        } else {
                            "Higher dBm increases range; lower dBm limits stray reads at POS."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )

                    OptionDropdown(
                        label = if (isArabic) "المنطقة الجغرافية" else "Frequency Region",
                        options = MiraReaderSettings.FREQ_LABELS.toList(),
                        selectedIndex = state.freqIndex,
                        onSelect = { idx -> onUpdateState { it.copy(freqIndex = idx) } }
                    )

                    OptionDropdown(
                        label = "RFLink",
                        options = MiraReaderSettings.RFLINK_LABELS.toList(),
                        selectedIndex = state.rfLink,
                        onSelect = { idx -> onUpdateState { it.copy(rfLink = idx) } }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OptionDropdown(
                            label = "Session",
                            options = listOf("S0", "S1", "S2", "S3"),
                            selectedIndex = state.session,
                            onSelect = { idx -> onUpdateState { it.copy(session = idx) } },
                            modifier = Modifier.weight(1f)
                        )
                        OptionDropdown(
                            label = "Target",
                            options = listOf("A", "B"),
                            selectedIndex = state.target,
                            onSelect = { idx -> onUpdateState { it.copy(target = idx) } },
                            modifier = Modifier.weight(1f)
                        )
                        OptionDropdown(
                            label = "Q",
                            options = (0..15).map { it.toString() },
                            selectedIndex = state.qValue,
                            onSelect = { idx -> onUpdateState { it.copy(qValue = idx) } },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    SwitchRow(
                        title = "FastID",
                        subtitle = if (isArabic) "تسريع قراءة هوية الشريحة" else "Faster tag ID inventory",
                        checked = state.fastId,
                        onCheckedChange = { c -> onUpdateState { it.copy(fastId = c) } }
                    )
                    SwitchRow(
                        title = "TagFocus",
                        subtitle = if (isArabic) "تحسين الاستقرار فوق الأسطح المعدنية" else "Improved stability near metal",
                        checked = state.tagFocus,
                        onCheckedChange = { c -> onUpdateState { it.copy(tagFocus = c) } }
                    )
                    SwitchRow(
                        title = "Fast Inventory",
                        subtitle = if (isArabic) "وضع الجرد السريع" else "High-speed inventory mode",
                        checked = state.fastInventory,
                        onCheckedChange = { c -> onUpdateState { it.copy(fastInventory = c) } }
                    )
                }

                // 2) Read Mode & Trigger Mode
                SectionCard(
                    title = if (isArabic) "وضع القراءة والزناد" else "Read & Trigger Modes",
                    icon = Icons.Rounded.Tune,
                    accent = Color(0xFF06B6D4)
                ) {
                    OptionDropdown(
                        label = if (isArabic) "كيف يقرأ القارئ؟" else "Read Mode",
                        options = MiraReaderSettings.READ_MODE_LABELS.toList(),
                        selectedIndex = state.readMode,
                        onSelect = { idx -> onUpdateState { it.copy(readMode = idx) } }
                    )
                    if (state.readMode in MiraReaderSettings.READ_MODE_HINTS.indices) {
                        HintBox(MiraReaderSettings.READ_MODE_HINTS[state.readMode])
                    }

                    OptionDropdown(
                        label = if (isArabic) "وضع الزناد" else "Trigger Mode",
                        options = MiraReaderSettings.TRIGGER_MODE_LABELS.toList(),
                        selectedIndex = state.triggerMode,
                        onSelect = { idx -> onUpdateState { it.copy(triggerMode = idx) } }
                    )
                    if (state.triggerMode in MiraReaderSettings.TRIGGER_MODE_HINTS.indices) {
                        HintBox(MiraReaderSettings.TRIGGER_MODE_HINTS[state.triggerMode])
                    }
                }

                // 3) Live Test Card
                SectionCard(
                    title = if (isArabic) "اختبار القراءة المباشر" else "Live Reader Test",
                    icon = Icons.Rounded.Radar,
                    accent = Color(0xFF10B981)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LiveStatBox(
                            label = if (isArabic) "القراءات" else "Reads",
                            value = state.liveReadCount.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        LiveStatBox(
                            label = if (isArabic) "المعدل" else "Rate",
                            value = state.liveRateText,
                            modifier = Modifier.weight(1f)
                        )
                        LiveStatBox(
                            label = "RSSI",
                            value = state.liveLastRssi?.let { "$it dBm" } ?: "—",
                            valueColor = when {
                                state.liveLastRssi == null -> colors.textSecondary
                                state.liveLastRssi >= -45 -> Color(0xFF10B981)
                                state.liveLastRssi >= -65 -> Color(0xFFF59E0B)
                                else -> Color(0xFFEF4444)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = colors.backgroundTop
                    ) {
                        Text(
                            text = "EPC: ${state.liveLastEpc}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF22D3EE),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(10.dp)
                        )
                    }

                    Button(
                        onClick = onToggleLiveTest,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.isLiveTesting) Color(0xFFDC2626) else Color(0xFF06B6D4),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {
                        Icon(
                            imageVector = if (state.isLiveTesting) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (state.isLiveTesting) {
                                if (isArabic) "إيقاف الاختبار" else "Stop Test"
                            } else {
                                if (isArabic) "ابدأ اختبار القراءة" else "Start Live Test"
                            },
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                // 4) Sound, Vibration & Tones
                SectionCard(
                    title = if (isArabic) "الأصوات والاهتزاز والنغمات" else "Sound, Vibration & Tones",
                    icon = Icons.Rounded.VolumeUp,
                    accent = Color(0xFFF59E0B)
                ) {
                    SwitchRow(
                        title = if (isArabic) "تفعيل الصوت" else "Sound Enabled",
                        checked = state.soundEnabled,
                        onCheckedChange = { c -> onUpdateState { it.copy(soundEnabled = c) } }
                    )
                    if (state.soundEnabled) {
                        Text(
                            text = "${if (isArabic) "مستوى الصوت" else "Volume"}: ${state.soundVolume}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary
                        )
                        Slider(
                            value = state.soundVolume.toFloat(),
                            onValueChange = { v -> onUpdateState { it.copy(soundVolume = v.toInt().coerceIn(0, 100)) } },
                            valueRange = 0f..100f
                        )
                    }

                    SwitchRow(
                        title = if (isArabic) "تفعيل الاهتزاز" else "Vibration Enabled",
                        checked = state.vibrateEnabled,
                        onCheckedChange = { c -> onUpdateState { it.copy(vibrateEnabled = c) } }
                    )
                    if (state.vibrateEnabled) {
                        Text(
                            text = "${if (isArabic) "مدة الاهتزاز" else "Duration"}: ${state.vibrateMs} ms",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary
                        )
                        Slider(
                            value = state.vibrateMs.toFloat(),
                            onValueChange = { v -> onUpdateState { it.copy(vibrateMs = v.toInt().coerceIn(0, 500)) } },
                            valueRange = 0f..500f
                        )
                    }

                    ToneSelectorRow(
                        label = if (isArabic) "نغمة قطعة جديدة" else "New Tag Tone",
                        selectedIndex = state.toneNew,
                        onSelect = { idx -> onUpdateState { it.copy(toneNew = idx) } },
                        onPreview = { onPreviewTone(state.toneNew, if (isArabic) "نغمة قطعة جديدة" else "New Tag") }
                    )
                    ToneSelectorRow(
                        label = if (isArabic) "نغمة مكررة" else "Duplicate Tone",
                        selectedIndex = state.toneDuplicate,
                        onSelect = { idx -> onUpdateState { it.copy(toneDuplicate = idx) } },
                        onPreview = { onPreviewTone(state.toneDuplicate, if (isArabic) "نغمة مكررة" else "Duplicate") }
                    )
                    ToneSelectorRow(
                        label = if (isArabic) "نغمة خطأ" else "Error Tone",
                        selectedIndex = state.toneError,
                        onSelect = { idx -> onUpdateState { it.copy(toneError = idx) } },
                        onPreview = { onPreviewTone(state.toneError, if (isArabic) "نغمة خطأ" else "Error") }
                    )

                    SwitchRow(
                        title = if (isArabic) "صوت في نقطة البيع (POS)" else "Sound on POS",
                        checked = state.soundOnPos,
                        onCheckedChange = { c -> onUpdateState { it.copy(soundOnPos = c) } }
                    )
                    SwitchRow(
                        title = if (isArabic) "صوت أثناء الجرد" else "Sound on Inventory",
                        checked = state.soundOnInventory,
                        onCheckedChange = { c -> onUpdateState { it.copy(soundOnInventory = c) } }
                    )
                }

                // 5) Advanced Reading & Global
                SectionCard(
                    title = if (isArabic) "القراءة المتقدمة والإعدادات العامة" else "Advanced & Global",
                    icon = Icons.Rounded.SettingsSuggest,
                    accent = Color(0xFF818CF8)
                ) {
                    SwitchRow(
                        title = if (isArabic) "فلترة التكرار" else "Filter Duplicates",
                        checked = state.filterDuplicates,
                        onCheckedChange = { c -> onUpdateState { it.copy(filterDuplicates = c) } }
                    )
                    if (state.filterDuplicates) {
                        Text(
                            text = "${if (isArabic) "مدة فلترة التكرار" else "Dup Window"}: ${state.dupFilterMs} ms",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary
                        )
                        Slider(
                            value = state.dupFilterMs.toFloat(),
                            onValueChange = { v -> onUpdateState { it.copy(dupFilterMs = v.toInt().coerceIn(0, 10000)) } },
                            valueRange = 0f..10000f
                        )
                    }

                    Text(
                        text = "${if (isArabic) "مهلة القراءة المفردة" else "Single Timeout"}: ${state.singleTimeoutMs} ms",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                    Slider(
                        value = state.singleTimeoutMs.toFloat(),
                        onValueChange = { v -> onUpdateState { it.copy(singleTimeoutMs = v.toInt().coerceIn(0, 10000)) } },
                        valueRange = 0f..10000f
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = state.maxTagsText,
                            onValueChange = { txt ->
                                onUpdateState { it.copy(maxTagsText = txt.filter { ch -> ch.isDigit() }) }
                            },
                            label = { Text(if (isArabic) "أقصى وسوم" else "Max Tags", fontSize = 11.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state.autoStopText,
                            onValueChange = { txt ->
                                onUpdateState { it.copy(autoStopText = txt.filter { ch -> ch.isDigit() }) }
                            },
                            label = { Text(if (isArabic) "إيقاف تلقائي (ms)" else "Auto Stop (ms)", fontSize = 11.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    SwitchRow(
                        title = if (isArabic) "إبقاء الشاشة مضاءة" else "Keep Screen On",
                        checked = state.keepScreenOn,
                        onCheckedChange = { c -> onUpdateState { it.copy(keepScreenOn = c) } }
                    )
                    SwitchRow(
                        title = if (isArabic) "وميض LED" else "LED Flash",
                        checked = state.ledFlash,
                        onCheckedChange = { c -> onUpdateState { it.copy(ledFlash = c) } }
                    )
                    SwitchRow(
                        title = if (isArabic) "إشعارات Toast" else "Toast Notifications",
                        checked = state.toastEnabled,
                        onCheckedChange = { c -> onUpdateState { it.copy(toastEnabled = c) } }
                    )
                    SwitchRow(
                        title = if (isArabic) "حفظ السجل تلقائياً" else "Auto Save Log",
                        checked = state.autoSaveLog,
                        onCheckedChange = { c -> onUpdateState { it.copy(autoSaveLog = c) } }
                    )
                }

                // Action Buttons
                Button(
                    onClick = onApplyNow,
                    enabled = !state.isApplying,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.goldPrimary,
                        contentColor = Color(0xFF0A0F1D)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isArabic) "تطبيق الإعدادات على القارئ" else "Apply to Reader",
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                OutlinedButton(
                    onClick = { showYemenDialog = true },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.goldPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text(
                        text = if (isArabic) "الإعدادات المثالية لجرد المجوهرات (اليمن)" else "Yemen Jewelry Optimal Defaults",
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onReadCurrent,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text(
                            text = if (isArabic) "قراءة الحالة" else "Read State",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = { showResetDialog = true },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF87171)),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text(
                            text = if (isArabic) "استعادة الافتراضي" else "Reset",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SectionCard(
        title: String,
        icon: ImageVector,
        accent: Color,
        content: @Composable ColumnScope.() -> Unit
    ) {
        val colors = MiraTheme.colors
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = colors.cardSurface,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.30f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.textPrimary
                    )
                }
                content()
            }
        }
    }

    @Composable
    private fun OptionDropdown(
        label: String,
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
        modifier: Modifier = Modifier
    ) {
        val colors = MiraTheme.colors
        var expanded by remember { mutableStateOf(false) }
        val safeIdx = selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))
        val currentText = options.getOrElse(safeIdx) { "—" }

        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { expanded = true },
                    shape = RoundedCornerShape(12.dp),
                    color = colors.backgroundMid,
                    border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.25f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentText,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Rounded.ArrowDropDown,
                            contentDescription = null,
                            tint = colors.goldPrimary
                        )
                    }
                }
                DropdownMenu(
    expanded = expanded,
    onDismissRequest = { expanded = false },
    modifier = Modifier.background(colors.cardSurface)
) {
    options.forEachIndexed { idx, opt ->
        DropdownMenuItem(
            text = {
                Text(
                    text = opt,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (idx == safeIdx) colors.goldPrimary else colors.textPrimary
                )
            },
            onClick = {
                expanded = false
                onSelect(idx)
            }
        )
    }
}
   }
        }
    }

    @Composable
    private fun ToneSelectorRow(
        label: String,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
        onPreview: () -> Unit
    ) {
        val colors = MiraTheme.colors
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OptionDropdown(
                label = label,
                options = MiraReaderSettings.TONE_LABELS.toList(),
                selectedIndex = selectedIndex,
                onSelect = onSelect,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onPreview,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.goldPrimary.copy(alpha = 0.16f))
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = colors.goldPrimary
                )
            }
        }
    }

    @Composable
    private fun SwitchRow(
        title: String,
        subtitle: String? = null,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        val colors = MiraTheme.colors
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onCheckedChange(!checked) }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF0A0F1D),
                    checkedTrackColor = colors.goldPrimary
                )
            )
        }
    }

    @Composable
    private fun HintBox(text: String) {
        val colors = MiraTheme.colors
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = colors.backgroundMid,
            border = BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.28f))
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(10.dp)
            )
        }
    }

    @Composable
    private fun LiveStatBox(
        label: String,
        value: String,
        valueColor: Color = MiraTheme.colors.textPrimary,
        modifier: Modifier = Modifier
    ) {
        val colors = MiraTheme.colors
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            color = colors.backgroundMid
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = valueColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
