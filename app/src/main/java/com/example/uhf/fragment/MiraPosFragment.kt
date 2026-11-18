package com.example.uhf.fragment

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.uhf.MiraRfid.MiraIRFIDReader
import com.example.uhf.MiraRfid.MiraReaderSettings
import com.example.uhf.MiraRfid.MiraRfidService
import com.example.uhf.MiraRfid.RFIDManager
import com.example.uhf.R
import com.example.uhf.activity.BaseTabFragmentActivity
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.api.MiraApiClient
import com.example.uhf.dialog.POSCheckoutDialog
import com.example.uhf.dialog.PendingSalesBottomSheet
import com.example.uhf.dialog.PosPowerSettingsDialog
import com.example.uhf.dialog.RefundDialog
import com.example.uhf.manager.*
import com.example.uhf.model.CartItem
import com.example.uhf.tools.DeviceDetector
import com.example.uhf.tools.SessionManager
import com.example.uhf.tools.UIHelper
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import com.example.uhf.utils.MiraExecutorService
import com.example.uhf.utils.ScaleSimulationHelper
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * MiraPosFragment (Jetpack Compose Edition)
 * يحافظ على كامل التوافق مع UHFMainActivity، قارئ RFID، الميزان الذكي، الدفع، الاسترجاع، والمزامنة بدون إنترنت.
 */
class MiraPosFragment : MiraBaseFragment() {

    companion object {
        private const val TAG = "MiraPosFragment"

        private const val KEYCODE_RFID_TRIGGER_1 = 139
        private const val KEYCODE_RFID_TRIGGER_2 = 280
        private const val KEYCODE_RFID_TRIGGER_3 = 293

        private const val PENDING_EPC_TIMEOUT_MS = 10_000L
        private const val SCAN_COOLDOWN_MS = 1500L
        private const val LAST_SALE_VALIDITY_MS = 5 * 60 * 1000L
        private const val ITEM_ADDED_DISPLAY_MS = 1000L
        private const val AUTO_ADD_DELAY_MS = 350L
        private const val SHORT_CODE_DELAY_MS = 800L
        private const val COOLDOWN_CLEANUP_MS = 60_000L

        private const val AUTO_ADD_MIN_LEN = 6
        private const val PARTIAL_SERIAL_MIN_LEN = 3
        private const val PARTIAL_SERIAL_MAX_LEN = 8
        private const val TRIGGER_GRACE_MS = 2000L

        private val SERIAL_PREFIXES = arrayOf(
            "MIRA-SN-", "MIRA-SN", "MIRASN-", "MIRASN",
            "MIRA-", "MIRA", "SN-", "SN", "JG-", "GOLD-", "GLD-"
        )
    }

    private enum class ScanMode { NONE, RFID, MANUAL, GS1 }

    private enum class HeroVisualState {
        IDLE, GS1_SCANNING, RFID_LISTENING, MANUAL_ENTRY, ITEM_ADDED, ERROR
    }

    private data class ScanFeedbackBanner(
        val id: Long = System.currentTimeMillis(),
        val title: String,
        val details: String?,
        val price: String?,
        val accentColor: Color,
        val isError: Boolean = false,
        val isWarning: Boolean = false
    )

    @Volatile
    private var currentScanMode = ScanMode.NONE

    @Volatile
    private var stopAfterFirstRead = false
    private val oneShotConsumed = AtomicBoolean(false)

