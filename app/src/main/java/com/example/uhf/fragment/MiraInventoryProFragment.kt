package com.example.uhf.fragment

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.uhf.BuildConfig
import com.example.uhf.MiraRfid.MiraIRFIDReader
import com.example.uhf.MiraRfid.MiraReaderSettings
import com.example.uhf.MiraRfid.MiraScanSessionController
import com.example.uhf.MiraRfid.RFIDManager
import com.example.uhf.R
import com.example.uhf.activity.InventoryReportPreviewActivity
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.adapter.ModernScannedItemsAdapter
import com.example.uhf.api.MiraApiClient
import com.example.uhf.dialog.inventory.InventoryDialogsManager
import com.example.uhf.engine.MiraInventoryEngine
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.manager.PermissionManager
import com.example.uhf.manager.inventory.InventoryCatalogManager
import com.example.uhf.manager.inventory.InventoryFeedbackHelper
import com.example.uhf.manager.inventory.InventoryFilterManager
import com.example.uhf.manager.inventory.InventoryPersistenceManager
import com.example.uhf.manager.inventory.InventoryPrintManager
import com.example.uhf.manager.inventory.InventoryScanController
import com.example.uhf.manager.inventory.InventorySessionManager
import com.example.uhf.manager.inventory.InventorySnapshotManager
import com.example.uhf.model.InventoryItem
import com.example.uhf.model.InventoryReport
import com.example.uhf.model.InventorySnapshot
import com.example.uhf.model.ScannedItemDisplay
import com.example.uhf.navigation.NavigationManager
import com.example.uhf.tools.SessionManager
import com.example.uhf.utils.InventoryAnimationHelper
import com.example.uhf.utils.MiraExecutorService
import com.example.uhf.utils.SoundManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MiraInventoryProFragment - الإصدار 4.5 (Kotlin Production Release)
 *
 * التحسينات والإصلاحات المعتمدة:
 *  1. استعادة قالب الطباعة الاحترافي A4 بالكامل (RTL/LTR) مع تفاصيل القطع والتواقيع.
 *  2. معالجة القراءات المكررة (ACCEPT_DUPLICATE) لتحديث عداد القراءات (×N) وقوة الإشارة (RSSI) لحظياً.
 *  3. مزامنة كاملة بين القائمة المعروضة و MiraInventoryEngine و MiraScanSessionController عند الاستئناف والفلترة.
 *  4. استمرارية عداد الوقت الفعلي للجلسة عند الإيقاف المؤقت ثم الاستئناف.
 *  5. حماية زر الزناد في وضع الضغط المستمر (TRIGGER_MODE_HOLD) من الإفلات السريع أثناء تهيئة القارئ.
 *  6. توحيد مسار استعادة الجلسات لمنع تداخل النوافذ المنبثقة، وربط زر الفلترة الحية (btnLiveFilter).
 */
