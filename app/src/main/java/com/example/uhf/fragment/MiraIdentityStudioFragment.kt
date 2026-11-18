package com.example.uhf.fragment

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.uhf.BuildConfig
import com.example.uhf.MiraRfid.MiraIRFIDReader
import com.example.uhf.MiraRfid.MiraReaderSettings
import com.example.uhf.MiraRfid.MiraRfidService
import com.example.uhf.MiraRfid.RFIDManager
import com.example.uhf.activity.BaseTabFragmentActivity
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.api.MiraApiClient
import com.example.uhf.dialog.PosPowerSettingsDialog
import com.example.uhf.manager.IdentityFormManager
import com.example.uhf.manager.IdentityPhotoManager
import com.example.uhf.manager.IdentitySyncManager
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.manager.MiraOrgProfileManager
import com.example.uhf.manager.MiraPriceManager
import com.example.uhf.manager.PermissionManager
import com.example.uhf.tools.SecureSessionManager
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import com.example.uhf.utils.BarcodeGenerator
import com.example.uhf.utils.MiraExecutorService
import com.example.uhf.utils.PricingCalculator
import com.example.uhf.utils.TagPrinter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import androidx.appcompat.app.AlertDialog


/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  MiraIdentityStudioFragment v6.5 — Jetpack Compose Luxury Edition
 * ═══════════════════════════════════════════════════════════════════════════
 */
class MiraIdentityStudioFragment : MiraBaseFragment() {

    
        companion object {
        private const val TAG = "MiraIdentityStudio"
        private const val ENABLE_TEMP_MANUAL_EPC = true // ✅ أضف هذا السطر (اجعله false لإخفاء البطاقة لاحقاً)

        private const val RFID_TIMEOUT_MS = 30000L       
        private const val MIN_TID_LENGTH = 12
        private const val MAX_TID_LENGTH = 32
        private const val MAX_PHOTOS = 5
        private const val ENABLE_TID_LENGTH_VALIDATION = true

        private const val SCAN_RETRY_INTERVAL_MS = 5000L
        private const val SCAN_POWER_DBM = 26

        private const val KEYCODE_TRIGGER_1 = 139
        private const val KEYCODE_TRIGGER_2 = 280
        private const val KEYCODE_TRIGGER_3 = 293
    }

    private data class ScanFeedbackData(
        val title: String,
        val details: String?,
        val price: String?,
        val accentColor: Color,
        val isSuccess: Boolean
    )

    private data class EncodingSuccessSummary(
        val title: String,
        val epc: String,
        val shortBarcode: String,
        val tid: String,
        val gs1Code: String,
        val qrUrl: String,
        val weight: String,
        val karat: String,
        val finalPriceUSD: Double,
        val currency: String,
        val photos: List<String>,
        val stoneType: String,
        val stoneWeightCarats: Double,
        val stoneCount: Int,
        val stoneValue: Double,
        val netWeight: Double
    )

    private data class AlertDialogPayload(
        val title: String,
        val message: String,
        val isError: Boolean
    )

    // ═══════════════════════════════════════════════════════════════════
    //  المدراء والخدمات الأساسية
    // ═══════════════════════════════════════════════════════════════════
    private var apiClient: MiraApiClient? = null
    private var branchesManager: MiraBranchesManager? = null
    private var orgProfile: MiraOrgProfileManager? = null
    private var priceManager: MiraPriceManager? = null
    private var permissionManager: PermissionManager? = null

    private var formManager: IdentityFormManager? = null
    private var photoManager: IdentityPhotoManager? = null
    private var syncManager: IdentitySyncManager? = null

    private var miraExecutor: MiraExecutorService? = null
    private var appContext: Context? = null

    private val handler = Handler(Looper.getMainLooper())
    private var rfidTimeoutRunnable: Runnable? = null
    private var scanRetryRunnable: Runnable? = null
    private var pendingHideFeedback: Runnable? = null

    // ═══════════════════════════════════════════════════════════════════
    //  حالة المسح الفيزيائي والـ Trigger (v6.5 Trigger-Safe)
    // ═══════════════════════════════════════════════════════════════════
    @Volatile private var isTriggerDown = false
    @Volatile private var savedPowerBeforeScan = -1
    @Volatile private var savedPowerBeforeEncoding = -1

    private val scanGeneration = AtomicInteger(0)
    @Volatile private var isRfidScanning = false
    @Volatile private var tagReceivedThisScan = false
    private val isProcessingTag = AtomicBoolean(false)
    private val stoppingReader = AtomicBoolean(false)

    @Volatile private var isFormLocked = false
    internal var isArabic: Boolean = true


    @Volatile private var isEpcBound = false
    private var boundEpcValue: String = ""
    @Volatile private var lastScannedEpc = ""
    @Volatile private var lastScannedTid = ""
    @Volatile private var lastScanSource = "CHAINWAY"

    private var encodingRfidListener: MiraIRFIDReader.OnTagReadListener? = null
    @Volatile private var pendingBarcodeFromScanner: String? = null

    // ═══════════════════════════════════════════════════════════════════
    //  Photo Launchers
    // ═══════════════════════════════════════════════════════════════════
    private lateinit var takePhotoLauncher: ActivityResultLauncher<Uri>
    private lateinit var pickImageLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>
    private var photoUri: Uri? = null
    private var currentPhotoPath: String? = null

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose Reactive States
    // ═══════════════════════════════════════════════════════════════════
    private var epcTextState by mutableStateOf("")
    private var statusBadgeTextState by mutableStateOf("WAITING")
    private var statusBadgeColorState by mutableStateOf(Color(0xFF94A3B8))
    private var isEpcBoundState by mutableStateOf(false)
    private var isDesktopScanModeState by mutableStateOf(false)

    private var selectedCategoryKeyState by mutableStateOf("rings")
    private var titleTextState by mutableStateOf("")
    private var selectedKaratState by mutableIntStateOf(21)
    private var grossWeightTextState by mutableStateOf("")

    private var hasStonesState by mutableStateOf(false)
    private var stoneTypeTextState by mutableStateOf("")
    private var stoneWeightTextState by mutableStateOf("")
    private var stoneCountTextState by mutableStateOf("")
    private var stoneValueTextState by mutableStateOf("")

    private var estimatedPriceTextState by mutableStateOf("0.00")
    private var netGoldWeightState by mutableDoubleStateOf(0.0)

    private var isRfidScanningState by mutableStateOf(false)
    private var isFormLockedState by mutableStateOf(false)
    private var canEditState by mutableStateOf(true)
    private var pendingSyncCountState by mutableIntStateOf(0)

    private val photoPathsState = mutableStateListOf<String>()
    private var previewPhotoPathState by mutableStateOf<String?>(null)

    private var loadingDialogState by mutableStateOf<Pair<String, String>?>(null)
    private var alertDialogState by mutableStateOf<AlertDialogPayload?>(null)
    private var draftDialogSummaryState by mutableStateOf<String?>(null)
    private var showScanMethodDialogState by mutableStateOf(false)
    private var scanFeedbackState by mutableStateOf<ScanFeedbackData?>(null)
    private var encodingSuccessState by mutableStateOf<EncodingSuccessSummary?>(null)

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
                    IdentityStudioScreen()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (context != null) {
            appContext = requireContext().applicationContext
        }

