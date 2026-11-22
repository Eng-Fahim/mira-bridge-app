package com.example.uhf.ui.inventory

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.uhf.manager.MiraBranchesManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object InventoryReportExporter {

    private var activePrintWebView: WebView? = null

    /**
     * استخراج اسم الجهة/المنشأة والفرع من جلسة التطبيق النشطة
     */
    private fun resolveEntityHeader(context: Context, fallbackBranch: String, isArabic: Boolean): Pair<String, String> {
        var entityName = if (isArabic) "ميرا تكنولوجي للحلول الذكية • MIRA ID™" else "MIRA TECHNOLOGY™ • Smart Jewelry ERP"
        var branchLabel = fallbackBranch

        runCatching {
            val bm = MiraBranchesManager.getInstance(context)
            val methodNames = listOf("getEntityName", "getOrganizationName", "getCompanyName", "getTenantName")
            for (name in methodNames) {
                val m = bm.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                val res = m?.invoke(bm)?.toString()?.trim()
                if (!res.isNullOrEmpty() && !res.equals("null", ignoreCase = true)) {
                    entityName = res
                    break
                }
            }

            if (branchLabel.isBlank() || branchLabel.startsWith("الفرع الحالي") || branchLabel.startsWith("Current Branch")) {
                val bMethod = bm.javaClass.methods.firstOrNull {
                    (it.name == "getActiveBranchName" || it.name == "getBranchName") && it.parameterTypes.isEmpty()
                }
                val bRes = bMethod?.invoke(bm)?.toString()?.trim()
                if (!bRes.isNullOrEmpty() && !bRes.equals("null", ignoreCase = true)) {
                    branchLabel = bRes
                }
            }
        }

        if (entityName.contains("MIRA", ignoreCase = true) || entityName.contains("ميرا")) {
            val prefs = context.getSharedPreferences("mira_prefs", Context.MODE_PRIVATE)
            val savedEntity = prefs.getString("entity_name", null)
                ?: prefs.getString("organization_name", null)
                ?: prefs.getString("company_name", null)
            if (!savedEntity.isNullOrBlank()) {
                entityName = savedEntity.trim()
            }
        }

        return entityName to branchLabel
    }

    /**
     * تجميع ملخص العيارات تلقائياً من قائمة القطع:
     * karat -> (matchedCount, expectedCount, scannedWeightGrams, scannedValueUsd)
     */
    private data class KaratAgg(
        var matched: Int = 0,
        var expected: Int = 0,
        var weight: Double = 0.0,
        var value: Double = 0.0
    )

    private fun aggregateByKarat(items: List<ArchiveReportItemDetail>): Map<String, KaratAgg> {
        val map = linkedMapOf<String, KaratAgg>()
        items.forEach { item ->
            val k = item.karat.ifBlank { "-" }
            val agg = map.getOrPut(k) { KaratAgg() }
            when (item.status) {
                ArchiveItemStatus.MATCHED -> {
                    agg.matched += 1
                    agg.expected += 1
                    agg.weight += item.weightGrams
                    agg.value += item.estimatedValue
                }
                ArchiveItemStatus.MISSING -> {
                    agg.expected += 1
                }
                ArchiveItemStatus.UNCATALOGUED -> {
                    agg.matched += 1
                    agg.weight += item.weightGrams
                    agg.value += item.estimatedValue
                }
            }
        }
        return map
    }

    /**
     * طباعة التقرير أو حفظه كملف PDF عبر مربع الطباعة الرسمي في أندرويد
     */
    fun printOrSavePdf(
        context: Context,
        details: ArchiveReportDetails,
        isArabic: Boolean
    ) {
        val dir = if (isArabic) "rtl" else "ltr"
        val align = if (isArabic) "right" else "left"
        val (entityName, resolvedBranch) = resolveEntityHeader(context, details.branchName, isArabic)
        val title = if (isArabic) "تقرير مطابقة الجرد الذكي" else "Smart Inventory Audit Report"

        val karatStats = aggregateByKarat(details.items)

        val karatRowsHtml = if (karatStats.isNotEmpty()) {
            buildString {
                append(
                    """
                    <h3 style="margin:14px 0 6px 0;font-size:13px;color:#0B132B;">
                        ${if (isArabic) "ملخص الجرد حسب العيار" else "Summary by Karat"}
                    </h3>
                    <table style="margin-top:4px;margin-bottom:14px;">
                        <thead>
                            <tr>
                                <th>${if (isArabic) "العيار" else "Karat"}</th>
                                <th>${if (isArabic) "المطابق / المتوقع" else "Matched / Expected"}</th>
                                <th>${if (isArabic) "نسبة المطابقة" else "Match %"}</th>
                                <th>${if (isArabic) "الوزن المقروء" else "Scanned Weight"}</th>
                                <th>${if (isArabic) "القيمة التقديرية" else "Est. Value"}</th>
                            </tr>
                        </thead>
                        <tbody>
                    """.trimIndent()
                )
                karatStats.forEach { (karat, agg) ->
                    val pct = if (agg.expected > 0) (agg.matched * 100.0) / agg.expected else 100.0
                    append(
                        """
                        <tr>
                            <td style="font-weight:bold;color:#B45309;">${escapeHtml(karat)}</td>
                            <td>${agg.matched} / ${agg.expected}</td>
                            <td>${String.format(Locale.US, "%.1f%%", pct)}</td>
                            <td>${String.format(Locale.US, "%.2f g", agg.weight)}</td>
                            <td>${String.format(Locale.US, "$%,.2f", agg.value)}</td>
                        </tr>
                        """.trimIndent()
                    )
                }
                append("</tbody></table>")
            }
        } else ""

        val rowsHtml = buildString {
            details.items.forEachIndexed { index, item ->
                val statusLabel = when (item.status) {
                    ArchiveItemStatus.MATCHED -> if (isArabic) "مطابق" else "Matched"
                    ArchiveItemStatus.MISSING -> if (isArabic) "مفقود" else "Missing"
                    ArchiveItemStatus.UNCATALOGUED -> if (isArabic) "غير مسجل" else "Uncatalogued"
                }
                val statusColor = when (item.status) {
                    ArchiveItemStatus.MATCHED -> "#10B981"
                    ArchiveItemStatus.MISSING -> "#EF4444"
                    ArchiveItemStatus.UNCATALOGUED -> "#F59E0B"
                }
                append(
                    """
                    <tr>
                        <td>${index + 1}</td>
                        <td>${escapeHtml(item.name)}</td>
                        <td>${escapeHtml(item.karat.ifBlank { "-" })}</td>
                        <td style="font-family:monospace;font-size:11px;">${escapeHtml(item.epc)}</td>
                        <td>${String.format(Locale.US, "%.2f", item.weightGrams)} g</td>
                        <td>${String.format(Locale.US, "$%.2f", item.estimatedValue)}</td>
                        <td style="color:$statusColor;font-weight:bold;">$statusLabel</td>
                    </tr>
                    """.trimIndent()
                )
            }
        }

        val html = """
            <!DOCTYPE html>
            <html dir="$dir">
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: sans-serif; color: #0F172A; margin: 18px; direction: $dir; }
                    .header { background: #0B132B; color: #F8FAFC; padding: 16px; border-radius: 8px; border-bottom: 4px solid #D4AF37; }
                    .entity { font-size: 15px; font-weight: bold; color: #FFFFFF; margin-bottom: 4px; }
                    .header h1 { margin: 0 0 6px 0; font-size: 17px; color: #D4AF37; }
                    .meta { font-size: 12px; color: #CBD5E1; line-height: 1.6; }
                    .kpis { display: flex; justify-content: space-between; gap: 8px; margin: 14px 0; }
                    .kpi { flex: 1; border: 1px solid #CBD5E1; border-radius: 6px; padding: 10px; text-align: center; background: #F8FAFC; }
                    .kpi-val { font-size: 15px; font-weight: bold; color: #0B132B; }
                    .kpi-lbl { font-size: 11px; color: #475569; margin-top: 3px; }
                    table { width: 100%; border-collapse: collapse; margin-top: 8px; font-size: 12px; }
                    th { background: #1E293B; color: #D4AF37; padding: 8px; text-align: $align; }
                    td { border-bottom: 1px solid #E2E8F0; padding: 7px 8px; text-align: $align; }
                    tr:nth-child(even) { background: #F8FAFC; }
                    .footer { margin-top: 18px; font-size: 11px; color: #64748B; text-align: center; border-top: 1px solid #E2E8F0; padding-top: 8px; }
                </style>
            </head>
            <body>
                <div class="header">
                    <div class="entity">${escapeHtml(entityName)}</div>
                    <h1>$title</h1>
                    <div class="meta">
                        ${if (isArabic) "رقم التقرير:" else "Report ID:"} <b>#${escapeHtml(details.reportCode)}</b> |
                        ${if (isArabic) "التاريخ:" else "Date:"} <b>${escapeHtml(details.createdAt)}</b><br/>
                        ${if (isArabic) "الفرع:" else "Branch:"} <b>${escapeHtml(resolvedBranch)}</b> |
                        ${if (isArabic) "الموظف:" else "Auditor:"} <b>${escapeHtml(details.auditorName)}</b>
                    </div>
                </div>

                <div class="kpis">
                    <div class="kpi">
                        <div class="kpi-val">${String.format(Locale.US, "%.1f%%", details.matchRatePercent)}</div>
                        <div class="kpi-lbl">${if (isArabic) "نسبة المطابقة" else "Match Rate"}</div>
                    </div>
                    <div class="kpi">
                        <div class="kpi-val" style="color:#10B981;">${details.matchedCount} / ${details.expectedCount}</div>
                        <div class="kpi-lbl">${if (isArabic) "المطابق / المتوقع" else "Matched / Expected"}</div>
                    </div>
                    <div class="kpi">
                        <div class="kpi-val" style="color:#EF4444;">${details.missingCount}</div>
                        <div class="kpi-lbl">${if (isArabic) "المفقود" else "Missing"}</div>
                    </div>
                    <div class="kpi">
                        <div class="kpi-val">${String.format(Locale.US, "%.2f g", details.scannedWeightGrams)}</div>
                        <div class="kpi-lbl">${if (isArabic) "الوزن المقروء" else "Scanned Weight"}</div>
                    </div>
                    <div class="kpi">
                        <div class="kpi-val" style="color:#059669;">${String.format(Locale.US, "$%,.0f", details.estimatedValueUsd)}</div>
                        <div class="kpi-lbl">${if (isArabic) "القيمة التقديرية" else "Est. Value"}</div>
                    </div>
                </div>

                $karatRowsHtml

                <h3 style="margin:10px 0 6px 0;font-size:13px;color:#0B132B;">
                    ${if (isArabic) "التفصيل الكامل للقطع المشمولة بالجرد" else "Detailed Audited Items"}
                </h3>
                <table>
                    <thead>
                        <tr>
                            <th>#</th>
                            <th>${if (isArabic) "القطعة" else "Item"}</th>
                            <th>${if (isArabic) "العيار" else "Karat"}</th>
                            <th>EPC</th>
                            <th>${if (isArabic) "الوزن" else "Weight"}</th>
                            <th>${if (isArabic) "القيمة" else "Value"}</th>
                            <th>${if (isArabic) "الحالة" else "Status"}</th>
                        </tr>
                    </thead>
                    <tbody>
                        $rowsHtml
                    </tbody>
                </table>

                <div class="footer">
                    ${escapeHtml(entityName)} • MIRA ID™ Smart Jewelry ERP • ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}
                </div>
            </body>
            </html>
        """.trimIndent()

        val webView = WebView(context)
        activePrintWebView = webView
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                if (printManager != null) {
                    val jobName = "MIRA_Inventory_${details.reportCode}"
                    val adapter = webView.createPrintDocumentAdapter(jobName)
                    printManager.print(jobName, adapter, PrintAttributes.Builder().build())
                }
                activePrintWebView = null
            }
        }
        webView.loadDataWithBaseURL(null, html, "text/HTML", "UTF-8", null)
    }

    /**
     * تصدير التقرير بصيغة CSV (يدعم الفتح المباشر في Excel باللغة العربية) ومشاركته أو حفظه
     */
    fun exportCsv(
        context: Context,
        details: ArchiveReportDetails,
        isArabic: Boolean
    ) {
        try {
            val (entityName, resolvedBranch) = resolveEntityHeader(context, details.branchName, isArabic)
            val karatStats = aggregateByKarat(details.items)

            val csv = buildString {
                // UTF-8 BOM لضمان ظهور الأحرف العربية بشكل سليم في Microsoft Excel
                append('\uFEFF')
                if (isArabic) {
                    appendLine("الجهة / المنشأة,رقم التقرير,التاريخ,الفرع,الموظف,المطابق,المتوقع,المفقود,غير مسجل,الوزن المقروء (جم),القيمة التقديرية ($)")
                } else {
                    appendLine("Entity / Organization,Report ID,Date,Branch,Auditor,Matched,Expected,Missing,Uncatalogued,Scanned Weight (g),Est. Value ($)")
                }
                appendLine(
                    listOf(
                        csvCell(entityName),
                        csvCell(details.reportCode),
                        csvCell(details.createdAt),
                        csvCell(resolvedBranch),
                        csvCell(details.auditorName),
                        details.matchedCount.toString(),
                        details.expectedCount.toString(),
                        details.missingCount.toString(),
                        details.uncataloguedCount.toString(),
                        String.format(Locale.US, "%.2f", details.scannedWeightGrams),
                        String.format(Locale.US, "%.2f", details.estimatedValueUsd)
                    ).joinToString(",")
                )

                if (karatStats.isNotEmpty()) {
                    appendLine()
                    if (isArabic) {
                        appendLine("العيار,المطابق,المتوقع,الوزن المقروء (جم),القيمة التقديرية ($)")
                    } else {
                        appendLine("Karat,Matched,Expected,Scanned Weight (g),Est. Value ($)")
                    }
                    karatStats.forEach { (karat, agg) ->
                        appendLine(
                            listOf(
                                csvCell(karat),
                                agg.matched.toString(),
                                agg.expected.toString(),
                                String.format(Locale.US, "%.2f", agg.weight),
                                String.format(Locale.US, "%.2f", agg.value)
                            ).joinToString(",")
                        )
                    }
                }

                appendLine()
                if (isArabic) {
                    appendLine("#,اسم القطعة,العيار,رمز EPC,الوزن (جم),القيمة ($),الحالة")
                } else {
                    appendLine("#,Item Name,Karat,EPC,Weight (g),Value ($),Status")
                }

                details.items.forEachIndexed { idx, item ->
                    val statusText = when (item.status) {
                        ArchiveItemStatus.MATCHED -> if (isArabic) "مطابق" else "Matched"
                        ArchiveItemStatus.MISSING -> if (isArabic) "مفقود" else "Missing"
                        ArchiveItemStatus.UNCATALOGUED -> if (isArabic) "غير مسجل" else "Uncatalogued"
                    }
                    appendLine(
                        listOf(
                            (idx + 1).toString(),
                            csvCell(item.name),
                            csvCell(item.karat),
                            csvCell(item.epc),
                            String.format(Locale.US, "%.2f", item.weightGrams),
                            String.format(Locale.US, "%.2f", item.estimatedValue),
                            csvCell(statusText)
                        ).joinToString(",")
                    )
                }
            }

            val fileName = "MIRA_Audit_${details.reportCode}_${System.currentTimeMillis() / 1000}.csv"
            val cacheDir = File(context.cacheDir, "reports").apply { mkdirs() }
            val file = File(cacheDir, fileName)
            file.writeText(csv, Charsets.UTF_8)

            val uriResult = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }

            if (uriResult.isSuccess) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uriResult.getOrNull())
                    putExtra(Intent.EXTRA_SUBJECT, "MIRA Inventory Report #${details.reportCode}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(
                        shareIntent,
                        if (isArabic) "مشاركة أو حفظ تقرير الجرد (CSV)" else "Share or Save Inventory Report (CSV)"
                    )
                )
            } else {
                saveToDownloadsFallback(context, fileName, csv, isArabic)
            }
        } catch (e: Exception) {
            Toast.makeText(
                context,
                if (isArabic) "تعذر تصدير التقرير: ${e.message}" else "Export failed: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun saveToDownloadsFallback(
        context: Context,
        fileName: String,
        content: String,
        isArabic: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                Toast.makeText(
                    context,
                    if (isArabic) "تم حفظ التقرير في التنزيلات: $fileName" else "Saved to Downloads: $fileName",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun csvCell(value: String): String =
        "\"${value.replace("\"", "\"\"")}\""

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
