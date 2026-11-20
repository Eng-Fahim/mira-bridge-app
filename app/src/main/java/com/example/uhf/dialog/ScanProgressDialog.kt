package com.example.uhf.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
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
import androidx.fragment.app.FragmentActivity
import com.example.uhf.scanner.BarcodeScanHandler
import com.example.uhf.scanner.ManualInputHandler
import com.example.uhf.scanner.RfidScanHandler
import com.example.uhf.scanner.ScanMethod
import com.example.uhf.scanner.ScanResult
import com.example.uhf.scanner.UnifiedScanManager
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import java.util.Locale

/**
 * حوار عرض وتنفيذ تقدم المسح لعمليات الإرسال والاستلام (Jetpack Compose)
 */
class ScanProgressDialog(
    private val activity: FragmentActivity,
    private val isArabic: Boolean,
    private val transferId: Int,
    private val transferNumber: String,
    private val phase: String,
    private val listener: OnScanCompletedListener?
) : Dialog(activity) {

    companion object {
        private const val TAG = "ScanProgressDialog"
    }

    interface OnScanCompletedListener {
        fun onCompleted(scannedCount: Int)
        fun onCancelled()
        fun onError(message: String?)
    }

    private var scanManager: UnifiedScanManager? = null
    private var rfidHandler: RfidScanHandler? = null
    private var manualHandler: ManualInputHandler? = null
    private var barcodeHandler: BarcodeScanHandler? = null

    private var activeMethodState by mutableStateOf(ScanMethod.RFID)
    private var expectedCountState by mutableIntStateOf(0)
    private var scannedCountState by mutableIntStateOf(0)
    private val scannedItemsState = mutableStateListOf<ScanResult>()

    private var statusMessageState by mutableStateOf("")
    private var statusColorState by mutableStateOf(ComposeColor(0xFF38BDF8))
    private var isCompletingState by mutableStateOf(false)
    private var showManualBarState by mutableStateOf(false)
    private var manualInputTextState by mutableStateOf("")
    private var manualErrorTextState by mutableStateOf<String?>(null)
    private var showCancelConfirmState by mutableStateOf(false)

    private var isDismissing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCancelable(false)
        setCanceledOnTouchOutside(false)

        initManagers()

        val composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MiraJewelryTheme {
                    ScanProgressContent()
                }
            }
        }

        setContentView(composeView)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        startSession()
    }

    override fun dismiss() {
        if (isDismissing) return
        isDismissing = true
        stopRfidIfActive()
        super.dismiss()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Initialization & Session Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    private fun initManagers() {
        val ctx: Context = activity
        scanManager = UnifiedScanManager.getInstance(ctx)
        rfidHandler = RfidScanHandler(activity)
        manualHandler = ManualInputHandler(ctx)
        barcodeHandler = BarcodeScanHandler(ctx)
    }

    private fun startSession() {
        Log.d(TAG, "Starting scan session: transfer=$transferId phase=$phase")
        expectedCountState = 0
        scannedCountState = 0
        scannedItemsState.clear()
        isCompletingState = false

        scanManager?.startSession(
            transferId,
            phase,
            ScanMethod.RFID,
            object : UnifiedScanManager.ScanCallback {
                override fun onScanResult(result: ScanResult?) {
                    activity.runOnUiThread { handleScanResult(result) }
                }

                override fun onSessionStarted(expected: Int) {
                    activity.runOnUiThread {
                        expectedCountState = expected
                        scannedCountState = 0
                        scannedItemsState.clear()
                        updateStatusBanner(
                            msg = if (isArabic) "✅ بدأت الجلسة — ابدأ المسح الآن"
                            else "✅ Session started — begin scanning",
                            color = ComposeColor(0xFF10B981)
                        )
                    }
                }

                override fun onSessionError(error: String?) {
                    activity.runOnUiThread {
                        val errMsg = error ?: if (isArabic) "حدث خطأ في الجلسة" else "Session error"
                        updateStatusBanner(msg = "❌ $errMsg", color = ComposeColor(0xFFEF4444))
                        listener?.onError(errMsg)
                    }
                }

                override fun onSessionCompleted(count: Int) {
                    activity.runOnUiThread {
                        isCompletingState = false
                        stopRfidIfActive()
                        listener?.onCompleted(count)
                    }
                }

                override fun onSessionCancelled() {
                    activity.runOnUiThread {
                        isCompletingState = false
                        stopRfidIfActive()
                        listener?.onCancelled()
                    }
                }
            }
        )
    }

    private fun handleScanResult(result: ScanResult?) {
        if (result == null) return

        when (result.status) {
            ScanResult.Status.SCANNED -> {
                addScannedItem(result)
                scannedCountState = result.scannedCount
                if (result.expectedCount > 0) {
                    expectedCountState = result.expectedCount
                }
                if (result.isAllScanned) {
                    updateStatusBanner(
                        msg = if (isArabic) "✅ اكتمل مسح جميع القطع — يمكنك الإتمام"
                        else "✅ All items scanned — ready to complete",
                        color = ComposeColor(0xFF10B981)
                    )
                } else {
                    updateStatusBanner(
                        msg = result.message ?: (if (isArabic) "تم تسجيل القطعة" else "Item scanned"),
                        color = ComposeColor(0xFF10B981)
                    )
                }
            }

            ScanResult.Status.ALREADY_SCANNED -> {
                updateStatusBanner(
                    msg = if (isArabic) "⚠️ هذه القطعة ممسوحة مسبقاً" else "⚠️ Already scanned",
                    color = ComposeColor(0xFFF59E0B)
                )
            }

            ScanResult.Status.NOT_FOUND -> {
                updateStatusBanner(
                    msg = if (isArabic) "❌ القطعة غير مسجلة في النظام" else "❌ Item not registered",
                    color = ComposeColor(0xFFEF4444)
                )
            }

            ScanResult.Status.EXTRA -> {
                updateStatusBanner(
                    msg = if (isArabic) "⚠️ القطعة غير مدرجة في هذه الشحنة" else "⚠️ Item not in this transfer",
                    color = ComposeColor(0xFFF59E0B)
                )
            }

            ScanResult.Status.ERROR -> {
                updateStatusBanner(
                    msg = "❌ ${result.message ?: if (isArabic) "خطأ أثناء المسح" else "Scan error"}",
                    color = ComposeColor(0xFFEF4444)
                )
            }

            else -> Unit
        }
    }

    private fun addScannedItem(result: ScanResult) {
        val targetId = result.itemId
        if (targetId != null && scannedItemsState.any { it.itemId == targetId }) {
            return
        }
        scannedItemsState.add(result)
    }

    private fun updateStatusBanner(msg: String, color: ComposeColor) {
        statusMessageState = msg
        statusColorState = color
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Scan Methods: RFID / GS1 Camera / Manual Input
    // ═══════════════════════════════════════════════════════════════════

    private fun selectScanMethod(method: ScanMethod) {
        if (isCompletingState) return
        stopRfidIfActive()
        showManualBarState = false
        manualErrorTextState = null

        activeMethodState = method
        scanManager?.setCurrentMethod(method)

        when (method) {
            ScanMethod.RFID -> startRfidIfActive()
            ScanMethod.GS1 -> openBarcodeCamera()
            ScanMethod.MANUAL -> showManualBarState = true
            else -> Unit
        }
    }

    private fun startRfidIfActive() {
        try {
            rfidHandler?.startScanning()
            updateStatusBanner(
                msg = if (isArabic) "📡 قارئ RFID جاهز — وجّه الجهاز نحو القطع"
                else "📡 RFID active — point reader at items",
                color = ComposeColor(0xFF06B6D4)
            )
        } catch (e: Exception) {
            Log.e(TAG, "RFID start error", e)
        }
    }

    private fun stopRfidIfActive() {
        try {
            if (rfidHandler?.isScanning == true) {
                rfidHandler?.stopScanning()
            }
        } catch (_: Exception) {
        }
    }

    private fun openBarcodeCamera() {
        try {
            val cameraDialog = BarcodeCameraDialog(
                activity,
                isArabic,
                object : BarcodeCameraDialog.OnBarcodeScannedListener {
                    override fun onBarcodeScanned(rawBarcode: String?) {
                        if (!rawBarcode.isNullOrBlank()) {
                            barcodeHandler?.processRawBarcode(rawBarcode)
                        }
                    }

                    override fun onClosed() {
                        Log.d(TAG, "Barcode camera closed")
                    }
                }
            )
            cameraDialog.show()
            updateStatusBanner(
                msg = if (isArabic) "📷 وجّه الكاميرا نحو رمز القطعة"
                else "📷 Point camera at item code",
                color = ComposeColor(0xFF10B981)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Camera open error", e)
            Toast.makeText(
                activity,
                if (isArabic) "تعذر فتح الكاميرا" else "Unable to open camera",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun submitManualCode() {
        val raw = manualInputTextState.trim()
        val handler = manualHandler ?: return

        if (!handler.isValid(raw)) {
            manualErrorTextState = handler.getValidationError(raw)
            return
        }

        manualErrorTextState = null
        handler.submit(raw)
        manualInputTextState = ""
    }

    private fun completeSession() {
        if (isCompletingState) return

        if (expectedCountState > 0 && scannedCountState < expectedCountState) {
            updateStatusBanner(
                msg = if (isArabic) "⚠️ لم تُمسح جميع القطع ($scannedCountState / $expectedCountState)"
                else "⚠️ Not all items scanned ($scannedCountState / $expectedCountState)",
                color = ComposeColor(0xFFF59E0B)
            )
            return
        }

        isCompletingState = true
        stopRfidIfActive()
        updateStatusBanner(
            msg = if (isArabic) "⏳ جاري اعتماد وإتمام العملية..." else "⏳ Completing session...",
            color = ComposeColor(0xFFD4AF37)
        )
        scanManager?.completeSession()
    }

    private fun confirmCancelSession() {
        showCancelConfirmState = false
        isCompletingState = true
        stopRfidIfActive()
        scanManager?.cancelSession()
        dismiss()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose UI
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun ScanProgressContent() {
        val colors = MiraTheme.colors
        val isDispatch = phase.equals("dispatch", ignoreCase = true)
        val isReadyToComplete = expectedCountState > 0 && scannedCountState >= expectedCountState

        val progressFraction by animateFloatAsState(
            targetValue = if (expectedCountState > 0) {
                (scannedCountState.toFloat() / expectedCountState.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            },
            label = "scanProgress"
        )

        val listState = rememberLazyListState()
        LaunchedEffect(scannedItemsState.size) {
            if (scannedItemsState.isNotEmpty()) {
                listState.animateScrollToItem(scannedItemsState.lastIndex)
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 18.dp),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(
                1.5.dp,
                Brush.linearGradient(
                    listOf(
                        colors.goldPrimary.copy(alpha = 0.7f),
                        ComposeColor(0xFF06B6D4).copy(alpha = 0.55f)
                    )
                )
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                colors.backgroundTop.copy(alpha = 0.96f),
                                colors.cardSurface
                            )
                        )
                    )
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Header: Phase Title + Transfer Number + Close Icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                                .background(
                                    if (isDispatch) ComposeColor(0xFFF59E0B).copy(alpha = 0.16f)
                                    else ComposeColor(0xFF10B981).copy(alpha = 0.16f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isDispatch) Icons.Rounded.LocalShipping else Icons.Rounded.Inventory,
                                contentDescription = null,
                                tint = if (isDispatch) ComposeColor(0xFFF59E0B) else ComposeColor(0xFF10B981),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = if (isArabic) {
                                    if (isDispatch) "إرسال الشحنة" else "استلام الشحنة"
                                } else {
                                    if (isDispatch) "Dispatch Shipment" else "Receive Shipment"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = colors.goldPrimary.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = transferNumber,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.goldPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = { if (!isCompletingState) showCancelConfirmState = true },
                        enabled = !isCompletingState
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = colors.textSecondary
                        )
                    }
                }

                // 2. Progress & Counter Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = colors.backgroundMid,
                    border = BorderStroke(
                        1.dp,
                        if (isReadyToComplete) ComposeColor(0xFF10B981).copy(alpha = 0.55f)
                        else colors.goldPrimary.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isArabic) "تقدم المسح والمطابقة" else "Scan Progress",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = colors.textSecondary
                            )

                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "$scannedCountState",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isReadyToComplete) ComposeColor(0xFF10B981) else colors.goldPrimary
                                )
                                Text(
                                    text = " / $expectedCountState",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textSecondary,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }
                        }

                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape),
                            color = if (isReadyToComplete) ComposeColor(0xFF10B981) else ComposeColor(0xFF06B6D4),
                            trackColor = colors.cardSurface
                        )
                    }
                }

                // 3. Scan Method Selector Chips (RFID / Camera / Manual)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScanMethodChip(
                        icon = Icons.Rounded.Sensors,
                        label = "RFID",
                        selected = activeMethodState == ScanMethod.RFID,
                        accent = ComposeColor(0xFF06B6D4),
                        enabled = !isCompletingState,
                        onClick = { selectScanMethod(ScanMethod.RFID) },
                        modifier = Modifier.weight(1f)
                    )
                    ScanMethodChip(
                        icon = Icons.Rounded.PhotoCamera,
                        label = if (isArabic) "كاميرا" else "Camera",
                        selected = activeMethodState == ScanMethod.GS1,
                        accent = ComposeColor(0xFF10B981),
                        enabled = !isCompletingState,
                        onClick = { selectScanMethod(ScanMethod.GS1) },
                        modifier = Modifier.weight(1f)
                    )
                    ScanMethodChip(
                        icon = Icons.Rounded.Keyboard,
                        label = if (isArabic) "يدوي" else "Manual",
                        selected = activeMethodState == ScanMethod.MANUAL,
                        accent = colors.goldPrimary,
                        enabled = !isCompletingState,
                        onClick = { selectScanMethod(ScanMethod.MANUAL) },
                        modifier = Modifier.weight(1f)
                    )
                }

                // 4. Optional Manual Input Panel
                AnimatedVisibility(visible = showManualBarState) {
                    val focusRequester = remember { FocusRequester() }
                    val keyboard = LocalSoftwareKeyboardController.current

                    LaunchedEffect(Unit) {
                        try {
                            focusRequester.requestFocus()
                            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            imm?.showSoftInput(activity.currentFocus, InputMethodManager.SHOW_IMPLICIT)
                        } catch (_: Exception) {
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = colors.backgroundMid,
                        border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.45f))
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = manualInputTextState,
                                    onValueChange = {
                                        manualInputTextState = it
                                        manualErrorTextState = null
                                    },
                                    singleLine = true,
                                    isError = manualErrorTextState != null,
                                    placeholder = {
                                        Text(
                                            text = "EPC / Serial / ID",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.textSecondary
                                        )
                                    },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(
                                        onSend = {
                                            submitManualCode()
                                            keyboard?.hide()
                                        }
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(focusRequester)
                                )

                                Button(
                                    onClick = { submitManualCode() },
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.goldPrimary,
                                        contentColor = ComposeColor(0xFF0A0F1D)
                                    )
                                ) {
                                    Text(
                                        text = if (isArabic) "إضافة" else "Add",
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        showManualBarState = false
                                        manualErrorTextState = null
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ExpandLess,
                                        contentDescription = null,
                                        tint = colors.textSecondary
                                    )
                                }
                            }

                            manualErrorTextState?.let { err ->
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ComposeColor(0xFFEF4444)
                                )
                            }
                        }
                    }
                }

                // 5. Live Status Banner
                if (statusMessageState.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = statusColorState.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, statusColorState.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = statusMessageState,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = statusColorState,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                        )
                    }
                }

                // 6. Scanned Items List
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp, max = 260.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.backgroundMid)
                        .padding(8.dp)
                ) {
                    if (scannedItemsState.isEmpty()) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.QrCodeScanner,
                                contentDescription = null,
                                tint = colors.textSecondary.copy(alpha = 0.6f),
                                modifier = Modifier.size(34.dp)
                            )
                            Text(
                                text = if (isArabic) "في انتظار مسح قطع الشحنة..."
                                else "Waiting for shipment items scan...",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(
                                items = scannedItemsState,
                                key = { index, item -> item.itemId?.toString() ?: "${item.serial ?: ""}_$index" }
                            ) { index, item ->
                                ScannedItemRowCard(
                                    index = index + 1,
                                    item = item,
                                    isArabic = isArabic
                                )
                            }
                        }
                    }
                }

                // 7. Bottom Actions: Cancel + Complete
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { showCancelConfirmState = true },
                        enabled = !isCompletingState,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, ComposeColor(0xFFEF4444).copy(alpha = 0.65f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ComposeColor(0xFFEF4444))
                    ) {
                        Text(
                            text = if (isArabic) "إلغاء" else "Cancel",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = { completeSession() },
                        enabled = !isCompletingState && (isReadyToComplete || expectedCountState == 0),
                        modifier = Modifier
                            .weight(1.65f)
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isReadyToComplete) ComposeColor(0xFF10B981) else colors.goldPrimary,
                            contentColor = ComposeColor(0xFF0A0F1D)
                        )
                    ) {
                        if (isCompletingState) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = ComposeColor(0xFF0A0F1D)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isArabic) "إتمام العملية" else "Complete",
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }

        if (showCancelConfirmState) {
            AlertDialog(
                onDismissRequest = { showCancelConfirmState = false },
                containerColor = colors.cardSurface,
                title = {
                    Text(
                        text = if (isArabic) "إلغاء جلسة المسح؟" else "Cancel Scan Session?",
                        color = ComposeColor(0xFFEF4444),
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                text = {
                    Text(
                        text = if (isArabic) "سيتم إلغاء جلسة المسح الحالية وعدم اعتماد العملية."
                        else "Current scan session will be cancelled.",
                        color = colors.textPrimary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { confirmCancelSession() },
                        colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFFEF4444))
                    ) {
                        Text(
                            text = if (isArabic) "تأكيد الإلغاء" else "Yes, Cancel",
                            fontWeight = FontWeight.Bold,
                            color = ComposeColor.White
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCancelConfirmState = false }) {
                        Text(
                            text = if (isArabic) "تراجع" else "Keep Scanning",
                            color = colors.textSecondary
                        )
                    }
                }
            )
        }
    }

    @Composable
    private fun ScanMethodChip(
        icon: ImageVector,
        label: String,
        selected: Boolean,
        accent: ComposeColor,
        enabled: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val colors = MiraTheme.colors
        Surface(
            modifier = modifier
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled) { onClick() },
            shape = RoundedCornerShape(12.dp),
            color = if (selected) accent.copy(alpha = 0.2f) else colors.backgroundMid,
            border = BorderStroke(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) accent else colors.textSecondary.copy(alpha = 0.22f)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) accent else colors.textSecondary,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = if (selected) accent else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    @Composable
    private fun ScannedItemRowCard(
        index: Int,
        item: ScanResult,
        isArabic: Boolean
    ) {
        val colors = MiraTheme.colors
        val accentColor = when (item.status) {
            ScanResult.Status.SCANNED -> ComposeColor(0xFF10B981)
            ScanResult.Status.ALREADY_SCANNED, ScanResult.Status.EXTRA -> ComposeColor(0xFFF59E0B)
            ScanResult.Status.NOT_FOUND, ScanResult.Status.ERROR -> ComposeColor(0xFFEF4444)
            else -> ComposeColor(0xFF06B6D4)
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = colors.cardSurface,
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f))
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
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$index",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = accentColor
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (!item.title.isNullOrBlank()) item.title
                            else if (isArabic) "قطعة مجوهرات" else "Jewelry Item",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        val subInfo = buildList {
                            if (!item.serial.isNullOrBlank()) add("SN: ${item.serial}")
                            if (!item.goldKarat.isNullOrBlank()) add(item.goldKarat)
                            if (item.weight > 0.0) add(String.format(Locale.US, "%.2f g", item.weight))
                        }.joinToString(" • ")

                        if (subInfo.isNotEmpty()) {
                            Text(
                                text = subInfo,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Icon(
                    imageVector = when (item.method?.lowercase(Locale.US)) {
                        "rfid" -> Icons.Rounded.Sensors
                        "gs1" -> Icons.Rounded.PhotoCamera
                        "manual" -> Icons.Rounded.Keyboard
                        else -> Icons.Rounded.CheckCircle
                    },
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
