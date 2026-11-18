package com.example.uhf.fragment

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.uhf.BuildConfig
import com.example.uhf.MiraRfid.MiraReaderSettings
import com.example.uhf.MiraRfid.MiraScanSessionController
import com.example.uhf.MiraRfid.RFIDManager
import com.example.uhf.R
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
import com.example.uhf.model.ScannedItemDisplay
import com.example.uhf.utils.InventoryAnimationHelper
import com.example.uhf.utils.MiraExecutorService
import com.example.uhf.utils.SoundManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MiraInventoryProFragment - الإصدار 4.0 (Kotlinized - DIRECT C83 READER)
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

        private const val KEYCODE_RFID_TRIGGER_1 = 139
        private const val KEYCODE_RFID_TRIGGER_2 = 280
        private const val KEYCODE_RFID_TRIGGER_3 = 293

        private const val ITEMS_LIST_PAGE_SIZE = 100

        private const val PREFS_UI = "mira_inventory_ui_prefs"
        private const val KEY_SKIP_RESTORE_PROMPT = "skip_restore_prompt"
        private const val MIN_SESSION_AGE_TO_PROMPT_MS = 2 * 60 * 1000L
        private const val MIN_ITEMS_TO_PROMPT = 3

        @JvmOverloads
        fun newInstance(mode: String? = null): MiraInventoryProFragment {
            return MiraInventoryProFragment().apply {
                if (mode != null) {
                    arguments = Bundle().apply {
                        putString("INVENTORY_MODE", mode)
                    }
                }
            }
        }
    }

    // ==================== Debug Overlay ====================
    private var debugOverlay: TextView? = null
    private val debugLog = StringBuilder()
    private val debugMaxLines = 12

    private fun addDebugLine(line: String) {
        if (!BuildConfig.DEBUG) return
        if (!isAdded || activity == null) return
        activity?.runOnUiThread {
            try {
                val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                debugLog.insert(0, "$time  $line\n")

                val lines = debugLog.toString().split("\n")
                if (lines.size > debugMaxLines) {
                    debugLog.setLength(0)
                    for (i in 0 until debugMaxLines) {
                        debugLog.append(lines[i]).append("\n")
                    }
                }

                debugOverlay?.text = debugLog.toString()
            } catch (_: Exception) {}
        }
    }

    private fun createDebugOverlay() {
        val act = activity ?: return
        if (debugOverlay?.parent != null) {
            (debugOverlay?.parent as? ViewGroup)?.removeView(debugOverlay)
        }

        debugOverlay = TextView(act).apply {
            textSize = 9f
            setTextColor(0xFF00FF00.toInt())
            setBackgroundColor(0xCC000000.toInt())
            setPadding(12, 12, 12, 12)
            typeface = android.graphics.Typeface.MONOSPACE
            text = "🔍 Debug Ready\n"
            setOnClickListener {
                visibility = if (visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

        val params = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM
        }

        debugOverlay?.layoutParams = params
        val rootView = act.window.decorView as ViewGroup
        rootView.addView(debugOverlay)
        addDebugLine("🎬 Overlay ready")
    }

    // ==================== Views ====================
    private var welcomeState: View? = null
    private var activeContentGroup: View? = null
    private var progressCard: View? = null
    private var metricsRow: View? = null
    private var fancyDivider: View? = null
    private var iconActionsRow: View? = null
    private var nestedScroll: View? = null
    private var btnInventoryClose: ImageButton? = null

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

    // ==================== Executors ====================
    private var miraExecutor: MiraExecutorService? = null
    private lateinit var backgroundExecutor: ExecutorService
    private lateinit var ioExecutor: ExecutorService

    // ==================== Handler ====================
    private val mainHandler = Handler(Looper.getMainLooper())

    // ==================== State ====================
    @Volatile private var isScanning = false
    @Volatile private var isFragmentDestroyed = false
    @Volatile private var isArabic = true
    @Volatile private var expectedCount = 0
    @Volatile private var currentPowerDbm = 27
    @Volatile private var shouldResumeOnNextStart = false
    @Volatile private var skipCatalogLoad = false

    private var selectedCategoryFilter: String? = null
    private var currentLiveFilter = 0
    private var lastScannedForAnimation = 0
    private var lastServerReport: JSONObject? = null
    private var autoSnapshotEnabled = true
    @Volatile private var hasCelebrated = false

    private var scanStartTime = 0L
    @Volatile private var pendingUiUpdate = false

    @Volatile private var aggregateTotalWeight = 0.0
    @Volatile private var aggregateTotalValue = 0.0

    private var hasTriedRestoringSession = false
    @Volatile private var hasRestoredThisSession = false
    @Volatile private var isStartingScan = false

    @Volatile private var isPaused = false
    @Volatile private var nextScanIsResume = false
    @Volatile private var hasUncommittedSession = false
    @Volatile private var sessionElapsedBeforePause = 0L

    // ==================== Collections ====================
    private val scannedItemsDisplayList: MutableList<ScannedItemDisplay> = CopyOnWriteArrayList()
    private val catalogEpcs: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    // ==================== Back Press ====================
    private var backPressedCallback: OnBackPressedCallback? = null

    fun isScanning(): Boolean = isScanning

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view: View? = try {
            inflater.inflate(R.layout.fragment_mira_inventory_pro, container, false)
        } catch (e: Exception) {
            Log.e(TAG, "Inflate error", e)
            return TextView(requireContext()).apply {
                text = "✅ الجرد يعمل (Fallback)\n\n${e.message}"
                textSize = 16f
                gravity = android.view.Gravity.CENTER
            }
        }

        isFragmentDestroyed = false

        // Executors
        try {
            miraExecutor = MiraExecutorService.getInstance()
            val exec = if (miraExecutor != null && miraExecutor!!.isHealthy()) {
                miraExecutor!!.backgroundExecutor
            } else {
                Executors.newSingleThreadExecutor()
            }
            backgroundExecutor = exec
            ioExecutor = exec
        } catch (e: Exception) {
            Log.e(TAG, "Executor init error", e)
            val fallbackExec = Executors.newSingleThreadExecutor()
            backgroundExecutor = fallbackExec
            ioExecutor = fallbackExec
        }

        // Locale + RTL
        try {
            isArabic = Locale.getDefault().language == "ar"
            view?.layoutDirection = if (isArabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        } catch (e: Exception) {
            Log.e(TAG, "Locale error", e)
        }

        // SoundManager
        try {
            soundManager = SoundManager(requireContext())
        } catch (e: Exception) {
            Log.e(TAG, "SoundManager error", e)
        }

        scanSession = MiraScanSessionController(this)

        // Feedback & Animation
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

        // Session & Catalog & Filter
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
            Log.e(TAG, "snapshotManager error", e)
        }

        try {
            val sessionId = "SESS-" + UUID.randomUUID().toString().substring(0, 8).uppercase(Locale.US)
            inventoryEngine = MiraInventoryEngine(requireContext(), sessionId, deviceId)
        } catch (e: Exception) {
            Log.e(TAG, "Engine error", e)
        }

        try {
            apiClient = MiraApiClient.getInstance(requireContext())
        } catch (e: Exception) {
            apiClient = null
        }

        try {
            branchesManager = MiraBranchesManager.getInstance(requireContext())
        } catch (e: Exception) {
            branchesManager = null
        }

        try {
            permissionManager = PermissionManager.getInstance(requireContext())
        } catch (e: Exception) {
            permissionManager = null
        }

        try {
            scanController = InventoryScanController(requireContext(), isArabic, backgroundExecutor, ioExecutor).apply {
                setCallback(this@MiraInventoryProFragment)
                setSoundManager(soundManager)
                setCurrentPowerDbm(currentPowerDbm)
                inventoryEngine?.let { setInventoryEngine(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ScanController error", e)
        }

        // RFID Reader Status Check
        try {
            val rfidManager = RFIDManager.getInstance(requireContext())
            rfidManager?.ensureReady(object : RFIDManager.ReadyCallback {
                override fun onReady(reader: com.example.uhf.MiraRfid.MiraIRFIDReader?) {
                    mainHandler.post {
                        if (tvInventoryStatus != null && isAdded) {
                            tvInventoryStatus?.text = if (isArabic) "📡 القارئ جاهز" else "📡 Reader ready"
                        }
                    }
                }
                override fun onError(reason: String?) {
                    mainHandler.post {
                        if (tvInventoryStatus != null && isAdded) {
                            tvInventoryStatus?.text = if (isArabic) "📡 القارئ غير متصل" else "📡 Reader not connected"
                        }
                    }
                }
            })
        } catch (_: Exception) {}

        try {
            val rfidManager = RFIDManager.getInstance(requireContext())
            if (rfidManager != null && scanController != null) {
                scanController?.attachRfidManager(rfidManager)
                Log.d(TAG, "✅ RFID attached EARLY: state=${rfidManager.state}")
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

        view?.let { initViews(it) }

        mainHandler.postDelayed({ animateWelcomeLogo() }, 300)

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
            if (isAdded && !isFragmentDestroyed) updateButtonsState()
        }, 150)

        mainHandler.postDelayed({ silentRestoreSession() }, 100)

        mainHandler.postDelayed({
            if (isAdded && !isFragmentDestroyed) {
                if (scannedItemsDisplayList.isNotEmpty()) {
                    switchToActiveState(false)
                } else {
                    switchToWelcomeState()
                }
            }
        }, 200)

        return view
    }

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

        feedbackHelper?.setFlashOverlay(view.findViewById(R.id.flashOverlay))

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
        rvInventoryItems?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            isNestedScrollingEnabled = false
            modernAdapter = ModernScannedItemsAdapter(isArabic) { item ->
                dialogsManager?.showItemOptionsDialog(item)
            }
            adapter = modernAdapter
            setHasFixedSize(false)
            itemAnimator = null
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
                mainHandler.postDelayed({ showFinishAndReviewDialog() }, 300)
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

        btnInventoryClose?.setOnClickListener {
            try {
                val imm = activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                etSearchItems?.windowToken?.let { token ->
                    imm?.hideSoftInputFromWindow(token, 0)
                }
            } catch (_: Exception) {}
            handleCustomBack()
        }
    }

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

            for (item in scannedItemsDisplayList) {
                if (item == null) continue
                totalReads += item.count
                if (catalogEpcs.contains(item.epc)) registered++ else unregistered++
            }

            val missing = maxOf(0, expectedCount - registered)
            val accuracy = if (expectedCount > 0) minOf(100, (registered * 100) / expectedCount) else 0
            val elapsedSeconds = totalElapsedSeconds
            val elapsed = formatElapsed(elapsedSeconds)

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

    private val totalElapsedSeconds: Long
        get() {
            var current = 0L
            if (isScanning && scanStartTime > 0) {
                current = (SystemClock.elapsedRealtime() - scanStartTime) / 1000
            }
            return sessionElapsedBeforePause + current
        }

    private fun formatElapsed(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun setupSearchBar() {
        etSearchItems?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterItems(s?.toString() ?: "")
                btnClearSearch?.visibility = if ((s?.length ?: 0) > 0) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnClearSearch?.setOnClickListener {
            etSearchItems?.setText("")
        }
    }

    private fun setupSegmentedControl() {
        val segModes = segmentedModes ?: return
        val activeBg = ContextCompat.getColor(requireContext(), R.color.gold_500)
        val inactiveBg = ContextCompat.getColor(requireContext(), R.color.pure_white)
        val activeText = ContextCompat.getColor(requireContext(), R.color.pure_white)
        val inactiveText = ContextCompat.getColor(requireContext(), R.color.text_gold_accent)

        val updateStyles = Runnable {
            val checkedId = segModes.checkedButtonId
            val buttons = arrayOf(btnModeFull, btnModeCategory, btnModeBlind)
            for (btn in buttons) {
                if (btn == null) continue
                val isActive = btn.id == checkedId
                btn.setBackgroundColor(if (isActive) activeBg else inactiveBg)
                btn.setTextColor(if (isActive) activeText else inactiveText)
                btn.iconTint = android.content.res.ColorStateList.valueOf(if (isActive) activeText else inactiveText)
            }
        }

        mainHandler.post(updateStyles)

        segModes.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            updateStyles.run()

            when (checkedId) {
                R.id.btnModeFull -> {
                    selectedCategoryFilter = null
                    if (!isScanning && scannedItemsDisplayList.isEmpty()) {
                        showToast(if (isArabic) "🎯 جاهز للجرد الكامل" else "🎯 Ready for Full Inventory")
                    }
                }
                R.id.btnModeCategory -> {
                    if (isScanning) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(if (isArabic) "⚠️ الجرد قيد التشغيل" else "⚠️ Inventory Running")
                            .setMessage(if (isArabic) "لتغيير الفئة، يجب إيقاف الجرد الحالي.\nهل تريد المتابعة؟" else "To change category, stop the current inventory.\nContinue?")
                            .setPositiveButton(if (isArabic) "إيقاف ومتابعة" else "Stop & Continue") { _, _ ->
                                stopScanning()
                                mainHandler.postDelayed({ showCategorySelectionDialog() }, 500)
                            }
                            .setNegativeButton(if (isArabic) "إلغاء" else "Cancel") { _, _ ->
                                segmentedModes?.check(R.id.btnModeFull)
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
                            .setMessage(if (isArabic) "هل تريد إيقاف الجرد الحالي وبدء الجرد الأعمى؟" else "Stop current inventory and start blind inventory?")
                            .setPositiveButton(if (isArabic) "متابعة" else "Continue") { _, _ ->
                                stopScanning()
                                mainHandler.postDelayed({ startBlindInventory() }, 500)
                            }
                            .setNegativeButton(if (isArabic) "إلغاء" else "Cancel") { _, _ ->
                                segmentedModes?.check(R.id.btnModeFull)
                            }
                            .show()
                    }
                }
            }
        }
    }

    private fun switchToActiveState(animate: Boolean) {
        if (!isAdded || isFragmentDestroyed) return
        welcomeState?.visibility = View.GONE
        val contentGroup = activeContentGroup ?: return

        if (!animate) {
            contentGroup.visibility = View.VISIBLE
            return
        }

        contentGroup.visibility = View.VISIBLE
        val cards = arrayOf(lastScanCard, progressCard, metricsRow, fancyDivider, btnOpenItemsList, iconActionsRow)
        for (v in cards) {
            v?.alpha = 0f
            v?.translationY = 30f
        }

        for (i in cards.indices) {
            cards[i]?.animate()?.alpha(1f)?.translationY(0f)?.setStartDelay((i * 80).toLong())?.duration = 350
        }
    }

    private fun switchToWelcomeState() {
        if (!isAdded || isFragmentDestroyed) return
        activeContentGroup?.visibility = View.GONE
        welcomeState?.visibility = View.VISIBLE
    }

    private fun setupPrintManagerCallback() {
        printManager?.setCallback(object : InventoryPrintManager.PrintCallback {
            override fun getScannedItems(): List<ScannedItemDisplay> = ArrayList(scannedItemsDisplayList)
            override fun getSessionId(): String = inventoryEngine?.clientSessionId ?: "SESS-UNKNOWN"
            override fun getExpectedCount(): Int = expectedCount
            override fun getScannedCount(): Int = inventoryEngine?.uniqueCount ?: scannedItemsDisplayList.size
            override fun getArchivedReports(): List<InventoryReport> = ArrayList()
        })
    }

    private fun setupBackPressHandler() {
        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleCustomBack()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback!!)
    }

    private fun handleCustomBack() {
        try {
            if (isScanning) {
                pauseScanning()
                mainHandler.postDelayed({ checkAndExitAfterPause() }, 300)
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
            .setMessage(if (isArabic) "لديك ${scannedItemsDisplayList.size} قطعة في الجلسة الحالية.\n\nهل تريد المغادرة؟ (سيتم حفظ الجلسة تلقائياً)" else "You have ${scannedItemsDisplayList.size} items in the session.\n\nLeave anyway? (Session will be saved)")
            .setPositiveButton(if (isArabic) "مغادرة" else "Leave") { _, _ -> navigateAwayFromInventory() }
            .setNegativeButton(if (isArabic) "البقاء" else "Stay", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun navigateAwayFromInventory() {
        try {
            try { scanController?.stopScanning() } catch (_: Exception) {}
            if (isScanning) stopScanning()

            isPaused = false
            hasUncommittedSession = false
            nextScanIsResume = false
            shouldResumeOnNextStart = false

            if (scannedItemsDisplayList.isNotEmpty()) {
                sessionManager?.saveSession(expectedCount, scanStartTime, selectedCategoryFilter, scannedItemsDisplayList)
            }

            try {
                (activity as? UHFMainActivity)?.setNavigationLocked(false)
            } catch (_: Exception) {}

            val act = activity as? UHFMainActivity
            if (act?.navigationManager != null) {
                act.navigationManager.goToDashboard()
            } else {
                act?.loadFragmentDirectly(MiraDashboardFragment(), com.example.uhf.navigation.NavigationManager.TAG_DASHBOARD)
            }
        } catch (e: Exception) {
            Log.e(TAG, "navigateAwayFromInventory error", e)
        }
    }

    private fun canEditInventory(): Boolean {
        return permissionManager?.canEdit("inventory") == true || permissionManager?.isManager() == true
    }

    private fun canViewInventory(): Boolean {
        return permissionManager?.canView("inventory") == true || permissionManager?.isManager() == true
    }

    private fun applyPermissions() {
        if (permissionManager == null) return
        if (!canViewInventory()) {
            tvInventoryStatus?.text = if (isArabic) "⛔ غير مصرح لك بالوصول" else "⛔ Access denied"
        }
    }

    private fun applyLanguage() {
        if (btnMainToggleScan != null) {
            btnMainToggleScan?.text = if (isScanning) {
                if (isArabic) "⏹️ إيقاف الجرد" else "⏹️ Stop Scan"
            } else {
                if (isArabic) "▶️ بدء جرد RFID" else "▶️ Start RFID Inventory"
            }
        }
        btnGenerateReport?.text = if (isArabic) "📋 تقرير" else "📋 Report"
        btnArchiveReports?.text = if (isArabic) "📂 الأرشيف" else "📂 Archive"
        btnPowerControl?.text = "⚡ $currentPowerDbm dBm"
        if (btnCommitSession != null) {
            val text = btnCommitSession?.text.toString()
            if (!text.contains("✅") && !text.contains("Committed")) {
                btnCommitSession?.text = if (isArabic) "اعتماد" else "Commit"
            }
        }
    }

    private fun showAccessDenied() {
        if (!isAdded || isFragmentDestroyed) return
        showToast(if (isArabic) "⛔ غير مصرح" else "⛔ Access denied")
        scanSession?.notifyError()
    }

    override fun myOnKeyDwon(keyCode: Int, event: KeyEvent): Boolean {
        val isTrigger = (keyCode == KEYCODE_RFID_TRIGGER_1 || keyCode == KEYCODE_RFID_TRIGGER_2 || keyCode == KEYCODE_RFID_TRIGGER_3)
        if (!isTrigger) return false

        var triggerMode = MiraReaderSettings.TRIGGER_MODE_HOLD
        try {
            MiraReaderSettings.getInstance(requireContext())?.let {
                triggerMode = it.triggerMode
            }
        } catch (_: Exception) {}

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (!canEditInventory()) {
                showAccessDenied()
                return true
            }

            feedbackHelper?.hapticFeedback(if (isScanning) SoundManager.FeedbackType.ERROR else SoundManager.FeedbackType.KNOWN_ITEM)

            if (triggerMode == MiraReaderSettings.TRIGGER_MODE_TOGGLE) {
                if (isScanning) pauseScanning() else startScanning()
            } else {
                if (!isScanning) startScanning()
            }
            return true
        }

        if (event.action == KeyEvent.ACTION_UP) {
            if (triggerMode == MiraReaderSettings.TRIGGER_MODE_HOLD) {
                if (isStartingScan) {
                    mainHandler.postDelayed({
                        if (isScanning) pauseScanning()
                    }, 300)
                    return true
                }
                if (isScanning) pauseScanning()
            }
            return true
        }
        return false
    }

    override fun onScanStarted() {
        addDebugLine("✅ onScanStarted")
        mainHandler.post {
            if (!isAdded || isFragmentDestroyed) return@post
            isStartingScan = false
            scanSession?.onScanStarted(nextScanIsResume)
            nextScanIsResume = false
            isScanning = true

            switchToActiveState(false)
            updateMainToggleButton(true)
            animateMainButton()

            animationHelper?.updateStatusBarBackground(statusBar, true, true)
            tvInventoryStatus?.text = if (isArabic) "📡 جاري الجرد..." else "📡 Scanning..."
            tvElapsedTime?.text = "00:00:00"

            try {
                feedbackHelper?.hapticFeedback(SoundManager.FeedbackType.KNOWN_ITEM)
            } catch (_: Exception) {}

            scanStartTime = SystemClock.elapsedRealtime()
            mainHandler.post(timerRunnable)

            (activity as? UHFMainActivity)?.setNavigationLocked(true)
            updateButtonsState()
        }
    }

    override fun onScanStopped() {
        addDebugLine("⏹️ onScanStopped")
        mainHandler.post {
            if (!isAdded || isFragmentDestroyed) return@post
            scanSession?.onScanStopped()
            isScanning = false
            updateMainToggleButton(false)
            animateMainButton()

            var isConnected = false
            try {
                isConnected = RFIDManager.getInstance(requireContext())?.isReady() == true
            } catch (_: Exception) {}

            animationHelper?.updateStatusBarBackground(statusBar, isConnected, false)
            feedbackHelper?.resetDebounce()
            tvInventoryStatus?.text = if (isArabic) "📡 تم إيقاف الجرد" else "📡 Scan stopped"

            (activity as? UHFMainActivity)?.setNavigationLocked(false)
            updateUI()
            syncOfflineTransactions()
            updateButtonsState()
        }
    }

    override fun onTagRead(normalizedEpc: String?, rssi: Int) {
        if (normalizedEpc.isNullOrEmpty()) return
        addDebugLine("📡 $normalizedEpc / $rssi")
        processValidEpc(normalizedEpc, rssi)
    }

    private fun processValidEpc(epc: String?, rssi: Int) {
        if (epc.isNullOrEmpty()) return
        if (epc.length < 16) {
            addDebugLine("⚠️ Reject short EPC: $epc")
            return
        }

        if (!selectedCategoryFilter.isNullOrEmpty()) {
            if (!catalogEpcs.contains(epc)) return
        }

        val d = scanSession?.evaluateTag(epc) ?: return
        when (d) {
            MiraScanSessionController.Decision.REJECT_SESSION_OFF,
            MiraScanSessionController.Decision.REJECT_DUP_FILTER -> return
            MiraScanSessionController.Decision.REJECT_LIMIT -> {
                scanSession?.confirmLimitReached()
                return
            }
            MiraScanSessionController.Decision.ACCEPT_DUPLICATE -> {
                scanSession?.confirmTagDuplicate()
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

    override fun onDuplicateTag(epc: String?) {}

    private fun processTagReadDirect(epc: String, rssi: Int) {
        if (!isAdded || isFragmentDestroyed || modernAdapter == null) return
        val catalogItem = catalogManager?.getItemByEpc(epc)

        addScannedItemToDisplay(epc, rssi, catalogItem)
        updateLastScan(epc, rssi)
        scheduleUiUpdate()

        scanSession?.let {
            val isKnown = catalogEpcs.contains(epc)
            it.confirmTagAccepted(isKnown)
            it.scheduleSingleModeStop()
        }
    }

    override fun onError(error: String?) {
        addDebugLine("❌ onError: $error")
        mainHandler.post {
            if (!isAdded || isFragmentDestroyed) return@post
            isStartingScan = false
            showToast((if (isArabic) "⚠️ " else "⚠️ ") + (error ?: ""))

            try { scanController?.stopScanning() } catch (_: Exception) {}
            try { scanSession?.onScanStopped() } catch (_: Exception) {}

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
        mainHandler.post {
            addDebugLine("🔌 Connection: $isConnected")
            if (!isAdded || isFragmentDestroyed) return@post
            tvInventoryStatus?.text = if (isConnected) {
                if (isArabic) "📡 متصل" else "📡 Connected"
            } else {
                if (isArabic) "📡 غير متصل" else "📡 Disconnected"
            }

            statusDot?.setBackgroundResource(if (isConnected) R.drawable.bg_status_dot_green else R.drawable.bg_status_dot_red)
            animationHelper?.updateStatusBarBackground(statusBar, isConnected, isScanning)
        }
    }

    override fun onTagDebounced(epc: String?) {}

    override fun onExpectedCountChanged(count: Int) {
        mainHandler.post {
            if (!isAdded || isFragmentDestroyed) return@post
            if (count == 0 && expectedCount > 0) return@post
            if (count > 0 || expectedCount == 0) {
                expectedCount = count
                tvExpectedCount?.text = count.toString()
            }
        }
    }

    override fun onPowerChanged(newPowerDbm: Int) {
        currentPowerDbm = newPowerDbm
        scanController?.setCurrentPowerDbm(newPowerDbm)
        applyAntennaPower(newPowerDbm)
        btnPowerControl?.text = "⚡ $newPowerDbm dBm"
        btnPowerControlCompact?.text = "⚡ $newPowerDbm dBm"
    }

    override fun onSessionReset() { confirmResetSession() }
    override fun onBuzzerTest() {}
    override fun onCommitConfirmed() { commitInventoryToServer() }
    override fun onPrintCurrentReport() { printCurrentReport() }
    override fun onPrintSingleItem(item: ScannedItemDisplay?) { printSingleItemLabel(item) }
    override fun onItemStatusChange(item: ScannedItemDisplay?, newStatus: String?) { updateItemStatus(item?.epc, newStatus) }
    override fun onItemDamageRegister(item: ScannedItemDisplay?, damageType: String?) { registerItemDamage(item?.epc, damageType) }
    override fun onShowArchive() { showArchiveReports() }
    override fun onShowSnapshots() { openSnapshotsScreen() }

    private fun startScanning() {
        addDebugLine("▶️ startScanning()")
        if (!canEditInventory()) {
            showAccessDenied()
            return
        }
        if (isScanning || isStartingScan || isFragmentDestroyed) return
        isStartingScan = true

        try {
            val rfidManager = RFIDManager.getInstance(requireContext())
            if (rfidManager == null) {
                showToast(if (isArabic) "⚠️ مدير القارئ غير جاهز" else "⚠️ Reader manager not ready")
                isStartingScan = false
                return
            }

            if (rfidManager.isReady()) {
                proceedWithScanning()
                return
            }

            rfidManager.ensureReady(object : RFIDManager.ReadyCallback {
                override fun onReady(reader: com.example.uhf.MiraRfid.MiraIRFIDReader?) {
                    mainHandler.post {
                        if (isAdded && !isFragmentDestroyed) proceedWithScanning()
                    }
                }
                override fun onError(reason: String?) {
                    mainHandler.post {
                        if (!isAdded || isFragmentDestroyed) return@post
                        isStartingScan = false
                        showToast(if (isArabic) "❌ فشل تهيئة القارئ: $reason" else "❌ Reader init failed: $reason")
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
        btnGenerateReport?.isEnabled = true
        btnGenerateReport?.alpha = 1.0f
        btnCommitSession?.text = if (isArabic) "اعتماد" else "Commit"
        btnCommitSession?.alpha = 1.0f

        nextScanIsResume = shouldResumeOnNextStart
        shouldResumeOnNextStart = false

        if (!nextScanIsResume) {
            clearAllLists()
            catalogEpcs.clear()
            sessionElapsedBeforePause = 0L
        }

        isPaused = false
        hasUncommittedSession = true

        val controller = scanController
        if (controller == null) {
            isStartingScan = false
            showFatalErrorDialog(if (isArabic) "⚠️ مدير المسح غير جاهز" else "⚠️ Scan controller not ready", "")
            return
        }

        val emptyCatalog = ConcurrentHashMap<String, InventoryItem>()
        val started = controller.startScanning(emptyCatalog)
        if (!started) {
            nextScanIsResume = false
            isPaused = false
            hasUncommittedSession = false
            shouldResumeOnNextStart = false
            isStartingScan = false
            showFatalErrorDialog(if (isArabic) "❌ فشل بدء المسح" else "❌ Failed to start scanning", "")
            return
        }

        if (skipCatalogLoad) {
            skipCatalogLoad = false
        } else if (catalogEpcs.isEmpty()) {
            loadCatalogInBackground()
        }
    }

    private fun loadCatalogInBackground() {
        if (!isAdded || isFragmentDestroyed || catalogManager == null) return
        val sessionAtStart = inventoryEngine?.clientSessionId

        backgroundExecutor.execute {
            catalogManager?.fetchCatalog(apiClient, branchesManager, object : InventoryCatalogManager.CatalogCallback {
                override fun onCatalogLoaded(catalog: Map<String, InventoryItem>?) {
                    mainHandler.post {
                        if (!isAdded || isFragmentDestroyed) return@post
                        if (sessionAtStart == null || inventoryEngine == null || sessionAtStart != inventoryEngine?.clientSessionId) return@post

                        inventoryEngine?.setCatalog(catalog)
                        catalogEpcs.clear()
                        catalog?.keys?.let { catalogEpcs.addAll(it) }

                        expectedCount = catalog?.size ?: 0
                        addDebugLine("📚 Catalog: $expectedCount items")
                        tvExpectedCount?.text = if (expectedCount > 0) expectedCount.toString() else "—"
                        progressScanned?.max = 100

                        reclassifyScannedItems()
                    }
                }

                override fun onError(error: String?) {
                    if (sessionAtStart == null || inventoryEngine == null || sessionAtStart != inventoryEngine?.clientSessionId) return
                    mainHandler.post {
                        addDebugLine("❌ Catalog FAILED")
                        if (!isAdded || isFragmentDestroyed) return@post
                        tvInventoryStatus?.text = if (isArabic) "📡 جرد بدون كتالوج" else "📡 Blind inventory (no catalog)"
                    }
                }
            })
        }
    }

    private fun showResumeDialog(itemCount: Int, expected: Int, timeAgoMs: Long) {
        if (!isAdded || isFragmentDestroyed || itemCount < 1) return

        val timeAgo = when {
            timeAgoMs < 60 * 1000 -> if (isArabic) "قبل لحظات" else "just now"
            timeAgoMs < 60 * 60 * 1000 -> if (isArabic) "قبل ${timeAgoMs / 60000} دقيقة" else "${timeAgoMs / 60000} min ago"
            else -> if (isArabic) "قبل ${timeAgoMs / 3600000} ساعة" else "${timeAgoMs / 3600000} hours ago"
        }

        val message = if (isArabic) {
            "📂 وجدنا جلسة سابقة ($timeAgo)\n\n• القطع: $itemCount\n• المتوقع: ${if (expected > 0) expected else "—"}\n\nهل تريد الاستئناف؟"
        } else {
            "📂 Previous session ($timeAgo)\n\n• Items: $itemCount\n• Expected: ${if (expected > 0) expected else "—"}\n\nResume?"
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
                inventoryEngine?.clearSession()
                scanSession?.onSessionReset()

                hasUncommittedSession = false
                isPaused = false
                nextScanIsResume = false
                shouldResumeOnNextStart = false
                expectedCount = 0
                sessionElapsedBeforePause = 0L

                tvExpectedCount?.text = "0"
                sessionManager?.clearSession()
                hasRestoredThisSession = false
                startScanning()
            }
            .setCancelable(false)
            .show()
    }

    fun pauseScanning() {
        isStartingScan = false
        if (!isScanning) return

        if (scanStartTime > 0) {
            sessionElapsedBeforePause += (SystemClock.elapsedRealtime() - scanStartTime) / 1000
        }

        scanController?.stopScanning()
        isScanning = false
        isPaused = true
        hasUncommittedSession = true
        shouldResumeOnNextStart = true

        mainHandler.post {
            if (!isAdded || isFragmentDestroyed) return@post
            updateMainToggleButton(false)
            animateMainButton()
            tvInventoryStatus?.text = if (isArabic) "⏸️ الجرد موقوف مؤقتاً" else "⏸️ Inventory paused"
            (activity as? UHFMainActivity)?.setNavigationLocked(false)
            updateButtonsState()
        }
    }

    fun stopScanning() {
        isStartingScan = false
        if (!isScanning) return

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
        inventoryEngine?.clearSession()
        scanSession?.onSessionReset()

        isPaused = false
        hasUncommittedSession = false
        nextScanIsResume = false
        shouldResumeOnNextStart = false
        expectedCount = 0
        catalogEpcs.clear()
        skipCatalogLoad = true

        tvExpectedCount?.text = "—"
        tvInventoryStatus?.text = if (isArabic) "⚡ جرد أعمى" else "⚡ Blind inventory"
        proceedWithScanning()
    }

    private fun startCategoryInventory(category: String) {
        if (isScanning || isFragmentDestroyed) return
        clearAllLists()
        showToast(if (isArabic) "🔄 جاري تحميل كتالوج $category..." else "🔄 Loading catalog...")

        backgroundExecutor.execute {
            if (isFragmentDestroyed || catalogManager == null) return@execute
            var catalogMap: Map<String, InventoryItem>? = null
            val latch = java.util.concurrent.CountDownLatch(1)

            catalogManager?.fetchCatalog(apiClient, branchesManager, object : InventoryCatalogManager.CatalogCallback {
                override fun onCatalogLoaded(catalog: Map<String, InventoryItem>?) {
                    catalogMap = catalog
                    latch.countDown()
                }
                override fun onError(error: String?) {
                    catalogMap = ConcurrentHashMap()
                    latch.countDown()
                }
            })
            try { latch.await(30, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }

            val fullCatalog = catalogMap ?: ConcurrentHashMap()
            val filtered = catalogManager?.filterByCategory(fullCatalog, category) ?: ConcurrentHashMap()

            mainHandler.post {
                if (!isAdded || isFragmentDestroyed) return@post
                inventoryEngine?.clearSession()
                scanSession?.onSessionReset()

                isPaused = false
                hasUncommittedSession = false
                nextScanIsResume = false
                shouldResumeOnNextStart = false

                catalogEpcs.clear()
                catalogEpcs.addAll(filtered.keys)
                expectedCount = filtered.size
                tvExpectedCount?.text = expectedCount.toString()

                if (expectedCount == 0) {
                    showToast(if (isArabic) "⚠️ لا توجد قطع في هذه الفئة" else "⚠️ No items in category")
                }
                proceedWithScanning()
            }
        }
    }

    private fun clearAllLists() {
        try {
            scannedItemsDisplayList.clear()
            aggregateTotalWeight = 0.0
            aggregateTotalValue = 0.0
            hasCelebrated = false

            modernAdapter?.submitList(ArrayList())
            modernAdapter?.resetAnimationTracker()
            rvInventoryItems?.scrollToPosition(0)

            tvScannedCount?.text = "0"
            tvItemCount?.text = "0"
            tvVariance?.text = "—"
            tvVariance?.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
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
            tvLastScanStatus?.text = if (isArabic) "بانتظار المسح" else "Waiting"
            tvLastScanStatus?.setTextColor(0xFF6B7280.toInt())

            btnCommitSession?.text = if (isArabic) "اعتماد" else "Commit"
            btnCommitSession?.alpha = 1.0f
            updateButtonsState()
        } catch (e: Exception) {
            Log.e(TAG, "clearAllLists error", e)
        }
    }

    private fun updateMainToggleButton(scanning: Boolean) {
        val btn = btnMainToggleScan ?: return
        try {
            if (scanning) {
                btn.text = if (isArabic) "⏸️ إيقاف مؤقت" else "⏸️ Pause"
                btn.icon = requireContext().getDrawable(android.R.drawable.ic_media_pause)
                val redColor = ContextCompat.getColor(requireContext(), R.color.inventory_stop_red)
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(redColor)
            } else if (isPaused && hasUncommittedSession) {
                btn.text = if (isArabic) "▶️ استئناف أو جرد جديد" else "▶️ Resume or New"
                btn.icon = requireContext().getDrawable(android.R.drawable.ic_media_play)
                val greenColor = ContextCompat.getColor(requireContext(), R.color.success_green)
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(greenColor)
            } else {
                btn.text = if (isArabic) "▶️ بدء جرد جديد" else "▶️ Start New Inventory"
                btn.icon = requireContext().getDrawable(android.R.drawable.ic_media_play)
                val primaryColor = ContextCompat.getColor(requireContext(), R.color.colorPrimary)
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateMainToggleButton error", e)
        }
    }

    private fun animateMainButton() {
        btnMainToggleScan?.let {
            try {
                android.animation.ObjectAnimator.ofFloat(it, "scaleX", 1f, 1.05f, 1f).apply { duration = 300; start() }
                android.animation.ObjectAnimator.ofFloat(it, "scaleY", 1f, 1.05f, 1f).apply { duration = 300; start() }
            } catch (_: Exception) {}
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
            .setMessage(if (isArabic) "لديك $itemCount قطعة في الجلسة الموقوفة.\n\n• استئناف: يكمل من حيث توقفت\n• جرد جديد: يمسح البيانات ويبدأ من الصفر" else "Paused session found.")
            .setPositiveButton(if (isArabic) "▶️ استئناف" else "▶️ Resume") { _, _ -> startScanning() }
            .setNeutralButton(if (isArabic) "🆕 جرد جديد" else "🆕 New") { _, _ ->
                clearAllLists()
                catalogEpcs.clear()
                inventoryEngine?.clearSession()
                scanSession?.onSessionReset()

                isPaused = false
                hasUncommittedSession = false
                shouldResumeOnNextStart = false
                nextScanIsResume = false
                expectedCount = 0
                sessionElapsedBeforePause = 0L
                hasCelebrated = false
                sessionManager?.clearSession()

                tvExpectedCount?.text = "0"
                startScanning()
            }
            .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
            .setCancelable(true)
            .show()
    }

    private fun addScannedItemToDisplay(epc: String, rssi: Int, item: InventoryItem?) {
        if (!isAdded || isFragmentDestroyed || modernAdapter == null) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { addScannedItemToDisplay(epc, rssi, item) }
            return
        }

        var currentDisplay: ScannedItemDisplay? = null
        var isDuplicate = false
        val wasEmpty = scannedItemsDisplayList.isEmpty()

        for (display in scannedItemsDisplayList) {
            if (display != null && epc == display.epc) {
                display.count++
                display.rssi = rssi
                display.time = currentTimeString
                currentDisplay = display
                isDuplicate = true
                break
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
            if (catalogItem != null) {
                title = if (!catalogItem.title.isNullOrEmpty()) catalogItem.title else (if (isArabic) "قطعة ذهبية" else "Gold item")
                karat = if (!catalogItem.karat.isNullOrEmpty()) catalogItem.karat else "—"
                weight = catalogItem.weight
                price = catalogItem.price
            } else if (item != null) {
                title = if (!item.title.isNullOrEmpty()) item.title else (if (isArabic) "قطعة ذهبية" else "Gold item")
                karat = if (!item.karat.isNullOrEmpty()) item.karat else "—"
                weight = item.weight
                price = item.price
            }

            val display = ScannedItemDisplay(epc, title, karat, 1, rssi, currentTimeString).apply {
                this.weight = weight
                this.price = price
            }

            scannedItemsDisplayList.add(0, display)
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
        if (modernAdapter == null || isFragmentDestroyed || filterManager == null) return
        val filtered = filterManager?.applyFilter(scannedItemsDisplayList, catalogEpcs) ?: emptyList()
        modernAdapter?.submitList(filtered)
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

    private fun updateLastScanCard(item: ScannedItemDisplay, isDuplicate: Boolean) {
        if (item.title == null || tvLastScanTitle == null) return
        try {
            tvLastScanTitle?.text = item.title ?: "—"
            tvLastScanEpc?.let {
                val epc = item.epc ?: ""
                if (epc.isNotEmpty()) {
                    it.text = formatEpcShort(epc)
                    it.visibility = View.VISIBLE
                } else {
                    it.visibility = View.GONE
                }
            }

            tvLastScanPrice?.let {
                if (item.price > 0) it.text = String.format(Locale.US, "$%,.2f", item.price) else it.text = "—"
            }

            tvLastScanWeight?.let {
                if (item.weight > 0) it.text = String.format(Locale.US, "%.3fg", item.weight) else it.text = "—"
            }

            tvLastScanKarat?.text = if (!item.karat.isNullOrEmpty()) item.karat else "—"
            tvLastScanRssi?.text = item.rssi.toString()

            if (chipReadCount != null && tvLastScanReadCount != null) {
                if (item.count > 1) {
                    tvLastScanReadCount?.text = String.format(Locale.US, "× %d", item.count)
                    chipReadCount?.visibility = View.VISIBLE
                } else {
                    chipReadCount?.visibility = View.GONE
                }
            }

            if (rowSerialTime != null) {
                var hasSerial = false
                var hasTime = false
                val serial = getSerialDisplay(item)
                if (!serial.isNullOrEmpty()) {
                    tvLastScanSerial?.text = serial
                    hasSerial = true
                }
                if (!item.time.isNullOrEmpty()) {
                    tvLastScanTime?.text = "⏰ " + extractTimeShort(item.time)
                    hasTime = true
                }
                rowSerialTime?.visibility = if (hasSerial || hasTime) View.VISIBLE else View.GONE
            }

            val isKnown = catalogEpcs.contains(item.epc)
            val hasCatalog = catalogEpcs.isNotEmpty()

            tvLastScanStatus?.let {
                val (statusText, statusColor) = when {
                    isDuplicate -> (if (isArabic) "🔁 مكرر" else "🔁 Duplicate") to 0xFF3B82F6.toInt()
                    isKnown -> (if (isArabic) "✅ مطابق للمخزون" else "✅ In Catalog") to 0xFF16A34A.toInt()
                    hasCatalog -> (if (isArabic) "🆕 غير متوقع" else "🆕 Unexpected") to 0xFFF59E0B.toInt()
                    else -> (if (isArabic) "⚠️ غير معروف" else "⚠️ Unknown") to 0xFFDC2626.toInt()
                }
                it.text = statusText
                it.setTextColor(statusColor)
            }

            lastScanCard?.let {
                android.animation.ObjectAnimator.ofFloat(it, "scaleX", 0.98f, 1f).apply { duration = 200; start() }
                android.animation.ObjectAnimator.ofFloat(it, "scaleY", 0.98f, 1f).apply { duration = 200; start() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateLastScanCard error", e)
        }
    }

    private fun formatEpcShort(epc: String?): String {
        if (epc.isNullOrEmpty()) return ""
        if (epc.length <= 16) return epc
        return epc.substring(0, 8) + "…" + epc.substring(epc.length - 6)
    }

    private fun getSerialDisplay(item: ScannedItemDisplay?): String? {
        try {
            if (item == null) return null
            val catalogItem = catalogManager?.getItemByEpc(item.epc)
            if (catalogItem != null) {
                if (!catalogItem.serial.isNullOrEmpty()) return "SN: " + catalogItem.serial
                if (!catalogItem.gtin.isNullOrEmpty()) return "GTIN: " + catalogItem.gtin
            }
        } catch (_: Exception) {}
        return null
    }

    private fun extractTimeShort(timeStr: String?): String {
        if (timeStr.isNullOrEmpty()) return ""
        try {
            val parts = timeStr.split(":")
            if (parts.size >= 2) return "${parts[0]}:${parts[1]}"
        } catch (_: Exception) {}
        return timeStr ?: ""
    }

    private fun reclassifyScannedItems() {
        if (!isAdded || isFragmentDestroyed || scannedItemsDisplayList.isEmpty()) return

        for (item in scannedItemsDisplayList) {
            if (item == null) continue
            val catalogItem = catalogManager?.getItemByEpc(item.epc)
            if (catalogItem != null) {
                if (!catalogItem.title.isNullOrEmpty()) item.title = catalogItem.title
                if (!catalogItem.karat.isNullOrEmpty()) item.karat = catalogItem.karat
                item.weight = catalogItem.weight
                item.price = catalogItem.price

                inventoryEngine?.updateItemFromCatalog(item.epc, item.title, item.karat, item.weight, item.price)
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

        btnMainToggleScan?.isEnabled = canEdit
        btnMainToggleScan?.alpha = if (canEdit) 1.0f else 0.5f

        btnFinishAndReview?.visibility = if (hasItems && !isScanning) View.VISIBLE else View.GONE

        val canCommit = hasItems && !isScanning && canEdit
        btnCommitSession?.isEnabled = canCommit
        btnCommitSession?.alpha = if (canCommit) 1.0f else 0.5f

        val canReport = hasItems && !isScanning
        btnGenerateReport?.isEnabled = canReport
        btnGenerateReport?.alpha = if (canReport) 1.0f else 0.5f

        btnOpenItemsList?.isEnabled = hasItems
        btnOpenItemsList?.alpha = if (hasItems) 1.0f else 0.5f

        btnPowerControl?.isEnabled = !isScanning && canEdit
        btnPowerControl?.alpha = if (!isScanning && canEdit) 1.0f else 0.5f
        btnPowerControlCompact?.isEnabled = !isScanning && canEdit
        btnPowerControlCompact?.alpha = if (!isScanning && canEdit) 1.0f else 0.5f

        val canArchive = !isScanning && canView
        btnArchiveReports?.isEnabled = canArchive
        btnArchiveReports?.alpha = if (canArchive) 1.0f else 0.5f
        btnArchiveReportsCompact?.isEnabled = canArchive
        btnArchiveReportsCompact?.alpha = if (canArchive) 1.0f else 0.5f

        val canSnapshot = hasItems && !isScanning
        btnTakeSnapshot?.isEnabled = canSnapshot
        btnTakeSnapshot?.alpha = if (canSnapshot) 1.0f else 0.5f

        btnViewSnapshots?.isEnabled = !isScanning
        btnViewSnapshots?.alpha = if (isScanning) 0.5f else 1.0f
    }

    private val uiUpdateRunnable = Runnable {
        pendingUiUpdate = false
        updateUI()
        updateTotalWeightAndValue()
        updateAdapterList()
    }

    private val uiUpdateDelay = 200L

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isScanning || isFragmentDestroyed) return
            updateElapsedTime()
            mainHandler.postDelayed(this, 1000)
        }
    }

    private fun scheduleUiUpdate() {
        if (!pendingUiUpdate && !isFragmentDestroyed) {
            pendingUiUpdate = true
            mainHandler.postDelayed(uiUpdateRunnable, uiUpdateDelay)
        }
    }

    private fun updateUI() {
        if (!isAdded || isFragmentDestroyed) return
        val scanned = scannedItemsDisplayList.size

        if (tvScannedCount != null) {
            val newText = scanned.toString()
            if (newText != tvScannedCount?.text.toString()) {
                if (animationHelper != null) {
                    animationHelper?.animateNumberChange(tvScannedCount, lastScannedForAnimation, scanned)
                } else {
                    tvScannedCount?.text = newText
                }
            }
        }
        tvItemCount?.text = scanned.toString()

        if (expectedCount > 0) {
            var foundInCatalog = 0
            for (item in scannedItemsDisplayList) {
                if (item != null && catalogEpcs.contains(item.epc)) foundInCatalog++
            }

            val progress = minOf(((foundInCatalog.toFloat() / expectedCount) * 100).toInt(), 100)
            if (progressScanned != null && animationHelper != null) {
                val prev = progressScanned?.progress ?: 0
                if (progress != prev) animationHelper?.animateProgressBar(progressScanned, prev, progress)
            } else {
                progressScanned?.progress = progress
            }

            if (tvProgressPercent != null && animationHelper != null) {
                animationHelper?.animateProgressText(tvProgressPercent, progress)
            } else {
                tvProgressPercent?.text = "$progress%"
            }

            tvAccuracy?.text = "$progress%"
            val color = when {
                progress >= 100 -> ContextCompat.getColor(requireContext(), R.color.success_green)
                progress >= 70 -> ContextCompat.getColor(requireContext(), R.color.warning_amber)
                else -> ContextCompat.getColor(requireContext(), R.color.text_primary)
            }
            tvAccuracy?.setTextColor(color)

            val variance = expectedCount - foundInCatalog
            tvVariance?.let {
                when {
                    variance > 0 -> {
                        it.text = "−$variance"
                        it.setTextColor(ContextCompat.getColor(requireContext(), R.color.warning_amber))
                    }
                    variance < 0 -> {
                        it.text = "+${kotlin.math.abs(variance)}"
                        it.setTextColor(ContextCompat.getColor(requireContext(), R.color.info_blue))
                    }
                    else -> {
                        it.text = "0"
                        it.setTextColor(ContextCompat.getColor(requireContext(), R.color.success_green))
                    }
                }
            }

            if (foundInCatalog >= expectedCount && isScanning) {
                if (!hasCelebrated) {
                    hasCelebrated = true
                    animationHelper?.celebrateCompletion(tvProgressPercent, progressScanned, tvScannedCount)
                    showToast(if (isArabic) "🎉 تم مسح كل المواد المتوقعة" else "🎉 All expected items scanned")
                }
            } else {
                hasCelebrated = false
            }
        } else {
            tvProgressPercent?.text = "—"
            tvAccuracy?.text = "—"
            tvVariance?.text = "—"
        }

        tvEmptyState?.visibility = if (scanned > 0) View.GONE else View.VISIBLE
        if (tvInventoryStatus != null && isScanning) {
            tvInventoryStatus?.text = (if (isArabic) "📡 جاري الجرد... (" else "📡 Scanning... (") + scanned + ")"
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
        for (item in scannedItemsDisplayList) {
            if (item != null) {
                w += item.weight
                v += item.price
            }
        }
        aggregateTotalWeight = w
        aggregateTotalValue = v
    }

    private fun updateElapsedTime() {
        if (tvElapsedTime != null && scanStartTime > 0) {
            val seconds = (SystemClock.elapsedRealtime() - scanStartTime) / 1000
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            val secs = seconds % 60
            tvElapsedTime?.text = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
        }
    }

    private fun showCommitConfirmationDialog() {
        if (dialogsManager == null || inventoryEngine == null) return
        if (!canEditInventory()) {
            showAccessDenied()
            return
        }
        val scanned = inventoryEngine?.uniqueCount ?: 0
        dialogsManager?.setExpectedCount(expectedCount)
        dialogsManager?.showCommitConfirmationDialog(scanned)
    }

    private fun buildLocalCommitSummary(): JSONObject {
        return try {
            JSONObject().apply {
                val scanned = inventoryEngine?.uniqueCount ?: 0
                val accuracy = if (expectedCount > 0) minOf(100, (scanned * 100) / expectedCount).toString() else "100"
                val variance = kotlin.math.abs(expectedCount - scanned)
                val riskLevel = when {
                    variance == 0 -> "none"
                    variance <= 2 -> "low"
                    variance <= 10 -> "medium"
                    else -> "high"
                }
                put("accuracy", accuracy)
                put("risk_level", riskLevel)
                put("recommendation", if (isArabic) "تم الاعتماد — التقرير متاح في الأرشيف" else "Committed")
                put("expected_count", expectedCount)
                put("found_count", scanned)
                put("missing_count", maxOf(0, expectedCount - scanned))
                put("unexpected_count", maxOf(0, scanned - expectedCount))
                put("closed_at", currentDateTimeString)
            }
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun commitInventoryToServer() {
        if (!isAdded || isFragmentDestroyed) return
        if (persistenceManager == null || inventoryEngine == null || apiClient == null) {
            showToast("⚠️ غير جاهز")
            return
        }

        if (persistenceManager?.isCommittingNow() == true) {
            showToast(if (isArabic) "⏳ جاري الإرسال بالفعل..." else "⏳ Already sending...")
            return
        }

        showToast(if (isArabic) "⏳ جاري إرسال البيانات..." else "⏳ Sending...")
        val scanned = inventoryEngine?.uniqueCount ?: 0
        val totalReads = inventoryEngine?.totalReads ?: 0
        val branchId = activeBranchId

        persistenceManager?.commitInventory(
            inventoryEngine!!.clientSessionId,
            branchId,
            expectedCount,
            totalReads,
            scanned,
            scannedItemsDisplayList,
            apiClient,
            object : InventoryPersistenceManager.CommitCallback {
                override fun onCommitSuccess(report: JSONObject?) {
                    mainHandler.post {
                        if (!isAdded || isFragmentDestroyed) return@post
                        val finalReport = if (report == null || report.length() == 0) buildLocalCommitSummary() else report
                        lastServerReport = finalReport

                        showToast(if (isArabic) "✅ تم اعتماد الجرد وحفظ التقرير" else "✅ Inventory committed")
                        scanSession?.notifySuccess()

                        isPaused = false
                        hasUncommittedSession = false
                        nextScanIsResume = false
                        shouldResumeOnNextStart = false
                        sessionElapsedBeforePause = 0L
                        hasCelebrated = false

                        sessionManager?.clearSession()

                        btnGenerateReport?.isEnabled = false
                        btnGenerateReport?.alpha = 0.5f
                        btnCommitSession?.isEnabled = false
                        btnCommitSession?.text = if (isArabic) "✅ معتمد" else "✅ Committed"
                        btnCommitSession?.alpha = 0.7f

                        autoSaveSnapshotAfterCommit(finalReport)
                        showCommitSuccessDialog(finalReport)
                    }
                }

                override fun onCommitError(error: String?) {
                    mainHandler.post {
                        if (!isAdded || isFragmentDestroyed) return@post
                        showToast((if (isArabic) "❌ خطأ: " else "❌ Error: ") + (error ?: ""))
                        scanSession?.notifyError()
                    }
                }
            }
        )
    }

    private fun showCommitSuccessDialog(report: JSONObject?) {
        if (!isAdded || isFragmentDestroyed) return
        try {
            val accuracy = extractString(report, "accuracy", "100")
            val riskLevel = extractString(report, "risk_level", extractString(report, "risk", "none"))
            val recommendation = extractString(report, "recommendation", extractString(report, "message", if (isArabic) "تم الاعتماد بنجاح" else "Success"))
            val expected = extractInt(report, "expected_count", extractInt(report, "expected", expectedCount))
            val found = extractInt(report, "found_count", extractInt(report, "unique_count", inventoryEngine?.uniqueCount ?: 0))
            val missing = extractInt(report, "missing_count", maxOf(0, expected - found))

            val sb = StringBuilder().apply {
                append(if (isArabic) "✅ تم اعتماد الجرد بنجاح:\n\n" else "✅ Inventory committed:\n\n")
                append(if (isArabic) "• الدقة: " else "• Accuracy: ").append(accuracy).append("%\n")
                append(if (isArabic) "• مستوى الخطورة: " else "• Risk Level: ").append(riskLevel).append("\n")
                if (expected > 0) {
                    append(if (isArabic) "• المتوقع: " else "• Expected: ").append(expected).append("\n")
                    append(if (isArabic) "• الممسوح: " else "• Found: ").append(found).append("\n")
                    if (missing > 0) append(if (isArabic) "• المفقود: " else "• Missing: ").append(missing).append("\n")
                }
                append("\n• ").append(recommendation)
            }

            lastServerReport = report
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (isArabic) "🎉 نجاح العملية" else "🎉 Success")
                .setMessage(sb.toString())
                .setPositiveButton(if (isArabic) "🖨️ طباعة" else "🖨️ Print") { _, _ -> printCurrentReport() }
                .setNeutralButton(if (isArabic) "📂 الأرشيف" else "📂 Archive") { _, _ -> showArchiveReports() }
                .setNegativeButton(if (isArabic) "✔️ إغلاق وبدء جديد" else "✔️ Close & New") { _, _ ->
                    clearAllLists()
                    catalogEpcs.clear()
                    switchToWelcomeState()

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
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "showCommitSuccessDialog error", e)
        }
    }

    private fun extractString(obj: JSONObject?, key: String, defaultValue: String): String {
        if (obj == null) return defaultValue
        return try {
            val v = obj.opt(key) ?: return defaultValue
            val str = v.toString().trim()
            if (str.isEmpty()) defaultValue else str
        } catch (_: Exception) {
            defaultValue
        }
    }

    private fun extractInt(obj: JSONObject?, key: String, defaultValue: Int): Int {
        if (obj == null) return defaultValue
        return try { obj.optInt(key, defaultValue) } catch (_: Exception) { defaultValue }
    }

    private fun showArchiveReports() {
        if (!isAdded || isFragmentDestroyed) return
        if (persistenceManager == null || apiClient == null || branchesManager == null) return

        val loading = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isArabic) "📂 جاري تحميل الأرشيف..." else "📂 Loading...")
            .setCancelable(false)
            .show()

        val branchId = activeBranchId
        persistenceManager?.fetchArchiveReports(apiClient!!, branchId, object : InventoryPersistenceManager.ArchiveCallback {
            override fun onArchiveLoaded(reports: List<InventoryReport>?) {
                mainHandler.post {
                    try { loading.dismiss() } catch (_: Exception) {}
                    if (isAdded && !isFragmentDestroyed) displayArchiveDialog(reports)
                }
            }
            override fun onArchiveError(error: String?) {
                mainHandler.post {
                    try { loading.dismiss() } catch (_: Exception) {}
                    if (isAdded && !isFragmentDestroyed) showToast("❌ Load failed: $error")
                }
            }
        })
    }

    private fun displayArchiveDialog(reports: List<InventoryReport>?) {
        if (!isAdded || isFragmentDestroyed || reports.isNullOrEmpty()) {
            showToast(if (isArabic) "لا توجد تقارير" else "No reports")
            return
        }

        val titles = reports.map { String.format("📋 %s - %s (%d/%d)", it.sessionId, it.date, it.scannedCount, it.expectedCount) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isArabic) "📂 الأرشيف" else "📂 Archive")
            .setItems(titles) { _, which -> showReportDetails(reports[which]) }
            .setPositiveButton(if (isArabic) "طباعة الكل" else "Print All") { _, _ -> printManager?.printAllReports(reports) }
            .setNegativeButton(if (isArabic) "إغلاق" else "Close", null)
            .show()
    }

    private fun showReportDetails(report: InventoryReport?) {
        if (report == null || !isAdded || isFragmentDestroyed) return
        printManager?.showReportDetails(report) {}
    }

    private fun openSnapshotsScreen() {
        try {
            if (!isAdded || isFragmentDestroyed) return
            if (isScanning) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(if (isArabic) "⚠️ الجرد قيد التشغيل" else "⚠️ Scan Running")
                    .setMessage(if (isArabic) "سيتم إيقاف الجرد للانتقال إلى اللقطات. متابعة؟" else "Scanning will stop to open snapshots. Continue?")
                    .setPositiveButton(if (isArabic) "متابعة" else "Continue") { _, _ ->
                        stopScanning()
                        mainHandler.postDelayed({ navigateToSnapshots() }, 400)
                    }
                    .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
                    .show()
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
            val act = activity as? UHFMainActivity
            val nav = act?.navigationManager
            if (nav != null) {
                val tag = com.example.uhf.navigation.NavigationManager.TAG_SNAPSHOTS
                val fragment = nav.createFragmentByTag(tag)
                if (fragment != null) {
                    nav.loadFragmentDirectly(fragment, tag)
                    return
                }
            }
            val snapshotsFragment = MiraSnapshotsFragment()
            act?.supportFragmentManager?.beginTransaction()
                ?.replace(android.R.id.content, snapshotsFragment, "Snapshots")
                ?.addToBackStack("Snapshots")
                ?.commit()
        } catch (e: Exception) {
            Log.e(TAG, "navigateToSnapshots error", e)
        }
    }

    private fun printCurrentReport() {
        if (!isAdded || isFragmentDestroyed) return
        try {
            val html = buildReportHtml()
            val title = if (isArabic) "تقرير الجرد" else "Inventory Report"
            val intent = android.content.Intent(requireContext(), com.example.uhf.activity.InventoryReportPreviewActivity::class.java).apply {
                putExtra("report_html", html)
                putExtra("report_title", title)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "printCurrentReport error", e)
        }
    }

    private fun buildReportHtml(): String {
        return "<html dir=\"${if (isArabic) "rtl" else "ltr"}\"><body><h1>Inventory Report</h1></body></html>"
    }

    private fun printSingleItemLabel(item: ScannedItemDisplay?) {
        if (printManager != null && item != null) printManager?.printSingleItemLabel(item)
    }

    private fun showItemsListDialog() {
        try {
            if (!isAdded || isFragmentDestroyed) return
            val totalCount = scannedItemsDisplayList.size
            if (totalCount == 0) {
                showToast(if (isArabic) "لا توجد مسوحات" else "No scans")
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
                if (isArabic) "📋 القطع $start - $end (من $totalCount)" else "📋 Items $start - $end"
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("📋 Scans ($totalCount)")
                .setItems(options) { _, which ->
                    showItemsListPage(which * ITEMS_LIST_PAGE_SIZE, minOf((which + 1) * ITEMS_LIST_PAGE_SIZE, totalCount))
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
            val pageSize = endIndex - startIndex
            if (pageSize <= 0) return

            val titles = Array(pageSize) { i ->
                val item = scannedItemsDisplayList[startIndex + i]
                val safeTitle = if (!item.title.isNullOrEmpty()) item.title else "Unknown"
                val safeEpc = formatEpcShort(item.epc)
                String.format(Locale.US, "%s\n%s • %.3fg", safeTitle, safeEpc, item.weight)
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Scans ${startIndex + 1}-$endIndex")
                .setItems(titles) { _, which ->
                    dialogsManager?.showItemOptionsDialog(scannedItemsDisplayList[startIndex + which])
                }
                .setPositiveButton(if (isArabic) "رجوع" else "Back") { _, _ -> showItemsListDialog() }
                .setNegativeButton(if (isArabic) "إغلاق" else "Close", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "showItemsListPage error", e)
        }
    }

    private fun showPowerControlSheet() {
        dialogsManager?.let {
            it.setCurrentPowerDbm(currentPowerDbm)
            it.showPowerControlSheet()
        }
    }

    private fun applyAntennaPower(dbm: Int) {
        try {
            val rfidManager = RFIDManager.getInstance(requireContext()) ?: return
            val success = rfidManager.setPower(dbm)
            addDebugLine("⚡ setPower $dbm → $success")
            showToast(if (success) "✅ Power set to $dbm dBm" else "⚠️ Power control failed")
        } catch (e: Exception) {
            Log.e(TAG, "applyAntennaPower error", e)
        }
    }

    private fun showCategorySelectionDialog() {
        if (!isAdded || isFragmentDestroyed) return
        val categories = if (isArabic) arrayOf("الكل", "عيار 24", "عيار 22", "عيار 21", "عيار 18", "سبائك", "أخرى") else arrayOf("All", "24K", "22K", "21K", "18K", "Bullion", "Other")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isArabic) "🎯 اختر الفئة" else "🎯 Select Category")
            .setItems(categories) { _, which -> applyCategoryFilter(categories[which], which) }
            .setNegativeButton(if (isArabic) "إلغاء" else "Cancel") { _, _ -> segmentedModes?.check(R.id.btnModeFull) }
            .show()
    }

    private fun applyCategoryFilter(category: String, index: Int) {
        if (index == 0) return
        selectedCategoryFilter = category
        if (isScanning) {
            stopScanning()
            mainHandler.postDelayed({ startCategoryInventory(category) }, 500)
        } else {
            startCategoryInventory(category)
        }
    }

    private fun checkAndRestoreSession() {
        if (sessionManager == null || !isAdded || isFragmentDestroyed || hasRestoredThisSession) return
        if (scannedItemsDisplayList.isNotEmpty()) return

        sessionManager?.tryRestoreSession(object : InventorySessionManager.SessionCallback {
            override fun onSessionFound(items: JSONArray?, expectedCount: Int, category: String?, timeAgoMillis: Long) {
                if (!isAdded || isFragmentDestroyed) return
                if (items != null) showRestoreSessionDialog(items, expectedCount, category, timeAgoMillis)
            }
            override fun onNoSession() {}
        })
    }

    private fun showRestoreSessionDialog(items: JSONArray, expected: Int, category: String?, timeAgo: Long) {
        if (!isAdded || isFragmentDestroyed) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isArabic) "🔄 استرجاع جلسة" else "🔄 Restore Session")
            .setPositiveButton(if (isArabic) "استرجاع" else "Restore") { _, _ -> applyRestoredSession(items, expected, category) }
            .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
            .show()
    }

    private fun applyRestoredSession(items: JSONArray, expected: Int, category: String?) {
        try {
            scannedItemsDisplayList.clear()
            var w = 0.0
            var v = 0.0

            for (i in 0 until items.length()) {
                val obj = items.getJSONObject(i)
                val item = ScannedItemDisplay(
                    obj.optString("epc", ""),
                    obj.optString("title", ""),
                    obj.optString("karat", "—"),
                    maxOf(1, obj.optInt("count", 1)),
                    obj.optInt("rssi", -50),
                    obj.optString("time", "")
                ).apply {
                    weight = obj.optDouble("weight", 0.0)
                    price = obj.optDouble("price", 0.0)
                }
                w += item.weight
                v += item.price
                scannedItemsDisplayList.add(item)
            }

            aggregateTotalWeight = w
            aggregateTotalValue = v
            expectedCount = expected
            selectedCategoryFilter = category

            switchToActiveState(false)
            updateUI()
            updateTotalWeightAndValue()
            updateButtonsState()
        } catch (e: Exception) {
            Log.e(TAG, "applyRestoredSession error", e)
        }
    }

    private fun confirmResetSession() {
        if (!isAdded || isFragmentDestroyed) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isArabic) "⚠️ تأكيد" else "⚠️ Confirm")
            .setMessage(if (isArabic) "إعادة تعيين الجلسة الحالية؟" else "Reset session?")
            .setPositiveButton(if (isArabic) "إعادة تعيين" else "Reset") { _, _ ->
                inventoryEngine?.clearSession()
                scanSession?.onSessionReset()
                clearAllLists()
                isPaused = false
                hasUncommittedSession = false
                shouldResumeOnNextStart = false
                sessionManager?.clearSession()
                expectedCount = 0
                switchToWelcomeState()
            }
            .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
            .show()
    }

    private fun showToast(msg: String?) {
        if (!msg.isNullOrEmpty() && isAdded && context != null) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private val currentTimeString: String
        get() = try { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) } catch (_: Exception) { "" }

    private val currentDateTimeString: String
        get() = try { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()) } catch (_: Exception) { "" }

    private val currentUserName: String
        get() = try {
            val sm = com.example.uhf.tools.SessionManager(requireContext())
            sm.displayName ?: sm.username ?: ""
        } catch (_: Exception) { "" }

    private val deviceId: String
        get() = try {
            android.provider.Settings.Secure.getString(requireContext().contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
        } catch (_: Exception) { "UNKNOWN" }

    private val activeBranchId: Int
        get() = branchesManager?.activeBranchId ?: 0

    private fun takeSnapshot() {
        if (snapshotManager == null) return
        val scanned = inventoryEngine?.uniqueCount ?: 0
        if (scanned == 0) return

        val input = EditText(requireContext()).apply { hint = "Snapshot name" }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Save Snapshot")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                ioExecutor.execute {
                    try {
                        val snapshot = snapshotManager?.createSnapshot(
                            inventoryEngine?.clientSessionId ?: "",
                            scannedItemsDisplayList,
                            expectedCount,
                            branchesManager?.activeBranchDisplayName ?: "",
                            if (selectedCategoryFilter.isNullOrEmpty()) "full" else "category",
                            currentUserName,
                            name.ifEmpty { null }
                        )
                        if (snapshot != null) snapshotManager?.saveSnapshot(snapshot)
                    } catch (_: Exception) {}
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun autoSaveSnapshotAfterCommit(serverReport: JSONObject?) {
        if (!autoSnapshotEnabled || snapshotManager == null || inventoryEngine == null) return
        ioExecutor.execute {
            try {
                val snapshot = snapshotManager?.createSnapshot(
                    inventoryEngine!!.clientSessionId,
                    scannedItemsDisplayList,
                    expectedCount,
                    branchesManager?.activeBranchDisplayName ?: "",
                    "full",
                    currentUserName,
                    "Auto-" + System.currentTimeMillis()
                )
                if (snapshot != null) snapshotManager?.saveSnapshot(snapshot)
            } catch (_: Exception) {}
        }
    }

    private fun syncOfflineTransactions() {
        if (persistenceManager == null || apiClient == null) return
        persistenceManager?.syncOfflineTransactions(apiClient!!, object : InventoryPersistenceManager.SyncCallback {
            override fun onSyncComplete(syncedCount: Int) {}
            override fun onSyncError(error: String?) {}
        })
    }

    private fun updateItemStatus(epc: String?, status: String?) {}
    private fun registerItemDamage(epc: String?, damageType: String?) {}

    override fun onStart() {
        super.onStart()
        try {
            (activity as? UHFMainActivity)?.let {
                it.setBottomNavVisible(false)
                it.findViewById<View>(R.id.fabActionCenter)?.visibility = View.GONE
            }
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try {
            (activity as? UHFMainActivity)?.let {
                it.setBottomNavVisible(true)
                it.findViewById<View>(R.id.fabActionCenter)?.visibility = View.VISIBLE
            }
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        addDebugLine("▶️ onResume")
        try {
            (activity as? UHFMainActivity)?.setActiveInventoryFragment(this)
            if (!hasTriedRestoringSession) {
                hasTriedRestoringSession = true
                mainHandler.postDelayed({ checkAndRestoreSession() }, 800)
            }
            updateButtonsState()
        } catch (_: Exception) {}
    }

    private fun silentRestoreSession() {
        if (hasRestoredThisSession || sessionManager == null || !isAdded || isFragmentDestroyed) return
        hasRestoredThisSession = true

        try {
            if (sessionManager?.hasValidSession() == true) {
                sessionManager?.tryRestoreSession(object : InventorySessionManager.SessionCallback {
                    override fun onSessionFound(items: JSONArray?, expected: Int, category: String?, timeAgoMillis: Long) {
                        if (!isAdded || isFragmentDestroyed || items == null || items.length() == 0) return
                        applyRestoredSessionSilent(items, expected, category)
                        mainHandler.postDelayed({ showResumeDialog(items.length(), expected, timeAgoMillis) }, 500)
                    }
                    override fun onNoSession() {}
                })
            }
        } catch (_: Exception) {}
    }

    private fun applyRestoredSessionSilent(items: JSONArray, expected: Int, category: String?) {
        try {
            scannedItemsDisplayList.clear()
            var w = 0.0
            var v = 0.0
            for (i in 0 until items.length()) {
                val obj = items.getJSONObject(i)
                val item = ScannedItemDisplay(
                    obj.optString("epc", ""),
                    obj.optString("title", ""),
                    obj.optString("karat", "—"),
                    maxOf(1, obj.optInt("count", 1)),
                    obj.optInt("rssi", -50),
                    obj.optString("time", "")
                ).apply {
                    weight = obj.optDouble("weight", 0.0)
                    price = obj.optDouble("price", 0.0)
                }
                w += item.weight
                v += item.price
                scannedItemsDisplayList.add(item)
            }
            aggregateTotalWeight = w
            aggregateTotalValue = v
            expectedCount = expected
            selectedCategoryFilter = category

            switchToActiveState(false)
            updateUI()
            updateTotalWeightAndValue()
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        try {
            if (scannedItemsDisplayList.isNotEmpty()) {
                sessionManager?.saveSession(expectedCount, scanStartTime, selectedCategoryFilter, scannedItemsDisplayList)
            }
            (activity as? UHFMainActivity)?.setActiveInventoryFragment(null)
        } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        try {
            if (scannedItemsDisplayList.isNotEmpty()) {
                sessionManager?.saveSession(expectedCount, scanStartTime, selectedCategoryFilter, scannedItemsDisplayList)
            }
        } catch (_: Exception) {}

        scanSession?.release()
        scanSession = null

        backPressedCallback?.remove()
        backPressedCallback = null

        isFragmentDestroyed = true
        isScanning = false

        mainHandler.removeCallbacks(uiUpdateRunnable)
        mainHandler.removeCallbacks(timerRunnable)

        scanController?.let {
            try { it.detachRfidManager() } catch (_: Exception) {}
            try { it.stopScanning() } catch (_: Exception) {}
            try { it.cleanup() } catch (_: Exception) {}
        }

        soundManager?.release()
        soundManager = null
        feedbackHelper?.cleanup()
        feedbackHelper = null

        debugOverlay?.parent?.let { (it as? ViewGroup)?.removeView(debugOverlay) }
        debugOverlay = null

        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        scanSession?.release()
        scanSession = null
        scannedItemsDisplayList.clear()
        catalogEpcs.clear()
        catalogManager?.clear()
        persistenceManager?.cleanup()
    }

    private fun animateWelcomeLogo() {
        ivWelcomeLogo?.let {
            it.alpha = 0f
            it.scaleX = 0.5f
            it.scaleY = 0.5f
            it.animate().alpha(1f).scaleX(1f).scaleY(1f).duration = 600
        }
    }

    override fun getHostContext(): Context? = context
    override fun getHostActivity(): Activity? = activity
    override fun isHostAlive(): Boolean = isAdded && !isFragmentDestroyed

    override fun onSessionTagAccepted(epc: String?, rssi: Int) {}
    override fun onSessionLimitReached(max: Int) {
        showToast("Max tags reached ($max)")
        if (isScanning) pauseScanning()
    }
    override fun onSessionShouldStop(reason: String?) {
        if (isScanning) pauseScanning()
    }

        // ==================== Public Bridge Methods for UHFMainActivity ====================

    fun showInventoryScannerSettings() {
        showPowerControlSheet()
    }

    fun showInventorySessionSettings() {
        showCategorySelectionDialog()
    }

    fun getScannedCount(): Int {
        return scannedItemsDisplayList.size
    }

    fun isCurrentlyScanning(): Boolean {
        return isScanning
    }

    fun showArchiveReportsPublic() {
        showArchiveReports()
    }
}