class MiraInventoryProFragment : KeyDwonFragment(),
    InventoryScanController.ScanCallback,
    InventoryDialogsManager.DialogsCallback,
    MiraScanSessionController.Host {

    companion object {
        private const val TAG = "MiraInventoryPro"

        private const val UI_UPDATE_THROTTLE_MIN_MS = 100L
        private const val UI_UPDATE_THROTTLE_MEDIUM_MS = 200L
        private const val UI_UPDATE_THROTTLE_MAX_MS = 350L

        private const val ITEMS_COUNT_SMALL = 200
        private const val ITEMS_COUNT_MEDIUM = 800
        private const val DEBUG_MAX_LINES = 12

        private const val KEYCODE_RFID_TRIGGER_1 = 139
        private const val KEYCODE_RFID_TRIGGER_2 = 280
        private const val KEYCODE_RFID_TRIGGER_3 = 293

        private const val ITEMS_LIST_PAGE_SIZE = 100

        private const val PREFS_UI = "mira_inventory_ui_prefs"
        private const val KEY_SKIP_RESTORE_PROMPT = "skip_restore_prompt"
        private const val MIN_SESSION_AGE_TO_PROMPT_MS = 2 * 60 * 1000L
        private const val MIN_ITEMS_TO_PROMPT = 3

        @JvmStatic
        fun newInstance(): MiraInventoryProFragment = MiraInventoryProFragment()

        @JvmStatic
        fun newInstance(mode: String?): MiraInventoryProFragment {
            return MiraInventoryProFragment().apply {
                arguments = Bundle().apply {
                    putString("INVENTORY_MODE", mode)
                }
            }
        }
    }

    // ==================== Debug Overlay ====================
    private var debugOverlay: TextView? = null
    private val debugLog = StringBuilder()

    private fun addDebugLine(line: String) {
        if (!BuildConfig.DEBUG) return
        if (!isAdded || activity == null) return

        activity?.runOnUiThread {
            try {
                val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                debugLog.insert(0, "$time  $line\n")

                val lines = debugLog.toString().split("\n")
                if (lines.size > DEBUG_MAX_LINES) {
                    debugLog.setLength(0)
                    for (i in 0 until DEBUG_MAX_LINES) {
                        debugLog.append(lines[i]).append("\n")
                    }
                }
                debugOverlay?.text = debugLog.toString()
            } catch (_: Exception) {
            }
        }
    }

    // ==================== Layout Refs ====================
    private var welcomeState: View? = null
    private var activeContentGroup: View? = null
    private var progressCard: View? = null
    private var metricsRow: View? = null
    private var fancyDivider: View? = null
    private var iconActionsRow: View? = null
    private var nestedScroll: View? = null
    private var btnInventoryClose: ImageButton? = null

    // ==================== Views ====================
    private var tvProgressPercent: TextView? = null
    private var progressScanned: ProgressBar? = null
    private var tvScannedCount: TextView? = null
    private var tvExpectedCount: TextView? = null

    private var statusDot: View? = null
    private var statusBar: View? = null
    private var tvInventoryStatus: TextView? = null
    private var tvElapsedTime: TextView? = null
    private var tvRssi: TextView? = null

    private var segmentedModes: MaterialButtonToggleGroup? = null
    private var btnModeFull: MaterialButton? = null
    private var btnModeCategory: MaterialButton? = null
    private var btnModeBlind: MaterialButton? = null
    private var btnViewSnapshots: MaterialButton? = null

    private var tvVariance: TextView? = null
    private var tvAccuracy: TextView? = null
    private var tvItemCount: TextView? = null

    private var tvTotalWeight: TextView? = null
    private var tvTotalValue: TextView? = null

    private var btnPowerControl: MaterialButton? = null
    private var btnGenerateReport: MaterialButton? = null
    private var btnArchiveReports: MaterialButton? = null

    private var etSearchItems: EditText? = null
    private var btnClearSearch: ImageView? = null
    private var btnLiveFilter: ImageView? = null

    private var rvInventoryItems: RecyclerView? = null
    private var modernAdapter: ModernScannedItemsAdapter? = null
    private var tvEmptyState: View? = null

    private var btnMainToggleScan: MaterialButton? = null
    private var btnCommitSession: MaterialButton? = null
    private var btnFinishAndReview: MaterialButton? = null
    private var btnTakeSnapshot: MaterialButton? = null

    private var lastScanCard: View? = null
    private var tvLastScanTitle: TextView? = null
    private var tvLastScanEpc: TextView? = null
    private var tvLastScanKarat: TextView? = null
    private var tvLastScanWeight: TextView? = null
    private var tvLastScanRssi: TextView? = null
    private var tvLastScanStatus: TextView? = null

    private var tvLastScanPrice: TextView? = null
    private var tvLastScanReadCount: TextView? = null
    private var tvLastScanSerial: TextView? = null
    private var tvLastScanTime: TextView? = null
    private var chipReadCount: View? = null
    private var rowSerialTime: View? = null

    private var ivWelcomeLogo: ImageView? = null

    private var btnOpenItemsList: MaterialButton? = null
    private var btnPowerControlCompact: MaterialButton? = null
    private var btnArchiveReportsCompact: MaterialButton? = null

    // ==================== Managers ====================
    private var feedbackHelper: InventoryFeedbackHelper? = null
    private var animationHelper: InventoryAnimationHelper? = null
    private var sessionManager: InventorySessionManager? = null
    private var catalogManager: InventoryCatalogManager? = null
    private var filterManager: InventoryFilterManager? = null
    private var dialogsManager: InventoryDialogsManager? = null
    private var persistenceManager: InventoryPersistenceManager? = null
    private var printManager: InventoryPrintManager? = null
    private var scanController: InventoryScanController? = null
    private var scanSession: MiraScanSessionController? = null
    private var snapshotManager: InventorySnapshotManager? = null

    // ==================== Core ====================
    private var inventoryEngine: MiraInventoryEngine? = null
    private var apiClient: MiraApiClient? = null
    private var branchesManager: MiraBranchesManager? = null
    private var permissionManager: PermissionManager? = null
    private var soundManager: SoundManager? = null

    // ==================== Executors & Handlers ====================
    private var miraExecutor: MiraExecutorService? = null
    private var backgroundExecutor: ExecutorService? = null
    private var ioExecutor: ExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ==================== State ====================
    @Volatile private var isScanning = false
    @Volatile private var isFragmentDestroyed = false
    @Volatile private var isArabic = true
    @Volatile private var expectedCount = 0
    @Volatile private var currentPowerDbm = 27
    @Volatile private var shouldResumeOnNextStart = false
    @Volatile private var skipCatalogLoad = false
    @Volatile private var selectedCategoryFilter: String? = null
    @Volatile private var currentLiveFilter = 0
    @Volatile private var lastScannedForAnimation = 0
    @Volatile private var hasCelebrated = false
    @Volatile private var pendingUiUpdate = false
    @Volatile private var aggregateTotalWeight = 0.0
    @Volatile private var aggregateTotalValue = 0.0
    @Volatile private var hasTriedRestoringSession = false
    @Volatile private var hasRestoredThisSession = false
    @Volatile private var isStartingScan = false
    @Volatile private var triggerReleasedWhileStarting = false
    @Volatile private var isPaused = false
    @Volatile private var nextScanIsResume = false
    @Volatile private var hasUncommittedSession = false
    @Volatile private var sessionElapsedBeforePause = 0L

    private var lastServerReport: JSONObject? = null
    private var autoSnapshotEnabled = true
    private var scanStartTime = 0L
    private val uiUpdateDelay = 200L

    // ==================== Collections ====================
    private val scannedItemsDisplayList = CopyOnWriteArrayList<ScannedItemDisplay>()
    private val catalogEpcs: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    // تتبع محلي لحالات القطع والتلف (لحين ربط مسارات API لاحقاً)
    private val itemStatusOverrides = ConcurrentHashMap<String, String>()
    private val itemDamageNotes = ConcurrentHashMap<String, String>()

    // ==================== Back Press ====================
    private var backPressedCallback: OnBackPressedCallback? = null

    fun isScanning(): Boolean = isScanning

    // ==================== Runnables ====================
    private val uiUpdateRunnable = Runnable {
        pendingUiUpdate = false
        updateUI()
        updateTotalWeightAndValue()
        updateAdapterList()
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isScanning || isFragmentDestroyed) return
            updateElapsedTime()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    // ==================== Lifecycle: onCreateView ====================

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view: View = try {
            inflater.inflate(R.layout.fragment_mira_inventory_pro, container, false)
        } catch (e: Exception) {
            Log.e(TAG, "Inflate error", e)
            return TextView(requireContext()).apply {
                text = "✅ الجرد يعمل (Fallback)\n\n${e.message}"
                textSize = 16f
                gravity = Gravity.CENTER
            }
        }

        isFragmentDestroyed = false

        // 1) Executors
        try {
            miraExecutor = MiraExecutorService.getInstance()
            val sharedPool = if (miraExecutor != null && miraExecutor!!.isHealthy) {
                miraExecutor!!.backgroundExecutor
            } else {
                Executors.newSingleThreadExecutor()
            }
            backgroundExecutor = sharedPool
            ioExecutor = sharedPool
        } catch (e: Exception) {
            Log.e(TAG, "Executor init error", e)
            val fallbackPool = Executors.newSingleThreadExecutor()
            backgroundExecutor = fallbackPool
            ioExecutor = fallbackPool
        }

        // 2) Locale + RTL
        try {
            isArabic = Locale.getDefault().language == "ar"
            view.layoutDirection = if (isArabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        } catch (e: Exception) {
            Log.e(TAG, "Locale error", e)
        }

        // 3) Sound & Session Controllers
        try {
            soundManager = SoundManager(requireContext())
        } catch (e: Exception) {
            Log.e(TAG, "SoundManager error", e)
        }

        scanSession = MiraScanSessionController(this)

        // 4) Helpers & Managers
        try {
            feedbackHelper = InventoryFeedbackHelper(requireContext())
        } catch (e: Exception) {
            Log.e(TAG, "FeedbackHelper error", e)
        }

        try {
            animationHelper = InventoryAnimationHelper(isArabic)
        } catch (e: Exception) {
            Log.e(TAG, "AnimationHelper error", e)
        }

        try {
            sessionManager = InventorySessionManager(requireContext(), isArabic)
        } catch (e: Exception) {
            Log.e(TAG, "SessionManager error", e)
        }

        try {
            catalogManager = InventoryCatalogManager(requireContext(), isArabic)
        } catch (e: Exception) {
            Log.e(TAG, "CatalogManager error", e)
        }

        try {
            filterManager = InventoryFilterManager(isArabic)
        } catch (e: Exception) {
            Log.e(TAG, "FilterManager error", e)
        }

        try {
            dialogsManager = InventoryDialogsManager(requireContext(), isArabic).apply {
                setSoundManager(soundManager)
                setCurrentPowerDbm(currentPowerDbm)
                setCallback(this@MiraInventoryProFragment)
            }
        } catch (e: Exception) {
            Log.e(TAG, "DialogsManager error", e)
        }

        // مزامنة القدرة من MiraReaderSettings
        try {
            val settings = MiraReaderSettings.getInstance(requireContext())
            currentPowerDbm = settings.powerDbm
            Log.d(TAG, "✅ Power synced from MiraReaderSettings: $currentPowerDbm dBm")
        } catch (e: Exception) {
            Log.w(TAG, "Sync power error: ${e.message}")
        }

        try {
            persistenceManager = InventoryPersistenceManager(requireContext(), isArabic, ioExecutor)
        } catch (e: Exception) {
            Log.e(TAG, "PersistenceManager error", e)
        }

        try {
            printManager = InventoryPrintManager(requireContext(), isArabic)
            setupPrintManagerCallback()
        } catch (e: Exception) {
            Log.e(TAG, "PrintManager error", e)
        }

        try {
            snapshotManager = InventorySnapshotManager(requireContext(), ioExecutor)
        } catch (e: Exception) {
            Log.e(TAG, "SnapshotManager error", e)
        }

        // 5) Core: Engine + API + Branches + Permissions
        try {
            val sessionId = "SESS-" + UUID.randomUUID().toString().substring(0, 8).uppercase(Locale.US)
            inventoryEngine = MiraInventoryEngine(requireContext(), sessionId, getDeviceId())
        } catch (e: Exception) {
            Log.e(TAG, "Engine error", e)
        }

        apiClient = try {
            MiraApiClient.getInstance(requireContext())
        } catch (_: Exception) {
            null
        }

        branchesManager = try {
            MiraBranchesManager.getInstance(requireContext())
        } catch (_: Exception) {
            null
        }

        permissionManager = try {
            PermissionManager.getInstance(requireContext())
        } catch (_: Exception) {
            null
        }

        // 6) ScanController
        try {
            scanController = InventoryScanController(
                requireContext(), isArabic, backgroundExecutor, ioExecutor
            ).apply {
                setCallback(this@MiraInventoryProFragment)
                setSoundManager(soundManager)
                setCurrentPowerDbm(currentPowerDbm)
                inventoryEngine?.let { setInventoryEngine(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ScanController error", e)
        }

        // 7) تهيئة RFIDManager مبكراً قبل initViews
        try {
            val rfidManager = RFIDManager.getInstance(requireContext())
            if (rfidManager != null) {
                scanController?.attachRfidManager(rfidManager)
                Log.d(TAG, "✅ RFID attached EARLY: state=${rfidManager.state}")

                rfidManager.ensureReady(object : RFIDManager.ReadyCallback {
                    override fun onReady(reader: MiraIRFIDReader?) {
                        mainHandler.post {
                            if (isAdded && !isFragmentDestroyed) {
                                tvInventoryStatus?.text = if (isArabic) "📡 القارئ جاهز" else "📡 Reader ready"
                            }
                        }
                    }

                    override fun onError(reason: String?) {
                        mainHandler.post {
                            if (isAdded && !isFragmentDestroyed) {
                                tvInventoryStatus?.text = if (isArabic) "📡 القارئ غير متصل" else "📡 Reader not connected"
                            }
                        }
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Early RFID init error", e)
        }

        if (BuildConfig.DEBUG) {
            try {
                createDebugOverlay()
            } catch (e: Exception) {
                Log.e(TAG, "createDebugOverlay error", e)
            }
        }

        // 8) Views & Setup
        try {
            initViews(view)
        } catch (e: Exception) {
            Log.e(TAG, "initViews error", e)
        }

        mainHandler.postDelayed({ animateWelcomeLogo() }, 300L)

        try {
            setupBackPressHandler()
        } catch (e: Exception) {
            Log.e(TAG, "BackPress error", e)
        }

        try {
            applyPermissions()
            applyLanguage()
        } catch (e: Exception) {
            Log.e(TAG, "Permissions/Language error", e)
        }

        mainHandler.postDelayed({
            if (isAdded && !isFragmentDestroyed) {
                updateButtonsState()
            }
        }, 150L)

        // 9) استرجاع صامت فوري للجلسة السابقة
        mainHandler.postDelayed({ silentRestoreSession() }, 100L)

        // 10) تحديد الحالة الأولية (Welcome / Active)
        mainHandler.postDelayed({
            if (isAdded && !isFragmentDestroyed) {
                if (scannedItemsDisplayList.isNotEmpty()) {
                    switchToActiveState(false)
                } else {
                    switchToWelcomeState()
                }
            }
        }, 200L)

        return view
    }

    // ==================== Debug Overlay ====================

    private fun createDebugOverlay() {
        val act = activity ?: return
        (debugOverlay?.parent as? ViewGroup)?.removeView(debugOverlay)

        debugOverlay = TextView(act).apply {
            textSize = 9f
            setTextColor(0xFF00FF00.toInt())
            setBackgroundColor(0xCC000000.toInt())
            setPadding(12, 12, 12, 12)
            typeface = Typeface.MONOSPACE
            text = "🔍 Debug Ready\n"
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
            }
            setOnClickListener {
                visibility = if (visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

        val rootView = act.window.decorView as ViewGroup
        rootView.addView(debugOverlay)
        addDebugLine("🎬 Overlay ready")
    }

    // ==================== initViews ====================

    private fun initViews(view: View) {
        btnInventoryClose = view.findViewById(R.id.btnInventoryClose)

        tvProgressPercent = view.findViewById(R.id.tvProgressPercent)
        progressScanned = view.findViewById(R.id.progressScanned)
        tvScannedCount = view.findViewById(R.id.tvScannedCount)
        tvExpectedCount = view.findViewById(R.id.tvExpectedCount)

        btnTakeSnapshot = view.findViewById(R.id.btnTakeSnapshot)
        btnViewSnapshots = view.findViewById(R.id.btnViewSnapshots)

        ivWelcomeLogo = view.findViewById(R.id.ivWelcomeLogo)
        welcomeState = view.findViewById(R.id.welcomeState)
        activeContentGroup = view.findViewById(R.id.activeContentGroup)
        progressCard = view.findViewById(R.id.progressCard)
        metricsRow = view.findViewById(R.id.metricsRow)
        fancyDivider = view.findViewById(R.id.fancyDivider)
        iconActionsRow = view.findViewById(R.id.iconActionsRow)
        nestedScroll = view.findViewById(R.id.nestedScroll)

        statusDot = view.findViewById(R.id.statusDot)
        statusBar = view.findViewById(R.id.statusBar)
        tvInventoryStatus = view.findViewById(R.id.tvInventoryStatus)
        tvElapsedTime = view.findViewById(R.id.tvElapsedTime)
        tvRssi = view.findViewById(R.id.tvRssi)

        segmentedModes = view.findViewById(R.id.segmentedModes)
        btnModeFull = view.findViewById(R.id.btnModeFull)
        btnModeCategory = view.findViewById(R.id.btnModeCategory)
        btnModeBlind = view.findViewById(R.id.btnModeBlind)

        tvVariance = view.findViewById(R.id.tvVariance)
        tvAccuracy = view.findViewById(R.id.tvAccuracy)
        tvItemCount = view.findViewById(R.id.tvItemCount)

        tvTotalWeight = view.findViewById(R.id.tvTotalWeight)
        tvTotalValue = view.findViewById(R.id.tvTotalValue)

        view.findViewById<MaterialButton>(R.id.btnClearSession)?.setOnClickListener {
            confirmResetSession()
        }

        btnPowerControl = view.findViewById(R.id.btnPowerControl)
        btnGenerateReport = view.findViewById(R.id.btnGenerateReport)
        btnArchiveReports = view.findViewById(R.id.btnArchiveReports)

        etSearchItems = view.findViewById(R.id.etSearchItems)
        btnClearSearch = view.findViewById(R.id.btnClearSearch)
        btnLiveFilter = view.findViewById(R.id.btnLiveFilter)

        rvInventoryItems = view.findViewById(R.id.rvInventoryItems)
        tvEmptyState = view.findViewById(R.id.tvEmptyState)

        btnMainToggleScan = view.findViewById(R.id.btnMainToggleScan)
        btnCommitSession = view.findViewById(R.id.btnCommitSession)
        btnFinishAndReview = view.findViewById(R.id.btnFinishAndReview)

        val flashOverlayView = view.findViewById<View>(R.id.flashOverlay)
        feedbackHelper?.setFlashOverlay(flashOverlayView)

        lastScanCard = view.findViewById(R.id.lastScanCard)
        tvLastScanTitle = view.findViewById(R.id.tvLastScanTitle)
        tvLastScanEpc = view.findViewById(R.id.tvLastScanEpc)
        tvLastScanKarat = view.findViewById(R.id.tvLastScanKarat)
        tvLastScanWeight = view.findViewById(R.id.tvLastScanWeight)
        tvLastScanRssi = view.findViewById(R.id.tvLastScanRssi)
        tvLastScanStatus = view.findViewById(R.id.tvLastScanStatus)

        btnOpenItemsList = view.findViewById(R.id.btnOpenItemsList)
        btnPowerControlCompact = view.findViewById(R.id.btnPowerControlCompact)
        btnArchiveReportsCompact = view.findViewById(R.id.btnArchiveReportsCompact)

        tvLastScanPrice = view.findViewById(R.id.tvLastScanPrice)
        tvLastScanReadCount = view.findViewById(R.id.tvLastScanReadCount)
        tvLastScanSerial = view.findViewById(R.id.tvLastScanSerial)
        tvLastScanTime = view.findViewById(R.id.tvLastScanTime)
        chipReadCount = view.findViewById(R.id.chipReadCount)
        rowSerialTime = view.findViewById(R.id.rowSerialTime)

        setupRecyclerView()
        setupButtons()
        setupSearchBar()
        setupSegmentedControl()
    }

    private fun setupRecyclerView() {
        try {
            val rv = rvInventoryItems ?: return
            rv.layoutManager = LinearLayoutManager(requireContext())
            rv.isNestedScrollingEnabled = false

            modernAdapter = ModernScannedItemsAdapter(isArabic) { item ->
                dialogsManager?.showItemOptionsDialog(item)
            }

            rv.adapter = modernAdapter
            rv.setHasFixedSize(false)
            rv.itemAnimator = null
        } catch (e: Exception) {
            Log.e(TAG, "setupRecyclerView error", e)
        }
    }

    private fun setupButtons() {
        btnMainToggleScan?.setOnClickListener {
            if (!canEditInventory()) {
                showAccessDenied()
                return@setOnClickListener
            }

            if (isScanning) {
                pauseScanning()
                return@setOnClickListener
            }

            if (isPaused && hasUncommittedSession && scannedItemsDisplayList.isNotEmpty()) {
                showResumeOrNewDialog()
                return@setOnClickListener
            }

            startScanning()
        }

        btnFinishAndReview?.setOnClickListener {
            if (isScanning) {
                pauseScanning()
                mainHandler.postDelayed({ showFinishAndReviewDialog() }, 300L)
            } else {
                showFinishAndReviewDialog()
            }
        }

        btnCommitSession?.setOnClickListener { showCommitConfirmationDialog() }
        btnPowerControl?.setOnClickListener { showPowerControlSheet() }
        btnGenerateReport?.setOnClickListener { printCurrentReport() }
        btnTakeSnapshot?.setOnClickListener { takeSnapshot() }
        btnViewSnapshots?.setOnClickListener { openSnapshotsScreen() }
        btnArchiveReports?.setOnClickListener { showArchiveReports() }
        btnOpenItemsList?.setOnClickListener { showItemsListDialog() }
        btnPowerControlCompact?.setOnClickListener { showPowerControlSheet() }
        btnArchiveReportsCompact?.setOnClickListener { showArchiveReports() }

        // ربط زر الفلتر الحي بجانب شريط البحث
        btnLiveFilter?.setOnClickListener { showLiveFilterChips() }

        btnInventoryClose?.setOnClickListener {
            try {
                val act = activity
                val edit = etSearchItems
                if (act != null && edit != null) {
                    val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(edit.windowToken, 0)
                }
            } catch (_: Exception) {
            }
            handleCustomBack()
        }
    }

    // ==================== Finish & Review ====================

    private fun showFinishAndReviewDialog() {
        if (!isAdded || isFragmentDestroyed) return

        try {
            val scanned = scannedItemsDisplayList.size
            if (scanned == 0) {
                showToast(if (isArabic) "⚠️ لا توجد بيانات لمراجعتها" else "⚠️ No data to review")
                return
            }

            var registered = 0
            var unregistered = 0
            var totalReads = 0

            synchronized(scannedItemsDisplayList) {
                for (item in scannedItemsDisplayList) {
                    if (item == null) continue
                    totalReads += item.count
                    if (catalogEpcs.contains(item.epc)) {
                        registered++
                    } else {
                        unregistered++
                    }
                }
            }

            val missing = maxOf(0, expectedCount - registered)
            val accuracy = if (expectedCount > 0) {
                minOf(100, (registered * 100) / expectedCount)
            } else {
                0
            }

            val elapsed = formatElapsed(getTotalElapsedSeconds())

            val sb = StringBuilder().apply {
                append(if (isArabic) "📋 ملخص الجلسة قبل الاعتماد:\n\n" else "📋 Session summary before commit:\n\n")
                append(if (isArabic) "✅ مسجّل وممسوح: " else "✅ Registered & scanned: ").append(registered).append("\n")
                append(if (isArabic) "⚠️ غير مسجّل: " else "⚠️ Unregistered: ").append(unregistered).append("\n")

                if (expectedCount > 0) {
                    append(if (isArabic) "❌ مفقود: " else "❌ Missing: ").append(missing).append("\n")
                    append(if (isArabic) "📈 الدقة: " else "📈 Accuracy: ").append(accuracy).append("%\n")
                }

                append(if (isArabic) "🔁 إجمالي القراءات: " else "🔁 Total reads: ").append(totalReads).append("\n")
                append(if (isArabic) "⏱️ المدة: " else "⏱️ Duration: ").append(elapsed).append("\n")
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (isArabic) "🏁 إنهاء الجرد ومراجعته" else "🏁 Finish & Review")
                .setMessage(sb.toString())
                .setPositiveButton(if (isArabic) "✅ اعتماد نهائي" else "✅ Commit") { d, _ ->
                    d.dismiss()
                    showCommitConfirmationDialog()
                }
                .setNeutralButton(if (isArabic) "🔍 عرض القطع" else "🔍 Show Items") { d, _ ->
                    d.dismiss()
                    showItemsListDialog()
                }
                .setNegativeButton(if (isArabic) "↩️ العودة للشاشة" else "↩️ Back to Screen") { d, _ ->
                    d.dismiss()
                    switchToActiveState(false)
                    updateButtonsState()
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "showFinishAndReviewDialog error", e)
        }
    }

    private fun getTotalElapsedSeconds(): Long {
        val current = if (isScanning && scanStartTime > 0L) {
            (SystemClock.elapsedRealtime() - scanStartTime) / 1000L
        } else {
            0L
        }
        return sessionElapsedBeforePause + current
    }

    private fun formatElapsed(seconds: Long): String {
        val h = seconds / 3600L
        val m = (seconds % 3600L) / 60L
        val s = seconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    // ==================== Search & Segmented Control ====================

    private fun setupSearchBar() {
        val searchBox = etSearchItems ?: return

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty()
                filterItems(query)
                btnClearSearch?.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        btnClearSearch?.setOnClickListener {
            etSearchItems?.setText("")
        }
    }

    private fun setupSegmentedControl() {
        val group = segmentedModes ?: return

        val activeBg = ContextCompat.getColor(requireContext(), R.color.gold_500)
        val inactiveBg = ContextCompat.getColor(requireContext(), R.color.pure_white)
        val activeText = ContextCompat.getColor(requireContext(), R.color.pure_white)
        val inactiveText = ContextCompat.getColor(requireContext(), R.color.text_gold_accent)

        val updateStyles = Runnable {
            val checkedId = group.checkedButtonId
            val buttons = arrayOf(btnModeFull, btnModeCategory, btnModeBlind)

            for (btn in buttons) {
                if (btn == null) continue
                val isActive = btn.id == checkedId
                btn.backgroundTintList = ColorStateList.valueOf(if (isActive) activeBg else inactiveBg)
                btn.setTextColor(if (isActive) activeText else inactiveText)
                btn.iconTint = ColorStateList.valueOf(if (isActive) activeText else inactiveText)
            }
        }

        mainHandler.post(updateStyles)

        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            updateStyles.run()

            when (checkedId) {
                R.id.btnModeFull -> {
                    selectedCategoryFilter = null
                    Log.d(TAG, "Mode: Full")
                    if (!isScanning && scannedItemsDisplayList.isEmpty()) {
                        showToast(if (isArabic) "🎯 جاهز للجرد الكامل" else "🎯 Ready for Full Inventory")
                    }
                }

                R.id.btnModeCategory -> {
                    if (isScanning) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(if (isArabic) "⚠️ الجرد قيد التشغيل" else "⚠️ Inventory Running")
                            .setMessage(
                                if (isArabic) "لتغيير الفئة، يجب إيقاف الجرد الحالي.\nهل تريد المتابعة؟"
                                else "To change category, stop the current inventory.\nContinue?"
                            )
                            .setPositiveButton(if (isArabic) "إيقاف ومتابعة" else "Stop & Continue") { _, _ ->
                                stopScanning()
                                mainHandler.postDelayed({ showCategorySelectionDialog() }, 500L)
                            }
                            .setNegativeButton(if (isArabic) "إلغاء" else "Cancel") { _, _ ->
                                btnModeFull?.let { segmentedModes?.check(R.id.btnModeFull) }
                            }
                            .show()
                    } else {
                        showCategorySelectionDialog()
                    }
                }

                R.id.btnModeBlind -> {
                    selectedCategoryFilter = null
                    if (!isScanning) {
                        startBlindInventory()
                    } else {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(if (isArabic) "⚠️ الجرد قيد التشغيل" else "⚠️ Inventory Running")
                            .setMessage(
                                if (isArabic) "هل تريد إيقاف الجرد الحالي وبدء الجرد الأعمى؟"
                                else "Stop current inventory and start blind inventory?"
                            )
                            .setPositiveButton(if (isArabic) "متابعة" else "Continue") { _, _ ->
                                stopScanning()
                                mainHandler.postDelayed({ startBlindInventory() }, 500L)
                            }
                            .setNegativeButton(if (isArabic) "إلغاء" else "Cancel") { _, _ ->
                                btnModeFull?.let { segmentedModes?.check(R.id.btnModeFull) }
                            }
                            .show()
                    }
                }
            }
        }
    }

    // ==================== State Switching ====================

    private fun switchToActiveState(animate: Boolean) {
        if (!isAdded || isFragmentDestroyed) return

        welcomeState?.visibility = View.GONE
        val activeGroup = activeContentGroup ?: return

        if (!animate) {
            activeGroup.visibility = View.VISIBLE
            return
        }

        activeGroup.visibility = View.VISIBLE

        val cards = arrayOf(
            lastScanCard,
            progressCard,
            metricsRow,
            fancyDivider,
            btnOpenItemsList,
            iconActionsRow
        )

        for (v in cards) {
            v?.apply {
                alpha = 0f
                translationY = 30f
            }
        }

        for (i in cards.indices) {
            cards[i]?.animate()
                ?.alpha(1f)
                ?.translationY(0f)
                ?.setStartDelay(i * 80L)
                ?.setDuration(350L)
                ?.start()
        }
    }

    private fun switchToWelcomeState() {
        if (!isAdded || isFragmentDestroyed) return
        activeContentGroup?.visibility = View.GONE
        welcomeState?.visibility = View.VISIBLE
    }

    private fun animateCount(textView: TextView?, from: Int, to: Int) {
        if (textView == null || from == to) {
            textView?.text = to.toString()
            return
        }
        try {
            ValueAnimator.ofInt(from, to).apply {
                duration = 400L
                interpolator = DecelerateInterpolator()
                addUpdateListener { animation ->
                    textView.text = animation.animatedValue.toString()
                }
                start()
            }
        } catch (_: Exception) {
            textView.text = to.toString()
        }
    }

    // ==================== Print Manager Callback ====================

    private fun setupPrintManagerCallback() {
        printManager?.setCallback(object : InventoryPrintManager.PrintCallback {
            override fun getScannedItems(): List<ScannedItemDisplay> =
                ArrayList(scannedItemsDisplayList)

            override fun getSessionId(): String =
                inventoryEngine?.clientSessionId ?: "SESS-UNKNOWN"

            override fun getExpectedCount(): Int = expectedCount

            override fun getScannedCount(): Int = getEffectiveScannedCount()

            override fun getArchivedReports(): List<InventoryReport> = emptyList()
        })
    }

    // ==================== Back Press ====================

    private fun setupBackPressHandler() {
        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleCustomBack()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            backPressedCallback!!
        )
    }

    private fun handleCustomBack() {
        try {
            if (isScanning) {
                pauseScanning()
                mainHandler.postDelayed({ checkAndExitAfterPause() }, 300L)
                return
            }

            if (scannedItemsDisplayList.isNotEmpty()) {
                showExitConfirmation()
                return
            }

            navigateAwayFromInventory()
        } catch (e: Exception) {
            Log.e(TAG, "handleCustomBack error", e)
            navigateAwayFromInventory()
        }
    }

    private fun checkAndExitAfterPause() {
        if (!isAdded || isFragmentDestroyed) return
        if (scannedItemsDisplayList.isNotEmpty()) {
            showExitConfirmation()
        } else {
            navigateAwayFromInventory()
        }
    }

    private fun showExitConfirmation() {
        if (!isAdded || isFragmentDestroyed) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isArabic) "📦 مغادرة شاشة الجرد؟" else "📦 Leave Inventory?")
            .setMessage(
                if (isArabic)
                    "لديك ${scannedItemsDisplayList.size} قطعة في الجلسة الحالية.\n\nهل تريد المغادرة؟ (سيتم حفظ الجلسة تلقائياً)"
                else
                    "You have ${scannedItemsDisplayList.size} items in the session.\n\nLeave anyway? (Session will be saved)"
            )
            .setPositiveButton(if (isArabic) "مغادرة" else "Leave") { _, _ ->
                navigateAwayFromInventory()
            }
            .setNegativeButton(if (isArabic) "البقاء" else "Stay", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun navigateAwayFromInventory() {
        try {
            try {
                scanController?.stopScanning()
                Log.d(TAG, "⏹️ Controller stopped defensively")
            } catch (_: Exception) {
            }

            try {
                if (isScanning) {
                    Log.d(TAG, "⏹️ Stopping scan before leaving")
                    stopScanning()
                }
            } catch (_: Exception) {
            }

            isPaused = false
            hasUncommittedSession = false
            nextScanIsResume = false
            shouldResumeOnNextStart = false

            try {
                if (scannedItemsDisplayList.isNotEmpty() && sessionManager != null) {
                    sessionManager?.saveSession(
                        expectedCount,
                        scanStartTime,
                        selectedCategoryFilter,
                        scannedItemsDisplayList
                    )
                    Log.d(TAG, "💾 Session saved before leaving")
                }
            } catch (_: Exception) {
            }

            try {
                (activity as? UHFMainActivity)?.setNavigationLocked(false)
            } catch (_: Exception) {
            }

            val mainActivity = activity as? UHFMainActivity
            if (mainActivity != null) {
                val nav = mainActivity.navigationManager
                if (nav != null) {
                    nav.goToDashboard()
                    Log.d(TAG, "✅ Navigated to Dashboard via NavigationManager")
                } else {
                    mainActivity.loadFragmentDirectly(
                        MiraDashboardFragment(),
                        NavigationManager.TAG_DASHBOARD
                    )
                    Log.d(TAG, "✅ Navigated to Dashboard (fallback)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "navigateAwayFromInventory error", e)
        }
    }

    private fun showExitWarning(onConfirm: Runnable?) {
        if (!isAdded || isFragmentDestroyed) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isArabic) "⚠️ تحذير مهم" else "⚠️ Important Warning")
            .setMessage(
                if (isArabic)
                    "الجرد ما زال قيد التشغيل!\n\nإذا قمت بتغيير الواجهة، سيتوقف الجرد وستفقد البيانات غير المحفوظة.\n\nهل تريد إيقاف الجرد والمتابعة؟"
                else
                    "Inventory is still running!\n\nIf you change the screen, the inventory will stop and unsaved data will be lost.\n\nDo you want to stop and continue?"
            )
            .setPositiveButton(if (isArabic) "إيقاف والمتابعة" else "Stop and Continue") { _, _ ->
                onConfirm?.run()
            }
            .setNegativeButton(if (isArabic) "البقاء في الجرد" else "Stay in Inventory") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    // ==================== Permissions & Language ====================

    private fun canEditInventory(): Boolean {
        return try {
            val pm = permissionManager ?: return true
            pm.canEdit("inventory") || pm.isManager
        } catch (_: Exception) {
            false
        }
    }

    private fun canViewInventory(): Boolean {
        return try {
            val pm = permissionManager ?: return true
            pm.canView("inventory") || pm.isManager
        } catch (_: Exception) {
            false
        }
    }

    private fun applyPermissions() {
        if (permissionManager == null) return
        if (!canViewInventory()) {
            tvInventoryStatus?.text = if (isArabic) "⛔ غير مصرح لك بالوصول" else "⛔ Access denied"
        }
    }

    private fun applyLanguage() {
        btnMainToggleScan?.text = if (isScanning) {
            if (isArabic) "⏹️ إيقاف الجرد" else "⏹️ Stop Scan"
        } else {
            if (isArabic) "▶️ بدء جرد RFID" else "▶️ Start RFID Inventory"
        }

        btnGenerateReport?.text = if (isArabic) "📋 تقرير" else "📋 Report"
        btnArchiveReports?.text = if (isArabic) "📂 الأرشيف" else "📂 Archive"
        btnPowerControl?.text = "⚡ $currentPowerDbm dBm"

        btnCommitSession?.let { btn ->
            val currentText = btn.text.toString()
            if (!currentText.contains("✅") && !currentText.contains("Committed")) {
                btn.text = if (isArabic) "اعتماد" else "Commit"
            }
        }
    }

    private fun showAccessDenied() {
        if (!isAdded || isFragmentDestroyed) return
        showToast(if (isArabic) "⛔ غير مصرح" else "⛔ Access denied")
        scanSession?.notifyError()
    }

    // ==================== RFID Trigger (Dual Mode + Hold Race Protection) ====================

    override fun myOnKeyDwon(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) return false

        val isTrigger = keyCode == KEYCODE_RFID_TRIGGER_1 ||
                keyCode == KEYCODE_RFID_TRIGGER_2 ||
                keyCode == KEYCODE_RFID_TRIGGER_3

        if (!isTrigger) return false

        var triggerMode = MiraReaderSettings.TRIGGER_MODE_HOLD
        try {
            val settings = MiraReaderSettings.getInstance(requireContext())
            if (settings != null) triggerMode = settings.triggerMode
        } catch (_: Exception) {
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (!canEditInventory()) {
                showAccessDenied()
                return true
            }

            try {
                feedbackHelper?.hapticFeedback(
                    if (isScanning) SoundManager.FeedbackType.ERROR
                    else SoundManager.FeedbackType.KNOWN_ITEM
                )
            } catch (_: Exception) {
            }

            if (triggerMode == MiraReaderSettings.TRIGGER_MODE_TOGGLE) {
                if (isScanning) {
                    Log.i(TAG, "🎯 Trigger [TOGGLE]: pause")
                    pauseScanning()
                } else {
                    Log.i(TAG, "🎯 Trigger [TOGGLE]: start")
                    startScanning()
                }
            } else {
                triggerReleasedWhileStarting = false
                if (!isScanning) {
                    Log.i(TAG, "🎯 Trigger [HOLD]: start")
                    startScanning()
                }
            }
            return true
        }

        if (event.action == KeyEvent.ACTION_UP) {
            if (triggerMode == MiraReaderSettings.TRIGGER_MODE_HOLD) {
                if (isStartingScan) {
                    Log.i(TAG, "🎯 Trigger [HOLD]: released while starting — marking stop")
                    triggerReleasedWhileStarting = true
                    mainHandler.postDelayed({
                        if (isScanning && triggerReleasedWhileStarting) {
                            triggerReleasedWhileStarting = false
                            Log.i(TAG, "🎯 Trigger [HOLD]: pause (delayed fallback)")
                            pauseScanning()
                        }
                    }, 300L)
                    return true
                }

                if (isScanning) {
                    Log.i(TAG, "🎯 Trigger [HOLD]: pause")
                    pauseScanning()
                }
            }
            return true
        }

        return false
    }

    // ================================================================
    //  ScanController.ScanCallback Implementation
    // ================================================================

    override fun onScanStarted() {
        Log.d(TAG, "📡 onScanStarted")
        addDebugLine("✅ onScanStarted")
        mainHandler.post {
            if (!isAdded || isFragmentDestroyed) return@post

            isStartingScan = false

            scanSession?.onScanStarted(nextScanIsResume)
            nextScanIsResume = false

            isScanning = true

            // إذا أفلت المستخدم زر الزناد أثناء تهيئة القارئ في وضع Hold، أوقف المسح فوراً
            if (triggerReleasedWhileStarting) {
                triggerReleasedWhileStarting = false
                Log.i(TAG, "🎯 Trigger released during init — pausing immediately")
                pauseScanning()
                return@post
            }

            switchToActiveState(false)
            updateMainToggleButton(true)
            animateMainButton()

            statusBar?.let { bar ->
                animationHelper?.updateStatusBarBackground(bar, true, true)
            }

            tvInventoryStatus?.text = if (isArabic) "📡 جاري الجرد..." else "📡 Scanning..."
            tvElapsedTime?.text = formatElapsed(sessionElapsedBeforePause)

            try {
                feedbackHelper?.hapticFeedback(SoundManager.FeedbackType.KNOWN_ITEM)
            } catch (_: Exception) {
            }

            scanStartTime = SystemClock.elapsedRealtime()
            mainHandler.removeCallbacks(timerRunnable)
            mainHandler.post(timerRunnable)

            (activity as? UHFMainActivity)?.setNavigationLocked(true)
            updateButtonsState()
        }
    }

    override fun onScanStopped() {
        Log.d(TAG, "📡 onScanStopped")
        addDebugLine("⏹️ onScanStopped")
        mainHandler.post {
            if (!isAdded || isFragmentDestroyed) return@post

            scanSession?.onScanStopped()

            isScanning = false
            updateMainToggleButton(false)
            animateMainButton()

            statusBar?.let { bar ->
                val isConnected = try {
                    RFIDManager.getInstance(requireContext())?.isReady == true
                } catch (_: Exception) {
                    false
                }
                animationHelper?.updateStatusBarBackground(bar, isConnected, false)
            }

            feedbackHelper?.resetDebounce()

            tvInventoryStatus?.text = if (isArabic) "📡 تم إيقاف الجرد" else "📡 Scan stopped"

            (activity as? UHFMainActivity)?.setNavigationLocked(false)
            updateUI()
            syncOfflineTransactions()
            updateButtonsState()
        }
    }

    override fun onTagRead(normalizedEpc: String?, rssi: Int) {
        addDebugLine("📡 $normalizedEpc / $rssi")
        if (normalizedEpc.isNullOrEmpty()) return
        processValidEpc(normalizedEpc, rssi)
    }

    private fun processValidEpc(epc: String?, rssi: Int) {
        if (epc.isNullOrEmpty()) return

        // رفض الشرائح المشوّهة (fragments)
        if (epc.length < 16) {
            addDebugLine("⚠️ Reject short EPC: $epc")
            return
        }

        // فلتر الفئة
        if (!selectedCategoryFilter.isNullOrEmpty()) {
            if (!catalogEpcs.contains(epc)) return
        }

        val session = scanSession ?: return
        when (session.evaluateTag(epc)) {
            MiraScanSessionController.Decision.REJECT_SESSION_OFF -> return

            MiraScanSessionController.Decision.REJECT_LIMIT -> {
                session.confirmLimitReached()
                return
            }

            MiraScanSessionController.Decision.ACCEPT_DUPLICATE -> {
                session.confirmTagDuplicate()
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    handleDuplicateTagRead(epc, rssi)
                } else {
                    mainHandler.post { handleDuplicateTagRead(epc, rssi) }
                }
                return
            }

            MiraScanSessionController.Decision.ACCEPT_NEW -> {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    processTagReadDirect(epc, rssi)
                } else {
                    mainHandler.post { processTagReadDirect(epc, rssi) }
                }
                return
            }
        }
    }

    override fun onDuplicateTag(epc: String?) {
        // يُعالج عبر MiraScanSessionController.Decision.ACCEPT_DUPLICATE في processValidEpc
    }

    private fun handleDuplicateTagRead(epc: String, rssi: Int) {
        if (!isAdded || isFragmentDestroyed || modernAdapter == null) return

        var updatedItem: ScannedItemDisplay? = null
        synchronized(scannedItemsDisplayList) {
            for (display in scannedItemsDisplayList) {
                if (display != null && epc == display.epc) {
                    display.count++
                    display.rssi = rssi
                    display.time = getCurrentTimeString()
                    updatedItem = display
                    break
                }
            }
        }

        if (updatedItem != null) {
            addDebugLine("🔁 DUP: ${epc.takeLast(6)} (×${updatedItem!!.count})")
            updateLastScan(epc, rssi)
            updateLastScanCard(updatedItem, isDuplicate = true)
            scheduleUiUpdate()
        }
    }

    private fun processTagReadDirect(epc: String, rssi: Int) {
        if (!isAdded || isFragmentDestroyed || modernAdapter == null) return

        val catalogItem = catalogManager?.getItemByEpc(epc)

        addScannedItemToDisplay(epc, rssi, catalogItem)
        updateLastScan(epc, rssi)
        scheduleUiUpdate()

        scanSession?.let { session ->
            val isKnown = catalogEpcs.contains(epc)
            session.confirmTagAccepted(isKnown)
            session.scheduleSingleModeStop()
        }
    }

    override fun onError(error: String?) {
        Log.e(TAG, "📡 RFID Error: $error")
        addDebugLine("❌ onError: $error")

        mainHandler.post {
            if (!isAdded || isFragmentDestroyed) return@post

            isStartingScan = false
            triggerReleasedWhileStarting = false

            showToast("⚠️ ${error.orEmpty()}")

            try {
                scanController?.stopScanning()
            } catch (_: Exception) {
            }

            try {
                scanSession?.onScanStopped()
            } catch (_: Exception) {
            }

            isScanning = false
            isPaused = false
            hasUncommittedSession = false
            nextScanIsResume = false
            shouldResumeOnNextStart = false

            (activity as? UHFMainActivity)?.setNavigationLocked(false)

            updateMainToggleButton(false)
            updateButtonsState()
        }
    }

    override fun onConnectionChanged(isConnected: Boolean) {
        Log.i(TAG, "📡 RFID Connection: $isConnected")
        mainHandler.post {
            addDebugLine("🔌 Connection: $isConnected")
            if (!isAdded || isFragmentDestroyed) return@post

            tvInventoryStatus?.text = if (isConnected) {
                if (isArabic) "📡 متصل" else "📡 Connected"
            } else {
                if (isArabic) "📡 غير متصل" else "📡 Disconnected"
            }

            try {
                statusDot?.setBackgroundResource(
                    if (isConnected) R.drawable.bg_status_dot_green
                    else R.drawable.bg_status_dot_red
                )
            } catch (_: Exception) {
            }

            statusBar?.let { bar ->
                animationHelper?.updateStatusBarBackground(bar, isConnected, isScanning)
            }
        }
    }

    override fun onTagDebounced(epc: String?) {
        // لا حاجة
    }

    override fun onExpectedCountChanged(count: Int) {
        mainHandler.post {
            if (!isAdded || isFragmentDestroyed) return@post

            if (count == 0 && expectedCount > 0) {
                Log.d(TAG, "⏭️ Ignoring count=0 (catalog already loaded: $expectedCount)")
                return@post
            }

            if (count > 0 || expectedCount == 0) {
                expectedCount = count
                tvExpectedCount?.text = count.toString()
            }
        }
    }

    // ================================================================
    //  DialogsManager.DialogsCallback Implementation
    // ================================================================

    override fun onPowerChanged(newPowerDbm: Int) {
        currentPowerDbm = newPowerDbm
        scanController?.setCurrentPowerDbm(newPowerDbm)
        applyAntennaPower(newPowerDbm)

        btnPowerControl?.text = "⚡ $newPowerDbm dBm"
        btnPowerControlCompact?.text = "⚡ $newPowerDbm dBm"
    }

    override fun onSessionReset() {
        confirmResetSession()
    }

    override fun onBuzzerTest() {}

    override fun onCommitConfirmed() {
        commitInventoryToServer()
    }

    override fun onPrintCurrentReport() {
        printCurrentReport()
    }

    override fun onPrintSingleItem(item: ScannedItemDisplay?) {
        printSingleItemLabel(item)
    }

    override fun onItemStatusChange(item: ScannedItemDisplay?, newStatus: String?) {
        updateItemStatus(item?.epc, newStatus)
    }

    override fun onItemDamageRegister(item: ScannedItemDisplay?, damageType: String?) {
        registerItemDamage(item?.epc, damageType)
    }

    override fun onShowArchive() {
        showArchiveReports()
    }

    override fun onShowSnapshots() {
        openSnapshotsScreen()
    }

    // ================================================================
    //  Scan Lifecycle
    // ================================================================

    private fun startScanning() {
        addDebugLine("▶️ startScanning()")
        if (!canEditInventory()) {
            showAccessDenied()
            return
        }

        if (isScanning || isStartingScan || isFragmentDestroyed) {
            Log.d(TAG, "⏭️ startScanning ignored: already scanning or starting")
            return
        }
        isStartingScan = true

        try {
            val rfidManager = RFIDManager.getInstance(requireContext())
            if (rfidManager == null) {
                isStartingScan = false
                showToast(if (isArabic) "⚠️ مدير القارئ غير جاهز" else "⚠️ Reader manager not ready")
                return
            }

            if (rfidManager.isReady) {
                proceedWithScanning()
                return
            }

            if (rfidManager.state == RFIDManager.State.INITIALIZING) {
                showToast(if (isArabic) "⏳ جاري تهيئة القارئ..." else "⏳ Initializing reader...")

                rfidManager.ensureReady(object : RFIDManager.ReadyCallback {
                    override fun onReady(reader: MiraIRFIDReader?) {
                        mainHandler.post {
                            if (isAdded && !isFragmentDestroyed) {
                                proceedWithScanning()
                            } else {
                                isStartingScan = false
                            }
                        }
                    }

                    override fun onError(reason: String?) {
                        mainHandler.post {
                            isStartingScan = false
                            if (!isAdded || isFragmentDestroyed) return@post
                            showToast(
                                if (isArabic) "❌ فشل تهيئة القارئ: $reason"
                                else "❌ Reader init failed: $reason"
                            )
                        }
                    }
                })
                return
            }

            rfidManager.ensureReady(object : RFIDManager.ReadyCallback {
                override fun onReady(reader: MiraIRFIDReader?) {
                    mainHandler.post {
                        if (isAdded && !isFragmentDestroyed) {
                            proceedWithScanning()
                        } else {
                            isStartingScan = false
                        }
                    }
                }

                override fun onError(reason: String?) {
                    mainHandler.post {
                        isStartingScan = false
                        if (!isAdded || isFragmentDestroyed) return@post
                        showFatalErrorDialog(
                            if (isArabic) "⚠️ القارئ غير متصل" else "⚠️ Reader not connected",
                            reason.orEmpty()
                        )
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Reader readiness check error", e)
            isStartingScan = false
            showToast(if (isArabic) "⚠️ خطأ في القارئ" else "⚠️ Reader error")
        }
    }

    private fun proceedWithScanning() {
        Log.d(TAG, "▶️ proceedWithScanning")

        btnGenerateReport?.apply {
            isEnabled = true
            alpha = 1.0f
        }
        btnCommitSession?.apply {
            text = if (isArabic) "اعتماد" else "Commit"
            alpha = 1.0f
        }

        nextScanIsResume = shouldResumeOnNextStart
        shouldResumeOnNextStart = false

        if (!nextScanIsResume) {
            Log.i(TAG, "🆕 New session — clearing lists + catalog")
            clearAllLists()
            catalogEpcs.clear()
            sessionElapsedBeforePause = 0L
        } else {
            Log.i(TAG, "🔄 Resuming session — preserving ${scannedItemsDisplayList.size} items")
        }

        isPaused = false
        hasUncommittedSession = true

        val controller = scanController
        if (controller == null) {
            isStartingScan = false
            showFatalErrorDialog(
                if (isArabic) "⚠️ مدير المسح غير جاهز" else "⚠️ Scan controller not ready",
                if (isArabic) "حاول إعادة فتح الشاشة" else "Try reopening the screen"
            )
            return
        }

        val emptyCatalog: Map<String, InventoryItem> = ConcurrentHashMap()
        val started = controller.startScanning(emptyCatalog)
        if (!started) {
            nextScanIsResume = false
            isPaused = false
            hasUncommittedSession = false
            shouldResumeOnNextStart = false
            isStartingScan = false

            showFatalErrorDialog(
                if (isArabic) "❌ فشل بدء المسح" else "❌ Failed to start scanning",
                if (isArabic) "تحقق من اتصال القارئ" else "Check reader connection"
            )
            return
        }

        if (skipCatalogLoad) {
            Log.i(TAG, "⚡ Skipping full catalog load (blind or category mode)")
            skipCatalogLoad = false
        } else if (catalogEpcs.isEmpty()) {
            loadCatalogInBackground()
        }
    }

    private fun loadCatalogInBackground() {
        if (!isAdded || isFragmentDestroyed || catalogManager == null) return

        val sessionAtStart = inventoryEngine?.clientSessionId

        backgroundExecutor?.execute {
            catalogManager?.fetchCatalog(
                apiClient,
                branchesManager,
                object : InventoryCatalogManager.CatalogCallback {
                    override fun onCatalogLoaded(catalog: Map<String, InventoryItem>?) {
                        mainHandler.post {
                            if (!isAdded || isFragmentDestroyed) return@post

                            val currentSessionId = inventoryEngine?.clientSessionId
                            if (sessionAtStart == null || currentSessionId == null || sessionAtStart != currentSessionId) {
                                Log.d(TAG, "⏭️ Stale catalog load — session changed")
                                return@post
                            }

                            inventoryEngine?.setCatalog(catalog)

                            catalogEpcs.clear()
                            if (catalog != null) {
                                catalogEpcs.addAll(catalog.keys)
                            }

                            expectedCount = catalog?.size ?: 0
                            addDebugLine("📚 Catalog: $expectedCount items")

                            tvExpectedCount?.text = if (expectedCount > 0) expectedCount.toString() else "—"
                            progressScanned?.max = 100

                            reclassifyScannedItems()
                            Log.i(TAG, "✅ Catalog loaded in background: $expectedCount items")
                        }
                    }

                    override fun onError(error: String?) {
                        val currentSessionId = inventoryEngine?.clientSessionId
                        if (sessionAtStart == null || currentSessionId == null || sessionAtStart != currentSessionId) {
                            return
                        }

                        Log.w(TAG, "⚠️ Catalog load failed (blind mode active): $error")
                        mainHandler.post {
                            addDebugLine("❌ Catalog FAILED")
                            if (!isAdded || isFragmentDestroyed) return@post
                            tvInventoryStatus?.text = if (isArabic) {
                                "📡 جرد بدون كتالوج"
                            } else {
                                "📡 Blind inventory (no catalog)"
                            }
                        }
                    }
                }
            )
        }
    }

    private fun showResumeDialog(itemCount: Int, expected: Int, timeAgoMs: Long) {
        if (!isAdded || isFragmentDestroyed || itemCount < 1) return

        val timeAgo = when {
            timeAgoMs < 60 * 1000L -> if (isArabic) "قبل لحظات" else "just now"
            timeAgoMs < 60 * 60 * 1000L -> {
                val mins = timeAgoMs / 60000L
                if (isArabic) "قبل $mins دقيقة" else "$mins min ago"
            }
            else -> {
                val hrs = timeAgoMs / 3600000L
                if (isArabic) "قبل $hrs ساعة" else "$hrs hours ago"
            }
        }

        val expectedDisplay = if (expected > 0) expected.toString() else "—"
        val message = if (isArabic) {
            "📂 وجدنا جلسة سابقة ($timeAgo)\n\n" +
                    "• القطع: $itemCount\n" +
                    "• المتوقع: $expectedDisplay\n\n" +
                    "هل تريد الاستئناف؟"
        } else {
            "📂 Previous session ($timeAgo)\n\n" +
                    "• Items: $itemCount\n" +
                    "• Expected: $expectedDisplay\n\n" +
                    "Resume?"
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isArabic) "🔄 جلسة سابقة" else "🔄 Previous Session")
            .setMessage(message)
            .setPositiveButton(if (isArabic) "🔄 استئناف" else "🔄 Resume") { _, _ ->
                hasUncommittedSession = true
                isPaused = true
                shouldResumeOnNextStart = true
                switchToActiveState(false)
                updateButtonsState()
            }
            .setNegativeButton(if (isArabic) "🆕 جديدة" else "🆕 New") { _, _ ->
                clearAllLists()

                try {
                    inventoryEngine?.clearSession()
                } catch (_: Exception) {
                }
                try {
                    scanSession?.onSessionReset()
                } catch (_: Exception) {
                }

                hasUncommittedSession = false
                isPaused = false
                nextScanIsResume = false
                shouldResumeOnNextStart = false
                expectedCount = 0
                sessionElapsedBeforePause = 0L

                tvExpectedCount?.text = "0"
                sessionManager?.clearSession()
                hasRestoredThisSession = false
                switchToWelcomeState()
            }
            .setCancelable(false)
            .show()
    }

    fun pauseScanning() {
        isStartingScan = false
        if (!isScanning) return

        if (scanStartTime > 0L) {
            sessionElapsedBeforePause += (SystemClock.elapsedRealtime() - scanStartTime) / 1000L
            scanStartTime = 0L
        }

        Log.d(TAG, "⏸️ pauseScanning")

        scanController?.stopScanning()

        isScanning = false
        isPaused = true
        hasUncommittedSession = true
        shouldResumeOnNextStart = true

        mainHandler.post {
            if (!isAdded || isFragmentDestroyed) return@post

            updateMainToggleButton(false)
            animateMainButton()

            tvInventoryStatus?.text = if (isArabic) {
                "⏸️ الجرد موقوف مؤقتاً — يمكنك الاستئناف"
            } else {
                "⏸️ Inventory paused — you can resume"
            }

            (activity as? UHFMainActivity)?.setNavigationLocked(false)
            updateButtonsState()
        }
    }

    fun stopScanning() {
        isStartingScan = false
        if (!isScanning) return

        Log.d(TAG, "⏹️ stopScanning (FINAL)")

        scanController?.stopScanning()

        isScanning = false
        isPaused = false
        hasUncommittedSession = false
        nextScanIsResume = false
        shouldResumeOnNextStart = false

        mainHandler.post {
            if (!isAdded || isFragmentDestroyed) return@post

            updateMainToggleButton(false)
            animateMainButton()
            (activity as? UHFMainActivity)?.setNavigationLocked(false)
            updateButtonsState()
        }
    }

    private fun startBlindInventory() {
        if (!canEditInventory()) {
            showAccessDenied()
            return
        }
        if (isScanning || isFragmentDestroyed) return

        clearAllLists()

        try {
            inventoryEngine?.clearSession()
        } catch (_: Exception) {
        }
        try {
            scanSession?.onSessionReset()
        } catch (_: Exception) {
        }

        isPaused = false
        hasUncommittedSession = false
        nextScanIsResume = false
        shouldResumeOnNextStart = false

        expectedCount = 0
        catalogEpcs.clear()
        skipCatalogLoad = true

        tvExpectedCount?.text = "—"
        tvInventoryStatus?.text = if (isArabic) {
            "⚡ جرد أعمى - بدون توقعات"
        } else {
            "⚡ Blind inventory - No expectations"
        }

        proceedWithScanning()
    }

    private fun startCategoryInventory(category: String) {
        if (isScanning || isFragmentDestroyed) return

        clearAllLists()

        showToast(
            if (isArabic) "🔄 جاري تحميل كتالوج $category..."
            else "🔄 Loading catalog for $category..."
        )

        backgroundExecutor?.execute {
            val catMgr = catalogManager ?: return@execute
            if (isFragmentDestroyed) return@execute

            var loadedCatalog: Map<String, InventoryItem>? = null
            val latch = CountDownLatch(1)

            catMgr.fetchCatalog(
                apiClient,
                branchesManager,
                object : InventoryCatalogManager.CatalogCallback {
                    override fun onCatalogLoaded(catalog: Map<String, InventoryItem>?) {
                        loadedCatalog = catalog
                        latch.countDown()
                    }

                    override fun onError(error: String?) {
                        loadedCatalog = ConcurrentHashMap()
                        latch.countDown()
                    }
                }
            )

            try {
                latch.await(30, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            val fullCatalog = loadedCatalog ?: ConcurrentHashMap()
            val filtered = catMgr.filterByCategory(fullCatalog, category)

            mainHandler.post {
                if (!isAdded || isFragmentDestroyed) return@post

                try {
                    inventoryEngine?.clearSession()
                    inventoryEngine?.setCatalog(filtered)
                } catch (_: Exception) {
                }
                try {
                    scanSession?.onSessionReset()
                } catch (_: Exception) {
                }

                isPaused = false
                hasUncommittedSession = false
                nextScanIsResume = false
                shouldResumeOnNextStart = false

                // منع تحميل الكتالوج الكامل حتى لو كانت الفئة فارغة
                skipCatalogLoad = true

                catalogEpcs.clear()
                catalogEpcs.addAll(filtered.keys)
                expectedCount = filtered.size

                tvExpectedCount?.text = expectedCount.toString()
                tvInventoryStatus?.text = if (isArabic) {
                    "🎯 جرد فئوي: $category ($expectedCount)"
                } else {
                    "🎯 Category: $category ($expectedCount)"
                }

                if (expectedCount == 0) {
                    showToast(
                        if (isArabic) "⚠️ لا توجد قطع في هذه الفئة"
                        else "⚠️ No items in this category"
                    )
                }

                proceedWithScanning()
            }
        }
    }

    private fun clearAllLists() {
        try {
            synchronized(scannedItemsDisplayList) {
                scannedItemsDisplayList.clear()
            }
            itemStatusOverrides.clear()
            itemDamageNotes.clear()
            aggregateTotalWeight = 0.0
            aggregateTotalValue = 0.0
            hasCelebrated = false

            modernAdapter?.apply {
                submitList(emptyList())
                resetAnimationTracker()
            }

            rvInventoryItems?.scrollToPosition(0)

            tvScannedCount?.text = "0"
            tvItemCount?.text = "0"
            tvVariance?.apply {
                text = "—"
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            }
            tvAccuracy?.text = "—"
            progressScanned?.progress = 0
            tvProgressPercent?.text = "0%"
            tvTotalWeight?.text = "0.00 g"
            tvTotalValue?.text = "$0.00"
            tvEmptyState?.visibility = View.VISIBLE

            feedbackHelper?.resetDebounce()
            lastScannedForAnimation = 0

            tvElapsedTime?.text = "00:00:00"
            tvRssi?.text = "📶 —"
            scanStartTime = 0L

            tvLastScanTitle?.text = if (isArabic) "لا توجد مسوحات" else "No scans"
            tvLastScanEpc?.text = "—"
            tvLastScanKarat?.text = "—"
            tvLastScanWeight?.text = "0.00g"
            tvLastScanRssi?.text = "📶 —"
            tvLastScanStatus?.apply {
                text = if (isArabic) "بانتظار المسح" else "Waiting"
                setTextColor(0xFF6B7280.toInt())
            }

            btnCommitSession?.apply {
                text = if (isArabic) "اعتماد" else "Commit"
                alpha = 1.0f
            }

            updateButtonsState()
        } catch (e: Exception) {
            Log.e(TAG, "clearAllLists error", e)
        }
    }

    private fun updateMainToggleButton(scanning: Boolean) {
        val btn = btnMainToggleScan ?: return

        try {
            when {
                scanning -> {
                    btn.text = if (isArabic) "⏸️ إيقاف مؤقت" else "⏸️ Pause"
                    try {
                        btn.icon = ContextCompat.getDrawable(requireContext(), android.R.drawable.ic_media_pause)
                    } catch (_: Exception) {
                    }
                    try {
                        val redColor = ContextCompat.getColor(requireContext(), R.color.inventory_stop_red)
                        btn.backgroundTintList = ColorStateList.valueOf(redColor)
                    } catch (_: Exception) {
                    }
                }

                isPaused && hasUncommittedSession -> {
                    btn.text = if (isArabic) "▶️ استئناف أو جرد جديد" else "▶️ Resume or New"
                    try {
                        btn.icon = ContextCompat.getDrawable(requireContext(), android.R.drawable.ic_media_play)
                    } catch (_: Exception) {
                    }
                    try {
                        val greenColor = ContextCompat.getColor(requireContext(), R.color.success_green)
                        btn.backgroundTintList = ColorStateList.valueOf(greenColor)
                    } catch (_: Exception) {
                    }
                }

                else -> {
                    btn.text = if (isArabic) "▶️ بدء جرد جديد" else "▶️ Start New Inventory"
                    try {
                        btn.icon = ContextCompat.getDrawable(requireContext(), android.R.drawable.ic_media_play)
                    } catch (_: Exception) {
                    }
                    try {
                        val primaryColor = ContextCompat.getColor(requireContext(), R.color.colorPrimary)
                        btn.backgroundTintList = ColorStateList.valueOf(primaryColor)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateMainToggleButton error", e)
        }
    }

    private fun animateMainButton() {
        val btn = btnMainToggleScan ?: return
        try {
            val scaleX = ObjectAnimator.ofFloat(btn, "scaleX", 1f, 1.05f, 1f)
            val scaleY = ObjectAnimator.ofFloat(btn, "scaleY", 1f, 1.05f, 1f)
            scaleX.duration = 300L
            scaleY.duration = 300L
            scaleX.start()
            scaleY.start()
        } catch (_: Exception) {
        }
    }

    private fun showFatalErrorDialog(title: String, message: String) {
        if (!isAdded || isFragmentDestroyed) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(if (isArabic) "حسناً" else "OK", null)
            .setCancelable(false)
            .show()
    }

    private fun showResumeOrNewDialog() {
        if (!isAdded || isFragmentDestroyed) return

        val itemCount = scannedItemsDisplayList.size

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isArabic) "🔄 لديك جلسة موقوفة" else "🔄 Paused Session")
            .setMessage(
                if (isArabic)
                    "لديك $itemCount قطعة في الجلسة الموقوفة.\n\n" +
                            "• استئناف: يكمل من حيث توقفت (تحتفظ بالبيانات)\n" +
                            "• جرد جديد: يمسح البيانات ويبدأ من الصفر"
                else
                    "You have $itemCount items in the paused session.\n\n" +
                            "• Resume: Continue where you stopped (data preserved)\n" +
                            "• New: Clear data and start fresh"
            )
            .setPositiveButton(if (isArabic) "▶️ استئناف" else "▶️ Resume") { _, _ ->
                Log.i(TAG, "🔄 User chose: RESUME")
                startScanning()
            }
            .setNeutralButton(if (isArabic) "🆕 جرد جديد" else "🆕 New") { _, _ ->
                Log.i(TAG, "🆕 User chose: NEW — clearing all data")
                clearAllLists()
                catalogEpcs.clear()

                try {
                    inventoryEngine?.clearSession()
                } catch (_: Exception) {
                }
                try {
                    scanSession?.onSessionReset()
                } catch (_: Exception) {
                }

                isPaused = false
                hasUncommittedSession = false
                shouldResumeOnNextStart = false
                nextScanIsResume = false
                expectedCount = 0
                sessionElapsedBeforePause = 0L
                hasCelebrated = false

                try {
                    sessionManager?.clearSession()
                } catch (_: Exception) {
                }

                tvExpectedCount?.text = "0"
                startScanning()
            }
            .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
            .setCancelable(true)
            .show()
    }

    // ================================================================
    //  Scanned Items Handling
    // ================================================================

    private fun addScannedItemToDisplay(epc: String, rssi: Int, item: InventoryItem?) {
        if (!isAdded || isFragmentDestroyed || modernAdapter == null) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { addScannedItemToDisplay(epc, rssi, item) }
            return
        }

        var currentDisplay: ScannedItemDisplay? = null
        var isDuplicate = false
        val wasEmpty = scannedItemsDisplayList.isEmpty()

        synchronized(scannedItemsDisplayList) {
            for (display in scannedItemsDisplayList) {
                if (display != null && epc == display.epc) {
                    display.count++
                    display.rssi = rssi
                    display.time = getCurrentTimeString()
                    currentDisplay = display
                    isDuplicate = true
                    break
                }
            }
        }

        if (!isDuplicate) {
            val isKnown = catalogEpcs.contains(epc)
            addDebugLine("➕ NEW: ${epc.takeLast(6)} | known=$isKnown")

            var title = if (isArabic) "قطعة غير معروفة" else "Unknown item"
            var karat = "—"
            var weight = 0.0
            var price = 0.0

            val catalogItem = catalogManager?.getItemByEpc(epc)

            when {
                catalogItem != null -> {
                    title = if (!catalogItem.title.isNullOrEmpty()) catalogItem.title
                    else if (isArabic) "قطعة ذهبية" else "Gold item"
                    karat = if (!catalogItem.karat.isNullOrEmpty()) catalogItem.karat else "—"
                    weight = catalogItem.weight
                    price = catalogItem.price
                }

                item != null -> {
                    title = if (!item.title.isNullOrEmpty()) item.title
                    else if (isArabic) "قطعة ذهبية" else "Gold item"
                    karat = if (!item.karat.isNullOrEmpty()) item.karat else "—"
                    weight = item.weight
                    price = item.price
                }

                isKnown -> {
                    title = if (isArabic) "مادة مسجلة بالكتالوج" else "Catalog registered item"
                }
            }

            val display = ScannedItemDisplay(
                epc, title, karat, 1, rssi, getCurrentTimeString()
            ).apply {
                this.weight = weight
                this.price = price
            }

            synchronized(scannedItemsDisplayList) {
                scannedItemsDisplayList.add(0, display)
            }
            currentDisplay = display

            rvInventoryItems?.scrollToPosition(0)
            tvEmptyState?.visibility = View.GONE
        } else {
            addDebugLine("🔁 DUP: ${epc.takeLast(6)}")
        }

        recalculateAggregates()
        updateTotalWeightAndValue()

        if (wasEmpty && scannedItemsDisplayList.isNotEmpty()) {
            switchToActiveState(true)
        }

        currentDisplay?.let { updateLastScanCard(it, isDuplicate) }
    }

    private fun updateAdapterList() {
        val adapter = modernAdapter ?: return
        val fMgr = filterManager ?: return
        if (isFragmentDestroyed) return

        val filtered = fMgr.applyFilter(scannedItemsDisplayList, catalogEpcs)
        adapter.submitList(filtered)
    }

    private fun filterItems(query: String) {
        try {
            filterManager?.setSearchQuery(query)
            updateAdapterList()
        } catch (e: Exception) {
            Log.e(TAG, "filterItems error", e)
        }
    }

    private fun updateLastScan(epc: String, rssi: Int) {
        tvRssi?.text = String.format(Locale.US, "📶 %d", rssi)
    }

    private fun updateLastScanCard(item: ScannedItemDisplay?, isDuplicate: Boolean) {
        if (item == null || tvLastScanTitle == null) return

        try {
            tvLastScanTitle?.text = item.title ?: "—"

            tvLastScanEpc?.let { tv ->
                val epc = item.epc.orEmpty()
                if (epc.isNotEmpty()) {
                    tv.text = formatEpcShort(epc)
                    tv.visibility = View.VISIBLE
                } else {
                    tv.visibility = View.GONE
                }
            }

            tvLastScanPrice?.text = if (item.price > 0) {
                String.format(Locale.US, "$%,.2f", item.price)
            } else {
                "—"
            }

            tvLastScanWeight?.text = if (item.weight > 0) {
                String.format(Locale.US, "%.3fg", item.weight)
            } else {
                "—"
            }

            tvLastScanKarat?.text = if (!item.karat.isNullOrEmpty()) item.karat else "—"
            tvLastScanRssi?.text = String.format(Locale.US, "%d", item.rssi)

            if (chipReadCount != null && tvLastScanReadCount != null) {
                if (item.count > 1) {
                    tvLastScanReadCount?.text = String.format(Locale.US, "× %d", item.count)
                    chipReadCount?.visibility = View.VISIBLE
                } else {
                    chipReadCount?.visibility = View.GONE
                }
            }

            rowSerialTime?.let { row ->
                var hasSerial = false
                var hasTime = false

                tvLastScanSerial?.let { tv ->
                    val serial = getSerialDisplay(item)
                    if (!serial.isNullOrEmpty()) {
                        tv.text = serial
                        hasSerial = true
                    }
                }

                if (tvLastScanTime != null && !item.time.isNullOrEmpty()) {
                    tvLastScanTime?.text = "⏰ ${extractTimeShort(item.time)}"
                    hasTime = true
                }

                row.visibility = if (hasSerial || hasTime) View.VISIBLE else View.GONE
            }

            val isKnown = catalogEpcs.contains(item.epc)
            val hasCatalog = catalogEpcs.isNotEmpty()

            tvLastScanStatus?.let { tv ->
                val statusText: String
                val statusColor: Int

                when {
                    isDuplicate -> {
                        statusText = if (isArabic) "🔁 مكرر" else "🔁 Duplicate"
                        statusColor = 0xFF3B82F6.toInt()
                    }
                    isKnown -> {
                        statusText = if (isArabic) "✅ مطابق للمخزون" else "✅ In Catalog"
                        statusColor = 0xFF16A34A.toInt()
                    }
                    hasCatalog -> {
                        statusText = if (isArabic) "🆕 غير متوقع" else "🆕 Unexpected"
                        statusColor = 0xFFF59E0B.toInt()
                    }
                    else -> {
                        statusText = if (isArabic) "⚠️ غير معروف" else "⚠️ Unknown"
                        statusColor = 0xFFDC2626.toInt()
                    }
                }

                tv.text = statusText
                tv.setTextColor(statusColor)
            }

            lastScanCard?.let { card ->
                val scaleX = ObjectAnimator.ofFloat(card, "scaleX", 0.98f, 1f)
                val scaleY = ObjectAnimator.ofFloat(card, "scaleY", 0.98f, 1f)
                scaleX.duration = 200L
                scaleY.duration = 200L
                scaleX.start()
                scaleY.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateLastScanCard error", e)
        }
    }

    private fun formatEpcShort(epc: String?): String {
        if (epc.isNullOrEmpty()) return ""
        if (epc.length <= 16) return epc
        return "${epc.substring(0, 8)}…${epc.substring(epc.length - 6)}"
    }

    private fun getSerialDisplay(item: ScannedItemDisplay?): String? {
        return try {
            if (item == null) return null
            val catalogItem = catalogManager?.getItemByEpc(item.epc) ?: return null

            val serial = catalogItem.serial
            if (!serial.isNullOrEmpty()) return "SN: $serial"

            val gtin = catalogItem.gtin
            if (!gtin.isNullOrEmpty()) return "GTIN: $gtin"

            null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractTimeShort(timeStr: String?): String {
        if (timeStr.isNullOrEmpty()) return ""
        return try {
            val parts = timeStr.split(":")
            if (parts.size >= 2) "${parts[0]}:${parts[1]}" else timeStr
        } catch (_: Exception) {
            timeStr
        }
    }

    private fun reclassifyScannedItems() {
        if (!isAdded || isFragmentDestroyed || scannedItemsDisplayList.isEmpty()) return

        Log.i(TAG, "🔄 Reclassifying ${scannedItemsDisplayList.size} items after catalog load")

        synchronized(scannedItemsDisplayList) {
            for (item in scannedItemsDisplayList) {
                if (item == null) continue
                val catalogItem = catalogManager?.getItemByEpc(item.epc) ?: continue

                if (!catalogItem.title.isNullOrEmpty()) item.title = catalogItem.title
                if (!catalogItem.karat.isNullOrEmpty()) item.karat = catalogItem.karat
                item.weight = catalogItem.weight
                item.price = catalogItem.price

                try {
                    inventoryEngine?.updateItemFromCatalog(
                        item.epc,
                        item.title,
                        item.karat,
                        item.weight,
                        item.price
                    )
                } catch (_: Exception) {
                }
            }
        }

        recalculateAggregates()
        updateTotalWeightAndValue()
        updateAdapterList()
        updateUI()
    }

    private fun updateButtonsState() {
        if (!isAdded || isFragmentDestroyed) return

        val scanned = scannedItemsDisplayList.size
        val hasItems = scanned > 0
        val canEdit = canEditInventory()
        val canView = canViewInventory()

        btnMainToggleScan?.apply {
            isEnabled = canEdit
            alpha = if (canEdit) 1.0f else 0.5f
        }

        btnFinishAndReview?.visibility = if (hasItems && !isScanning) View.VISIBLE else View.GONE

        btnCommitSession?.apply {
            val canCommit = hasItems && !isScanning && canEdit
            isEnabled = canCommit
            alpha = if (canCommit) 1.0f else 0.5f
        }

        btnGenerateReport?.apply {
            val canReport = hasItems && !isScanning
            isEnabled = canReport
            alpha = if (canReport) 1.0f else 0.5f
        }

        btnOpenItemsList?.apply {
            isEnabled = hasItems
            alpha = if (hasItems) 1.0f else 0.5f
        }

        val canPower = !isScanning && canEdit
        btnPowerControl?.apply {
            isEnabled = canPower
            alpha = if (canPower) 1.0f else 0.5f
        }
        btnPowerControlCompact?.apply {
            isEnabled = canPower
            alpha = if (canPower) 1.0f else 0.5f
        }

        val canArchive = !isScanning && canView
        btnArchiveReports?.apply {
            isEnabled = canArchive
            alpha = if (canArchive) 1.0f else 0.5f
        }
        btnArchiveReportsCompact?.apply {
            isEnabled = canArchive
            alpha = if (canArchive) 1.0f else 0.5f
        }

        btnTakeSnapshot?.apply {
            val canSnapshot = hasItems && !isScanning
            isEnabled = canSnapshot
            alpha = if (canSnapshot) 1.0f else 0.5f
        }

        btnViewSnapshots?.apply {
            isEnabled = !isScanning
            alpha = if (isScanning) 0.5f else 1.0f
        }
    }

    // ================================================================
    //  UI Updates
    // ================================================================

    private fun scheduleUiUpdate() {
        if (!pendingUiUpdate && !isFragmentDestroyed) {
            pendingUiUpdate = true
            mainHandler.postDelayed(uiUpdateRunnable, uiUpdateDelay)
        }
    }

    private fun getAdaptiveUiThrottle(): Long {
        val count = scannedItemsDisplayList.size
        return when {
            count < ITEMS_COUNT_SMALL -> UI_UPDATE_THROTTLE_MIN_MS
            count < ITEMS_COUNT_MEDIUM -> UI_UPDATE_THROTTLE_MEDIUM_MS
            else -> UI_UPDATE_THROTTLE_MAX_MS
        }
    }

    private fun updateUI() {
        if (!isAdded || isFragmentDestroyed) return

        val scanned = scannedItemsDisplayList.size

        tvScannedCount?.let { tv ->
            val newText = scanned.toString()
            if (newText != tv.text.toString()) {
                val anim = animationHelper
                if (anim != null) {
                    anim.animateNumberChange(tv, lastScannedForAnimation, scanned)
                } else {
                    tv.text = newText
                }
            }
        }
        tvItemCount?.text = scanned.toString()

        if (expectedCount > 0) {
            var foundInCatalog = 0
            synchronized(scannedItemsDisplayList) {
                for (item in scannedItemsDisplayList) {
                    if (item != null && catalogEpcs.contains(item.epc)) {
                        foundInCatalog++
                    }
                }
            }

            val progress = minOf(((foundInCatalog.toFloat() / expectedCount) * 100).toInt(), 100)

            progressScanned?.let { bar ->
                val anim = animationHelper
                if (anim != null) {
                    val prev = bar.progress
                    if (progress != prev) {
                        anim.animateProgressBar(bar, prev, progress)
                    }
                } else {
                    bar.progress = progress
                }
            }

            tvProgressPercent?.let { tv ->
                val anim = animationHelper
                if (anim != null) {
                    anim.animateProgressText(tv, progress)
                } else {
                    tv.text = "$progress%"
                }
            }

            tvAccuracy?.apply {
                text = "$progress%"
                val colorRes = when {
                    progress >= 100 -> R.color.success_green
                    progress >= 70 -> R.color.warning_amber
                    else -> R.color.text_primary
                }
                setTextColor(ContextCompat.getColor(requireContext(), colorRes))
            }

            tvVariance?.apply {
                val variance = expectedCount - foundInCatalog
                when {
                    variance > 0 -> {
                        text = "−$variance"
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.warning_amber))
                    }
                    variance < 0 -> {
                        text = "+${kotlin.math.abs(variance)}"
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.info_blue))
                    }
                    else -> {
                        text = "0"
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.success_green))
                    }
                }
            }

            if (foundInCatalog >= expectedCount && isScanning) {
                if (!hasCelebrated) {
                    hasCelebrated = true
                    animationHelper?.celebrateCompletion(
                        tvProgressPercent,
                        progressScanned,
                        tvScannedCount
                    )
                    showToast(
                        if (isArabic) "🎉 تم مسح كل المواد المتوقعة — يمكنك الاستمرار أو الإيقاف يدوياً"
                        else "🎉 All expected items scanned — continue or stop manually"
                    )
                }
            } else {
                hasCelebrated = false
            }
        } else {
            tvProgressPercent?.text = "—"
            tvAccuracy?.text = "—"
            tvVariance?.apply {
                text = "—"
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            }
        }

        tvEmptyState?.visibility = if (scanned > 0) View.GONE else View.VISIBLE

        if (isScanning) {
            tvInventoryStatus?.text = (if (isArabic) "📡 جاري الجرد... (" else "📡 Scanning... (") + "$scanned)"
        }

        lastScannedForAnimation = scanned
        updateButtonsState()
    }

    private fun updateTotalWeightAndValue() {
        val finalWeight = aggregateTotalWeight
        val finalValue = aggregateTotalValue

        mainHandler.post {
            if (!isAdded || isFragmentDestroyed) return@post
            tvTotalWeight?.text = String.format(Locale.US, "%.2f g", finalWeight)
            tvTotalValue?.text = String.format(Locale.US, "$%,.2f", finalValue)
        }
    }

    private fun recalculateAggregates() {
        var w = 0.0
        var v = 0.0
        synchronized(scannedItemsDisplayList) {
            for (item in scannedItemsDisplayList) {
                if (item != null) {
                    w += item.weight
                    v += item.price
                }
            }
        }
        aggregateTotalWeight = w
        aggregateTotalValue = v
    }

    private fun updateElapsedTime() {
        val tv = tvElapsedTime ?: return
        if (scanStartTime > 0L) {
            val currentSegmentSeconds = (SystemClock.elapsedRealtime() - scanStartTime) / 1000L
            tv.text = formatElapsed(sessionElapsedBeforePause + currentSegmentSeconds)
        }
    }

    /** مصدر الحقيقة الموحّد لعدد القطع الفريدة بين الواجهة والمحرك */
    private fun getEffectiveScannedCount(): Int {
        val engineCount = inventoryEngine?.uniqueCount ?: 0
        val listCount = scannedItemsDisplayList.size
        return maxOf(engineCount, listCount)
    }

    private fun syncEngineWithRestoredItems(items: List<ScannedItemDisplay>) {
        val engine = inventoryEngine ?: return
        try {
            engine.clearSession()
            for (item in items) {
                if (item.epc.isNullOrEmpty()) continue
                engine.addEpc(item.epc, item.rssi)
                if (!item.title.isNullOrEmpty() || item.weight > 0.0 || item.price > 0.0) {
                    try {
                        engine.updateItemFromCatalog(
                            item.epc,
                            item.title,
                            item.karat,
                            item.weight,
                            item.price
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "syncEngineWithRestoredItems warning: ${e.message}")
        }
    }

    // ================================================================
    //  Commit & Archive
    // ================================================================

    private fun showCommitConfirmationDialog() {
        val dMgr = dialogsManager ?: return
        if (inventoryEngine == null) return
        if (!canEditInventory()) {
            showAccessDenied()
            return
        }
        val scanned = getEffectiveScannedCount()
        dMgr.setExpectedCount(expectedCount)
        dMgr.showCommitConfirmationDialog(scanned)
    }

    private fun buildLocalCommitSummary(): JSONObject {
        return try {
            val scanned = getEffectiveScannedCount()
            val accuracy = if (expectedCount > 0) {
                minOf(100, (scanned * 100) / expectedCount).toString()
            } else {
                "100"
            }

            val variance = kotlin.math.abs(expectedCount - scanned)
            val riskLevel = when {
                variance == 0 -> "none"
                variance <= 2 -> "low"
                variance <= 10 -> "medium"
                else -> "high"
            }

            JSONObject().apply {
                put("accuracy", accuracy)
                put("risk_level", riskLevel)
                put(
                    "recommendation",
                    if (isArabic) "تم الاعتماد — التقرير متاح في الأرشيف"
                    else "Committed — report available in archive"
                )
                put("expected_count", expectedCount)
                put("found_count", scanned)
                put("missing_count", maxOf(0, expectedCount - scanned))
                put("unexpected_count", maxOf(0, scanned - expectedCount))
                put("closed_at", getCurrentDateTimeString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "buildLocalCommitSummary error", e)
            JSONObject()
        }
    }

    private fun commitInventoryToServer() {
        if (!isAdded || isFragmentDestroyed) return

        val pMgr = persistenceManager
        val engine = inventoryEngine
        val api = apiClient

        if (pMgr == null || engine == null || api == null) {
            showToast("⚠️ غير جاهز")
            return
        }

        if (pMgr.isCommittingNow) {
            showToast(if (isArabic) "⏳ جاري الإرسال بالفعل..." else "⏳ Already sending...")
            return
        }

        showToast(if (isArabic) "⏳ جاري إرسال البيانات..." else "⏳ Sending...")

        val scanned = getEffectiveScannedCount()
        var calculatedReads = 0
        synchronized(scannedItemsDisplayList) {
            for (item in scannedItemsDisplayList) {
                calculatedReads += item?.count ?: 0
            }
        }
        val totalReads = maxOf(engine.totalReads, calculatedReads)
        val branchId = getActiveBranchId()

        pMgr.commitInventory(
            engine.clientSessionId,
            branchId,
            expectedCount,
            totalReads,
            scanned,
            scannedItemsDisplayList,
            api,
            object : InventoryPersistenceManager.CommitCallback {
                override fun onCommitSuccess(report: JSONObject?) {
                    mainHandler.post {
                        if (!isAdded || isFragmentDestroyed) return@post

                        val finalReport = if (report == null || report.length() == 0) {
                            Log.w(TAG, "⚠️ Empty report from server — building local summary")
                            buildLocalCommitSummary()
                        } else {
                            report
                        }

                        lastServerReport = finalReport

                        showToast(
                            if (isArabic) "✅ تم اعتماد الجرد وحفظ التقرير"
                            else "✅ Inventory committed successfully"
                        )

                        if (scanSession != null) {
                            scanSession?.notifySuccess()
                        } else {
                            soundManager?.playBeep(SoundManager.FeedbackType.COMPLETED)
                        }

                        isPaused = false
                        hasUncommittedSession = false
                        nextScanIsResume = false
                        shouldResumeOnNextStart = false
                        sessionElapsedBeforePause = 0L
                        hasCelebrated = false

                        try {
                            sessionManager?.clearSession()
                            Log.d(TAG, "🧹 Session cleared after commit")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to clear session after commit", e)
                        }

                        btnGenerateReport?.apply {
                            isEnabled = false
                            alpha = 0.5f
                        }
                        btnCommitSession?.apply {
                            isEnabled = false
                            text = if (isArabic) "✅ معتمد" else "✅ Committed"
                            alpha = 0.7f
                        }

                        autoSaveSnapshotAfterCommit(finalReport)
                        showCommitSuccessDialog(finalReport)
                    }
                }

                override fun onCommitError(error: String?) {
                    mainHandler.post {
                        if (!isAdded || isFragmentDestroyed) return@post
                        Log.e(TAG, "Commit failed: $error")
                        showToast((if (isArabic) "❌ خطأ: " else "❌ Error: ") + error.orEmpty())
                        scanSession?.notifyError()
                    }
                }
            }
        )
    }

    private fun showCommitSuccessDialog(report: JSONObject) {
        if (!isAdded || isFragmentDestroyed) return

        try {
            val accuracy = extractString(report, "accuracy", "100")
            val riskLevel = extractString(
                report,
                "risk_level",
                extractString(report, "risk", "none")
            )
            val recommendation = extractString(
                report,
                "recommendation",
                extractString(
                    report,
                    "message",
                    if (isArabic) "تم الاعتماد بنجاح" else "Committed successfully"
                )
            )

            val expected = extractInt(
                report,
                "expected_count",
                extractInt(report, "expected", expectedCount)
            )
            val found = extractInt(
                report,
                "found_count",
                extractInt(
                    report,
                    "unique_count",
                    extractInt(report, "scanned_count", getEffectiveScannedCount())
                )
            )
            val missing = extractInt(report, "missing_count", maxOf(0, expected - found))
            val soldDuringSession = extractInt(report, "sold_during_session", 0)
            val transferredDuring = extractInt(report, "transferred_during_session", 0)
            val deletedDuring = extractInt(report, "deleted_during_session", 0)

            val sb = StringBuilder().apply {
                append(if (isArabic) "✅ تم اعتماد الجرد بنجاح:\n\n" else "✅ Inventory committed:\n\n")
                append(if (isArabic) "• الدقة: " else "• Accuracy: ").append(accuracy).append("%\n")
                append(if (isArabic) "• مستوى الخطورة: " else "• Risk Level: ").append(riskLevel).append("\n")

                if (expected > 0) {
                    append(if (isArabic) "• المتوقع: " else "• Expected: ").append(expected).append("\n")
                    append(if (isArabic) "• الممسوح: " else "• Found: ").append(found).append("\n")
                    if (missing > 0) {
                        append(if (isArabic) "• المفقود فعلاً: " else "• Truly missing: ").append(missing).append("\n")
                    }
                    if (soldDuringSession > 0) {
                        append(if (isArabic) "• 🛒 مبيع أثناء الجرد: " else "• 🛒 Sold during session: ")
                            .append(soldDuringSession).append("\n")
                    }
                    if (transferredDuring > 0) {
                        append(if (isArabic) "• 🔄 منقول لفرع آخر: " else "• 🔄 Transferred: ")
                            .append(transferredDuring).append("\n")
                    }
                    if (deletedDuring > 0) {
                        append(if (isArabic) "• 🗑️ محذوف: " else "• 🗑️ Deleted: ")
                            .append(deletedDuring).append("\n")
                    }
                }

                append("\n• ").append(recommendation)
            }

            lastServerReport = report

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (isArabic) "🎉 نجاح العملية" else "🎉 Success")
                .setMessage(sb.toString())
                .setPositiveButton(if (isArabic) "🖨️ طباعة" else "🖨️ Print") { _, _ ->
                    printCurrentReport()
                }
                .setNeutralButton(if (isArabic) "📂 الأرشيف" else "📂 Archive") { _, _ ->
                    showArchiveReports()
                }
                .setNegativeButton(if (isArabic) "✔️ إغلاق وبدء جديد" else "✔️ Close & New") { _, _ ->
                    clearAllLists()
                    catalogEpcs.clear()
                    switchToWelcomeState()

                    try {
                        inventoryEngine?.clearSession()
                    } catch (_: Exception) {
                    }
                    try {
                        scanSession?.onSessionReset()
                    } catch (_: Exception) {
                    }

                    isPaused = false
                    hasUncommittedSession = false
                    shouldResumeOnNextStart = false
                    nextScanIsResume = false
                    expectedCount = 0
                    sessionElapsedBeforePause = 0L
                    hasCelebrated = false

                    tvExpectedCount?.text = "0"
                    hasRestoredThisSession = false
                    hasTriedRestoringSession = false

                    Log.i(TAG, "✅ Post-commit: everything cleared, ready for new inventory")
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "showCommitSuccessDialog error", e)
            showToast(if (isArabic) "✅ تم الاعتماد" else "✅ Committed")
        }
    }

    private fun extractString(obj: JSONObject?, key: String, defaultValue: String): String {
        if (obj == null) return defaultValue
        return try {
            val value = obj.opt(key) ?: return defaultValue
            val str = value.toString().trim()
            str.ifEmpty { defaultValue }
        } catch (_: Exception) {
            defaultValue
        }
    }

    private fun extractInt(obj: JSONObject?, key: String, defaultValue: Int): Int {
        if (obj == null) return defaultValue
        return try {
            obj.optInt(key, defaultValue)
        } catch (_: Exception) {
            defaultValue
        }
    }

    private fun showArchiveReports() {
        if (!isAdded || isFragmentDestroyed) return

        val pMgr = persistenceManager
        val api = apiClient
        if (pMgr == null || api == null || branchesManager == null) {
            showToast(if (isArabic) "❌ غير جاهز" else "❌ Not ready")
            return
        }

        val loading = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isArabic) "📂 جاري تحميل الأرشيف..." else "📂 Loading archive...")
            .setMessage(if (isArabic) "يرجى الانتظار" else "Please wait")
            .setCancelable(false)
            .show()

        val branchId = getActiveBranchId()

        pMgr.fetchArchiveReports(
            api,
            branchId,
            object : InventoryPersistenceManager.ArchiveCallback {
                override fun onArchiveLoaded(reports: List<InventoryReport>?) {
                    mainHandler.post {
                        try {
                            loading.dismiss()
                        } catch (_: Exception) {
                        }
                        if (isAdded && !isFragmentDestroyed) {
                            displayArchiveDialog(reports)
                        }
                    }
                }

                override fun onArchiveError(error: String?) {
                    mainHandler.post {
                        try {
                            loading.dismiss()
                        } catch (_: Exception) {
                        }
                        if (isAdded && !isFragmentDestroyed) {
                            showToast((if (isArabic) "❌ فشل التحميل: " else "❌ Load failed: ") + error.orEmpty())
                        }
                    }
                }
            }
        )
    }

    private fun displayArchiveDialog(reports: List<InventoryReport>?) {
        if (!isAdded || isFragmentDestroyed) return

        if (reports.isNullOrEmpty()) {
            showToast(if (isArabic) "لا توجد تقارير سابقة" else "No previous reports")
            return
        }

        val reportTitles = Array(reports.size) { i ->
            val r = reports[i]
            String.format(
                Locale.US,
                "📋 %s - %s (%d/%d)",
                r.sessionId,
                r.date,
                r.scannedCount,
                r.expectedCount
            )
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isArabic) "📂 أرشيف تقارير الجرد" else "📂 Inventory Reports Archive")
            .setItems(reportTitles) { _, which ->
                showReportDetails(reports[which])
            }
            .setPositiveButton(if (isArabic) "طباعة الكل" else "Print All") { _, _ ->
                printManager?.printAllReports(reports)
            }
            .setNegativeButton(if (isArabic) "إغلاق" else "Close", null)
            .show()
    }

    private fun showReportDetails(report: InventoryReport?) {
        if (report == null || !isAdded || isFragmentDestroyed) return
        printManager?.showReportDetails(report) { r ->
            Log.d(TAG, "Print requested for ${r.sessionId}")
        }
    }

    private fun openSnapshotsScreen() {
        try {
            if (!isAdded || isFragmentDestroyed) return

            if (isScanning) {
                showExitWarning {
                    stopScanning()
                    mainHandler.postDelayed({ navigateToSnapshots() }, 400L)
                }
            } else {
                navigateToSnapshots()
            }
        } catch (e: Exception) {
            Log.e(TAG, "openSnapshotsScreen error", e)
        }
    }

    private fun navigateToSnapshots() {
        try {
            if (!isAdded || isFragmentDestroyed || activity == null) return

            val mainAct = activity as? UHFMainActivity
            val nav = mainAct?.navigationManager
            if (nav != null) {
                val tag = NavigationManager.TAG_SNAPSHOTS
                val fragment: Fragment? = nav.createFragmentByTag(tag)
                if (fragment != null) {
                    nav.loadFragmentDirectly(fragment, tag)
                    Log.d(TAG, "✅ Navigated to Snapshots via NavigationManager")
                    return
                }
            }

            Log.w(TAG, "Using FragmentManager fallback for Snapshots")
            requireActivity().supportFragmentManager
                .beginTransaction()
                .replace(android.R.id.content, MiraSnapshotsFragment(), "Snapshots")
                .addToBackStack("Snapshots")
                .commit()
        } catch (e: Exception) {
            Log.e(TAG, "navigateToSnapshots error", e)
            showToast(if (isArabic) "⚠️ خطأ في التنقل" else "⚠️ Navigation error")
        }
    }

    // ================================================================
    //  Print (Full A4 HTML Report)
    // ================================================================

    private fun printCurrentReport() {
        if (!isAdded || isFragmentDestroyed) return

        try {
            val html = buildReportHtml()
            val title = if (isArabic) "تقرير الجرد" else "Inventory Report"

            val intent = Intent(requireContext(), InventoryReportPreviewActivity::class.java).apply {
                putExtra("report_html", html)
                putExtra("report_title", title)
            }
            startActivity(intent)
            Log.d(TAG, "✅ Report preview opened")
        } catch (e: Exception) {
            Log.e(TAG, "printCurrentReport error", e)
            showToast(if (isArabic) "⚠️ فشل فتح المعاينة" else "⚠️ Preview failed")
        }
    }

    private fun buildReportHtml(): String {
        if (!isAdded || context == null) return ""

        val tTitle = getString(R.string.report_title)
        val tSubtitle = getString(R.string.report_subtitle)
        val tBrand = getString(R.string.report_brand)
        val tSession = getString(R.string.report_session_id)
        val tOperator = getString(R.string.report_operator)
        val tDate = getString(R.string.report_date)
        val tTime = getString(R.string.report_time)
        val tBranch = getString(R.string.report_branch)
        val tMode = getString(R.string.report_mode)
        val tModeFull = getString(R.string.report_mode_full)
        val tModeBlind = getString(R.string.report_mode_blind)

        val tScanned = getString(R.string.report_stat_scanned)
        val tExpected = getString(R.string.report_stat_expected)
        val tAccuracy = getString(R.string.report_stat_accuracy)
        val tVariance = getString(R.string.report_stat_variance)

        val tTotalWeight = getString(R.string.report_total_weight)
        val tTotalValue = getString(R.string.report_total_value)
        val tTotalReads = getString(R.string.report_total_reads)
        val tMissing = getString(R.string.report_missing)

        val tItemsDetails = getString(R.string.report_items_details)
        val tColNum = getString(R.string.report_col_num)
        val tColName = getString(R.string.report_col_name)
        val tColEpc = getString(R.string.report_col_epc)
        val tColKarat = getString(R.string.report_col_karat)
        val tColWeight = getString(R.string.report_col_weight)
        val tColValue = getString(R.string.report_col_value)
        val tColCount = getString(R.string.report_col_count)

        val tFooterNote = getString(R.string.report_footer_note)
        val tSignOperator = getString(R.string.report_sign_operator)
        val tSignManager = getString(R.string.report_sign_manager)
        val tSignOfficial = getString(R.string.report_sign_official)
        val tSystemUser = getString(R.string.report_system_user)

        val tDir = if (isArabic) "rtl" else "ltr"
        val tAlign = if (isArabic) "right" else "left"
        val tOppositeAlign = if (isArabic) "left" else "right"
        val tBorderSide = if (isArabic) "right" else "left"

        val scanned = getEffectiveScannedCount()
        var calculatedReads = 0
        synchronized(scannedItemsDisplayList) {
            for (item in scannedItemsDisplayList) {
                calculatedReads += item?.count ?: 0
            }
        }
        val totalReads = maxOf(inventoryEngine?.totalReads ?: 0, calculatedReads)
        val missing = maxOf(0, expectedCount - scanned)
        val variance = scanned - expectedCount
        val accuracy = if (expectedCount > 0) {
            minOf(100, (scanned * 100) / expectedCount)
        } else {
            100
        }

        val sessionId = inventoryEngine?.clientSessionId ?: "SESS-LOCAL"
        val branchName = branchesManager?.activeBranchDisplayName ?: "—"
        val userName = getCurrentUserName().ifEmpty { tSystemUser }

        val dateOnly: String
        val timeOnly: String
        try {
            val now = Date()
            dateOnly = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
            timeOnly = SimpleDateFormat("HH:mm:ss", Locale.US).format(now)
        } catch (_: Exception) {
            dateOnly = "—"
            timeOnly = "—"
        }

        val reportNumber = "RPT-" + (System.currentTimeMillis() % 1000000L)
        val modeLabel = when {
            !selectedCategoryFilter.isNullOrEmpty() -> escapeHtml(selectedCategoryFilter)
            expectedCount == 0 -> tModeBlind
            else -> tModeFull
        }

        val varianceClass = when {
            variance == 0 -> "green"
            variance > 0 -> "gold"
            else -> "red"
        }
        val varianceText = when {
            variance == 0 -> "0"
            variance > 0 -> "+$variance"
            else -> variance.toString()
        }

        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n")
            .append("<html dir=\"").append(tDir).append("\" lang=\"").append(if (isArabic) "ar" else "en").append("\">\n")
            .append("<head>\n")
            .append("<meta charset=\"UTF-8\">\n")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
            .append("<title>").append(tTitle).append("</title>\n")
            .append("<style>\n")
            .append("* { margin: 0; padding: 0; box-sizing: border-box; }\n")
            .append("@page { size: A4; margin: 15mm 10mm; }\n")
            .append("body { font-family: 'Cairo', 'Segoe UI', Arial, sans-serif; ")
            .append("       color: #1A1200; background: #FFFFFF; padding: 0; ")
            .append("       direction: ").append(tDir).append("; ")
            .append("       font-size: 12px; line-height: 1.5; }\n")
            .append(".report-container { border: 2px solid #B89456; border-radius: 8px; padding: 0; overflow: hidden; }\n")
            .append(".header { background: linear-gradient(135deg, #F5E4B0, #E5C158); ")
            .append("  padding: 20px 25px; border-bottom: 3px double #8B6914; ")
            .append("  display: flex; justify-content: space-between; align-items: center; }\n")
            .append(".header-left { text-align: ").append(tAlign).append("; }\n")
            .append(".header-left .logo { font-size: 28px; font-weight: 900; color: #3D2E00; ")
            .append("  letter-spacing: 2px; line-height: 1.1; direction: ltr; }\n")
            .append(".header-left .logo-en { font-size: 11px; font-weight: bold; color: #6B5A28; ")
            .append("  letter-spacing: 4px; margin-top: 3px; direction: ltr; ")
            .append("  text-align: ").append(tAlign).append("; }\n")
            .append(".header-left .branch { font-size: 10px; color: #6B5A28; margin-top: 8px; ")
            .append("  padding-top: 6px; border-top: 1px solid #8B6914; }\n")
            .append(".header-right { text-align: ").append(tOppositeAlign).append("; }\n")
            .append(".header-right .title { font-size: 18px; font-weight: 900; color: #3D2E00; }\n")
            .append(".header-right .subtitle { font-size: 11px; color: #6B5A28; margin-top: 3px; }\n")
            .append(".header-right .report-num { display: inline-block; background: #3D2E00; color: #F5D97A; ")
            .append("  padding: 4px 12px; border-radius: 12px; font-size: 10px; ")
            .append("  font-weight: bold; margin-top: 8px; letter-spacing: 1px; ")
            .append("  font-family: monospace; direction: ltr; }\n")
            .append(".meta { display: grid; grid-template-columns: 1fr 1fr; ")
            .append("  gap: 0; padding: 15px 25px; background: #FAF7F0; ")
            .append("  border-bottom: 1px solid #E8DFC8; }\n")
            .append(".meta-item { padding: 6px 0; font-size: 11px; }\n")
            .append(".meta-item .label { color: #6B5A28; font-weight: bold; ")
            .append("  display: inline-block; min-width: 90px; }\n")
            .append(".meta-item .value { color: #1A1200; font-weight: 600; }\n")
            .append(".ltr-inline { direction: ltr; unicode-bidi: embed; ")
            .append("  display: inline-block; font-family: monospace; }\n")
            .append(".stats { display: grid; grid-template-columns: repeat(4, 1fr); ")
            .append("  gap: 0; border-bottom: 1px solid #E8DFC8; }\n")
            .append(".stat { text-align: center; padding: 18px 10px; ")
            .append("  background: #FFFDF5; border-").append(tBorderSide).append(": 1px solid #E8DFC8; }\n")
            .append(".stat:first-child { border-").append(tBorderSide).append(": none; }\n")
            .append(".stat .value { font-size: 24px; font-weight: 900; color: #3D2E00; line-height: 1; }\n")
            .append(".stat .value.gold { color: #B89456; }\n")
            .append(".stat .value.green { color: #16A34A; }\n")
            .append(".stat .value.red { color: #DC2626; }\n")
            .append(".stat .label { font-size: 10px; color: #6B5A28; margin-top: 6px; font-weight: bold; letter-spacing: 0.5px; }\n")
            .append(".summary { display: grid; grid-template-columns: repeat(4, 1fr); ")
            .append("  gap: 0; padding: 12px 25px; background: #FAF7F0; border-bottom: 2px solid #B89456; }\n")
            .append(".summary-item { text-align: center; font-size: 11px; }\n")
            .append(".summary-item .label { color: #6B5A28; font-size: 9px; font-weight: bold; display: block; margin-bottom: 4px; letter-spacing: 0.5px; }\n")
            .append(".summary-item .value { color: #1A1200; font-weight: 900; font-size: 13px; direction: ltr; display: inline-block; }\n")
            .append(".table-section { padding: 20px 25px; }\n")
            .append(".table-title { font-size: 14px; font-weight: 900; color: #3D2E00; margin-bottom: 10px; padding-bottom: 8px; ")
            .append("  border-bottom: 2px solid #B89456; text-align: ").append(tAlign).append("; }\n")
            .append("table { width: 100%; border-collapse: collapse; font-size: 10px; }\n")
            .append("th { background: linear-gradient(135deg, #E5C158, #B89456); color: #FFFFFF; padding: 10px 6px; font-weight: bold; ")
            .append("  text-align: center; border: 1px solid #8B6914; font-size: 10px; letter-spacing: 0.5px; }\n")
            .append("td { padding: 8px 6px; border: 1px solid #E8DFC8; text-align: center; font-size: 10px; }\n")
            .append("tr:nth-child(even) td { background: #FDFCF8; }\n")
            .append("td.epc { font-family: 'Courier New', monospace; font-size: 8px; direction: ltr; text-align: left; letter-spacing: 0.3px; }\n")
            .append("td.name { text-align: ").append(tAlign).append("; font-weight: 600; }\n")
            .append("td.value { font-weight: 900; color: #B89456; direction: ltr; }\n")
            .append("td.count { font-weight: 900; color: #3D2E00; }\n")
            .append(".badge-note { display: inline-block; font-size: 8px; padding: 1px 5px; border-radius: 4px; background: #FEF3C7; color: #92400E; margin-top: 2px; }\n")
            .append(".footer { background: linear-gradient(135deg, #F5E4B0, #E5C158); padding: 15px 25px; text-align: center; ")
            .append("  border-top: 3px double #8B6914; font-size: 10px; color: #3D2E00; line-height: 1.7; }\n")
            .append(".footer .brand { font-weight: 900; letter-spacing: 2px; color: #3D2E00; direction: ltr; display: inline-block; }\n")
            .append(".footer .signature { display: flex; justify-content: space-around; margin-top: 15px; padding-top: 12px; border-top: 1px dashed #8B6914; }\n")
            .append(".footer .signature-item { text-align: center; }\n")
            .append(".footer .signature-item .line { width: 100px; height: 1px; background: #3D2E00; margin: 20px auto 5px; }\n")
            .append(".footer .signature-item .label { font-size: 9px; font-weight: bold; color: #6B5A28; }\n")
            .append("@media print { body { padding: 0; } .report-container { border: 2px solid #B89456; } .stat, th { -webkit-print-color-adjust: exact; print-color-adjust: exact; } }\n")
            .append("</style>\n</head>\n<body>\n")

        sb.append("<div class=\"report-container\">\n")
            .append("  <div class=\"header\">\n")
            .append("    <div class=\"header-left\">\n")
            .append("      <div class=\"logo\">MIRA</div>\n")
            .append("      <div class=\"logo-en\">JEWELRY ENTERPRISE™</div>\n")
            .append("      <div class=\"branch\">📍 ").append(escapeHtml(branchName)).append("</div>\n")
            .append("    </div>\n")
            .append("    <div class=\"header-right\">\n")
            .append("      <div class=\"title\">").append(tTitle).append("</div>\n")
            .append("      <div class=\"subtitle\">").append(tSubtitle).append("</div>\n")
            .append("      <div class=\"report-num\">").append(reportNumber).append("</div>\n")
            .append("    </div>\n")
            .append("  </div>\n")

        sb.append("  <div class=\"meta\">\n")
            .append("    <div class=\"meta-item\"><span class=\"label\">").append(tSession).append(":</span> <span class=\"value ltr-inline\">").append(escapeHtml(sessionId)).append("</span></div>\n")
            .append("    <div class=\"meta-item\"><span class=\"label\">").append(tOperator).append(":</span> <span class=\"value\">").append(escapeHtml(userName)).append("</span></div>\n")
            .append("    <div class=\"meta-item\"><span class=\"label\">").append(tDate).append(":</span> <span class=\"value ltr-inline\">").append(dateOnly).append("</span></div>\n")
            .append("    <div class=\"meta-item\"><span class=\"label\">").append(tTime).append(":</span> <span class=\"value ltr-inline\">").append(timeOnly).append("</span></div>\n")
            .append("    <div class=\"meta-item\"><span class=\"label\">").append(tBranch).append(":</span> <span class=\"value\">").append(escapeHtml(branchName)).append("</span></div>\n")
            .append("    <div class=\"meta-item\"><span class=\"label\">").append(tMode).append(":</span> <span class=\"value\">").append(modeLabel).append("</span></div>\n")
            .append("  </div>\n")

        sb.append("  <div class=\"stats\">\n")
            .append("    <div class=\"stat\"><div class=\"value\">").append(scanned).append("</div><div class=\"label\">").append(tScanned).append("</div></div>\n")
            .append("    <div class=\"stat\"><div class=\"value\">").append(expectedCount).append("</div><div class=\"label\">").append(tExpected).append("</div></div>\n")
            .append("    <div class=\"stat\"><div class=\"value gold\">").append(accuracy).append("%</div><div class=\"label\">").append(tAccuracy).append("</div></div>\n")
            .append("    <div class=\"stat\"><div class=\"value ").append(varianceClass).append("\">").append(varianceText).append("</div><div class=\"label\">").append(tVariance).append("</div></div>\n")
            .append("  </div>\n")

        sb.append("  <div class=\"summary\">\n")
            .append("    <div class=\"summary-item\"><span class=\"label\">").append(tTotalWeight).append("</span><span class=\"value\">")
            .append(String.format(Locale.US, "%.2f g", aggregateTotalWeight)).append("</span></div>\n")
            .append("    <div class=\"summary-item\"><span class=\"label\">").append(tTotalValue).append("</span><span class=\"value\" style=\"color: #B89456;\">")
            .append(String.format(Locale.US, "$%,.2f", aggregateTotalValue)).append("</span></div>\n")
            .append("    <div class=\"summary-item\"><span class=\"label\">").append(tTotalReads).append("</span><span class=\"value\">").append(totalReads).append("</span></div>\n")
            .append("    <div class=\"summary-item\"><span class=\"label\">").append(tMissing).append("</span><span class=\"value\" style=\"color: ")
            .append(if (missing > 0) "#DC2626" else "#16A34A").append(";\">").append(missing).append("</span></div>\n")
            .append("  </div>\n")

        if (scannedItemsDisplayList.isNotEmpty()) {
            sb.append("  <div class=\"table-section\">\n")
                .append("    <div class=\"table-title\">📋 ").append(tItemsDetails).append(" (").append(scannedItemsDisplayList.size).append(")</div>\n")
                .append("    <table>\n<thead>\n<tr>\n")
                .append("      <th style=\"width: 4%;\">").append(tColNum).append("</th>\n")
                .append("      <th style=\"width: 22%;\">").append(tColName).append("</th>\n")
                .append("      <th style=\"width: 30%;\">").append(tColEpc).append("</th>\n")
                .append("      <th style=\"width: 8%;\">").append(tColKarat).append("</th>\n")
                .append("      <th style=\"width: 10%;\">").append(tColWeight).append("</th>\n")
                .append("      <th style=\"width: 12%;\">").append(tColValue).append("</th>\n")
                .append("      <th style=\"width: 7%;\">").append(tColCount).append("</th>\n")
                .append("    </tr>\n</thead>\n<tbody>\n")

            var index = 1
            synchronized(scannedItemsDisplayList) {
                for (item in scannedItemsDisplayList) {
                    if (item == null) continue
                    val statusTag = itemStatusOverrides[item.epc]
                    val damageTag = itemDamageNotes[item.epc]
                    val noteBadge = buildString {
                        if (!statusTag.isNullOrEmpty()) {
                            append("<br><span class=\"badge-note\">").append(escapeHtml(statusTag)).append("</span>")
                        }
                        if (!damageTag.isNullOrEmpty()) {
                            append("<br><span class=\"badge-note\">⚠️ ").append(escapeHtml(damageTag)).append("</span>")
                        }
                    }

                    sb.append("      <tr>\n")
                        .append("        <td>").append(index++).append("</td>\n")
                        .append("        <td class=\"name\">").append(escapeHtml(item.title)).append(noteBadge).append("</td>\n")
                        .append("        <td class=\"epc\">").append(escapeHtml(item.epc)).append("</td>\n")
                        .append("        <td>").append(escapeHtml(item.karat)).append("</td>\n")
                        .append("        <td>").append(String.format(Locale.US, "%.2f", item.weight)).append("</td>\n")
                        .append("        <td class=\"value\">").append(String.format(Locale.US, "$%,.2f", item.price)).append("</td>\n")
                        .append("        <td class=\"count\">").append(item.count).append("</td>\n")
                        .append("      </tr>\n")
                }
            }
            sb.append("    </tbody>\n</table>\n</div>\n")
        }

        sb.append("  <div class=\"footer\">\n")
            .append("    <div class=\"brand\">").append(tBrand).append("</div>\n")
            .append("    <div style=\"margin-top: 5px;\">").append(tFooterNote)
            .append(" • <span class=\"ltr-inline\">").append(dateOnly).append(" ").append(timeOnly).append("</span></div>\n")
            .append("    <div class=\"signature\">\n")
            .append("      <div class=\"signature-item\"><div class=\"line\"></div><div class=\"label\">").append(tSignOperator).append("</div></div>\n")
            .append("      <div class=\"signature-item\"><div class=\"line\"></div><div class=\"label\">").append(tSignManager).append("</div></div>\n")
            .append("      <div class=\"signature-item\"><div class=\"line\"></div><div class=\"label\">").append(tSignOfficial).append("</div></div>\n")
            .append("    </div>\n")
            .append("  </div>\n")
            .append("</div>\n")
            .append("</body>\n</html>")

        return sb.toString()
    }

    private fun escapeHtml(text: String?): String {
        if (text == null) return ""
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun printSingleItemLabel(item: ScannedItemDisplay?) {
        if (printManager != null && item != null) {
            printManager?.printSingleItemLabel(item)
        }
    }

    // ================================================================
    //  Items List Dialog (Pagination)
    // ================================================================

    private fun showItemsListDialog() {
        try {
            if (!isAdded || isFragmentDestroyed) return

            val totalCount = scannedItemsDisplayList.size
            if (totalCount == 0) {
                showToast(if (isArabic) "لا توجد مسوحات بعد" else "No scans yet")
                return
            }

            if (totalCount <= ITEMS_LIST_PAGE_SIZE) {
                showItemsListPage(0, totalCount)
                return
            }

            val pages = kotlin.math.ceil(totalCount.toDouble() / ITEMS_LIST_PAGE_SIZE).toInt()
            val options = Array(pages) { i ->
                val start = i * ITEMS_LIST_PAGE_SIZE + 1
                val end = minOf((i + 1) * ITEMS_LIST_PAGE_SIZE, totalCount)
                if (isArabic) {
                    String.format(Locale.US, "📋 القطع %d - %d (من %d)", start, end, totalCount)
                } else {
                    String.format(Locale.US, "📋 Items %d - %d (of %d)", start, end, totalCount)
                }
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle((if (isArabic) "📋 الممسوحات (" else "📋 Scans (") + "$totalCount)")
                .setItems(options) { _, which ->
                    val startIndex = which * ITEMS_LIST_PAGE_SIZE
                    val endIndex = minOf(startIndex + ITEMS_LIST_PAGE_SIZE, totalCount)
                    showItemsListPage(startIndex, endIndex)
                }
                .setNegativeButton(if (isArabic) "إغلاق" else "Close", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "showItemsListDialog error", e)
        }
    }

    private fun showItemsListPage(startIndex: Int, endIndex: Int) {
        try {
            if (!isAdded || isFragmentDestroyed) return
            if (startIndex < 0 || endIndex > scannedItemsDisplayList.size) return

            val pageSize = endIndex - startIndex
            if (pageSize <= 0) return

            val titles = Array(pageSize) { "" }
            val pageItems = ArrayList<ScannedItemDisplay>(pageSize)

            for (i in 0 until pageSize) {
                val item = scannedItemsDisplayList[startIndex + i]
                pageItems.add(item)

                val safeTitle = if (!item.title.isNullOrEmpty()) item.title
                else if (isArabic) "قطعة غير معروفة" else "Unknown item"

                val safeEpc = if (!item.epc.isNullOrEmpty()) formatEpcShort(item.epc) else "—"
                val safeKarat = if (!item.karat.isNullOrEmpty()) item.karat else "—"

                titles[i] = String.format(
                    Locale.US,
                    "%s\n%s • %s • %.3fg • 📶%d",
                    safeTitle,
                    safeEpc,
                    safeKarat,
                    item.weight,
                    item.rssi
                )
            }

            val rangeTitle = (if (isArabic) "📋 الممسوحات (" else "📋 Scans (") +
                    "${startIndex + 1}-$endIndex" +
                    (if (isArabic) " من " else " of ") +
                    "${scannedItemsDisplayList.size})"

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(rangeTitle)
                .setItems(titles) { _, which ->
                    if (which in pageItems.indices) {
                        dialogsManager?.showItemOptionsDialog(pageItems[which])
                    }
                }
                .setPositiveButton(if (isArabic) "رجوع" else "Back") { _, _ ->
                    showItemsListDialog()
                }
                .setNegativeButton(if (isArabic) "إغلاق" else "Close", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "showItemsListPage error", e)
        }
    }

    // ================================================================
    //  Power / Category / Filter
    // ================================================================

    private fun showPowerControlSheet() {
        dialogsManager?.apply {
            setCurrentPowerDbm(currentPowerDbm)
            showPowerControlSheet()
        }
    }

    private fun applyAntennaPower(dbm: Int) {
        try {
            val rfidManager = RFIDManager.getInstance(requireContext())
            if (rfidManager == null) {
                Log.w(TAG, "RFIDManager is null")
                return
            }

            val success = rfidManager.setPower(dbm)
            addDebugLine("⚡ setPower $dbm → $success")

            if (success) {
                Log.i(TAG, "✅ Power applied: $dbm dBm")
                showToast(
                    if (isArabic) "✅ تم ضبط القدرة على $dbm dBm"
                    else "✅ Power set to $dbm dBm"
                )
            } else {
                Log.w(TAG, "⚠️ Power apply failed")
                showToast(
                    if (isArabic) "⚠️ فشل ضبط القدرة — تحقق من الاتصال بالقارئ"
                    else "⚠️ Power control failed — check reader"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyAntennaPower error", e)
            showToast(if (isArabic) "⚠️ خطأ في ضبط القدرة" else "⚠️ Power control error")
        }
    }

    private fun showCategorySelectionDialog() {
        if (!isAdded || isFragmentDestroyed) return

        val categoriesAr = arrayOf(
            "الكل", "عيار 24", "عيار 22", "عيار 21", "عيار 18", "عيار 14",
            "سبائك", "أونصة", "فضة", "ألماس", "أخرى"
        )
        val categoriesEn = arrayOf(
            "All", "24K", "22K", "21K", "18K", "14K",
            "Bullion", "Ounce", "Silver", "Diamond", "Other"
        )
        val categories = if (isArabic) categoriesAr else categoriesEn

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isArabic) "🎯 اختر الفئة للجرد" else "🎯 Select Category")
            .setItems(categories) { _, which ->
                applyCategoryFilter(categories[which], which)
            }
            .setNegativeButton(if (isArabic) "إلغاء" else "Cancel") { _, _ ->
                btnModeFull?.let { segmentedModes?.check(R.id.btnModeFull) }
            }
            .show()
    }

    private fun applyCategoryFilter(category: String, index: Int) {
        try {
            if (index == 0) {
                selectedCategoryFilter = null
                showToast(if (isArabic) "✅ وضع الجرد الكامل" else "✅ Full inventory mode")
                return
            }

            selectedCategoryFilter = category
            showToast(if (isArabic) "🎯 جرد فئوي: $category" else "🎯 Category: $category")

            if (isScanning) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(if (isArabic) "⚠️ إعادة التشغيل" else "⚠️ Restart")
                    .setMessage(
                        if (isArabic) "لتطبيق الفئة، يجب إيقاف الجرد الحالي وإعادة تشغيله. هل تريد المتابعة؟"
                        else "To apply the category, current inventory must be stopped and restarted. Continue?"
                    )
                    .setPositiveButton(if (isArabic) "متابعة" else "Continue") { _, _ ->
                        stopScanning()
                        mainHandler.postDelayed({ startCategoryInventory(category) }, 500L)
                    }
                    .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
                    .show()
            } else {
                startCategoryInventory(category)
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyCategoryFilter error", e)
        }
    }

    private fun showLiveFilterChips() {
        try {
            val fMgr = filterManager ?: return
            if (!isAdded || isFragmentDestroyed) return

            val filters = fMgr.filterOptions

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(fMgr.buildFilterDialogTitle())
                .setItems(filters) { _, which -> applyLiveFilter(which) }
                .setNegativeButton(if (isArabic) "إغلاق" else "Close", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "showLiveFilterChips error", e)
        }
    }

    private fun applyLiveFilter(filterType: Int) {
        try {
            val fMgr = filterManager ?: return
            if (!isAdded || isFragmentDestroyed) return

            currentLiveFilter = filterType
            fMgr.setCurrentFilter(filterType)

            if (filterType == InventoryFilterManager.FILTER_MISSING) {
                showMissingItemsDialog()
                return
            }

            updateAdapterList()

            showToast(
                if (isArabic) "🎯 الفلتر: ${fMgr.currentFilterName}"
                else "🎯 Filter: ${fMgr.currentFilterName}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "applyLiveFilter error", e)
        }
    }

    private fun showMissingItemsDialog() {
        try {
            val fMgr = filterManager ?: return
            val cMgr = catalogManager ?: return
            if (!isAdded || isFragmentDestroyed) return

            if (cMgr.isEmpty) {
                showToast(fMgr.buildNoCatalogMessage())
                return
            }

            val missingEpcs = fMgr.getMissingItems(catalogEpcs, scannedItemsDisplayList)
            if (missingEpcs.isEmpty()) {
                showToast(fMgr.buildNoMissingMessage())
                return
            }

            val missingTitles = fMgr.buildMissingItemsList(missingEpcs, cMgr.catalogItemsMap)

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(fMgr.buildMissingItemsTitle(missingEpcs.size))
                .setItems(missingTitles, null)
                .setPositiveButton(if (isArabic) "موافق" else "OK", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "showMissingItemsDialog error", e)
        }
    }

    // ================================================================
    //  Session Save / Restore
    // ================================================================

    private fun checkAndRestoreSession() {
        val sMgr = sessionManager ?: return
        if (!isAdded || isFragmentDestroyed) return

        if (hasRestoredThisSession || scannedItemsDisplayList.isNotEmpty()) {
            Log.d(TAG, "⏭️ Already restored — skip checkAndRestoreSession")
            return
        }

        sMgr.tryRestoreSession(object : InventorySessionManager.SessionCallback {
            override fun onSessionFound(
                items: JSONArray?,
                expectedCount: Int,
                category: String?,
                timeAgoMillis: Long
            ) {
                if (!isAdded || isFragmentDestroyed) return
                hasRestoredThisSession = true
                showRestoreSessionDialog(items, expectedCount, category, timeAgoMillis)
            }

            override fun onNoSession() {
                Log.d(TAG, "No previous session found")
            }
        })
    }

    private fun showRestoreSessionDialog(
        items: JSONArray?,
        expectedCount: Int,
        category: String?,
        timeAgoMillis: Long
    ) {
        if (!isAdded || isFragmentDestroyed) return

        val message = sessionManager?.buildRestoreMessage(items, timeAgoMillis).orEmpty()

        val dontAskAgain = CheckBox(requireContext()).apply {
            text = if (isArabic) "لا تسألني مجدداً عن هذه الجلسة" else "Don't ask again for this session"
            setPadding(40, 20, 40, 20)
        }

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
            addView(dontAskAgain)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isArabic) "🔄 استرجاع جلسة سابقة" else "🔄 Restore Previous Session")
            .setMessage(message)
            .setView(container)
            .setPositiveButton(if (isArabic) "✅ استرجاع" else "✅ Restore") { _, _ ->
                if (dontAskAgain.isChecked) saveSkipRestorePrompt(true)
                applyRestoredSession(items, expectedCount, category)
            }
            .setNegativeButton(if (isArabic) "بدء جديد" else "Start New") { _, _ ->
                if (dontAskAgain.isChecked) saveSkipRestorePrompt(true)
                sessionManager?.clearSession()
                showToast(if (isArabic) "✨ بدء جلسة جديدة" else "✨ Started fresh session")
            }
            .setNeutralButton(if (isArabic) "لاحقاً" else "Later", null)
            .setCancelable(true)
            .show()
    }

    private fun saveSkipRestorePrompt(skip: Boolean) {
        try {
            context?.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                ?.edit()
                ?.putBoolean(KEY_SKIP_RESTORE_PROMPT, skip)
                ?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveSkipRestorePrompt error", e)
        }
    }

    private fun resetSkipRestorePrompt() {
        try {
            context?.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                ?.edit()
                ?.putBoolean(KEY_SKIP_RESTORE_PROMPT, false)
                ?.apply()
        } catch (_: Exception) {
        }
    }

    private fun applyRestoredSession(items: JSONArray?, expectedCount: Int, category: String?) {
        if (items == null) return

        try {
            Log.d(TAG, "🔄 Restoring ${items.length()} items...")

            var totalWeight = 0.0
            var totalValue = 0.0

            synchronized(scannedItemsDisplayList) {
                scannedItemsDisplayList.clear()
                for (i in 0 until items.length()) {
                    try {
                        val itemObj = items.getJSONObject(i)
                        val item = ScannedItemDisplay(
                            itemObj.optString("epc", ""),
                            itemObj.optString("title", ""),
                            itemObj.optString("karat", "—"),
                            maxOf(1, itemObj.optInt("count", 1)),
                            itemObj.optInt("rssi", -50),
                            itemObj.optString("time", "")
                        ).apply {
                            weight = itemObj.optDouble("weight", 0.0)
                            price = itemObj.optDouble("price", 0.0)
                        }

                        totalWeight += item.weight
                        totalValue += item.price
                        scannedItemsDisplayList.add(item)
                    } catch (e: Exception) {
                        Log.w(TAG, "Item parse error at $i", e)
                    }
                }
            }

            aggregateTotalWeight = totalWeight
            aggregateTotalValue = totalValue

            // مزامنة المحرك مع العناصر المسترجعة لضمان دقة الاعتماد والتقرير
            syncEngineWithRestoredItems(scannedItemsDisplayList)

            if (modernAdapter != null) {
                updateAdapterList()
                modernAdapter?.resetAnimationTracker()
            }

            if (scannedItemsDisplayList.isNotEmpty()) {
                switchToActiveState(false)
                hasUncommittedSession = true
                isPaused = true
                shouldResumeOnNextStart = true
            }

            this.expectedCount = expectedCount
            tvExpectedCount?.text = if (expectedCount > 0) expectedCount.toString() else "—"

            selectedCategoryFilter = if (category.isNullOrEmpty()) null else category
            lastScannedForAnimation = 0

            updateUI()
            updateTotalWeightAndValue()

            tvTotalWeight?.text = String.format(Locale.US, "%.2f g", totalWeight)
            tvTotalValue?.text = String.format(Locale.US, "$%,.2f", totalValue)
            tvScannedCount?.text = scannedItemsDisplayList.size.toString()
            tvItemCount?.text = scannedItemsDisplayList.size.toString()
            tvEmptyState?.visibility = if (scannedItemsDisplayList.isEmpty()) View.VISIBLE else View.GONE

            lastServerReport = null

            btnGenerateReport?.apply {
                isEnabled = true
                alpha = 1.0f
            }
            btnCommitSession?.apply {
                text = if (isArabic) "اعتماد" else "Commit"
                isEnabled = true
                alpha = 1.0f
            }

            rvInventoryItems?.scrollToPosition(0)
            updateButtonsState()

            showToast(
                if (isArabic) "✅ تم استرجاع ${scannedItemsDisplayList.size} قطعة"
                else "✅ Restored ${scannedItemsDisplayList.size} items"
            )

            soundManager?.playBeep(SoundManager.FeedbackType.COMPLETED)

            Log.d(
                TAG,
                "✅ Session restored: ${scannedItemsDisplayList.size} items, " +
                        String.format(Locale.US, "%.2f g, $%.2f", totalWeight, totalValue)
            )
        } catch (e: Exception) {
            Log.e(TAG, "applyRestoredSession error", e)
            showToast(if (isArabic) "❌ فشل استرجاع الجلسة" else "❌ Restore failed")
        }
    }

    private fun confirmResetSession() {
        if (!isAdded || isFragmentDestroyed) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isArabic) "⚠️ تأكيد" else "⚠️ Confirm")
            .setMessage(
                if (isArabic) "هل أنت متأكد من إعادة تعيين الجلسة؟\nسيتم حذف كل البيانات غير المعتمدة."
                else "Are you sure you want to reset the session?\nAll uncommitted data will be lost."
            )
            .setPositiveButton(if (isArabic) "إعادة تعيين" else "Reset") { _, _ ->
                try {
                    try {
                        inventoryEngine?.clearSession()
                    } catch (_: Exception) {
                    }
                    try {
                        scanSession?.onSessionReset()
                    } catch (_: Exception) {
                    }

                    clearAllLists()

                    isPaused = false
                    hasUncommittedSession = false
                    nextScanIsResume = false
                    shouldResumeOnNextStart = false
                    sessionElapsedBeforePause = 0L
                    hasCelebrated = false
                    hasRestoredThisSession = false
                    hasTriedRestoringSession = false

                    sessionManager?.clearSession()

                    expectedCount = 0
                    selectedCategoryFilter = null
                    lastScannedForAnimation = 0

                    tvExpectedCount?.text = "0"
                    btnCommitSession?.text = if (isArabic) "اعتماد" else "Commit"
                    btnGenerateReport?.text = if (isArabic) "📋 تقرير" else "📋 Report"

                    mainHandler.postDelayed({
                        if (isAdded && !isFragmentDestroyed) {
                            updateUI()
                            updateTotalWeightAndValue()
                            updateButtonsState()
                        }
                    }, 100L)

                    showToast(if (isArabic) "✅ تم إعادة التعيين" else "✅ Reset successfully")
                    switchToWelcomeState()
                } catch (e: Exception) {
                    Log.e(TAG, "Reset error", e)
                    showToast(if (isArabic) "❌ خطأ في إعادة التعيين" else "❌ Reset error")
                }
            }
            .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
            .show()
    }

    // ================================================================
    //  Utilities & Snapshots
    // ================================================================

    private fun showToast(msg: String?) {
        if (msg.isNullOrEmpty()) return
        try {
            if (isAdded && context != null) {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
        }
    }

    private fun getCurrentTimeString(): String {
        return try {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        } catch (_: Exception) {
            ""
        }
    }

    private fun getCurrentDateTimeString(): String {
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        } catch (_: Exception) {
            ""
        }
    }

    private fun takeSnapshot() {
        val snapMgr = snapshotManager
        if (snapMgr == null) {
            showToast(if (isArabic) "⚠️ مدير اللقطات غير جاهز" else "⚠️ Snapshot manager not ready")
            return
        }

        val scanned = getEffectiveScannedCount()
        if (scanned == 0) {
            showToast(if (isArabic) "⚠️ لا توجد مسوحات لحفظها" else "⚠️ No scans to save")
            return
        }

        val input = EditText(requireContext()).apply {
            hint = if (isArabic) "اسم اللقطة (اختياري)" else "Snapshot name (optional)"
            setPadding(40, 40, 40, 40)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isArabic) "📸 حفظ لقطة" else "📸 Save Snapshot")
            .setMessage(
                if (isArabic) "سيتم حفظ $scanned قطعة في لقطة جديدة"
                else "Will save $scanned items"
            )
            .setView(input)
            .setPositiveButton(if (isArabic) "حفظ" else "Save") { _, _ ->
                val name = input.text.toString().trim()
                val branchName = branchesManager?.activeBranchDisplayName.orEmpty()
                val mode = if (!selectedCategoryFilter.isNullOrEmpty()) "category" else "full"
                val user = getCurrentUserName()
                val sessionId = inventoryEngine?.clientSessionId.orEmpty()

                ioExecutor?.execute {
                    try {
                        val snapshot = snapMgr.createSnapshot(
                            sessionId,
                            scannedItemsDisplayList,
                            expectedCount,
                            branchName,
                            mode,
                            user,
                            name.ifEmpty { null }
                        )
                        val saved = snapMgr.saveSnapshot(snapshot)

                        mainHandler.post {
                            if (!isAdded || isFragmentDestroyed) return@post
                            if (saved) {
                                showToast(if (isArabic) "✅ تم حفظ اللقطة" else "✅ Snapshot saved")
                                soundManager?.playBeep(SoundManager.FeedbackType.COMPLETED)
                            } else {
                                showToast(if (isArabic) "❌ فشل الحفظ" else "❌ Save failed")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "takeSnapshot error", e)
                        mainHandler.post {
                            if (isAdded && !isFragmentDestroyed) {
                                showToast(if (isArabic) "❌ خطأ" else "❌ Error")
                            }
                        }
                    }
                }
            }
            .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
            .show()
    }

    private fun autoSaveSnapshotAfterCommit(serverReport: JSONObject?) {
        try {
            if (!autoSnapshotEnabled) return
            val snapMgr = snapshotManager ?: return
            val engine = inventoryEngine ?: return

            val scanned = getEffectiveScannedCount()
            if (scanned == 0) return

            val autoName = buildAutoSnapshotName()
            val branchName = branchesManager?.activeBranchDisplayName.orEmpty()
            val mode = if (!selectedCategoryFilter.isNullOrEmpty()) "category" else "full"
            val user = getCurrentUserName()

            ioExecutor?.execute {
                try {
                    val snapshot: InventorySnapshot = snapMgr.createSnapshot(
                        engine.clientSessionId,
                        scannedItemsDisplayList,
                        expectedCount,
                        branchName,
                        mode,
                        user,
                        autoName
                    )

                    val notes = StringBuilder().apply {
                        append(if (isArabic) "لقطة تلقائية بعد الاعتماد" else "Auto-snapshot after commit")
                        if (serverReport != null && serverReport.length() > 0) {
                            val accuracy = serverReport.optString("accuracy", "")
                            val risk = serverReport.optString("risk_level", "")
                            if (accuracy.isNotEmpty()) {
                                append(" • ").append(if (isArabic) "دقة: " else "Accuracy: ").append(accuracy).append("%")
                            }
                            if (risk.isNotEmpty() && risk != "unknown") {
                                append(" • ").append(if (isArabic) "خطر: " else "Risk: ").append(risk)
                            }
                        }
                    }
                    snapshot.notes = notes.toString()

                    val saved = snapMgr.saveSnapshot(snapshot)
                    mainHandler.post {
                        if (!isAdded || isFragmentDestroyed) return@post
                        if (saved) {
                            Log.i(TAG, "✅ Auto-snapshot saved: $autoName")
                            showToast(if (isArabic) "📸 تم حفظ لقطة تلقائية" else "📸 Auto-snapshot saved")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "autoSaveSnapshotAfterCommit error", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "autoSaveSnapshotAfterCommit outer error", e)
        }
    }

    private fun buildAutoSnapshotName(): String {
        return try {
            val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val branch = branchesManager?.activeBranchDisplayName.orEmpty()
            val modeLabel = when {
                !selectedCategoryFilter.isNullOrEmpty() -> selectedCategoryFilter!!
                expectedCount == 0 -> if (isArabic) "أعمى" else "Blind"
                else -> if (isArabic) "كامل" else "Full"
            }

            buildString {
                append(dateTime)
                if (branch.isNotEmpty()) {
                    append(" — ").append(branch)
                }
                append(" [").append(modeLabel).append("]")
            }
        } catch (e: Exception) {
            Log.e(TAG, "buildAutoSnapshotName error", e)
            "Auto-${System.currentTimeMillis()}"
        }
    }

    private fun getCurrentUserName(): String {
        return try {
            val sm = SessionManager(requireContext())
            sm.displayName ?: sm.username.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun getDeviceId(): String {
        return try {
            Settings.Secure.getString(
                requireContext().contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "UNKNOWN-DEVICE"
        } catch (_: Exception) {
            "UNKNOWN-DEVICE"
        }
    }

    private fun getActiveBranchId(): Int {
        return try {
            branchesManager?.activeBranchId ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun syncOfflineTransactions() {
        val pMgr = persistenceManager ?: return
        val api = apiClient ?: return

        pMgr.syncOfflineTransactions(api, object : InventoryPersistenceManager.SyncCallback {
            override fun onSyncComplete(syncedCount: Int) {
                if (syncedCount > 0) {
                    Log.i(TAG, "✅ Synced $syncedCount transactions")
                    mainHandler.post {
                        if (isAdded && !isFragmentDestroyed) {
                            showToast(
                                if (isArabic) "✅ تمت مزامنة $syncedCount معاملة"
                                else "✅ Synced $syncedCount transactions"
                            )
                        }
                    }
                }
            }

            override fun onSyncError(error: String?) {
                Log.w(TAG, "⚠️ Sync error: $error")
            }
        })
    }

    // تحديث حالة القطعة محلياً في القائمة وتقرير الطباعة (جاهزة للربط مع MiraApiClient لاحقاً)
    private fun updateItemStatus(epc: String?, status: String?) {
        if (epc.isNullOrEmpty() || status.isNullOrEmpty()) return
        Log.d(TAG, "Update status $epc -> $status")

        itemStatusOverrides[epc] = status
        updateAdapterList()

        if (scannedItemsDisplayList.isNotEmpty()) {
            sessionManager?.saveSession(
                expectedCount,
                scanStartTime,
                selectedCategoryFilter,
                scannedItemsDisplayList
            )
        }

        showToast(
            if (isArabic) "✅ تم تحديث حالة القطعة: $status"
            else "✅ Item status updated: $status"
        )
    }

    // تسجيل تلف القطعة محلياً في القائمة وتقرير الطباعة (جاهزة للربط مع MiraApiClient لاحقاً)
    private fun registerItemDamage(epc: String?, damageType: String?) {
        if (epc.isNullOrEmpty() || damageType.isNullOrEmpty()) return
        Log.d(TAG, "Register damage $epc -> $damageType")

        itemDamageNotes[epc] = damageType
        updateAdapterList()

        if (scannedItemsDisplayList.isNotEmpty()) {
            sessionManager?.saveSession(
                expectedCount,
                scanStartTime,
                selectedCategoryFilter,
                scannedItemsDisplayList
            )
        }

        showToast(
            if (isArabic) "⚠️ تم تسجيل ملاحظة تلف: $damageType"
            else "⚠️ Damage recorded: $damageType"
        )
    }

    // ================================================================
    //  Public APIs
    // ================================================================

    fun showInventoryScannerSettings() {
        try {
            if (isAdded && !isFragmentDestroyed) showPowerControlSheet()
        } catch (e: Exception) {
            Log.e(TAG, "showInventoryScannerSettings error", e)
        }
    }

    fun showInventorySessionSettings() {
        try {
            if (isAdded && !isFragmentDestroyed) {
                dialogsManager?.apply {
                    setExpectedCount(expectedCount)
                    showSessionSettingsDialog()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "showInventorySessionSettings error", e)
        }
    }

    fun showInventoryBuzzerSettings() {
        try {
            if (isAdded && !isFragmentDestroyed) {
                dialogsManager?.showBuzzerSettingsDialog()
            }
        } catch (e: Exception) {
            Log.e(TAG, "showInventoryBuzzerSettings error", e)
        }
    }

    fun getScannedCount(): Int {
        return try {
            getEffectiveScannedCount()
        } catch (_: Exception) {
            0
        }
    }

    fun isCurrentlyScanning(): Boolean = isScanning

    fun showArchiveReportsPublic() {
        try {
            if (isAdded && !isFragmentDestroyed) showArchiveReports()
        } catch (e: Exception) {
            Log.e(TAG, "showArchiveReportsPublic error", e)
        }
    }

    // ================================================================
    //  Lifecycle
    // ================================================================

    override fun onStart() {
        super.onStart()
        try {
            (activity as? UHFMainActivity)?.let { act ->
                act.setBottomNavVisible(false)
                act.findViewById<View>(R.id.fabActionCenter)?.visibility = View.GONE
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStart error", e)
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
            Log.e(TAG, "onStop error", e)
        }
    }

    override fun onResume() {
        super.onResume()
        addDebugLine("▶️ onResume")
        try {
            (activity as? UHFMainActivity)?.setActiveInventoryFragment(this)

            if (!hasRestoredThisSession && shouldPromptForRestore()) {
                hasTriedRestoringSession = true
                mainHandler.postDelayed({ checkAndRestoreSession() }, 800L)
            }

            updateButtonsState()
        } catch (e: Exception) {
            Log.e(TAG, "onResume error", e)
        }
    }

    private fun silentRestoreSession() {
        if (hasRestoredThisSession) {
            Log.d(TAG, "⏭️ Session already restored — skip")
            return
        }
        hasRestoredThisSession = true

        val sMgr = sessionManager ?: return
        if (!isAdded || isFragmentDestroyed) return

        try {
            if (!sMgr.hasValidSession()) {
                Log.d(TAG, "silentRestore: no valid session")
                hasRestoredThisSession = false
                return
            }

            sMgr.tryRestoreSession(object : InventorySessionManager.SessionCallback {
                override fun onSessionFound(
                    items: JSONArray?,
                    expected: Int,
                    category: String?,
                    timeAgoMillis: Long
                ) {
                    if (!isAdded || isFragmentDestroyed) return
                    if (items == null || items.length() == 0) {
                        hasRestoredThisSession = false
                        return
                    }

                    Log.i(TAG, "🔄 Restoring ${items.length()} items")
                    applyRestoredSessionSilent(items, expected, category)

                    mainHandler.postDelayed({
                        showResumeDialog(items.length(), expected, timeAgoMillis)
                    }, 500L)
                }

                override fun onNoSession() {
                    Log.d(TAG, "silentRestore: no session")
                    hasRestoredThisSession = false
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "silentRestoreSession error", e)
        }
    }

    private fun applyRestoredSessionSilent(items: JSONArray?, expectedCount: Int, category: String?) {
        if (items == null) return

        try {
            var totalWeight = 0.0
            var totalValue = 0.0

            synchronized(scannedItemsDisplayList) {
                scannedItemsDisplayList.clear()
                for (i in 0 until items.length()) {
                    try {
                        val itemObj = items.getJSONObject(i)
                        val item = ScannedItemDisplay(
                            itemObj.optString("epc", ""),
                            itemObj.optString("title", ""),
                            itemObj.optString("karat", "—"),
                            maxOf(1, itemObj.optInt("count", 1)),
                            itemObj.optInt("rssi", -50),
                            itemObj.optString("time", "")
                        ).apply {
                            weight = itemObj.optDouble("weight", 0.0)
                            price = itemObj.optDouble("price", 0.0)
                        }

                        totalWeight += item.weight
                        totalValue += item.price
                        scannedItemsDisplayList.add(item)
                    } catch (e: Exception) {
                        Log.w(TAG, "Silent restore parse error at $i", e)
                    }
                }
            }

            aggregateTotalWeight = totalWeight
            aggregateTotalValue = totalValue

            syncEngineWithRestoredItems(scannedItemsDisplayList)

            if (modernAdapter != null) {
                updateAdapterList()
                modernAdapter?.resetAnimationTracker()
            }

            if (scannedItemsDisplayList.isNotEmpty()) {
                switchToActiveState(false)
            }

            this.expectedCount = expectedCount
            tvExpectedCount?.text = if (expectedCount > 0) expectedCount.toString() else "—"

            selectedCategoryFilter = if (category.isNullOrEmpty()) null else category
            lastScannedForAnimation = 0

            updateUI()
            updateTotalWeightAndValue()

            tvScannedCount?.text = scannedItemsDisplayList.size.toString()
            tvItemCount?.text = scannedItemsDisplayList.size.toString()
            tvEmptyState?.visibility = if (scannedItemsDisplayList.isEmpty()) View.VISIBLE else View.GONE

            updateButtonsState()
            Log.d(TAG, "✅ Silent restore complete: ${scannedItemsDisplayList.size} items")
        } catch (e: Exception) {
            Log.e(TAG, "applyRestoredSessionSilent error", e)
        }
    }

    private fun shouldPromptForRestore(): Boolean {
        return try {
            if (hasTriedRestoringSession || isScanning) return false
            if (scannedItemsDisplayList.isNotEmpty()) return false
            if ((inventoryEngine?.uniqueCount ?: 0) > 0) return false

            val ctx = context ?: return false
            val prefs = ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_SKIP_RESTORE_PROMPT, false)) {
                Log.d(TAG, "⏭️ User chose to skip restore prompt")
                return false
            }

            sessionManager?.hasMeaningfulSession(
                MIN_ITEMS_TO_PROMPT,
                MIN_SESSION_AGE_TO_PROMPT_MS
            ) == true
        } catch (e: Exception) {
            Log.e(TAG, "shouldPromptForRestore error", e)
            false
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            if (scannedItemsDisplayList.isNotEmpty()) {
                sessionManager?.saveSession(
                    expectedCount,
                    scanStartTime,
                    selectedCategoryFilter,
                    scannedItemsDisplayList
                )
                Log.d(TAG, "✅ Session saved on onPause: ${scannedItemsDisplayList.size} items")
            }

            (activity as? UHFMainActivity)?.setActiveInventoryFragment(null)
        } catch (e: Exception) {
            Log.e(TAG, "onPause error", e)
        }
    }

    override fun onDestroyView() {
        Log.d(TAG, "onDestroyView: cleanup")

        try {
            if (scannedItemsDisplayList.isNotEmpty()) {
                sessionManager?.saveSession(
                    expectedCount,
                    scanStartTime,
                    selectedCategoryFilter,
                    scannedItemsDisplayList
                )
                Log.d(TAG, "✅ Session saved on onDestroyView: ${scannedItemsDisplayList.size} items")
            }
        } catch (e: Exception) {
            Log.e(TAG, "save on destroy error", e)
        }

        scanSession?.release()
        scanSession = null

        backPressedCallback?.remove()
        backPressedCallback = null

        isFragmentDestroyed = true
        isScanning = false

        try {
            mainHandler.removeCallbacks(uiUpdateRunnable)
            mainHandler.removeCallbacks(timerRunnable)
        } catch (_: Exception) {
        }

        scanController?.let { ctrl ->
            try {
                ctrl.detachRfidManager()
            } catch (_: Exception) {
            }
            try {
                ctrl.stopScanning()
            } catch (_: Exception) {
            }
            try {
                ctrl.cleanup()
            } catch (e: Exception) {
                Log.e(TAG, "scanController cleanup error", e)
            }
        }

        try {
            soundManager?.release()
        } catch (_: Exception) {
        }
        soundManager = null

        try {
            feedbackHelper?.cleanup()
        } catch (_: Exception) {
        }
        feedbackHelper = null

        try {
            filterManager?.reset()
        } catch (_: Exception) {
        }

        modernAdapter = null
        rvInventoryItems = null
        scanController = null
        persistenceManager = null
        printManager = null

        (debugOverlay?.parent as? ViewGroup)?.removeView(debugOverlay)
        debugOverlay = null

        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: full cleanup")

        try {
            scanSession?.release()
        } catch (_: Exception) {
        }
        scanSession = null

        synchronized(scannedItemsDisplayList) {
            scannedItemsDisplayList.clear()
        }
        catalogEpcs.clear()
        itemStatusOverrides.clear()
        itemDamageNotes.clear()

        try {
            catalogManager?.clear()
        } catch (_: Exception) {
        }
        try {
            persistenceManager?.cleanup()
        } catch (_: Exception) {
        }

        backgroundExecutor = null
        ioExecutor = null
    }

    private fun animateWelcomeLogo() {
        try {
            val logo = ivWelcomeLogo ?: return
            logo.alpha = 0f
            logo.scaleX = 0.5f
            logo.scaleY = 0.5f

            logo.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(600L)
                .setInterpolator(OvershootInterpolator(1.5f))
                .start()

            mainHandler.postDelayed({
                if (isAdded && !isFragmentDestroyed && ivWelcomeLogo != null) {
                    ObjectAnimator.ofFloat(ivWelcomeLogo, "alpha", 1f, 0.7f, 1f).apply {
                        duration = 2000L
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        start()
                    }
                }
            }, 700L)
        } catch (e: Exception) {
            Log.e(TAG, "animateWelcomeLogo error", e)
        }
    }

    // ================================================================
    //  MiraScanSessionController.Host Implementation
    // ================================================================

    override fun getHostContext(): Context? = context

    override fun getHostActivity(): Activity? = activity

    override fun isHostAlive(): Boolean = isAdded && !isFragmentDestroyed

    override fun onSessionTagAccepted(epc: String?, rssi: Int) {
        // يُعالج مباشرة في processTagReadDirect
    }

    override fun onSessionLimitReached(max: Int) {
        showToast(if (isArabic) "📊 وصلت للحد الأقصى ($max)" else "📊 Max tags reached ($max)")
        if (isScanning) pauseScanning()
    }

    override fun onSessionShouldStop(reason: String?) {
        if ("single-mode" == reason) {
            showToast(if (isArabic) "🎯 تم" else "🎯 Done")
        }
        if (isScanning) pauseScanning()
    }
}
