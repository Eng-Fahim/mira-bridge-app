package com.example.uhf.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.uhf.MiraRfid.MiraIRFIDReader
import com.example.uhf.MiraRfid.RFIDManager
import com.example.uhf.R
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.api.MiraApiClient
import com.example.uhf.dialog.ScanMethodDialog
import com.example.uhf.dialog.ScanProgressDialog
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.manager.PermissionManager
import com.example.uhf.scanner.ScanMethod
import com.example.uhf.tools.SessionManager
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  MiraTransferFragment — Jetpack Compose Enterprise Edition
 * ───────────────────────────────────────────────────────────────────────────
 *  إدارة التحويلات والمناقلات المخزنية بين الفروع:
 *   • دعم كامل لزناد القارئ اليدوي (139 / 280 / 293) والمسح الجماعي المستمر
 *   • التحقق اللحظي من صلاحية كل قطعة عبر السيرفر قبل إضافتها للشحنة
 *   • منع التكرار مع إمكانية حذف أي قطعة ممسوحة بالخطأ قبل الاعتماد
 *   • دورة حياة كاملة للشحنة: إنشاء ➔ إضافة قطع ➔ إرسال إلزامي بالمسح ➔ استلام إلزامي بالمسح
 *   • بيان تفصيلي لقطع الشحنة بالأوزان والعيارات والأكواد مع إمكانية الإلغاء
 * ═══════════════════════════════════════════════════════════════════════════
 */
class MiraTransferFragment : MiraBaseFragment() {

    companion object {
        private const val TAG = "MiraTransfer"
        private const val OFFLINE_PREFS = "transfers_cache"
        const val ACTION_REFRESH_TRANSFERS = "com.example.uhf.REFRESH_TRANSFERS"

        private const val KEYCODE_TRIGGER_1 = 139
        private const val KEYCODE_TRIGGER_2 = 280
        private const val KEYCODE_TRIGGER_3 = 293
    }

    data class TransferData(
        val id: Int,
        val transferNumber: String,
        val fromBranch: String,
        val toBranch: String,
        val fromBranchId: Int,
        val toBranchId: Int,
        val status: String,
        val itemCount: Int,
        val totalWeight: Double,
        val notes: String,
        val createdAt: String
    )

    private data class BranchOption(
        val id: Int,
        val name: String
    )

    private data class ScannedTransferItem(
        val epc: String,
        val title: String,
        val serialNumber: String,
        val weight: Double,
        val karat: String,
        val isAuthorized: Boolean,
        val rejectReason: String = ""
    )

    private data class TransferManifestItem(
        val title: String,
        val serialNumber: String,
        val rfidEpc: String,
        val weight: Double,
        val karat: String
    )

    private data class TransferDialogPayload(
        val title: String,
        val message: String,
        val isError: Boolean
    )

    private data class FilterOption(
        val key: String,
        val labelAr: String,
        val labelEn: String,
        val color: Color,
        val icon: ImageVector
    )

    // ─── Services & Managers ───────────────────────────────────────────
    private var sessionManager: SessionManager? = null
    private var apiClient: MiraApiClient? = null
    private var branchesManager: MiraBranchesManager? = null
    private var permissionManager: PermissionManager? = null
    private var offlineCache: SharedPreferences? = null
    private var appContext: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var executor: ExecutorService? = null
    private var toneGenerator: ToneGenerator? = null

    // ─── Hardware RFID Scan State ──────────────────────────────────────
    @Volatile private var isTriggerDown = false
    @Volatile private var isRfidSweepActive = false
    private val isStoppingReader = AtomicBoolean(false)
    private var transferRfidListener: MiraIRFIDReader.OnTagReadListener? = null
    private var scanProgressDialog: ScanProgressDialog? = null

    private var isArabic = true
    private var selectedTransfer: TransferData? = null

    // ─── Compose Reactive States ───────────────────────────────────────
    private val allTransfersState = mutableStateListOf<TransferData>()
    private val displayedTransfersState = mutableStateListOf<TransferData>()

    private var currentFilterState by mutableStateOf("")
    private var searchQueryState by mutableStateOf("")
    private var activeBranchNameState by mutableStateOf("")
    private var canCreateTransferState by mutableStateOf(false)
    private var canViewTransfersState by mutableStateOf(true)
    private var isLoadingTransfersState by mutableStateOf(false)

    // New Transfer Dialog State
    private var showNewTransferSheetState by mutableStateOf(false)
    private val availableBranchesState = mutableStateListOf<BranchOption>()
    private var isLoadingBranchesState by mutableStateOf(false)

    // Scan & Verify Items Sheet State
    private var activeScanTransferState by mutableStateOf<TransferData?>(null)
    private val scannedItemsState = mutableStateListOf<ScannedTransferItem>()
    private val activeScanEpcSet = HashSet<String>()
    private val verifyingEpcSet = HashSet<String>()
    private var manualEpcInputState by mutableStateOf("")
    private var isRfidScanningUiState by mutableStateOf(false)
    private var isSavingTransferItemsState by mutableStateOf(false)

    // Manifest / Items View State
    private var manifestTransferState by mutableStateOf<TransferData?>(null)
    private val manifestItemsState = mutableStateListOf<TransferManifestItem>()
    private var isLoadingManifestState by mutableStateOf(false)

    // Action Sheet & Confirmation Dialogs
    private var actionSheetTransferState by mutableStateOf<TransferData?>(null)
    private var detailsTransferState by mutableStateOf<TransferData?>(null)
    private var cancelConfirmTransferState by mutableStateOf<TransferData?>(null)
    private var showTransferPickerState by mutableStateOf(false)

