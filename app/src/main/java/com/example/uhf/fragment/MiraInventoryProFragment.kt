package com.example.uhf.fragment

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.example.uhf.MiraRfid.MiraIRFIDReader
import com.example.uhf.MiraRfid.MiraReaderSettings
import com.example.uhf.MiraRfid.RFIDManager
import com.example.uhf.R
import com.example.uhf.activity.BaseTabFragmentActivity
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.api.MiraApiClient
import com.example.uhf.dialog.PosPowerSettingsDialog
import com.example.uhf.manager.*
import com.example.uhf.tools.DeviceDetector
import com.example.uhf.tools.SessionManager
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import com.example.uhf.utils.MiraExecutorService
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * MiraInventoryProFragment
 * منظومة الجرد الذكي المتكاملة لـ MIRA ID™:
 * - مطابقة فورية Set-Based Reconciliation
 * - مزامنة الكتالوج قبل الجرد لتوحيد عدد القطع المتوقعة مع السيرفر
 * - التقاط لقطة شاشة توثيقية (PixelCopy) قبل الاعتماد
 * - حفظ الجلسة كاملة في inventory_sessions وتفاصيل جميع القطع في inventory_audit_items
 * - توليد تقارير PDF و CSV تفصيلية شاملة للعيارات والمفقودات والمطابق والزائد
 */
class MiraInventoryProFragment : MiraBaseFragment() {

    companion object {
        private const val TAG = "MiraInventoryPro"

        private const val KEYCODE_RFID_TRIGGER_1 = 139
        private const val KEYCODE_RFID_TRIGGER_2 = 280
        private const val KEYCODE_RFID_TRIGGER_3 = 293

        private const val PREFS_PENDING_AUDITS = "mira_pending_inventory_audits"
    }

    enum class AuditFilterTab { ALL, MATCHED, MISSING, UNREGISTERED }

    enum class ItemAuditStatus { MATCHED, MISSING, UNREGISTERED }

    data class CatalogJewelryItem(
        val itemId: Int,
        val epc: String,
        val tid: String,
        val serialNo: String,
        val gtin: String,
        val title: String,
        val karat: String,
        val weight: Double,
        val priceUsd: Double,
        val location: String,
        val status: String
    )

    data class LiveTagMetric(
        val epc: String,
        var rssi: Int,
        var readCount: Int,
        var tidVerified: Boolean,
        val firstSeenMs: Long,
        var lastSeenMs: Long
    )

    data class AuditDisplayItem(
        val key: String,
        val itemId: Int,
        val epc: String,
        val tid: String,
        val serialNo: String,
        val title: String,
        val karat: String,
        val weight: Double,
        val priceUsd: Double,
        val auditStatus: ItemAuditStatus,
        val rssi: Int,
        val readCount: Int,
        val tidVerified: Boolean,
        val location: String
    )

    data class KaratSummary(
        val karat: String,
        val expectedCount: Int,
        val matchedCount: Int,
        val missingCount: Int,
        val expectedWeight: Double,
        val scannedWeight: Double,
        val matchedValueUsd: Double,
        val missingValueUsd: Double
    )

    data class ReconciledAuditReport(
        val sessionId: Long,
        val sessionUuid: String,
        val branchId: Int,
        val branchName: String,
        val auditorName: String,
        val auditTypeLabel: String,
        val startedAtMs: Long,
        val approvedAtMs: Long,
        val durationSeconds: Long,
        val expectedCount: Int,
        val matchedCount: Int,
        val missingCount: Int,
        val unregisteredCount: Int,
        val accuracyPercent: Int,
        val expectedWeight: Double,
        val scannedWeight: Double,
        val missingWeight: Double,
        val totalExpectedValueUsd: Double,
        val matchedValueUsd: Double,
        val missingValueUsd: Double,
        val riskCode: String,
        val riskTitleAr: String,
        val riskTitleEn: String,
        val isHighRisk: Boolean,
        val savedOnServer: Boolean,
        val screenshotFilePath: String?,
        val karatSummaries: List<KaratSummary>,
        val matchedItems: List<AuditDisplayItem>,
        val missingItems: List<AuditDisplayItem>,
        val unregisteredItems: List<AuditDisplayItem>
    )

    // ═══════════════════════════════════════════════════════════════════
    //  Core Managers & Executors
    // ═══════════════════════════════════════════════════════════════════

    private val sdkExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "INV-SDK").apply {
        priority = Thread.MAX_PRIORITY - 1
        isDaemon = true
    }
}

/**
 * خيط مخصص لعمليات القرص (حفظ لقطات الشاشة، توليد PDF/CSV، قراءة الكتالوج المحلي).
 * منفصل عن sdkExecutor لتفادي حجب قراءة الـ UHF، ومتسلسل لتفادي التزامن على I/O.
 */
private val diskExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "INV-DISK").apply {
        priority = Thread.NORM_PRIORITY - 1
        isDaemon = true
    }
}