        try {
            if (savedInstanceState != null) {
                isFormLocked = savedInstanceState.getBoolean("is_form_locked", false)
                isFormLockedState = isFormLocked
                currentPhotoPath = savedInstanceState.getString("camera_photo_path", null)
            }

            initializeComponents()
            isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar") ||
                Locale.getDefault().language == "ar"
            view.layoutDirection = if (isArabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

            setupPhotoLaunchers()
            setupDraftNotificationCallback()
            applyPermissions()
            resetCalculatedDisplay()

            (activity as? UHFMainActivity)?.setActiveEncodingFragment(this)

            val exec = miraExecutor
            if (exec != null && exec.isHealthy) {
                exec.singleThreadExecutor.execute {
                    formManager?.cleanupOldDrafts()
                    formManager?.checkAndNotifyDraft()
                    photoManager?.cleanupOrphanFiles(null)
                    safeRun { updatePendingSyncBadge() }
                }
            }

            Log.d(TAG, "✅ IdentityStudio v6.5 (Compose) initialization complete")
        } catch (e: Exception) {
            Log.e(TAG, "onViewCreated error: ${e.message}", e)
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        if (savedInstanceState == null) return
        if (!savedInstanceState.getBoolean("is_epc_bound", false)) return

        val savedBoundValue = savedInstanceState.getString("epc_bound_value", null)
        if (savedBoundValue.isNullOrEmpty()) return

        val currentText = epcTextState.trim()
        if (currentText.equals(savedBoundValue, ignoreCase = true)) {
            isEpcBound = true
            isEpcBoundState = true
            boundEpcValue = savedBoundValue
            statusBadgeTextState = if (isArabic) "✅ موثّق" else "✅ VERIFIED"
            statusBadgeColorState = Color(0xFF22C55E)
        } else {
            isEpcBound = false
            isEpcBoundState = false
            statusBadgeTextState = if (isArabic) "⚠️ أعد المسح" else "⚠️ Rescan"
            statusBadgeColorState = Color(0xFFF59E0B)
        }
    }

    private fun initializeComponents() {
        val ctx = appContext ?: context?.applicationContext ?: return
        appContext = ctx

        apiClient = MiraApiClient.getInstance(ctx)
        branchesManager = MiraBranchesManager.getInstance(ctx)
        orgProfile = MiraOrgProfileManager.getInstance(ctx)
        priceManager = MiraPriceManager.getInstance(ctx)
        permissionManager = PermissionManager.getInstance(ctx)
        miraExecutor = MiraExecutorService.getInstance()
        formManager = IdentityFormManager(ctx)
        photoManager = IdentityPhotoManager(ctx, miraExecutor!!.backgroundExecutor)
        syncManager = IdentitySyncManager(ctx, miraExecutor!!.backgroundExecutor)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Draft Management
    // ═══════════════════════════════════════════════════════════════════

    private fun setupDraftNotificationCallback() {
        formManager?.setNotificationCallback(object : IdentityFormManager.DraftNotificationCallback {
            override fun onDraftFound(info: IdentityFormManager.DraftInfo, ageText: String) {
                safeRun {
                    draftDialogSummaryState = formManager?.getDraftSummary() ?: ageText
                }
            }

            override fun onDraftRestored(info: IdentityFormManager.DraftInfo) {
                safeRun {
                    showToast(if (isArabic) "📝 تم استعادة المسودة" else "📝 Draft restored")
                    syncPhotosFromManager()
                }
            }

            override fun onDraftExpired(info: IdentityFormManager.DraftInfo) {
                safeRun {
                    if (isAdded) {
                        showToast(if (isArabic) "🧹 تم حذف مسودة قديمة" else "🧹 Old draft removed")
                    }
                }
            }
        })
    }

    private fun restoreDraftIfExists() {
        val fm = formManager ?: return
        if (isFormLocked) return

        try {
            val photoPaths = ArrayList<String>()
            val restored = fm.restoreDraft(object : IdentityFormManager.FormData {
                override fun getEpc(): String = epcTextState
                override fun getTitle(): String = titleTextState
                override fun getGrossWeight(): String = grossWeightTextState
                override fun isHasStones(): Boolean = hasStonesState
                override fun getStoneType(): String = stoneTypeTextState
                override fun getStoneWeight(): String = stoneWeightTextState
                override fun getStoneCount(): String = stoneCountTextState
                override fun getStoneValue(): String = stoneValueTextState

                override fun setEpc(v: String?) {
                    safeRun { if (v != null) onEpcInputChanged(v) }
                }
                override fun setTitle(v: String?) {
                    safeRun { if (v != null) titleTextState = v }
                }
                override fun setGrossWeight(v: String?) {
                    safeRun {
                        if (v != null) {
                            grossWeightTextState = v
                            calculateEstimatedGoldValue()
                        }
                    }
                }
                override fun setStoneType(v: String?) {
                    safeRun { if (v != null) stoneTypeTextState = v }
                }
                override fun setStoneWeight(v: String?) {
                    safeRun {
                        if (v != null) {
                            stoneWeightTextState = v
                            calculateEstimatedGoldValue()
                        }
                    }
                }
                override fun setStoneCount(v: String?) {
                    safeRun { if (v != null) stoneCountTextState = v }
                }
                override fun setStoneValue(v: String?) {
                    safeRun {
                        if (v != null) {
                            stoneValueTextState = v
                            calculateEstimatedGoldValue()
                        }
                    }
                }
                override fun setHasStones(v: Boolean) {
                    safeRun {
                        hasStonesState = v
                        calculateEstimatedGoldValue()
                    }
                }
            }, photoPaths)

            if (restored) {
                if (photoManager != null && photoPaths.isNotEmpty()) {
                    photoManager?.restoreFromSavedPaths(photoPaths, photoCallback)
                }
                safeRun {
                    if (epcTextState.isNotEmpty()) {
                        isEpcBound = false
                        isEpcBoundState = false
                        statusBadgeTextState = if (isArabic) "⚠️ أعد مسح الشريحة" else "⚠️ Rescan tag"
                        statusBadgeColorState = Color(0xFFF59E0B)
                        showToast(
                            if (isArabic) "⚠️ المسودة استُعيدت — أعد مسح الشريحة قبل الحفظ"
                            else "⚠️ Draft restored — rescan tag before saving"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "restoreDraftIfExists error: ${e.message}", e)
        }
    }

    private fun saveCurrentDraftAsync() {
        val fm = formManager ?: return
        val paths = photoManager?.photoPaths ?: ArrayList()

        fm.saveDraft(object : IdentityFormManager.FormData {
            override fun getEpc(): String = epcTextState
            override fun getTitle(): String = titleTextState
            override fun getGrossWeight(): String = grossWeightTextState
            override fun isHasStones(): Boolean = hasStonesState
            override fun getStoneType(): String = stoneTypeTextState
            override fun getStoneWeight(): String = stoneWeightTextState
            override fun getStoneCount(): String = stoneCountTextState
            override fun getStoneValue(): String = stoneValueTextState

            override fun setEpc(v: String?) {}
            override fun setTitle(v: String?) {}
            override fun setGrossWeight(v: String?) {}
            override fun setStoneType(v: String?) {}
            override fun setStoneWeight(v: String?) {}
            override fun setStoneCount(v: String?) {}
            override fun setStoneValue(v: String?) {}
            override fun setHasStones(v: Boolean) {}
        }, paths)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Photos Management
    // ═══════════════════════════════════════════════════════════════════

    private val photoCallback = object : IdentityPhotoManager.PhotoCallback {
        override fun onPhotoAdded(path: String, totalCount: Int) {
            safeRun { syncPhotosFromManager() }
        }

        override fun onPhotoRemoved(path: String, totalCount: Int) {
            safeRun { syncPhotosFromManager() }
        }

        override fun onError(message: String) {
            safeRun { showToast("❌ $message") }
        }
    }

    private fun syncPhotosFromManager() {
        val paths = photoManager?.photoPaths ?: emptyList()
        photoPathsState.clear()
        photoPathsState.addAll(paths)
    }

    private fun setupPhotoLaunchers() {
        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) openCamera()
            else showToast(if (isArabic) "صلاحية الكاميرا مطلوبة" else "Camera permission required")
        }

        takePhotoLauncher = registerForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { success ->
            if (success) {
                photoManager?.compressAndAddPhoto(currentPhotoPath, photoCallback)
            }
        }

        pickImageLauncher = registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            val pm = photoManager ?: return@registerForActivityResult
            if (uris.isNullOrEmpty()) return@registerForActivityResult

            val currentCount = pm.photoCount
            val remaining = MAX_PHOTOS - currentCount
            if (remaining <= 0) {
                showToast(
                    if (isArabic) "⚠️ وصلت للحد الأقصى ($MAX_PHOTOS صور)"
                    else "⚠️ Max photos reached ($MAX_PHOTOS)"
                )
                return@registerForActivityResult
            }

            val toAdd = minOf(uris.size, remaining)
            for (i in 0 until toAdd) {
                pm.copyAndAddPhoto(uris[i], photoCallback)
            }
            if (uris.size > remaining) {
                showToast(
                    if (isArabic) "⚠️ أضيفت $toAdd فقط (الحد $MAX_PHOTOS)"
                    else "⚠️ Added $toAdd only (max $MAX_PHOTOS)"
                )
            }
        }
    }

    private fun checkCameraPermissionAndTakePhoto() {
        val ctx = context ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            openCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCamera() {
        try {
            val ctx = context ?: return
            val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            if (takePictureIntent.resolveActivity(ctx.packageManager) != null) {
                val photoFile = createImageFile()
                if (photoFile != null) {
                    val uri = FileProvider.getUriForFile(
                        ctx,
                        "${ctx.applicationContext.packageName}.fileprovider",
                        photoFile
                    )
                    photoUri = uri
                    takePhotoLauncher.launch(uri)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "openCamera error: ${e.message}", e)
        }
    }

    private fun openGallery() {
        try {
            pickImageLauncher.launch(arrayOf("image/*"))
        } catch (e: Exception) {
            Log.e(TAG, "openGallery error: ${e.message}", e)
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File? {
        val ctx = context ?: return null
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val image = File.createTempFile("MIRA_${timeStamp}_", ".jpg", storageDir)
        currentPhotoPath = image.absolutePath
        return image
    }

    // ═══════════════════════════════════════════════════════════════════
    //  🎯 Physical RFID Trigger (v6.5 Trigger-Safe)
    // ═══════════════════════════════════════════════════════════════════

    override fun myOnKeyDwon(keyCode: Int, event: KeyEvent): Boolean {
        val isTrigger = keyCode == KEYCODE_TRIGGER_1 ||
            keyCode == KEYCODE_TRIGGER_2 ||
            keyCode == KEYCODE_TRIGGER_3
        if (!isTrigger) return false

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (isTriggerDown) return true

            val pm = permissionManager
            if (pm == null || (!pm.canEdit("identity") && !pm.isManager)) {
                showToast(if (isArabic) "⛔ غير مصرح" else "⛔ Access Denied")
                return true
            }

            if (isProcessingTag.get()) {
                showToast(if (isArabic) "⏳ جاري معالجة الشريحة..." else "⏳ Processing tag...")
                return true
            }

            if (isFormLocked) {
                showToast(if (isArabic) "⛔ العملية قيد التنفيذ" else "⛔ Operation in progress")
                return true
            }

            isTriggerDown = true
            Log.i(TAG, "🎯 Trigger DOWN — starting scan")
            activateRFIDModeForEncoding()
            return true
        }

        if (event.action == KeyEvent.ACTION_UP) {
            if (!isTriggerDown) return true
            isTriggerDown = false
            Log.i(TAG, "🎯 Trigger UP — stopping scan immediately")
            if (isRfidScanning) {
                stopRfidScanningMode()
            }
            return true
        }

        return false
    }

    private fun activateRFIDModeForEncoding() {
        val pm = permissionManager ?: return
        if (!(pm.canEdit("identity") || pm.isManager)) {
            showToast(if (isArabic) "⛔ غير مصرح" else "⛔ Access Denied")
            return
        }
        val ctx = appContext ?: return
        if (isRfidScanning || !isTriggerDown) return

        val mgr = RFIDManager.getInstance(ctx) ?: return

        val myGen = scanGeneration.incrementAndGet()
        isRfidScanning = true
        updateScanningUI(true)
        applyScanPowerForReading()

        if (!mgr.isReady) {
            showToast(if (isArabic) "⏳ جاري تهيئة القارئ..." else "⏳ Initializing reader...")
            mgr.ensureReady(object : RFIDManager.ReadyCallback {
                override fun onReady(reader: MiraIRFIDReader) {
                    safeRun { onReaderReadyForScan(myGen, mgr) }
                }

                override fun onError(reason: String?) {
                    safeRun { onReaderInitError(myGen, reason) }
                }
            })
            return
        }

        startScanWithManager(myGen, mgr)
    }

    private fun onReaderReadyForScan(gen: Int, mgr: RFIDManager) {
        if (scanGeneration.get() != gen) return
        if (!isTriggerDown) {
            isRfidScanning = false
            updateScanningUI(false)
            return
        }
        if (!isAdded) {
            isRfidScanning = false
            return
        }
        startScanWithManager(gen, mgr)
    }

    private fun onReaderInitError(gen: Int, reason: String?) {
        if (scanGeneration.get() != gen) return
        isRfidScanning = false
        updateScanningUI(false)
        showErrorDialog(
            if (isArabic) "⚠️ القارئ غير متاح" else "⚠️ Reader unavailable",
            reason ?: "Unknown error"
        )
    }

        private fun startScanWithManager(gen: Int, mgr: RFIDManager) {
        if (scanGeneration.get() != gen) return
        if (!isTriggerDown) {
            isRfidScanning = false
            updateScanningUI(false)
            return
        }

        try {
            val listener = object : MiraIRFIDReader.OnTagReadListener {
                override fun onTagRead(epc: String?, rssi: Int) {
                    if (!isRfidScanning || epc.isNullOrBlank()) return
                    if (!isProcessingTag.compareAndSet(false, true)) return

                    tagReceivedThisScan = true
                    val receivedEpc = epc.trim().uppercase(Locale.US)
                    isTriggerDown = false

                    val exec = miraExecutor
                    val ctx = appContext
                    if (exec == null || ctx == null) {
                        stopRfidScanningMode()
                        restorePowerForEncoding()
                        isProcessingTag.set(false)
                        return
                    }

                    exec.backgroundExecutor.execute {
                        var tid: String? = null
                        try {
                            tid = MiraRfidService.getInstance(ctx).readTid(null)
                        } catch (e: Exception) {
                            Log.w(TAG, "readTid failed", e)
                        }

                        stopRfidScanningMode()
                        restorePowerForEncoding()

                        if (tid.isNullOrBlank()) {
                            isProcessingTag.set(false)
                            safeRun {
                                playErrorSound()
                                showErrorDialog(
                                    if (isArabic) "❌ فشل قراءة TID" else "❌ TID Read Failed",
                                    if (isArabic) "لا يمكن قراءة TID الشريحة.\n\n💡 قرّب الشريحة وأعد المحاولة."
                                    else "Cannot read tag TID.\n\n💡 Move tag closer and retry."
                                )
                            }
                            return@execute
                        }

                        val finalTid = tid.trim().uppercase(Locale.US)
                        lastScannedTid = finalTid
                        secureLog("✅ TID read: ${maskEpc(finalTid)}")
                        safeRun { processReceivedTagWithTid(receivedEpc, finalTid, "CHAINWAY") }
                    }
                }

                override fun onScanStarted() {}
                override fun onScanStopped() {}
                override fun onConnectionChanged(c: Boolean) {}
                override fun onError(error: String?) {
                    Log.e(TAG, "RFID error: $error")
                }
            }

            encodingRfidListener = listener
            mgr.addListener(listener)

            mgr.startScan(object : RFIDManager.ReadyCallback {
                override fun onReady(reader: MiraIRFIDReader) {
                    Log.i(TAG, "✅ Encoding scan started")
                }

                override fun onError(reason: String?) {
                    safeRun {
                        stopRfidScanningMode()
                        showErrorDialog(
                            if (isArabic) "❌ فشل بدء المسح" else "❌ Scan start failed",
                            reason ?: "Reader not ready"
                        )
                    }
                }
            })

            showToast(
                if (isArabic) "📡 جاري البحث عن شريحة RFID..."
                else "📡 Searching for RFID tag..."
            )

            rfidTimeoutRunnable?.let { handler.removeCallbacks(it) }
            rfidTimeoutRunnable = Runnable { stopRfidScanningMode() }
            handler.postDelayed(rfidTimeoutRunnable!!, RFID_TIMEOUT_MS)

            scheduleScanRetry(gen, mgr)
        } catch (e: Exception) {
            Log.e(TAG, "startScanWithManager error: ${e.message}", e)
            stopRfidScanningMode()
        }
    }


    private fun scheduleScanRetry(gen: Int, mgr: RFIDManager) {
        scanRetryRunnable?.let { handler.removeCallbacks(it) }
        scanRetryRunnable = Runnable {
            if (scanGeneration.get() != gen) return@Runnable
            if (!isTriggerDown || tagReceivedThisScan || !isRfidScanning || !isAdded) return@Runnable

            try {
                mgr.startScan(object : RFIDManager.ReadyCallback {
                    override fun onReady(reader: MiraIRFIDReader) {}
                    override fun onError(reason: String?) {}
                })
                scheduleScanRetry(gen, mgr)
            } catch (e: Exception) {
                Log.w(TAG, "scheduleScanRetry startScan failed", e)
            }
        }
        handler.postDelayed(scanRetryRunnable!!, SCAN_RETRY_INTERVAL_MS)
    }
        private fun stopRfidScanningMode() {
        scanGeneration.incrementAndGet()

        val wasScanning = isRfidScanning
        isRfidScanning = false
        tagReceivedThisScan = false

        rfidTimeoutRunnable?.let {
            handler.removeCallbacks(it)
            rfidTimeoutRunnable = null
        }
        scanRetryRunnable?.let {
            handler.removeCallbacks(it)
            scanRetryRunnable = null
        }

        updateScanningUI(false)
        restorePowerForEncoding() // ✅ خفض الطاقة فوراً حتى لو أُفلت الزناد دون قراءة شريحة

        if (!wasScanning) return
        val ctx = appContext ?: return
        val exec = miraExecutor ?: return

        if (!stoppingReader.compareAndSet(false, true)) {
            stoppingReader.set(true)
        }

        val toRemove = encodingRfidListener
        encodingRfidListener = null

        try {
            exec.backgroundExecutor.execute {
                try {
                    val mgr = RFIDManager.getInstance(ctx) ?: return@execute
                    if (toRemove != null) {
                        try {
                            mgr.removeListener(toRemove)
                        } catch (_: Exception) {
                        }
                    }
                    try {
                        mgr.stopScan()
                    } catch (_: Exception) {
                    }
                } finally {
                    stoppingReader.set(false)
                }
            }
        } catch (e: Exception) {
            stoppingReader.set(false)
        }
    }

    private fun updateScanningUI(scanning: Boolean) {
        safeRun {
            isRfidScanningState = scanning
            if (scanning) {
                statusBadgeTextState = "SCANNING..."
                statusBadgeColorState = Color(0xFF06B6D4)
            } else {
                refreshBadgeFromCurrentText()
            }
        }
    }

    private fun refreshBadgeFromCurrentText() {
        when {
            epcTextState.isEmpty() -> {
                statusBadgeTextState = "WAITING"
                statusBadgeColorState = Color(0xFF94A3B8)
                isEpcBound = false
                isEpcBoundState = false
            }
            isEpcBound -> {
                statusBadgeTextState = if (isArabic) "✅ موثّق" else "✅ VERIFIED"
                statusBadgeColorState = Color(0xFF22C55E)
            }
            else -> {
                statusBadgeTextState = if (isArabic) "⚠️ كتابة يدوية" else "⚠️ TYPED"
                statusBadgeColorState = Color(0xFFF59E0B)
            }
        }
    }

    private fun applyManualTestEpc(rawEpc: String) {
        if (!ENABLE_TEMP_MANUAL_EPC) return
        val cleanEpc = rawEpc.trim().uppercase(Locale.US)
        val testTid = ("E280117020000201" + cleanEpc).take(24)

        isEpcBound = true
        isEpcBoundState = true
        boundEpcValue = cleanEpc
        lastScannedEpc = cleanEpc
        lastScannedTid = testTid
        lastScanSource = "CHAINWAY"

        epcTextState = cleanEpc
        statusBadgeTextState = if (isArabic) "✅ موثّق (تجريبي)" else "✅ VERIFIED (TEST)"
        statusBadgeColorState = Color(0xFF22C55E)

        showScanFeedback(
            title = if (isArabic) "🧪 تم اعتماد EPC تجريبي" else "🧪 Test EPC Applied",
            details = "EPC: $cleanEpc\nTID: $testTid",
            price = null,
            accentColor = Color(0xFF22C55E),
            isSuccess = true
        )
    }


    private fun onEpcInputChanged(newText: String) {
        epcTextState = newText
        if (newText.isEmpty()) {
            isEpcBound = false
            isEpcBoundState = false
            boundEpcValue = ""
            statusBadgeTextState = "WAITING"
            statusBadgeColorState = Color(0xFF94A3B8)
        } else if (isEpcBound && !newText.trim().equals(boundEpcValue, ignoreCase = true)) {
            isEpcBound = false
            isEpcBoundState = false
            boundEpcValue = ""
            statusBadgeTextState = if (isArabic) "⚠️ كتابة يدوية" else "⚠️ TYPED"
            statusBadgeColorState = Color(0xFFF59E0B)
        } else if (!isEpcBound) {
            statusBadgeTextState = if (isArabic) "⚠️ كتابة يدوية" else "⚠️ TYPED"
            statusBadgeColorState = Color(0xFFF59E0B)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Tag Processing
    // ═══════════════════════════════════════════════════════════════════

        private fun processReceivedTagWithTid(
        epc: String?,
        preReadTid: String?,
        source: String
    ) {
        if (epc.isNullOrEmpty()) {
            isProcessingTag.set(false)
            return
        }
        if (!isAdded || isRemoving || isDetached) {
            isProcessingTag.set(false)
            return
        }

        val cleanEpc = epc.trim().uppercase(Locale.US)
        lastScannedEpc = cleanEpc
        lastScanSource = source

        val exec = miraExecutor ?: run {
            isProcessingTag.set(false)
            return
        }
        val ctx = appContext ?: run {
            isProcessingTag.set(false)
            return
        }

        exec.backgroundExecutor.execute {
            var isTagAllowed = false
            var isDuplicate = false
            var actualTid = cleanEpc
            var isTidSkipped = false
            var idForDuplicateCheck = cleanEpc

            try {
                val rfidService = MiraRfidService.getInstance(ctx)

                if ("DESKTOP_RFID" == source && preReadTid.isNullOrBlank()) {
                    actualTid = "DESKTOP_SKIP_TID"
                    isTagAllowed = true
                    isTidSkipped = true
                    idForDuplicateCheck = cleanEpc
                } else if (!preReadTid.isNullOrBlank()) {
                    actualTid = preReadTid.trim().uppercase(Locale.US)
                    val isSim = rfidService.isSimulationMode
                    val localAllowed = isSim || rfidService.verifyTagAllowed(actualTid)
                    if (!localAllowed && !isSim) {
                        Log.w(TAG, "⚠️ Local whitelist rejected ${maskEpc(actualTid)}")
                    }
                    isTagAllowed = true
                    idForDuplicateCheck = actualTid
                } else {
                    val tidFromTag = rfidService.readTid(null)
                    if (!tidFromTag.isNullOrBlank()) {
                        actualTid = tidFromTag.trim().uppercase(Locale.US)
                    }
                    isTagAllowed = true
                    idForDuplicateCheck = actualTid
                }

                isDuplicate = syncManager?.isEpcDuplicateLocally(idForDuplicateCheck) ?: false
            } catch (e: Exception) {
                Log.e(TAG, "processReceivedTagWithTid checks error: ${e.message}", e)
                isProcessingTag.set(false)
                return@execute
            }

            val finalTid = actualTid
            lastScannedTid = finalTid

            if (!isTidSkipped && ENABLE_TID_LENGTH_VALIDATION) {
                if (finalTid.length < MIN_TID_LENGTH || finalTid.length > MAX_TID_LENGTH) {
                    isProcessingTag.set(false)
                    safeRun {
                        playErrorSound()
                        showErrorDialog(
                            if (isArabic) "⚠️ TID غير صالح" else "⚠️ Invalid TID",
                            if (isArabic) "طول TID غير متوقع: ${finalTid.length} حرف"
                            else "Unexpected TID length: ${finalTid.length} chars"
                        )
                    }
                    return@execute
                }
            }

            val fAllowed = isTagAllowed
            val fDuplicate = isDuplicate
            val fTidSkipped = isTidSkipped
            val displayEpcValue = if (isTidSkipped) cleanEpc else actualTid

            safeRun {
                try {
                    if (!isAdded) return@safeRun

                    if (!fAllowed) {
                        playErrorSound()
                        showScanFeedback(
                            if (isArabic) "❌ شريحة غير معتمدة" else "❌ Unauthorized Tag",
                            if (isArabic) "TID: $finalTid\nليست من الموردين المعتمدين"
                            else "TID: $finalTid\nNot from approved suppliers",
                            null,
                            Color(0xFFEF4444),
                            false
                        )
                        showErrorDialog(
                            if (isArabic) "⛔ شريحة غير معتمدة" else "⛔ Unauthorized Tag",
                            if (isArabic) "هذه الشريحة ليست من مخزون الشرائح المعتمدة:\n\n🔹 TID: $finalTid\n\n💡 استخدم شريحة من مورد معتمد."
                            else "This tag is not from approved inventory:\n\n🔹 TID: $finalTid\n\n💡 Use a tag from an approved supplier."
                        )
                        return@safeRun
                    }

                    if (fDuplicate) {
                        playErrorSound()
                        showScanFeedback(
                            if (isArabic) "⚠️ شريحة مكررة" else "⚠️ Duplicate Tag",
                            if (isArabic) "ID: $displayEpcValue\nمسجلة بالفعل في النظام"
                            else "ID: $displayEpcValue\nAlready registered",
                            null,
                            Color(0xFF3B82F6),
                            false
                        )
                        showErrorDialog(
                            if (isArabic) "شريحة مكررة" else "Duplicate Tag",
                            if (isArabic) "هذه الشريحة مسجلة بالفعل في النظام:\n\n🔹 ID: $displayEpcValue\n\n💡 استخدم شريحة جديدة."
                            else "This tag is already registered:\n\n🔹 ID: $displayEpcValue\n\n💡 Use a new tag."
                        )
                        return@safeRun
                    }

                    val existing = epcTextState.trim()
                    val wasReplacing = isEpcBound && existing.isNotEmpty() && existing != displayEpcValue

                    isEpcBound = true
                    isEpcBoundState = true
                    boundEpcValue = displayEpcValue
                    epcTextState = displayEpcValue
                    statusBadgeTextState = if (isArabic) "✅ موثّق" else "✅ VERIFIED"
                    statusBadgeColorState = Color(0xFF22C55E)

                    val titleText = if (wasReplacing) {
                        if (isArabic) "🔄 تم استبدال الوسم" else "🔄 Tag Replaced"
                    } else {
                        if (isArabic) "✅ شريحة صالحة" else "✅ Valid Tag"
                    }

                    val tidDisplay = if (fTidSkipped) {
                        if (isArabic) "غير متوفر (قارئ مكتبي)" else "N/A (Desktop)"
                    } else finalTid

                    val detailsText = if (wasReplacing) {
                        if (isArabic) "TID جديد: $tidDisplay\nجاهز للتكويد"
                        else "New TID: $tidDisplay\nReady for encoding"
                    } else {
                        if (isArabic) "TID: $tidDisplay\nجاهزة للتكويد"
                        else "TID: $tidDisplay\nReady for encoding"
                    }

                    showScanFeedback(titleText, detailsText, null, Color(0xFF22C55E), true)
                    playSuccessSound()
                } finally {
                    isProcessingTag.set(false)
                }
            }
        }
    }


    fun onRfidScanned(epc: String?) {
        if (!isAdded || activity == null || epc.isNullOrBlank()) return
        val ctx = appContext ?: return
        val exec = miraExecutor ?: return

        val pm = permissionManager
        if (pm == null || (!pm.canEdit("identity") && !pm.isManager)) return
        if (isFormLocked) return

        val scannedEpc = epc.trim().uppercase(Locale.US)
        lastScannedEpc = scannedEpc

        if (!isProcessingTag.compareAndSet(false, true)) return

        exec.backgroundExecutor.execute {
            var tid: String? = null
            try {
                tid = MiraRfidService.getInstance(ctx).readTid(null)
            } catch (e: Exception) {
                Log.w(TAG, "onRfidScanned: readTid failed", e)
            }
            val finalTid = tid?.trim()?.uppercase(Locale.US)

            safeRun {
                if (finalTid.isNullOrEmpty()) {
                    isProcessingTag.set(false)
                    playErrorSound()
                    showErrorDialog(
                        if (isArabic) "❌ فشل قراءة TID" else "❌ TID Read Failed",
                        if (isArabic) "أعد المحاولة مع تقريب الشريحة."
                        else "Retry with tag closer to reader."
                    )
                    return@safeRun
                }
                processReceivedTagWithTid(scannedEpc, finalTid, "CHAINWAY")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Barcode & Desktop Reader Helpers
    // ═══════════════════════════════════════════════════════════════════

    private fun activateDesktopScannerMode() {
        isDesktopScanModeState = true
        try {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            view?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
        } catch (_: Exception) {
        }
        showToast(if (isArabic) "🖥️ جاهز للمسح بالقارئ المكتبي" else "🖥️ Desktop Reader Ready")
    }

    private fun handleDesktopScanSubmit() {
        val raw = epcTextState.trim().uppercase(Locale.US)
        if (raw.isEmpty()) return
        processReceivedTagWithTid(raw, null, "DESKTOP_RFID")
    }

    private fun openBarcodeScannerForEPC() {
        try {
            if (!isAdded || activity == null) return
            val fm = parentFragmentManager
            if (fm.isStateSaved || fm.findFragmentByTag("barcode_scanner_tag") != null) return

            val sf = MiraBarcodeScannerFragment()
            sf.setOnBarcodeScannedListener { data, _ ->
                if (!data.isNullOrBlank()) {
                    pendingBarcodeFromScanner = data.trim().uppercase(Locale.US)
                }
                try {
                    if (fm.backStackEntryCount > 0) fm.popBackStack()
                } catch (_: Exception) {
                }
            }

            val v = view
            val cid = if (v != null && v.parent is View) (v.parent as View).id else android.R.id.content

            fm.beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(
                    android.R.anim.fade_in, android.R.anim.fade_out,
                    android.R.anim.fade_in, android.R.anim.fade_out
                )
                .hide(this)
                .add(cid, sf, "barcode_scanner_tag")
                .addToBackStack("barcode_scanner_encode")
                .commit()
        } catch (e: Exception) {
            Log.e(TAG, "Barcode scanner error: ${e.message}", e)
            showToast(if (isArabic) "❌ خطأ في فتح الماسح" else "❌ Scanner error")
        }
    }

    private fun processPendingBarcode() {
        val scannedCode = pendingBarcodeFromScanner ?: return
        pendingBarcodeFromScanner = null

        val extractedIdentifier = extractIdentifierFromBarcode(scannedCode)
        if (extractedIdentifier.isEmpty() || extractedIdentifier.length < 2 || extractedIdentifier.length > 128) {
            playErrorSound()
            showScanFeedback(
                if (isArabic) "❌ باركود غير صالح" else "❌ Invalid Barcode",
                if (isArabic) "الطول: ${extractedIdentifier.length} حرف" else "Length: ${extractedIdentifier.length} chars",
                null,
                Color(0xFFEF4444),
                false
            )
            showErrorDialog(
                if (isArabic) "❌ باركود غير صالح" else "❌ Invalid Barcode",
                if (isArabic) "طول الباركود غير منطقي أو غير معروف" else "Barcode length unreasonable or unknown"
            )
            return
        }

        val exec = miraExecutor ?: return
        exec.backgroundExecutor.execute {
            val isDuplicate = syncManager?.isEpcDuplicateLocally(extractedIdentifier) ?: false
            safeRun {
                if (!isAdded) return@safeRun
                if (isDuplicate) {
                    playErrorSound()
                    showScanFeedback(
                        if (isArabic) "⚠️ باركود مكرر" else "⚠️ Duplicate Barcode",
                        "$extractedIdentifier\n" + if (isArabic) "مسجل بالفعل" else "Already registered",
                        null,
                        Color(0xFF3B82F6),
                        false
                    )
                    showErrorDialog(
                        if (isArabic) "باركود مكرر" else "Duplicate Barcode",
                        (if (isArabic) "هذا الكود مسجل بالفعل:\n" else "This code is already registered:\n") + extractedIdentifier
                    )
                    return@safeRun
                }

                isEpcBound = false
                isEpcBoundState = false
                boundEpcValue = ""
                epcTextState = extractedIdentifier
                statusBadgeTextState = if (isArabic) "⚠️ باركود (للبحث فقط)" else "⚠️ Barcode (lookup only)"
                statusBadgeColorState = Color(0xFFF59E0B)

                showScanFeedback(
                    if (isArabic) "🔍 باركود للبحث فقط" else "🔍 Barcode (Lookup)",
                    if (isArabic) "الباركود يحدد القطعة، لكن يجب مسح شريحة RFID للتكويد"
                    else "Barcode identifies item, but RFID scan is required to encode",
                    null,
                    Color(0xFF3B82F6),
                    false
                )

                showErrorDialog(
                    if (isArabic) "⚠️ الباركود لا يكفي" else "⚠️ Barcode is not sufficient",
                    if (isArabic) "لتكويد القطعة:\n\n1. استخدم زر مسح RFID\n2. قرّب الشريحة من القارئ\n3. اضغط الزناد"
                    else "To encode:\n\n1. Use RFID scan button\n2. Move tag near reader\n3. Press trigger"
                )
            }
        }
    }

    private fun extractIdentifierFromBarcode(rawBarcode: String?): String {
        if (rawBarcode.isNullOrBlank()) return ""
        val clean = rawBarcode.trim()

        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            return try {
                val uri = Uri.parse(clean)
                val lastSegment = uri.lastPathSegment
                if (lastSegment != null && lastSegment.matches(Regex("^MIRA-SN-\\d+$"))) {
                    return lastSegment
                }
                val serial = uri.getQueryParameter("serial") ?: uri.getQueryParameter("sn")
                if (serial != null && serial.matches(Regex("^MIRA-SN-\\d+$"))) {
                    return serial
                }
                ""
            } catch (_: Exception) {
                ""
            }
        }

        val gs1 = clean.replace('\u001D', '|').replace("(#)", "|")
        val mSerial = Pattern.compile("(?:^|\\|)21(MIRA-SN-\\d+)").matcher(gs1)
        if (mSerial.find()) {
            val serial = mSerial.group(1)
            if (serial != null && serial.matches(Regex("^MIRA-SN-\\d+$"))) {
                return serial
            }
        }

        if (clean.matches(Regex("^MIRA-SN-\\d+$"))) {
            return clean
        }

        return ""
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Calculations & Helpers
    // ═══════════════════════════════════════════════════════════════════

    private fun calculateEstimatedGoldValue() {
        try {
            val pm = priceManager ?: return
            val weightStr = grossWeightTextState.trim()
            if (weightStr.isEmpty()) {
                resetCalculatedDisplay()
                return
            }

            val grossWeight = weightStr.toDoubleOrNull() ?: 0.0
            if (grossWeight <= 0) {
                resetCalculatedDisplay()
                return
            }

            var stoneWeightCarats = 0.0
            var stoneValue = 0.0

            if (hasStonesState) {
                stoneWeightCarats = stoneWeightTextState.trim().toDoubleOrNull() ?: 0.0
                stoneValue = stoneValueTextState.trim().toDoubleOrNull() ?: 0.0
            }

            val pricing = PricingCalculator.calculatePricingDetails(
                grossWeight, stoneWeightCarats, stoneValue, selectedKaratState, pm
            )
            val totalPrice = pricing[5]
            netGoldWeightState = pricing[6]
            estimatedPriceTextState = pm.formatPriceDefault(totalPrice)
        } catch (e: Exception) {
            resetCalculatedDisplay()
        }
    }

    private fun resetCalculatedDisplay() {
        val pm = priceManager
        val symbol = pm?.getCurrencySymbol(pm.defaultCurrency) ?: ""
        estimatedPriceTextState = "0.00 $symbol".trim()
        netGoldWeightState = 0.0
    }

    private fun getSelectedCategoryLabel(): String {
        return when (selectedCategoryKeyState) {
            "rings" -> if (isArabic) "خواتم" else "Rings"
            "sets" -> if (isArabic) "أطقم" else "Sets"
            "bracelets" -> if (isArabic) "أساور" else "Bracelets"
            "necklaces" -> if (isArabic) "عقود" else "Necklaces"
            else -> if (isArabic) "مجوهرات" else "Jewelry"
        }
    }

    private fun getSuggestions(typeKey: String): List<String> {
        return if (isArabic) {
            when (typeKey) {
                "rings" -> listOf("خاتم سوليتير ألماسي", "خاتم محبس كلاسيك", "خاتم توينز راقي", "خاتم خطوبة مميز")
                "sets" -> listOf("طقم شبكة عروس كامل", "طقم ملكي مرصع بالألماس", "طقم سهرة فاخر")
                "bracelets" -> listOf("بناجر / أساور خليجي", "سوار تنادي عصري", "سوار ذهبي كلاسيك")
                "necklaces" -> listOf("عقد شوكر عصري", "سلسلة ذهبية ناعمة", "عقد لؤلؤ فاخر")
                else -> emptyList()
            }
        } else {
            when (typeKey) {
                "rings" -> listOf("Diamond Solitaire Ring", "Classic Band Ring", "Twins Ring", "Engagement Ring")
                "sets" -> listOf("Complete Bridal Set", "Royal Diamond Set", "Luxury Evening Set")
                "bracelets" -> listOf("Khaleeji Bangle", "Modern Tennis Bracelet", "Classic Gold Bracelet")
                "necklaces" -> listOf("Modern Choker", "Fine Gold Chain", "Luxury Pearl Necklace")
                else -> emptyList()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Save & Encode — finalizeItemIdentity
    // ═══════════════════════════════════════════════════════════════════

    private fun finalizeItemIdentity() {
        if (isFormLocked) {
            showToast(if (isArabic) "⛔ العملية قيد التنفيذ..." else "⛔ Operation in progress...")
            return
        }

        val pm = permissionManager ?: return
        if (!(pm.canEdit("identity") || pm.isManager)) {
            showToast(if (isArabic) "⛔ غير مصرح" else "⛔ Access Denied")
            return
        }

        isFormLocked = true
        isFormLockedState = true

        try {
            val ctx = appContext ?: run {
                unlockFormFast()
                return
            }
            val prcMgr = priceManager ?: run {
                unlockFormFast()
                return
            }

            val identifier = epcTextState.trim()
            val title = titleTextState.trim()
            val weightStr = grossWeightTextState.trim()

            if (!validateInputData(identifier, title, weightStr)) {
                unlockFormFast()
                return
            }

            val grossWeight = weightStr.toDouble()
            var stoneType = ""
            var stoneWeightCarats = 0.0
            var stoneCount = 0
            var stoneValue = 0.0

            if (hasStonesState) {
                stoneType = stoneTypeTextState.trim()
                stoneWeightCarats = stoneWeightTextState.trim().toDoubleOrNull() ?: 0.0
                stoneCount = stoneCountTextState.trim().toIntOrNull() ?: 0
                stoneValue = stoneValueTextState.trim().toDoubleOrNull() ?: 0.0
            }

            val stoneWeightGrams = PricingCalculator.caratsToGrams(stoneWeightCarats)
            if (stoneWeightGrams >= grossWeight) {
                showValidationError(
                    if (isArabic) "❌ وزن الأحجار لا يمكن أن يتجاوز أو يساوي الوزن الإجمالي!"
                    else "❌ Stone weight cannot exceed gross weight!"
                )
                unlockFormFast()
                return
            }

            val selectedKaratNum = selectedKaratState
            val pricing = PricingCalculator.calculatePricingDetails(
                grossWeight, stoneWeightCarats, stoneValue, selectedKaratNum, prcMgr
            )
            val netGoldWeight = pricing[6]

            val details = prcMgr.calculatePricingDetails(selectedKaratNum, netGoldWeight)
            val localCurrency = prcMgr.defaultCurrency
            val finalPriceUSD = details.finalPrice + stoneValue
            val finalPriceLocal = prcMgr.convertFromUSD(finalPriceUSD, localCurrency)
            val exchangeRate = prcMgr.getExchangeRate(localCurrency)

            loadingDialogState = (
                if (isArabic) "🔄 جاري التكويد وإنشاء الأكواد المجهرية..."
                else "🔄 Encoding & Generating Micro-Codes..."
            ) to (
                if (isArabic) "يرجى الانتظار - يتم الآن تسجيل القطعة في قاعدة البيانات"
                else "Please wait - saving item in database"
            )

            val selectedKarat = "${selectedKaratNum}K"
            val selectedCategory = getSelectedCategoryLabel()
            val location = if (isArabic) "المعرض الرئيسي" else "Main Showroom"
            val branchId = branchesManager?.activeBranchId ?: 1
            val finalPhotoPaths = ArrayList(photoManager?.photoPaths ?: emptyList())
            val currentUserId = getCurrentUserId()

            if (currentUserId <= 0) {
                loadingDialogState = null
                unlockFormFast()
                showErrorDialog(
                    if (isArabic) "❌ جلسة منتهية" else "❌ Session Expired",
                    if (isArabic) "يرجى تسجيل الدخول مجدداً" else "Please log in again"
                )
                return
            }

            val exec = miraExecutor ?: run {
                loadingDialogState = null
                unlockFormFast()
                return
            }

            exec.backgroundExecutor.execute {
                var tidFromTagWorking = ""
                var epcFromTagWorking = ""
                var isSim = false
                val isDesktopScan = "DESKTOP_RFID" == lastScanSource

                try {
                    val rfidSvc = MiraRfidService.getInstance(ctx)
                    if (isDesktopScan) {
                        tidFromTagWorking = ""
                        epcFromTagWorking = lastScannedEpc
                        isSim = false
                    } else {
                        tidFromTagWorking = lastScannedTid
                        epcFromTagWorking = lastScannedEpc.ifEmpty { identifier }
                        isSim = try {
                            rfidSvc.isSimulationMode
                        } catch (_: Exception) {
                            false
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Read TID/EPC error", e)
                }

                if (!isDesktopScan && tidFromTagWorking.isBlank()) {
                    safeRun {
                        loadingDialogState = null
                        unlockFormFast()
                        showErrorDialog(
                            if (isArabic) "❌ فشل التكويد" else "❌ Encoding Failed",
                            if (isArabic) "لم نتمكن من قراءة TID الشريحة.\n\n💡 أعد المسح."
                            else "Cannot read tag TID.\n\n💡 Rescan the tag."
                        )
                    }
                    return@execute
                }

                val tid = tidFromTagWorking.trim().uppercase(Locale.US)
                val originalEpc = epcFromTagWorking.trim().uppercase(Locale.US)

                val verifyError = verifyTagWithServerBeforeEncodingSync(tid, originalEpc, lastScanSource)
                if (verifyError != null) {
                    safeRun {
                        loadingDialogState = null
                        unlockFormFast()
                        playErrorSound()
                        showErrorDialog(
                            if (isArabic) "⛔ الشريحة مرفوضة" else "⛔ Tag Rejected",
                            verifyError
                        )
                    }
                    return@execute
                }

                try {
                    val payload = buildPayload(
                        tid, title, selectedKarat, grossWeight, netGoldWeight,
                        stoneWeightCarats, stoneCount, stoneValue, stoneType,
                        details, selectedKaratNum, finalPriceUSD, finalPriceLocal,
                        localCurrency, exchangeRate, selectedCategory, location,
                        branchId, currentUserId, tid, originalEpc,
                        isSim, finalPhotoPaths, ""
                    )

                    var apiSuccess = false
                    var serverItemId = 0
                    var errorMessage: String? = null
                    var isNetworkError = false
                    var responseDataObj: JSONObject? = null

                    apiClient?.let { client ->
                        try {
                            val response = client.encodeItem(payload)
                            if (response != null) {
                                apiSuccess = response.isSuccess
                                responseDataObj = response.data
                                if (response.isSuccess && response.data != null) {
                                    serverItemId = response.data!!.optInt("item_id", 0)
                                } else if (!response.isSuccess) {
                                    errorMessage = extractErrorMessage(response)
                                    isNetworkError = response.isNetworkError
                                }
                            }
                        } catch (e: Exception) {
                            errorMessage = e.message
                            isNetworkError = true
                        }
                    }

                    if (!apiSuccess) {
                        safeRun {
                            loadingDialogState = null
                            if (isNetworkError) {
                                exec.singleThreadExecutor.execute {
                                    syncManager?.saveEncodeOffline(payload)
                                    safeRun { updatePendingSyncBadge() }
                                }
                                showToast(
                                    if (isArabic) "💾 تم الحفظ محلياً (أوفلاين)"
                                    else "💾 Saved locally (Offline)"
                                )
                                scheduleAutoSync()
                                unlockFormFast()
                            } else {
                                showErrorDialog(
                                    if (isArabic) "خطأ في التكويد" else "Encoding Error",
                                    errorMessage ?: if (isArabic) "❌ فشل التكويد" else "❌ Encoding failed"
                                )
                                unlockFormFast()
                            }
                        }
                        return@execute
                    }

                    val rfidService = MiraRfidService.getInstance(ctx)

                    var miraIdWorking = responseDataObj?.optString(
                        "mira_id",
                        responseDataObj?.optString("serial_number", identifier) ?: identifier
                    ) ?: identifier
                    if (miraIdWorking.isBlank()) miraIdWorking = identifier
                    val miraId = miraIdWorking

                    val serverEpc = responseDataObj?.optString("epc", "") ?: ""
                    val existingEpc = (serverEpc.ifEmpty { lastScannedEpc }).trim().uppercase(Locale.US)

                    val hasValidEpc = existingEpc.matches(Regex("^E2[0-9A-F]{22}$")) &&
                        !existingEpc.startsWith("3008") &&
                        existingEpc != "300800000000000000000000"

                    val finalNewEpc: String
                    val writeOk: Boolean
                    val wasWritten: Boolean

                    if (hasValidEpc) {
                        finalNewEpc = existingEpc
                        writeOk = true
                        wasWritten = false
                    } else {
                        finalNewEpc = responseDataObj?.optString("tid", tid) ?: tid
                        writeOk = true
                        wasWritten = false
                    }

                    var verifyOk = true
                    if (wasWritten) {
                        var readBackEpcWorking: String? = null
                        if (writeOk) {
                            try {
                                Thread.sleep(150)
                            } catch (_: InterruptedException) {
                            }
                            try {
                                readBackEpcWorking = rfidService.readEpc(null)?.trim()?.uppercase(Locale.US)
                            } catch (_: Exception) {
                            }
                        }
                        verifyOk = writeOk && !readBackEpcWorking.isNullOrEmpty() && readBackEpcWorking == finalNewEpc

                        if (!verifyOk) {
                            safeRun {
                                loadingDialogState = null
                                unlockFormFast()
                                showErrorDialog(
                                    if (isArabic) "❌ فشل التحقق من الكتابة" else "❌ Write Verification Failed",
                                    if (isArabic) "لم يتم كتابة EPC بنجاح على الشريحة." else "Failed to write EPC on tag."
                                )
                            }
                            return@execute
                        }
                    }

                    var lockOk = false
                    if (wasWritten) {
                        try {
                            lockOk = rfidService.setTagLockState(null, 1, true)
                        } catch (_: Exception) {
                        }
                    }

                    try {
                        rfidService.markTagProgrammed(tid, miraId, finalNewEpc)
                    } catch (_: Exception) {
                    }

                    try {
                        val updatePayload = JSONObject().apply {
                            put("item_id", serverItemId)
                            if (finalNewEpc.matches(Regex("^[0-9A-F]{24}$"))) {
                                put("rfid_epc_final", finalNewEpc)
                            }
                            if (tid.isNotEmpty()) {
                                put("rfid_tid", tid)
                            }
                            put("rfid_locked", lockOk)
                            put("rfid_verified", verifyOk)
                            put("rfid_pre_encoded", !wasWritten)
                        }
                        apiClient?.postWithAuth("/items/update-rfid", updatePayload)
                    } catch (_: Exception) {
                    }

                    var serialTemp = ""
                    responseDataObj?.let { respObj ->
                        serialTemp = respObj.optString("serial_number", "")
                        if (serialTemp.isEmpty()) serialTemp = respObj.optString("mira_id", "")
                        if (serialTemp.isEmpty()) serialTemp = respObj.optString("serial", "")
                        if (serialTemp.isEmpty()) {
                            respObj.optJSONObject("data")?.let { innerData ->
                                serialTemp = innerData.optString("serial_number", "")
                                if (serialTemp.isEmpty()) serialTemp = innerData.optString("mira_id", "")
                                if (serialTemp.isEmpty()) serialTemp = innerData.optString("serial", "")
                            }
                        }
                    }
                    val finalServerSerial = serialTemp

                    safeRun {
                        loadingDialogState = null
                        playSuccessSound()

                        exec.singleThreadExecutor.execute {
                            syncManager?.removePendingEncode(identifier)
                        }
                        formManager?.clearDraft()

                        notifyDashboardUpdate("encode_success")

                        if (finalPhotoPaths.isNotEmpty() && serverItemId > 0) {
                            uploadItemImages(serverItemId, finalPhotoPaths)
                        }

                        val displayEpc = serverEpc.ifEmpty { finalNewEpc }
                        var shortBarcode = finalServerSerial
                        if (shortBarcode.isBlank() ||
                            (shortBarcode.startsWith("E2") && shortBarcode.length == 24)
                        ) {
                            val randomNum = Random().nextInt(9000) + 1000
                            shortBarcode = "TMP-$randomNum"
                        }

                        var gs1Code = "(21)$shortBarcode"
                        var qrUrl = "https://sa.mirabarcode.com/$shortBarcode"

                        responseDataObj?.optJSONObject("gs1")?.let { gs1Obj ->
                            val sGs1 = gs1Obj.optString("raw_fnc1", "")
                            if (sGs1.isNotEmpty()) gs1Code = sGs1
                            val sUrl = gs1Obj.optString("digital_link", "")
                            if (sUrl.isNotEmpty()) qrUrl = sUrl
                        }

                        epcTextState = ""

                        if (!wasWritten) {
                            showToast(
                                if (isArabic) "✅ تم استخدام EPC الموجود بنجاح"
                                else "✅ Existing EPC used successfully"
                            )
                        } else if (writeOk && verifyOk && lockOk) {
                            showToast(
                                if (isArabic) "🔒 تم التكويد والقفل بنجاح"
                                else "🔒 Encoded & locked successfully"
                            )
                        }

                        encodingSuccessState = EncodingSuccessSummary(
                            title = title,
                            epc = displayEpc,
                            shortBarcode = shortBarcode,
                            tid = tid,
                            gs1Code = gs1Code,
                            qrUrl = qrUrl,
                            weight = grossWeight.toString(),
                            karat = selectedKarat,
                            finalPriceUSD = finalPriceUSD,
                            currency = localCurrency,
                            photos = finalPhotoPaths,
                            stoneType = stoneType,
                            stoneWeightCarats = stoneWeightCarats,
                            stoneCount = stoneCount,
                            stoneValue = stoneValue,
                            netWeight = netGoldWeight
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Background execution error", e)
                    safeRun {
                        loadingDialogState = null
                        unlockFormFast()
                        showErrorDialog(
                            if (isArabic) "خطأ للنظام" else "System Error",
                            e.message ?: "Unknown"
                        )
                    }
                }
            }
        } catch (e: NumberFormatException) {
            showValidationError(if (isArabic) "❌ خطأ في الأرقام المدخلة" else "❌ Invalid number format")
            unlockFormFast()
        } catch (e: Exception) {
            unlockFormFast()
            showToast("❌ ${e.message}")
        }
    }

    private fun verifyTagWithServerBeforeEncodingSync(tid: String, epc: String, source: String?): String? {
        val client = apiClient ?: return if (isArabic) "❌ لا اتصال بالسيرفر" else "❌ No server connection"
        return try {
            val payload = JSONObject().apply {
                put("source", source ?: "CHAINWAY")
                if ("DESKTOP_RFID" == source) {
                    if (epc.isNotEmpty()) put("epc", epc)
                } else {
                    if (tid.isNotEmpty()) put("tid", tid)
                    if (epc.isNotEmpty()) put("epc", epc)
                }
            }
            val response = client.postWithAuth("/verify-encoding-tag", payload)
                ?: return if (isArabic) "❌ لم يتم استلام رد من السيرفر" else "❌ No response from server"

            if (!response.isSuccess) {
                val errorMsg = extractErrorMessage(response)
                return errorMsg ?: if (isArabic) "الشريحة غير معتمدة" else "Tag not authorized"
            }
            null
        } catch (e: Exception) {
            if (isArabic) "❌ خطأ في التحقق: ${e.message}" else "❌ Verification error: ${e.message}"
        }
    }

    private fun validateInputData(epc: String?, title: String, weightStr: String): Boolean {
        if (epc.isNullOrBlank()) {
            showValidationError(
                if (isArabic) "❌ يجب مسح شريحة RFID قبل الحفظ"
                else "❌ Must scan RFID tag before saving"
            )
            return false
        }
        if (!isEpcBound) {
            showValidationError(
                if (isArabic) "❌ لم يتم التحقق من الشريحة عبر RFID\nاستخدم زر المسح"
                else "❌ Tag not verified via RFID\nUse the scan button"
            )
            return false
        }

        val epcClean = epc.trim()
        val validFormat = epcClean.matches(Regex("^[0-9A-F]{16,32}$")) ||
            epcClean.matches(Regex("^E2[0-9A-F]{22}$"))

        if (!validFormat) {
            showValidationError(
                if (isArabic) "❌ صيغة الشريحة غير صالحة\nاستخدم المسح بدل الكتابة اليدوية"
                else "❌ Invalid tag format\nUse scan instead of manual typing"
            )
            return false
        }

        if (title.isEmpty()) {
            showValidationError(if (isArabic) "يرجى كتابة عنوان القطعة" else "Please enter item title")
            return false
        }
        if (title.length < 3) {
            showValidationError(if (isArabic) "العنوان قصير جداً" else "Title is too short")
            return false
        }
        if (weightStr.isEmpty()) {
            showValidationError(if (isArabic) "يرجى تحديد وزن القطعة" else "Please specify item weight")
            return false
        }

        val weight = weightStr.toDoubleOrNull()
        if (weight == null) {
            showValidationError(if (isArabic) "صيغة الوزن غير صحيحة" else "Invalid weight format")
            return false
        }
        if (weight <= 0) {
            showValidationError(if (isArabic) "يجب أن يكون الوزن أكبر من صفر" else "Weight must be greater than zero")
            return false
        }
        if (weight > 10000) {
            showValidationError(if (isArabic) "الوزن كبير جداً" else "Weight is too large")
            return false
        }

        return true
    }

    private fun buildPayload(
        epc: String, title: String, karat: String, grossWeight: Double, netWeight: Double,
        stoneWeightCarats: Double, stoneCount: Int, stoneValue: Double, stoneType: String,
        details: MiraPriceManager.PricingDetails, karatNum: Int, priceUSD: Double,
        priceLocal: Double, currency: String, exchangeRate: Double, category: String,
        location: String, branchId: Int, userId: Int, tid: String, originalEpc: String,
        isSim: Boolean, photoPathsList: List<String>, gtin: String
    ): JSONObject {
        val pm = priceManager!!
        return JSONObject().apply {
            put("title", title)
            put("purity_code", karat)
            put("category", category)
            put("location", location)
            put("branch_id", branchId)
            put("user_id", userId)

            put("gross_weight", grossWeight)
            put("net_weight", netWeight)
            put("stone_weight", stoneWeightCarats)
            put("stone_count", stoneCount)
            put("stone_value", stoneValue)
            put("stone_type", stoneType)

            put("gold_price_per_gram", details.gramPrice)
            put("gold_value", details.baseGoldCost)
            put("making_per_gram", pm.getMakingCharge(karatNum))
            put("making_charge", details.totalMakingCost)
            put("total_cost", details.baseGoldCost + details.totalMakingCost)
            put("profit_margin", pm.getProfitMargin(karatNum))
            put("profit_amount", details.profitAmount)

            put("retail_price_usd", priceUSD)
            put("retail_price", priceUSD)
            put("retail_price_local", priceLocal)
            put("currency", "USD")
            put("local_currency", currency)
            put("exchange_rate", exchangeRate)
            put("currency_symbol", pm.getCurrencySymbol(currency))

            if (tid.isNotEmpty()) {
                put("tid", tid)
                put("rfid_tid", tid)
            }
            if (originalEpc.isNotEmpty()) {
                put("epc", originalEpc)
            }
            if (gtin.isNotEmpty()) {
                put("gtin", gtin)
            }

            put("rfid_epc_original", "")
            put("rfid_epc_final", "")
            put("rfid_locked", false)
            put("rfid_verified", false)

            if (photoPathsList.isNotEmpty()) {
                val imagesArray = JSONArray()
                photoPathsList.forEach { imagesArray.put(it) }
                put("image_paths", imagesArray)
            }
        }
    }

    private fun unlockFormFast() {
        isFormLocked = false
        isFormLockedState = false
    }

    private fun extractErrorMessage(response: MiraApiClient.ApiResponse?): String? {
        if (response == null) return if (isArabic) "❌ خطأ غير معروف" else "❌ Unknown error"
        response.data?.optJSONObject("error")?.let { errorObj ->
            val message = errorObj.optString("message", "")
            val messageEn = errorObj.optString("message_en", "")
            var displayMessage = if (isArabic) message else messageEn
            if (displayMessage.isEmpty()) displayMessage = message.ifEmpty { messageEn }

            errorObj.optJSONObject("details")?.optString("suggestion", "")?.let { suggestion ->
                if (suggestion.isNotEmpty()) {
                    displayMessage += "\n\n💡 $suggestion"
                }
            }
            if (displayMessage.isNotEmpty()) return displayMessage
        }
        if (!response.message.isNullOrEmpty()) return response.message
        if (!response.error.isNullOrEmpty()) return response.error
        return if (isArabic) "❌ فشل العملية" else "❌ Operation failed"
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Sync Log & Image Upload
    // ═══════════════════════════════════════════════════════════════════

    private fun openSyncLogFragment() {
        try {
            if (!isAdded || activity == null) return
            val fm = parentFragmentManager
            if (fm.isStateSaved || fm.findFragmentByTag("sync_log_tag") != null) return

            val v = view
            val cid = if (v != null && v.parent is View) (v.parent as View).id else android.R.id.content
            fm.beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(
                    android.R.anim.fade_in, android.R.anim.fade_out,
                    android.R.anim.fade_in, android.R.anim.fade_out
                )
                .replace(cid, SyncLogFragment(), "sync_log_tag")
                .addToBackStack("sync_log")
                .commit()
        } catch (e: Exception) {
            showToast(if (isArabic) "❌ خطأ في فتح السجل" else "❌ Error opening log")
        }
    }

    private fun scheduleAutoSync() {
        val exec = miraExecutor ?: return
        if (syncManager == null) return

        exec.singleThreadExecutor.execute {
            try {
                Thread.sleep(3000)
                performAutoSync()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
            }
        }
    }

        private fun performAutoSync() {
        val sm = syncManager ?: return
        val exec = miraExecutor ?: return
        val count = sm.pendingCount // ✅ خاصية Kotlin وليست getPendingCount()
        if (count == 0) return

        val branchId = branchesManager?.activeBranchId ?: 1
        exec.networkExecutor.execute {
            sm.syncPendingEncodes(branchId, object : IdentitySyncManager.SyncCallback {
                override fun onSyncComplete(syncedCount: Int) {
                    if (syncedCount > 0) {
                        safeRun {
                            updatePendingSyncBadge()
                            if (isAdded && context != null) {
                                showToast(
                                    if (isArabic) "✅ تمت مزامنة $syncedCount تكويد"
                                    else "✅ Synced $syncedCount encodings"
                                )
                            }
                            notifyDashboardUpdate("sync_complete")
                        }
                    }
                }

                override fun onSyncError(error: String?) {} // ✅ مع علامة ? لتطابق الواجهة
            })
        }
    }

    private fun updatePendingSyncBadge() {
        pendingSyncCountState = syncManager?.pendingCount ?: 0 // ✅ خاصية Kotlin
    }

    private fun uploadItemImages(itemId: Int, imagePaths: List<String>) {
        val client = apiClient ?: return
        val exec = miraExecutor ?: return
        if (imagePaths.isEmpty() || itemId <= 0) return

        exec.imageExecutor.execute {
            var uploaded = 0
            for (i in imagePaths.indices) {
                val imageFile = File(imagePaths[i])
                if (!imageFile.exists()) continue
                try {
                    val response = client.uploadItemImage(itemId, imageFile, i == 0, "Image")
                    if (response != null && response.isSuccess) uploaded++
                } catch (_: Exception) {
                }
            }
            val fUploaded = uploaded
            safeRun {
                if (fUploaded > 0) {
                    showToast(
                        if (isArabic) "📷 تم رفع $fUploaded صورة"
                        else "📷 $fUploaded images uploaded"
                    )
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Reset Form for Next Scan
    // ═══════════════════════════════════════════════════════════════════

    private fun resetFormForNextScan() {
        isEpcBound = false
        isEpcBoundState = false
        boundEpcValue = ""
        lastScannedEpc = ""
        lastScannedTid = ""
        isProcessingTag.set(false)
        isTriggerDown = false

        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { resetFormForNextScan() }
            return
        }

        try {
            unlockFormFast()
            appContext?.let { MiraRfidService.getInstance(it).resetForNewTag() }

            epcTextState = ""
            titleTextState = ""
            grossWeightTextState = ""
            stoneTypeTextState = ""
            stoneWeightTextState = ""
            stoneCountTextState = ""
            stoneValueTextState = ""
            statusBadgeTextState = "WAITING"
            statusBadgeColorState = Color(0xFF94A3B8)

            resetCalculatedDisplay()

            hasStonesState = false
            selectedCategoryKeyState = "rings"
            selectedKaratState = 21

            currentPhotoPath?.let { path ->
                try {
                    val f = File(path)
                    if (f.exists()) f.delete()
                } catch (_: Exception) {
                }
                currentPhotoPath = null
            }

            photoManager?.clearPhotos(false)
            syncPhotosFromManager()
            formManager?.clearDraft()
        } catch (e: Exception) {
            Log.e(TAG, "resetFormForNextScan error: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Permissions, Power Management & Feedback
    // ═══════════════════════════════════════════════════════════════════

    private fun applyPermissions() {
        val pm = permissionManager ?: return
        val allowed = pm.canEdit("identity") || pm.isManager
        canEditState = allowed
        if (!allowed) {
            showToast(
                if (isArabic) "⛔ وضع القراءة فقط - غير مصرح بالتعديل"
                else "⛔ Read only mode - Access Denied"
            )
        }
    }

    private fun applyLowPowerForEncoding() {
        val ctx = appContext ?: return
        val exec = miraExecutor ?: return
        exec.singleThreadExecutor.execute {
            try {
                val settings = MiraReaderSettings.getInstance(ctx)
                savedPowerBeforeEncoding = settings.powerDbm
                val encodingPower = settings.posPowerDbm
                if (savedPowerBeforeEncoding == encodingPower) return@execute
                settings.powerDbm = encodingPower
                BaseTabFragmentActivity.getSharedReader()?.setPower(encodingPower)
            } catch (_: Exception) {
            }
        }
    }

    private fun applyScanPowerForReading() {
        val ctx = appContext ?: return
        val exec = miraExecutor ?: return
        exec.singleThreadExecutor.execute {
            try {
                val settings = MiraReaderSettings.getInstance(ctx)
                savedPowerBeforeScan = settings.powerDbm
                if (savedPowerBeforeScan == SCAN_POWER_DBM) return@execute
                settings.powerDbm = SCAN_POWER_DBM
                BaseTabFragmentActivity.getSharedReader()?.setPower(SCAN_POWER_DBM)
            } catch (_: Exception) {
            }
        }
    }

    private fun restorePowerForEncoding() {
        val ctx = appContext ?: return
        val exec = miraExecutor ?: return
        if (savedPowerBeforeScan <= 0) return
        savedPowerBeforeScan = -1

        exec.singleThreadExecutor.execute {
            try {
                val settings = MiraReaderSettings.getInstance(ctx)
                val encodingPower = settings.posPowerDbm
                settings.powerDbm = encodingPower
                BaseTabFragmentActivity.getSharedReader()?.setPower(encodingPower)
            } catch (_: Exception) {
            }
        }
    }

    private fun restoreOriginalPowerEncoding() {
        if (savedPowerBeforeEncoding <= 0) return
        val ctx = appContext ?: return
        val exec = miraExecutor ?: return
        val target = savedPowerBeforeEncoding
        savedPowerBeforeEncoding = -1

        exec.singleThreadExecutor.execute {
            try {
                val settings = MiraReaderSettings.getInstance(ctx)
                settings.powerDbm = target
                BaseTabFragmentActivity.getSharedReader()?.setPower(target)
            } catch (_: Exception) {
            }
        }
    }

    private fun openEncodingPowerSettings() {
        if (!isAdded || context == null) return
        try {
            PosPowerSettingsDialog.show(requireContext().applicationContext, isArabic) { newPowerDbm ->
                miraExecutor?.singleThreadExecutor?.execute {
                    try {
                        BaseTabFragmentActivity.getSharedReader()?.setPower(newPowerDbm)
                    } catch (_: Exception) {
                    }
                }
                showToast(
                    if (isArabic) "✅ تم تحديث قوة القراءة إلى $newPowerDbm dBm"
                    else "✅ Power updated to $newPowerDbm dBm"
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun showScanFeedback(
        title: String,
        details: String?,
        price: String?,
        accentColor: Color,
        isSuccess: Boolean
    ) {
        pendingHideFeedback?.let { handler.removeCallbacks(it) }
        scanFeedbackState = ScanFeedbackData(title, details, price, accentColor, isSuccess)
        val hideTask = Runnable {
            scanFeedbackState = null
            pendingHideFeedback = null
        }
        pendingHideFeedback = hideTask
        handler.postDelayed(hideTask, 3000)
    }

    private fun showValidationError(message: String) {
        alertDialogState = AlertDialogPayload(
            title = if (isArabic) "⚠️ تنبيه الحماية" else "⚠️ Validation Alert",
            message = message,
            isError = false
        )
    }

        protected override fun showErrorDialog(title: String, message: String): AlertDialog? {
        alertDialogState = AlertDialogPayload(
            title = title,
            message = message,
            isError = true
        )
        return null
    }


    private fun playSuccessSound() {
        try {
            (activity as? UHFMainActivity)?.playSound(1)
        } catch (_: Exception) {
        }
    }

    private fun playErrorSound() {
        try {
            (activity as? UHFMainActivity)?.playSound(2)
        } catch (_: Exception) {
        }
    }

    private fun showToast(msg: String) {
        if (context != null && isAdded) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun secureLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private fun maskEpc(epc: String?): String {
        if (epc == null || epc.length < 8) return "***"
        return "${epc.substring(0, 4)}****${epc.substring(epc.length - 4)}"
    }

    private fun notifyDashboardUpdate(source: String) {
        try {
            val ctx = context ?: return
            if (!isAdded) return
            val branchId = branchesManager?.activeBranchId ?: 0
            val timestamp = System.currentTimeMillis()

            ctx.sendBroadcast(Intent("com.example.uhf.REFRESH_INVENTORY").apply {
                setPackage(ctx.packageName)
                putExtra("source", source)
                putExtra("branch_id", branchId)
                putExtra("timestamp", timestamp)
            })

            ctx.sendBroadcast(Intent("com.example.uhf.REFRESH_DASHBOARD").apply {
                setPackage(ctx.packageName)
                putExtra("source", source)
                putExtra("branch_id", branchId)
                putExtra("timestamp", timestamp)
            })

            ctx.getSharedPreferences("app_refresh_flags", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_data_change", timestamp)
                .putString("last_change_type", source)
                .putInt("last_change_branch_id", branchId)
                .putBoolean("needs_refresh", true)
                .apply()
        } catch (_: Exception) {
        }
    }
    
    

    private fun getCurrentUserId(): Int {
    val ctx = appContext ?: context?.applicationContext ?: return -1
    appContext = ctx
    
    // ✅ التصحيح: استدعاء getUserId() كدالة لأن SecureSessionManager أصبح مكتوباً بـ Kotlin
    val userId = SecureSessionManager.getInstance(ctx).getUserId()
    
    if (userId > 0) return userId
    val legacyId = ctx.getSharedPreferences("session", Context.MODE_PRIVATE).getInt("user_id", -1)
    return if (legacyId > 0) legacyId else -1
}


    private fun safeRun(r: () -> Unit) {
        if (isAdded && activity != null && !isRemoving && !isDetached) {
            requireActivity().runOnUiThread {
                if (isAdded && !isRemoving && !isDetached && view != null) {
                    try {
                        r()
                    } catch (e: Exception) {
                        Log.e(TAG, "safeRun error: ${e.message}", e)
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Fragment Lifecycle Overrides
    // ═══════════════════════════════════════════════════════════════════

    override fun onResume() {
        super.onResume()
        if (!isAdded || activity == null) return

        isProcessingTag.set(false)
        isTriggerDown = false
        isRfidScanning = false
        isRfidScanningState = false
        tagReceivedThisScan = false
        scanGeneration.incrementAndGet()
        lastScannedTid = ""
        if (stoppingReader.get()) stoppingReader.set(false)

        rfidTimeoutRunnable?.let {
            handler.removeCallbacks(it)
            rfidTimeoutRunnable = null
        }
        pendingHideFeedback?.let {
            handler.removeCallbacks(it)
            pendingHideFeedback = null
        }

        (activity as? UHFMainActivity)?.apply {
            setBottomNavVisible(false)
            setActiveEncodingFragment(this@MiraIdentityStudioFragment)
        }

        applyLowPowerForEncoding()
        priceManager?.refreshNow()
        calculateEstimatedGoldValue()
        scheduleAutoSync()

        miraExecutor?.takeIf { it.isHealthy }?.backgroundExecutor?.execute {
            formManager?.cleanupOldDrafts()
            photoManager?.cleanupOrphanFiles(photoManager?.photoPaths)
        }
    }

    override fun onPause() {
        super.onPause()
        restorePowerForEncoding()

        (activity as? UHFMainActivity)?.apply {
            setBottomNavVisible(true)
            setActiveEncodingFragment(null)
        }

        restoreOriginalPowerEncoding()

        isTriggerDown = false
        isProcessingTag.set(false)

        rfidTimeoutRunnable?.let {
            handler.removeCallbacks(it)
            rfidTimeoutRunnable = null
        }
        pendingHideFeedback?.let {
            handler.removeCallbacks(it)
            pendingHideFeedback = null
        }

        stopRfidScanningMode()
        isDesktopScanModeState = false
        saveCurrentDraftAsync()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            processPendingBarcode()
        } else {
            isTriggerDown = false
            stopRfidScanningMode()
        }
    }

    override fun onDestroyView() {
        isTriggerDown = false
        isProcessingTag.set(false)
        stopRfidScanningMode()

        loadingDialogState = null
        rfidTimeoutRunnable?.let { handler.removeCallbacks(it) }
        pendingHideFeedback?.let { handler.removeCallbacks(it) }

        (activity as? UHFMainActivity)?.setActiveEncodingFragment(null)
        formManager?.setNotificationCallback(null)
        stoppingReader.set(false)

        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("is_form_locked", isFormLocked)
        outState.putBoolean("is_epc_bound", isEpcBound)
        if (boundEpcValue.isNotEmpty()) {
            outState.putString("epc_bound_value", boundEpcValue)
        }
        currentPhotoPath?.let { outState.putString("camera_photo_path", it) }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose UI
    // ═══════════════════════════════════════════════════════════════════

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun IdentityStudioScreen() {
        val colors = MiraTheme.colors
        val isAr = isArabic
        val formEditable = canEditState && !isFormLockedState
        val keyboardController = LocalSoftwareKeyboardController.current
        val epcFocusRequester = remember { FocusRequester() }

        LaunchedEffect(isDesktopScanModeState) {
            if (isDesktopScanModeState) {
                try {
                    epcFocusRequester.requestFocus()
                } catch (_: Exception) {
                }
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
                    // 1. الشريط العلوي لاستوديو التكويد
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
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(colors.goldPrimary.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.QrCode2,
                                    contentDescription = null,
                                    tint = colors.goldPrimary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (isAr) "استوديو تكويد MIRA ID" else "MIRA ID Studio",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = if (isAr) "الخطوة 1 من 4: الربط والتصنيف وتوليد الأكواد"
                                    else "Step 1 of 4: Bind, Classify & Generate Codes",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // زر سجل المزامنة والعمليات المعلقة
                        Surface(
                            modifier = Modifier.clickable { openSyncLogFragment() },
                            shape = RoundedCornerShape(12.dp),
                            color = if (pendingSyncCountState > 0)
                                Color(0xFFF59E0B).copy(alpha = 0.18f)
                            else colors.cardSurface,
                            border = BorderStroke(
                                1.dp,
                                if (pendingSyncCountState > 0) Color(0xFFF59E0B)
                                else colors.goldPrimary.copy(alpha = 0.35f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudSync,
                                    contentDescription = null,
                                    tint = if (pendingSyncCountState > 0) Color(0xFFF59E0B)
                                    else colors.goldPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                if (pendingSyncCountState > 0) {
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "$pendingSyncCountState",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFF59E0B)
                                    )
                                }
                            }
                        }
                    }

                    // 2. بطاقة التقييم اللحظي (Hero Live Valuation Card)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                        border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.45f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            colors.goldPrimary.copy(alpha = 0.16f),
                                            Color(0xFF10B981).copy(alpha = 0.08f),
                                            Color.Transparent
                                        )
                                    )
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1.2f)) {
                                    Text(
                                        text = if (isAr) "القيمة التقديرية للقطعة" else "Estimated Item Value",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textSecondary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = estimatedPriceTextState,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = colors.goldPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(36.dp)
                                        .background(colors.textSecondary.copy(alpha = 0.22f))
                                )

                                Column(
                                    modifier = Modifier.weight(0.9f),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    Text(
                                        text = if (isAr) "صافي الذهب (${selectedKaratState}K)"
                                        else "Net Gold (${selectedKaratState}K)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textSecondary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = String.format(Locale.US, "%.2f g", netGoldWeightState),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF10B981)
                                    )
                                }
                            }
                        }
                    }

                    // 3. محتوى النموذج القابل للتمرير
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // أ. بطاقة الربط بالشريحة الذكية (RFID Binding)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                            border = BorderStroke(
                                1.2.dp,
                                if (isEpcBoundState) Color(0xFF22C55E).copy(alpha = 0.55f)
                                else colors.goldPrimary.copy(alpha = 0.35f)
                            )
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
                                            imageVector = Icons.Rounded.Contactless,
                                            contentDescription = null,
                                            tint = colors.goldPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isAr) "ربط شريحة RFID" else "RFID Tag Binding",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.textPrimary
                                        )
                                    }

                                    // شارة حالة الشريحة
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = statusBadgeColorState.copy(alpha = 0.16f),
                                        border = BorderStroke(1.dp, statusBadgeColorState.copy(alpha = 0.45f))
                                    ) {
                                        Text(
                                            text = statusBadgeTextState,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = statusBadgeColorState,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = epcTextState,
                                    onValueChange = { raw ->
                                        if (isDesktopScanModeState && (raw.endsWith("\n") || raw.endsWith("\r"))) {
                                            onEpcInputChanged(raw.trim())
                                            handleDesktopScanSubmit()
                                        } else {
                                            onEpcInputChanged(raw)
                                        }
                                    },
                                    enabled = formEditable,
                                    singleLine = true,
                                    label = {
                                        Text(
                                            if (isDesktopScanModeState) {
                                                if (isAr) "قرب الشريحة من القارئ المكتبي..." else "Approach tag to Desktop Reader..."
                                            } else {
                                                if (isAr) "رمز الشريحة (EPC / TID / Serial)" else "Tag Code (EPC / TID / Serial)"
                                            }
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Nfc,
                                            contentDescription = null,
                                            tint = if (isEpcBoundState) Color(0xFF22C55E) else colors.goldPrimary
                                        )
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { openBarcodeScannerForEPC() },
                                            enabled = formEditable
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.QrCodeScanner,
                                                contentDescription = null,
                                                tint = Color(0xFF06B6D4)
                                            )
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        imeAction = if (isDesktopScanModeState) ImeAction.Done else ImeAction.Next
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            keyboardController?.hide()
                                            if (isDesktopScanModeState) handleDesktopScanSubmit()
                                        }
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(epcFocusRequester)
                                )

                                // أزرار المسح وإعدادات الطاقة
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .combinedClickable(
                                                enabled = formEditable,
                                                onClick = { showScanMethodDialogState = true },
                                                onLongClick = { openEncodingPowerSettings() }
                                            ),
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isRfidScanningState)
                                            Color(0xFF06B6D4).copy(alpha = 0.2f)
                                        else colors.goldPrimary.copy(alpha = 0.16f),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isRfidScanningState) Color(0xFF06B6D4)
                                            else colors.goldPrimary
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Sensors,
                                                contentDescription = null,
                                                tint = if (isRfidScanningState) Color(0xFF06B6D4)
                                                else colors.goldPrimary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (isRfidScanningState) {
                                                    if (isAr) "جاري المسح..." else "Scanning..."
                                                } else {
                                                    if (isAr) "مسح رمز RFID" else "Scan RFID Tag"
                                                },
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (isRfidScanningState) Color(0xFF06B6D4)
                                                else colors.goldPrimary
                                            )
                                        }
                                    }

                                    // زر ضبط طاقة التكويد (dBm)
                                    Surface(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clickable(enabled = formEditable) {
                                                openEncodingPowerSettings()
                                            },
                                        shape = RoundedCornerShape(12.dp),
                                        color = colors.backgroundMid,
                                        border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.25f))
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Rounded.Tune,
                                                contentDescription = null,
                                                tint = colors.goldPrimary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        
                        if (ENABLE_TEMP_MANUAL_EPC) {
    TempManualEpcInputCard(
        isArabic = isAr,
        currentEpc = epcTextState,
        enabled = formEditable,
        onEpcConfirmed = { confirmedEpc ->
            applyManualTestEpc(confirmedEpc)
        }
    )
}


                        // ب. بطاقة التصنيف والوصف
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.Category,
                                        contentDescription = null,
                                        tint = colors.goldPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isAr) "تصنيف ووصف القطعة" else "Classification & Description",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.textPrimary
                                    )
                                }

                                // رقائق التصنيف
                                val categories = listOf(
                                    "rings" to (if (isAr) "خواتم" else "Rings"),
                                    "sets" to (if (isAr) "أطقم" else "Sets"),
                                    "bracelets" to (if (isAr) "أساور" else "Bracelets"),
                                    "necklaces" to (if (isAr) "عقود" else "Necklaces")
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    categories.forEach { (key, label) ->
                                        val selected = selectedCategoryKeyState == key
                                        Surface(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable(enabled = formEditable) {
                                                    selectedCategoryKeyState = key
                                                },
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (selected) colors.goldPrimary.copy(alpha = 0.2f)
                                            else colors.backgroundMid,
                                            border = BorderStroke(
                                                if (selected) 1.5.dp else 1.dp,
                                                if (selected) colors.goldPrimary
                                                else colors.textSecondary.copy(alpha = 0.2f)
                                            )
                                        ) {
                                            Text(
                                                text = label,
                                                modifier = Modifier.padding(vertical = 9.dp),
                                                textAlign = TextAlign.Center,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (selected) colors.goldPrimary else colors.textSecondary,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }

                                // اقتراحات سريعة للعنوان
                                val suggestions = getSuggestions(selectedCategoryKeyState)
                                if (suggestions.isNotEmpty()) {
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        items(suggestions) { suggestion ->
                                            SuggestionChip(
                                                onClick = {
                                                    if (formEditable) titleTextState = suggestion
                                                },
                                                enabled = formEditable,
                                                label = {
                                                    Text(
                                                        text = suggestion,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = colors.textPrimary
                                                    )
                                                },
                                                shape = RoundedCornerShape(50),
                                                colors = SuggestionChipDefaults.suggestionChipColors(
                                                    containerColor = colors.backgroundMid
                                                ),
                                                border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.3f))
                                            )
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = titleTextState,
                                    onValueChange = { titleTextState = it },
                                    enabled = formEditable,
                                    singleLine = true,
                                    label = { Text(if (isAr) "عنوان ووصف القطعة" else "Item Title & Description") },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Diamond, contentDescription = null, modifier = Modifier.size(18.dp))
                                    },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // ج. بطاقة العيار والوزن الإجمالي
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.Scale,
                                        contentDescription = null,
                                        tint = colors.goldPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isAr) "العيار والوزن الموزون" else "Gold Karat & Weight",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.textPrimary
                                    )
                                }

                                val karats = listOf(24, 21, 18, 14)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    karats.forEach { k ->
                                        val selected = selectedKaratState == k
                                        Surface(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable(enabled = formEditable) {
                                                    selectedKaratState = k
                                                    calculateEstimatedGoldValue()
                                                },
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (selected) colors.goldPrimary.copy(alpha = 0.2f)
                                            else colors.backgroundMid,
                                            border = BorderStroke(
                                                if (selected) 1.5.dp else 1.dp,
                                                if (selected) colors.goldPrimary
                                                else colors.textSecondary.copy(alpha = 0.2f)
                                            )
                                        ) {
                                            Text(
                                                text = "${k}K",
                                                modifier = Modifier.padding(vertical = 10.dp),
                                                textAlign = TextAlign.Center,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (selected) colors.goldPrimary else colors.textSecondary
                                            )
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = grossWeightTextState,
                                    onValueChange = {
                                        grossWeightTextState = it
                                        calculateEstimatedGoldValue()
                                    },
                                    enabled = formEditable,
                                    singleLine = true,
                                    label = { Text(if (isAr) "الوزن الإجمالي (جرام)" else "Gross Weight (grams)") },
                                    suffix = {
                                        Text("g", color = colors.goldPrimary, fontWeight = FontWeight.Bold)
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Decimal,
                                        imeAction = ImeAction.Done
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // د. بطاقة الأحجار الكريمة والفصوص
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                            border = BorderStroke(
                                1.dp,
                                if (hasStonesState) Color(0xFF06B6D4).copy(alpha = 0.45f)
                                else colors.textSecondary.copy(alpha = 0.16f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = formEditable) {
                                            hasStonesState = !hasStonesState
                                            calculateEstimatedGoldValue()
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Rounded.AutoAwesome,
                                            contentDescription = null,
                                            tint = Color(0xFF06B6D4),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = if (isAr) "الأحجار الكريمة والفصوص" else "Precious Stones",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = colors.textPrimary
                                            )
                                            Text(
                                                text = if (isAr) "خصم وزن الأحجار وإضافة قيمتها"
                                                else "Deduct stone weight & add stone value",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.textSecondary
                                            )
                                        }
                                    }

                                    Switch(
                                        checked = hasStonesState,
                                        onCheckedChange = { checked ->
                                            if (formEditable) {
                                                hasStonesState = checked
                                                calculateEstimatedGoldValue()
                                            }
                                        },
                                        enabled = formEditable
                                    )
                                }

                                AnimatedVisibility(visible = hasStonesState) {
                                    Column(
                                        modifier = Modifier.padding(top = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = stoneTypeTextState,
                                            onValueChange = { stoneTypeTextState = it },
                                            enabled = formEditable,
                                            singleLine = true,
                                            label = { Text(if (isAr) "نوع الحجر (ألماس، زركون...)" else "Stone Type (Diamond, Zircon...)") },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedTextField(
                                                value = stoneWeightTextState,
                                                onValueChange = {
                                                    stoneWeightTextState = it
                                                    calculateEstimatedGoldValue()
                                                },
                                                enabled = formEditable,
                                                singleLine = true,
                                                label = { Text(if (isAr) "الوزن (قيراط)" else "Weight (ct)") },
                                                suffix = { Text("ct", color = Color(0xFF06B6D4)) },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.weight(1f)
                                            )

                                            OutlinedTextField(
                                                value = stoneCountTextState,
                                                onValueChange = { stoneCountTextState = it },
                                                enabled = formEditable,
                                                singleLine = true,
                                                label = { Text(if (isAr) "العدد" else "Count") },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.weight(1f)
                                            )
                                        }

                                        OutlinedTextField(
                                            value = stoneValueTextState,
                                            onValueChange = {
                                                stoneValueTextState = it
                                                calculateEstimatedGoldValue()
                                            },
                                            enabled = formEditable,
                                            singleLine = true,
                                            label = { Text(if (isAr) "قيمة الأحجار (USD)" else "Stone Value (USD)") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }

                        // هـ. بطاقة الصور المرفقة
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
                                            imageVector = Icons.Rounded.PhotoCamera,
                                            contentDescription = null,
                                            tint = colors.goldPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isAr) "صور القطعة (${photoPathsState.size}/$MAX_PHOTOS)"
                                            else "Item Photos (${photoPathsState.size}/$MAX_PHOTOS)",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.textPrimary
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilledTonalButton(
                                            onClick = {
                                                if (photoPathsState.size >= MAX_PHOTOS) {
                                                    showToast(
                                                        if (isAr) "⚠️ الحد الأقصى $MAX_PHOTOS صور"
                                                        else "⚠️ Maximum $MAX_PHOTOS photos"
                                                    )
                                                } else {
                                                    checkCameraPermissionAndTakePhoto()
                                                }
                                            },
                                            enabled = formEditable,
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = colors.goldPrimary.copy(alpha = 0.16f),
                                                contentColor = colors.goldPrimary
                                            )
                                        ) {
                                            Icon(Icons.Rounded.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (isAr) "كاميرا" else "Camera",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        FilledTonalButton(
                                            onClick = {
                                                if (photoPathsState.size >= MAX_PHOTOS) {
                                                    showToast(
                                                        if (isAr) "⚠️ الحد الأقصى $MAX_PHOTOS صور"
                                                        else "⚠️ Maximum $MAX_PHOTOS photos"
                                                    )
                                                } else {
                                                    openGallery()
                                                }
                                            },
                                            enabled = formEditable,
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = Color(0xFF06B6D4).copy(alpha = 0.16f),
                                                contentColor = Color(0xFF06B6D4)
                                            )
                                        ) {
                                            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (isAr) "استوديو" else "Gallery",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                if (photoPathsState.isEmpty()) {
                                    Text(
                                        text = if (isAr) "لم يتم إرفاق صور بعد" else "No photos attached yet",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary
                                    )
                                } else {
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        itemsIndexed(photoPathsState) { index, path ->
                                            PhotoThumbnailCard(
                                                path = path,
                                                enabled = formEditable,
                                                onPreview = { previewPhotoPathState = path },
                                                onDelete = {
                                                    photoManager?.removePhoto(index, false, photoCallback)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // 4. الشريط السفلي للاعتماد والتصدير
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.cardSurface,
                        shadowElevation = 12.dp,
                        border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.25f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Button(
                                onClick = { finalizeItemIdentity() },
                                enabled = formEditable,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .alpha(if (formEditable) 1f else 0.5f),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.goldPrimary,
                                    contentColor = Color(0xFF0A0F1D)
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Verified,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isAr) "اعتماد الموزون وتصدير MIRA ID"
                                    else "Approve & Export MIRA ID",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 15.sp
                                )
                            }
                        }
                    }
                }

                // 5. بطاقة إشعار المسح العلوية المتحركة (Scan Feedback Banner)
                AnimatedVisibility(
                    visible = scanFeedbackState != null,
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    scanFeedbackState?.let { fb ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                            border = BorderStroke(1.5.dp, fb.accentColor)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(fb.accentColor.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (fb.isSuccess) Icons.Rounded.CheckCircle
                                        else Icons.Rounded.WarningAmber,
                                        contentDescription = null,
                                        tint = fb.accentColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = fb.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = colors.textPrimary
                                    )
                                    fb.details?.let {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.textSecondary
                                        )
                                    }
                                    fb.price?.let {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = fb.accentColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        //  Dialogs & Modals
        // ═══════════════════════════════════════════════════════════════════

        // 1. اختيار طريقة المسح (يدوي / مكتبي)
        if (showScanMethodDialogState) {
            AlertDialog(
                onDismissRequest = { showScanMethodDialogState = false },
                containerColor = colors.cardSurface,
                title = {
                    Text(
                        text = if (isAr) "اختر طريقة المسح" else "Choose Scan Method",
                        color = colors.goldPrimary,
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showScanMethodDialogState = false
                                    isDesktopScanModeState = false
                                    showToast(
                                        if (isAr) "🎯 اضغط زر الزناد في القارئ اليدوي الآن"
                                        else "🎯 Press the handheld reader trigger now"
                                    )
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = colors.backgroundMid,
                            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Sensors, contentDescription = null, tint = colors.goldPrimary)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (isAr) "📡 مسح بالقارئ اليدوي (الزناد)"
                                    else "📡 Scan with Handheld Reader",
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showScanMethodDialogState = false
                                    activateDesktopScannerMode()
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = colors.backgroundMid,
                            border = BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Usb, contentDescription = null, tint = Color(0xFF06B6D4))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (isAr) "🖥️ مسح بالقارئ المكتبي (USB)"
                                    else "🖥️ Scan with Desktop Reader",
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showScanMethodDialogState = false }) {
                        Text(if (isAr) "إلغاء" else "Cancel", color = colors.textSecondary)
                    }
                }
            )
        }

        // 2. نافذة استعادة المسودة
        draftDialogSummaryState?.let { summary ->
            AlertDialog(
                onDismissRequest = {},
                containerColor = colors.cardSurface,
                title = {
                    Text(
                        text = if (isAr) "📝 مسودة محفوظة" else "📝 Saved Draft",
                        color = colors.goldPrimary,
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                text = {
                    Text(text = summary, color = colors.textPrimary)
                },
                confirmButton = {
                    Button(
                        onClick = {
                            draftDialogSummaryState = null
                            restoreDraftIfExists()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.goldPrimary,
                            contentColor = Color(0xFF0A0F1D)
                        )
                    ) {
                        Text(if (isAr) "استعادة" else "Restore", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            draftDialogSummaryState = null
                            formManager?.clearDraft()
                            showToast(if (isArabic) "🗑️ تم حذف المسودة" else "🗑️ Draft discarded")
                        }
                    ) {
                        Text(if (isAr) "تجاهل" else "Discard", color = Color(0xFFEF4444))
                    }
                }
            )
        }

        // 3. نافذة التحميل أثناء التكويد
        loadingDialogState?.let { (title, message) ->
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
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CircularProgressIndicator(color = colors.goldPrimary)
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.textPrimary,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // 4. نافذة التنبيهات والأخطاء
        alertDialogState?.let { payload ->
            AlertDialog(
                onDismissRequest = { alertDialogState = null },
                containerColor = colors.cardSurface,
                title = {
                    Text(
                        text = payload.title,
                        color = if (payload.isError) Color(0xFFEF4444) else Color(0xFFF59E0B),
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                text = {
                    Text(text = payload.message, color = colors.textPrimary)
                },
                confirmButton = {
                    Button(
                        onClick = { alertDialogState = null },
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

        // 5. نافذة معاينة الصورة بحجم كامل
        previewPhotoPathState?.let { path ->
            Dialog(onDismissRequest = { previewPhotoPathState = null }) {
                val bmp by produceState<ImageBitmap?>(initialValue = null, path) {
                    value = loadScaledBitmap(path, 1024)
                }
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                    border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (bmp != null) {
                            Image(
                                bitmap = bmp!!,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 380.dp)
                                    .clip(RoundedCornerShape(14.dp))
                            )
                        }
                        Button(
                            onClick = { previewPhotoPathState = null },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.goldPrimary,
                                contentColor = Color(0xFF0A0F1D)
                            )
                        ) {
                            Text(if (isAr) "إغلاق" else "Close", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 6. بطاقة نجاح التكويد وتصدير MIRA ID (Success Summary Sheet/Dialog)
        encodingSuccessState?.let { summary ->
            EncodingSuccessDialog(
                summary = summary,
                isArabic = isAr,
                onCloseAndNext = {
                    encodingSuccessState = null
                    resetFormForNextScan()
                }
            )
        }
    }

    @Composable
    private fun PhotoThumbnailCard(
        path: String,
        enabled: Boolean,
        onPreview: () -> Unit,
        onDelete: () -> Unit
    ) {
        val colors = MiraTheme.colors
        val thumb by produceState<ImageBitmap?>(initialValue = null, path) {
            value = loadScaledBitmap(path, 220)
        }

        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.backgroundMid)
                .clickable { onPreview() }
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb!!,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (enabled) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .clickable { onDelete() },
                    shape = CircleShape,
                    color = Color(0xFFEF4444)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
    
    @Composable
private fun TempManualEpcInputCard(
    isArabic: Boolean,
    currentEpc: String,
    enabled: Boolean = true,
    onEpcConfirmed: (String) -> Unit
) {
    if (!ENABLE_TEMP_MANUAL_EPC) return

    val colors = MiraTheme.colors
    var manualEpc by remember(currentEpc) { mutableStateOf(currentEpc) }
    var validationError by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.EditNote,
                        contentDescription = null,
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isArabic) "إدخال EPC يدوياً (للتجربة المؤقتة)"
                        else "Manual EPC Input (Temp Test)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFF59E0B)
                    )
                }

                // توليد EPC قياسي من 24 خانة Hex بضغطة واحدة
                TextButton(
                    onClick = {
                        val hexChars = "0123456789ABCDEF"
                        val randomHex = buildString(20) {
                            repeat(20) { append(hexChars.random()) }
                        }
                        val generated = "E280$randomHex"
                        manualEpc = generated
                        validationError = null
                        onEpcConfirmed(generated)
                    },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFF06B6D4),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isArabic) "توليد تجريبي" else "Generate Test",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF06B6D4)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = manualEpc,
                    onValueChange = { input ->
                        manualEpc = input.uppercase(Locale.US).filter { !it.isWhitespace() }
                        validationError = null
                    },
                    enabled = enabled,
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = "E280117000000201...",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    },
                    trailingIcon = {
                        if (manualEpc.isNotEmpty() && enabled) {
                            IconButton(
                                onClick = {
                                    manualEpc = ""
                                    validationError = null
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = null,
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        val clean = manualEpc.trim().uppercase(Locale.US)
                        val isValidHex = clean.length in 16..32 && clean.all {
                            it in '0'..'9' || it in 'A'..'F'
                        }
                        if (!isValidHex) {
                            validationError = if (isArabic) {
                                "أدخل رمز Hex صالح (من 16 إلى 32 خانة: 0-9, A-F)"
                            } else {
                                "Enter valid Hex EPC (16-32 chars: 0-9, A-F)"
                            }
                        } else {
                            validationError = null
                            onEpcConfirmed(clean)
                        }
                    },
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.goldPrimary,
                        contentColor = Color(0xFF0A0F1D)
                    )
                ) {
                    Text(
                        text = if (isArabic) "اعتماد" else "Apply",
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            validationError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFEF4444)
                )
            }
        }
    }
}


    @Composable
    private fun EncodingSuccessDialog(
        summary: EncodingSuccessSummary,
        isArabic: Boolean,
        onCloseAndNext: () -> Unit
    ) {
        val colors = MiraTheme.colors

        val dmBitmap by produceState<ImageBitmap?>(initialValue = null, summary.gs1Code) {
            value = if (summary.gs1Code.isNotEmpty()) {
                BarcodeGenerator.generateGS1DataMatrix(summary.gs1Code, 250, 250)?.asImageBitmap()
            } else null
        }

        val qrBitmap by produceState<ImageBitmap?>(initialValue = null, summary.qrUrl) {
            value = if (summary.qrUrl.isNotEmpty()) {
                BarcodeGenerator.generateMicroQRCode(summary.qrUrl, 250, 250)?.asImageBitmap()
            } else null
        }

        val itemPhotoBitmap by produceState<ImageBitmap?>(initialValue = null, summary.photos) {
            val firstPath = summary.photos.firstOrNull()
            value = if (!firstPath.isNullOrEmpty() && File(firstPath).exists()) {
                loadScaledBitmap(firstPath, 300)
            } else null
        }

        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
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
                border = BorderStroke(1.5.dp, colors.goldPrimary.copy(alpha = 0.55f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // شارة النجاح
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xFF22C55E).copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = if (isArabic) "✅ تم إصدار الهوية الرقمية MIRA ID"
                            else "✅ MIRA ID Digital Identity Issued",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF22C55E),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }

                    // صورة القطعة إن وجدت
                    itemPhotoBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(92.dp)
                                .clip(RoundedCornerShape(16.dp))
                        )
                    }

                    Text(
                        text = summary.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = if (isArabic) "الباركود: ${summary.shortBarcode} | EPC: ${summary.epc}"
                        else "Barcode: ${summary.shortBarcode} | EPC: ${summary.epc}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    )

                    // تفاصيل العيار والوزن والسعر
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = colors.backgroundMid
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (isArabic) "العيار" else "Karat",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                                Text(
                                    text = summary.karat,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colors.goldPrimary
                                )
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (isArabic) "الوزن" else "Weight",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                                Text(
                                    text = "${summary.weight} g",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colors.textPrimary
                                )
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (isArabic) "السعر" else "Price",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                                Text(
                                    text = priceManager?.formatPriceDefault(summary.finalPriceUSD)
                                        ?: "${summary.finalPriceUSD}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF10B981)
                                )
                            }
                        }
                    }

                    // معلومات الأحجار إن وجدت
                    if (summary.stoneWeightCarats > 0 || summary.stoneValue > 0 || summary.stoneCount > 0) {
                        val stonesLine = buildString {
                            if (isArabic) {
                                append("💎 الأحجار: ")
                                append(summary.stoneType.ifEmpty { "غير محدد" })
                                if (summary.stoneWeightCarats > 0) append(" | ${summary.stoneWeightCarats} قيراط")
                                if (summary.stoneCount > 0) append(" | ${summary.stoneCount} حجر")
                            } else {
                                append("💎 Stones: ")
                                append(summary.stoneType.ifEmpty { "N/A" })
                                if (summary.stoneWeightCarats > 0) append(" | ${summary.stoneWeightCarats} ct")
                                if (summary.stoneCount > 0) append(" | ${summary.stoneCount} pcs")
                            }
                        }
                        Text(
                            text = stonesLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF06B6D4),
                            textAlign = TextAlign.Center
                        )
                    }

                    // الأكواد المجهرية (GS1 DataMatrix + Micro QR)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        dmBitmap?.let { dm ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White,
                                    modifier = Modifier.padding(4.dp)
                                ) {
                                    Image(
                                        bitmap = dm,
                                        contentDescription = "GS1 DataMatrix",
                                        modifier = Modifier
                                            .size(115.dp)
                                            .padding(6.dp)
                                    )
                                }
                                Text(
                                    text = "GS1 DataMatrix",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                            }
                        }

                        qrBitmap?.let { qr ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White,
                                    modifier = Modifier.padding(4.dp)
                                ) {
                                    Image(
                                        bitmap = qr,
                                        contentDescription = "Micro QR",
                                        modifier = Modifier
                                            .size(115.dp)
                                            .padding(6.dp)
                                    )
                                }
                                Text(
                                    text = "MIRA Micro QR",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                            }
                        }
                    }

                    // زر طباعة الملصق الحراري / الليزري
                    OutlinedButton(
                        onClick = {
                            miraExecutor?.imageExecutor?.execute {
                                val tagToPrint: Bitmap = TagPrinter.generatePhysicalTagBitmap(
                                    summary.shortBarcode,
                                    summary.gs1Code,
                                    summary.qrUrl
                                )
                                safeRun {
                                    TagPrinter.printTagDirectly(requireContext(), tagToPrint)
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.2.dp, colors.goldPrimary),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.goldPrimary)
                    ) {
                        Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isArabic) "🖨️ طباعة ملصق القطعة" else "🖨️ Print Physical Tag",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // زر الإغلاق وتجهيز قطعة جديدة
                    Button(
                        onClick = onCloseAndNext,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.goldPrimary,
                            contentColor = Color(0xFF0A0F1D)
                        )
                    ) {
                        Icon(Icons.Rounded.AddCircleOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isArabic) "إغلاق وتكويد قطعة جديدة" else "Done & Scan Next Item",
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }

    private fun loadScaledBitmap(path: String, reqSize: Int): ImageBitmap? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            var inSampleSize = 1
            while (options.outWidth / inSampleSize > reqSize || options.outHeight / inSampleSize > reqSize) {
                inSampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            BitmapFactory.decodeFile(path, decodeOpts)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
}