    private var loadingOverlayMessageState by mutableStateOf<String?>(null)
    private var feedbackDialogState by mutableStateOf<TransferDialogPayload?>(null)

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null && ACTION_REFRESH_TRANSFERS == intent.action) {
                loadTransfers()
            }
        }
    }

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
                    TransferManagementScreen()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx = requireContext()
        appContext = ctx.applicationContext
        executor = Executors.newFixedThreadPool(2)

        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar") ||
            Locale.getDefault().language == "ar"
        view.layoutDirection = if (isArabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

        apiClient = MiraApiClient.getInstance(ctx)
        branchesManager = MiraBranchesManager.getInstance(ctx)
        permissionManager = PermissionManager.getInstance(ctx)
        offlineCache = ctx.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE)
        sessionManager = SessionManager(ctx)

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (_: Exception) {
        }

        applyPermissions()
        activeBranchNameState = branchesManager?.getActiveBranchDisplayName() ?: ""

        LocalBroadcastManager.getInstance(ctx).registerReceiver(
            refreshReceiver,
            IntentFilter(ACTION_REFRESH_TRANSFERS)
        )

        loadTransfers()
    }

    override fun onResume() {
        super.onResume()
        (activity as? UHFMainActivity)?.setBottomNavVisible(false)
        activity?.findViewById<View>(R.id.appBarLayout)?.visibility = View.GONE

        activeBranchNameState = branchesManager?.getActiveBranchDisplayName() ?: ""
        loadTransfers()
    }

    override fun onPause() {
        super.onPause()
        isTriggerDown = false
        stopRfidSweep()

        (activity as? UHFMainActivity)?.setBottomNavVisible(true)
        activity?.findViewById<View>(R.id.appBarLayout)?.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        isTriggerDown = false
        stopRfidSweep()
        loadingOverlayMessageState = null

        context?.let {
            try {
                LocalBroadcastManager.getInstance(it).unregisterReceiver(refreshReceiver)
            } catch (_: Exception) {
            }
        }
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor?.takeIf { !it.isShutdown }?.shutdownNow()
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Exception) {
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Physical RFID Trigger Integration (Handheld Reader)
    // ═══════════════════════════════════════════════════════════════════

    override fun myOnKeyDwon(keyCode: Int, event: KeyEvent): Boolean {
        val isTrigger = keyCode == KEYCODE_TRIGGER_1 ||
            keyCode == KEYCODE_TRIGGER_2 ||
            keyCode == KEYCODE_TRIGGER_3
        if (!isTrigger) return false

        // إذا كانت نافذة إضافة القطع مفتوحة، استخدم الزناد للمسح الجماعي المباشر
        val targetTransfer = activeScanTransferState ?: return false

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (isTriggerDown) return true
            if (isSavingTransferItemsState) return true

            isTriggerDown = true
            startRfidSweep(targetTransfer)
            return true
        }

        if (event.action == KeyEvent.ACTION_UP) {
            if (!isTriggerDown) return true
            isTriggerDown = false
            stopRfidSweep()
            return true
        }

        return false
    }

    private fun startRfidSweep(transfer: TransferData) {
        val ctx = appContext ?: return
        if (isRfidSweepActive) return

        val mgr = RFIDManager.getInstance(ctx) ?: return
        isRfidSweepActive = true
        isRfidScanningUiState = true

        val listener = object : MiraIRFIDReader.OnTagReadListener {
            override fun onTagRead(epc: String?, rssi: Int) {
                if (!isRfidSweepActive || epc.isNullOrBlank()) return
                onRfidTagScanned(epc)
            }

            override fun onScanStarted() {}
            override fun onScanStopped() {}
            override fun onConnectionChanged(c: Boolean) {}
            override fun onError(error: String?) {
                Log.e(TAG, "RFID sweep error: $error")
            }
        }

        transferRfidListener = listener
        mgr.addListener(listener)

        mgr.startScan(object : RFIDManager.ReadyCallback {
            override fun onReady(reader: MiraIRFIDReader) {
                Log.i(TAG, "✅ Transfer RFID sweep started for ${transfer.transferNumber}")
            }

            override fun onError(reason: String?) {
                mainHandler.post {
                    stopRfidSweep()
                    showToast(
                        if (isArabic) "❌ تعذر تشغيل قارئ RFID: ${reason ?: ""}"
                        else "❌ RFID reader error: ${reason ?: ""}"
                    )
                }
            }
        })
    }

    private fun stopRfidSweep() {
        val wasActive = isRfidSweepActive
        isRfidSweepActive = false
        isRfidScanningUiState = false

        if (!wasActive) return
        val ctx = appContext ?: return
        val exec = executor ?: return
        if (exec.isShutdown) return

        if (!isStoppingReader.compareAndSet(false, true)) return

        val listenerToRemove = transferRfidListener
        transferRfidListener = null

        try {
            exec.execute {
                try {
                    val mgr = RFIDManager.getInstance(ctx) ?: return@execute
                    if (listenerToRemove != null) {
                        try {
                            mgr.removeListener(listenerToRemove)
                        } catch (_: Exception) {
                        }
                    }
                    try {
                        mgr.stopScan()
                    } catch (_: Exception) {
                    }
                } finally {
                    isStoppingReader.set(false)
                }
            }
        } catch (_: Exception) {
            isStoppingReader.set(false)
        }
    }

    fun onRfidTagScanned(epc: String?) {
        if (epc.isNullOrBlank()) return
        val cleanEpc = epc.trim().uppercase(Locale.US)

        mainHandler.post {
            if (!isAdded || activeScanTransferState == null) return@post
            if (activeScanEpcSet.contains(cleanEpc) || verifyingEpcSet.contains(cleanEpc)) return@post
            verifyAndAddEpcToTransfer(cleanEpc)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Permissions & Role Checks
    // ═══════════════════════════════════════════════════════════════════

    private fun applyPermissions() {
        val role = sessionManager?.roleString ?: ""
        val isTopLevel = "SYSTEM_ADMIN".equals(role, ignoreCase = true) ||
            "ORG_ADMIN".equals(role, ignoreCase = true) ||
            "ADMIN".equals(role, ignoreCase = true)

        val pm = permissionManager
        canCreateTransferState = isTopLevel ||
            "BRANCH_MANAGER".equals(role, ignoreCase = true) ||
            (pm?.canEdit("transfers") == true)

        canViewTransfersState = isTopLevel || (pm?.canView("transfers") == true)
    }

    private fun canCreateTransfer(): Boolean {
        val pm = permissionManager ?: return canCreateTransferState
        return canCreateTransferState || pm.canEdit("transfers") || pm.isManager || pm.isInventoryManager
    }

    private fun canApproveTransfer(): Boolean {
        val pm = permissionManager ?: return false
        return pm.isManager || pm.isBranchManager || pm.canEdit("transfers")
    }

    private fun canReceiveTransfer(t: TransferData?): Boolean {
        if (t == null) return false
        val userBranchId = branchesManager?.activeBranchId ?: 0
        if (t.fromBranchId == userBranchId) return false

        val role = sessionManager?.roleString ?: ""
        val isTopLevel = "SYSTEM_ADMIN".equals(role, ignoreCase = true) ||
            "ORG_ADMIN".equals(role, ignoreCase = true) ||
            "ADMIN".equals(role, ignoreCase = true)

        return isTopLevel || (t.toBranchId == userBranchId)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Data Loading, Filtering & Offline Cache
    // ═══════════════════════════════════════════════════════════════════

    private fun loadTransfers() {
        val exec = executor ?: return
        if (exec.isShutdown) return
        val client = apiClient ?: return

        isLoadingTransfersState = true

        exec.execute {
            val branchId = branchesManager?.activeBranchId ?: 0
            val role = sessionManager?.roleString ?: ""
            val isTopLevel = "SYSTEM_ADMIN".equals(role, ignoreCase = true) ||
                "ORG_ADMIN".equals(role, ignoreCase = true) ||
                "ADMIN".equals(role, ignoreCase = true)

            val response = try {
                client.getTransfers(branchId, "")
            } catch (e: Exception) {
                Log.e(TAG, "getTransfers failed", e)
                null
            }

            mainHandler.post {
                isLoadingTransfersState = false
                if (!isAdded || context == null) return@post

                allTransfersState.clear()
                if (response != null && response.isSuccess && response.data != null) {
                    val transfers = response.data!!.optJSONArray("transfers")
                    if (transfers != null) {
                        for (i in 0 until transfers.length()) {
                            val d = parseTransferData(transfers.optJSONObject(i))
                            if (d != null) {
                                if (isTopLevel || d.fromBranchId == branchId || d.toBranchId == branchId) {
                                    allTransfersState.add(d)
                                }
                            }
                        }
                    }
                    saveTransfersOffline(transfers)
                } else {
                    loadOfflineTransfers(branchId, isTopLevel)
                }

                applyFilterAndRefreshList()
            }
        }
    }

    private fun parseTransferData(t: JSONObject?): TransferData? {
        if (t == null) return null
        val id = t.optInt("id")
        return TransferData(
            id = id,
            transferNumber = t.optString("transfer_number", "TR-$id"),
            fromBranch = t.optString("from_branch_name_ar", t.optString("from_branch_name", "")),
            toBranch = t.optString("to_branch_name_ar", t.optString("to_branch_name", "")),
            fromBranchId = t.optInt("from_branch_id"),
            toBranchId = t.optInt("to_branch_id"),
            status = t.optString("status", "draft"),
            itemCount = t.optInt("item_count", 0),
            totalWeight = t.optDouble("total_weight", 0.0),
            notes = t.optString("notes", ""),
            createdAt = t.optString("created_at", "")
        )
    }

    private fun saveTransfersOffline(transfers: JSONArray?) {
        try {
            if (offlineCache == null || transfers == null) return
            offlineCache?.edit()
                ?.putString("transfers", transfers.toString())
                ?.putLong("last_update", System.currentTimeMillis())
                ?.apply()
        } catch (_: Exception) {
        }
    }

    private fun loadOfflineTransfers(branchId: Int, isTopLevel: Boolean) {
        try {
            val json = offlineCache?.getString("transfers", "") ?: ""
            if (json.isEmpty()) return
            val transfers = JSONArray(json)
            allTransfersState.clear()
            for (i in 0 until transfers.length()) {
                val d = parseTransferData(transfers.optJSONObject(i))
                if (d != null && (isTopLevel || d.fromBranchId == branchId || d.toBranchId == branchId)) {
                    allTransfersState.add(d)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun getStatusCount(key: String): Int {
        if (key.isEmpty()) return allTransfersState.size
        return allTransfersState.count { t ->
            if (key == "in_transit") {
                t.status == "in_transit" || t.status == "approved"
            } else {
                t.status == key
            }
        }
    }

    private fun applyFilterAndRefreshList() {
        val q = searchQueryState.trim().lowercase(Locale.ROOT)
        val filtered = allTransfersState.filter { t ->
            val matchesStatus = when {
                currentFilterState.isEmpty() -> true
                currentFilterState == "in_transit" -> t.status == "in_transit" || t.status == "approved"
                else -> t.status == currentFilterState
            }
            val matchesQuery = q.isEmpty() ||
                t.transferNumber.lowercase(Locale.ROOT).contains(q) ||
                t.fromBranch.lowercase(Locale.ROOT).contains(q) ||
                t.toBranch.lowercase(Locale.ROOT).contains(q) ||
                t.notes.lowercase(Locale.ROOT).contains(q)
            matchesStatus && matchesQuery
        }

        displayedTransfersState.clear()
        displayedTransfersState.addAll(filtered)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Transfer Actions & Mandatory Scan Phases
    // ═══════════════════════════════════════════════════════════════════

    private fun handleTransferAction(t: TransferData, action: String) {
        selectedTransfer = t
        when (action) {
            "approve" -> {
                if (canApproveTransfer()) {
                    openScanMethodForDispatch(t)
                } else {
                    showAccessDenied()
                }
            }
            "receive" -> {
                if (canReceiveTransfer(t)) {
                    openScanMethodForReceive(t)
                } else {
                    feedbackDialogState = TransferDialogPayload(
                        title = if (isArabic) "⛔ غير مصرح" else "⛔ Not Authorized",
                        message = if (isArabic) "فقط الفرع المستقبل يمكنه تأكيد استلام هذه الشحنة."
                        else "Only the receiving branch can confirm receipt.",
                        isError = true
                    )
                }
            }
            "scan" -> openScanItemsSheetForTransfer(t)
            "items" -> openTransferManifest(t)
        }
    }

    private fun openScanMethodForDispatch(transfer: TransferData) {
        if (!isAdded || context == null) return
        ScanMethodDialog(requireContext(), isArabic) { method ->
            startScanSession(transfer, "dispatch", method)
        }.show()
    }

    private fun openScanMethodForReceive(transfer: TransferData) {
        if (!isAdded || context == null) return
        ScanMethodDialog(requireContext(), isArabic) { method ->
            startScanSession(transfer, "receive", method)
        }.show()
    }

    private fun startScanSession(transfer: TransferData, phase: String, method: ScanMethod) {
        if (!isAdded || context == null) return
        val dialog = ScanProgressDialog(
            requireActivity(),
            isArabic,
            transfer.id,
            transfer.transferNumber,
            phase,
            object : ScanProgressDialog.OnScanCompletedListener {
                override fun onCompleted(scannedCount: Int) {
                    if (!isAdded || context == null) return
                    try {
                        if (scanProgressDialog?.isShowing == true) {
                            scanProgressDialog?.dismiss()
                        }
                    } catch (_: Exception) {
                    }

                    val msg = if (isArabic) {
                        "✅ تم ${if (phase == "dispatch") "إرسال" else "استلام"} الشحنة بنجاح ($scannedCount قطعة)"
                    } else {
                        "✅ ${if (phase == "dispatch") "Dispatched" else "Received"} successfully ($scannedCount items)"
                    }

                    feedbackDialogState = TransferDialogPayload(
                        title = if (isArabic) {
                            if (phase == "dispatch") "✅ تم الإرسال" else "✅ تم الاستلام"
                        } else {
                            if (phase == "dispatch") "✅ Dispatched" else "✅ Received"
                        },
                        message = msg,
                        isError = false
                    )
                    loadTransfers()
                    notifyDashboardUpdate()
                }

                override fun onCancelled() {
                    showToast(if (isArabic) "تم إلغاء المسح" else "Scan cancelled")
                }

                override fun onError(message: String?) {
                    if (!isAdded || context == null) return
                    feedbackDialogState = TransferDialogPayload(
                        title = if (isArabic) "❌ فشل المسح" else "❌ Scan Failed",
                        message = message ?: (if (isArabic) "حدث خطأ أثناء المسح" else "Error during scan"),
                        isError = true
                    )
                }
            }
        )
        scanProgressDialog = dialog
        dialog.show()
    }

    private fun cancelTransferConfirmed(t: TransferData) {
        if (!canCreateTransfer() && permissionManager?.isManager != true) {
            showAccessDenied()
            return
        }

        val exec = executor ?: return
        val client = apiClient ?: return
        loadingOverlayMessageState = if (isArabic) "جاري إلغاء الشحنة..." else "Cancelling transfer..."

        exec.execute {
            try {
                val payload = JSONObject().apply { put("status", "cancelled") }
                val response = client.updateTransfer(t.id, payload)
                mainHandler.post {
                    loadingOverlayMessageState = null
                    if (!isAdded || context == null) return@post

                    if (response != null && response.isSuccess) {
                        feedbackDialogState = TransferDialogPayload(
                            title = if (isArabic) "✅ تم الإلغاء" else "✅ Transfer Cancelled",
                            message = if (isArabic) "تم إلغاء الشحنة ${t.transferNumber} وإعادة القطع للمخزون المتاح."
                            else "Transfer ${t.transferNumber} cancelled and items released.",
                            isError = false
                        )
                        loadTransfers()
                        notifyDashboardUpdate()
                    } else {
                        feedbackDialogState = TransferDialogPayload(
                            title = if (isArabic) "❌ فشل الإلغاء" else "❌ Cancel Failed",
                            message = getErrorMessage(response),
                            isError = true
                        )
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    loadingOverlayMessageState = null
                    feedbackDialogState = TransferDialogPayload(
                        title = if (isArabic) "❌ خطأ" else "❌ Error",
                        message = e.message ?: "Unknown error",
                        isError = true
                    )
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  New Transfer Creation Flow
    // ═══════════════════════════════════════════════════════════════════

    private fun openNewTransferSheet() {
        if (!canCreateTransfer()) {
            showAccessDenied()
            return
        }

        availableBranchesState.clear()
        isLoadingBranchesState = true
        showNewTransferSheetState = true

        val fromBranchId = branchesManager?.activeBranchId ?: 0
        val exec = executor ?: return
        val client = apiClient ?: return

        exec.execute {
            val response = try {
                client.getBranches()
            } catch (_: Exception) {
                null
            }

            mainHandler.post {
                isLoadingBranchesState = false
                if (!isAdded) return@post

                availableBranchesState.clear()
                if (response != null && response.isSuccess && response.data != null) {
                    val branches = response.data!!.optJSONArray("branches")
                    if (branches != null) {
                        for (i in 0 until branches.length()) {
                            val b = branches.optJSONObject(i) ?: continue
                            val bId = b.optInt("id")
                            if (bId != 0 && bId != fromBranchId) {
                                val bName = if (isArabic) {
                                    b.optString("branch_name_ar", b.optString("branch_name", "فرع #$bId"))
                                } else {
                                    b.optString("branch_name", b.optString("branch_name_ar", "Branch #$bId"))
                                }
                                availableBranchesState.add(BranchOption(bId, bName))
                            }
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Scan & Verify Items into Transfer (Handheld + Manual)
    // ═══════════════════════════════════════════════════════════════════

    private fun openScanItemsSheetForTransfer(t: TransferData) {
        activeScanEpcSet.clear()
        verifyingEpcSet.clear()
        scannedItemsState.clear()
        manualEpcInputState = ""
        isSavingTransferItemsState = false
        activeScanTransferState = t
    }

    private fun closeScanItemsSheet() {
        isTriggerDown = false
        stopRfidSweep()
        activeScanEpcSet.clear()
        verifyingEpcSet.clear()
        scannedItemsState.clear()
        manualEpcInputState = ""
        activeScanTransferState = null
    }

    private fun verifyAndAddEpcToTransfer(rawEpc: String) {
        val epc = rawEpc.trim().uppercase(Locale.US)
        if (epc.isEmpty()) return
        if (activeScanEpcSet.contains(epc) || verifyingEpcSet.contains(epc)) {
            showToast(if (isArabic) "⚠️ هذه القطعة ممسوحة مسبقاً" else "⚠️ Item already scanned")
            return
        }

        val exec = executor ?: return
        val client = apiClient ?: return
        verifyingEpcSet.add(epc)

        exec.execute {
            try {
                val params = JSONObject().apply {
                    put("branch_id", branchesManager?.activeBranchId ?: 0)
                }
                val response = client.authorize(epc, "transfer", params)

                mainHandler.post {
                    verifyingEpcSet.remove(epc)
                    if (!isAdded || activeScanTransferState == null) return@post
                    if (activeScanEpcSet.contains(epc)) return@post

                    if (response != null && response.isSuccess && response.data != null) {
                        val allowed = response.data!!.optBoolean("allowed", false)
                        val itemObj = response.data!!.optJSONObject("item")

                        if (allowed || itemObj != null) {
                            val title = itemObj?.optString("title", "")
                                ?.ifEmpty { itemObj.optString("item_title", epc) }
                                ?: epc
                            val serial = itemObj?.optString("serial_number", "") ?: ""
                            val weight = itemObj?.optDouble("gross_weight", itemObj.optDouble("gross_weight_grams", 0.0)) ?: 0.0
                            val karat = itemObj?.optString("purity_code", itemObj.optString("gold_karat", "")) ?: ""

                            activeScanEpcSet.add(epc)
                            scannedItemsState.add(
                                0,
                                ScannedTransferItem(
                                    epc = epc,
                                    title = title,
                                    serialNumber = serial,
                                    weight = weight,
                                    karat = karat,
                                    isAuthorized = true
                                )
                            )
                            playScanBeepSound()
                        } else {
                            val reason = response.data!!.optString(
                                "reason",
                                if (isArabic) "غير مسموح بنقل هذه القطعة" else "Not authorized for transfer"
                            )
                            playErrorBeepSound()
                            showToast("❌ $epc: $reason")
                        }
                    } else {
                        // وضع التوافق في حال عدم توفر استجابة السيرفر التفصيلية
                        activeScanEpcSet.add(epc)
                        scannedItemsState.add(
                            0,
                            ScannedTransferItem(
                                epc = epc,
                                title = epc,
                                serialNumber = "",
                                weight = 0.0,
                                karat = "",
                                isAuthorized = true
                            )
                        )
                        playScanBeepSound()
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    verifyingEpcSet.remove(epc)
                    if (!isAdded || activeScanTransferState == null) return@post
                    if (activeScanEpcSet.contains(epc)) return@post

                    activeScanEpcSet.add(epc)
                    scannedItemsState.add(
                        0,
                        ScannedTransferItem(
                            epc = epc,
                            title = epc,
                            serialNumber = "",
                            weight = 0.0,
                            karat = "",
                            isAuthorized = true
                        )
                    )
                    playScanBeepSound()
                }
            }
        }
    }

    private fun removeScannedItem(epc: String) {
        activeScanEpcSet.remove(epc)
        scannedItemsState.removeAll { it.epc == epc }
    }

    private fun saveScannedItemsToTransfer(t: TransferData) {
        if (activeScanEpcSet.isEmpty()) {
            showToast(
                if (isArabic) "⚠️ لا توجد قطع ممسوحة للحفظ"
                else "⚠️ No scanned items to save"
            )
            return
        }

        val exec = executor ?: return
        val client = apiClient ?: return

        isTriggerDown = false
        stopRfidSweep()
        isSavingTransferItemsState = true

        val itemsArray = JSONArray()
        activeScanEpcSet.forEach { itemsArray.put(it) }

        exec.execute {
            try {
                if (t.id == 0) {
                    val createPayload = JSONObject().apply {
                        put("from_branch_id", t.fromBranchId)
                        put("to_branch_id", t.toBranchId)
                        put("notes", t.notes)
                        put("items", itemsArray)
                    }

                    val createResponse = client.createTransfer(createPayload)
                    mainHandler.post {
                        isSavingTransferItemsState = false
                        if (!isAdded || context == null) return@post

                        if (createResponse != null && createResponse.isSuccess) {
                            closeScanItemsSheet()
                            feedbackDialogState = TransferDialogPayload(
                                title = if (isArabic) "✅ تم إنشاء الشحنة" else "✅ Transfer Created",
                                message = if (isArabic) "تم إنشاء أمر التحويل وإضافة ${itemsArray.length()} قطعة بنجاح."
                                else "Transfer order created with ${itemsArray.length()} items.",
                                isError = false
                            )
                            loadTransfers()
                            notifyDashboardUpdate()
                        } else {
                            feedbackDialogState = TransferDialogPayload(
                                title = if (isArabic) "❌ فشل الإنشاء" else "❌ Create Failed",
                                message = getErrorMessage(createResponse),
                                isError = true
                            )
                        }
                    }
                } else {
                    val addResponse = client.addItemsToTransfer(t.id, itemsArray)
                    mainHandler.post {
                        isSavingTransferItemsState = false
                        if (!isAdded || context == null) return@post

                        if (addResponse != null && addResponse.isSuccess) {
                            closeScanItemsSheet()
                            feedbackDialogState = TransferDialogPayload(
                                title = if (isArabic) "✅ تمت الإضافة بنجاح" else "✅ Items Added",
                                message = if (isArabic) "تم إضافة ${itemsArray.length()} قطعة إلى الشحنة ${t.transferNumber}."
                                else "Added ${itemsArray.length()} items to ${t.transferNumber}.",
                                isError = false
                            )
                            loadTransfers()
                            notifyDashboardUpdate()
                        } else {
                            feedbackDialogState = TransferDialogPayload(
                                title = if (isArabic) "❌ فشل الحفظ" else "❌ Save Failed",
                                message = getErrorMessage(addResponse),
                                isError = true
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    isSavingTransferItemsState = false
                    feedbackDialogState = TransferDialogPayload(
                        title = if (isArabic) "❌ خطأ" else "❌ Error",
                        message = e.message ?: "Unknown error",
                        isError = true
                    )
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Manifest / Items Viewer
    // ═══════════════════════════════════════════════════════════════════

    private fun openTransferManifest(t: TransferData) {
        manifestTransferState = t
        manifestItemsState.clear()
        isLoadingManifestState = true

        val exec = executor ?: return
        val client = apiClient ?: return

        exec.execute {
            val response = try {
                client.getTransfer(t.id)
            } catch (_: Exception) {
                null
            }

            mainHandler.post {
                isLoadingManifestState = false
                if (!isAdded || manifestTransferState == null) return@post

                manifestItemsState.clear()
                if (response != null && response.isSuccess && response.data != null) {
                    val items = response.data!!.optJSONArray("items")
                    if (items != null) {
                        for (i in 0 until items.length()) {
                            val obj = items.optJSONObject(i) ?: continue
                            manifestItemsState.add(
                                TransferManifestItem(
                                    title = obj.optString("item_title", obj.optString("title", if (isArabic) "قطعة مجوهرات" else "Jewelry Item")),
                                    serialNumber = obj.optString("serial_number", ""),
                                    rfidEpc = obj.optString("rfid_epc", obj.optString("epc", "")),
                                    weight = obj.optDouble("gross_weight_grams", obj.optDouble("gross_weight", 0.0)),
                                    karat = obj.optString("gold_karat", obj.optString("purity_code", ""))
                                )
                            )
                        }
                    }
                } else {
                    showToast(if (isArabic) "❌ تعذر تحميل قطع الشحنة" else "❌ Could not load transfer items")
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers & Audio
    // ═══════════════════════════════════════════════════════════════════

    private fun showAccessDenied() {
        feedbackDialogState = TransferDialogPayload(
            title = if (isArabic) "⛔ لا تملك صلاحية" else "⛔ Access Denied",
            message = if (isArabic) "عذراً، ليس لديك صلاحية لتنفيذ هذا الإجراء.\nيرجى التواصل مع مدير النظام."
            else "Sorry, you don't have permission to perform this action.",
            isError = true
        )
    }

    private fun getErrorMessage(response: MiraApiClient.ApiResponse?): String {
        if (response == null) return if (isArabic) "تعذر الاتصال بالسيرفر." else "Could not connect to server."
        if (!response.error.isNullOrEmpty()) return response.error!!
        if (!response.message.isNullOrEmpty()) return response.message!!
        return if (isArabic) "حدث خطأ غير متوقع." else "An unexpected error occurred."
    }

    private fun notifyDashboardUpdate() {
        val ctx = context ?: return
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(Intent("com.example.uhf.REFRESH_DASHBOARD"))
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(Intent("com.example.uhf.REFRESH_INVENTORY"))
    }

    private fun playScanBeepSound() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
        } catch (_: Exception) {
        }
    }

    private fun playErrorBeepSound() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 150)
        } catch (_: Exception) {
        }
    }

    private fun showToast(msg: String) {
        if (isAdded && context != null) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun getStatusLabel(status: String): String {
        return when (status) {
            "draft" -> if (isArabic) "مسودة" else "Draft"
            "pending" -> if (isArabic) "معلق للاعتماد" else "Pending"
            "approved" -> if (isArabic) "معتمد ومجهز" else "Approved"
            "in_transit" -> if (isArabic) "قيد النقل" else "In Transit"
            "received" -> if (isArabic) "مستلم" else "Received"
            "rejected", "cancelled" -> if (isArabic) "ملغى" else "Cancelled"
            else -> status
        }
    }

    private fun getStatusColor(status: String): Color {
        return when (status) {
            "draft" -> Color(0xFF94A3B8)
            "pending" -> Color(0xFFF59E0B)
            "approved", "in_transit" -> Color(0xFF06B6D4)
            "received" -> Color(0xFF10B981)
            "rejected", "cancelled" -> Color(0xFFEF4444)
            else -> Color(0xFF94A3B8)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose UI
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun TransferManagementScreen() {
        val colors = MiraTheme.colors
        val isAr = isArabic
        val keyboardController = LocalSoftwareKeyboardController.current

        val filterOptions = remember {
            listOf(
                FilterOption("", "الكل", "All", Color(0xFFD4AF37), Icons.Rounded.AllInclusive),
                FilterOption("draft", "مسودة", "Draft", Color(0xFF94A3B8), Icons.Rounded.EditNote),
                FilterOption("pending", "معلق", "Pending", Color(0xFFF59E0B), Icons.Rounded.HourglassTop),
                FilterOption("in_transit", "قيد النقل", "In Transit", Color(0xFF06B6D4), Icons.Rounded.LocalShipping),
                FilterOption("received", "مستلم", "Received", Color(0xFF10B981), Icons.Rounded.CheckCircle)
            )
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
                    // 1. الشريط العلوي (Header)
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
                                    .size(42.dp)
                                    .clickable {
                                        if (isAdded) parentFragmentManager.popBackStack()
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = colors.cardSurface,
                                border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.35f))
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isAr) Icons.Rounded.ArrowForward else Icons.Rounded.ArrowBack,
                                        contentDescription = null,
                                        tint = colors.goldPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = if (isAr) "التحويلات بين الفروع" else "Branch Transfers",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = (if (isAr) "📍 الفرع النشط: " else "📍 Active Branch: ") + activeBranchNameState,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.goldPrimary,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // زر التحديث
                        Surface(
                            modifier = Modifier
                                .size(42.dp)
                                .clickable(enabled = !isLoadingTransfersState) { loadTransfers() },
                            shape = RoundedCornerShape(12.dp),
                            color = colors.cardSurface,
                            border = BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.4f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    tint = Color(0xFF06B6D4),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // 2. بطاقة الإجراءات السريعة (تحويل جديد + مسح وتدقيق)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { openNewTransferSheet() },
                            enabled = canCreateTransferState,
                            modifier = Modifier
                                .weight(1.25f)
                                .height(48.dp)
                                .alpha(if (canCreateTransferState) 1f else 0.5f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.goldPrimary,
                                contentColor = Color(0xFF0A0F1D)
                            )
                        ) {
                            Icon(Icons.Rounded.AddBox, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isAr) "تحويل جديد" else "New Transfer",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                if (!canViewTransfersState) {
                                    showAccessDenied()
                                    return@OutlinedButton
                                }
                                val current = selectedTransfer
                                if (current != null && (current.status == "draft" || current.status == "pending")) {
                                    openScanItemsSheetForTransfer(current)
                                } else if (allTransfersState.isNotEmpty()) {
                                    showTransferPickerState = true
                                } else {
                                    feedbackDialogState = TransferDialogPayload(
                                        title = if (isArabic) "📭 لا توجد شحنات" else "📭 No Transfers",
                                        message = if (isArabic) "لا توجد شحنات نشطة للمسح حالياً. أنشئ تحويلاً جديداً أولاً."
                                        else "No active transfers to scan. Create a new transfer first.",
                                        isError = false
                                    )
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.2.dp, Color(0xFF06B6D4)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF06B6D4))
                        ) {
                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(19.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isAr) "مسح وتدقيق" else "Scan & Audit",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    // 3. شريط البحث السريع عن الشحنات
                    OutlinedTextField(
                        value = searchQueryState,
                        onValueChange = {
                            searchQueryState = it
                            applyFilterAndRefreshList()
                        },
                        singleLine = true,
                        placeholder = {
                            Text(
                                text = if (isAr) "ابحث برقم الشحنة أو اسم الفرع..."
                                else "Search by transfer number or branch...",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.goldPrimary, modifier = Modifier.size(20.dp))
                        },
                        trailingIcon = {
                            if (searchQueryState.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        searchQueryState = ""
                                        applyFilterAndRefreshList()
                                        keyboardController?.hide()
                                    }
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = colors.cardSurface,
                            unfocusedContainerColor = colors.cardSurface,
                            focusedBorderColor = colors.goldPrimary,
                            unfocusedBorderColor = colors.textSecondary.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    )

                    // 4. فلاتر الحالة مع العدادات الحية
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filterOptions) { option ->
                            val selected = currentFilterState == option.key
                            val count = getStatusCount(option.key)
                            val label = "${if (isAr) option.labelAr else option.labelEn} ($count)"

                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable {
                                        currentFilterState = option.key
                                        applyFilterAndRefreshList()
                                    },
                                shape = RoundedCornerShape(50),
                                color = if (selected) option.color.copy(alpha = 0.22f) else colors.cardSurface,
                                border = BorderStroke(
                                    if (selected) 1.5.dp else 1.dp,
                                    if (selected) option.color else colors.textSecondary.copy(alpha = 0.22f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = option.icon,
                                        contentDescription = null,
                                        tint = if (selected) option.color else colors.textSecondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        color = if (selected) option.color else colors.textSecondary
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = isLoadingTransfersState) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                            color = colors.goldPrimary,
                            trackColor = Color.Transparent
                        )
                    }

                    // 5. قائمة الشحنات والتحويلات
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        if (displayedTransfersState.isEmpty() && !isLoadingTransfersState) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .background(colors.goldPrimary.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.SwapHorizontalCircle,
                                        contentDescription = null,
                                        tint = colors.goldPrimary,
                                        modifier = Modifier.size(38.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = if (isAr) "لا توجد تحويلات مطابقة" else "No Matching Transfers",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colors.textPrimary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (isAr) "اختر خيار فلترة آخر أو ابدأ بإنشاء أمر تحويل جديد بين الفروع"
                                    else "Select another filter or create a new branch transfer order",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 6.dp,
                                    bottom = 24.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(
                                    items = displayedTransfersState,
                                    key = { it.id }
                                ) { transfer ->
                                    TransferCard(
                                        transfer = transfer,
                                        isArabic = isAr,
                                        onCardClick = {
                                            selectedTransfer = transfer
                                            actionSheetTransferState = transfer
                                        },
                                        onPrimaryAction = { action ->
                                            handleTransferAction(transfer, action)
                                        },
                                        onViewItems = {
                                            handleTransferAction(transfer, "items")
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        //  Modals & Dialogs
        // ═══════════════════════════════════════════════════════════════════

        // 1. نافذة إنشاء تحويل جديد
        if (showNewTransferSheetState) {
            NewTransferDialog(
                isArabic = isAr,
                fromBranchName = activeBranchNameState,
                branches = availableBranchesState,
                isLoadingBranches = isLoadingBranchesState,
                onDismiss = { showNewTransferSheetState = false },
                onProceedToScan = { targetBranch, notes ->
                    showNewTransferSheetState = false
                    val pendingTransfer = TransferData(
                        id = 0,
                        transferNumber = "TR-NEW",
                        fromBranch = activeBranchNameState,
                        toBranch = targetBranch.name,
                        fromBranchId = branchesManager?.activeBranchId ?: 0,
                        toBranchId = targetBranch.id,
                        status = "draft",
                        itemCount = 0,
                        totalWeight = 0.0,
                        notes = notes,
                        createdAt = ""
                    )
                    openScanItemsSheetForTransfer(pendingTransfer)
                }
            )
        }

        // 2. نافذة مسح وإضافة القطع للشحنة (RFID + Manual)
        activeScanTransferState?.let { transfer ->
            ScanTransferItemsDialog(
                transfer = transfer,
                isArabic = isAr,
                scannedItems = scannedItemsState,
                manualInput = manualEpcInputState,
                onManualInputChange = { manualEpcInputState = it },
                onManualAdd = {
                    val code = manualEpcInputState.trim().uppercase(Locale.US)
                    if (code.isNotEmpty()) {
                        verifyAndAddEpcToTransfer(code)
                        manualEpcInputState = ""
                    }
                },
                onRemoveItem = { epc -> removeScannedItem(epc) },
                onClearAll = {
                    activeScanEpcSet.clear()
                    scannedItemsState.clear()
                },
                isRfidScanning = isRfidScanningUiState,
                isSaving = isSavingTransferItemsState,
                onSave = { saveScannedItemsToTransfer(transfer) },
                onClose = { closeScanItemsSheet() }
            )
        }

        // 3. نافذة خيارات الشحنة (Action Menu)
        actionSheetTransferState?.let { t ->
            TransferActionsDialog(
                transfer = t,
                isArabic = isAr,
                canAddItems = (t.status == "draft" || t.status == "pending") && canCreateTransfer(),
                canDispatch = (t.status == "draft" || t.status == "pending") && canApproveTransfer(),
                canReceive = (t.status == "approved" || t.status == "in_transit") && canReceiveTransfer(t),
                canCancel = (t.status == "draft" || t.status == "pending") && (canCreateTransfer() || permissionManager?.isManager == true),
                onDismiss = { actionSheetTransferState = null },
                onViewItems = {
                    actionSheetTransferState = null
                    openTransferManifest(t)
                },
                onAddItems = {
                    actionSheetTransferState = null
                    openScanItemsSheetForTransfer(t)
                },
                onDispatch = {
                    actionSheetTransferState = null
                    openScanMethodForDispatch(t)
                },
                onReceive = {
                    actionSheetTransferState = null
                    openScanMethodForReceive(t)
                },
                onViewDetails = {
                    actionSheetTransferState = null
                    detailsTransferState = t
                },
                onCancelTransfer = {
                    actionSheetTransferState = null
                    cancelConfirmTransferState = t
                }
            )
        }

        // 4. بيان قطع الشحنة (Manifest Items Viewer)
        manifestTransferState?.let { t ->
            TransferManifestDialog(
                transfer = t,
                isArabic = isAr,
                items = manifestItemsState,
                isLoading = isLoadingManifestState,
                onDismiss = { manifestTransferState = null }
            )
        }

        // 5. تفاصيل بيان الشحنة
        detailsTransferState?.let { t ->
            AlertDialog(
                onDismissRequest = { detailsTransferState = null },
                containerColor = colors.cardSurface,
                title = {
                    Text(
                        text = if (isAr) "📋 بيان الشحنة ${t.transferNumber}" else "📋 Manifest ${t.transferNumber}",
                        color = colors.goldPrimary,
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DetailRow(if (isAr) "من فرع:" else "From:", t.fromBranch)
                        DetailRow(if (isAr) "إلى فرع:" else "To:", t.toBranch)
                        DetailRow(if (isAr) "الحالة:" else "Status:", getStatusLabel(t.status))
                        DetailRow(
                            if (isAr) "عدد القطع:" else "Items:",
                            "${t.itemCount} ${if (isAr) "قطعة" else "pcs"}"
                        )
                        DetailRow(
                            if (isAr) "الوزن الإجمالي:" else "Total Weight:",
                            String.format(Locale.US, "%.2f g", t.totalWeight)
                        )
                        DetailRow(
                            if (isAr) "التاريخ:" else "Date:",
                            if (t.createdAt.length >= 10) t.createdAt.substring(0, 10) else "-"
                        )
                        if (t.notes.isNotEmpty()) {
                            DetailRow(if (isAr) "ملاحظات:" else "Notes:", t.notes)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { detailsTransferState = null },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.goldPrimary,
                            contentColor = Color(0xFF0A0F1D)
                        )
                    ) {
                        Text(if (isAr) "حسناً" else "OK", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // 6. تأكيد إلغاء الشحنة
        cancelConfirmTransferState?.let { t ->
            AlertDialog(
                onDismissRequest = { cancelConfirmTransferState = null },
                containerColor = colors.cardSurface,
                title = {
                    Text(
                        text = if (isAr) "🚫 تأكيد إلغاء الشحنة" else "🚫 Confirm Cancellation",
                        color = Color(0xFFEF4444),
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                text = {
                    Text(
                        text = if (isAr) "هل أنت متأكد من إلغاء الشحنة ${t.transferNumber} بالكامل؟ سيتم فك ارتباط جميع القطع المضافة إليها."
                        else "Are you sure you want to cancel transfer ${t.transferNumber} completely?",
                        color = colors.textPrimary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            cancelConfirmTransferState = null
                            cancelTransferConfirmed(t)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                    ) {
                        Text(if (isAr) "تأكيد الإلغاء" else "Confirm Cancel", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { cancelConfirmTransferState = null }) {
                        Text(if (isAr) "تراجع" else "Back", color = colors.textSecondary)
                    }
                }
            )
        }

        // 7. اختيار شحنة للمسح السريع
        if (showTransferPickerState) {
            val activeList = allTransfersState.filter {
                it.status == "draft" || it.status == "pending" || it.status == "approved" || it.status == "in_transit"
            }
            AlertDialog(
                onDismissRequest = { showTransferPickerState = false },
                containerColor = colors.cardSurface,
                title = {
                    Text(
                        text = if (isAr) "اختر الشحنة للمسح" else "Select Transfer to Scan",
                        color = colors.goldPrimary,
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                text = {
                    if (activeList.isEmpty()) {
                        Text(
                            text = if (isAr) "لا توجد شحنات نشطة حالياً." else "No active transfers available.",
                            color = colors.textSecondary
                        )
                    } else {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            activeList.forEach { item ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showTransferPickerState = false
                                            selectedTransfer = item
                                            if (item.status == "approved" || item.status == "in_transit") {
                                                if (canReceiveTransfer(item)) openScanMethodForReceive(item)
                                                else openTransferManifest(item)
                                            } else {
                                                openScanItemsSheetForTransfer(item)
                                            }
                                        },
                                    shape = RoundedCornerShape(12.dp),
                                    color = colors.backgroundMid,
                                    border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.35f))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = item.transferNumber,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = colors.goldPrimary
                                        )
                                        Text(
                                            text = "${item.fromBranch} ➔ ${item.toBranch}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.textPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showTransferPickerState = false }) {
                        Text(if (isAr) "إغلاق" else "Close", color = colors.textSecondary)
                    }
                }
            )
        }

        // 8. شاشة التحميل العامة
        loadingOverlayMessageState?.let { msg ->
            Dialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
            ) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                    border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = colors.goldPrimary)
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // 9. رسائل النجاح والأخطاء
        feedbackDialogState?.let { payload ->
            AlertDialog(
                onDismissRequest = { feedbackDialogState = null },
                containerColor = colors.cardSurface,
                title = {
                    Text(
                        text = payload.title,
                        color = if (payload.isError) Color(0xFFEF4444) else Color(0xFF10B981),
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                text = {
                    Text(text = payload.message, color = colors.textPrimary)
                },
                confirmButton = {
                    Button(
                        onClick = { feedbackDialogState = null },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.goldPrimary,
                            contentColor = Color(0xFF0A0F1D)
                        )
                    ) {
                        Text(if (isAr) "موافق" else "OK", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Composable Subcomponents
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun TransferCard(
        transfer: TransferData,
        isArabic: Boolean,
        onCardClick: () -> Unit,
        onPrimaryAction: (String) -> Unit,
        onViewItems: () -> Unit
    ) {
        val colors = MiraTheme.colors
        val statusColor = getStatusColor(transfer.status)
        val statusText = getStatusLabel(transfer.status)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCardClick() },
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // الصف العلوي: رقم الشحنة + شارة الحالة
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.goldPrimary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SwapHoriz,
                                contentDescription = null,
                                tint = colors.goldPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = transfer.transferNumber,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = colors.textPrimary
                            )
                            if (transfer.createdAt.length >= 10) {
                                Text(
                                    text = transfer.createdAt.substring(0, 10),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = statusColor.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                // مسار النقل (من فرع ➔ إلى فرع)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.backgroundMid
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Store,
                                contentDescription = null,
                                tint = colors.goldPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = transfer.fromBranch,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Icon(
                            imageVector = if (isArabic) Icons.Rounded.ArrowBack else Icons.Rounded.ArrowForward,
                            contentDescription = null,
                            tint = Color(0xFF06B6D4),
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .size(18.dp)
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Storefront,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = transfer.toBranch,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // إحصائيات القطع والوزن + أزرار الإجراء السريع
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Inventory2,
                                contentDescription = null,
                                tint = colors.goldPrimary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${transfer.itemCount} ${if (isArabic) "قطعة" else "pcs"}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Scale,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = String.format(Locale.US, "%.2f g", transfer.totalWeight),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // زر الإجراء المرحلي (إضافة / اعتماد وإرسال / استلام)
                        when {
                            transfer.status == "draft" && canCreateTransfer() -> {
                                FilledTonalButton(
                                    onClick = { onPrimaryAction("scan") },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = colors.goldPrimary.copy(alpha = 0.18f),
                                        contentColor = colors.goldPrimary
                                    )
                                ) {
                                    Text(
                                        text = if (isArabic) "+ إضافة قطع" else "+ Add Items",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                            transfer.status == "pending" && canApproveTransfer() -> {
                                FilledTonalButton(
                                    onClick = { onPrimaryAction("approve") },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color(0xFFF59E0B).copy(alpha = 0.2f),
                                        contentColor = Color(0xFFF59E0B)
                                    )
                                ) {
                                    Text(
                                        text = if (isArabic) "إرسال بالمسح" else "Dispatch Scan",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                            (transfer.status == "approved" || transfer.status == "in_transit") && canReceiveTransfer(transfer) -> {
                                FilledTonalButton(
                                    onClick = { onPrimaryAction("receive") },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color(0xFF10B981).copy(alpha = 0.2f),
                                        contentColor = Color(0xFF10B981)
                                    )
                                ) {
                                    Text(
                                        text = if (isArabic) "استلام بالمسح" else "Receive Scan",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }

                        // زر عرض القطع
                        OutlinedButton(
                            onClick = onViewItems,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = if (isArabic) "القطع" else "Items",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun NewTransferDialog(
        isArabic: Boolean,
        fromBranchName: String,
        branches: List<BranchOption>,
        isLoadingBranches: Boolean,
        onDismiss: () -> Unit,
        onProceedToScan: (BranchOption, String) -> Unit
    ) {
        val colors = MiraTheme.colors
        var selectedBranch by remember(branches) { mutableStateOf(branches.firstOrNull()) }
        var notesText by remember { mutableStateOf("") }

        Dialog(onDismissRequest = onDismiss) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                border = BorderStroke(1.5.dp, colors.goldPrimary.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.SwapHoriz,
                            contentDescription = null,
                            tint = colors.goldPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isArabic) "إنشاء أمر تحويل جديد" else "New Branch Transfer",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.textPrimary
                        )
                    }

                    // الفرع المرسل (الحالي)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = colors.backgroundMid,
                        border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = if (isArabic) "من الفرع (المُرسِل)" else "From Branch (Source)",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                            Text(
                                text = "🏢 $fromBranchName",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.goldPrimary
                            )
                        }
                    }

                    // اختيار الفرع المستلم
                    Text(
                        text = if (isArabic) "إلى الفرع (الوجهة)" else "To Branch (Destination)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )

                    if (isLoadingBranches) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = colors.goldPrimary)
                        }
                    } else if (branches.isEmpty()) {
                        Text(
                            text = if (isArabic) "⚠️ لا توجد فروع أخرى متاحة حالياً" else "⚠️ No other branches available",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFF59E0B)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 170.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            branches.forEach { branch ->
                                val isSelected = selectedBranch?.id == branch.id
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedBranch = branch },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) Color(0xFF10B981).copy(alpha = 0.18f) else colors.backgroundMid,
                                    border = BorderStroke(
                                        if (isSelected) 1.5.dp else 1.dp,
                                        if (isSelected) Color(0xFF10B981) else colors.textSecondary.copy(alpha = 0.2f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "🏢 ${branch.name}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                            color = if (isSelected) Color(0xFF10B981) else colors.textPrimary
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Rounded.CheckCircle,
                                                contentDescription = null,
                                                tint = Color(0xFF10B981),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = notesText,
                        onValueChange = { notesText = it },
                        label = { Text(if (isArabic) "ملاحظات الشحنة / المندوب (اختياري)" else "Transfer Notes / Courier (Optional)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isArabic) "إلغاء" else "Cancel", color = colors.textSecondary)
                        }

                        Button(
                            onClick = {
                                val target = selectedBranch
                                if (target == null) {
                                    showToast(if (isArabic) "يرجى اختيار الفرع المستلم" else "Select destination branch")
                                } else {
                                    onProceedToScan(target, notesText.trim())
                                }
                            },
                            enabled = selectedBranch != null,
                            modifier = Modifier.weight(1.4f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.goldPrimary,
                                contentColor = Color(0xFF0A0F1D)
                            )
                        ) {
                            Text(
                                text = if (isArabic) "التالي: مسح القطع" else "Next: Scan Items",
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ScanTransferItemsDialog(
        transfer: TransferData,
        isArabic: Boolean,
        scannedItems: List<ScannedTransferItem>,
        manualInput: String,
        onManualInputChange: (String) -> Unit,
        onManualAdd: () -> Unit,
        onRemoveItem: (String) -> Unit,
        onClearAll: () -> Unit,
        isRfidScanning: Boolean,
        isSaving: Boolean,
        onSave: () -> Unit,
        onClose: () -> Unit
    ) {
        val colors = MiraTheme.colors
        val totalScannedWeight = remember(scannedItems.size) {
            scannedItems.sumOf { it.weight }
        }

        Dialog(
            onDismissRequest = { if (!isSaving) onClose() },
            properties = DialogProperties(
                dismissOnBackPress = !isSaving,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                border = BorderStroke(1.5.dp, colors.goldPrimary.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // رأس النافذة
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isArabic) "مسح قطع الشحنة" else "Scan Transfer Items",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "${transfer.transferNumber} (${transfer.fromBranch} ➔ ${transfer.toBranch})",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.goldPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(onClick = onClose, enabled = !isSaving) {
                            Icon(Icons.Rounded.Close, contentDescription = null, tint = colors.textSecondary)
                        }
                    }

                    // مؤشر حالة القارئ اليدوي + العدادات
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = if (isRfidScanning) Color(0xFF06B6D4).copy(alpha = 0.16f) else colors.backgroundMid,
                        border = BorderStroke(
                            1.dp,
                            if (isRfidScanning) Color(0xFF06B6D4) else colors.goldPrimary.copy(alpha = 0.35f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = Icons.Rounded.Sensors,
                                    contentDescription = null,
                                    tint = if (isRfidScanning) Color(0xFF06B6D4) else colors.goldPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isRfidScanning) {
                                        if (isArabic) "جاري قراءة الشرائح..." else "Reading RFID tags..."
                                    } else {
                                        if (isArabic) "اضغط زناد القارئ لإضافة القطع" else "Hold trigger to scan tags"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isRfidScanning) Color(0xFF06B6D4) else colors.textPrimary
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "${scannedItems.size} ${if (isArabic) "قطعة" else "pcs"}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colors.goldPrimary
                                )
                                if (totalScannedWeight > 0) {
                                    Text(
                                        text = String.format(Locale.US, "%.2f g", totalScannedWeight),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF10B981),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // إدخال يدوي / باركود لرمز الشريحة
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = manualInput,
                            onValueChange = onManualInputChange,
                            singleLine = true,
                            enabled = !isSaving,
                            label = { Text(if (isArabic) "إدخال EPC أو Serial يدوياً" else "Enter EPC or Serial") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onManualAdd() }),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )

                        Button(
                            onClick = onManualAdd,
                            enabled = !isSaving && manualInput.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF06B6D4),
                                contentColor = Color(0xFF0A0F1D)
                            )
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                        }
                    }

                    // قائمة القطع الممسوحة
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 260.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.backgroundMid)
                            .padding(8.dp)
                    ) {
                        if (scannedItems.isEmpty()) {
                            Text(
                                text = if (isArabic) "لم يتم مسح أي قطعة بعد.\nقرّب القطع واضغط الزناد للمسح الفوري."
                                else "No items scanned yet.\nApproach items and press trigger.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(scannedItems, key = { it.epc }) { item ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = colors.cardSurface,
                                        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.35f))
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.title,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    color = colors.textPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                val meta = buildString {
                                                    append(item.epc)
                                                    if (item.karat.isNotEmpty()) append(" • ${item.karat}")
                                                    if (item.weight > 0) append(" • ${String.format(Locale.US, "%.2f g", item.weight)}")
                                                }
                                                Text(
                                                    text = meta,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = colors.textSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            IconButton(
                                                onClick = { onRemoveItem(item.epc) },
                                                enabled = !isSaving,
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.RemoveCircleOutline,
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
                    }

                    // أزرار المسح والحفظ
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onClearAll,
                            enabled = !isSaving && scannedItems.isNotEmpty(),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
                        ) {
                            Text(if (isArabic) "تفريغ" else "Clear", fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = onSave,
                            enabled = !isSaving && scannedItems.isNotEmpty(),
                            modifier = Modifier
                                .weight(1.6f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.goldPrimary,
                                contentColor = Color(0xFF0A0F1D)
                            )
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF0A0F1D)
                                )
                            } else {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isArabic) "حفظ واعتماد (${scannedItems.size})" else "Save Items (${scannedItems.size})",
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TransferActionsDialog(
        transfer: TransferData,
        isArabic: Boolean,
        canAddItems: Boolean,
        canDispatch: Boolean,
        canReceive: Boolean,
        canCancel: Boolean,
        onDismiss: () -> Unit,
        onViewItems: () -> Unit,
        onAddItems: () -> Unit,
        onDispatch: () -> Unit,
        onReceive: () -> Unit,
        onViewDetails: () -> Unit,
        onCancelTransfer: () -> Unit
    ) {
        val colors = MiraTheme.colors

        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = colors.cardSurface,
            title = {
                Column {
                    Text(
                        text = transfer.transferNumber,
                        color = colors.goldPrimary,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "${transfer.fromBranch} ➔ ${transfer.toBranch}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionOptionRow(
                        icon = Icons.Rounded.Inventory2,
                        label = if (isArabic) "عرض بيان القطع (${transfer.itemCount})" else "View Items (${transfer.itemCount})",
                        accentColor = colors.goldPrimary,
                        onClick = onViewItems
                    )

                    if (canAddItems) {
                        ActionOptionRow(
                            icon = Icons.Rounded.AddCircleOutline,
                            label = if (isArabic) "إضافة قطع للشحنة بالمسح" else "Scan & Add Items",
                            accentColor = Color(0xFF06B6D4),
                            onClick = onAddItems
                        )
                    }

                    if (canDispatch) {
                        ActionOptionRow(
                            icon = Icons.Rounded.LocalShipping,
                            label = if (isArabic) "اعتماد وإرسال الشحنة (مسح إلزامي)" else "Dispatch Transfer (Mandatory Scan)",
                            accentColor = Color(0xFFF59E0B),
                            onClick = onDispatch
                        )
                    }

                    if (canReceive) {
                        ActionOptionRow(
                            icon = Icons.Rounded.FactCheck,
                            label = if (isArabic) "تأكيد استلام الشحنة (مسح إلزامي)" else "Receive Transfer (Mandatory Scan)",
                            accentColor = Color(0xFF10B981),
                            onClick = onReceive
                        )
                    }

                    ActionOptionRow(
                        icon = Icons.Rounded.Description,
                        label = if (isArabic) "تفاصيل بيان الشحنة" else "Transfer Manifest Details",
                        accentColor = Color(0xFF94A3B8),
                        onClick = onViewDetails
                    )

                    if (canCancel) {
                        ActionOptionRow(
                            icon = Icons.Rounded.Cancel,
                            label = if (isArabic) "إلغاء الشحنة بالكامل" else "Cancel Entire Transfer",
                            accentColor = Color(0xFFEF4444),
                            onClick = onCancelTransfer
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(if (isArabic) "إغلاق" else "Close", color = colors.textSecondary)
                }
            }
        )
    }

    @Composable
    private fun ActionOptionRow(
        icon: ImageVector,
        label: String,
        accentColor: Color,
        onClick: () -> Unit
    ) {
        val colors = MiraTheme.colors
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() },
            shape = RoundedCornerShape(12.dp),
            color = colors.backgroundMid,
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
            }
        }
    }

    @Composable
    private fun TransferManifestDialog(
        transfer: TransferData,
        isArabic: Boolean,
        items: List<TransferManifestItem>,
        isLoading: Boolean,
        onDismiss: () -> Unit
    ) {
        val colors = MiraTheme.colors
        val totalWeight = remember(items.size) { items.sumOf { it.weight } }

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                border = BorderStroke(1.5.dp, colors.goldPrimary.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = (if (isArabic) "📦 بيان قطع الشحنة " else "📦 Transfer Manifest ") + transfer.transferNumber,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = colors.goldPrimary
                            )
                            Text(
                                text = "${items.size} ${if (isArabic) "قطعة" else "pcs"} • ${String.format(Locale.US, "%.2f g", totalWeight)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF10B981),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = null, tint = colors.textSecondary)
                        }
                    }

                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = colors.goldPrimary)
                        }
                    } else if (items.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isArabic) "لا توجد قطع مضافة في هذه الشحنة بعد." else "No items in this transfer yet.",
                                color = colors.textSecondary
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(items) { item ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = colors.backgroundMid,
                                    border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.18f))
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = item.title,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = colors.textPrimary,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (item.weight > 0) {
                                                Text(
                                                    text = "${String.format(Locale.US, "%.2f g", item.weight)} ${if (item.karat.isNotEmpty()) "(${item.karat})" else ""}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = colors.goldPrimary
                                                )
                                            }
                                        }
                                        if (item.serialNumber.isNotEmpty()) {
                                            Text(
                                                text = "SN: ${item.serialNumber}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF06B6D4)
                                            )
                                        }
                                        if (item.rfidEpc.isNotEmpty()) {
                                            Text(
                                                text = "EPC: ${item.rfidEpc}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.textSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.goldPrimary,
                            contentColor = Color(0xFF0A0F1D)
                        )
                    ) {
                        Text(if (isArabic) "إغلاق البيان" else "Close Manifest", fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }

    @Composable
    private fun DetailRow(label: String, value: String) {
        val colors = MiraTheme.colors
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                textAlign = TextAlign.End
            )
        }
    }
}
