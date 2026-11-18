package com.example.uhf.fragment

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.print.PrintHelper
import com.example.uhf.api.MiraApiClient
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MiraScrapReceiptFragment : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_PURCHASE_ID = "purchase_id"
        private const val ARG_RAW_JSON = "raw_json"

        fun newInstance(purchaseId: Int, rawJson: String?): MiraScrapReceiptFragment {
            val f = MiraScrapReceiptFragment()
            val args = Bundle().apply {
                putInt(ARG_PURCHASE_ID, purchaseId)
                putString(ARG_RAW_JSON, rawJson)
            }
            f.arguments = args
            return f
        }
    }

    private var purchaseId: Int = 0
    private var rawJson: String? = null
    private var isArabic: Boolean = true


    // تفاصيل الإيصال المتفاعلة
    private var companyNameState by mutableStateOf("MIRA ID™")
    private var branchNameState by mutableStateOf("الفرع الرئيسي")
    private var branchPhoneState by mutableStateOf("")
    private var receiptNumberState by mutableStateOf("---")
    private var dateTimeState by mutableStateOf("")
    private var sellerNameState by mutableStateOf("")
    private var customerNameState by mutableStateOf("")
    private var customerPhoneState by mutableStateOf("")
    
    private var weightState by mutableStateOf("0.000 g")
    private var karatState by mutableStateOf("21K")
    private var netWeightState by mutableStateOf("0.000 g")
    private var marketPriceState by mutableStateOf("0.00 SAR")
    private var deductionState by mutableStateOf("0.00 SAR")
    private var totalAmountState by mutableStateOf("0.00 SAR")
    private var paymentMethodState by mutableStateOf("نقداً")

    private var isLoadingState by mutableStateOf(false)

    private fun checkIsArabic(): Boolean {
        return AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar") ||
            resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            purchaseId = it.getInt(ARG_PURCHASE_ID, 0)
            rawJson = it.getString(ARG_RAW_JSON, null)
        }
        isArabic = checkIsArabic()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MiraJewelryTheme {
                    ScrapReceiptContent()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadReceiptData()
    }

    private fun loadReceiptData() {
        // 1. تحميل فوري من الـ JSON الممرر
        if (!rawJson.isNullOrBlank()) {
            try {
                val obj = JSONObject(rawJson!!)
                parseAndPopulate(obj)
            } catch (_: Exception) {
            }
        }

        // 2. جلب من السيرفر للتحديث
        if (purchaseId > 0) {
            isLoadingState = true
            Thread {
                try {
                    val ctx = context ?: return@Thread
                    val api = MiraApiClient.getInstance(ctx)
                    val response = api.getScrapPurchaseReceipt(purchaseId)

                    val act = activity
                    if (act == null || isDetached) return@Thread

                    act.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        isLoadingState = false
                        if (response != null && response.isSuccess && response.data != null) {
                            val dataObj = response.data!!.optJSONObject("data") ?: response.data!!
                            parseAndPopulate(dataObj)
                        }
                    }
                } catch (e: Exception) {
                    val act = activity
                    if (act != null && isAdded) {
                        act.runOnUiThread { isLoadingState = false }
                    }
                }
            }.start()
        }
    }

    private fun parseAndPopulate(root: JSONObject) {
        try {
            val purchase = root.optJSONObject("purchase") ?: root
            val branch = root.optJSONObject("branch")
            val company = root.optJSONObject("company")

            if (company != null) {
                companyNameState = company.optString("name_ar", company.optString("name", "MIRA ID™"))
            }
            if (branch != null) {
                branchNameState = branch.optString("branch_name_ar", branch.optString("branch_name", "الفرع الرئيسي"))
                val phone = branch.optString("phone", "")
                branchPhoneState = if (phone.isNotEmpty()) "📞 $phone" else ""
            }

            receiptNumberState = purchase.optString("purchase_number", purchase.optString("id", "---"))
            dateTimeState = formatDateTime(purchase.optString("created_at", purchase.optString("date", "")))
            sellerNameState = root.optString("seller_name", purchase.optString("seller_name", ""))

            customerNameState = purchase.optString("customer_name", "")
            customerPhoneState = purchase.optString("customer_phone", "")

            val weight = purchase.optDouble("gross_weight_grams", purchase.optDouble("weight_grams", 0.0))
            val netWeight = purchase.optDouble("net_pure_weight_24k", purchase.optDouble("net_weight_grams", 0.0))
            val karat = purchase.optString("gold_karat", purchase.optString("karat", "21K"))

            weightState = String.format(Locale.US, "%.3f g", weight)
            netWeightState = String.format(Locale.US, "%.3f g", netWeight)
            karatState = karat

            val marketPrice = purchase.optDouble("market_price_per_gram", purchase.optDouble("price_per_gram", 0.0))
            val deduction = purchase.optDouble("deduction_per_gram", 0.0)
            val totalAmount = purchase.optDouble("total_amount", purchase.optDouble("total", 0.0))
            val currency = purchase.optString("currency", "SAR")
            val payMethod = purchase.optString("payment_method", "cash")

            marketPriceState = String.format(Locale.US, "%,.2f %s", marketPrice, currency)
            deductionState = String.format(Locale.US, "%,.2f %s", deduction, currency)
            totalAmountState = String.format(Locale.US, "%,.2f %s", totalAmount, currency)

            paymentMethodState = when (payMethod.lowercase(Locale.ROOT)) {
                "card", "pos" -> if (isArabic) "بطاقة / شبكة" else "Card / POS"
                "transfer", "bank" -> if (isArabic) "تحويل بنكي" else "Bank Transfer"
                "cheque" -> if (isArabic) "شيك" else "Cheque"
                else -> if (isArabic) "نقداً" else "Cash"
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun formatDateTime(dateStr: String): String {
        if (dateStr.isBlank()) return ""
        return try {
            val input = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val output = SimpleDateFormat("yyyy/MM/dd - HH:mm", Locale.getDefault())
            val d = input.parse(dateStr)
            if (d != null) output.format(d) else dateStr
        } catch (_: Exception) {
            dateStr
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Print & Share Actions
    // ═══════════════════════════════════════════════════════════════════

    private var receiptViewForCapture: View? = null

    private fun printReceipt() {
        try {
            val view = receiptViewForCapture
            if (view == null) {
                Toast.makeText(requireContext(), "فشل تحضير الإيصال للطباعة", Toast.LENGTH_SHORT).show()
                return
            }
            val bitmap = captureViewAsBitmap(view)
            if (bitmap == null) {
                Toast.makeText(requireContext(), "فشل إنشاء صورة الإيصال", Toast.LENGTH_SHORT).show()
                return
            }

            val printHelper = PrintHelper(requireContext())
            printHelper.setScaleMode(PrintHelper.SCALE_MODE_FIT)
            printHelper.setColorMode(PrintHelper.COLOR_MODE_COLOR)
            printHelper.printBitmap("SCRAP-$receiptNumberState", bitmap)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "خطأ في الطباعة: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareReceipt() {
        try {
            val view = receiptViewForCapture
            if (view == null) {
                Toast.makeText(requireContext(), "فشل تحضير الإيصال للمشاركة", Toast.LENGTH_SHORT).show()
                return
            }
            val bitmap = captureViewAsBitmap(view)
            if (bitmap == null) {
                Toast.makeText(requireContext(), "فشل إنشاء صورة الإيصال", Toast.LENGTH_SHORT).show()
                return
            }

            val cacheDir = File(requireContext().cacheDir, "receipts")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val file = File(cacheDir, "scrap_receipt_$purchaseId.png")
            val fos = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            fos.close()

            val uri = FileProvider.getUriForFile(
                requireContext(),
                requireContext().packageName + ".fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "إيصال شراء كسر ذهب - $companyNameState (#$receiptNumberState)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, "مشاركة إيصال الشراء"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "خطأ في المشاركة: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun captureViewAsBitmap(view: View): Bitmap? {
        return try {
            val width = if (view.width > 0) view.width else 800
            val height = if (view.height > 0) view.height else 1200
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xFFFFFFFF.toInt())
            view.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose UI Layout
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun ScrapReceiptContent() {
        val colors = MiraTheme.colors
        val isAr = isArabic

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            color = colors.cardSurface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // شريط السحب العلوي للـ BottomSheet
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(colors.textSecondary.copy(alpha = 0.3f))
                )

                // إيصال المعاينة البصرية القابل للالتقاط للطباعة والمشاركة
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        android.widget.ScrollView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                            isVerticalScrollBarEnabled = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp),
                    update = { container ->
                        if (container.childCount == 0) {
                            val composeView = ComposeView(container.context).apply {
                                setContent {
                                    ReceiptPrintableCard(isArabic = isAr)
                                }
                            }
                            container.addView(composeView)
                            receiptViewForCapture = composeView
                        }
                    }
                )

                if (isLoadingState) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.goldPrimary
                    )
                }

                // أزرار الإجراءات (طباعة ومشاركة وإغلاق)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { printReceipt() },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.goldPrimary,
                            contentColor = Color(0xFF0A0F1D)
                        )
                    ) {
                        Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isAr) "طباعة" else "Print",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = { shareReceipt() },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.goldPrimary)
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isAr) "مشاركة" else "Share",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = { dismiss() },
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.backgroundMid)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = colors.textSecondary
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ReceiptPrintableCard(isArabic: Boolean) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // رأس الإيصال
                Text(
                    text = companyNameState,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF0F172A)
                )
                Text(
                    text = branchNameState,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD4AF37)
                )
                if (branchPhoneState.isNotEmpty()) {
                    Text(
                        text = branchPhoneState,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF64748B)
                    )
                }

                Divider(color = Color(0xFFCBD5E1), thickness = 1.dp)

                // معلومات السند والتاريخ
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = if (isArabic) "رقم السند:" else "Receipt #:",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF64748B)
                        )
                        Text(
                            text = "#$receiptNumberState",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (isArabic) "التاريخ:" else "Date:",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF64748B)
                        )
                        Text(
                            text = dateTimeState,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                    }
                }

                if (customerNameState.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isArabic) "اسم العميل:" else "Customer:",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF64748B)
                        )
                        Text(
                            text = customerNameState,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                    }
                }

                Divider(color = Color(0xFFE2E8F0), thickness = 0.5.dp)

                // جدول تفاصيل الذهب
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ReceiptDetailRow(if (isArabic) "الوزن الإجمالي:" else "Gross Weight:", weightState)
                    ReceiptDetailRow(if (isArabic) "العيار:" else "Karat:", karatState)
                    ReceiptDetailRow(if (isArabic) "الوزن النقي (24k):" else "Pure Weight:", netWeightState)
                    ReceiptDetailRow(if (isArabic) "سعر الجرام السوقي:" else "Market Price/g:", marketPriceState)
                    ReceiptDetailRow(if (isArabic) "خصم الجرام:" else "Deduction/g:", deductionState)
                }

                Divider(color = Color(0xFFE2E8F0), thickness = 0.5.dp)

                // الإجمالي النهائي وطريقة الدفع
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (isArabic) "طريقة الصرف:" else "Payout Method:",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF64748B)
                        )
                        Text(
                            text = paymentMethodState,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (isArabic) "إجمالي المبلغ المستحق:" else "Total Amount:",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF64748B)
                        )
                        Text(
                            text = totalAmountState,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF10B981)
                        )
                    }
                }

                if (sellerNameState.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = (if (isArabic) "الموظف المسؤول: " else "Cashier: ") + sellerNameState,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF64748B)
                    )
                }

                Text(
                    text = "— MIRA ID™ Secure Verification —",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    @Composable
    private fun ReceiptDetailRow(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = Color(0xFF475569))
            Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
        }
    }
}
