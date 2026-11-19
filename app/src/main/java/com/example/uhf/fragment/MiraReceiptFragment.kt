package com.example.uhf.fragment

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import com.example.uhf.R
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.manager.MiraOrgProfileManager
import com.example.uhf.manager.MiraPriceManager
import com.example.uhf.tools.ArabicNumberToWords
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.abs

class MiraReceiptFragment : DialogFragment() {

    companion object {
        private const val TAG = "MiraReceiptFragment"
        private const val ARG_RECEIPT_JSON = "receipt_json"

        @JvmStatic
        fun newInstance(jsonResponse: String?): MiraReceiptFragment {
            return MiraReceiptFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_RECEIPT_JSON, jsonResponse ?: "{}")
                }
            }
        }
    }

    private var receiptJsonStr: String = "{}"
    private var isArabic: Boolean = true
    private var orgProfile: MiraOrgProfileManager? = null
    private var priceManager: MiraPriceManager? = null
    private var orgName: String = "MIRA Jewelry"
    private var defaultCurrency: String = "YER"

    private var webReceipt: WebView? = null
    private var progressLoading: ProgressBar? = null
    private var btnCloseReceipt: TextView? = null
    private var btnPrintReceipt: Button? = null
    private var btnShareReceipt: Button? = null
    private var btnSaveReceipt: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Material_Light_Dialog_Alert)

        receiptJsonStr = arguments?.getString(ARG_RECEIPT_JSON, "{}") ?: "{}"
        isArabic = Locale.getDefault().language == "ar"

        context?.let { ctx ->
            try {
                orgProfile = MiraOrgProfileManager.getInstance(ctx)
                orgName = orgProfile?.displayName?.takeIf { it.isNotBlank() } ?: "MIRA Jewelry"
            } catch (e: Exception) {
                Log.w(TAG, "OrgProfile init warning: ${e.message}")
            }

            try {
                priceManager = MiraPriceManager.getInstance(ctx)
                defaultCurrency = priceManager?.defaultCurrency?.takeIf { it.isNotBlank() } ?: "YER"
            } catch (e: Exception) {
                Log.w(TAG, "PriceManager init warning: ${e.message}")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_mira_receipt, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        applyLanguage()
        setupListeners()
        renderHtmlReceipt()
    }

    private fun bindViews(view: View) {
        webReceipt = view.findViewById(R.id.webMiraReceipt)
        progressLoading = view.findViewById(R.id.progressReceiptLoading)
        btnCloseReceipt = view.findViewById(R.id.btnMiraCloseReceipt)
        btnPrintReceipt = view.findViewById(R.id.btnPrintReceipt)
        btnShareReceipt = view.findViewById(R.id.btnShareReceipt)
        btnSaveReceipt = view.findViewById(R.id.btnSaveReceipt)

        webReceipt?.apply {
            settings.apply {
                javaScriptEnabled = false
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = false
                setSupportZoom(false)
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) {
                    progressLoading?.visibility = View.GONE
                }
            }
        }
    }

    private fun applyLanguage() {
        btnPrintReceipt?.text = if (isArabic) "طباعة" else "Print"
        btnShareReceipt?.text = if (isArabic) "مشاركة" else "Share"
        btnSaveReceipt?.text = if (isArabic) "حفظ PDF" else "Save PDF"
    }

    private fun setupListeners() {
        btnCloseReceipt?.setOnClickListener { closeFragment() }
        btnPrintReceipt?.setOnClickListener { printReceipt() }
        btnShareReceipt?.setOnClickListener { shareReceiptAsPDF() }
        btnSaveReceipt?.setOnClickListener { saveReceiptAsPDF() }
    }

    // ═══════════════════════════════════════════════════════
    // Safe JSON & Formatting Helpers
    // ═══════════════════════════════════════════════════════

    private fun safeString(obj: JSONObject?, key: String?, defaultValue: String): String {
        if (obj == null || key == null) return defaultValue
        return try {
            val valObj = obj.opt(key)
            if (valObj == null || valObj === JSONObject.NULL) return defaultValue
            val s = valObj.toString().trim()
            if (s.isEmpty() || s.equals("null", ignoreCase = true)) defaultValue else s
        } catch (_: Exception) {
            defaultValue
        }
    }

    private fun findFirstNonEmpty(obj: JSONObject?, vararg keys: String): String {
        if (obj == null) return "—"
        for (key in keys) {
            val v = safeString(obj, key, "")
            if (v.isNotEmpty()) return v
        }
        return "—"
    }

    private fun safeDouble(obj: JSONObject?, key: String?, defaultValue: Double): Double {
        if (obj == null || key == null) return defaultValue
        return try {
            val valObj = obj.opt(key)
            if (valObj == null || valObj === JSONObject.NULL) return defaultValue
            if (valObj is Number) return valObj.toDouble()
            val s = valObj.toString().trim().replace(",", "")
            if (s.isEmpty() || s.equals("null", ignoreCase = true)) defaultValue else s.toDouble()
        } catch (_: Exception) {
            defaultValue
        }
    }

    private fun safeBoolean(obj: JSONObject?, key: String?, defaultValue: Boolean): Boolean {
        if (obj == null || key == null) return defaultValue
        return try {
            val valObj = obj.opt(key)
            if (valObj == null || valObj === JSONObject.NULL) return defaultValue
            if (valObj is Boolean) return valObj
            when (valObj.toString().trim().lowercase(Locale.US)) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> defaultValue
            }
        } catch (_: Exception) {
            defaultValue
        }
    }

    private fun formatMoney(amount: Double, currencyCode: String? = null): String {
        val formatted = String.format(Locale.US, "%,.2f", amount)
        return if (!currencyCode.isNullOrBlank()) "$formatted $currencyCode" else formatted
    }

    private fun escapeHtml(input: String?): String {
        if (input == null) return ""
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun extractItemsArray(t: JSONObject, root: JSONObject): JSONArray? {
        val keys = arrayOf("items", "sold_items", "products", "invoice_items", "lines", "order_items")
        for (k in keys) {
            t.optJSONArray(k)?.let { if (it.length() > 0) return it }
            t.optJSONObject("data")?.optJSONArray(k)?.let { if (it.length() > 0) return it }
            t.optJSONObject("sale")?.optJSONArray(k)?.let { if (it.length() > 0) return it }
            root.optJSONArray(k)?.let { if (it.length() > 0) return it }
        }
        return t.optJSONArray("items") ?: t.optJSONArray("sold_items") ?: t.optJSONArray("products")
    }

    // ═══════════════════════════════════════════════════════
    // Currency, Payment Method & Offline QR Helpers
    // ═══════════════════════════════════════════════════════

    private fun getCurrencyIcon(currency: String?): String {
        return when (currency?.uppercase(Locale.US)) {
            "YER" -> "🇾🇪"
            "SAR" -> "🇸🇦"
            "USD" -> "🇺🇸"
            "AED" -> "🇦🇪"
            "OMR" -> "🇴🇲"
            "KWD" -> "🇰🇼"
            "BHD" -> "🇧🇭"
            "QAR" -> "🇶🇦"
            "EUR" -> "🇪🇺"
            "GBP" -> "🇬🇧"
            else -> "💵"
        }
    }

    private fun getPaymentMethodDisplay(method: String?): String {
        if (method.isNullOrBlank()) return if (isArabic) "نقداً 💵" else "Cash 💵"
        val m = method.uppercase(Locale.US).trim()

        if (!isArabic) {
            return when (m) {
                "CASH" -> "Cash 💵"
                "POS_MADA" -> "Mada POS 💳"
                "VISA_MASTER" -> "Visa / MasterCard 💳"
                "CREDIT" -> "Credit (Receivable) ⏳"
                "CUSTOMER_CREDIT" -> "Customer Credit 💳"
                "TRADE_IN" -> "Gold Trade-In 🥇"
                "SPLIT", "MIX", "MIXED" -> "Mixed Payment 💰"
                "BANK_TRANSFER" -> "Bank Transfer 🏦"
                "E_WALLET" -> "E-Wallet 📱"
                else -> method.replace("_", " ")
            }
        }

        return when (m) {
            "CASH" -> "نقداً 💵"
            "POS_MADA" -> "شبكة مدى 💳"
            "VISA_MASTER" -> "فيزا / ماستر 💳"
            "BANK_TADHAMON" -> "بنك التضامن 🏦"
            "BANK_KURAIMI" -> "بنك الكريمي 🏦"
            "BANK_AHLI" -> "البنك الأهلي 🏦"
            "BANK_YKB" -> "بنك اليمن والكويت 🏦"
            "BANK_SABA" -> "بنك سبأ 🏦"
            "BANK_CAC" -> "كاك بنك 🏦"
            "WALLET_HAZMI" -> "شبكة الحزمي 📱"
            "WALLET_JAIB" -> "حوالات جيب 📱"
            "WALLET_DADIA" -> "شبكة دادية 📱"
            "WALLET_IMTIAZ" -> "شبكة الامتياز 📱"
            "WALLET_NAJM" -> "شبكة النجم 📱"
            "WALLET_HAST" -> "شبكة الحوشي 📱"
            "WALLET_HOUTAR" -> "شبكة الهتار 📱"
            "WALLET_MOTAMAYEZ" -> "شبكة المميز 📱"
            "WALLET_AKWA" -> "شبكة الأكوع 📱"
            "WALLET_YEMEN_NET" -> "الشبكة اليمنية 📱"
            "WALLET_SAIFI" -> "الصيفي كاش 📱"
            "WALLET_EXPRESS" -> "يمن إكسبريس 📱"
            "WALLET_MONEY" -> "مال موني 📱"
            "WALLET_AMRI" -> "العامري كاش 📱"
            "WALLET_BARQ" -> "شبكة البرق 📱"
            "WALLET_SAREE" -> "السريع للحوالات 📱"
            "WALLET_MUHEET" -> "شبكة المحيط 📱"
            "WALLET_NASSER" -> "شبكة الناصر 📱"
            "WALLET_YEAH" -> "ياه موني 📱"
            "WALLET_ONE_CASH" -> "ون كاش 📱"
            "CREDIT" -> "آجل (ذمم) ⏳"
            "CUSTOMER_CREDIT" -> "رصيد دائن 💳"
            "TRADE_IN" -> "دفع بالذهب 🥇"
            "SPLIT", "MIX", "MIXED" -> "متعدد 💰"
            "BANK_TRANSFER" -> "تحويل بنكي 🏦"
            "E_WALLET" -> "محفظة إلكترونية 📱"
            else -> when {
                m.startsWith("BANK_") -> "🏦 ${method.replace("BANK_", "بنك ")}"
                m.startsWith("WALLET_") -> "📱 ${method.replace("WALLET_", "")}"
                else -> method
            }
        }
    }

    private fun generateOfflineQrDataUri(content: String, sizePx: Int = 180): String? {
        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            val darkColor = Color.parseColor("#0D1B2A")
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) darkColor else Color.WHITE)
                }
            }
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            "data:image/png;base64,$b64"
        } catch (e: Exception) {
            Log.w(TAG, "Offline QR fallback: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════
    // Print / Share / Save PDF
    // ═══════════════════════════════════════════════════════

    private fun printReceipt() {
        val ctx = context ?: return
        val web = webReceipt ?: return
        try {
            val pm = ctx.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
            val jobName = "${orgName}_Invoice"
            val adapter = web.createPrintDocumentAdapter(jobName)
            pm.print(jobName, adapter, PrintAttributes.Builder().build())
        } catch (e: Exception) {
            Log.e(TAG, "Print error: ${e.message}", e)
            Toast.makeText(ctx, if (isArabic) "❌ فشل الطباعة" else "❌ Print failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareReceiptAsPDF() {
        val ctx = context ?: return
        val web = webReceipt ?: return
        Toast.makeText(ctx, if (isArabic) "⏳ جاري تجهيز الملف..." else "⏳ Preparing file...", Toast.LENGTH_SHORT).show()

        web.postDelayed({
            if (!isAdded || context == null) return@postDelayed
            try {
                val pdf = generateCachePDF()
                if (pdf != null && pdf.exists()) {
                    val uri = FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.fileprovider",
                        pdf
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(
                            Intent.EXTRA_SUBJECT,
                            "$orgName - ${if (isArabic) "فاتورة مجوهرات" else "Jewelry Invoice"}"
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, if (isArabic) "مشاركة الفاتورة" else "Share Invoice"))
                } else {
                    shareAsImage()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Share PDF error: ${e.message}", e)
                shareAsImage()
            }
        }, 400L)
    }

    private fun shareAsImage() {
        val ctx = context ?: return
        try {
            val bitmap = captureWebView() ?: return
            val dir = File(ctx.cacheDir, "shares").apply { if (!exists()) mkdirs() }
            val file = File(dir, "invoice_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, if (isArabic) "مشاركة الفاتورة" else "Share Invoice"))
        } catch (e: Exception) {
            Log.e(TAG, "Share image error: ${e.message}", e)
        }
    }

    private fun saveReceiptAsPDF() {
        val ctx = context ?: return
        val web = webReceipt ?: return
        Toast.makeText(ctx, if (isArabic) "⏳ جاري الحفظ..." else "⏳ Saving...", Toast.LENGTH_SHORT).show()

        web.postDelayed({
            if (!isAdded || context == null) return@postDelayed
            try {
                val savedName = savePdfToDownloads()
                if (savedName != null) {
                    Toast.makeText(
                        requireContext(),
                        (if (isArabic) "✅ تم الحفظ في التنزيلات: " else "✅ Saved to Downloads: ") + savedName,
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        if (isArabic) "❌ فشل حفظ الملف" else "❌ Failed to save file",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Save PDF error: ${e.message}", e)
                Toast.makeText(
                    requireContext(),
                    if (isArabic) "❌ فشل الحفظ" else "❌ Save failed",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, 400L)
    }

    private fun buildPdfDocument(): PdfDocument? {
        val bitmap = captureWebView() ?: return null
        val doc = PdfDocument()
        val pageWidth = 595
        val pageHeight = maxOf(842, (bitmap.height * pageWidth) / bitmap.width)

        val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = doc.startPage(info)
        val scaled = Bitmap.createScaledBitmap(bitmap, pageWidth, pageHeight, true)
        page.canvas.drawBitmap(scaled, 0f, 0f, null)
        doc.finishPage(page)
        return doc
    }

    private fun generateCachePDF(): File? {
        val ctx = context ?: return null
        val doc = buildPdfDocument() ?: return null
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "${orgName.replace("\\s+".toRegex(), "_")}_Invoice_$timestamp.pdf"
            val dir = File(ctx.cacheDir, "invoices").apply { if (!exists()) mkdirs() }
            val file = File(dir, fileName)
            FileOutputStream(file).use { fos -> doc.writeTo(fos) }
            doc.close()
            file
        } catch (e: Exception) {
            doc.close()
            Log.e(TAG, "Cache PDF error: ${e.message}", e)
            null
        }
    }

    private fun savePdfToDownloads(): String? {
        val ctx = context ?: return null
        val doc = buildPdfDocument() ?: return null
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${orgName.replace("\\s+".toRegex(), "_")}_Invoice_$timestamp.pdf"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/MIRA_Invoices")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        doc.writeTo(out)
                    }
                    doc.close()
                    fileName
                } else {
                    doc.close()
                    null
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "MIRA_Invoices"
                ).apply { if (!exists()) mkdirs() }
                val file = File(dir, fileName)
                FileOutputStream(file).use { fos -> doc.writeTo(fos) }
                doc.close()
                file.name
            }
        } catch (e: Exception) {
            doc.close()
            Log.e(TAG, "Save Downloads PDF error: ${e.message}", e)
            null
        }
    }

    private fun captureWebView(): Bitmap? {
        val web = webReceipt ?: return null
        return try {
            web.measure(
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            web.layout(0, 0, web.measuredWidth, web.measuredHeight)
            val bitmap = Bitmap.createBitmap(
                web.measuredWidth,
                web.measuredHeight,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            web.draw(canvas)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Capture WebView error: ${e.message}", e)
            null
        }
    }

    // ═══════════════════════════════════════════════════════
    // Sub-Sections Builders (Payments, Trade-Ins, Refunds, Summary)
    // ═══════════════════════════════════════════════════════

    private fun extractPaymentsArray(t: JSONObject, paymentObj: JSONObject?): JSONArray? {
        return t.optJSONArray("payments") ?: paymentObj?.optJSONArray("payments")
    }

    private fun extractTradeInsArray(t: JSONObject, paymentObj: JSONObject?): JSONArray? {
        val keys = arrayOf("trade_ins", "gold_payments", "trade_in_items", "gold_trade_ins")
        for (k in keys) {
            t.optJSONArray(k)?.let { if (it.length() > 0) return it }
            paymentObj?.optJSONArray(k)?.let { if (it.length() > 0) return it }
            t.optJSONObject("sale")?.optJSONArray(k)?.let { if (it.length() > 0) return it }
        }
        return t.optJSONArray("trade_ins") ?: paymentObj?.optJSONArray("trade_ins")
    }

    private fun resolvePaymentBaseAmount(
        p: JSONObject,
        amount: Double,
        currency: String,
        baseCurrency: String,
        rate: Double
    ): Double {
        val explicitBase = safeDouble(
            p, "base_amount",
            safeDouble(p, "amount_in_yer", safeDouble(p, "amount_local", -1.0))
        )
        if (explicitBase > 0.0) {
            return explicitBase
        }
        if (baseCurrency.equals(currency, ignoreCase = true)) {
            return amount
        }
        if (currency.equals("USD", ignoreCase = true) && !baseCurrency.equals("USD", ignoreCase = true) && rate > 1.0) {
            return amount * rate
        }
        if (rate > 1.0 && amount < 5000.0 && baseCurrency.equals("YER", ignoreCase = true)) {
            return amount * rate
        }
        return amount
    }

    private fun buildPaymentsHtml(
        t: JSONObject,
        paymentObj: JSONObject?,
        baseCurrency: String,
        defaultExchangeRate: Double
    ): String {
        return try {
            val payments = extractPaymentsArray(t, paymentObj)
            if (payments == null || payments.length() == 0) return ""

            if (payments.length() == 1) {
                val single = payments.optJSONObject(0)
                if (single != null) {
                    val curr = safeString(single, "currency", baseCurrency)
                    val method = safeString(single, "method", "cash")
                    val ref = findFirstNonEmpty(single, "reference_number", "reference", "transaction_id", "voucher_no")
                    val tradeInsCheck = extractTradeInsArray(t, paymentObj)
                    val hasTradeIn = tradeInsCheck != null && tradeInsCheck.length() > 0
                    if (!hasTradeIn && ref == "—" &&
                        baseCurrency.equals(curr, ignoreCase = true) &&
                        "cash".equals(method, ignoreCase = true)
                    ) {
                        return ""
                    }
                }
            }

            val sb = StringBuilder()
            sb.append("<div class='payment-breakdown'>")
                .append("<div class='payment-title'>💵 ")
                .append(if (isArabic) "تفاصيل طرق الدفع والعملات" else "Payment & Currency Breakdown")
                .append("</div>")
                .append("<table class='payments-table'><thead><tr>")
                .append("<th>").append(if (isArabic) "العملة" else "Currency").append("</th>")
                .append("<th>").append(if (isArabic) "المبلغ المدفوع" else "Paid Amount").append("</th>")
                .append("<th>").append(if (isArabic) "طريقة الدفع / المرجع" else "Method / Ref").append("</th>")
                .append("<th>").append(if (isArabic) "المعادل ($baseCurrency)" else "Equivalent ($baseCurrency)").append("</th>")
                .append("</tr></thead><tbody>")

            var totalInBase = 0.0

            for (i in 0 until payments.length()) {
                val p = payments.optJSONObject(i) ?: continue
                val currency = safeString(p, "currency", baseCurrency)
                val amount = safeDouble(p, "amount", 0.0)
                val rate = safeDouble(p, "exchange_rate", safeDouble(p, "server_rate", defaultExchangeRate))
                val baseAmount = resolvePaymentBaseAmount(p, amount, currency, baseCurrency, rate)

                totalInBase += baseAmount

                val methodCode = safeString(p, "method", "cash")
                val serverLabel = safeString(p, "method_label", safeString(p, "payment_method_name", ""))
                val methodText = serverLabel.ifEmpty { getPaymentMethodDisplay(methodCode) }
                val ref = findFirstNonEmpty(p, "reference_number", "reference", "transaction_id", "voucher_no")

                val icon = getCurrencyIcon(currency)
                val rateInfo = if (!baseCurrency.equals(currency, ignoreCase = true) && rate > 1.0) {
                    "<br><span class='sub-note'>(1 $currency = ${String.format(Locale.US, "%,.2f", rate)} $baseCurrency)</span>"
                } else {
                    ""
                }
                val refInfo = if (ref != "—") {
                    "<br><span class='ref-tag'>#${escapeHtml(ref)}</span>"
                } else {
                    ""
                }

                sb.append("<tr>")
                    .append("<td class='nowrap' style='font-weight:bold;'>").append(icon).append(" ").append(escapeHtml(currency)).append("</td>")
                    .append("<td class='nowrap' style='font-weight:bold; color:#0d1b2a;'>")
                    .append(String.format(Locale.US, "%,.2f", amount)).append(rateInfo).append("</td>")
                    .append("<td>").append(escapeHtml(methodText)).append(refInfo).append("</td>")
                    .append("<td class='nowrap' style='font-weight:bold; color:#c59b27;'>")
                    .append(String.format(Locale.US, "%,.2f", baseAmount)).append("</td>")
                    .append("</tr>")
            }

            if (payments.length() > 1) {
                sb.append("<tr class='payment-total-row'>")
                    .append("<td colspan='3' style='text-align:right; font-weight:bold;'>")
                    .append(if (isArabic) "إجمالي المدفوعات:" else "Total Paid:")
                    .append("</td>")
                    .append("<td class='nowrap' style='font-weight:bold; color:#0d1b2a;'>")
                    .append(String.format(Locale.US, "%,.2f", totalInBase)).append(" $baseCurrency</td>")
                    .append("</tr>")
            }

            sb.append("</tbody></table></div>")
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "buildPaymentsHtml error: ${e.message}", e)
            ""
        }
    }

    private fun buildTradeInsHtml(t: JSONObject, paymentObj: JSONObject?, baseCurrency: String): String {
        return try {
            val tradeIns = extractTradeInsArray(t, paymentObj)
            if (tradeIns == null || tradeIns.length() == 0) return ""

            var fallbackTotal = safeDouble(t, "trade_in_total", safeDouble(t, "tradeInTotal", 0.0))
            if (fallbackTotal <= 0 && paymentObj != null) {
                fallbackTotal = safeDouble(paymentObj, "trade_in_total", safeDouble(paymentObj, "tradeInTotal", 0.0))
            }

            val sb = StringBuilder()
            sb.append("<div class='trade-in-section'>")
                .append("<div class='trade-in-title'>🥇 ")
                .append(if (isArabic) "الذهب المُستلم كجزء من الدفع (استبدال/كسر)" else "Gold Trade-In Received")
                .append("</div>")
                .append("<table class='trade-ins-table'><thead><tr>")
                .append("<th style='width:25px;'>#</th>")
                .append("<th>").append(if (isArabic) "الوصف" else "Description").append("</th>")
                .append("<th>").append(if (isArabic) "العيار" else "Karat").append("</th>")
                .append("<th>").append(if (isArabic) "الوزن الصافي (g)" else "Net Wt (g)").append("</th>")
                .append("<th>").append(if (isArabic) "سعر الجرام" else "Rate/g").append("</th>")
                .append("<th>").append(if (isArabic) "القيمة ($baseCurrency)" else "Value ($baseCurrency)").append("</th>")
                .append("</tr></thead><tbody>")

            var totalWeight = 0.0
            var totalValue = 0.0

            for (i in 0 until tradeIns.length()) {
                val ti = tradeIns.optJSONObject(i) ?: continue
                val grossWt = safeDouble(ti, "gross_weight_grams", safeDouble(ti, "gross_weight", 0.0))
                val stoneWt = safeDouble(ti, "stone_weight_grams", safeDouble(ti, "stone_weight", 0.0))
                val netWt = safeDouble(
                    ti, "net_weight_grams",
                    safeDouble(
                        ti, "weight_grams",
                        safeDouble(ti, "weight", if (grossWt > 0) (grossWt - stoneWt).coerceAtLeast(0.0) else 0.0)
                    )
                )
                val value = safeDouble(
                    ti, "total_amount",
                    safeDouble(ti, "value_yer", safeDouble(ti, "value", 0.0))
                )
                val ratePerGram = safeDouble(
                    ti, "price_per_gram",
                    safeDouble(ti, "gram_price", if (netWt > 0 && value > 0) value / netWt else 0.0)
                )
                val karat = safeString(ti, "gold_karat", safeString(ti, "karat", "21K"))
                val desc = safeString(
                    ti, "description",
                    safeString(ti, "purchase_number", if (isArabic) "ذهب كسر" else "Scrap Gold")
                )

                totalWeight += netWt
                totalValue += value

                val valueCell = if (value > 0) "${String.format(Locale.US, "%,.0f", value)} $baseCurrency" else "—"
                val rateCell = if (ratePerGram > 0) String.format(Locale.US, "%,.0f", ratePerGram) else "—"
                val wtNote = if (stoneWt > 0.001) {
                    "<br><span class='sub-note'>(${if (isArabic) "قائم" else "Gross"}: ${String.format(Locale.US, "%.3f", grossWt)}g)</span>"
                } else {
                    ""
                }

                sb.append("<tr>")
                    .append("<td>").append(i + 1).append("</td>")
                    .append("<td>").append(escapeHtml(desc)).append("</td>")
                    .append("<td class='nowrap'><span class='karat-badge'>").append(escapeHtml(karat)).append("</span></td>")
                    .append("<td class='nowrap'><b>").append(String.format(Locale.US, "%.3f", netWt)).append("</b> g").append(wtNote).append("</td>")
                    .append("<td class='nowrap'>").append(rateCell).append("</td>")
                    .append("<td class='nowrap' style='font-weight:bold; color:#b45309;'>").append(valueCell).append("</td>")
                    .append("</tr>")
            }

            val displayTotal = if (totalValue > 0) totalValue else fallbackTotal
            val totalCell = if (displayTotal > 0) "${String.format(Locale.US, "%,.0f", displayTotal)} $baseCurrency" else "—"

            sb.append("<tr class='trade-in-total-row'>")
                .append("<td colspan='3' style='text-align:right; font-weight:bold;'>")
                .append(if (isArabic) "إجمالي الذهب المُستلم:" else "Total Trade-In:")
                .append("</td>")
                .append("<td class='nowrap' style='font-weight:bold;'>").append(String.format(Locale.US, "%.3f", totalWeight)).append(" g</td>")
                .append("<td>—</td>")
                .append("<td class='nowrap' style='font-weight:bold; color:#b45309;'>").append(totalCell).append("</td>")
                .append("</tr></tbody></table></div>")

            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "buildTradeInsHtml error: ${e.message}", e)
            ""
        }
    }

    private fun buildRefundsHtml(t: JSONObject, baseCurrency: String): String {
        return try {
            val refunds = t.optJSONArray("refunds")
            val singleRefund = t.optJSONObject("refund")
            if ((refunds == null || refunds.length() == 0) && singleRefund == null) return ""

            val list = mutableListOf<JSONObject>()
            if (refunds != null) {
                for (i in 0 until refunds.length()) {
                    refunds.optJSONObject(i)?.let { list.add(it) }
                }
            } else if (singleRefund != null) {
                list.add(singleRefund)
            }
            if (list.isEmpty()) return ""

            val sb = StringBuilder()
            sb.append("<div class='refund-section'>")
                .append("<div class='refund-section-title'>🔄 ")
                .append(if (isArabic) "سجل عمليات الإرجاع المرتبطة بالفاتورة" else "Linked Return Transactions")
                .append("</div>")
                .append("<table class='refund-table'><thead><tr>")
                .append("<th>").append(if (isArabic) "رقم المرتجع" else "Return #").append("</th>")
                .append("<th>").append(if (isArabic) "التاريخ" else "Date").append("</th>")
                .append("<th>").append(if (isArabic) "السبب / البيان" else "Reason").append("</th>")
                .append("<th>").append(if (isArabic) "طريقة الإرجاع" else "Method").append("</th>")
                .append("<th>").append(if (isArabic) "المبلغ المُرجع" else "Refunded").append("</th>")
                .append("</tr></thead><tbody>")

            for (r in list) {
                val refNum = findFirstNonEmpty(r, "refund_number", "return_number", "id")
                val refDate = findFirstNonEmpty(r, "created_at", "refund_date", "timestamp")
                val reason = findFirstNonEmpty(r, "reason", "notes", "remark")
                val method = getPaymentMethodDisplay(safeString(r, "refund_method", safeString(r, "payment_method", "CASH")))
                val amount = safeDouble(
                    r, "refund_amount_local",
                    safeDouble(r, "total_refunded", safeDouble(r, "amount", safeDouble(r, "refund_amount_usd", 0.0)))
                )
                val curr = safeString(r, "currency", baseCurrency)

                sb.append("<tr>")
                    .append("<td class='nowrap' style='font-weight:bold;'>").append(escapeHtml(refNum)).append("</td>")
                    .append("<td>").append(escapeHtml(refDate)).append("</td>")
                    .append("<td>").append(escapeHtml(reason)).append("</td>")
                    .append("<td>").append(escapeHtml(method)).append("</td>")
                    .append("<td class='nowrap' style='font-weight:bold; color:#c53030;'>")
                    .append(String.format(Locale.US, "%,.2f", amount)).append(" ").append(escapeHtml(curr))
                    .append("</td>")
                    .append("</tr>")
            }

            sb.append("</tbody></table></div>")
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "buildRefundsHtml error: ${e.message}", e)
            ""
        }
    }

    private fun buildPaymentSummaryHtml(
        t: JSONObject,
        paymentObj: JSONObject?,
        finalInvoiceAmount: Double,
        displayCurrency: String,
        usdToYerRate: Double
    ): String {
        return try {
            if (finalInvoiceAmount <= 0) return ""

            val isDisplayUsd = displayCurrency.equals("USD", ignoreCase = true)
            val safeRate = if (usdToYerRate > 1.0) usdToYerRate else 530.0
            val finalYer = if (isDisplayUsd) finalInvoiceAmount * safeRate else finalInvoiceAmount

            val payments = extractPaymentsArray(t, paymentObj)
            var cashPaidYer = 0.0
            if (payments != null) {
                for (i in 0 until payments.length()) {
                    val p = payments.optJSONObject(i) ?: continue
                    val pCurr = safeString(p, "currency", "YER")
                    val pAmt = safeDouble(p, "amount", 0.0)
                    val pRate = safeDouble(
                        p, "exchange_rate",
                        safeDouble(p, "server_rate", if (pCurr.equals("USD", true)) safeRate else 1.0)
                    )
                    cashPaidYer += resolvePaymentBaseAmount(p, pAmt, pCurr, "YER", pRate)
                }
            }

            val tradeIns = extractTradeInsArray(t, paymentObj)
            var tradeInYer = 0.0
            if (tradeIns != null) {
                for (i in 0 until tradeIns.length()) {
                    val ti = tradeIns.optJSONObject(i) ?: continue
                    tradeInYer += safeDouble(
                        ti, "total_amount",
                        safeDouble(ti, "value_yer", safeDouble(ti, "value", 0.0))
                    )
                }
            }
            if (tradeInYer <= 0) {
                tradeInYer = safeDouble(
                    t, "trade_in_total",
                    paymentObj?.let { safeDouble(it, "trade_in_total", 0.0) } ?: 0.0
                )
            }

            val creditUsedYer = safeDouble(
                t, "credit_used_local",
                safeDouble(
                    t, "credit_used",
                    paymentObj?.let { safeDouble(it, "credit_used_local", safeDouble(it, "credit_used", 0.0)) } ?: 0.0
                )
            )

            if (cashPaidYer <= 0 && tradeInYer <= 0 && creditUsedYer <= 0) return ""

            val factor = if (isDisplayUsd) (1.0 / safeRate) else 1.0
            val cashDisplay = cashPaidYer * factor
            val tradeInDisplay = tradeInYer * factor
            val creditDisplay = creditUsedYer * factor
            val diffDisplay = (cashPaidYer + tradeInYer + creditUsedYer - finalYer) * factor
            val tolerance = if (isDisplayUsd) 0.02 else 1.0

            val sb = StringBuilder()
            sb.append("<div class='payment-summary'>")
                .append("<div class='payment-summary-title'>📊 ")
                .append(if (isArabic) "ملخص السداد والتسوية" else "Payment & Settlement Summary")
                .append("</div>")
                .append("<table class='payment-summary-table'>")
                .append("<tr><td class='label'>")
                .append(if (isArabic) "صافي قيمة الفاتورة:" else "Net Invoice Total:")
                .append("</td><td class='value nowrap'>")
                .append(String.format(Locale.US, "%,.2f", finalInvoiceAmount)).append(" $displayCurrency</td></tr>")

            if (cashDisplay > 0) {
                sb.append("<tr><td class='label'>💵 ")
                    .append(if (isArabic) "المسدد نقداً / إلكترونياً:" else "Cash / Electronic Paid:")
                    .append("</td><td class='value nowrap'>")
                    .append(String.format(Locale.US, "%,.2f", cashDisplay)).append(" $displayCurrency</td></tr>")
            }

            if (tradeInDisplay > 0) {
                sb.append("<tr><td class='label'>🥇 ")
                    .append(if (isArabic) "قيمة الذهب المُستلم:" else "Gold Trade-In Value:")
                    .append("</td><td class='value nowrap'>")
                    .append(String.format(Locale.US, "%,.2f", tradeInDisplay)).append(" $displayCurrency</td></tr>")
            }

            if (creditDisplay > 0) {
                sb.append("<tr><td class='label'>💳 ")
                    .append(if (isArabic) "خصم من رصيد العميل الدائن:" else "Applied Customer Credit:")
                    .append("</td><td class='value nowrap'>")
                    .append(String.format(Locale.US, "%,.2f", creditDisplay)).append(" $displayCurrency</td></tr>")
            }

            when {
                abs(diffDisplay) < tolerance -> {
                    sb.append("<tr class='payment-summary-equal'><td colspan='2' style='text-align:center;'>")
                        .append(if (isArabic) "✅ الفاتورة مسددة بالكامل" else "✅ Paid in Full")
                        .append("</td></tr>")
                }
                diffDisplay > 0 -> {
                    sb.append("<tr class='payment-summary-credit'><td class='label'>")
                        .append(if (isArabic) "💰 فائض / رصيد دائن للعميل:" else "💰 Customer Credit Balance:")
                        .append("</td><td class='value nowrap'>+")
                        .append(String.format(Locale.US, "%,.2f", diffDisplay)).append(" $displayCurrency</td></tr>")
                }
                else -> {
                    sb.append("<tr class='payment-summary-short'><td class='label'>")
                        .append(if (isArabic) "⚠️ المتبقي (ذمم على العميل):" else "⚠️ Remaining Balance Due:")
                        .append("</td><td class='value nowrap'>")
                        .append(String.format(Locale.US, "%,.2f", abs(diffDisplay))).append(" $displayCurrency</td></tr>")
                }
            }

            sb.append("</table></div>")
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "buildPaymentSummaryHtml error: ${e.message}", e)
            ""
        }
    }

    // ═══════════════════════════════════════════════════════
    // Main HTML Rendering
    // ═══════════════════════════════════════════════════════

    private fun renderHtmlReceipt() {
        val web = webReceipt ?: return
        progressLoading?.visibility = View.VISIBLE

        val phone = orgProfile?.orgPhone.orEmpty()
        val address = orgProfile?.orgAddress.orEmpty()
        val dir = if (isArabic) "rtl" else "ltr"
        val alignStart = if (isArabic) "right" else "left"
        val alignEnd = if (isArabic) "left" else "right"

        val h = StringBuilder()
        h.append("<!DOCTYPE html><html dir='").append(dir).append("'><head><meta charset='UTF-8'>")
            .append("<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>")
            .append("<style>")
            .append("* { box-sizing: border-box; }")
            .append("body { font-family: 'Segoe UI', Tahoma, sans-serif; padding: 10px; margin: 0 auto; max-width: 100%; background: #fff; color: #1a1a1a; font-size: 10.5px; line-height: 1.4; }")
            .append(".nowrap { white-space: nowrap; }")
            .append(".sub-note { font-size: 8px; color: #64748b; }")
            .append(".ref-tag { font-size: 8px; font-family: monospace; color: #2b6cb0; }")
            .append(".header { display: flex; justify-content: space-between; align-items: center; border-bottom: 2px solid #0d1b2a; padding-bottom: 8px; margin-bottom: 8px; }")
            .append(".brand-box { text-align: ").append(alignStart).append("; }")
            .append(".org-name { font-size: 19px; font-weight: 800; color: #0d1b2a; }")
            .append(".org-subtitle { font-size: 9.5px; color: #c59b27; font-weight: bold; }")
            .append(".org-meta { font-size: 9px; color: #555; margin-top: 2px; }")
            .append(".invoice-title-bar { display: flex; justify-content: space-between; align-items: center; gap: 8px; background: #0d1b2a; color: #fff; padding: 7px 10px; border-radius: 6px; margin: 8px 0; }")
            .append(".invoice-title { font-size: 13px; font-weight: bold; color: #f4d58d; }")
            .append(".invoice-type { font-size: 10px; white-space: nowrap; background: rgba(255,255,255,0.15); padding: 3px 8px; border-radius: 4px; border: 1px solid #c59b27; }")
            .append(".refund-alert { background: #fff5f5; border: 1px solid #feb2b2; color: #c53030; padding: 7px; border-radius: 6px; text-align: center; font-weight: bold; margin-bottom: 8px; font-size: 10.5px; }")
            .append(".info-grid { width: 100%; font-size: 9.5px; margin-bottom: 8px; border: 1px solid #e0e0e0; border-radius: 6px; padding: 6px 8px; background: #fafafa; }")
            .append(".info-grid table { width: 100%; border-collapse: collapse; }")
            .append(".info-grid td { padding: 3px 4px; vertical-align: top; }")
            .append(".label { color: #666; } .value { font-weight: bold; color: #0d1b2a; }")
            .append("table.items { width: 100%; border-collapse: collapse; margin: 8px 0 4px 0; font-size: 9.5px; }")
            .append("table.items th { background: #0d1b2a; color: #f4d58d; padding: 5px 3px; text-align: center; border: 1px solid #0d1b2a; }")
            .append("table.items td { padding: 5px 3px; border: 1px solid #e2e8f0; text-align: center; color: #2d3748; }")
            .append("table.items tr:nth-child(even) { background-color: #f8fafc; }")
            .append(".returned-row { background-color: #fff5f5 !important; color: #e53e3e; text-decoration: line-through; }")
            .append(".serial-box { font-family: monospace; font-size: 8.5px; background: #edf2f7; padding: 2px 4px; border-radius: 3px; color: #2b6cb0; word-break: break-all; display: inline-block; }")
            .append(".karat-badge { display: inline-block; padding: 1px 5px; border-radius: 8px; font-weight: bold; background: #fefcbf; color: #744210; border: 1px solid #f6e05e; }")
            .append(".karat-strip { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 5px 8px; margin-bottom: 8px; font-size: 9.5px; color: #1e293b; text-align: center; }")
            .append(".payment-breakdown { margin: 10px 0; border: 2px solid #c59b27; border-radius: 8px; overflow: hidden; background: #fffdf5; }")
            .append(".payment-title { background: #0d1b2a; color: #f4d58d; padding: 6px 10px; font-weight: bold; font-size: 10.5px; text-align: center; }")
            .append("table.payments-table { width: 100%; border-collapse: collapse; font-size: 9.5px; }")
            .append("table.payments-table th { background: #faf8f0; color: #0d1b2a; padding: 5px 3px; text-align: center; border-bottom: 2px solid #e0d5b0; font-weight: bold; }")
            .append("table.payments-table td { padding: 6px 3px; border-bottom: 1px solid #f0ead0; color: #2d3748; text-align: center; }")
            .append(".payment-total-row td { background: #faf8f0; font-weight: bold; }")
            .append(".summary-container { display: flex; justify-content: space-between; margin-top: 8px; gap: 8px; }")
            .append(".notes-box { width: 52%; font-size: 8.8px; border: 1px dashed #cbd5e0; padding: 7px; border-radius: 6px; background: #f7fafc; }")
            .append(".totals-box { width: 46%; }")
            .append(".totals-table { width: 100%; font-size: 10px; border-collapse: collapse; }")
            .append(".totals-table td { padding: 3px 0; }")
            .append(".grand-total { font-size: 12px; font-weight: bold; color: #0d1b2a; border-top: 2px solid #0d1b2a; border-bottom: 2px solid #0d1b2a; padding: 5px 0; }")
            .append(".tafqeet-box { font-size: 9.5px; color: #1a202c; text-align: center; margin-top: 8px; padding: 6px; background: #f7fafc; border-radius: 6px; border: 1px solid #e2e8f0; }")
            .append(".warranty-box { font-size: 9.5px; font-weight: bold; text-align: center; margin: 8px 0; padding: 6px; border-radius: 6px; border: 1px dashed #38a169; background: #f0fff4; color: #22543d; }")
            .append(".footer-section { margin-top: 12px; display: flex; justify-content: space-between; align-items: flex-end; }")
            .append(".qr-box { text-align: center; }")
            .append(".qr-box img { width: 78px; height: 78px; border: 1px solid #e2e8f0; padding: 2px; border-radius: 4px; background: #fff; }")
            .append(".signatures { display: flex; gap: 16px; }")
            .append(".sig-box { text-align: center; width: 96px; font-size: 8.5px; font-weight: bold; color: #4a5568; }")
            .append(".sig-line { border-top: 1px solid #a0aec0; margin-top: 26px; padding-top: 3px; }")
            .append(".copyright { text-align: center; margin-top: 12px; font-size: 8px; color: #a0aec0; border-top: 1px solid #edf2f7; padding-top: 5px; }")
            .append(".trade-in-section { margin: 10px 0; border: 2px solid #d97706; border-radius: 8px; overflow: hidden; background: #fffbeb; }")
            .append(".trade-in-title { background: linear-gradient(135deg,#92400e,#b45309); color: #fff; padding: 6px 10px; font-weight: bold; font-size: 10.5px; text-align: center; }")
            .append("table.trade-ins-table { width: 100%; border-collapse: collapse; font-size: 9.5px; }")
            .append("table.trade-ins-table th { background: #fef3c7; color: #78350f; padding: 5px 3px; text-align: center; border-bottom: 2px solid #fde68a; }")
            .append("table.trade-ins-table td { padding: 6px 3px; border-bottom: 1px solid #fde68a; color: #2d3748; text-align: center; }")
            .append(".trade-in-total-row td { background: #fef3c7; border-top: 2px solid #f59e0b; }")
            .append(".refund-section { margin: 10px 0; border: 2px solid #e53e3e; border-radius: 8px; overflow: hidden; background: #fff5f5; }")
            .append(".refund-section-title { background: #c53030; color: #fff; padding: 6px 10px; font-weight: bold; font-size: 10.5px; text-align: center; }")
            .append("table.refund-table { width: 100%; border-collapse: collapse; font-size: 9.5px; }")
            .append("table.refund-table th { background: #fed7d7; color: #742a2a; padding: 5px 3px; text-align: center; }")
            .append("table.refund-table td { padding: 5px 3px; border-bottom: 1px solid #feb2b2; text-align: center; }")
            .append(".payment-summary { margin: 10px 0; border: 2px solid #0d1b2a; border-radius: 8px; overflow: hidden; background: #f8fafc; }")
            .append(".payment-summary-title { background: #0d1b2a; color: #f4d58d; padding: 6px 10px; font-weight: bold; font-size: 10.5px; text-align: center; }")
            .append("table.payment-summary-table { width: 100%; border-collapse: collapse; font-size: 10px; }")
            .append("table.payment-summary-table td { padding: 6px 10px; border-bottom: 1px solid #e2e8f0; }")
            .append("table.payment-summary-table td.label { color: #4a5568; text-align: ").append(alignStart).append("; }")
            .append("table.payment-summary-table td.value { font-weight: bold; text-align: ").append(alignEnd).append("; color: #0d1b2a; }")
            .append(".payment-summary-equal { background: #dcfce7; color: #166534; font-weight: bold; }")
            .append(".payment-summary-credit { background: #dbeafe; color: #1e40af; font-weight: bold; }")
            .append(".payment-summary-short { background: #fee2e2; color: #991b1b; font-weight: bold; }")
            .append("</style></head><body>")

        var totalWeightSum = 0.0
        var displayAmount = "0.00"
        var displayCurrencyLabel = "USD"
        var saleNumber = "—"
        var dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

        try {
            val root = JSONObject(receiptJsonStr)
            var t = root.optJSONObject("sale")
                ?: root.optJSONObject("transaction")
                ?: root.optJSONObject("data")
                ?: root.optJSONObject("receipt")
                ?: root

            val innerSale = t.optJSONObject("sale")
            if (innerSale != null && innerSale.optString("sale_number", "").isNotEmpty()) {
                t = innerSale
            }

            val customerObj = t.optJSONObject("customer")
            val paymentObj = t.optJSONObject("payment")
            val orgObj = root.optJSONObject("org") ?: t.optJSONObject("org")

            val taxNumber = findFirstNonEmpty(orgObj ?: t, "tax_number", "vat_number", "cr_number")
            val sellerName = findFirstNonEmpty(t, "seller_name", "seller", "cashier_name", "created_by")

            var branchName = safeString(t, "branch_name", safeString(t, "branch", ""))
            if (branchName.isEmpty() && context != null) {
                try {
                    val bm = MiraBranchesManager.getInstance(requireContext())
                    if (bm != null && bm.hasActiveBranch()) {
                        branchName = bm.activeBranchDisplayName
                    }
                } catch (_: Exception) {
                }
            }
            if (branchName.isEmpty()) branchName = if (isArabic) "الفرع الرئيسي" else "Main Branch"

            saleNumber = findFirstNonEmpty(t, "sale_number", "invoice_number", "receipt_number", "id")
            val origSaleNumber = findFirstNonEmpty(t, "original_sale_number", "parent_sale_number")
            val rawDate = findFirstNonEmpty(t, "timestamp", "created_at", "sale_date")
            if (rawDate != "—") {
                dateStr = rawDate
            }

            var customer = safeString(t, "customer_name", customerObj?.let { safeString(it, "name", "") }.orEmpty())
            if (customer.isEmpty()) customer = if (isArabic) "عميل نقدي" else "Cash Customer"

            val customerPhone = safeString(t, "customer_phone", customerObj?.let { safeString(it, "phone", "") }.orEmpty())
            val customerEmail = safeString(t, "customer_email", customerObj?.let { safeString(it, "email", "") }.orEmpty())
            val customerAddress = safeString(t, "customer_address", customerObj?.let { safeString(it, "address", "") }.orEmpty())
            val customerId = safeString(t, "customer_id", "")

            val saleNotes = safeString(t, "notes", safeString(t, "sale_notes", safeString(t, "remark", "")))
            val customerNotes = safeString(t, "customer_notes", customerObj?.let { safeString(it, "notes", "") }.orEmpty())
            val combinedNotes = listOf(saleNotes, customerNotes).filter { it.isNotEmpty() }.distinct().joinToString(" | ")

            var paymentMethod = findFirstNonEmpty(
                t, "payment_method",
                paymentObj?.let { safeString(it, "payment_method", "CASH") } ?: "CASH"
            )
            if (paymentMethod == "—") paymentMethod = "CASH"

            val tradeInsHdr = extractTradeInsArray(t, paymentObj)
            val paymentsHdr = extractPaymentsArray(t, paymentObj)
            val hasTradeInHdr = tradeInsHdr != null && tradeInsHdr.length() > 0
            val paymentsCount = paymentsHdr?.length() ?: 0

            val paymentMethodDisplay = when {
                hasTradeInHdr && paymentsCount == 0 ->
                    if (isArabic) "دفع بالذهب 🥇" else "Gold Trade-In 🥇"
                hasTradeInHdr || paymentsCount > 1 || "MIX".equals(safeString(t, "currency", ""), ignoreCase = true) ->
                    if (isArabic) "متعدد 💰" else "Mixed 💰"
                else -> getPaymentMethodDisplay(paymentMethod)
            }

            var currency = safeString(
                t, "currency",
                paymentObj?.let { safeString(it, "currency_code", "") }.orEmpty()
            )
            if (currency.isEmpty() || "MIX".equals(currency, ignoreCase = true)) {
                currency = priceManager?.defaultCurrency?.takeIf { it.isNotBlank() } ?: defaultCurrency
            }

            var currencyName = paymentObj?.let { safeString(it, "currency_name", "") }.orEmpty()
            if (currencyName.isEmpty()) {
                currencyName = try {
                    ArabicNumberToWords.getCurrencyArabicName(currency)
                } catch (_: Exception) {
                    currency
                }
            }

            var exchangeRate = safeDouble(
                t, "exchange_rate",
                paymentObj?.let { safeDouble(it, "exchange_rate", 0.0) } ?: 0.0
            )
            if (exchangeRate <= 1.0 && priceManager != null) {
                try {
                    val pmRate = priceManager!!.getExchangeRate(currency)
                    if (pmRate > 1.0) exchangeRate = pmRate
                } catch (_: Exception) {
                }
            }
            if (exchangeRate <= 1.0) exchangeRate = 530.0

            val goldSpot21k = safeDouble(t, "gold_price_21k", safeDouble(t, "gold_rate_21k", 0.0))
            val goldSpot24k = safeDouble(t, "gold_price_24k", safeDouble(t, "spot_price_usd", 0.0))

            var refundStatus = findFirstNonEmpty(t, "refund_status", "status")
            if (refundStatus == "—") refundStatus = "completed"

            val isFullyRefunded = refundStatus == "refunded" || refundStatus == "fully_refunded"
            val isPartiallyRefunded = refundStatus == "partially_refunded"

            // ═══ 1. Header ═══
            h.append("<div class='header'><div class='brand-box'>")
                .append("<div class='org-name'>").append(escapeHtml(orgName)).append("</div>")
                .append("<div class='org-subtitle'>")
                .append(if (isArabic) "للمجوهرات والذهب والألماس" else "Jewelry, Gold & Diamond Specialist")
                .append("</div>")
                .append("<div class='org-meta'>📍 ")
                .append(escapeHtml(address.ifEmpty { if (isArabic) "صنعاء - اليمن" else "Sana'a - Yemen" }))
            if (phone.isNotEmpty()) {
                h.append(" | 📞 ").append(escapeHtml(phone))
            }
            if (taxNumber != "—") {
                h.append(" | 🏛️ ").append(if (isArabic) "الرقم الضريبي: " else "Tax ID: ").append(escapeHtml(taxNumber))
            }
            h.append("</div></div></div>")

            // ═══ 2. Invoice Title Bar ═══
            val titleText = when {
                isFullyRefunded -> if (isArabic) "🔄 سند مرتجع مبيعات كلي" else "FULL RETURN INVOICE"
                isPartiallyRefunded -> if (isArabic) "⚠️ سند مرتجع مبيعات جزئي" else "PARTIAL RETURN INVOICE"
                else -> if (isArabic) "🧾 فاتورة بيع مجوهرات" else "JEWELRY SALES INVOICE"
            }

            h.append("<div class='invoice-title-bar'>")
                .append("<div class='invoice-title'>").append(titleText).append("</div>")
                .append("<div class='invoice-type'>")
                .append(if (isArabic) "رقم السند: " else "INV #: ")
                .append(escapeHtml(saleNumber))
                .append("</div></div>")

            if (isPartiallyRefunded) {
                h.append("<div class='refund-alert'>")
                    .append(
                        if (isArabic) "⚠️ تنبيه: تم إرجاع بعض القطع من هذه الفاتورة. الأصناف المرتجعة مشطوبة أدناه."
                        else "⚠️ Notice: Some items in this invoice were returned (crossed out below)."
                    )
                    .append("</div>")
            } else if (isFullyRefunded) {
                h.append("<div class='refund-alert'>")
                    .append(
                        if (isArabic) "🚨 تنبيه: هذه الفاتورة مرتجعة بالكامل والمبلغ مُرجع للعميل."
                        else "🚨 Notice: This invoice has been fully refunded."
                    )
                    .append("</div>")
            }

            // ═══ 3. Info Grid ═══
            h.append("<div class='info-grid'><table>")
                .append("<tr>")
                .append("<td class='label'>").append(if (isArabic) "اسم العميل:" else "Customer:").append("</td>")
                .append("<td class='value'>").append(escapeHtml(customer))
            if (customerId.isNotEmpty() && customerId != "0" && customerId != "null") {
                h.append(" <span class='ref-tag'>(#").append(escapeHtml(customerId)).append(")</span>")
            }
            h.append("</td>")
                .append("<td class='label'>").append(if (isArabic) "التاريخ:" else "Date:").append("</td>")
                .append("<td class='value'>").append(escapeHtml(dateStr)).append("</td>")
                .append("</tr>")

                .append("<tr>")
                .append("<td class='label'>").append(if (isArabic) "هاتف العميل:" else "Phone:").append("</td>")
                .append("<td class='value'>").append(if (customerPhone.isEmpty()) "—" else escapeHtml(customerPhone)).append("</td>")
                .append("<td class='label'>").append(if (isArabic) "البائع:" else "Seller:").append("</td>")
                .append("<td class='value'>").append(escapeHtml(sellerName)).append("</td>")
                .append("</tr>")

                .append("<tr>")
                .append("<td class='label'>").append(if (isArabic) "طريقة الدفع:" else "Payment:").append("</td>")
                .append("<td class='value'>").append(escapeHtml(paymentMethodDisplay)).append("</td>")
                .append("<td class='label'>").append(if (isArabic) "الفرع:" else "Branch:").append("</td>")
                .append("<td class='value'>").append(escapeHtml(branchName)).append("</td>")
                .append("</tr>")

                .append("<tr>")
                .append("<td class='label'>").append(if (isArabic) "سعر الصرف:" else "FX Rate:").append("</td>")
                .append("<td class='value nowrap'>1 USD = ").append(String.format(Locale.US, "%,.2f", exchangeRate)).append(" ").append(escapeHtml(currency)).append("</td>")
            when {
                goldSpot21k > 0 || goldSpot24k > 0 -> {
                    val spotLabel = if (goldSpot21k > 0) {
                        "21K: ${String.format(Locale.US, "%,.2f", goldSpot21k)}"
                    } else {
                        "24K: ${String.format(Locale.US, "%,.2f", goldSpot24k)}"
                    }
                    h.append("<td class='label'>").append(if (isArabic) "تسعيرة الذهب:" else "Gold Rate:").append("</td>")
                        .append("<td class='value nowrap'>").append(spotLabel).append("</td>")
                }
                origSaleNumber != "—" -> {
                    h.append("<td class='label'>").append(if (isArabic) "الفاتورة الأصلية:" else "Original Inv:").append("</td>")
                        .append("<td class='value'>").append(escapeHtml(origSaleNumber)).append("</td>")
                }
                else -> h.append("<td colspan='2'></td>")
            }
            h.append("</tr>")

            if (customerEmail.isNotEmpty() || customerAddress.isNotEmpty()) {
                h.append("<tr>")
                if (customerEmail.isNotEmpty()) {
                    h.append("<td class='label'>").append(if (isArabic) "البريد:" else "Email:").append("</td>")
                        .append("<td class='value'>").append(escapeHtml(customerEmail)).append("</td>")
                } else {
                    h.append("<td colspan='2'></td>")
                }
                if (customerAddress.isNotEmpty()) {
                    h.append("<td class='label'>").append(if (isArabic) "العنوان:" else "Address:").append("</td>")
                        .append("<td class='value'>").append(escapeHtml(customerAddress)).append("</td>")
                } else {
                    h.append("<td colspan='2'></td>")
                }
                h.append("</tr>")
            }

            if (combinedNotes.isNotEmpty()) {
                h.append("<tr><td class='label'>")
                    .append(if (isArabic) "ملاحظات البيع:" else "Sale Notes:")
                    .append("</td><td class='value' colspan='3'>")
                    .append(escapeHtml(combinedNotes))
                    .append("</td></tr>")
            }

            h.append("</table></div>")

            // ═══ 4. Items Table & Karat Weight Summary ═══
            val items = extractItemsArray(t, root)
            var activePiecesCount = 0
            val weightByKarat = LinkedHashMap<String, Double>()

            if (items != null && items.length() > 0) {
                h.append("<table class='items'><thead><tr>")
                    .append("<th style='width:24px;'>#</th>")
                    .append("<th>").append(if (isArabic) "الوصف والصنف" else "Item Description").append("</th>")
                    .append("<th>").append(if (isArabic) "الرقم التسلسلي / RFID" else "Serial / Tag").append("</th>")
                    .append("<th>").append(if (isArabic) "العيار" else "Karat").append("</th>")
                    .append("<th>").append(if (isArabic) "الوزن الصافي" else "Net Wt (g)").append("</th>")
                    .append("<th>").append(if (isArabic) "المصنعية" else "Making").append("</th>")
                    .append("<th>").append(if (isArabic) "الإجمالي ($)" else "Total ($)").append("</th>")
                    .append("</tr></thead><tbody>")

                for (i in 0 until items.length()) {
                    val it = items.optJSONObject(i) ?: continue

                    val isItemRefunded = safeBoolean(
                        it, "is_returned",
                        safeBoolean(it, "is_refunded", false)
                    ) || isFullyRefunded

                    val netWeight = safeDouble(
                        it, "net_weight",
                        safeDouble(
                            it, "weight",
                            safeDouble(
                                it, "net_weight_grams",
                                safeDouble(it, "gross_weight", safeDouble(it, "gross_weight_grams", 0.0))
                            )
                        )
                    )
                    val grossWeight = safeDouble(it, "gross_weight", safeDouble(it, "gross_weight_grams", netWeight))
                    val stoneWeight = safeDouble(it, "stone_weight", safeDouble(it, "stone_weight_grams", 0.0))

                    var karat = findFirstNonEmpty(it, "karat", "gold_karat")
                    if (karat == "—") karat = "21K"

                    if (!isItemRefunded) {
                        totalWeightSum += netWeight
                        activePiecesCount++
                        weightByKarat[karat] = (weightByKarat[karat] ?: 0.0) + netWeight
                    }

                    val laborFee = safeDouble(it, "making_fee", safeDouble(it, "labor_price", 0.0))
                    val itemPriceUSD = safeDouble(
                        it, "price_usd",
                        safeDouble(it, "total_usd", safeDouble(it, "sale_price", safeDouble(it, "price", 0.0)))
                    )

                    val serial = findFirstNonEmpty(
                        it, "serial_number", "serial_no", "item_code", "epc", "barcode", "gtin", "tag", "code"
                    )
                    val title = findFirstNonEmpty(
                        it, "title", "item_title", "name", "product_name", "description"
                    )
                    val category = findFirstNonEmpty(it, "category", "category_name", "item_type")

                    val rowClass = if (isItemRefunded) "class='returned-row'" else ""
                    val weightNote = when {
                        stoneWeight > 0.001 -> "<br><span class='sub-note'>(${if (isArabic) "فصوص" else "Stone"}: ${String.format(Locale.US, "%.3f", stoneWeight)}g)</span>"
                        grossWeight > netWeight + 0.001 -> "<br><span class='sub-note'>(${if (isArabic) "قائم" else "Gross"}: ${String.format(Locale.US, "%.3f", grossWeight)}g)</span>"
                        else -> ""
                    }

                    h.append("<tr ").append(rowClass).append(">")
                        .append("<td>").append(i + 1).append("</td>")
                        .append("<td style='text-align:").append(alignStart).append("; font-weight:bold;'>")
                        .append(escapeHtml(title))
                    if (category != "—") {
                        h.append("<br><span class='sub-note'>")
                            .append(escapeHtml(category)).append("</span>")
                    }
                    if (isItemRefunded) {
                        h.append(" <span style='color:#e53e3e; font-size:9px;'>(")
                            .append(if (isArabic) "مرتجع" else "Returned").append(")</span>")
                    }
                    h.append("</td>")
                        .append("<td><span class='serial-box'>").append(escapeHtml(serial)).append("</span></td>")
                        .append("<td class='nowrap'><span class='karat-badge'>").append(escapeHtml(karat)).append("</span></td>")
                        .append("<td class='nowrap'><b>").append(String.format(Locale.US, "%.3f", netWeight)).append("</b> g").append(weightNote).append("</td>")
                        .append("<td class='nowrap'>").append(if (laborFee > 0) formatMoney(laborFee, "$") else "—").append("</td>")
                        .append("<td class='nowrap' style='font-weight:bold; color:#0d1b2a;'>").append(formatMoney(itemPriceUSD, "$")).append("</td>")
                        .append("</tr>")
                }
                h.append("</tbody></table>")

                if (weightByKarat.isNotEmpty()) {
                    val karatSummary = weightByKarat.entries.joinToString(" &nbsp;|&nbsp; ") { (k, w) ->
                        "<b>${escapeHtml(k)}:</b> ${String.format(Locale.US, "%.3f", w)} g"
                    }
                    h.append("<div class='karat-strip'>")
                        .append(if (isArabic) "⚖️ ملخص الأوزان النشطة: " else "⚖️ Active Weights: ")
                        .append(karatSummary)
                        .append(" &nbsp;|&nbsp; <b>")
                        .append(if (isArabic) "العدد: " else "Pieces: ").append(activePiecesCount)
                        .append("</b></div>")
                }
            }

            if (totalWeightSum <= 0.0) {
                totalWeightSum = safeDouble(
                    t, "total_weight",
                    safeDouble(t, "net_weight", safeDouble(t, "total_net_weight", safeDouble(t, "weight", 0.0)))
                )
            }

            // ═══ 5. Multi-Currency Payments Breakdown ═══
            val paymentsHtml = buildPaymentsHtml(t, paymentObj, currency, exchangeRate)
            if (paymentsHtml.isNotEmpty()) h.append(paymentsHtml)

            // ═══ 6. Gold Trade-Ins Table ═══
            val tradeInsHtml = buildTradeInsHtml(t, paymentObj, currency)
            if (tradeInsHtml.isNotEmpty()) h.append(tradeInsHtml)

            // ═══ 7. Refunds History Table ═══
            val refundsHtml = buildRefundsHtml(t, currency)
            if (refundsHtml.isNotEmpty()) h.append(refundsHtml)

            // ═══ 8. Totals & Notes ═══
            val subtotalUSD = safeDouble(
                t, "subtotal_usd",
                paymentObj?.let { safeDouble(it, "subtotal_usd", safeDouble(t, "subtotal", 0.0)) }
                    ?: safeDouble(t, "subtotal", 0.0)
            )
            val discountUSD = safeDouble(
                t, "discount_amount_usd",
                safeDouble(
                    t, "discount_usd",
                    paymentObj?.let { safeDouble(it, "discount_amount_usd", safeDouble(t, "discount", 0.0)) }
                        ?: safeDouble(t, "discount", 0.0)
                )
            )
            val taxUSD = safeDouble(
                t, "tax_amount_usd",
                safeDouble(t, "tax_usd", safeDouble(t, "vat_usd", 0.0))
            )
            val finalUSD = safeDouble(
                t, "final_amount_usd",
                safeDouble(
                    t, "total_amount_usd",
                    safeDouble(
                        t, "total_usd",
                        paymentObj?.let { safeDouble(it, "final_amount_usd", subtotalUSD - discountUSD + taxUSD) }
                            ?: (subtotalUSD - discountUSD + taxUSD)
                    )
                )
            )

            var finalLocal = safeDouble(
                t, "final_amount",
                safeDouble(
                    t, "total_amount",
                    safeDouble(
                        t, "final_amount_local",
                        safeDouble(
                            t, "total",
                            paymentObj?.let {
                                safeDouble(it, "final_amount", safeDouble(it, "final_amount_local", 0.0))
                            } ?: 0.0
                        )
                    )
                )
            )
            if (finalLocal <= 0 && finalUSD > 0) {
                finalLocal = finalUSD * exchangeRate
            }

            h.append("<div class='summary-container'>")

            h.append("<div class='notes-box'>")
                .append("<b>📌 ").append(if (isArabic) "شروط الضمان والاسترجاع:" else "Warranty & Return Policy:").append("</b><br>")
                .append("1. ").append(if (isArabic) "المجوهرات المباعة مضمونة من عيوب التصنيع لمدة عام كامل." else "Jewelry guaranteed against manufacturing defects for 1 year.").append("<br>")
                .append("2. ").append(if (isArabic) "عند الاستبدال أو الإرجاع يجب إحضار أصل هذا السند والبطاقة الذكية." else "Original receipt and smart tag required for exchange or return.").append("<br>")
                .append("3. ").append(if (isArabic) "إجمالي الوزن الصافي النشط: " else "Total Active Net Weight: ")
                .append("<b>").append(String.format(Locale.US, "%.3f g", totalWeightSum)).append("</b>")
                .append("</div>")

            h.append("<div class='totals-box'><table class='totals-table'>")
                .append("<tr><td class='label'>")
                .append(if (isArabic) "الإجمالي قبل الخصم:" else "Subtotal:")
                .append("</td><td class='value nowrap' style='text-align:").append(alignEnd).append(";'>")
                .append(formatMoney(subtotalUSD, "USD"))
                .append("</td></tr>")

            if (discountUSD > 0) {
                h.append("<tr><td class='label'>")
                    .append(if (isArabic) "الخصم الممنوح:" else "Discount:")
                    .append("</td><td class='value nowrap' style='text-align:").append(alignEnd).append("; color:#e53e3e;'>- ")
                    .append(formatMoney(discountUSD, "USD"))
                    .append("</td></tr>")
            }

            if (taxUSD > 0) {
                h.append("<tr><td class='label'>")
                    .append(if (isArabic) "الضريبة:" else "Tax / VAT:")
                    .append("</td><td class='value nowrap' style='text-align:").append(alignEnd).append(";'>")
                    .append(formatMoney(taxUSD, "USD"))
                    .append("</td></tr>")
            }

            if (finalLocal > 0 && abs(finalLocal - finalUSD) > 0.01) {
                h.append("<tr><td class='label'>")
                    .append(if (isArabic) "الصافي بالدولار:" else "Net (USD):")
                    .append("</td><td class='value nowrap' style='text-align:").append(alignEnd).append(";'>")
                    .append(formatMoney(finalUSD, "USD"))
                    .append("</td></tr>")
            }

            val effectiveTotalForSummary: Double
            if (finalLocal > 0 && abs(finalLocal - finalUSD) > 0.01) {
                displayAmount = String.format(Locale.US, "%,.2f", finalLocal)
                displayCurrencyLabel = currency
                effectiveTotalForSummary = finalLocal
            } else {
                displayAmount = String.format(Locale.US, "%,.2f", finalUSD)
                displayCurrencyLabel = "USD"
                effectiveTotalForSummary = finalUSD
            }

            h.append("<tr class='grand-total'><td class='label' style='color:#0d1b2a;'>")
                .append(if (isArabic) "💰 الصافي المستحق:" else "💰 Net Total:")
                .append("</td><td class='value nowrap' style='text-align:").append(alignEnd).append("; color:#0d1b2a;'>")
                .append(displayAmount)
                .append(" <span style='font-size:10.5px; color:#c59b27;'>")
                .append(escapeHtml(displayCurrencyLabel))
                .append("</span></td></tr>")
                .append("</table></div></div>")

            // ═══ 9. Payment Settlement Summary ═══
            val summaryHtml = buildPaymentSummaryHtml(
                t, paymentObj, effectiveTotalForSummary, displayCurrencyLabel, exchangeRate
            )
            if (summaryHtml.isNotEmpty()) h.append(summaryHtml)

            // ═══ 10. Customer Store Credit Created ═══
            var creditCreated = safeDouble(
                t, "credit_created_local",
                paymentObj?.let { safeDouble(it, "credit_created_local", 0.0) } ?: 0.0
            )
            if (creditCreated <= 0.01) {
                t.optJSONObject("sale")?.let { creditCreated = safeDouble(it, "credit_created_local", 0.0) }
            }

            if (creditCreated > 0.01) {
                var creditId = t.optLong("credit_id_created", 0L)
                if (creditId == 0L) {
                    t.optJSONObject("sale")?.let { creditId = it.optLong("credit_id_created", 0L) }
                }

                h.append("<div style='margin:10px 0; padding:10px; background:linear-gradient(135deg,#eff6ff 0%,#dbeafe 100%); border:2px solid #3b82f6; border-radius:10px;'>")
                    .append("<div style='text-align:center; font-weight:bold; color:#1e40af; font-size:11.5px; margin-bottom:4px;'>💰 ")
                    .append(if (isArabic) "رصيد دائن للعميل" else "Customer Store Credit")
                    .append("</div>")
                    .append("<div style='text-align:center; font-size:18px; font-weight:900; color:#1e3a8a; padding:5px; background:#fff; border-radius:6px;'>+")
                    .append(String.format(Locale.US, "%,.2f", creditCreated)).append(" ").append(escapeHtml(currency))
                    .append("</div>")

                if (creditId > 0L) {
                    h.append("<div style='text-align:center; font-size:9px; color:#3b82f6; font-family:monospace; margin-top:4px;'>")
                        .append(if (isArabic) "رقم قيد الرصيد: " else "Credit ID: ").append(creditId)
                        .append("</div>")
                }

                h.append("<div style='text-align:center; font-size:9.5px; color:#1e40af; margin-top:4px;'>")
                    .append(if (isArabic) "✅ صالح 12 شهراً — يُستخدم في مشترياتك القادمة" else "✅ Valid for 12 months on future purchases")
                    .append("</div></div>")
            }

            // ═══ 11. Tafqeet (Amount in Words) ═══
            if (finalUSD > 0 || finalLocal > 0) {
                try {
                    val isLocal = currency.equals("YER", ignoreCase = true) ||
                        currency.equals("SAR", ignoreCase = true) ||
                        currency.equals("AED", ignoreCase = true)
                    val amountForWords = if (isLocal && finalLocal > 0) finalLocal else finalUSD
                    val amountInWords = ArabicNumberToWords.convert(amountForWords, currencyName)

                    h.append("<div class='tafqeet-box'>")
                        .append("<b>").append(if (isArabic) "فقط وقدره كتابةً: " else "Amount in Words: ").append("</b>")
                        .append("<span style='color:#c59b27; font-weight:bold;'>").append(escapeHtml(amountInWords)).append("</span>")
                        .append("</div>")
                } catch (e: Exception) {
                    Log.w(TAG, "Tafqeet warning: ${e.message}")
                }
            }

            // ═══ 12. Warranty Banner & Offline QR Footer ═══
            h.append("<div class='warranty-box'>🛡️ ")
                .append(if (isArabic) "سند إلكتروني موثق برقم تسلسلي مع الضمان المالي والمصنعي" else "Digitally Verified Gold & Jewelry Transaction Guarantee")
                .append("</div>")

            val verifyUrl = safeString(t, "verify_url", "")
            val qrPayload = verifyUrl.ifEmpty {
                "Org:$orgName|Invoice:$saleNumber|Date:$dateStr|Total:$displayAmount $displayCurrencyLabel|Weight:${String.format(Locale.US, "%.3f", totalWeightSum)}g"
            }
            val qrImgSrc = generateOfflineQrDataUri(qrPayload)
                ?: "https://api.qrserver.com/v1/create-qr-code/?size=150x150&data=${android.net.Uri.encode(qrPayload)}"

            h.append("<div class='footer-section'>")
                .append("<div class='qr-box'>")
                .append("<img src='").append(qrImgSrc).append("' alt='QR Verify'/><br>")
                .append("<span class='sub-note'>")
                .append(if (isArabic) "رمز التحقق الرقمي" else "Digital Verification")
                .append("</span></div>")
                .append("<div class='signatures'>")
                .append("<div class='sig-box'><div class='sig-line'>").append(if (isArabic) "توقيع المشتري" else "Customer Sign").append("</div></div>")
                .append("<div class='sig-box'><div class='sig-line'>").append(if (isArabic) "ختم وتوقيع المحل" else "Authorized Stamp").append("</div></div>")
                .append("</div></div>")

        } catch (e: Exception) {
            Log.e(TAG, "Error rendering receipt: ${e.message}", e)
            h.append("<p style='color:red;text-align:center;padding:20px;'>")
                .append(if (isArabic) "❌ خطأ في تحميل بيانات السند" else "❌ Error rendering receipt")
                .append("</p>")
                .append("<p style='color:#666;text-align:center;font-size:10px;'>").append(escapeHtml(e.message)).append("</p>")
        }

        h.append("<div class='copyright'>")
            .append(escapeHtml(orgName)).append(" © 2026 | ")
            .append(if (isArabic) "نظام إدارة المبيعات الذكي - ميرا للمجوهرات" else "MIRA Jewelry POS System")
            .append("</div></body></html>")

        web.loadDataWithBaseURL(null, h.toString(), "text/html", "UTF-8", null)
    }

    private fun closeFragment() {
        if (isAdded) {
            dismissAllowingStateLoss()
        }
    }
}