    private val sdkExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "POS-SDK").apply { isDaemon = true }
    }

    private var sessionManager: SessionManager? = null
    private var scaleManager: SmartScaleManager? = null
    private var simulationHelper: ScaleSimulationHelper? = null
    private var refundDialog: RefundDialog? = null
    private var isRefundDialogHiddenForScan = false
    private var miraExecutor: MiraExecutorService? = null
    private var apiClient: MiraApiClient? = null
    private var priceManager: MiraPriceManager? = null
    private var branchesManager: MiraBranchesManager? = null
    private var permissionManager: PermissionManager? = null

    private var cartManager: PosCartManager? = null
    private var priceResolver: PosPriceResolver? = null
    private var rfidManager: RFIDManager? = null

    private var cartListener: PosCartManager.OnCartChangedListener? = null
    private var rfidListener: MiraIRFIDReader.OnTagReadListener? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var cooldownCleanupRunnable: Runnable? = null

    @Volatile
    private var isCheckoutDialogShowing = false

    @Volatile
    private var liveScaleWeight = 0.0

    @Volatile
    private var savedPowerBeforePos = -1

    @Volatile
    private var currentSaleUuid: String? = null

    @Volatile
    private var activeCheckoutDialog: POSCheckoutDialog? = null

    private var pendingSalesQueue: PendingSalesQueue? = null
    private var offlineSyncer: OfflineSalesSyncer? = null

    private val pendingEpcs: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lastScannedTimes = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastSaleResponse: MiraApiClient.ApiResponse? = null

    @Volatile
    private var lastSaleResponseTime = 0L

    private var backPressedCallback: OnBackPressedCallback? = null

    @Volatile
    private var pendingBarcodeFromScanner: String? = null
    private var pendingStopRfid: Runnable? = null

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose Reactive States
    // ═══════════════════════════════════════════════════════════════════

    private var cartItemsState by mutableStateOf<List<CartItem>>(emptyList())
    private var cartCountState by mutableIntStateOf(0)
    private var cartTotalUsdState by mutableDoubleStateOf(0.0)
    private var cartHasUnavailableState by mutableStateOf(false)

    private var heroState by mutableStateOf(HeroVisualState.IDLE)
    private var rfidReadCountState by mutableIntStateOf(0)
    private var isRfidConnectedState by mutableStateOf(true)
    private var hasRfidHardwareState by mutableStateOf(true)

    private var isManualInputVisibleState by mutableStateOf(false)
    private var manualCodeInputState by mutableStateOf("")

    private var scaleStatusTextState by mutableStateOf("")
    private var isScaleConnectedState by mutableStateOf(false)

    private var statusMessageState by mutableStateOf("")
    private var pendingSalesCountState by mutableIntStateOf(0)
    private var activeBranchNameState by mutableStateOf("")

    private var canUsePosState by mutableStateOf(true)
    private var canPerformRefundState by mutableStateOf(false)
    private var isButtonsBusyState by mutableStateOf(false)

    private var scanFeedbackState by mutableStateOf<ScanFeedbackBanner?>(null)
    private var showExitDialogState by mutableStateOf(false)

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
                    MiraPosScreen()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            val ctx = requireContext().applicationContext

            branchesManager = MiraBranchesManager.getInstance(ctx)
            priceManager = MiraPriceManager.getInstance(ctx)
            permissionManager = PermissionManager.getInstance(ctx)
            sessionManager = SessionManager(ctx)

            miraExecutor = MiraExecutorService.getInstance()
            if (miraExecutor == null || miraExecutor?.isHealthy != true) {
                Log.e(TAG, "MiraExecutorService unhealthy")
                showToast(getString(R.string.pos_msg_engine_error))
                return
            }

            priceResolver = PosPriceResolver(priceManager)
            cartManager = PosCartManager.getInstance()
            apiClient = MiraApiClient.getInstance(ctx)
            pendingSalesQueue = PendingSalesQueue.getInstance(ctx)
            offlineSyncer = OfflineSalesSyncer.getInstance(ctx)

            scaleStatusTextState = getString(R.string.pos_scale_offline)
            statusMessageState = getString(R.string.pos_cart_empty)
            activeBranchNameState = branchesManager?.activeBranchDisplayName ?: ""

            hasRfidHardwareState = try {
                DeviceDetector.isRfidHardwareSupported()
            } catch (_: Exception) {
                false
            }

            rfidManager = RFIDManager.getInstance(ctx)
            rfidManager?.let { manager ->
                val listener = object : MiraIRFIDReader.OnTagReadListener {
                    override fun onTagRead(epc: String, rssi: Int) {
                        if (currentScanMode == ScanMode.RFID) {
                            safeRun { onRingScanned(epc) }
                        }
                    }

                    override fun onScanStarted() {}
                    override fun onScanStopped() {}

                    override fun onConnectionChanged(connected: Boolean) {
                        safeRun {
                            isRfidConnectedState = connected
                        }
                    }

                    override fun onError(error: String) {
                        Log.e(TAG, "RFID Error: $error")
                    }
                }
                rfidListener = listener
                manager.addListener(listener)
            }

            setupCartListener()
            applyPermissions()
            initSmartScale()
            requestScalePermissions()

            cartManager?.let { syncCartState(it.snapshot()) }
            heroState = HeroVisualState.IDLE

            setupBackPressedHandler()
            checkAutoOpenRefundIntent()
        } catch (e: Exception) {
            Log.e(TAG, "onViewCreated error", e)
        }
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
                act.setActivePOSFragment(this)
                act.findViewById<View>(R.id.fabActionCenter)?.visibility = View.GONE
            }

            isCheckoutDialogShowing = false
            currentScanMode = ScanMode.NONE
            stopAfterFirstRead = false
            oneShotConsumed.set(false)
            cancelPendingStopIfAny()

            scaleManager?.connect()
            applyLowPowerForPos()

            cartManager?.let { syncCartState(it.snapshot()) }
            activeBranchNameState = branchesManager?.activeBranchDisplayName ?: ""

            processPendingBarcodeIfAny()
            updatePendingSalesBadge()
            triggerOfflineSyncIfNeeded()
            startCooldownCleanup()
        } catch (e: Exception) {
            Log.e(TAG, "onResume error", e)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            cancelPendingStopIfAny()
            stopCooldownCleanup()

            if (currentScanMode != ScanMode.NONE) {
                switchScanMode(ScanMode.NONE)
            }

            isCheckoutDialogShowing = false
            heroState = HeroVisualState.IDLE

            (activity as? UHFMainActivity)?.let { act ->
                act.setActivePOSFragment(null)
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

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            processPendingBarcodeIfAny()
            if (isRefundDialogHiddenForScan) {
                isRefundDialogHiddenForScan = false
                if (refundDialog != null && refundDialog?.isShowing == false) {
                    refundDialog?.show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            stopRFIDScan()

            backPressedCallback?.let {
                it.isEnabled = false
                it.remove()
            }
            backPressedCallback = null

            rfidListener?.let { listener ->
                rfidManager?.removeListener(listener)
            }
            rfidListener = null

            cartListener?.let { listener ->
                cartManager?.removeListener(listener)
            }
            cartListener = null

            restoreOriginalPower()

            scaleManager?.cleanup()
            simulationHelper?.cleanup()

            cancelPendingStopIfAny()
            stopCooldownCleanup()

            pendingEpcs.clear()
            lastScannedTimes.clear()
            currentScanMode = ScanMode.NONE
            stopAfterFirstRead = false
            oneShotConsumed.set(false)
            currentSaleUuid = null
            activeCheckoutDialog = null

            mainHandler.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            Log.e(TAG, "onDestroyView error", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            stopRFIDScan()
            scaleManager?.cleanup()
            scaleManager = null

            if (!sdkExecutor.isShutdown) {
                sdkExecutor.shutdownNow()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy error", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Cart State Sync
    // ═══════════════════════════════════════════════════════════════════

    private fun setupCartListener() {
        val manager = cartManager ?: return
        val listener = PosCartManager.OnCartChangedListener { snapshot, _, _ ->
            safeRun { syncCartState(snapshot) }
        }
        cartListener = listener
        manager.addListener(listener)
    }

    private fun syncCartState(snapshot: PosCartManager.CartSnapshot) {
        if (!isFragmentAlive()) return
        cartItemsState = ArrayList(snapshot.items)
        cartCountState = snapshot.count
        cartTotalUsdState = snapshot.totalUsd
        cartHasUnavailableState = snapshot.hasUnavailable

        statusMessageState = when {
            !canUsePosState -> if (isArabic) "⛔ غير مصرح" else "⛔ Access Denied"
            snapshot.count == 0 -> getString(R.string.pos_cart_empty)
            snapshot.hasUnavailable -> getString(R.string.pos_cart_unavailable)
            else -> getString(R.string.pos_cart_ready)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Permissions & Scale Setup
    // ═══════════════════════════════════════════════════════════════════

    private fun requestScalePermissions() {
        val ctx = context ?: return
        val act = activity ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    act,
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                    1002
                )
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    act,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    1002
                )
            }
        }
    }

    private fun applyPermissions() {
        canUsePosState = hasPosPermission()
        canPerformRefundState = canPerformRefund()
        if (!canUsePosState) {
            statusMessageState = if (isArabic) "⛔ غير مصرح" else "⛔ Access Denied"
        }
    }

    private fun hasPosPermission(): Boolean {
        return try {
            permissionManager?.canUsePos() == true
        } catch (_: Exception) {
            false
        }
    }

    private fun canPerformCheckout(): Boolean {
        val pm = permissionManager ?: return false
        return try {
            pm.canUsePos() && (pm.isPosOperator() || pm.isManager() || pm.isAdmin())
        } catch (_: Exception) {
            false
        }
    }

    private fun canPerformRefund(): Boolean {
        val pm = permissionManager ?: return false
        return try {
            pm.isManager() || pm.isAdmin() || pm.isFinanceManager()
        } catch (_: Exception) {
            false
        }
    }

    private fun initSmartScale() {
        try {
            scaleManager = SmartScaleManager(requireContext(), object : SmartScaleManager.ScaleCallback {
                override fun onWeightReceived(weight: Double) {
                    liveScaleWeight = weight
                    safeRun {
                        isScaleConnectedState = true
                        scaleStatusTextState = String.format(Locale.US, "⚖️ %.2fg", weight)
                        updateTopCartItemScaleWeight(weight)
                    }
                }

                override fun onScaleConnected(name: String) {
                    safeRun {
                        isScaleConnectedState = true
                        scaleStatusTextState = "⚖️ $name"
                        showToast(getString(R.string.pos_msg_scale_connected, name))
                    }
                }

                override fun onScaleDisconnected() {
                    liveScaleWeight = 0.0
                    safeRun {
                        isScaleConnectedState = false
                        scaleStatusTextState = getString(R.string.pos_scale_offline)
                    }
                }

                override fun onScaleError(error: String) {
                    Log.e(TAG, "Scale Error: $error")
                }
            })

            simulationHelper = ScaleSimulationHelper(requireContext()) { weight, _ ->
                safeRun {
                    liveScaleWeight = weight
                    isScaleConnectedState = true
                    scaleStatusTextState = String.format(Locale.US, "⚖️ %.2fg", weight)
                    updateTopCartItemScaleWeight(weight)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "initSmartScale error", e)
        }
    }

    private fun updateTopCartItemScaleWeight(currentScaleWeight: Double) {
        val manager = cartManager ?: return
        if (manager.isEmpty || currentScaleWeight <= 0) return
        try {
            val items = manager.items
            if (items.isEmpty()) return
            val topItem = items[0] ?: return
            if (topItem.scaleWeight == currentScaleWeight) return
            topItem.scaleWeight = currentScaleWeight
            manager.updateItemAt(0, topItem)
        } catch (e: Exception) {
            Log.e(TAG, "updateTopCartItemScaleWeight error", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Cooldown & Power Management
    // ═══════════════════════════════════════════════════════════════════

    private fun startCooldownCleanup() {
        stopCooldownCleanup()
        cooldownCleanupRunnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                lastScannedTimes.entries.removeIf { (now - it.value) > COOLDOWN_CLEANUP_MS }
                mainHandler.postDelayed(this, COOLDOWN_CLEANUP_MS)
            }
        }
        mainHandler.postDelayed(cooldownCleanupRunnable!!, COOLDOWN_CLEANUP_MS)
    }

    private fun stopCooldownCleanup() {
        cooldownCleanupRunnable?.let { mainHandler.removeCallbacks(it) }
        cooldownCleanupRunnable = null
    }

    private fun applyLowPowerForPos() {
        if (sdkExecutor.isShutdown) return
        sdkExecutor.execute {
            try {
                val appCtx = getSafeAppContext() ?: return@execute
                val settings = MiraReaderSettings.getInstance(appCtx)
                savedPowerBeforePos = settings.powerDbm
                val posPower = settings.posPowerDbm
                if (savedPowerBeforePos == posPower) return@execute

                settings.powerDbm = posPower
                BaseTabFragmentActivity.getSharedReader()?.let { reader ->
                    try {
                        reader.setPower(posPower)
                    } catch (e: Exception) {
                        Log.w(TAG, "setPower failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "applyLowPowerForPos error", e)
            }
        }
    }

    private fun applyPosPowerImmediate(newPowerDbm: Int) {
        if (sdkExecutor.isShutdown) return
        sdkExecutor.execute {
            try {
                BaseTabFragmentActivity.getSharedReader()?.setPower(newPowerDbm)
            } catch (e: Exception) {
                Log.w(TAG, "applyPosPowerImmediate failed", e)
            }
        }
    }

    private fun restoreOriginalPower() {
        if (savedPowerBeforePos <= 0 || sdkExecutor.isShutdown) return
        val target = savedPowerBeforePos
        savedPowerBeforePos = -1

        sdkExecutor.execute {
            try {
                val appCtx = getSafeAppContext() ?: return@execute
                val settings = MiraReaderSettings.getInstance(appCtx)
                settings.powerDbm = target
                BaseTabFragmentActivity.getSharedReader()?.setPower(target)
            } catch (e: Exception) {
                Log.e(TAG, "restoreOriginalPower error", e)
            }
        }
    }

    private fun openPosPowerSettings() {
        if (!isFragmentAlive()) return
        try {
            val ctx = getSafeAppContext() ?: return
            PosPowerSettingsDialog.show(ctx, isArabic) { newPowerDbm ->
                applyPosPowerImmediate(newPowerDbm)
                showToast(
                    if (isArabic) "✅ تم تحديث قوة القراءة إلى $newPowerDbm dBm"
                    else "✅ Power updated to $newPowerDbm dBm"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "openPosPowerSettings error", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Scan Mode & Hardware Trigger
    // ═══════════════════════════════════════════════════════════════════

    override fun myOnKeyDwon(keyCode: Int, event: KeyEvent): Boolean {
        val isTrigger = keyCode == KEYCODE_RFID_TRIGGER_1 ||
                keyCode == KEYCODE_RFID_TRIGGER_2 ||
                keyCode == KEYCODE_RFID_TRIGGER_3

        if (!isTrigger) return false

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            cancelPendingStopIfAny()

            if (isCheckoutDialogShowing) return true

            if (!hasPosPermission()) {
                showToast(getString(R.string.pos_msg_no_permission))
                return true
            }

            if (!hasRfidHardwareState) return true

            if (currentScanMode != ScanMode.RFID) {
                switchScanMode(ScanMode.RFID)
            } else {
                oneShotConsumed.set(false)
                stopAfterFirstRead = true
            }
            return true
        }

        if (event.action == KeyEvent.ACTION_UP) {
            if (currentScanMode == ScanMode.RFID) {
                scheduleDelayedStopIfNeeded()
            }
            return true
        }

        return false
    }

    private fun scheduleDelayedStopIfNeeded() {
        cancelPendingStopIfAny()
        pendingStopRfid = Runnable {
            pendingStopRfid = null
            if (currentScanMode == ScanMode.RFID && stopAfterFirstRead) {
                switchScanMode(ScanMode.NONE)
            }
        }
        mainHandler.postDelayed(pendingStopRfid!!, TRIGGER_GRACE_MS)
    }

    private fun cancelPendingStopIfAny() {
        pendingStopRfid?.let { mainHandler.removeCallbacks(it) }
        pendingStopRfid = null
    }

    private fun switchScanMode(target: ScanMode) {
        if (!isFragmentAlive()) return
        if (currentScanMode == target) return

        when (currentScanMode) {
            ScanMode.RFID -> {
                cancelPendingStopIfAny()
                stopRFIDScan()
            }
            ScanMode.MANUAL -> {
                isManualInputVisibleState = false
                manualCodeInputState = ""
            }
            ScanMode.GS1, ScanMode.NONE -> {}
        }

        currentScanMode = target

        when (target) {
            ScanMode.GS1 -> {
                heroState = HeroVisualState.GS1_SCANNING
                openBarcodeScanner()
            }
            ScanMode.RFID -> {
                rfidReadCountState = 0
                heroState = HeroVisualState.RFID_LISTENING
                startRFIDScan()
            }
            ScanMode.MANUAL -> {
                heroState = HeroVisualState.MANUAL_ENTRY
                isManualInputVisibleState = true
            }
            ScanMode.NONE -> {
                heroState = HeroVisualState.IDLE
            }
        }
    }

    private fun startRFIDScan() {
        val manager = rfidManager ?: return
        if (sdkExecutor.isShutdown) return

        oneShotConsumed.set(false)
        stopAfterFirstRead = true

        sdkExecutor.execute {
            try {
                manager.startScan(object : RFIDManager.ReadyCallback {
                    override fun onReady(reader: MiraIRFIDReader) {}
                    override fun onError(reason: String) {
                        safeRun {
                            if (!isFragmentAlive()) return@safeRun
                            currentScanMode = ScanMode.NONE
                            heroState = HeroVisualState.IDLE
                        }
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "startScan bg error", e)
            }
        }
    }

    private fun stopRFIDScan() {
        val manager = rfidManager ?: return
        if (sdkExecutor.isShutdown) {
            try {
                manager.stopScan()
            } catch (_: Exception) {
            }
            return
        }
        sdkExecutor.execute {
            try {
                manager.stopScan()
            } catch (e: Exception) {
                Log.e(TAG, "stopScan bg error", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Scanning & Item Lookup
    // ═══════════════════════════════════════════════════════════════════

    private fun submitManualCode(rawCode: String) {
        if (!hasPosPermission()) {
            showToast(getString(R.string.pos_msg_no_permission))
            return
        }
        if (isCheckoutDialogShowing) return

        UIHelper.hideKeyboard(activity)
        val epc = rawCode.trim()
        if (epc.isEmpty()) {
            showToast(getString(R.string.pos_msg_not_found))
            return
        }

        cartManager?.let { cm ->
            var dup = cm.isIdScanned(epc)
            if (!dup && looksLikePartialSerial(epc)) {
                for (prefix in SERIAL_PREFIXES) {
                    if (cm.isIdScanned(prefix + epc)) {
                        dup = true
                        break
                    }
                }
            }
            if (dup) {
                showToast(getString(R.string.pos_msg_already_in_cart))
                playAlertSound(2)
                return
            }
        }

        processRingEpc(epc, "manual")
        manualCodeInputState = ""
        isManualInputVisibleState = false
        currentScanMode = ScanMode.NONE
        heroState = HeroVisualState.IDLE
    }

    fun onRingScanned(epc: String?) {
        try {
            if (!hasPosPermission()) return
            if (epc.isNullOrBlank()) return

            if (refundDialog != null && refundDialog?.isShowing == true) {
                if (!canPerformRefund()) return
                if (currentScanMode != ScanMode.NONE) {
                    switchScanMode(ScanMode.NONE)
                }
                refundDialog?.setQueryFromScan(epc.trim())
                heroState = HeroVisualState.IDLE
                return
            }

            if (currentScanMode != ScanMode.RFID) return

            val cleanEpc = epc.trim().uppercase(Locale.US)
            val now = System.currentTimeMillis()
            val lastTime = lastScannedTimes[cleanEpc]
            if (lastTime != null && (now - lastTime < SCAN_COOLDOWN_MS)) return
            lastScannedTimes[cleanEpc] = now

            if (stopAfterFirstRead) {
                if (!oneShotConsumed.compareAndSet(false, true)) return
                stopAfterFirstRead = false
                cancelPendingStopIfAny()
                switchScanMode(ScanMode.NONE)
            }

            val isValidEpc = cleanEpc.matches(Regex("^E2[0-9A-F]{22}$"))
            if (!isValidEpc) {
                readTidAndProcess(cleanEpc, "rfid")
                return
            }

            if (cartManager?.isIdScanned(cleanEpc) == true) {
                showToast(getString(R.string.pos_msg_already_in_cart))
                playAlertSound(2)
                return
            }

            processRingEpc(cleanEpc, "rfid")
        } catch (e: Exception) {
            Log.e(TAG, "onRingScanned error", e)
        }
    }

    private fun readTidAndProcess(originalEpc: String, source: String) {
        if (!hasPosPermission()) return
        if (sdkExecutor.isShutdown) {
            processRingEpc(originalEpc, source)
            return
        }

        safeRun {
            statusMessageState = getString(R.string.pos_msg_searching)
        }

        sdkExecutor.execute {
            var tidRead: String? = null
            try {
                val ctx = getSafeAppContext()
                if (ctx != null) {
                    tidRead = MiraRfidService.getInstance(ctx).readTid(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "readTid error", e)
            }

            val cleanTid = if (!tidRead.isNullOrBlank()) tidRead.trim().uppercase(Locale.US) else null

            safeRun {
                if (!isFragmentAlive()) return@safeRun
                if (cleanTid == null) {
                    processRingEpc(originalEpc, source)
                    return@safeRun
                }
                if (cartManager?.isIdScanned(cleanTid) == true) {
                    showToast(getString(R.string.pos_msg_already_in_cart))
                    playAlertSound(2)
                    return@safeRun
                }
                processRingEpc(cleanTid, source)
            }
        }
    }

    fun onBarcodeScanned(rawData: String?) {
        try {
            if (!hasPosPermission() || rawData.isNullOrBlank()) return

            val parsed = parseGs1OrQr(rawData.trim())
            val data = parsed[0]
            if (data.isBlank()) return

            val now = System.currentTimeMillis()
            val lastTime = lastScannedTimes[data]
            if (lastTime != null && (now - lastTime < SCAN_COOLDOWN_MS)) return
            lastScannedTimes[data] = now

            if (refundDialog != null && refundDialog?.isShowing == true) {
                if (!canPerformRefund()) return
                refundDialog?.setQueryFromScan(data)
                return
            }

            cartManager?.let { cm ->
                var dup = cm.isIdScanned(data)
                if (!dup && looksLikePartialSerial(data)) {
                    for (prefix in SERIAL_PREFIXES) {
                        if (cm.isIdScanned(prefix + data)) {
                            dup = true
                            break
                        }
                    }
                }
                if (dup) {
                    showToast(getString(R.string.pos_msg_already_in_cart))
                    playAlertSound(2)
                    return
                }
            }

            processRingEpc(data, "barcode")
        } catch (e: Exception) {
            Log.e(TAG, "onBarcodeScanned error", e)
        }
    }

    private fun processRingEpc(code: String?, source: String) {
        if (!hasPosPermission()) {
            showToast(getString(R.string.pos_msg_no_permission))
            playAlertSound(2)
            return
        }
        if (isCheckoutDialogShowing || code.isNullOrBlank() || !isFragmentAlive()) return

        val cleanCode = code.trim()
        val candidates = buildSearchCandidates(cleanCode)
        playAlertSound(1)

        safeRun {
            statusMessageState = getString(R.string.pos_msg_searching)
        }

        processRingEpcWithCandidates(cleanCode, source, candidates, 0)
    }

    private fun processRingEpcWithCandidates(
        originalCode: String,
        source: String,
        candidates: List<String>,
        index: Int
    ) {
        if (!isFragmentAlive()) return

        if (index >= candidates.size) {
            safeRun {
                if (!isFragmentAlive()) return@safeRun
                showToast(getString(R.string.pos_msg_not_found))
                playAlertSound(2)
                heroState = HeroVisualState.ERROR
                resetHeroAfterDelay()
                showScanBanner(
                    title = if (isArabic) "❌ لم يتم العثور" else "❌ Not Found",
                    details = originalCode,
                    price = null,
                    accentColor = Color(0xFFEF4444),
                    isError = true
                )
            }
            return
        }

        val candidate = candidates[index]
        if (!pendingEpcs.add(candidate)) return

        mainHandler.postDelayed({ pendingEpcs.remove(candidate) }, PENDING_EPC_TIMEOUT_MS)

        try {
            miraExecutor?.networkExecutor?.execute {
                try {
                    val context = getSafeAppContext() ?: return@execute
                    val syncManager = MiraItemSyncManager.getInstance(context)
                    val branchId = getActiveBranchId()

                    syncManager.findItem(candidate, branchId, object : MiraItemSyncManager.ItemLookupCallback {
                        override fun onItemFound(item: JSONObject, fromServer: Boolean) {
                            safeRun {
                                if (!isFragmentAlive() || !hasPosPermission()) return@safeRun
                                val itemBranchId = item.optInt("branch_id", -1)
                                if (itemBranchId > 0 && itemBranchId != branchId) {
                                    showToast(getString(R.string.pos_msg_other_branch))
                                    playAlertSound(2)
                                    resetHeroAfterDelay()
                                    return@safeRun
                                }
                                parseItemResponse(candidate, item, source)
                            }
                        }

                        override fun onItemNotFound() {
                            safeRun {
                                processRingEpcWithCandidates(originalCode, source, candidates, index + 1)
                            }
                        }
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "processRingEpcWithCandidates error", e)
                    safeRun {
                        processRingEpcWithCandidates(originalCode, source, candidates, index + 1)
                    }
                } finally {
                    pendingEpcs.remove(candidate)
                }
            }
        } catch (e: Exception) {
            pendingEpcs.remove(candidate)
            safeRun {
                processRingEpcWithCandidates(originalCode, source, candidates, index + 1)
            }
        }
    }

    private fun parseItemResponse(code: String, data: JSONObject, source: String) {
        try {
            if (!hasPosPermission()) return

            val item = if (data.has("item")) data.optJSONObject("item") else data
            if (item == null) {
                showToast(getString(R.string.pos_msg_not_found))
                playAlertSound(2)
                heroState = HeroVisualState.ERROR
                resetHeroAfterDelay()
                return
            }

            try {
                val cache = MiraLocalItemCache.getInstance(getSafeAppContext())
                val cached = cache?.findItemComprehensive(code)
                if (cached != null) {
                    val cachedStatus = cached.optString("vault_status", cached.optString("status", "")).lowercase()
                    if (cachedStatus == "sold" || cachedStatus == "exited") {
                        showToast(getString(R.string.pos_msg_already_sold))
                        playAlertSound(2)
                        heroState = HeroVisualState.ERROR
                        resetHeroAfterDelay()
                        showScanBanner(
                            title = if (isArabic) "❌ القطعة مباعة" else "❌ Already Sold",
                            details = extractString(item, "title", "item_title"),
                            price = null,
                            accentColor = Color(0xFFEF4444),
                            isError = true
                        )
                        return
                    }
                }
            } catch (_: Exception) {
            }

            val vaultStatus = item.optString("status", item.optString("vault_status", "")).lowercase()
            if (vaultStatus == "sold" || vaultStatus == "exited") {
                showToast(getString(R.string.pos_msg_already_sold))
                playAlertSound(2)
                heroState = HeroVisualState.ERROR
                resetHeroAfterDelay()
                showScanBanner(
                    title = if (isArabic) "❌ القطعة مباعة" else "❌ Already Sold",
                    details = extractString(item, "title", "item_title"),
                    price = null,
                    accentColor = Color(0xFFEF4444),
                    isError = true
                )
                return
            }

            val isAvailable = vaultStatus != "sold" && vaultStatus != "reserved"
            val title = item.optString("title", item.optString("item_title", if (isArabic) "قطعة" else "Item"))
            val karat = item.optString("karat", item.optString("gold_karat", "21K"))
            val weight = item.optDouble(
                "weight",
                item.optDouble("gross_weight", item.optDouble("gross_weight_grams", 0.0))
            )

            val karatNum = getKaratNumber(karat)
            val priceResult = priceResolver!!.resolve(item, karatNum, weight)
            val price = priceResult.priceUsd

            val cartItem = CartItem(
                code, title, karat, weight, liveScaleWeight, price, isAvailable, vaultStatus.uppercase(Locale.US)
            ).apply {
                serialNo = extractString(item, "serial", "serial_number", "serialNo", "sn")
                gtin = extractString(item, "gtin", "gtin13", "barcode", "code")
                location = extractString(item, "location", "current_location", "zone")
                itemId = item.optInt("id", item.optInt("item_id", item.optInt("product_id", 0)))

                val extractedTid = extractString(item, "tid", "tag_tid", "rfid_tid")
                if (extractedTid.isNotEmpty() && serialNo.isEmpty()) {
                    serialNo = extractedTid
                }
                tid = extractedTid

                val rfidEpcFromServer = extractString(item, "rfid_epc", "epc_final", "epc")
                if (rfidEpcFromServer.isNotEmpty()) {
                    rfidEpc = rfidEpcFromServer
                    epc = rfidEpcFromServer
                } else {
                    val fallback = serialNo.ifEmpty { code }
                    rfidEpc = fallback
                    epc = fallback
                }
            }

            val alreadyInCart = cartManager?.containsItem(cartItem.epc, cartItem.serialNo, cartItem.gtin) == true
            if (alreadyInCart) {
                showToast(getString(R.string.pos_msg_already_in_cart))
                playAlertSound(2)
                resetHeroAfterDelay()
                showScanBanner(
                    title = if (isArabic) "⚠️ مكررة في السلة" else "⚠️ Already in Cart",
                    details = title,
                    price = null,
                    accentColor = Color(0xFF3B82F6),
                    isWarning = true
                )
                return
            }

            if (!isAvailable) {
                showToast(getString(R.string.pos_msg_unavailable))
                resetHeroAfterDelay()
                showScanBanner(
                    title = if (isArabic) "⚠️ القطعة غير متاحة" else "⚠️ Item Unavailable",
                    details = title,
                    price = null,
                    accentColor = Color(0xFFF59E0B),
                    isWarning = true
                )
                return
            }

            val pos = cartManager?.addItem(cartItem) ?: -1
            if (pos < 0) {
                showToast(getString(R.string.pos_msg_already_in_cart))
                resetHeroAfterDelay()
                return
            }

            playAlertSound(1)
            showToast(getString(R.string.pos_msg_added, title))

            showScanBanner(
                title = title,
                details = "$karat • ${String.format(Locale.US, "%.2fg", weight)}",
                price = buildTotalText(1, price),
                accentColor = Color(0xFF10B981)
            )

            heroState = HeroVisualState.ITEM_ADDED
            if (source == "rfid") {
                rfidReadCountState += 1
            }
            resetHeroAfterDelay()
        } catch (e: Exception) {
            Log.e(TAG, "parseItemResponse error", e)
            safeRun {
                if (!isFragmentAlive()) return@safeRun
                showToast(getString(R.string.pos_msg_not_found))
                resetHeroAfterDelay()
            }
        }
    }

    private fun resetHeroAfterDelay() {
        mainHandler.postDelayed({
            if (!isFragmentAlive()) return@postDelayed
            if (heroState == HeroVisualState.ITEM_ADDED || heroState == HeroVisualState.ERROR) {
                heroState = HeroVisualState.IDLE
            }
        }, ITEM_ADDED_DISPLAY_MS)
    }

    private fun showScanBanner(
        title: String,
        details: String?,
        price: String?,
        accentColor: Color,
        isError: Boolean = false,
        isWarning: Boolean = false
    ) {
        scanFeedbackState = ScanFeedbackBanner(
            id = System.currentTimeMillis(),
            title = title,
            details = details,
            price = price,
            accentColor = accentColor,
            isError = isError,
            isWarning = isWarning
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Barcode Scanner
    // ═══════════════════════════════════════════════════════════════════

    private fun openBarcodeScanner() {
        try {
            if (!hasPosPermission()) {
                showToast(getString(R.string.pos_msg_no_permission))
                currentScanMode = ScanMode.NONE
                heroState = HeroVisualState.IDLE
                return
            }
            if (!isFragmentAlive()) return

            val fm = parentFragmentManager
            if (fm.isStateSaved) {
                currentScanMode = ScanMode.NONE
                heroState = HeroVisualState.IDLE
                return
            }

            val scannerFragment = MiraBarcodeScannerFragment()
            scannerFragment.setOnBarcodeScannedListener { data, _ ->
                if (!data.isNullOrEmpty()) {
                    pendingBarcodeFromScanner = data
                }
            }

            var containerId = android.R.id.content
            try {
                val parent = view?.parent
                if (parent is View && parent.id != View.NO_ID) {
                    containerId = parent.id
                }
            } catch (_: Exception) {
            }

            fm.beginTransaction()
                .hide(this)
                .add(containerId, scannerFragment, "barcode_scanner")
                .addToBackStack("barcode_scanner")
                .commit()
        } catch (e: Exception) {
            Log.e(TAG, "openBarcodeScanner error", e)
            heroState = HeroVisualState.IDLE
            currentScanMode = ScanMode.NONE
        }
    }

    private fun processPendingBarcodeIfAny() {
        val code = pendingBarcodeFromScanner ?: return
        if (code.isEmpty()) return

        pendingBarcodeFromScanner = null
        currentScanMode = ScanMode.NONE
        heroState = HeroVisualState.IDLE

        mainHandler.postDelayed({
            if (!isFragmentAlive()) return@postDelayed
            val parsed = parseGs1OrQr(code)
            onBarcodeScanned(parsed[0])
        }, 150)
    }

    private fun parseGs1OrQr(raw: String?): Array<String> {
        if (raw.isNullOrBlank()) return arrayOf("", "empty")
        var data = raw.trim()

        if (data.startsWith("]d") || data.startsWith("]Q") ||
            data.startsWith("]e") || data.startsWith("]C") ||
            data.startsWith("]D") || data.startsWith("]A")
        ) {
            data = data.substring(3).trim()
        }

        if (data.startsWith("http://") || data.startsWith("https://")) {
            try {
                val uri = Uri.parse(data)
                val serial = uri.getQueryParameter("serial") ?: uri.getQueryParameter("sn")
                if (!serial.isNullOrEmpty()) return arrayOf(serial, "qr_url")
                val gtin = uri.getQueryParameter("gtin")
                if (!gtin.isNullOrEmpty()) return arrayOf(gtin, "qr_url")
                val last = uri.lastPathSegment
                if (!last.isNullOrEmpty()) return arrayOf(last, "qr_url")
            } catch (_: Exception) {
            }
        }

        val gs1 = data.replace('\u001D', '|')
            .replace("(#)", "|")
            .replace("(21)", "|21")
            .replace("(01)", "|01")

        val mSerial = Pattern.compile("(?:^|\\|)21([A-Za-z0-9\\-_.]+)").matcher(gs1)
        if (mSerial.find()) {
            val serial = mSerial.group(1)
            if (!serial.isNullOrEmpty()) return arrayOf(serial, "gs1_serial")
        }

        val mGtin = Pattern.compile("(?:^|\\|)01(\\d{14})").matcher(gs1)
        if (mGtin.find()) {
            val gtin = mGtin.group(1)
            if (!gtin.isNullOrEmpty()) return arrayOf(gtin, "gs1_gtin")
        }

        return arrayOf(data, "raw")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Checkout Flow (with live price verification & dialog retention)
    // ═══════════════════════════════════════════════════════════════════

    private fun processCheckoutAndStockUpdate() {
        try {
            if (!hasPosPermission()) {
                showToast(getString(R.string.pos_msg_no_permission))
                return
            }
            if (!canPerformCheckout()) {
                showToast(if (isArabic) "⛔ غير مصرح بإجراء البيع" else "⛔ Not authorized")
                return
            }
            val manager = cartManager
            if (manager == null || manager.isEmpty) {
                showToast(getString(R.string.pos_msg_cart_empty_toast))
                return
            }
            if (isCheckoutDialogShowing || !isFragmentAlive()) return

            UIHelper.hideKeyboard(activity)

            val itemIds = manager.items.mapNotNull { item ->
                if (item != null && item.itemId > 0) item.itemId else null
            }

            if (itemIds.isEmpty()) {
                showToast(if (isArabic) "لا يمكن تحديد معرّفات القطع" else "Cannot identify items")
                return
            }

            showToast(if (isArabic) "🔍 جاري التحقق من الأسعار..." else "🔍 Checking prices...")
            refreshPricesBeforeCheckout(itemIds)
        } catch (e: Exception) {
            Log.e(TAG, "processCheckoutAndStockUpdate error", e)
        }
    }

    private fun refreshPricesBeforeCheckout(itemIds: List<Int>) {
        val loadingDialog = PosUIManager.showLoadingDialog(requireContext(), isArabic)

        miraExecutor?.networkExecutor?.execute {
            try {
                val ctx = getSafeAppContext() ?: return@execute
                val client = MiraApiClient.getInstance(ctx)
                val resp = client.prepareCheckout(itemIds)

                safeRun {
                    PosUIManager.dismissDialog(loadingDialog)

                    if (resp == null || !resp.isSuccess || resp.data == null) {
                        val err = resp?.error
                            ?: if (isArabic) "فشل التحقق من الأسعار" else "Price check failed"
                        showToast("❌ $err")
                        return@safeRun
                    }

                    try {
                        val manager = cartManager ?: return@safeRun
                        val freshItems = resp.data?.optJSONArray("items")
                        val unavailable = resp.data?.optJSONArray("unavailable")
                        val freshTotalUsd = resp.data?.optDouble("total_usd", 0.0) ?: 0.0
                        val freshTotalYer = resp.data?.optDouble("total_yer", 0.0) ?: 0.0
                        val cachedTotalUsd = manager.snapshot().totalUsd

                        val pricesChanged = abs(freshTotalUsd - cachedTotalUsd) > 0.01

                        if (freshItems != null) {
                            for (i in 0 until freshItems.length()) {
                                val it = freshItems.getJSONObject(i)
                                val id = it.optInt("id", 0)
                                val newPrice = it.optDouble("retail_price_usd", 0.0)
                                if (id > 0 && newPrice > 0) {
                                    manager.updateItemPrice(id, newPrice)
                                }
                            }
                        }

                        if (unavailable != null && unavailable.length() > 0) {
                            val names = StringBuilder()
                            for (i in 0 until unavailable.length()) {
                                val u = unavailable.getJSONObject(i)
                                val id = u.optInt("id", 0)
                                val serial = u.optString("serial", "")
                                if (id > 0) {
                                    manager.removeItemByItemId(id)
                                    if (names.isNotEmpty()) names.append(", ")
                                    names.append(serial.ifEmpty { "#$id" })
                                }
                            }
                            showToast(
                                if (isArabic) "⚠️ تم إزالة قطع غير متاحة: $names"
                                else "⚠️ Removed unavailable items: $names"
                            )
                        }

                        if (pricesChanged) {
                            val msg = if (isArabic) {
                                "💰 تم تحديث الأسعار. الإجمالي الجديد: ${String.format(Locale.US, "%,.0f", freshTotalYer)} YER"
                            } else {
                                "💰 Prices updated. New total: ${String.format(Locale.US, "%,.0f", freshTotalYer)} YER"
                            }
                            showToast(msg)
                        }

                        val ratesObj = resp.data?.optJSONObject("exchange_rates")
                        val ratesJson = ratesObj?.toString() ?: "{}"

                        openCheckoutDialogWithTotal(freshTotalUsd, ratesJson)
                    } catch (e: Exception) {
                        Log.e(TAG, "refreshPrices parse error", e)
                        showToast("⚠️ ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "refreshPricesBeforeCheckout error", e)
                safeRun {
                    PosUIManager.dismissDialog(loadingDialog)
                    showToast("⚠️ ${e.message}")
                }
            }
        }
    }

    private fun openCheckoutDialogWithTotal(totalUsd: Double, ratesJson: String) {
        try {
            if (!isFragmentAlive()) return
            val manager = cartManager ?: return

            isCheckoutDialogShowing = true
            val snap = manager.snapshot()

            val dialog = POSCheckoutDialog.newInstance(totalUsd, snap.count, ratesJson)
            activeCheckoutDialog = dialog

            dialog.setCheckoutListener(object : POSCheckoutDialog.OnCheckoutListener {
                override fun onCheckout(data: POSCheckoutDialog.POSCheckoutData?) {
                    if (data != null) executeCheckoutDirectly(data)
                }
            })

            dialog.setOnDismissListener(object : POSCheckoutDialog.OnDialogDismissListener {
                override fun onDialogDismissed() {
                    isCheckoutDialogShowing = false
                    activeCheckoutDialog = null
                }
            })

            val fm = parentFragmentManager
            if (!fm.isStateSaved) {
                dialog.show(fm, "POSCheckoutDialog")
            } else {
                isCheckoutDialogShowing = false
                activeCheckoutDialog = null
            }
        } catch (e: Exception) {
            isCheckoutDialogShowing = false
            activeCheckoutDialog = null
            Log.e(TAG, "openCheckoutDialogWithTotal error", e)
        }
    }

    private fun executeCheckoutDirectly(data: POSCheckoutDialog.POSCheckoutData) {
        if (!isFragmentAlive()) return
        if (!hasPosPermission() || !canPerformCheckout()) {
            showToast(getString(R.string.pos_msg_no_permission))
            return
        }

        var loadingDialog: AlertDialog? = null
        try {
            isButtonsBusyState = true
            loadingDialog = PosUIManager.showLoadingDialog(requireContext(), isArabic)
            val finalLoadingDialog = loadingDialog

            miraExecutor?.networkExecutor?.execute {
                try {
                    val ctx = getSafeAppContext() ?: return@execute
                    val client = MiraApiClient.getInstance(ctx)
                    val payload = buildCheckoutPayload(client, data)
                    val response = client.checkout(payload)

                    safeRun {
                        PosUIManager.dismissDialog(finalLoadingDialog)
                        isButtonsBusyState = false

                        if (response != null && response.isSuccess) {
                            if (activeCheckoutDialog != null && activeCheckoutDialog?.isAdded == true) {
                                activeCheckoutDialog?.onCheckoutSuccess()
                            }
                            handleSaleSuccess(response, data, false)
                        } else if (response != null && response.isNetworkError) {
                            if (activeCheckoutDialog != null && activeCheckoutDialog?.isAdded == true) {
                                activeCheckoutDialog?.onCheckoutSuccess()
                            }
                            saveOfflineSale(payload, data)
                            handleSaleSuccess(response, data, true)
                        } else {
                            val errorMsg = response?.error ?: "حدث خطأ غير معروف"
                            when (response?.errorCode) {
                                "RATE_MISMATCH" -> {
                                    currentSaleUuid = null
                                    val msg = if (isArabic) {
                                        "⚠️ سعر الصرف غير محدّث — أغلق النافذة وافتحها مجدداً"
                                    } else {
                                        "⚠️ Exchange rate outdated — close and reopen"
                                    }
                                    if (activeCheckoutDialog?.isAdded == true) {
                                        activeCheckoutDialog?.onCheckoutError(msg)
                                    } else {
                                        showToast(msg)
                                    }
                                    return@safeRun
                                }
                                "UUID_REQUIRED", "INVALID_UUID" -> {
                                    currentSaleUuid = null
                                    val msg = if (isArabic) {
                                        "⚠️ خطأ في معرف العملية — أعد المحاولة"
                                    } else {
                                        "⚠️ Transaction ID error — retry"
                                    }
                                    if (activeCheckoutDialog?.isAdded == true) {
                                        activeCheckoutDialog?.onCheckoutError(msg)
                                    }
                                    return@safeRun
                                }
                            }

                            if (activeCheckoutDialog != null && activeCheckoutDialog?.isAdded == true) {
                                activeCheckoutDialog?.onCheckoutError(errorMsg)
                            } else {
                                PosUIManager.showRetryDialog(
                                    requireContext(),
                                    getString(R.string.pos_dialog_retry_title),
                                    errorMsg,
                                    { executeCheckoutDirectly(data) },
                                    isArabic
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "executeCheckoutDirectly error", e)
                    safeRun {
                        PosUIManager.dismissDialog(finalLoadingDialog)
                        isButtonsBusyState = false
                        val errText = e.message ?: if (isArabic) "حدث خطأ غير متوقع" else "Unexpected error"
                        if (activeCheckoutDialog != null && activeCheckoutDialog?.isAdded == true) {
                            activeCheckoutDialog?.onCheckoutError(
                                if (isArabic) "خطأ في الاتصال: $errText" else "Network error: $errText"
                            )
                        } else {
                            PosUIManager.showRetryDialog(
                                requireContext(),
                                getString(R.string.pos_dialog_error_title),
                                errText,
                                { executeCheckoutDirectly(data) },
                                isArabic
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            loadingDialog?.let { dlg -> safeRun { PosUIManager.dismissDialog(dlg) } }
            isButtonsBusyState = false
            Log.e(TAG, "executeCheckoutDirectly outer error", e)
        }
    }

    private fun buildCheckoutPayload(
        client: MiraApiClient?,
        data: POSCheckoutDialog.POSCheckoutData
    ): JSONObject {
        val payload = JSONObject()
        val manager = cartManager!!
        val pm = priceManager!!

        payload.put("action", "COMPLETE_POS_SALE")
        payload.put("gate_id", client?.gateId ?: "")
        payload.put("branch_id", getActiveBranchId())
        payload.put("user_id", sessionManager?.userId ?: 0)

        if (currentSaleUuid.isNullOrEmpty()) {
            currentSaleUuid = UUID.randomUUID().toString()
        }
        payload.put("local_uuid", currentSaleUuid)
        payload.put("username", sessionManager?.username ?: "")

        val customerName = if (!data.customerName.isNullOrBlank()) {
            data.customerName.trim()
        } else {
            if (isArabic) "عميل نقدي" else "Cash Customer"
        }

        val customerPhone = if (!data.customerPhone.isNullOrBlank() && data.customerPhone.trim() != "—") {
            data.customerPhone.trim()
        } else {
            ""
        }

        payload.put("customer_name", customerName)
        payload.put("customer_phone", customerPhone)

        val customerObj = JSONObject().apply {
            put("name", customerName)
            put("phone", customerPhone)
            put("email", data.customerEmail ?: "")
            put("address", data.customerAddress ?: "")
            put("notes", data.customerNotes ?: "")
            put("payment_method", data.paymentMethod ?: "cash")
            put("discount_amount_usd", data.discountAmount)
        }
        payload.put("customer", customerObj)

        if (data.tradeIns != null && data.tradeIns.length() > 0) {
            val tradeInsArr = JSONArray()
            for (i in 0 until data.tradeIns.length()) {
                try {
                    val src = data.tradeIns.getJSONObject(i)
                    val obj = JSONObject().apply {
                        put("weight_grams", src.optDouble("weight_grams", 0.0))
                        put("karat", src.optString("karat", "21K"))
                        put("description", src.optString("description", "ذهب كسر"))
                    }
                    tradeInsArr.put(obj)
                } catch (_: Exception) {
                }
            }
            payload.put("trade_ins", tradeInsArr)
        }

        val itemsArr = JSONArray()
        for (item in manager.items) {
            if (item == null) continue
            val obj = item.toJson()
            if (obj.optString("serial_no", "").isEmpty()) {
                val fallback = when {
                    item.tid.isNotEmpty() -> item.tid
                    item.rfidEpc.isNotEmpty() -> item.rfidEpc
                    else -> item.epc
                }
                obj.put("serial_no", fallback ?: "")
            }
            itemsArr.put(obj)
        }
        payload.put("items", itemsArr)
        payload.put("item_count", manager.size())

        val subtotalUsd = data.originalTotal
        val discountUsd = data.discountAmount
        val finalUsd = data.finalAmount

        var yerRate = pm.getExchangeRate("YER")
        if (yerRate <= 0) yerRate = 530.0

        payload.put("subtotal_usd", subtotalUsd)
        payload.put("discount_usd", discountUsd)
        payload.put("total_usd", finalUsd)
        payload.put("total_amount_usd", finalUsd)
        payload.put("subtotal", subtotalUsd * yerRate)
        payload.put("discount", discountUsd * yerRate)
        payload.put("total", finalUsd * yerRate)
        payload.put("total_amount", finalUsd * yerRate)

        val paymentsArray = if (data.payments != null && data.payments.length() > 0) {
            data.payments
        } else {
            val defaultCurrency = pm.defaultCurrency
            var defaultRate = pm.getExchangeRate(defaultCurrency)
            if (defaultRate <= 0) defaultRate = yerRate

            JSONArray().apply {
                put(JSONObject().apply {
                    put("currency", defaultCurrency)
                    put("amount", finalUsd * yerRate / defaultRate)
                    put("exchange_rate", defaultRate)
                    put("base_amount", finalUsd * yerRate)
                    put("method", data.paymentMethod ?: "cash")
                })
            }
        }
        payload.put("payments", paymentsArray)

        val payment = JSONObject().apply {
            put("subtotal_usd", subtotalUsd)
            put("discount_amount_usd", discountUsd)
            put("final_amount_usd", finalUsd)
            put("subtotal", subtotalUsd * yerRate)
            put("discount_amount", discountUsd * yerRate)
            put("final_amount", finalUsd * yerRate)
            put("exchange_rate", yerRate)
            put("currency_code", "YER")
            put("payment_method", data.paymentMethod ?: "cash")
        }
        payload.put("payment", payment)
        payload.put("notes", if (isArabic) "بيع POS" else "POS Sale")
        return payload
    }

    private fun saveOfflineSale(payload: JSONObject, data: POSCheckoutDialog.POSCheckoutData) {
        try {
            val queue = pendingSalesQueue ?: return
            val localUuid = payload.optString("local_uuid", "")
            if (localUuid.isEmpty()) return

            val sale = PendingSale().apply {
                this.localUuid = localUuid
                this.payloadJson = payload.toString()
                this.branchId = getActiveBranchId()
                this.userId = sessionManager?.userId ?: 0
                this.totalUsd = data.finalAmount
                this.itemCount = cartManager?.size() ?: 0
                this.customerName = data.customerName ?: ""
                this.createdAt = System.currentTimeMillis()
                this.status = PendingSale.STATUS_PENDING
            }

            if (queue.enqueue(sale)) {
                updatePendingSalesBadge()
                showToast(
                    if (isArabic) "📥 سيُرسَل البيع تلقائياً عند عودة الاتصال"
                    else "📥 Sale will be sent automatically when online"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveOfflineSale error", e)
        }
    }

    private fun updateCacheAfterSale(soldItems: List<CartItem>) {
        if (soldItems.isEmpty()) return
        try {
            val cache = MiraLocalItemCache.getInstance(getSafeAppContext()) ?: return
            for (item in soldItems) {
                if (!item.epc.isNullOrEmpty()) cache.markItemAsSold(item.epc)
                if (!item.rfidEpc.isNullOrEmpty() && item.rfidEpc != item.epc) {
                    cache.markItemAsSold(item.rfidEpc)
                }
                if (!item.serialNo.isNullOrEmpty()) cache.markItemAsSold(item.serialNo)
                if (!item.gtin.isNullOrEmpty()) cache.markItemAsSold(item.gtin)
                if (!item.tid.isNullOrEmpty()) cache.markItemAsSold(item.tid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateCacheAfterSale error", e)
        }
    }

    private fun handleSaleSuccess(
        response: MiraApiClient.ApiResponse,
        data: POSCheckoutDialog.POSCheckoutData,
        isOffline: Boolean
    ) {
        if (!isFragmentAlive()) return
        try {
            playAlertSound(1)
            lastSaleResponse = response
            lastSaleResponseTime = System.currentTimeMillis()
            currentSaleUuid = null

            val soldItems = ArrayList(cartManager?.items ?: emptyList())
            val saleId = if (isOffline) 0L else extractSaleIdFromResponse(response)

            getSafeAppContext()?.let { appCtx ->
                GateSaleBridge.onSaleCompleted(
                    appCtx,
                    soldItems,
                    saleId,
                    data.finalAmount,
                    getActiveBranchId(),
                    sessionManager?.userId ?: 0,
                    isOffline
                )
            }

            updateCacheAfterSale(soldItems)
            cartManager?.clear()

            if (!isOffline) {
                showReceipt(response)
                PosUIManager.notifyDataChanged(requireContext(), "sale", getActiveBranchId())
                showToast(getString(R.string.pos_msg_sale_success))
            } else {
                showToast(getString(R.string.pos_msg_sale_offline))
                PosUIManager.notifyDataChanged(requireContext(), "sale_offline", getActiveBranchId())
            }

            rfidReadCountState = 0
            heroState = HeroVisualState.IDLE
        } catch (e: Exception) {
            Log.e(TAG, "handleSaleSuccess error", e)
        }
    }

    private fun showReceipt(response: MiraApiClient.ApiResponse?) {
        try {
            if (!isFragmentAlive()) return
            var receiptJson = "{}"
            if (response != null) {
                if (response.data != null) {
                    receiptJson = response.data.toString()
                } else if (!response.body.isNullOrEmpty()) {
                    receiptJson = response.body
                }
            }

            val receiptFragment = MiraReceiptFragment.newInstance(receiptJson)
            val fm = parentFragmentManager
            if (!fm.isStateSaved) {
                receiptFragment.show(fm, "MiraReceiptFragment")
            }
        } catch (e: Exception) {
            Log.e(TAG, "showReceipt error", e)
        }
    }

    private fun extractSaleIdFromResponse(response: MiraApiClient.ApiResponse?): Long {
        val data = response?.data ?: return 0L
        return try {
            var id = data.optLong("sale_id", data.optLong("saleId", data.optLong("id", 0L)))
            if (id > 0) return id
            val sale = data.optJSONObject("sale")
            if (sale != null) {
                id = sale.optLong("id", sale.optLong("sale_id", 0L))
                if (id > 0) return id
            }
            0L
        } catch (_: Exception) {
            0L
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Refund V2
    // ═══════════════════════════════════════════════════════════════════

    private fun showRefundDialog() {
        if (!isFragmentAlive()) return
        if (!canPerformRefund()) {
            showToast(if (isArabic) "⛔ غير مصرح بالاسترجاع" else "⛔ Not authorized to refund")
            return
        }

        try {
            isRefundDialogHiddenForScan = false
            refundDialog = RefundDialog(requireContext(), object : RefundDialog.RefundCallback {
                override fun onRefundSuccess(result: JSONObject?) {
                    handleRefundSuccessV2(result)
                }

                override fun onBarcodeScan() {
                    isRefundDialogHiddenForScan = true
                    refundDialog?.hide()
                    openRefundBarcodeScanner()
                }

                override fun onDialogClosed() {}
            }).apply {
                setOnHardwareKeyListener { _, k, e -> myOnKeyDwon(k, e) }
                show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "showRefundDialog error", e)
        }
    }

    private fun handleRefundSuccessV2(result: JSONObject?) {
        if (result == null) return
        try {
            val refundNumber = result.optString("refund_number", "")
            val amountLocal = result.optDouble("amount_local", 0.0)
            val method = result.optString("method", "cash")
            val isFull = result.optBoolean("is_full", false)

            val items = result.optJSONArray("items")
            if (items != null && items.length() > 0) {
                val appCtx = getSafeAppContext()
                val cache = if (appCtx != null) MiraLocalItemCache.getInstance(appCtx) else null
                if (cache != null) {
                    for (i in 0 until items.length()) {
                        try {
                            val it = items.getJSONObject(i)
                            val epc = it.optString("epc", "")
                            val serial = it.optString("serial", "")
                            if (epc.isNotEmpty()) cache.markItemAsAvailable(epc)
                            if (serial.isNotEmpty() && serial != epc) cache.markItemAsAvailable(serial)
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            val methodLabel = if (method.equals("credit", ignoreCase = true)) {
                if (isArabic) "رصيد دائن" else "Store Credit"
            } else {
                if (isArabic) "نقدي" else "Cash"
            }

            val msg = if (isArabic) {
                "✅ تم الإرجاع بنجاح!\n\n" +
                        "رقم الإرجاع: $refundNumber\n" +
                        "المبلغ: ${String.format(Locale.US, "%,.2f", amountLocal)} YER\n" +
                        "الطريقة: $methodLabel" +
                        if (isFull) "\n\n(إرجاع كامل)" else "\n\n(إرجاع جزئي)"
            } else {
                "✅ Refund successful!\n\n" +
                        "Refund #: $refundNumber\n" +
                        "Amount: ${String.format(Locale.US, "%,.2f", amountLocal)} YER\n" +
                        "Method: $methodLabel" +
                        if (isFull) "\n\n(Full refund)" else "\n\n(Partial refund)"
            }

            PosUIManager.showResultDialog(
                requireContext(),
                if (isArabic) "تم الإرجاع" else "Refund Success",
                msg,
                true,
                isArabic
            )
            PosUIManager.notifyDataChanged(requireContext(), "refund", getActiveBranchId())
        } catch (e: Exception) {
            Log.e(TAG, "handleRefundSuccessV2 error", e)
        }
    }

    private fun openRefundBarcodeScanner() {
        if (!isFragmentAlive()) return
        if (!hasPosPermission() || !canPerformRefund()) {
            showToast(if (isArabic) "⛔ غير مصرح" else "⛔ Not authorized")
            return
        }

        val fm = parentFragmentManager
        if (fm.isStateSaved) {
            refundDialog?.show()
            isRefundDialogHiddenForScan = false
            return
        }

        val scannerFragment = MiraBarcodeScannerFragment()
        scannerFragment.setOnBarcodeScannedListener { rawData, _ ->
            try {
                if (rawData.isNullOrEmpty()) return@setOnBarcodeScannedListener
                val parsed = parseGs1OrQr(rawData)
                val data = parsed[0]
                if (data.isEmpty()) return@setOnBarcodeScannedListener

                if (refundDialog != null && refundDialog?.isShowing == false) {
                    refundDialog?.show()
                    isRefundDialogHiddenForScan = false
                }
                refundDialog?.setQueryFromScan(data)
            } catch (e: Exception) {
                Log.e(TAG, "Refund barcode callback error", e)
            }
        }

        var containerId = android.R.id.content
        try {
            val parent = view?.parent
            if (parent is View && parent.id != View.NO_ID) {
                containerId = parent.id
            }
        } catch (_: Exception) {
        }

        fm.beginTransaction()
            .hide(this)
            .add(containerId, scannerFragment, "refund_barcode_scanner")
            .addToBackStack("refund_barcode_scanner")
            .commit()
    }

    private fun checkAutoOpenRefundIntent() {
        val args = arguments ?: return
        if (args.getBoolean("auto_open_refund", false)) {
            val jsonStr = args.getString("refund_data_json")
            args.remove("auto_open_refund")
            args.remove("refund_data_json")

            if (!jsonStr.isNullOrEmpty()) {
                try {
                    val activityData = JSONObject(jsonStr)
                    val code = extractCodeForRefund(activityData)
                    if (code.isNotEmpty()) {
                        mainHandler.postDelayed({
                            if (!isFragmentAlive()) return@postDelayed
                            showRefundDialog()
                            if (refundDialog?.isShowing == true) {
                                refundDialog?.setQueryFromScan(code)
                            }
                        }, 500)
                    } else {
                        showToast(if (isArabic) "⚠️ لم يتم العثور على رمز القطعة" else "⚠️ No item code found")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing auto refund data", e)
                }
            }
        }
    }

    private fun extractCodeForRefund(data: JSONObject): String {
        try {
            val epc = data.optString("epc", data.optString("rfid_epc", ""))
            if (epc.isNotEmpty()) return epc
            val serial = data.optString("serial", data.optString("serial_number", ""))
            if (serial.isNotEmpty()) return serial

            val items = data.optJSONArray("items")
            if (items != null && items.length() > 0) {
                val firstItem = items.optJSONObject(0)
                if (firstItem != null) {
                    val itemEpc = firstItem.optString("epc", firstItem.optString("rfid_epc", ""))
                    if (itemEpc.isNotEmpty()) return itemEpc
                    val itemSerial = firstItem.optString("serial", firstItem.optString("serial_number", ""))
                    if (itemSerial.isNotEmpty()) return itemSerial
                }
            }
        } catch (_: Exception) {
        }
        return ""
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Offline Queue & Sync
    // ═══════════════════════════════════════════════════════════════════

    private fun updatePendingSalesBadge() {
        try {
            pendingSalesCountState = pendingSalesQueue?.countPending() ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "updatePendingSalesBadge error", e)
        }
    }

    private fun openPendingSalesSheet() {
        try {
            if (!isFragmentAlive()) return
            PendingSalesBottomSheet().show(parentFragmentManager, "PendingSalesSheet")
        } catch (e: Exception) {
            Log.e(TAG, "openPendingSalesSheet error", e)
        }
    }

    private fun triggerOfflineSyncIfNeeded() {
        try {
            val syncer = offlineSyncer ?: return
            val queue = pendingSalesQueue ?: return
            if (syncer.isSyncing || queue.countPending() == 0) return

            syncer.syncAll(object : OfflineSalesSyncer.SyncCallback {
                override fun onSyncStart(total: Int) {}

                override fun onSaleSynced(sale: PendingSale, success: Boolean, error: String?) {
                    if (success && sale.payloadJson != null) {
                        try {
                            val payload = JSONObject(sale.payloadJson)
                            val items = payload.optJSONArray("items") ?: return
                            val ctx = getSafeAppContext() ?: return
                            val cache = OfflineGateCache.getInstance(ctx)
                            for (i in 0 until items.length()) {
                                val item = items.getJSONObject(i)
                                val epc = item.optString("epc", "")
                                val tid = item.optString("tid", "")
                                if (epc.isNotEmpty()) cache.markAsSynced(epc)
                                if (tid.isNotEmpty() && tid != epc) cache.markAsSynced(tid)
                            }
                        } catch (_: Exception) {
                        }
                    }
                }

                override fun onSyncComplete(synced: Int, failed: Int, remaining: Int) {
                    updatePendingSalesBadge()
                    if (synced > 0 && isFragmentAlive()) {
                        showToast(
                            if (isArabic) "✅ تمت مزامنة $synced من البيوع المعلّقة"
                            else "✅ Synced $synced pending sales"
                        )
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "triggerOfflineSyncIfNeeded error", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Public APIs (UHFMainActivity Compatibility)
    // ═══════════════════════════════════════════════════════════════════

    fun showLastReceiptDialog() {
        try {
            if (!hasPosPermission()) {
                showToast(getString(R.string.pos_msg_no_permission))
                return
            }
            if (lastSaleResponse == null || (System.currentTimeMillis() - lastSaleResponseTime) > LAST_SALE_VALIDITY_MS) {
                showToast(if (isArabic) "لا يوجد إيصال حديث" else "No recent receipt")
                return
            }
            showReceipt(lastSaleResponse)
        } catch (e: Exception) {
            Log.e(TAG, "showLastReceiptDialog error", e)
        }
    }

    fun focusSearchField() {
        switchScanMode(ScanMode.MANUAL)
    }

    @Deprecated("Kept for backward compatibility")
    fun showCartBottomSheet() {
    }

    @Deprecated("Kept for backward compatibility")
    fun showDiscountDialog() {
        processCheckoutAndStockUpdate()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Navigation & Back Handling
    // ═══════════════════════════════════════════════════════════════════

    private fun setupBackPressedHandler() {
        if (activity == null) return
        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleCustomBack()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback!!)
    }

    private fun handleCustomBack() {
        try {
            if (isCheckoutDialogShowing) return

            if (currentScanMode != ScanMode.NONE) {
                cancelPendingStopIfAny()
                switchScanMode(ScanMode.NONE)
                return
            }

            if (cartManager != null && cartManager?.isEmpty == false) {
                showExitDialogState = true
                return
            }

            navigateAwayFromPos()
        } catch (e: Exception) {
            Log.e(TAG, "handleCustomBack error", e)
        }
    }

    private fun navigateAwayFromPos() {
        try {
            cancelPendingStopIfAny()
            try {
                stopRFIDScan()
            } catch (_: Exception) {
            }
            try {
                UIHelper.hideKeyboard(activity)
            } catch (_: Exception) {
            }

            currentScanMode = ScanMode.NONE
            stopAfterFirstRead = false
            oneShotConsumed.set(false)

            if (!isAdded || isRemoving || isDetached) return
            val act = activity as? UHFMainActivity ?: return
            if (act.isFinishing || act.isDestroyed) return

            act.setBottomNavVisible(true)
            act.setNavigationLocked(false)
            act.clearActiveListeners()

            val nav = act.navigationManager
            if (nav != null) {
                nav.goToDashboard()
                act.syncCurrentFragment()
            } else {
                forceRestartActivity(act)
            }
        } catch (e: Exception) {
            Log.e(TAG, "navigateAwayFromPos error", e)
            (activity as? UHFMainActivity)?.let { forceRestartActivity(it) }
        }
    }

    private fun forceRestartActivity(activity: UHFMainActivity) {
        try {
            val intent = Intent(activity, UHFMainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            activity.startActivity(intent)
            activity.finish()
        } catch (e: Exception) {
            Log.e(TAG, "forceRestartActivity error", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose UI Screen
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun MiraPosScreen() {
        val colors = MiraTheme.colors
        val isAr = isArabic

        // Auto-dismiss top scan feedback banner after 2.5s
        LaunchedEffect(scanFeedbackState?.id) {
            if (scanFeedbackState != null) {
                delay(2500)
                scanFeedbackState = null
            }
        }

        // Auto-submit debounce for manual input
        LaunchedEffect(manualCodeInputState, isManualInputVisibleState) {
            if (!isManualInputVisibleState) return@LaunchedEffect
            val code = manualCodeInputState.trim()
            if (!shouldAutoSubmit(code)) return@LaunchedEffect
            val delayMs = if (looksLikePartialSerial(code)) SHORT_CODE_DELAY_MS else AUTO_ADD_DELAY_MS
            delay(delayMs)
            if (isManualInputVisibleState && code == manualCodeInputState.trim() && isFragmentAlive()) {
                submitManualCode(code)
            }
        }

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
                    // 1. شريط الرأس (Top POS Header)
                    PosTopHeaderBar(
                        isArabic = isAr,
                        branchName = activeBranchNameState,
                        scaleText = scaleStatusTextState,
                        isScaleConnected = isScaleConnectedState,
                        pendingSalesCount = pendingSalesCountState,
                        canRefund = canPerformRefundState,
                        onScaleClick = { scaleManager?.showSettingsDialog() },
                        onPendingSalesClick = { openPendingSalesSheet() },
                        onLastReceiptClick = { showLastReceiptDialog() },
                        onRefundClick = { showRefundDialog() },
                        onCloseClick = { handleCustomBack() }
                    )

                    // 2. شريط ملخص السلة العلوي
                    PosSummaryBar(
                        isArabic = isAr,
                        itemCount = cartCountState,
                        statusText = statusMessageState,
                        totalFormatted = buildTotalText(cartCountState, cartTotalUsdState),
                        hasUnavailable = cartHasUnavailableState
                    )

                    // 3. شريط المسح السريع والإدخال اليدوي (عند وجود قطع في السلة)
                    if (cartCountState > 0) {
                        QuickScanActionStrip(
                            isArabic = isAr,
                            hasRfidHardware = hasRfidHardwareState,
                            isRfidListening = currentScanMode == ScanMode.RFID,
                            isManualOpen = isManualInputVisibleState,
                            enabled = canUsePosState && !isButtonsBusyState,
                            onGs1Click = { switchScanMode(ScanMode.GS1) },
                            onRfidClick = {
                                showToast(
                                    if (isAr) "🎯 استخدم زر الزناد في القارئ للمسح"
                                    else "🎯 Use the physical trigger to scan"
                                )
                            },
                            onRfidLongClick = { openPosPowerSettings() },
                            onManualClick = {
                                switchScanMode(
                                    if (currentScanMode == ScanMode.MANUAL) ScanMode.NONE else ScanMode.MANUAL
                                )
                            }
                        )
                    }

                    // 4. حقل الإدخال اليدوي المنبثق
                    AnimatedVisibility(
                        visible = isManualInputVisibleState,
                        enter = fadeIn() + slideInVertically { -it / 2 },
                        exit = fadeOut() + slideOutVertically { -it / 2 }
                    ) {
                        ManualCodeEntryCard(
                            isArabic = isAr,
                            code = manualCodeInputState,
                            enabled = canUsePosState && !isButtonsBusyState,
                            onCodeChange = { manualCodeInputState = it },
                            onSubmit = { submitManualCode(manualCodeInputState) },
                            onClose = { switchScanMode(ScanMode.NONE) }
                        )
                    }

                    // 5. منطقة المحتوى الرئيسية: شاشة الترحيب التفاعلية أو قائمة السلة
                    Box(modifier = Modifier.weight(1f)) {
                        if (cartCountState == 0) {
                            PosEmptyHeroStage(
                                isArabic = isAr,
                                state = heroState,
                                rfidCount = rfidReadCountState,
                                hasRfidHardware = hasRfidHardwareState,
                                isRfidConnected = isRfidConnectedState,
                                enabled = canUsePosState && !isButtonsBusyState,
                                onGs1Click = { switchScanMode(ScanMode.GS1) },
                                onRfidClick = {
                                    showToast(
                                        if (isAr) "🎯 استخدم زر الزناد في القارئ للمسح"
                                        else "🎯 Use the physical trigger to scan"
                                    )
                                },
                                onRfidLongClick = { openPosPowerSettings() },
                                onManualClick = {
                                    switchScanMode(
                                        if (currentScanMode == ScanMode.MANUAL) ScanMode.NONE else ScanMode.MANUAL
                                    )
                                }
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                itemsIndexed(
                                    items = cartItemsState,
                                    key = { index, item -> "${item.epc}_${item.serialNo}_$index" }
                                ) { index, item ->
                                    PosCartItemCard(
                                        isArabic = isAr,
                                        item = item,
                                        formattedPrice = buildTotalText(1, item.price),
                                        onRemove = { cartManager?.removeItemAt(index) }
                                    )
                                }
                            }
                        }
                    }

                    // 6. شريط إتمام البيع السفلي (يظهر عند وجود عناصر في السلة)
                    AnimatedVisibility(
                        visible = cartCountState > 0,
                        enter = fadeIn() + slideInVertically { it },
                        exit = fadeOut() + slideOutVertically { it }
                    ) {
                        PosStickyCheckoutFooter(
                            isArabic = isAr,
                            itemCount = cartCountState,
                            totalFormatted = buildTotalText(cartCountState, cartTotalUsdState),
                            canCheckout = cartCountState > 0 && !cartHasUnavailableState && canUsePosState && !isButtonsBusyState,
                            canClear = cartCountState > 0 && !isButtonsBusyState,
                            onClearClick = {
                                cartManager?.clear()
                                currentSaleUuid = null
                            },
                            onCheckoutClick = { processCheckoutAndStockUpdate() }
                        )
                    }
                }

                // 7. بطاقة إشعار المسح العلوية العائمة (Scan Feedback Overlay)
                AnimatedVisibility(
                    visible = scanFeedbackState != null,
                    enter = fadeIn() + slideInVertically { -it },
                    exit = fadeOut() + slideOutVertically { -it },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 64.dp)
                ) {
                    scanFeedbackState?.let { banner ->
                        ScanFeedbackToastCard(banner = banner)
                    }
                }
            }
        }

        // نافذة تأكيد المغادرة عند وجود أصناف في السلة
        if (showExitDialogState) {
            AlertDialog(
                onDismissRequest = { showExitDialogState = false },
                containerColor = colors.cardSurface,
                title = {
                    Text(
                        text = if (isAr) "مغادرة شاشة البيع؟" else "Leave POS?",
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = if (isAr) {
                            "لديك $cartCountState عنصر في السلة.\nهل تريد المغادرة؟ (سيتم الاحتفاظ بالسلة)"
                        } else {
                            "You have $cartCountState items in cart.\nLeave anyway? (Cart will be saved)"
                        },
                        color = colors.textSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showExitDialogState = false
                            navigateAwayFromPos()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.goldPrimary,
                            contentColor = Color(0xFF0A0F1D)
                        )
                    ) {
                        Text(if (isAr) "مغادرة" else "Leave", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExitDialogState = false }) {
                        Text(if (isAr) "البقاء" else "Stay", color = colors.textSecondary)
                    }
                }
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Compose UI Components
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun PosTopHeaderBar(
        isArabic: Boolean,
        branchName: String,
        scaleText: String,
        isScaleConnected: Boolean,
        pendingSalesCount: Int,
        canRefund: Boolean,
        onScaleClick: () -> Unit,
        onPendingSalesClick: () -> Unit,
        onLastReceiptClick: () -> Unit,
        onRefundClick: () -> Unit,
        onCloseClick: () -> Unit
    ) {
        val colors = MiraTheme.colors

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // زر الإغلاق + العنوان
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    modifier = Modifier
                        .size(38.dp)
                        .clickable { onCloseClick() },
                    shape = RoundedCornerShape(12.dp),
                    color = colors.cardSurface,
                    border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.18f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = null,
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = if (isArabic) "نقطة البيع" else "MIRA POS",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (branchName.isNotEmpty()) {
                        Text(
                            text = branchName,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.goldPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // الأدوات العلوية: شارة المزامنة المعلقة + حالة الميزان + الإيصال الأخير + الاسترجاع
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (pendingSalesCount > 0) {
                    Surface(
                        modifier = Modifier.clickable { onPendingSalesClick() },
                        shape = RoundedCornerShape(50),
                        color = Color(0xFFF59E0B).copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.CloudSync,
                                contentDescription = null,
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = pendingSalesCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF59E0B)
                            )
                        }
                    }
                }

                // مؤشر الميزان الذكي
                val scaleColor = if (isScaleConnected) Color(0xFF10B981) else colors.textSecondary
                Surface(
                    modifier = Modifier.clickable { onScaleClick() },
                    shape = RoundedCornerShape(50),
                    color = scaleColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, scaleColor.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = scaleText.ifEmpty { "⚖️" },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scaleColor,
                        maxLines = 1
                    )
                }

                // آخر إيصال
                Surface(
                    modifier = Modifier
                        .size(36.dp)
                        .clickable { onLastReceiptClick() },
                    shape = RoundedCornerShape(10.dp),
                    color = colors.cardSurface,
                    border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.3f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.ReceiptLong,
                            contentDescription = null,
                            tint = colors.goldPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // الاسترجاع (للمصرح لهم)
                if (canRefund) {
                    Surface(
                        modifier = Modifier
                            .size(36.dp)
                            .clickable { onRefundClick() },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFEF4444).copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.AssignmentReturn,
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PosSummaryBar(
        isArabic: Boolean,
        itemCount: Int,
        statusText: String,
        totalFormatted: String,
        hasUnavailable: Boolean
    ) {
        val colors = MiraTheme.colors
        val active = itemCount > 0

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(
                1.dp,
                if (active) colors.goldPrimary.copy(alpha = 0.45f)
                else colors.textSecondary.copy(alpha = 0.14f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.goldPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = itemCount.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.goldPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (isArabic) "إجمالي السلة" else "Cart Summary",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (hasUnavailable) Color(0xFFEF4444) else Color(0xFF10B981)
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (isArabic) "المبلغ الإجمالي" else "Total Amount",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                    Text(
                        text = totalFormatted,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (active) colors.goldPrimary else colors.textPrimary
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun QuickScanActionStrip(
        isArabic: Boolean,
        hasRfidHardware: Boolean,
        isRfidListening: Boolean,
        isManualOpen: Boolean,
        enabled: Boolean,
        onGs1Click: () -> Unit,
        onRfidClick: () -> Unit,
        onRfidLongClick: () -> Unit,
        onManualClick: () -> Unit
    ) {
        val colors = MiraTheme.colors

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // GS1 / QR
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clickable(enabled = enabled) { onGs1Click() },
                shape = RoundedCornerShape(12.dp),
                color = colors.cardSurface,
                border = BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.4f))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Icon(
                        Icons.Rounded.QrCodeScanner,
                        contentDescription = null,
                        tint = Color(0xFF06B6D4),
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isArabic) "باركود GS1" else "GS1 Scan",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        maxLines = 1
                    )
                }
            }

            // RFID Trigger
            if (hasRfidHardware) {
                val rfidColor = if (isRfidListening) Color(0xFF10B981) else colors.goldPrimary
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .combinedClickable(
                            enabled = enabled,
                            onClick = onRfidClick,
                            onLongClick = onRfidLongClick
                        ),
                    shape = RoundedCornerShape(12.dp),
                    color = if (isRfidListening) rfidColor.copy(alpha = 0.16f) else colors.cardSurface,
                    border = BorderStroke(1.dp, rfidColor.copy(alpha = 0.45f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Contactless,
                            contentDescription = null,
                            tint = rfidColor,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "RFID",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = rfidColor,
                            maxLines = 1
                        )
                    }
                }
            }

            // Manual Input
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clickable(enabled = enabled) { onManualClick() },
                shape = RoundedCornerShape(12.dp),
                color = if (isManualOpen) colors.goldPrimary.copy(alpha = 0.16f) else colors.cardSurface,
                border = BorderStroke(
                    1.dp,
                    if (isManualOpen) colors.goldPrimary else colors.textSecondary.copy(alpha = 0.25f)
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Icon(
                        Icons.Rounded.Keyboard,
                        contentDescription = null,
                        tint = if (isManualOpen) colors.goldPrimary else colors.textSecondary,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isArabic) "إدخال يدوي" else "Manual",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        maxLines = 1
                    )
                }
            }
        }
    }

    @Composable
    private fun ManualCodeEntryCard(
        isArabic: Boolean,
        code: String,
        enabled: Boolean,
        onCodeChange: (String) -> Unit,
        onSubmit: () -> Unit,
        onClose: () -> Unit
    ) {
        val colors = MiraTheme.colors
        val focusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current

        LaunchedEffect(Unit) {
            try {
                focusRequester.requestFocus()
                keyboardController?.show()
            } catch (_: Exception) {
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    enabled = enabled,
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = if (isArabic) "أدخل الرقم التسلسلي أو EPC..." else "Enter Serial or EPC...",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    },
                    trailingIcon = {
                        if (code.isNotEmpty()) {
                            IconButton(onClick = { onCodeChange("") }) {
                                Icon(
                                    Icons.Rounded.Clear,
                                    contentDescription = null,
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                )

                Button(
                    onClick = onSubmit,
                    enabled = enabled && code.trim().isNotEmpty(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.goldPrimary,
                        contentColor = Color(0xFF0A0F1D)
                    )
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                }

                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun PosEmptyHeroStage(
        isArabic: Boolean,
        state: HeroVisualState,
        rfidCount: Int,
        hasRfidHardware: Boolean,
        isRfidConnected: Boolean,
        enabled: Boolean,
        onGs1Click: () -> Unit,
        onRfidClick: () -> Unit,
        onRfidLongClick: () -> Unit,
        onManualClick: () -> Unit
    ) {
        val colors = MiraTheme.colors

        val infiniteTransition = rememberInfiniteTransition(label = "hero_pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(1600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_scale"
        )

        val ringColor = when (state) {
            HeroVisualState.RFID_LISTENING -> Color(0xFF10B981)
            HeroVisualState.GS1_SCANNING -> Color(0xFF06B6D4)
            HeroVisualState.ITEM_ADDED -> Color(0xFF22C55E)
            HeroVisualState.ERROR -> Color(0xFFEF4444)
            HeroVisualState.MANUAL_ENTRY -> colors.goldPrimary
            HeroVisualState.IDLE -> colors.goldPrimary
        }

        val heroIcon = when (state) {
            HeroVisualState.RFID_LISTENING -> Icons.Rounded.Contactless
            HeroVisualState.GS1_SCANNING -> Icons.Rounded.QrCodeScanner
            HeroVisualState.ITEM_ADDED -> Icons.Rounded.CheckCircle
            HeroVisualState.ERROR -> Icons.Rounded.ErrorOutline
            HeroVisualState.MANUAL_ENTRY -> Icons.Rounded.Keyboard
            HeroVisualState.IDLE -> Icons.Rounded.PointOfSale
        }

        val heroTitle = when (state) {
            HeroVisualState.RFID_LISTENING -> if (isArabic) "جاري الاستماع لبطاقات RFID..." else "Listening for RFID Tags..."
            HeroVisualState.GS1_SCANNING -> if (isArabic) "ماسح الباركود نشط" else "Barcode Scanner Active"
            HeroVisualState.ITEM_ADDED -> if (isArabic) "تمت إضافة القطعة للسلة" else "Item Added to Cart"
            HeroVisualState.ERROR -> if (isArabic) "تعذر إضافة القطعة" else "Could Not Add Item"
            HeroVisualState.MANUAL_ENTRY -> if (isArabic) "الإدخال اليدوي السريع" else "Quick Manual Entry"
            HeroVisualState.IDLE -> if (isArabic) "جاهز لبدء عملية البيع" else "Ready to Start Sale"
        }

        val heroSubtitle = when (state) {
            HeroVisualState.RFID_LISTENING -> if (isArabic) "قرّب بطاقة المجوهرات من القارئ" else "Bring jewelry tag near reader"
            HeroVisualState.MANUAL_ENTRY -> if (isArabic) "اكتب الرقم التسلسلي أعلاه للإضافة الفورية" else "Type serial number above to add"
            else -> if (isArabic) "امسح بطاقة القطعة بالزناد أو الباركود أو أدخل الرقم يدوياً" else "Scan via RFID trigger, barcode, or manual entry"
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // الحلقة التفاعلية المركزية
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .scale(if (state == HeroVisualState.RFID_LISTENING) pulseScale else 1f)
                    .clip(CircleShape)
                    .background(ringColor.copy(alpha = 0.12f))
                    .border(2.dp, ringColor.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .clip(CircleShape)
                        .background(ringColor.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = heroIcon,
                        contentDescription = null,
                        tint = ringColor,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }

            if (state == HeroVisualState.RFID_LISTENING && rfidCount > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xFF10B981).copy(alpha = 0.18f)
                ) {
                    Text(
                        text = if (isArabic) "تمت قراءة: $rfidCount" else "Read: $rfidCount",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10B981)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = heroTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = heroSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(26.dp))

            // بطاقات طرق المسح الثلاث
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. GS1 / QR
                ScanMethodTile(
                    modifier = Modifier.weight(1f),
                    title = if (isArabic) "باركود GS1" else "GS1 / QR",
                    subtitle = if (isArabic) "كاميرا" else "Camera",
                    icon = Icons.Rounded.QrCodeScanner,
                    accentColor = Color(0xFF06B6D4),
                    enabled = enabled,
                    onClick = onGs1Click
                )

                // 2. UHF RFID (يخفى تلقائياً إذا الجهاز لا يدعم RFID)
                if (hasRfidHardware) {
                    ScanMethodTile(
                        modifier = Modifier.weight(1f),
                        title = "UHF RFID",
                        subtitle = if (isArabic) "الزناد • مطول للطاقة" else "Trigger • Hold Power",
                        icon = Icons.Rounded.Contactless,
                        accentColor = if (isRfidConnected) colors.goldPrimary else colors.textSecondary,
                        enabled = enabled,
                        onClick = onRfidClick,
                        onLongClick = onRfidLongClick
                    )
                }

                // 3. الإدخال اليدوي
                ScanMethodTile(
                    modifier = Modifier.weight(1f),
                    title = if (isArabic) "إدخال يدوي" else "Manual",
                    subtitle = if (isArabic) "رقم تسلسلي" else "Serial / EPC",
                    icon = Icons.Rounded.Keyboard,
                    accentColor = Color(0xFF10B981),
                    enabled = enabled,
                    onClick = onManualClick
                )
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ScanMethodTile(
        modifier: Modifier = Modifier,
        title: String,
        subtitle: String,
        icon: ImageVector,
        accentColor: Color,
        enabled: Boolean,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null
    ) {
        val colors = MiraTheme.colors

        Card(
            modifier = modifier
                .height(114.dp)
                .combinedClickable(
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick
                ),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    @Composable
    private fun PosCartItemCard(
        isArabic: Boolean,
        item: CartItem,
        formattedPrice: String,
        onRemove: () -> Unit
    ) {
        val colors = MiraTheme.colors
        val borderColor = if (item.isAvailable) {
            colors.textSecondary.copy(alpha = 0.16f)
        } else {
            Color(0xFFEF4444).copy(alpha = 0.6f)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, borderColor)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // أيقونة العيار
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.goldPrimary.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = (item.karat ?: "").ifEmpty { "21K" },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.goldPrimary
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // تفاصيل القطعة
                Column(modifier = Modifier.weight(1f)) {
                    val itemTitle = (item.name ?: "").ifEmpty {
                        if (isArabic) "قطعة مجوهرات" else "Jewelry Item"
                    }
                    Text(
                        text = itemTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = String.format(Locale.US, "⚖️ %.2fg", item.weight),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )

                        if (item.scaleWeight > 0.0) {
                            Text(
                                text = String.format(Locale.US, "(ميزان: %.2fg)", item.scaleWeight),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF10B981)
                            )
                        }
                    }

                    val codeLabel = (item.serialNo ?: "").ifEmpty { item.epc ?: "" }
                    if (codeLabel.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = codeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (!item.isAvailable) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isArabic) "⚠️ غير متاحة للبيع (${item.status})" else "⚠️ Unavailable (${item.status})",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFEF4444),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // السعر وزر الحذف
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formattedPrice,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.goldPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        modifier = Modifier
                            .size(32.dp)
                            .clickable { onRemove() },
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFEF4444).copy(alpha = 0.14f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PosStickyCheckoutFooter(
        isArabic: Boolean,
        itemCount: Int,
        totalFormatted: String,
        canCheckout: Boolean,
        canClear: Boolean,
        onClearClick: () -> Unit,
        onCheckoutClick: () -> Unit
    ) {
        val colors = MiraTheme.colors

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colors.cardSurface,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.25f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // زر مسح السلة
                OutlinedButton(
                    onClick = onClearClick,
                    enabled = canClear,
                    modifier = Modifier.height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.45f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isArabic) "مسح" else "Clear",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }

                // زر إتمام البيع الرئيسي
                Button(
                    onClick = onCheckoutClick,
                    enabled = canCheckout,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.goldPrimary,
                        contentColor = Color(0xFF0A0F1D),
                        disabledContainerColor = colors.goldPrimary.copy(alpha = 0.35f),
                        disabledContentColor = Color(0xFF0A0F1D).copy(alpha = 0.5f)
                    )
                ) {
                    Icon(Icons.Rounded.ShoppingBag, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isArabic) "إتمام البيع ($itemCount) • $totalFormatted"
                        else "Checkout ($itemCount) • $totalFormatted",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    @Composable
    private fun ScanFeedbackToastCard(banner: ScanFeedbackBanner) {
        val colors = MiraTheme.colors
        val icon = when {
            banner.isError -> Icons.Rounded.ErrorOutline
            banner.isWarning -> Icons.Rounded.WarningAmber
            else -> Icons.Rounded.CheckCircle
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.5.dp, banner.accentColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(banner.accentColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = banner.accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = banner.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!banner.details.isNullOrEmpty()) {
                        Text(
                            text = banner.details,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!banner.price.isNullOrEmpty()) {
                        Text(
                            text = banner.price,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = banner.accentColor
                        )
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers & Utilities
    // ═══════════════════════════════════════════════════════════════════

    private fun buildTotalText(count: Int, totalUsd: Double): String {
        if (count <= 0) {
            val def = getString(R.string.pos_default_amount)
            return if (def.trim().isEmpty()) "0" else def
        }
        priceManager?.let { pm ->
            try {
                val formatted = pm.formatPriceDefault(totalUsd)
                if (!formatted.isNullOrBlank() && formatted.trim() != "—" && formatted.any { it.isDigit() }) {
                    return formatted
                }
            } catch (_: Exception) {
            }
        }
        return String.format(Locale.US, "$%,.2f", totalUsd)
    }

    private fun looksLikePartialSerial(code: String?): Boolean {
        if (code == null) return false
        val len = code.length
        if (len < PARTIAL_SERIAL_MIN_LEN || len > PARTIAL_SERIAL_MAX_LEN) return false
        return code.all { it.isDigit() }
    }

    private fun shouldAutoSubmit(code: String?): Boolean {
        if (code.isNullOrEmpty()) return false
        if (looksLikePartialSerial(code)) return code.length >= 4
        if (code.length < AUTO_ADD_MIN_LEN) return false
        if (code.length < 10 && !code.contains("-")) return false
        return true
    }

    private fun buildSearchCandidates(code: String): List<String> {
        val candidates = ArrayList<String>()
        candidates.add(code)
        if (looksLikePartialSerial(code)) {
            for (prefix in SERIAL_PREFIXES) {
                val candidate = prefix + code
                if (!candidates.contains(candidate)) {
                    candidates.add(candidate)
                }
            }
        }
        return candidates
    }

    private fun isFragmentAlive(): Boolean {
        return isAdded && !isRemoving && !isDetached &&
                activity != null && !requireActivity().isFinishing && !requireActivity().isDestroyed
    }

    private fun getSafeAppContext(): Context? {
        return try {
            context?.applicationContext
        } catch (_: Exception) {
            null
        }
    }

    private fun showToast(msg: String) {
        PosUIManager.toast(context, msg)
    }

    private fun safeRun(r: Runnable?) {
        if (r == null) return
        try {
            activity?.runOnUiThread {
                if (isFragmentAlive()) {
                    try {
                        r.run()
                    } catch (e: Exception) {
                        Log.e(TAG, "safeRun inner error", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "safeRun error", e)
        }
    }

    private fun playAlertSound(id: Int) {
        try {
            (activity as? UHFMainActivity)?.playSound(id)
        } catch (e: Exception) {
            Log.e(TAG, "playAlertSound error", e)
        }
    }

    private fun getActiveBranchId(): Int {
        return try {
            branchesManager?.activeBranchId ?: 1
        } catch (_: Exception) {
            1
        }
    }

    private fun getKaratNumber(karat: String?): Int {
        if (karat == null) return 21
        val clean = karat.trim().uppercase(Locale.US).replace("K", "")
        return try {
            val num = clean.toInt()
            if (num in 10..24) num else 21
        } catch (_: NumberFormatException) {
            21
        }
    }

    private fun extractString(obj: JSONObject?, vararg keys: String): String {
        if (obj == null) return ""
        for (key in keys) {
            try {
                val v = obj.optString(key, "")
                if (v.isNotEmpty() && v != "null") return v
            } catch (_: Exception) {
            }
        }
        return ""
    }
}