private val mainHandler = Handler(Looper.getMainLooper())
    private var sessionManager: SessionManager? = null
    private var branchesManager: MiraBranchesManager? = null
    private var priceManager: MiraPriceManager? = null
    private var permissionManager: PermissionManager? = null
    private var apiClient: MiraApiClient? = null
    private var miraExecutor: MiraExecutorService? = null
    private var rfidManager: RFIDManager? = null
    private var rfidListener: MiraIRFIDReader.OnTagReadListener? = null
    private var backPressedCallback: OnBackPressedCallback? = null

    // ═══════════════════════════════════════════════════════════════════
    //  Thread-Safe Audit Engine State
    // ═══════════════════════════════════════════════════════════════════

    private val catalogByEpc = ConcurrentHashMap<String, CatalogJewelryItem>()
    private val catalogBySerial = ConcurrentHashMap<String, CatalogJewelryItem>()
    private val allCatalogItems = ConcurrentHashMap<String, CatalogJewelryItem>()
    private val scannedMetricsByEpc = ConcurrentHashMap<String, LiveTagMetric>()

    @Volatile
    private var currentSessionUuid: String = UUID.randomUUID().toString()

    @Volatile
    private var activeServerSessionId: Long = 0L

    @Volatile
    private var auditStartedAtMs: Long = System.currentTimeMillis()

    @Volatile
    private var accumulatedElapsedMs: Long = 0L

    @Volatile
    private var currentScanSegmentStartMs: Long = 0L

    @Volatile
    private var readsInLastSecond: Int = 0

    @Volatile
    private var savedPowerBeforeAudit: Int = -1

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose Reactive UI State
    // ═══════════════════════════════════════════════════════════════════

    private var branchNameState by mutableStateOf("")
    private var branchIdState by mutableIntStateOf(1)
    private var isScanningState by mutableStateOf(false)
    private var isSyncingCatalogState by mutableStateOf(false)
    private var isFinalizingState by mutableStateOf(false)
    private var hasRfidHardwareState by mutableStateOf(true)
    private var isRfidReadyState by mutableStateOf(false)

    private var expectedCountState by mutableIntStateOf(0)
    private var matchedCountState by mutableIntStateOf(0)
    private var missingCountState by mutableIntStateOf(0)
    private var unregisteredCountState by mutableIntStateOf(0)
    private var accuracyPercentState by mutableIntStateOf(0)

    private var expectedWeightState by mutableDoubleStateOf(0.0)
    private var scannedWeightState by mutableDoubleStateOf(0.0)
    private var estimatedValueUsdState by mutableDoubleStateOf(0.0)

    private var elapsedSecondsState by mutableLongStateOf(0L)
    private var tagRatePerSecState by mutableIntStateOf(0)

    private var karatSummariesState by mutableStateOf<List<KaratSummary>>(emptyList())
    private var allDisplayItemsState by mutableStateOf<List<AuditDisplayItem>>(emptyList())

    private var selectedFilterTabState by mutableStateOf(AuditFilterTab.ALL)
    private var searchQueryState by mutableStateOf("")
    private var selectedKaratFilterState by mutableStateOf<String?>(null)

    private var approvedReportDialogState by mutableStateOf<ReconciledAuditReport?>(null)
    private var showResetConfirmDialogState by mutableStateOf(false)
    private var showExitConfirmDialogState by mutableStateOf(false)

    private var tickerRunnable: Runnable? = null

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
                    MiraInventoryScreen()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx = requireContext().applicationContext
        sessionManager = SessionManager(ctx)
        branchesManager = MiraBranchesManager.getInstance(ctx)
        priceManager = MiraPriceManager.getInstance(ctx)
        permissionManager = PermissionManager.getInstance(ctx)
        apiClient = MiraApiClient.getInstance(ctx)
        miraExecutor = MiraExecutorService.getInstance()

        branchIdState = getActiveBranchId()
        branchNameState = branchesManager?.activeBranchDisplayName ?: "Main Branch"

        hasRfidHardwareState = try {
            DeviceDetector.isRfidHardwareSupported()
        } catch (_: Exception) {
            false
        }

        setupRfidManager(ctx)
        setupBackHandler()

        // تحميل الكتالوج المحلي فوراً ثم مزامنته مع السيرفر لضمان تطابق عدد المتوقع
        loadCatalogFromLocalCache()
        syncBranchCatalogWithServer(silent = true)
    }

    override fun onStart() {
        super.onStart()
        try {
            (activity as? UHFMainActivity)?.setBottomNavVisible(false)
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            (activity as? UHFMainActivity)?.let { act ->
                act.setActiveInventoryFragment(this)
                act.findViewById<View>(R.id.fabActionCenter)?.visibility = View.GONE
            }
            warmUpRfidReaderForInventory()
            startTicker()
        } catch (e: Exception) {
            Log.e(TAG, "onResume error", e)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            stopInventoryScan()
            stopTicker()
            (activity as? UHFMainActivity)?.let { act ->
                act.setActiveInventoryFragment(null)
                act.findViewById<View>(R.id.fabActionCenter)?.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            Log.e(TAG, "onPause error", e)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            (activity as? UHFMainActivity)?.setBottomNavVisible(true)
        } catch (_: Exception) {
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            stopInventoryScan()
            stopTicker()
            restoreOriginalPower()

            backPressedCallback?.let {
                it.isEnabled = false
                it.remove()
            }
            backPressedCallback = null

            rfidListener?.let { listener ->
                rfidManager?.removeListener(listener)
            }
            rfidListener = null
            mainHandler.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            Log.e(TAG, "onDestroyView error", e)
        }
    }

    override fun onDestroy() {
    super.onDestroy()
    if (!sdkExecutor.isShutdown) {
        sdkExecutor.shutdownNow()
    }
    if (!diskExecutor.isShutdown) {
        diskExecutor.shutdownNow()
    }
}

    // ═══════════════════════════════════════════════════════════════════
    //  RFID Reader Setup & Hardware Trigger
    // ═══════════════════════════════════════════════════════════════════

    private fun setupRfidManager(ctx: Context) {
        rfidManager = RFIDManager.getInstance(ctx)
        rfidManager?.let { manager ->
            isRfidReadyState = manager.isReady
            val listener = object : MiraIRFIDReader.OnTagReadListener {
                override fun onTagRead(epc: String, rssi: Int) {
                    if (isScanningState) {
                        onTagScanned(epc, rssi)
                    }
                }

                override fun onScanStarted() {
                    safeRun { isScanningState = true }
                }

                override fun onScanStopped() {
                    safeRun { isScanningState = false }
                }

                override fun onConnectionChanged(connected: Boolean) {
                    safeRun { isRfidReadyState = connected }
                }

                override fun onError(error: String) {
                    Log.e(TAG, "RFID Error: $error")
                }
            }
            rfidListener = listener
            manager.addListener(listener)
        }
    }

    private fun warmUpRfidReaderForInventory() {
        if (!hasRfidHardwareState) return
        val manager = rfidManager ?: return
        val appCtx = getSafeAppContext() ?: return

        try {
            val settings = MiraReaderSettings.getInstance(appCtx)
            if (savedPowerBeforeAudit <= 0) {
                savedPowerBeforeAudit = settings.powerDbm
            }
            val inventoryPower = settings.inventoryPowerDbm.takeIf { it > 0 } ?: 30
            settings.powerDbm = inventoryPower

            manager.ensureReady(object : RFIDManager.ReadyCallback {
                override fun onReady(reader: MiraIRFIDReader) {
                    safeRun { isRfidReadyState = true }
                    sdkExecutor.execute {
                        runCatching {
                            manager.setPower(inventoryPower)
                            BaseTabFragmentActivity.getSharedReader()?.setPower(inventoryPower)
                        }
                    }
                }

                override fun onError(reason: String) {
                    Log.w(TAG, "warmUpRfidReader warning: $reason")
                    safeRun { isRfidReadyState = false }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "warmUpRfidReaderForInventory error", e)
        }
    }

    private fun restoreOriginalPower() {
        if (savedPowerBeforeAudit <= 0 || sdkExecutor.isShutdown) return
        val target = savedPowerBeforeAudit
        savedPowerBeforeAudit = -1
        sdkExecutor.execute {
            try {
                val appCtx = getSafeAppContext() ?: return@execute
                MiraReaderSettings.getInstance(appCtx).powerDbm = target
                rfidManager?.setPower(target)
                BaseTabFragmentActivity.getSharedReader()?.setPower(target)
            } catch (_: Exception) {
            }
        }
    }

    override fun myOnKeyDwon(keyCode: Int, event: KeyEvent): Boolean {
        val isTrigger = keyCode == KEYCODE_RFID_TRIGGER_1 ||
                keyCode == KEYCODE_RFID_TRIGGER_2 ||
                keyCode == KEYCODE_RFID_TRIGGER_3

        if (!isTrigger) return false

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (approvedReportDialogState != null || isFinalizingState) return true
            if (isScanningState) {
                stopInventoryScan()
            } else {
                startInventoryScan()
            }
            return true
        }
        return true
    }

    private fun startInventoryScan() {
        if (!hasRfidHardwareState) {
            showToast(if (isArabic) "الجهاز لا يدعم قارئ UHF RFID" else "UHF RFID not supported")
            return
        }
        val manager = rfidManager ?: return
        if (isScanningState) return

        currentScanSegmentStartMs = System.currentTimeMillis()
        isScanningState = true

        sdkExecutor.execute {
            try {
                if (manager.isScanning) {
                    manager.stopScan()
                    Thread.sleep(60)
                }
                manager.startScan(object : RFIDManager.ReadyCallback {
                    override fun onReady(reader: MiraIRFIDReader) {
                        safeRun {
                            isRfidReadyState = true
                            isScanningState = true
                            playAlertSound(1)
                        }
                    }

                    override fun onError(reason: String) {
                        safeRun {
                            isScanningState = false
                            showToast(
                                if (isArabic) "⚠️ تعذر بدء المسح: $reason"
                                else "⚠️ Scan failed: $reason"
                            )
                        }
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "startInventoryScan error", e)
                safeRun { isScanningState = false }
            }
        }
    }

    private fun stopInventoryScan() {
        if (currentScanSegmentStartMs > 0L) {
            accumulatedElapsedMs += (System.currentTimeMillis() - currentScanSegmentStartMs)
            currentScanSegmentStartMs = 0L
        }
        isScanningState = false
        tagRatePerSecState = 0

        val manager = rfidManager ?: return
        if (sdkExecutor.isShutdown) {
            runCatching { manager.stopScan() }
            return
        }
        sdkExecutor.execute {
            runCatching { manager.stopScan() }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Tag Ingestion & Set-Based Reconciliation Engine
    // ═══════════════════════════════════════════════════════════════════

    fun onTagScanned(rawEpc: String?, rssi: Int = -55) {
        if (rawEpc.isNullOrBlank()) return
        val cleanEpc = rawEpc.trim().uppercase(Locale.US)
        val now = System.currentTimeMillis()
        readsInLastSecond++

        val catalogItem = resolveCatalogItemByCode(cleanEpc)
        val canonicalEpc = catalogItem?.epc ?: cleanEpc

        val existing = scannedMetricsByEpc[canonicalEpc]
        if (existing == null) {
            scannedMetricsByEpc[canonicalEpc] = LiveTagMetric(
                epc = canonicalEpc,
                rssi = rssi,
                readCount = 1,
                tidVerified = catalogItem != null && catalogItem.tid.isNotEmpty(),
                firstSeenMs = now,
                lastSeenMs = now
            )
            playAlertSound(1)
            recomputeReconciliationState()
        } else {
            existing.readCount += 1
            if (rssi > existing.rssi && rssi < 0) {
                existing.rssi = rssi
            }
            existing.lastSeenMs = now
            // تحديث دوري خفيف للواجهة دون إبطاء قراءة الـ UHF
            if (existing.readCount % 5 == 0) {
                recomputeReconciliationState()
            }
        }
    }

    fun onBarcodeScanned(rawCode: String?) {
        if (rawCode.isNullOrBlank()) return
        onTagScanned(rawCode.trim(), -40)
    }

    private fun resolveCatalogItemByCode(code: String): CatalogJewelryItem? {
        val upper = code.trim().uppercase(Locale.US)
        catalogByEpc[upper]?.let { return it }
        catalogBySerial[upper]?.let { return it }
        return null
    }

    private fun loadCatalogFromLocalCache() {
    val appCtx = getSafeAppContext() ?: return
    val targetBranchId = getActiveBranchId()

    diskExecutor.execute {
        try {
            val cache = MiraLocalItemCache.getInstance(appCtx) ?: return@execute
            val itemsList = extractBranchItemsFromCache(cache, targetBranchId)
            if (itemsList.isNotEmpty()) {
                applyCatalogItems(itemsList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadCatalogFromLocalCache error", e)
        }
    }
}

    /**
 * مزامنة كتالوج الفرع مع السيرفر — يجب أن تجري على networkExecutor،
 * ثم قراءة القرص تُغلق بـ diskExecutor داخل مسار الاعتماد فقط.
 */
private fun syncBranchCatalogWithServer(silent: Boolean) {
    val appCtx = getSafeAppContext() ?: return
    val targetBranchId = getActiveBranchId()
    if (isSyncingCatalogState) return

    safeRun { isSyncingCatalogState = true }

    miraExecutor?.networkExecutor?.execute {
        try {
            val syncManager = MiraItemSyncManager.getInstance(appCtx)
            invokeSyncManagerRefresh(syncManager, targetBranchId)

            val cache = MiraLocalItemCache.getInstance(appCtx)
            if (cache != null) {
                val refreshed = extractBranchItemsFromCache(cache, targetBranchId)
                if (refreshed.isNotEmpty()) {
                    applyCatalogItems(refreshed)
                }
            }

            safeRun {
                isSyncingCatalogState = false
                if (!silent) {
                    showToast(
                        if (isArabic) "✅ تم تحديث كتالوج الفرع (${allCatalogItems.size} قطعة)"
                        else "✅ Branch catalog synced (${allCatalogItems.size} items)"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncBranchCatalogWithServer error", e)
            safeRun {
                isSyncingCatalogState = false
                if (!silent) {
                    showToast(
                        if (isArabic) "⚠️ تعذرت المزامنة مع السيرفر، يتم العمل على البيانات المحلية"
                        else "⚠️ Server sync failed, using local cache"
                    )
                }
            }
        }
    }
}

    private fun applyCatalogItems(items: List<CatalogJewelryItem>) {
        catalogByEpc.clear()
        catalogBySerial.clear()
        allCatalogItems.clear()

        for (item in items) {
            val primaryKey = item.epc.ifEmpty { item.serialNo.ifEmpty { "ITEM_${item.itemId}" } }
                .uppercase(Locale.US)
            allCatalogItems[primaryKey] = item

            if (item.epc.isNotEmpty()) {
                catalogByEpc[item.epc.uppercase(Locale.US)] = item
            }
            if (item.tid.isNotEmpty()) {
                catalogByEpc[item.tid.uppercase(Locale.US)] = item
            }
            if (item.serialNo.isNotEmpty()) {
                catalogBySerial[item.serialNo.uppercase(Locale.US)] = item
            }
            if (item.gtin.isNotEmpty()) {
                catalogBySerial[item.gtin.uppercase(Locale.US)] = item
            }
        }

        recomputeReconciliationState()
    }

    private fun recomputeReconciliationState() {
        val catalogSnapshot = ArrayList(allCatalogItems.values)
        val scannedSnapshot = HashMap(scannedMetricsByEpc)

        var matchedCount = 0
        var missingCount = 0
        var expectedWeight = 0.0
        var scannedWeight = 0.0
        var matchedValueUsd = 0.0
        var totalExpectedValueUsd = 0.0

        val matchedCatalogKeys = HashSet<String>()
        val displayList = ArrayList<AuditDisplayItem>(catalogSnapshot.size + scannedSnapshot.size)
        val karatMap = LinkedHashMap<String, MutableKaratAcc>()

        for (item in catalogSnapshot) {
            val key = item.epc.ifEmpty { item.serialNo.ifEmpty { "ITEM_${item.itemId}" } }
                .uppercase(Locale.US)
            val metric = scannedSnapshot[key]
                ?: scannedSnapshot[item.epc.uppercase(Locale.US)]
                ?: scannedSnapshot[item.tid.uppercase(Locale.US)]
                ?: scannedSnapshot[item.serialNo.uppercase(Locale.US)]

            val isMatched = metric != null
            val normKarat = normalizeKaratLabel(item.karat)
            val acc = karatMap.getOrPut(normKarat) { MutableKaratAcc(normKarat) }

            acc.expectedCount += 1
            acc.expectedWeight += item.weight
            expectedWeight += item.weight
            totalExpectedValueUsd += item.priceUsd

            if (isMatched) {
                matchedCount += 1
                scannedWeight += item.weight
                matchedValueUsd += item.priceUsd

                acc.matchedCount += 1
                acc.scannedWeight += item.weight
                acc.matchedValueUsd += item.priceUsd

                matchedCatalogKeys.add(key)
                if (item.epc.isNotEmpty()) matchedCatalogKeys.add(item.epc.uppercase(Locale.US))
                if (item.tid.isNotEmpty()) matchedCatalogKeys.add(item.tid.uppercase(Locale.US))
                if (item.serialNo.isNotEmpty()) matchedCatalogKeys.add(item.serialNo.uppercase(Locale.US))

                displayList.add(
                    AuditDisplayItem(
                        key = key,
                        itemId = item.itemId,
                        epc = item.epc,
                        tid = item.tid,
                        serialNo = item.serialNo,
                        title = item.title,
                        karat = normKarat,
                        weight = item.weight,
                        priceUsd = item.priceUsd,
                        auditStatus = ItemAuditStatus.MATCHED,
                        rssi = metric!!.rssi,
                        readCount = metric.readCount,
                        tidVerified = metric.tidVerified || item.tid.isNotEmpty(),
                        location = item.location
                    )
                )
            } else {
                missingCount += 1
                acc.missingCount += 1
                acc.missingValueUsd += item.priceUsd

                displayList.add(
                    AuditDisplayItem(
                        key = key,
                        itemId = item.itemId,
                        epc = item.epc,
                        tid = item.tid,
                        serialNo = item.serialNo,
                        title = item.title,
                        karat = normKarat,
                        weight = item.weight,
                        priceUsd = item.priceUsd,
                        auditStatus = ItemAuditStatus.MISSING,
                        rssi = 0,
                        readCount = 0,
                        tidVerified = false,
                        location = item.location
                    )
                )
            }
        }

        var unregisteredCount = 0
        for ((epc, metric) in scannedSnapshot) {
            if (!matchedCatalogKeys.contains(epc.uppercase(Locale.US))) {
                unregisteredCount += 1
                displayList.add(
                    AuditDisplayItem(
                        key = "UNREG_$epc",
                        itemId = 0,
                        epc = epc,
                        tid = "",
                        serialNo = "",
                        title = if (isArabic) "بطاقة غير مسجلة في الفرع" else "Unregistered Tag",
                        karat = "—",
                        weight = 0.0,
                        priceUsd = 0.0,
                        auditStatus = ItemAuditStatus.UNREGISTERED,
                        rssi = metric.rssi,
                        readCount = metric.readCount,
                        tidVerified = false,
                        location = ""
                    )
                )
            }
        }

        val expectedCount = catalogSnapshot.size
        val accuracy = if (expectedCount > 0) {
            ((matchedCount.toDouble() / expectedCount.toDouble()) * 100.0).roundToInt()
        } else {
            0
        }

        val karatSummaries = karatMap.values.map {
            KaratSummary(
                karat = it.karat,
                expectedCount = it.expectedCount,
                matchedCount = it.matchedCount,
                missingCount = it.missingCount,
                expectedWeight = it.expectedWeight,
                scannedWeight = it.scannedWeight,
                matchedValueUsd = it.matchedValueUsd,
                missingValueUsd = it.missingValueUsd
            )
        }.sortedByDescending { it.karat.replace("K", "").toIntOrNull() ?: 0 }

        safeRun {
            expectedCountState = expectedCount
            matchedCountState = matchedCount
            missingCountState = missingCount
            unregisteredCountState = unregisteredCount
            accuracyPercentState = accuracy
            expectedWeightState = expectedWeight
            scannedWeightState = scannedWeight
            estimatedValueUsdState = if (matchedValueUsd > 0.0) matchedValueUsd else totalExpectedValueUsd
            karatSummariesState = karatSummaries
            allDisplayItemsState = displayList
        }
    }

    private data class MutableKaratAcc(
        val karat: String,
        var expectedCount: Int = 0,
        var matchedCount: Int = 0,
        var missingCount: Int = 0,
        var expectedWeight: Double = 0.0,
        var scannedWeight: Double = 0.0,
        var matchedValueUsd: Double = 0.0,
        var missingValueUsd: Double = 0.0
    )

    // ═══════════════════════════════════════════════════════════════════
    //  Approval Flow: Screenshot Capture + Full DB Persistence
    // ═══════════════════════════════════════════════════════════════════

    private fun initiateAuditApproval() {
        if (isFinalizingState) return

        if (expectedCountState == 0 && matchedCountState == 0 && unregisteredCountState == 0) {
            showToast(if (isArabic) "لا توجد بيانات جرد لاعتمادها" else "No inventory data to approve")
            return
        }

        // إيقاف المسح الحي قبل التقاط لقطة الشاشة واعتماد الجلسة
        if (isScanningState) {
            stopInventoryScan()
        }

        isFinalizingState = true

        // مهلة قصيرة (120ms) لتحديث الواجهة بحالة التوقف قبل التقاط لقطة الشاشة
        mainHandler.postDelayed({
            captureAuditScreenshot { screenshotBase64, screenshotFile ->
                finalizeAndSaveAuditSession(screenshotBase64, screenshotFile)
            }
        }, 120L)
    }

    /**
     * التقاط لقطة شاشة موثقة لشاشة الجرد عبر PixelCopy مع بديل Canvas
     */
    private fun captureAuditScreenshot(onCaptured: (base64: String?, savedFile: File?) -> Unit) {
        val act = activity
        val rootView = view
        if (act == null || rootView == null || rootView.width <= 0 || rootView.height <= 0) {
            onCaptured(null, null)
            return
        }

        try {
            val bitmap = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
            val window = act.window

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && window != null) {
                val location = IntArray(2)
                rootView.getLocationInWindow(location)
                val rect = Rect(
                    location[0],
                    location[1],
                    location[0] + rootView.width,
                    location[1] + rootView.height
                )

                PixelCopy.request(
                    window,
                    rect,
                    bitmap,
                    { copyResult ->
                        if (copyResult == PixelCopy.SUCCESS) {
                            encodeAndSaveScreenshot(bitmap, onCaptured)
                        } else {
                            // بديل Canvas إذا تعذر PixelCopy
                            runCatching {
                                val canvas = Canvas(bitmap)
                                rootView.draw(canvas)
                            }
                            encodeAndSaveScreenshot(bitmap, onCaptured)
                        }
                    },
                    mainHandler
                )
            } else {
                val canvas = Canvas(bitmap)
                rootView.draw(canvas)
                encodeAndSaveScreenshot(bitmap, onCaptured)
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureAuditScreenshot error", e)
            onCaptured(null, null)
        }
    }

    /**
 * يرمّز Bitmap إلى Base64 ويحفظ نسخة على القرص. يعمل على diskExecutor
 * حتى لا يحجب sdkExecutor (قراءة الـ UHF) ولا networkExecutor.
 */
private fun encodeAndSaveScreenshot(
    rawBitmap: Bitmap,
    onCaptured: (base64: String?, savedFile: File?) -> Unit
) {
    diskExecutor.execute {
        try {
            // تصغير متناسب لضمان سرعة الرفع مع الحفاظ على وضوح النصوص والأرقام
            val maxWidth = 720
            val scaledBitmap = if (rawBitmap.width > maxWidth) {
                val ratio = maxWidth.toFloat() / rawBitmap.width.toFloat()
                val targetHeight = (rawBitmap.height * ratio).roundToInt()
                Bitmap.createScaledBitmap(rawBitmap, maxWidth, targetHeight, true)
            } else {
                rawBitmap
            }

            val baos = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 78, baos)
            val bytes = baos.toByteArray()
            val base64Str = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

            val appCtx = getSafeAppContext()
            var savedFile: File? = null
            if (appCtx != null) {
                val dir = File(appCtx.cacheDir, "audit_screenshots").apply { mkdirs() }
                val file = File(dir, "audit_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { out -> out.write(bytes) }
                savedFile = file
            }

            if (scaledBitmap !== rawBitmap) {
                scaledBitmap.recycle()
            }
            rawBitmap.recycle()

            safeRun { onCaptured(base64Str, savedFile) }
        } catch (e: Exception) {
            Log.e(TAG, "encodeAndSaveScreenshot error", e)
            safeRun { onCaptured(null, null) }
        }
    }
}

    /**
     * بناء التقرير التفصيلي وحفظ الجلسة + جميع العناصر + لقطة الشاشة في السيرفر
     */
    private fun finalizeAndSaveAuditSession(screenshotBase64: String?, screenshotFile: File?) {
        val approvedAtMs = System.currentTimeMillis()
        val durationSec = getCurrentElapsedSeconds()

        val allItems = ArrayList(allDisplayItemsState)
        val matchedList = allItems.filter { it.auditStatus == ItemAuditStatus.MATCHED }
        val missingList = allItems.filter { it.auditStatus == ItemAuditStatus.MISSING }
        val unregList = allItems.filter { it.auditStatus == ItemAuditStatus.UNREGISTERED }

        val expectedCount = expectedCountState
        val matchedCount = matchedList.size
        val missingCount = missingList.size
        val unregCount = unregList.size

        val accuracy = if (expectedCount > 0) {
            ((matchedCount.toDouble() / expectedCount.toDouble()) * 100.0).roundToInt()
        } else 0

        val expectedWeight = expectedWeightState
        val scannedWeight = scannedWeightState
        val missingWeight = (expectedWeight - scannedWeight).coerceAtLeast(0.0)

        val matchedValueUsd = matchedList.sumOf { it.priceUsd }
        val missingValueUsd = missingList.sumOf { it.priceUsd }
        val totalExpectedValueUsd = matchedValueUsd + missingValueUsd

        val riskInfo = evaluateAuditRisk(expectedCount, matchedCount, missingCount, unregCount)

        val payload = buildFullAuditSessionPayload(
            approvedAtMs = approvedAtMs,
            durationSec = durationSec,
            expectedCount = expectedCount,
            matchedCount = matchedCount,
            missingCount = missingCount,
            unregisteredCount = unregCount,
            accuracy = accuracy,
            expectedWeight = expectedWeight,
            scannedWeight = scannedWeight,
            missingWeight = missingWeight,
            totalExpectedValueUsd = totalExpectedValueUsd,
            matchedValueUsd = matchedValueUsd,
            missingValueUsd = missingValueUsd,
            riskCode = riskInfo.first,
            karatSummaries = karatSummariesState,
            allItems = allItems,
            screenshotBase64 = screenshotBase64
        )

        miraExecutor?.networkExecutor?.execute {
            var serverSessionId = activeServerSessionId
            var savedOnServer = false
            var finalRiskCode = riskInfo.first

            try {
                val client = apiClient
                if (client != null) {
                    val responseObj = dispatchFinalizePayloadToApiClient(client, payload)
                    if (responseObj != null && responseObj.optBoolean("success", false)) {
                        savedOnServer = true
                        val dataObj = responseObj.optJSONObject("data") ?: responseObj
                        val returnedId = dataObj.optLong(
                            "session_id",
                            dataObj.optLong("id", serverSessionId)
                        )
                        if (returnedId > 0L) {
                            serverSessionId = returnedId
                            activeServerSessionId = returnedId
                        }
                        val serverRisk = dataObj.optString("risk_status", "")
                        if (serverRisk.isNotEmpty() && serverRisk != "null") {
                            finalRiskCode = serverRisk
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "finalizeAndSaveAuditSession network error", e)
            }

            if (!savedOnServer) {
                savePendingAuditOffline(payload)
            }

            val translatedRisk = translateRiskStatus(finalRiskCode, missingCount, expectedCount)

            val report = ReconciledAuditReport(
                sessionId = if (serverSessionId > 0L) serverSessionId else (System.currentTimeMillis() % 100000L),
                sessionUuid = currentSessionUuid,
                branchId = branchIdState,
                branchName = branchNameState,
                auditorName = sessionManager?.username ?: "POS Operator",
                auditTypeLabel = if (isArabic) "جرد شامل" else "Full Audit",
                startedAtMs = auditStartedAtMs,
                approvedAtMs = approvedAtMs,
                durationSeconds = durationSec,
                expectedCount = expectedCount,
                matchedCount = matchedCount,
                missingCount = missingCount,
                unregisteredCount = unregCount,
                accuracyPercent = accuracy,
                expectedWeight = expectedWeight,
                scannedWeight = scannedWeight,
                missingWeight = missingWeight,
                totalExpectedValueUsd = totalExpectedValueUsd,
                matchedValueUsd = matchedValueUsd,
                missingValueUsd = missingValueUsd,
                riskCode = finalRiskCode,
                riskTitleAr = translatedRisk.first,
                riskTitleEn = translatedRisk.second,
                isHighRisk = translatedRisk.third,
                savedOnServer = savedOnServer,
                screenshotFilePath = screenshotFile?.absolutePath,
                karatSummaries = karatSummariesState,
                matchedItems = matchedList,
                missingItems = missingList,
                unregisteredItems = unregList
            )

            safeRun {
                isFinalizingState = false
                approvedReportDialogState = report
                playAlertSound(1)
                if (savedOnServer) {
                    showToast(
                        if (isArabic) "✅ تم اعتماد الجلسة وحفظ جميع التفاصيل ولقطة الشاشة"
                        else "✅ Session approved & saved with screenshot"
                    )
                } else {
                    showToast(
                        if (isArabic) "📥 تم الاعتماد محلياً وسيُرفع للسيرفر عند توفر الاتصال"
                        else "📥 Approved locally; will sync when online"
                    )
                }
            }
        }
    }

    private fun buildFullAuditSessionPayload(
        approvedAtMs: Long,
        durationSec: Long,
        expectedCount: Int,
        matchedCount: Int,
        missingCount: Int,
        unregisteredCount: Int,
        accuracy: Int,
        expectedWeight: Double,
        scannedWeight: Double,
        missingWeight: Double,
        totalExpectedValueUsd: Double,
        matchedValueUsd: Double,
        missingValueUsd: Double,
        riskCode: String,
        karatSummaries: List<KaratSummary>,
        allItems: List<AuditDisplayItem>,
        screenshotBase64: String?
    ): JSONObject {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

        return JSONObject().apply {
            put("action", "FINALIZE_INVENTORY_SESSION")
            put("session_id", activeServerSessionId)
            put("local_uuid", currentSessionUuid)
            put("branch_id", branchIdState)
            put("user_id", sessionManager?.userId ?: 0)
            put("username", sessionManager?.username ?: "")
            put("audit_type", "full")
            put("status", "approved")
            put("started_at", isoFormat.format(Date(auditStartedAtMs)))
            put("approved_at", isoFormat.format(Date(approvedAtMs)))
            put("duration_seconds", durationSec)

            // إحصائيات جدول inventory_sessions
            put("expected_count", expectedCount)
            put("matched_count", matchedCount)
            put("missing_count", missingCount)
            put("unregistered_count", unregisteredCount)
            put("accuracy_percent", accuracy)

            put("expected_weight", expectedWeight)
            put("scanned_weight", scannedWeight)
            put("missing_weight", missingWeight)

            put("total_expected_value_usd", totalExpectedValueUsd)
            put("matched_value_usd", matchedValueUsd)
            put("missing_value_usd", missingValueUsd)
            put("risk_status", riskCode)

            if (!screenshotBase64.isNullOrEmpty()) {
                put("screenshot_base64", screenshotBase64)
            }

            // ملخص العيارات
            val karatArray = JSONArray()
            for (k in karatSummaries) {
                karatArray.put(JSONObject().apply {
                    put("karat", k.karat)
                    put("expected_count", k.expectedCount)
                    put("matched_count", k.matchedCount)
                    put("missing_count", k.missingCount)
                    put("expected_weight", k.expectedWeight)
                    put("scanned_weight", k.scannedWeight)
                    put("matched_value_usd", k.matchedValueUsd)
                    put("missing_value_usd", k.missingValueUsd)
                })
            }
            put("karat_breakdown", karatArray)

            // جميع القطع لحفظها في جدول inventory_audit_items
            val itemsArray = JSONArray()
            for (item in allItems) {
                val statusStr = when (item.auditStatus) {
                    ItemAuditStatus.MATCHED -> "matched"
                    ItemAuditStatus.MISSING -> "missing"
                    ItemAuditStatus.UNREGISTERED -> "unregistered"
                }
                itemsArray.put(JSONObject().apply {
                    put("item_id", item.itemId)
                    put("epc", item.epc)
                    put("tid", item.tid)
                    put("serial_no", item.serialNo)
                    put("title", item.title)
                    put("karat", item.karat)
                    put("weight", item.weight)
                    put("price_usd", item.priceUsd)
                    put("status", statusStr)
                    put("rssi", item.rssi)
                    put("read_count", item.readCount)
                    put("tid_verified", item.tidVerified)
                    put("location", item.location)
                })
            }
            put("items", itemsArray)
            // توافق مع أي Endpoint يتوقع مفتاح scanned_items
            val scannedOnlyArray = JSONArray()
            for (item in allItems) {
                if (item.auditStatus != ItemAuditStatus.MISSING) {
                    scannedOnlyArray.put(JSONObject().apply {
                        put("item_id", item.itemId)
                        put("epc", item.epc)
                        put("tid", item.tid)
                        put("serial_no", item.serialNo)
                        put("rssi", item.rssi)
                        put("read_count", item.readCount)
                        put("tid_verified", item.tidVerified)
                    })
                }
            }
            put("scanned_items", scannedOnlyArray)
        }
    }

    /**
     * استدعاء مرن لـ MiraApiClient لضمان التوافق التام مع دوال السيرفر
     */
    private fun dispatchFinalizePayloadToApiClient(
        client: MiraApiClient,
        payload: JSONObject
    ): JSONObject? {
        val methodNames = listOf(
            "finalizeInventorySession",
            "closeInventorySession",
            "submitInventoryAudit",
            "approveInventorySession"
        )
        for (name in methodNames) {
            try {
                val method = client.javaClass.methods.firstOrNull {
                    it.name == name && it.parameterTypes.size == 1 &&
                            it.parameterTypes[0] == JSONObject::class.java
                }
                if (method != null) {
                    val res = method.invoke(client, payload) ?: continue
                    if (res is JSONObject) return res
                    // استخراج isSuccess و data من كائن ApiResponse
                    val isSuccessField = res.javaClass.fields.firstOrNull { it.name == "isSuccess" }
                    val dataField = res.javaClass.fields.firstOrNull { it.name == "data" }
                    val ok = (isSuccessField?.get(res) as? Boolean) ?: false
                    val dataObj = dataField?.get(res) as? JSONObject
                    return JSONObject().apply {
                        put("success", ok)
                        if (dataObj != null) put("data", dataObj)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Method $name invocation failed: ${e.message}")
            }
        }
        return null
    }

    private fun savePendingAuditOffline(payload: JSONObject) {
        try {
            val ctx = getSafeAppContext() ?: return
            val prefs = ctx.getSharedPreferences(PREFS_PENDING_AUDITS, Context.MODE_PRIVATE)
            val existingStr = prefs.getString("queue", "[]") ?: "[]"
            val arr = JSONArray(existingStr)
            arr.put(payload)
            prefs.edit().putString("queue", arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "savePendingAuditOffline error", e)
        }
    }

    private fun resetAuditSession() {
        stopInventoryScan()
        scannedMetricsByEpc.clear()
        currentSessionUuid = UUID.randomUUID().toString()
        activeServerSessionId = 0L
        auditStartedAtMs = System.currentTimeMillis()
        accumulatedElapsedMs = 0L
        currentScanSegmentStartMs = 0L
        elapsedSecondsState = 0L
        tagRatePerSecState = 0
        selectedFilterTabState = AuditFilterTab.ALL
        selectedKaratFilterState = null
        searchQueryState = ""
        recomputeReconciliationState()
        showToast(if (isArabic) "🔄 تم تصفير الجلسة لبدء جرد جديد" else "🔄 Audit session reset")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Detailed PDF & CSV Report Generator
    // ═══════════════════════════════════════════════════════════════════

    private fun exportAndSharePdfReport(report: ReconciledAuditReport) {
        val ctx = context ?: return
        diskExecutor.execute {
    try {
        val pdfFile = generateDetailedPdfFile(ctx, report)
                safeRun {
                    shareGeneratedFile(
                        ctx = ctx,
                        file = pdfFile,
                        mimeType = "application/pdf",
                        chooserTitle = if (isArabic) "مشاركة أو طباعة تقرير الجرد PDF" else "Share or Print Audit PDF"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "exportAndSharePdfReport error", e)
                safeRun {
                    showToast(
                        if (isArabic) "❌ فشل إنشاء تقرير PDF: ${e.message}"
                        else "❌ PDF generation failed: ${e.message}"
                    )
                }
            }
        }
    }

    private fun exportAndShareCsvReport(report: ReconciledAuditReport) {
        val ctx = context ?: return
        diskExecutor.execute {
    try {
        val csvFile = generateDetailedCsvFile(ctx, report)
                safeRun {
                    shareGeneratedFile(
                        ctx = ctx,
                        file = csvFile,
                        mimeType = "text/csv",
                        chooserTitle = if (isArabic) "تصدير تقرير الجرد CSV" else "Export Audit CSV"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "exportAndShareCsvReport error", e)
                safeRun {
                    showToast(
                        if (isArabic) "❌ فشل تصدير ملف CSV: ${e.message}"
                        else "❌ CSV export failed: ${e.message}"
                    )
                }
            }
        }
    }

    private fun generateDetailedPdfFile(context: Context, report: ReconciledAuditReport): File {
        val doc = PdfDocument()
        val pageWidth = 595   // A4 width at 72 DPI
        val pageHeight = 842  // A4 height at 72 DPI
        val margin = 32f
        val contentWidth = pageWidth - (margin * 2)

        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#0F172A")
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val subTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#B45309")
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#0F172A")
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val bodyRightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#1E293B")
            textSize = 9.5f
            textAlign = Paint.Align.RIGHT
        }
        val bodyLeftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#334155")
            textSize = 8.5f
            textAlign = Paint.Align.LEFT
        }
        val headerBgPaint = Paint().apply {
            color = android.graphics.Color.parseColor("#FEF3C7")
            style = Paint.Style.FILL
        }
        val tableHeaderPaint = Paint().apply {
            color = android.graphics.Color.parseColor("#1E293B")
            style = Paint.Style.FILL
        }
        val tableHeaderTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 9f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val linePaint = Paint().apply {
            color = android.graphics.Color.parseColor("#CBD5E1")
            strokeWidth = 0.8f
        }

        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        var canvas = page.canvas
        var y = margin

        fun ensureSpace(neededHeight: Float) {
            if (y + neededHeight > pageHeight - margin) {
                doc.finishPage(page)
                pageNum++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
                canvas = page.canvas
                y = margin
            }
        }

        // 1. الترويسة الرسمية
        canvas.drawRoundRect(margin, y, pageWidth - margin, y + 66f, 8f, 8f, headerBgPaint)
        canvas.drawText("ميرا تكنولوجي للحلول الذكية — تقرير جرد المجوهرات المعتمد", pageWidth - margin - 12f, y + 24f, titlePaint)
        canvas.drawText(
            "الفرع: ${report.branchName}  |  رقم الجلسة: #${report.sessionId}  |  النوع: ${report.auditTypeLabel}",
            pageWidth - margin - 12f,
            y + 42f,
            subTitlePaint
        )
        canvas.drawText(
            "المسؤول: ${report.auditorName}  |  تاريخ الاعتماد: ${dateFmt.format(Date(report.approvedAtMs))}",
            pageWidth - margin - 12f,
            y + 57f,
            bodyRightPaint
        )
        y += 80f

        // 2. ملخص المطابقة والأوزان والقيم المالية
        canvas.drawText("أولاً: ملخص نتائج الجرد والتقييم الأمني", pageWidth - margin, y, sectionPaint)
        y += 16f
        canvas.drawText(
            "المتوقع: ${report.expectedCount} قطعة   |   المطابق: ${report.matchedCount} قطعة   |   المفقود (العجز): ${report.missingCount} قطعة   |   غير مسجل: ${report.unregisteredCount}",
            pageWidth - margin,
            y,
            bodyRightPaint
        )
        y += 15f
        canvas.drawText(
            String.format(
                Locale.US,
                "نسبة المطابقة: %d%%   |   الوزن المقروء: %.2fg   |   الوزن المتوقع: %.2fg   |   فارق الوزن: %.2fg",
                report.accuracyPercent,
                report.scannedWeight,
                report.expectedWeight,
                report.missingWeight
            ),
            pageWidth - margin,
            y,
            bodyRightPaint
        )
        y += 15f
        canvas.drawText(
            String.format(
                Locale.US,
                "قيمة الموجودات المقروءة: $%,.2f   |   قيمة العجز المفقود: $%,.2f   |   التقييم: %s",
                report.matchedValueUsd,
                report.missingValueUsd,
                report.riskTitleAr
            ),
            pageWidth - margin,
            y,
            bodyRightPaint
        )
        y += 18f
        canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
        y += 18f

        // 3. جدول التوزيع حسب العيار
        if (report.karatSummaries.isNotEmpty()) {
            ensureSpace(60f)
            canvas.drawText("ثانياً: تحليل الجرد حسب العيار", pageWidth - margin, y, sectionPaint)
            y += 10f
            canvas.drawRect(margin, y, pageWidth - margin, y + 20f, tableHeaderPaint)
            canvas.drawText("العيار", pageWidth - margin - 10f, y + 14f, tableHeaderTextPaint)
            canvas.drawText("المتوقع", pageWidth - margin - 90f, y + 14f, tableHeaderTextPaint)
            canvas.drawText("المطابق", pageWidth - margin - 160f, y + 14f, tableHeaderTextPaint)
            canvas.drawText("المفقود", pageWidth - margin - 230f, y + 14f, tableHeaderTextPaint)
            canvas.drawText("الوزن المقروء", pageWidth - margin - 330f, y + 14f, tableHeaderTextPaint)
            canvas.drawText("الوزن المتوقع", pageWidth - margin - 430f, y + 14f, tableHeaderTextPaint)
            y += 20f

            for (k in report.karatSummaries) {
                ensureSpace(20f)
                canvas.drawText(k.karat, pageWidth - margin - 10f, y + 14f, bodyRightPaint)
                canvas.drawText("${k.expectedCount}", pageWidth - margin - 90f, y + 14f, bodyRightPaint)
                canvas.drawText("${k.matchedCount}", pageWidth - margin - 160f, y + 14f, bodyRightPaint)
                canvas.drawText("${k.missingCount}", pageWidth - margin - 230f, y + 14f, bodyRightPaint)
                canvas.drawText(String.format(Locale.US, "%.2fg", k.scannedWeight), pageWidth - margin - 330f, y + 14f, bodyRightPaint)
                canvas.drawText(String.format(Locale.US, "%.2fg", k.expectedWeight), pageWidth - margin - 430f, y + 14f, bodyRightPaint)
                y += 18f
                canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
            }
            y += 16f
        }

        // 4. جدول القطع المفقودة (العجز)
        if (report.missingItems.isNotEmpty()) {
            ensureSpace(50f)
            canvas.drawText("ثالثاً: كشف القطع المفقودة / غير المقروءة (${report.missingItems.size} قطعة)", pageWidth - margin, y, sectionPaint)
            y += 10f
            canvas.drawRect(margin, y, pageWidth - margin, y + 20f, tableHeaderPaint)
            canvas.drawText("القطعة", pageWidth - margin - 10f, y + 14f, tableHeaderTextPaint)
            canvas.drawText("العيار", pageWidth - margin - 180f, y + 14f, tableHeaderTextPaint)
            canvas.drawText("الوزن", pageWidth - margin - 235f, y + 14f, tableHeaderTextPaint)
            canvas.drawText("القيمة", pageWidth - margin - 300f, y + 14f, tableHeaderTextPaint)
            canvas.drawText("EPC / التسلسلي", margin + 8f, y + 14f, tableHeaderTextPaint.apply { textAlign = Paint.Align.LEFT })
            tableHeaderTextPaint.textAlign = Paint.Align.RIGHT
            y += 20f

            for (item in report.missingItems) {
                ensureSpace(20f)
                val shortTitle = if (item.title.length > 26) item.title.take(26) + "…" else item.title
                val codeText = item.epc.ifEmpty { item.serialNo }
                canvas.drawText(shortTitle, pageWidth - margin - 10f, y + 14f, bodyRightPaint)
                canvas.drawText(item.karat, pageWidth - margin - 180f, y + 14f, bodyRightPaint)
                canvas.drawText(String.format(Locale.US, "%.2fg", item.weight), pageWidth - margin - 235f, y + 14f, bodyRightPaint)
                canvas.drawText(String.format(Locale.US, "$%.0f", item.priceUsd), pageWidth - margin - 300f, y + 14f, bodyRightPaint)
                canvas.drawText(codeText, margin + 6f, y + 14f, bodyLeftPaint)
                y += 18f
                canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
            }
            y += 16f
        }

        // 5. جدول القطع المطابقة
        if (report.matchedItems.isNotEmpty()) {
            ensureSpace(50f)
            canvas.drawText("رابعاً: كشف القطع المطابقة المقروءة (${report.matchedItems.size} قطعة)", pageWidth - margin, y, sectionPaint)
            y += 10f
            canvas.drawRect(margin, y, pageWidth - margin, y + 20f, tableHeaderPaint)
            canvas.drawText("القطعة", pageWidth - margin - 10f, y + 14f, tableHeaderTextPaint)
            canvas.drawText("العيار", pageWidth - margin - 175f, y + 14f, tableHeaderTextPaint)
            canvas.drawText("الوزن", pageWidth - margin - 225f, y + 14f, tableHeaderTextPaint)
            canvas.drawText("الإشارة", pageWidth - margin - 285f, y + 14f, tableHeaderTextPaint)
            canvas.drawText("EPC / TID", margin + 8f, y + 14f, tableHeaderTextPaint.apply { textAlign = Paint.Align.LEFT })
            tableHeaderTextPaint.textAlign = Paint.Align.RIGHT
            y += 20f

            for (item in report.matchedItems) {
                ensureSpace(20f)
                val shortTitle = if (item.title.length > 24) item.title.take(24) + "…" else item.title
                val codeText = "${item.epc} (${item.readCount}x)"
                canvas.drawText(shortTitle, pageWidth - margin - 10f, y + 14f, bodyRightPaint)
                canvas.drawText(item.karat, pageWidth - margin - 175f, y + 14f, bodyRightPaint)
                canvas.drawText(String.format(Locale.US, "%.2fg", item.weight), pageWidth - margin - 225f, y + 14f, bodyRightPaint)
                canvas.drawText("${item.rssi}dBm", pageWidth - margin - 285f, y + 14f, bodyRightPaint)
                canvas.drawText(codeText, margin + 6f, y + 14f, bodyLeftPaint)
                y += 18f
                canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
            }
            y += 16f
        }

        // 6. جدول البطاقات غير المسجلة إن وجدت
        if (report.unregisteredItems.isNotEmpty()) {
            ensureSpace(45f)
            canvas.drawText("خامساً: بطاقات غير مسجلة في الفرع (${report.unregisteredItems.size})", pageWidth - margin, y, sectionPaint)
            y += 14f
            for (u in report.unregisteredItems) {
                ensureSpace(18f)
                canvas.drawText("EPC: ${u.epc}  |  RSSI: ${u.rssi}dBm  |  Reads: ${u.readCount}x", margin + 6f, y + 12f, bodyLeftPaint)
                y += 16f
            }
        }

        doc.finishPage(page)

        val outDir = File(context.cacheDir, "audit_reports").apply { mkdirs() }
        val outFile = File(outDir, "MIRA_Audit_Session_${report.sessionId}.pdf")
        FileOutputStream(outFile).use { fos -> doc.writeTo(fos) }
        doc.close()
        return outFile
    }

    private fun generateDetailedCsvFile(context: Context, report: ReconciledAuditReport): File {
        val outDir = File(context.cacheDir, "audit_reports").apply { mkdirs() }
        val outFile = File(outDir, "MIRA_Audit_Session_${report.sessionId}.csv")
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        FileOutputStream(outFile).use { fos ->
            // كتابة UTF-8 BOM لضمان عرض النصوص العربية بوضوح في Excel
            fos.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                writer.appendLine("MIRA TECHNOLOGY - INVENTORY AUDIT REPORT")
                writer.appendLine("Session ID,#${report.sessionId}")
                writer.appendLine("Branch,\"${report.branchName}\"")
                writer.appendLine("Auditor,\"${report.auditorName}\"")
                writer.appendLine("Approved At,\"${dateFmt.format(Date(report.approvedAtMs))}\"")
                writer.appendLine("Expected Count,${report.expectedCount}")
                writer.appendLine("Matched Count,${report.matchedCount}")
                writer.appendLine("Missing Count,${report.missingCount}")
                writer.appendLine("Unregistered Count,${report.unregisteredCount}")
                writer.appendLine("Accuracy %,${report.accuracyPercent}%")
                writer.appendLine("Expected Weight (g),${String.format(Locale.US, "%.2f", report.expectedWeight)}")
                writer.appendLine("Scanned Weight (g),${String.format(Locale.US, "%.2f", report.scannedWeight)}")
                writer.appendLine("Security Assessment,\"${report.riskTitleAr}\"")
                writer.appendLine()

                writer.appendLine("KARAT BREAKDOWN")
                writer.appendLine("Karat,Expected,Matched,Missing,Scanned Weight (g),Expected Weight (g)")
                for (k in report.karatSummaries) {
                    writer.appendLine(
                        "${k.karat},${k.expectedCount},${k.matchedCount},${k.missingCount}," +
                                "${String.format(Locale.US, "%.2f", k.scannedWeight)}," +
                                "${String.format(Locale.US, "%.2f", k.expectedWeight)}"
                    )
                }
                writer.appendLine()

                writer.appendLine("DETAILED ITEMS LIST")
                writer.appendLine("Status,Title,Karat,Weight (g),Price (USD),Serial No,EPC,TID,TID Verified,RSSI (dBm),Read Count")
                val combined = report.missingItems + report.matchedItems + report.unregisteredItems
                for (item in combined) {
                    val statusAr = when (item.auditStatus) {
                        ItemAuditStatus.MATCHED -> "مطابق (Matched)"
                        ItemAuditStatus.MISSING -> "مفقود (Missing)"
                        ItemAuditStatus.UNREGISTERED -> "غير مسجل (Unregistered)"
                    }
                    val safeTitle = item.title.replace("\"", "\"\"")
                    writer.appendLine(
                        "\"$statusAr\",\"$safeTitle\",${item.karat}," +
                                "${String.format(Locale.US, "%.2f", item.weight)}," +
                                "${String.format(Locale.US, "%.2f", item.priceUsd)}," +
                                "\"${item.serialNo}\",\"${item.epc}\",\"${item.tid}\"," +
                                "${item.tidVerified},${item.rssi},${item.readCount}"
                    )
                }
            }
        }
        return outFile
    }

    private fun shareGeneratedFile(ctx: Context, file: File, mimeType: String, chooserTitle: String) {
        try {
            val authority = "${ctx.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(ctx, authority, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, chooserTitle))
        } catch (e: Exception) {
            Log.e(TAG, "shareGeneratedFile error", e)
            showToast(
                if (isArabic) "تم حفظ الملف في: ${file.name}"
                else "File saved: ${file.name}"
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Timer & Navigation
    // ═══════════════════════════════════════════════════════════════════

    private fun startTicker() {
        stopTicker()
        tickerRunnable = object : Runnable {
            override fun run() {
                if (isScanningState) {
                    elapsedSecondsState = getCurrentElapsedSeconds()
                    tagRatePerSecState = readsInLastSecond
                    readsInLastSecond = 0
                }
                mainHandler.postDelayed(this, 1000L)
            }
        }
        mainHandler.postDelayed(tickerRunnable!!, 1000L)
    }

    private fun stopTicker() {
        tickerRunnable?.let { mainHandler.removeCallbacks(it) }
        tickerRunnable = null
    }

    private fun getCurrentElapsedSeconds(): Long {
        val activeSegment = if (isScanningState && currentScanSegmentStartMs > 0L) {
            System.currentTimeMillis() - currentScanSegmentStartMs
        } else 0L
        return (accumulatedElapsedMs + activeSegment) / 1000L
    }

    private fun setupBackHandler() {
        if (activity == null) return
        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback!!)
    }

    private fun handleBackNavigation() {
        if (approvedReportDialogState != null) {
            approvedReportDialogState = null
            return
        }
        if (isScanningState) {
            stopInventoryScan()
            return
        }
        if (matchedCountState > 0 || unregisteredCountState > 0) {
            showExitConfirmDialogState = true
            return
        }
        navigateAway()
    }

    private fun navigateAway() {
        stopInventoryScan()
        val act = activity as? UHFMainActivity ?: return
        act.setBottomNavVisible(true)
        act.navigationManager?.goToDashboard()
        act.syncCurrentFragment()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose Screen & Approval Dialog
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun MiraInventoryScreen() {
        val colors = MiraTheme.colors
        val isAr = isArabic

        val filteredItems = remember(
            allDisplayItemsState,
            selectedFilterTabState,
            searchQueryState,
            selectedKaratFilterState
        ) {
            allDisplayItemsState.filter { item ->
                val matchesTab = when (selectedFilterTabState) {
                    AuditFilterTab.ALL -> true
                    AuditFilterTab.MATCHED -> item.auditStatus == ItemAuditStatus.MATCHED
                    AuditFilterTab.MISSING -> item.auditStatus == ItemAuditStatus.MISSING
                    AuditFilterTab.UNREGISTERED -> item.auditStatus == ItemAuditStatus.UNREGISTERED
                }
                val matchesKarat = selectedKaratFilterState == null ||
                        item.karat.equals(selectedKaratFilterState, ignoreCase = true)
                val q = searchQueryState.trim()
                val matchesQuery = q.isEmpty() ||
                        item.title.contains(q, ignoreCase = true) ||
                        item.epc.contains(q, ignoreCase = true) ||
                        item.serialNo.contains(q, ignoreCase = true)
                matchesTab && matchesKarat && matchesQuery
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.backgroundTop
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // Top Header
                InventoryTopHeader(
                    isArabic = isAr,
                    branchId = branchIdState,
                    branchName = branchNameState,
                    isSyncing = isSyncingCatalogState,
                    onSyncClick = { syncBranchCatalogWithServer(silent = false) },
                    onRefreshClick = { recomputeReconciliationState() },
                    onBackClick = { handleBackNavigation() }
                )

                // Summary KPI Card
                InventorySummaryDashboardCard(
                    isArabic = isAr,
                    expectedCount = expectedCountState,
                    matchedCount = matchedCountState,
                    missingCount = missingCountState,
                    unregisteredCount = unregisteredCountState,
                    accuracyPercent = accuracyPercentState,
                    elapsedSeconds = elapsedSecondsState,
                    tagRate = tagRatePerSecState,
                    estimatedValueUsd = estimatedValueUsdState,
                    scannedWeight = scannedWeightState,
                    expectedWeight = expectedWeightState,
                    karatSummaries = karatSummariesState,
                    selectedKarat = selectedKaratFilterState,
                    onKaratToggle = { karat ->
                        selectedKaratFilterState = if (selectedKaratFilterState == karat) null else karat
                    }
                )

                // Filter Tabs
                InventoryFilterTabsRow(
                    isArabic = isAr,
                    selectedTab = selectedFilterTabState,
                    allCount = allDisplayItemsState.size,
                    matchedCount = matchedCountState,
                    missingCount = missingCountState,
                    unregisteredCount = unregisteredCountState,
                    onSelectTab = { selectedFilterTabState = it }
                )

                // Search Bar
                OutlinedTextField(
                    value = searchQueryState,
                    onValueChange = { searchQueryState = it },
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = if (isAr) "بحث بالاسم، EPC، الباركود، أو الرقم التسلسلي..."
                            else "Search by name, EPC, barcode, or serial...",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.textSecondary)
                    },
                    trailingIcon = {
                        if (searchQueryState.isNotEmpty()) {
                            IconButton(onClick = { searchQueryState = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = null, tint = colors.textSecondary)
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )

                // Audited Items List
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = filteredItems,
                        key = { it.key }
                    ) { item ->
                        InventoryAuditItemCard(
                            isArabic = isAr,
                            item = item
                        )
                    }
                }

                // Bottom Control Bar
                InventoryBottomControlBar(
                    isArabic = isAr,
                    isScanning = isScanningState,
                    isFinalizing = isFinalizingState,
                    onResetClick = { showResetConfirmDialogState = true },
                    onApproveClick = { initiateAuditApproval() },
                    onToggleScanClick = {
                        if (isScanningState) stopInventoryScan() else startInventoryScan()
                    },
                    onPowerSettingsClick = {
                        getSafeAppContext()?.let { appCtx ->
                            PosPowerSettingsDialog.show(appCtx, isAr) { newPower ->
                                sdkExecutor.execute {
                                    runCatching {
                                        rfidManager?.setPower(newPower)
                                        BaseTabFragmentActivity.getSharedReader()?.setPower(newPower)
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }

        // نافذة اعتماد الجرد التفصيلية
        approvedReportDialogState?.let { report ->
            AuditApprovalResultDialog(
                isArabic = isAr,
                report = report,
                onExportCsv = { exportAndShareCsvReport(report) },
                onExportPdf = { exportAndSharePdfReport(report) },
                onDismiss = { approvedReportDialogState = null }
            )
        }

        if (showResetConfirmDialogState) {
            AlertDialog(
                onDismissRequest = { showResetConfirmDialogState = false },
                containerColor = colors.cardSurface,
                title = {
                    Text(
                        text = if (isAr) "تصفير جلسة الجرد؟" else "Reset Audit Session?",
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                },
                text = {
                    Text(
                        text = if (isAr) "سيتم مسح القراءات الحالية والبدء من الصفر."
                        else "Current scanned tags will be cleared.",
                        color = colors.textSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showResetConfirmDialogState = false
                            resetAuditSession()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                    ) {
                        Text(if (isAr) "تصفير" else "Reset", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirmDialogState = false }) {
                        Text(if (isAr) "إلغاء" else "Cancel", color = colors.textSecondary)
                    }
                }
            )
        }

        if (showExitConfirmDialogState) {
            AlertDialog(
                onDismissRequest = { showExitConfirmDialogState = false },
                containerColor = colors.cardSurface,
                title = {
                    Text(
                        text = if (isAr) "مغادرة الجرد دون اعتماد؟" else "Leave Without Approving?",
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                },
                text = {
                    Text(
                        text = if (isAr) "تمت قراءة $matchedCountState قطعة. هل تريد المغادرة دون حفظ التقرير؟"
                        else "$matchedCountState items scanned. Leave without saving?",
                        color = colors.textSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showExitConfirmDialogState = false
                            navigateAway()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.goldPrimary)
                    ) {
                        Text(if (isAr) "مغادرة" else "Leave", color = Color(0xFF0A0F1D))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExitConfirmDialogState = false }) {
                        Text(if (isAr) "البقاء" else "Stay", color = colors.textSecondary)
                    }
                }
            )
        }
    }

    @Composable
    private fun InventoryTopHeader(
        isArabic: Boolean,
        branchId: Int,
        branchName: String,
        isSyncing: Boolean,
        onSyncClick: () -> Unit,
        onRefreshClick: () -> Unit,
        onBackClick: () -> Unit
    ) {
        val colors = MiraTheme.colors

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onSyncClick, enabled = !isSyncing) {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = null,
                        tint = if (isSyncing) colors.goldPrimary else Color(0xFF0EA5E9)
                    )
                }
                IconButton(onClick = onRefreshClick) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = colors.textPrimary
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = if (isArabic) "جرد المجوهرات الذكي" else "Smart Jewelry Audit",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.goldPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isArabic) "فرع #$branchId • $branchName" else "Branch #$branchId • $branchName",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = colors.goldPrimary
                )
            }
        }
    }

    @Composable
    private fun InventorySummaryDashboardCard(
        isArabic: Boolean,
        expectedCount: Int,
        matchedCount: Int,
        missingCount: Int,
        unregisteredCount: Int,
        accuracyPercent: Int,
        elapsedSeconds: Long,
        tagRate: Int,
        estimatedValueUsd: Double,
        scannedWeight: Double,
        expectedWeight: Double,
        karatSummaries: List<KaratSummary>,
        selectedKarat: String?,
        onKaratToggle: (String) -> Unit
    ) {
        val colors = MiraTheme.colors

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.25f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // حلقة نسبة المطابقة
                    Box(
                        modifier = Modifier.size(86.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val sweep = (accuracyPercent.coerceIn(0, 100) / 100f) * 360f
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawArc(
                                color = Color.Gray.copy(alpha = 0.2f),
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                            )
                            drawArc(
                                color = if (accuracyPercent >= 95) Color(0xFF10B981) else Color(0xFFB45309),
                                startAngle = -90f,
                                sweepAngle = sweep,
                                useCenter = false,
                                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "$accuracyPercent%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = colors.goldPrimary
                            )
                            Text(
                                text = if (isArabic) "مطابقة" else "Matched",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = colors.textSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // شبكة الأرقام الأربعة
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MiniStatTile(
                                modifier = Modifier.weight(1f),
                                label = if (isArabic) "المتوقع" else "Expected",
                                value = expectedCount.toString(),
                                valueColor = colors.textPrimary
                            )
                            MiniStatTile(
                                modifier = Modifier.weight(1f),
                                label = if (isArabic) "المطابق" else "Matched",
                                value = matchedCount.toString(),
                                valueColor = Color(0xFF10B981)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MiniStatTile(
                                modifier = Modifier.weight(1f),
                                label = if (isArabic) "المفقود" else "Missing",
                                value = missingCount.toString(),
                                valueColor = Color(0xFFEF4444)
                            )
                            MiniStatTile(
                                modifier = Modifier.weight(1f),
                                label = if (isArabic) "زائد/مخالف" else "Extra",
                                value = unregisteredCount.toString(),
                                valueColor = Color(0xFFF59E0B)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // شريط الوقت والقيمة والوزن
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.Start) {
                        val mins = elapsedSeconds / 60
                        val secs = elapsedSeconds % 60
                        Text(
                            text = String.format(Locale.US, "%02d:%02d", mins, secs),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "$tagRate tag/s",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (isArabic) "القيمة التقديرية" else "Est. Value",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                        Text(
                            text = String.format(Locale.US, "$%,.0f", estimatedValueUsd),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF10B981)
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (isArabic) "الوزن المقروء / المتوقع" else "Scanned / Expected Wt",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                        Text(
                            text = String.format(Locale.US, "%.2fg / %.2fg", scannedWeight, expectedWeight),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.goldPrimary
                        )
                    }
                }

                // شرائح العيارات
                if (karatSummaries.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (k in karatSummaries) {
                            val isSelected = selectedKarat.equals(k.karat, ignoreCase = true)
                            val labelNum = k.karat.replace("K", "")
                            Surface(
                                modifier = Modifier.clickable { onKaratToggle(k.karat) },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) colors.goldPrimary.copy(alpha = 0.22f)
                                else colors.backgroundTop.copy(alpha = 0.6f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) colors.goldPrimary else colors.textSecondary.copy(alpha = 0.2f)
                                )
                            ) {
                                Text(
                                    text = if (isArabic) {
                                        "عيار $labelNum  ${k.matchedCount}/${k.expectedCount} (${String.format(Locale.US, "%.1fg", k.scannedWeight)})"
                                    } else {
                                        "${k.karat}  ${k.matchedCount}/${k.expectedCount} (${String.format(Locale.US, "%.1fg", k.scannedWeight)})"
                                    },
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.goldPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MiniStatTile(
        modifier: Modifier = Modifier,
        label: String,
        value: String,
        valueColor: Color
    ) {
        val colors = MiraTheme.colors
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(10.dp),
            color = colors.backgroundTop.copy(alpha = 0.55f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = valueColor
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
                )
            }
        }
    }

    @Composable
    private fun InventoryFilterTabsRow(
        isArabic: Boolean,
        selectedTab: AuditFilterTab,
        allCount: Int,
        matchedCount: Int,
        missingCount: Int,
        unregisteredCount: Int,
        onSelectTab: (AuditFilterTab) -> Unit
    ) {
        val colors = MiraTheme.colors
        val tabs = listOf(
            Triple(AuditFilterTab.ALL, if (isArabic) "الكل ($allCount)" else "All ($allCount)", colors.goldPrimary),
            Triple(AuditFilterTab.MATCHED, if (isArabic) "مطابق ($matchedCount)" else "Matched ($matchedCount)", Color(0xFF10B981)),
            Triple(AuditFilterTab.MISSING, if (isArabic) "مفقود ($missingCount)" else "Missing ($missingCount)", Color(0xFFEF4444)),
            Triple(AuditFilterTab.UNREGISTERED, if (isArabic) "غير مسجل ($unregisteredCount)" else "Extra ($unregisteredCount)", Color(0xFFF59E0B))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for ((tab, title, accent) in tabs) {
                val active = selectedTab == tab
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelectTab(tab) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (active) accent.copy(alpha = 0.16f) else Color.Transparent,
                    border = BorderStroke(
                        1.dp,
                        if (active) accent else colors.textSecondary.copy(alpha = 0.16f)
                    )
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) accent else colors.textSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    @Composable
    private fun InventoryAuditItemCard(
        isArabic: Boolean,
        item: AuditDisplayItem
    ) {
        val colors = MiraTheme.colors
        val (statusText, statusColor) = when (item.auditStatus) {
            ItemAuditStatus.MATCHED -> (if (isArabic) "مطابق" else "Matched") to Color(0xFF10B981)
            ItemAuditStatus.MISSING -> (if (isArabic) "مفقود" else "Missing") to Color(0xFFEF4444)
            ItemAuditStatus.UNREGISTERED -> (if (isArabic) "غير مسجل" else "Unregistered") to Color(0xFFF59E0B)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = statusColor.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = statusText,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = colors.goldPrimary.copy(alpha = 0.18f)
                        ) {
                            val kNum = item.karat.replace("K", "")
                            Text(
                                text = if (isArabic && kNum != "—") "عيار $kNum" else item.karat,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = colors.goldPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (item.auditStatus != ItemAuditStatus.MISSING) {
                        Text(
                            text = "${item.rssi}dBm • ${item.readCount}x",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                    } else {
                        Text(
                            text = if (isArabic) "لم يُقرأ في الجلسة" else "Not scanned",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFEF4444)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (item.priceUsd > 0.0) {
                            Text(
                                text = String.format(Locale.US, "$%,.0f", item.priceUsd),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981)
                            )
                        }
                        if (item.weight > 0.0) {
                            Text(
                                text = String.format(Locale.US, "%.2fg", item.weight),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.goldPrimary
                            )
                        }
                        Text(
                            text = item.epc.ifEmpty { item.serialNo },
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (item.tidVerified) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isArabic) "فحص TID ✓" else "TID Verified ✓",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.goldPrimary
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun InventoryBottomControlBar(
        isArabic: Boolean,
        isScanning: Boolean,
        isFinalizing: Boolean,
        onResetClick: () -> Unit,
        onApproveClick: () -> Unit,
        onToggleScanClick: () -> Unit,
        onPowerSettingsClick: () -> Unit
    ) {
        val colors = MiraTheme.colors

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colors.cardSurface,
            shadowElevation = 10.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // تصفير
                OutlinedButton(
                    onClick = onResetClick,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(20.dp))
                }

                // زر اعتماد الجرد وحفظ الجلسة + لقطة الشاشة
                Button(
                    onClick = onApproveClick,
                    enabled = !isFinalizing,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF047857),
                        contentColor = Color.White
                    )
                ) {
                    if (isFinalizing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Rounded.CloudDone, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isArabic) "اعتماد" else "Approve",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                // زر بدء / إيقاف المسح
                Button(
                    onClick = onToggleScanClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isScanning) Color(0xFFDC2626) else colors.goldPrimary,
                        contentColor = if (isScanning) Color.White else Color(0xFF0A0F1D)
                    )
                ) {
                    Icon(
                        imageVector = if (isScanning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isScanning) {
                            if (isArabic) "إيقاف" else "Stop"
                        } else {
                            if (isArabic) "بدء" else "Start"
                        },
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                }

                // إعدادات الطاقة
                OutlinedButton(
                    onClick = onPowerSettingsClick,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    /**
     * نافذة اعتماد تقرير الجرد المصححة بالكامل:
     * - تعرض الأرقام الحقيقية المتطابقة مع الجرد الفعلي
     * - تترجم التقييم الأمني للعربية
     * - تصدر تقرير PDF و CSV تفصيلي كامل
     */
    @Composable
    private fun AuditApprovalResultDialog(
        isArabic: Boolean,
        report: ReconciledAuditReport,
        onExportCsv: () -> Unit,
        onExportPdf: () -> Unit,
        onDismiss: () -> Unit
    ) {
        val colors = MiraTheme.colors

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                border = BorderStroke(1.5.dp, colors.goldPrimary.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF059669)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = if (isArabic) "تم اعتماد تقرير الجرد" else "Inventory Report Approved",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.goldPrimary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (isArabic) "رقم الجلسة: #${report.sessionId}" else "Session ID: #${report.sessionId}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textSecondary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = if (isArabic) "نسبة المطابقة: ${report.accuracyPercent}%"
                        else "Match Accuracy: ${report.accuracyPercent}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF10B981)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = if (isArabic) {
                            "المتوقع: ${report.expectedCount}  |  المطابق: ${report.matchedCount}  |  المفقود: ${report.missingCount}"
                        } else {
                            "Expected: ${report.expectedCount}  |  Matched: ${report.matchedCount}  |  Missing: ${report.missingCount}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = String.format(
                            Locale.US,
                            if (isArabic) "الوزن المقروء: %.2fg من %.2fg" else "Scanned Weight: %.2fg / %.2fg",
                            report.scannedWeight,
                            report.expectedWeight
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // شارة التقييم الأمني المترجمة
                    val riskBg = if (report.isHighRisk) Color(0xFFEF4444).copy(alpha = 0.14f)
                    else Color(0xFF10B981).copy(alpha = 0.14f)
                    val riskFg = if (report.isHighRisk) Color(0xFFEF4444) else Color(0xFF10B981)

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = riskBg,
                        border = BorderStroke(1.dp, riskFg.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = if (isArabic) report.riskTitleAr else report.riskTitleEn,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = riskFg,
                            textAlign = TextAlign.Center
                        )
                    }

                    if (report.screenshotFilePath != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isArabic) "📸 تم توثيق الجلسة بلقطة شاشة وحفظ التفاصيل"
                            else "📸 Session verified with screenshot & full item log",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // صندوق تصدير وطباعة التقرير التفصيلي
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = colors.backgroundTop.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.2f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "#${report.sessionId}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                                Text(
                                    text = if (isArabic) "تصدير وطباعة التقرير التفصيلي"
                                    else "Export & Print Detailed Report",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.goldPrimary
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onExportCsv,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp),
                                    shape = RoundedCornerShape(50),
                                    border = BorderStroke(1.dp, colors.goldPrimary)
                                ) {
                                    Icon(
                                        Icons.Rounded.Download,
                                        contentDescription = null,
                                        tint = colors.goldPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "CSV",
                                        fontWeight = FontWeight.Bold,
                                        color = colors.goldPrimary
                                    )
                                }

                                Button(
                                    onClick = onExportPdf,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp),
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.goldPrimary,
                                        contentColor = Color(0xFF0A0F1D)
                                    )
                                ) {
                                    Icon(
                                        Icons.Rounded.Print,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isArabic) "PDF / طباعة" else "PDF / Print",
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.Start)
                            .height(42.dp),
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.goldPrimary,
                            contentColor = Color(0xFF0A0F1D)
                        )
                    ) {
                        Text(
                            text = if (isArabic) "تم" else "Done",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Risk Evaluation, Cache Extraction & Utilities
    // ═══════════════════════════════════════════════════════════════════

    private fun evaluateAuditRisk(
        expected: Int,
        matched: Int,
        missing: Int,
        unregistered: Int
    ): Triple<String, String, Boolean> {
        if (expected == 0) {
            return Triple("no_expected_items", "لا توجد قطع مسجلة للفرع", false)
        }
        val missingRatio = missing.toDouble() / expected.toDouble()
        return when {
            missing == 0 && unregistered == 0 ->
                Triple("verified_clean", "مطابقة تامة 100% — المخزون سليم", false)
            missingRatio > 0.25 ->
                Triple("security_breach_possible", "تنبيه: عجز مرتفع يتطلب مراجعة فورية", true)
            missing > 0 ->
                Triple("minor_variance", "يوجد فارق جرد ($missing قطعة غير مقروءة)", true)
            else ->
                Triple("unregistered_detected", "تم رصد بطاقات غير مسجلة في الفرع", true)
        }
    }

    private fun translateRiskStatus(
        riskCode: String,
        missingCount: Int,
        expectedCount: Int
    ): Triple<String, String, Boolean> {
        return when (riskCode.lowercase(Locale.US)) {
            "verified_clean" ->
                Triple("مطابقة تامة 100% — المخزون سليم", "100% Verified Clean", false)
            "security_breach_possible" ->
                Triple("تنبيه: عجز مرتفع يتطلب مراجعة أمنية", "Alert: High Variance — Review Required", true)
            "minor_variance" ->
                Triple("فارق جرد جزئي ($missingCount من $expectedCount مفقود)", "Partial Variance ($missingCount missing)", true)
            "unregistered_detected" ->
                Triple("تنبيه: بطاقات غير مسجلة بالفرع", "Alert: Unregistered Tags Found", true)
            else -> evaluateAuditRisk(expectedCount, expectedCount - missingCount, missingCount, 0)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractBranchItemsFromCache(
        cache: MiraLocalItemCache,
        branchId: Int
    ): List<CatalogJewelryItem> {
        val result = ArrayList<CatalogJewelryItem>()
        try {
            val candidateMethods = listOf(
                "getItemsForBranch",
                "getBranchItems",
                "getAllItems",
                "getCachedItems"
            )
            var rawCollection: Any? = null
            for (mName in candidateMethods) {
                val m = cache.javaClass.methods.firstOrNull { it.name == mName && it.parameterTypes.size <= 1 }
                if (m != null) {
                    rawCollection = if (m.parameterTypes.isEmpty()) {
                        m.invoke(cache)
                    } else {
                        m.invoke(cache, branchId)
                    }
                    if (rawCollection != null) break
                }
            }

            val jsonItems = ArrayList<JSONObject>()
            when (rawCollection) {
                is JSONArray -> {
                    for (i in 0 until rawCollection.length()) {
                        rawCollection.optJSONObject(i)?.let { jsonItems.add(it) }
                    }
                }
                is List<*> -> {
                    for (el in rawCollection) {
                        if (el is JSONObject) jsonItems.add(el)
                    }
                }
                is Map<*, *> -> {
                    for (v in rawCollection.values) {
                        if (v is JSONObject) jsonItems.add(v)
                    }
                }
            }

            for (obj in jsonItems) {
                val itemBranch = obj.optInt("branch_id", branchId)
                if (itemBranch > 0 && itemBranch != branchId) continue

                val status = obj.optString("vault_status", obj.optString("status", "in_vault")).lowercase(Locale.US)
                if (status == "sold" || status == "exited" || status == "deleted") continue

                val id = obj.optInt("id", obj.optInt("item_id", 0))
                val epc = obj.optString("rfid_epc", obj.optString("epc_final", obj.optString("epc", ""))).trim()
                val tid = obj.optString("tid", obj.optString("tag_tid", "")).trim()
                val serial = obj.optString("serial", obj.optString("serial_number", "")).trim()
                val gtin = obj.optString("gtin", obj.optString("barcode", "")).trim()
                val title = obj.optString("title", obj.optString("item_title", if (isArabic) "قطعة مجوهرات" else "Jewelry Item"))
                val karat = obj.optString("karat", obj.optString("gold_karat", "21K"))
                val weight = obj.optDouble("weight", obj.optDouble("gross_weight", 0.0))
                var priceUsd = obj.optDouble("retail_price_usd", obj.optDouble("price_usd", 0.0))
                if (priceUsd <= 0.0 && weight > 0.0) {
                    priceUsd = estimateItemUsdPrice(karat, weight)
                }
                val location = obj.optString("location", obj.optString("zone", ""))

                result.add(
                    CatalogJewelryItem(
                        itemId = id,
                        epc = epc,
                        tid = tid,
                        serialNo = serial,
                        gtin = gtin,
                        title = title,
                        karat = karat,
                        weight = weight,
                        priceUsd = priceUsd,
                        location = location,
                        status = status
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractBranchItemsFromCache error", e)
        }
        return result
    }

    private fun invokeSyncManagerRefresh(syncManager: MiraItemSyncManager, branchId: Int) {
        try {
            val methods = syncManager.javaClass.methods
            val syncMethod = methods.firstOrNull {
                (it.name == "syncBranchItems" || it.name == "syncItems" || it.name == "syncAll") &&
                        it.parameterTypes.size <= 1
            }
            if (syncMethod != null) {
                if (syncMethod.parameterTypes.isEmpty()) {
                    syncMethod.invoke(syncManager)
                } else {
                    syncMethod.invoke(syncManager, branchId)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "invokeSyncManagerRefresh warning: ${e.message}")
        }
    }

    private fun estimateItemUsdPrice(karat: String, weight: Double): Double {
        val pm = priceManager ?: return 0.0
        val kNum = karat.uppercase(Locale.US).replace("K", "").toIntOrNull() ?: 21
        return try {
            val ratePerGram = pm.getGoldPricePerGram(kNum)
            if (ratePerGram > 0.0) ratePerGram * weight else 0.0
        } catch (_: Exception) {
            0.0
        }
    }

    private fun normalizeKaratLabel(raw: String): String {
        val clean = raw.trim().uppercase(Locale.US)
        return when {
            clean.contains("24") -> "24K"
            clean.contains("22") -> "22K"
            clean.contains("21") -> "21K"
            clean.contains("18") -> "18K"
            clean.contains("14") -> "14K"
            clean.isEmpty() -> "21K"
            clean.endsWith("K") -> clean
            else -> "${clean}K"
        }
    }

    private fun getActiveBranchId(): Int {
        return try {
            branchesManager?.activeBranchId ?: 1
        } catch (_: Exception) {
            1
        }
    }

    private fun getSafeAppContext(): Context? {
        return try {
            context?.applicationContext
        } catch (_: Exception) {
            null
        }
    }

    private fun isFragmentAlive(): Boolean {
        return isAdded && !isRemoving && !isDetached &&
                activity != null && !requireActivity().isFinishing && !requireActivity().isDestroyed
    }

    private fun safeRun(r: Runnable) {
        try {
            activity?.runOnUiThread {
                if (isFragmentAlive()) {
                    r.run()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun showToast(msg: String) {
        PosUIManager.toast(context, msg)
    }

    private fun playAlertSound(id: Int) {
        try {
            (activity as? UHFMainActivity)?.playSound(id)
        } catch (_: Exception) {
        }
    }
    // ═══════════════════════════════════════════════════════════════════
//  Public API — تُستدعى من UHFMainActivity
// ═══════════════════════════════════════════════════════════════════

/** هل المسح يعمل الآن؟ (UHFMainActivity:1694) */
fun isCurrentlyScanning(): Boolean = isScanningState

/** اسم بديل مستخدم في UHFMainActivity:2286 */
fun isScanning(): Boolean = isScanningState

/** إيقاف المسح من خارج الـ fragment (UHFMainActivity:2318) */
fun stopScanning() {
    stopInventoryScan()
}

/** عدد الوسوم الفريدة التي قُرئت في الجلسة (UHFMainActivity:1693) */
fun getScannedCount(): Int = scannedMetricsByEpc.size

/** فتح أرشيف تقارير PDF/CSV (UHFMainActivity:1726, 1733, 1741) */
fun showArchiveReportsPublic() {
    try {
        val appCtx = getSafeAppContext() ?: return
        val dir = File(appCtx.cacheDir, "audit_reports")
        val files = dir.listFiles()
            ?.filter { it.isFile && (it.extension == "pdf" || it.extension == "csv") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (files.isEmpty()) {
            showToast(if (isArabic) "📂 لا توجد تقارير محفوظة بعد" else "📂 No archived reports yet")
            return
        }

        val latest = files.first()
        val mime = if (latest.extension == "pdf") "application/pdf" else "text/csv"
        shareGeneratedFile(
            ctx = appCtx,
            file = latest,
            mimeType = mime,
            chooserTitle = if (isArabic)
                "مشاركة آخر تقرير (${files.size} إجمالي)"
            else
                "Share latest report (${files.size} total)"
        )
    } catch (e: Exception) {
        Log.e(TAG, "showArchiveReportsPublic error", e)
        showToast(if (isArabic) "⚠️ تعذر فتح الأرشيف" else "⚠️ Failed to open archive")
    }
}

/** إعدادات الماسح — تفتح حوار القدرة (UHFMainActivity:674, 679) */
fun showInventoryScannerSettings() {
    val appCtx = getSafeAppContext() ?: return
    PosPowerSettingsDialog.show(appCtx, isArabic) { newPower ->
        MiraReaderSettings.getInstance(appCtx).inventoryPowerDbm = newPower
        sdkExecutor.execute {
            runCatching {
                rfidManager?.setPower(newPower)
                BaseTabFragmentActivity.getSharedReader()?.setPower(newPower)
            }
        }
    }
}

/** إعدادات جلسة المسح — تُظهر ملخصاً (UHFMainActivity:691, 696) */
fun showInventorySessionSettings() {
    try {
        val appCtx = getSafeAppContext() ?: return
        val s = MiraReaderSettings.getInstance(appCtx)
        val rfLinkLabel = MiraReaderSettings.RFLINK_LABELS.getOrNull(s.rfLink) ?: "?"
        val summary = if (isArabic) {
            buildString {
                appendLine("📊 إعدادات جلسة المسح الحالية:")
                appendLine("• Session: S${s.session}   |   Target: ${if (s.target == 0) "A" else "B"}")
                appendLine("• Q: ${s.qValue} (${if (s.qMode == 0) "ثابت" else "ديناميكي"})")
                appendLine("• Miller: ${s.miller}   |   LinkFreq: ${s.linkFreq}")
                appendLine("• RFLink: $rfLinkLabel")
                append("• القدرة: ${s.inventoryPowerDbm} dBm")
            }
        } else {
            buildString {
                appendLine("📊 Session Settings:")
                appendLine("• Session: S${s.session}   |   Target: ${if (s.target == 0) "A" else "B"}")
                appendLine("• Q: ${s.qValue} (${if (s.qMode == 0) "fixed" else "dynamic"})")
                appendLine("• Miller: ${s.miller}   |   LinkFreq: ${s.linkFreq}")
                appendLine("• RFLink: $rfLinkLabel")
                append("• Power: ${s.inventoryPowerDbm} dBm")
            }
        }
        showToast(summary)
    } catch (e: Exception) {
        Log.e(TAG, "showInventorySessionSettings error", e)
        showToast(if (isArabic) "⚠️ تعذر عرض إعدادات الجلسة" else "⚠️ Failed to show session settings")
    }
}
}
