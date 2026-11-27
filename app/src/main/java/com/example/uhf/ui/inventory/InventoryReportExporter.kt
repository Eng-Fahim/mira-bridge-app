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
import kotlin.math.roundToInt

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
     * تجميع ملخص العيارات تلقائياً من قائمة القطع
     */
    private data class KaratAgg(
        var matched: Int = 0,
        var expected: Int = 0,
        var missing: Int = 0,
        var uncatalogued: Int = 0,
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
                    agg.missing += 1
                }
                ArchiveItemStatus.UNCATALOGUED -> {
                    agg.uncatalogued += 1
                    agg.weight += item.weightGrams
                    agg.value += item.estimatedValue
                }
            }
        }
        return map
    }

    /**
     * طباعة التقرير أو حفظه كملف PDF بتصميم تنفيذي متكامل
     */
    fun printOrSavePdf(
        context: Context,
        details: ArchiveReportDetails,
        isArabic: Boolean
    ) {
        val dir = if (isArabic) "rtl" else "ltr"
        val align = if (isArabic) "right" else "left"
        val oppAlign = if (isArabic) "left" else "right"
        val (entityName, resolvedBranch) = resolveEntityHeader(context, details.branchName, isArabic)
        val title = if (isArabic) "تقرير مطابقة الجرد الذكي للمجوهرات" else "Smart Jewelry Inventory Audit Report"
        val subtitle = if (isArabic) "نظام التدقيق والتتبع بتقنية RFID • MIRA ID™" else "RFID Jewelry Audit & Custody System • MIRA ID™"

        val karatStats = aggregateByKarat(details.items)
        val discrepancies = details.items.filter {
            it.status == ArchiveItemStatus.MISSING || it.status == ArchiveItemStatus.UNCATALOGUED
        }

        val hasIssues = details.missingCount > 0 || details.uncataloguedCount > 0
        val auditBadgeBg = if (hasIssues) "rgba(239, 68, 68, 0.18)" else "rgba(16, 185, 129, 0.18)"
        val auditBadgeBorder = if (hasIssues) "#F87171" else "#34D399"
        val auditBadgeColor = if (hasIssues) "#FCA5A5" else "#6EE7B7"
        val auditBadgeText = if (hasIssues) {
            if (isArabic) "يتطلب مراجعة (${details.missingCount} مفقود)" else "Review Required (${details.missingCount} Missing)"
        } else {
            if (isArabic) "مطابقة تامة • 100%" else "Fully Verified • 100%"
        }

        // حساب نسب شريط التوزيع البصري
        val totalBarBase = (details.matchedCount + details.missingCount + details.uncataloguedCount).coerceAtLeast(1)
        val matchedBarPct = ((details.matchedCount * 100.0) / totalBarBase).roundToInt().coerceIn(0, 100)
        val missingBarPct = ((details.missingCount * 100.0) / totalBarBase).roundToInt().coerceIn(0, 100)
        val uncatBarPct = (100 - matchedBarPct - missingBarPct).coerceAtLeast(0)

        // قسم تنبيهات الفروقات والعجز (يظهر في الأعلى لسرعة اتخاذ القرار)
        val discrepancyHtml = if (discrepancies.isNotEmpty()) {
            buildString {
                append(
                    """
                    <div class="alert-box">
                        <div class="alert-header">
                            <span class="alert-icon">!</span>
                            <div>
                                <div class="alert-title">${if (isArabic) "ملخص الفروقات والتنبيهات الأمنية (${discrepancies.size})" else "Audit Discrepancies & Alerts (${discrepancies.size})"}</div>
                                <div class="alert-sub">${if (isArabic) "القطع المفقودة أو غير المسجلة التي تتطلب تدقيقاً فورياً" else "Missing or uncatalogued items requiring immediate verification"}</div>
                            </div>
                        </div>
                        <table class="compact-table">
                            <thead>
                                <tr>
                                    <th style="width:36px;">#</th>
                                    <th>${if (isArabic) "القطعة" else "Item"}</th>
                                    <th>${if (isArabic) "العيار" else "Karat"}</th>
                                    <th>EPC</th>
                                    <th class="num">${if (isArabic) "الوزن" else "Weight"}</th>
                                    <th class="num">${if (isArabic) "القيمة" else "Value"}</th>
                                    <th style="text-align:center;">${if (isArabic) "الحالة" else "Status"}</th>
                                </tr>
                            </thead>
                            <tbody>
                    """.trimIndent()
                )
                discrepancies.forEachIndexed { idx, item ->
                    val isMissing = item.status == ArchiveItemStatus.MISSING
                    val badgeClass = if (isMissing) "badge-missing" else "badge-uncat"
                    val badgeLabel = if (isMissing) {
                        if (isArabic) "مفقود" else "Missing"
                    } else {
                        if (isArabic) "غير مسجل" else "Uncatalogued"
                    }
                    append(
                        """
                        <tr>
                            <td class="muted">${idx + 1}</td>
                            <td class="item-name">${escapeHtml(item.name)}</td>
                            <td><span class="karat-pill">${escapeHtml(item.karat.ifBlank { "-" })}</span></td>
                            <td><span class="epc-code">${escapeHtml(item.epc)}</span></td>
                            <td class="num">${String.format(Locale.US, "%.2f g", item.weightGrams)}</td>
                            <td class="num">${String.format(Locale.US, "$%,.2f", item.estimatedValue)}</td>
                            <td style="text-align:center;"><span class="badge $badgeClass"><span class="dot"></span>$badgeLabel</span></td>
                        </tr>
                        """.trimIndent()
                    )
                }
                append("</tbody></table></div>")
            }
        } else {
            """
            <div class="verified-banner">
                <span class="verified-check">✓</span>
                <div>
                    <strong>${if (isArabic) "شهادة مطابقة تامة للعهدة" else "100% Custody Verification Passed"}</strong>
                    <span>${if (isArabic) "تم التحقق من جميع القطع المسجلة في الفرع دون أي عجز أو قطع مجهولة." else "All registered branch items were verified with zero missing or uncatalogued discrepancies."}</span>
                </div>
            </div>
            """.trimIndent()
        }

        // جدول ملخص العيارات
        val karatRowsHtml = if (karatStats.isNotEmpty()) {
            var totalKaratMatched = 0
            var totalKaratExpected = 0
            var totalKaratUncat = 0
            var totalKaratWeight = 0.0
            var totalKaratValue = 0.0

            buildString {
                append(
                    """
                    <div class="section-header">
                        <span class="section-bar"></span>
                        <h2>${if (isArabic) "التحليل الإحصائي حسب العيار" else "Inventory Breakdown by Karat"}</h2>
                    </div>
                    <div class="table-card">
                        <table>
                            <thead>
                                <tr>
                                    <th>${if (isArabic) "العيار" else "Karat"}</th>
                                    <th class="num">${if (isArabic) "المطابق / المتوقع" else "Matched / Expected"}</th>
                                    <th class="num">${if (isArabic) "غير مسجل" else "Uncatalogued"}</th>
                                    <th style="width:160px;">${if (isArabic) "نسبة المطابقة" else "Match Rate"}</th>
                                    <th class="num">${if (isArabic) "الوزن المقروء" else "Scanned Weight"}</th>
                                    <th class="num">${if (isArabic) "القيمة التقديرية" else "Est. Value"}</th>
                                </tr>
                            </thead>
                            <tbody>
                    """.trimIndent()
                )
                karatStats.forEach { (karat, agg) ->
                    totalKaratMatched += agg.matched
                    totalKaratExpected += agg.expected
                    totalKaratUncat += agg.uncatalogued
                    totalKaratWeight += agg.weight
                    totalKaratValue += agg.value

                    val pct = if (agg.expected > 0) (agg.matched * 100.0) / agg.expected else 100.0
                    val barWidth = pct.coerceIn(0.0, 100.0).roundToInt()
                    val barColor = when {
                        pct >= 99.9 -> "#10B981"
                        pct >= 90.0 -> "#D4AF37"
                        else -> "#EF4444"
                    }
                    append(
                        """
                        <tr>
                            <td><span class="karat-pill">${escapeHtml(karat)}</span></td>
                            <td class="num"><strong>${agg.matched}</strong> <span class="muted">/ ${agg.expected}</span></td>
                            <td class="num">${if (agg.uncatalogued > 0) "<span style=\"color:#B45309;font-weight:700;\">+${agg.uncatalogued}</span>" else "<span class=\"muted\">0</span>"}</td>
                            <td>
                                <div class="mini-prog-wrap">
                                    <div class="mini-prog-bg"><div class="mini-prog-fill" style="width:${barWidth}%;background:$barColor;"></div></div>
                                    <span class="mini-prog-txt">${String.format(Locale.US, "%.1f%%", pct)}</span>
                                </div>
                            </td>
                            <td class="num"><strong>${String.format(Locale.US, "%.2f", agg.weight)}</strong> <span class="unit">g</span></td>
                            <td class="num" style="color:#065F46;font-weight:700;">${String.format(Locale.US, "$%,.2f", agg.value)}</td>
                        </tr>
                        """.trimIndent()
                    )
                }
                val totalPct = if (totalKaratExpected > 0) (totalKaratMatched * 100.0) / totalKaratExpected else 100.0
                append(
                    """
                            </tbody>
                            <tfoot>
                                <tr class="total-row">
                                    <td>${if (isArabic) "الإجمالي العام" else "Grand Total"}</td>
                                    <td class="num">$totalKaratMatched / $totalKaratExpected</td>
                                    <td class="num">$totalKaratUncat</td>
                                    <td class="num">${String.format(Locale.US, "%.1f%%", totalPct)}</td>
                                    <td class="num">${String.format(Locale.US, "%.2f g", totalKaratWeight)}</td>
                                    <td class="num">${String.format(Locale.US, "$%,.2f", totalKaratValue)}</td>
                                </tr>
                            </tfoot>
                        </table>
                    </div>
                    """.trimIndent()
                )
            }
        } else ""

        // السجل الكامل للقطع
        var totalItemsWeight = 0.0
        var totalItemsValue = 0.0
        val rowsHtml = buildString {
            details.items.forEachIndexed { index, item ->
                totalItemsWeight += item.weightGrams
                totalItemsValue += item.estimatedValue

                val (badgeLabel, badgeClass) = when (item.status) {
                    ArchiveItemStatus.MATCHED -> (if (isArabic) "مطابق" else "Matched") to "badge-matched"
                    ArchiveItemStatus.MISSING -> (if (isArabic) "مفقود" else "Missing") to "badge-missing"
                    ArchiveItemStatus.UNCATALOGUED -> (if (isArabic) "غير مسجل" else "Uncatalogued") to "badge-uncat"
                }
                val rowClass = when (item.status) {
                    ArchiveItemStatus.MISSING -> "row-missing"
                    ArchiveItemStatus.UNCATALOGUED -> "row-uncat"
                    else -> ""
                }
                append(
                    """
                    <tr class="$rowClass">
                        <td class="muted">${index + 1}</td>
                        <td class="item-name">${escapeHtml(item.name)}</td>
                        <td><span class="karat-pill">${escapeHtml(item.karat.ifBlank { "-" })}</span></td>
                        <td><span class="epc-code">${escapeHtml(item.epc)}</span></td>
                        <td class="num">${String.format(Locale.US, "%.2f", item.weightGrams)} <span class="unit">g</span></td>
                        <td class="num">${String.format(Locale.US, "$%,.2f", item.estimatedValue)}</td>
                        <td style="text-align:center;"><span class="badge $badgeClass"><span class="dot"></span>$badgeLabel</span></td>
                    </tr>
                    """.trimIndent()
                )
            }
        }

        val generatedAt = SimpleDateFormat("yyyy-MM-dd • HH:mm", Locale.US).format(Date())

        val html = """
            <!DOCTYPE html>
            <html dir="$dir" lang="${if (isArabic) "ar" else "en"}">
            <head>
                <meta charset="UTF-8">
                <style>
                    @page {
                        size: A4 portrait;
                        margin: 11mm 12mm 13mm 12mm;
                    }
                    * {
                        box-sizing: border-box;
                        -webkit-print-color-adjust: exact !important;
                        print-color-adjust: exact !important;
                    }
                    body {
                        font-family: 'Tajawal', 'Cairo', 'IBM Plex Sans Arabic', 'Segoe UI', Tahoma, Arial, sans-serif;
                        color: #0F172A;
                        background: #FFFFFF;
                        margin: 0;
                        padding: 0;
                        direction: $dir;
                        font-size: 11.5px;
                        line-height: 1.45;
                    }
                    .num {
                        text-align: $oppAlign;
                        font-variant-numeric: tabular-nums;
                        white-space: nowrap;
                    }
                    .muted { color: #64748B; }
                    .unit { font-size: 10px; color: #64748B; }

                    /* Executive Hero Header */
                    .hero {
                        background: linear-gradient(135deg, #090F24 0%, #162347 100%);
                        color: #F8FAFC;
                        border-radius: 10px;
                        overflow: hidden;
                        border: 1px solid #1E293B;
                        margin-bottom: 12px;
                        break-inside: avoid;
                    }
                    .gold-strip {
                        height: 4px;
                        background: linear-gradient(90deg, #B4831D 0%, #F3D06B 50%, #B4831D 100%);
                    }
                    .hero-top {
                        padding: 14px 18px 12px 18px;
                        display: flex;
                        justify-content: space-between;
                        align-items: flex-start;
                        gap: 12px;
                    }
                    .entity-name {
                        font-size: 15px;
                        font-weight: 800;
                        color: #FFFFFF;
                        letter-spacing: 0.2px;
                    }
                    .system-sub {
                        font-size: 10px;
                        color: #94A3B8;
                        margin-top: 2px;
                        letter-spacing: 0.4px;
                    }
                    .report-title {
                        margin: 8px 0 0 0;
                        font-size: 18px;
                        font-weight: 800;
                        color: #F3D06B;
                    }
                    .hero-badges {
                        display: flex;
                        flex-direction: column;
                        align-items: ${if (isArabic) "flex-start" else "flex-end"};
                        gap: 6px;
                        flex-shrink: 0;
                    }
                    .report-id-pill {
                        background: rgba(255, 255, 255, 0.08);
                        border: 1px solid rgba(212, 175, 55, 0.45);
                        color: #F8FAFC;
                        padding: 4px 10px;
                        border-radius: 6px;
                        font-size: 11.5px;
                        font-weight: 700;
                        font-family: monospace;
                    }
                    .status-stamp {
                        background: $auditBadgeBg;
                        border: 1px solid $auditBadgeBorder;
                        color: $auditBadgeColor;
                        padding: 4px 10px;
                        border-radius: 999px;
                        font-size: 11px;
                        font-weight: 700;
                    }
                    .meta-grid {
                        display: grid;
                        grid-template-columns: repeat(4, 1fr);
                        background: rgba(7, 11, 26, 0.55);
                        border-top: 1px solid rgba(255, 255, 255, 0.09);
                        padding: 9px 18px;
                        gap: 10px;
                    }
                    .meta-item-lbl {
                        font-size: 9.5px;
                        color: #94A3B8;
                        text-transform: uppercase;
                    }
                    .meta-item-val {
                        font-size: 11.5px;
                        font-weight: 700;
                        color: #F1F5F9;
                        margin-top: 1px;
                    }

                    /* KPI Bento Grid */
                    .kpi-grid {
                        display: grid;
                        grid-template-columns: repeat(5, 1fr);
                        gap: 8px;
                        margin-bottom: 10px;
                        break-inside: avoid;
                    }
                    .kpi-card {
                        background: #F8FAFC;
                        border: 1px solid #E2E8F0;
                        border-top: 3px solid #0B132B;
                        border-radius: 8px;
                        padding: 9px 10px;
                    }
                    .kpi-card.gold { border-top-color: #D4AF37; background: #FFFDF7; }
                    .kpi-card.emerald { border-top-color: #10B981; }
                    .kpi-card.danger { border-top-color: #EF4444; background: #FEF9F9; }
                    .kpi-lbl {
                        font-size: 10px;
                        color: #475569;
                        font-weight: 600;
                    }
                    .kpi-val {
                        font-size: 15px;
                        font-weight: 800;
                        color: #0B132B;
                        margin-top: 3px;
                        font-variant-numeric: tabular-nums;
                    }
                    .kpi-sub {
                        font-size: 9.5px;
                        color: #64748B;
                        margin-top: 2px;
                    }

                    /* Visual Distribution Bar */
                    .dist-card {
                        background: #FFFFFF;
                        border: 1px solid #E2E8F0;
                        border-radius: 8px;
                        padding: 8px 12px;
                        margin-bottom: 12px;
                        break-inside: avoid;
                    }
                    .dist-bar {
                        height: 8px;
                        border-radius: 999px;
                        background: #E2E8F0;
                        overflow: hidden;
                        display: flex;
                        margin-bottom: 6px;
                    }
                    .dist-seg-matched { background: #10B981; height: 100%; }
                    .dist-seg-missing { background: #EF4444; height: 100%; }
                    .dist-seg-uncat { background: #F59E0B; height: 100%; }
                    .dist-legend {
                        display: flex;
                        justify-content: space-between;
                        font-size: 10.5px;
                        color: #334155;
                    }
                    .legend-dot {
                        display: inline-block;
                        width: 8px;
                        height: 8px;
                        border-radius: 50%;
                        margin: 0 4px;
                        vertical-align: middle;
                    }

                    /* Discrepancy Alert & Verified Banner */
                    .alert-box {
                        border: 1px solid #FECACA;
                        background: #FFFBFB;
                        border-radius: 8px;
                        padding: 10px 12px;
                        margin-bottom: 14px;
                        break-inside: avoid;
                    }
                    .alert-header {
                        display: flex;
                        align-items: center;
                        gap: 9px;
                        margin-bottom: 8px;
                    }
                    .alert-icon {
                        width: 22px;
                        height: 22px;
                        border-radius: 50%;
                        background: #EF4444;
                        color: #FFFFFF;
                        font-weight: 800;
                        font-size: 13px;
                        display: inline-flex;
                        align-items: center;
                        justify-content: center;
                        flex-shrink: 0;
                    }
                    .alert-title { font-size: 12.5px; font-weight: 800; color: #991B1B; }
                    .alert-sub { font-size: 10.5px; color: #B91C1C; }

                    .verified-banner {
                        display: flex;
                        align-items: center;
                        gap: 10px;
                        background: #ECFDF5;
                        border: 1px solid #A7F3D0;
                        border-radius: 8px;
                        padding: 9px 14px;
                        margin-bottom: 14px;
                        color: #065F46;
                        break-inside: avoid;
                    }
                    .verified-check {
                        width: 22px;
                        height: 22px;
                        border-radius: 50%;
                        background: #10B981;
                        color: #FFFFFF;
                        font-weight: 800;
                        display: inline-flex;
                        align-items: center;
                        justify-content: center;
                        flex-shrink: 0;
                    }
                    .verified-banner strong { display: block; font-size: 12px; }
                    .verified-banner span { font-size: 10.5px; color: #047857; }

                    /* Section Titles */
                    .section-header {
                        display: flex;
                        align-items: center;
                        gap: 8px;
                        margin: 14px 0 7px 0;
                        break-after: avoid;
                    }
                    .section-bar {
                        width: 4px;
                        height: 15px;
                        background: #D4AF37;
                        border-radius: 2px;
                    }
                    .section-header h2 {
                        margin: 0;
                        font-size: 13px;
                        font-weight: 800;
                        color: #0B132B;
                    }

                    /* Tables */
                    .table-card {
                        border: 1px solid #CBD5E1;
                        border-radius: 8px;
                        overflow: hidden;
                        margin-bottom: 12px;
                    }
                    table {
                        width: 100%;
                        border-collapse: collapse;
                        font-size: 11px;
                    }
                    thead { display: table-header-group; }
                    tfoot { display: table-footer-group; }
                    tr { break-inside: avoid; page-break-inside: avoid; }
                    th {
                        background: #0F172A;
                        color: #F3D06B;
                        font-weight: 700;
                        padding: 7px 9px;
                        text-align: $align;
                        font-size: 10.5px;
                        border-bottom: 2px solid #D4AF37;
                    }
                    td {
                        padding: 6.5px 9px;
                        border-bottom: 1px solid #E2E8F0;
                        text-align: $align;
                        vertical-align: middle;
                    }
                    tbody tr:nth-child(even) { background: #F8FAFC; }
                    tbody tr.row-missing { background: #FEF2F2; }
                    tbody tr.row-uncat { background: #FFFBEB; }
                    .total-row td {
                        background: #F1F5F9;
                        font-weight: 800;
                        color: #0B132B;
                        border-top: 2px solid #94A3B8;
                        border-bottom: none;
                        padding: 8px 9px;
                    }
                    .compact-table th {
                        background: #7F1D1D;
                        color: #FEF2F2;
                        border-bottom: none;
                        padding: 5px 8px;
                    }
                    .compact-table td { padding: 5px 8px; }

                    /* Pills & Badges */
                    .item-name { font-weight: 700; color: #0F172A; }
                    .karat-pill {
                        display: inline-block;
                        background: #FEF3C7;
                        color: #92400E;
                        border: 1px solid #FDE68A;
                        font-weight: 800;
                        font-size: 10px;
                        padding: 1px 7px;
                        border-radius: 5px;
                    }
                    .epc-code {
                        font-family: 'JetBrains Mono', 'Consolas', monospace;
                        font-size: 10px;
                        background: #F1F5F9;
                        color: #334155;
                        border: 1px solid #E2E8F0;
                        padding: 2px 6px;
                        border-radius: 4px;
                        direction: ltr;
                        display: inline-block;
                    }
                    .badge {
                        display: inline-flex;
                        align-items: center;
                        gap: 4px;
                        padding: 2px 8px;
                        border-radius: 999px;
                        font-size: 10px;
                        font-weight: 700;
                        white-space: nowrap;
                    }
                    .badge .dot {
                        width: 6px;
                        height: 6px;
                        border-radius: 50%;
                    }
                    .badge-matched { background: #ECFDF5; color: #065F46; border: 1px solid #A7F3D0; }
                    .badge-matched .dot { background: #10B981; }
                    .badge-missing { background: #FEF2F2; color: #991B1B; border: 1px solid #FECACA; }
                    .badge-missing .dot { background: #EF4444; }
                    .badge-uncat { background: #FFFBEB; color: #92400E; border: 1px solid #FDE68A; }
                    .badge-uncat .dot { background: #F59E0B; }

                    /* Mini Progress Bar in Karat Table */
                    .mini-prog-wrap {
                        display: flex;
                        align-items: center;
                        gap: 6px;
                    }
                    .mini-prog-bg {
                        flex: 1;
                        height: 6px;
                        background: #E2E8F0;
                        border-radius: 999px;
                        overflow: hidden;
                    }
                    .mini-prog-fill { height: 100%; border-radius: 999px; }
                    .mini-prog-txt {
                        font-size: 10px;
                        font-weight: 700;
                        color: #334155;
                        min-width: 38px;
                        text-align: $oppAlign;
                        font-variant-numeric: tabular-nums;
                    }

                    /* Signatures Block */
                    .signatures {
                        display: grid;
                        grid-template-columns: repeat(3, 1fr);
                        gap: 12px;
                        margin-top: 18px;
                        break-inside: avoid;
                    }
                    .sig-box {
                        border: 1px dashed #94A3B8;
                        border-radius: 8px;
                        padding: 10px 12px;
                        background: #F8FAFC;
                        text-align: center;
                    }
                    .sig-title {
                        font-size: 10.5px;
                        font-weight: 700;
                        color: #1E293B;
                    }
                    .sig-name {
                        font-size: 10px;
                        color: #64748B;
                        margin-top: 2px;
                    }
                    .sig-line {
                        margin-top: 28px;
                        border-top: 1px solid #CBD5E1;
                        padding-top: 4px;
                        font-size: 9.5px;
                        color: #94A3B8;
                    }

                    /* Footer */
                    .footer {
                        margin-top: 14px;
                        padding-top: 8px;
                        border-top: 1px solid #E2E8F0;
                        display: flex;
                        justify-content: space-between;
                        font-size: 9.5px;
                        color: #64748B;
                        break-inside: avoid;
                    }
                </style>
            </head>
            <body>

                <!-- 1. Executive Header -->
                <div class="hero">
                    <div class="gold-strip"></div>
                    <div class="hero-top">
                        <div>
                            <div class="entity-name">${escapeHtml(entityName)}</div>
                            <div class="system-sub">$subtitle</div>
                            <h1 class="report-title">$title</h1>
                        </div>
                        <div class="hero-badges">
                            <span class="report-id-pill">#${escapeHtml(details.reportCode)}</span>
                            <span class="status-stamp">$auditBadgeText</span>
                        </div>
                    </div>
                    <div class="meta-grid">
                        <div>
                            <div class="meta-item-lbl">${if (isArabic) "الفرع / الموقع" else "Branch / Location"}</div>
                            <div class="meta-item-val">${escapeHtml(resolvedBranch)}</div>
                        </div>
                        <div>
                            <div class="meta-item-lbl">${if (isArabic) "تاريخ الجرد" else "Audit Date"}</div>
                            <div class="meta-item-val">${escapeHtml(details.createdAt)}</div>
                        </div>
                        <div>
                            <div class="meta-item-lbl">${if (isArabic) "القائم بالجرد" else "Auditor"}</div>
                            <div class="meta-item-val">${escapeHtml(details.auditorName)}</div>
                        </div>
                        <div>
                            <div class="meta-item-lbl">${if (isArabic) "إجمالي السجلات" else "Total Records"}</div>
                            <div class="meta-item-val">${details.items.size} ${if (isArabic) "قطعة" else "Items"}</div>
                        </div>
                    </div>
                </div>

                <!-- 2. KPI Bento Cards -->
                <div class="kpi-grid">
                    <div class="kpi-card gold">
                        <div class="kpi-lbl">${if (isArabic) "نسبة المطابقة" else "Match Rate"}</div>
                        <div class="kpi-val">${String.format(Locale.US, "%.1f%%", details.matchRatePercent)}</div>
                        <div class="kpi-sub">${if (isArabic) "دقة الجرد الفعلي" else "Audit Accuracy"}</div>
                    </div>
                    <div class="kpi-card emerald">
                        <div class="kpi-lbl">${if (isArabic) "المطابق / المتوقع" else "Matched / Expected"}</div>
                        <div class="kpi-val" style="color:#065F46;">${details.matchedCount} / ${details.expectedCount}</div>
                        <div class="kpi-sub">${if (isArabic) "قطعة مؤكدة" else "Verified Pieces"}</div>
                    </div>
                    <div class="kpi-card ${if (details.missingCount > 0) "danger" else ""}">
                        <div class="kpi-lbl">${if (isArabic) "القطع المفقودة" else "Missing Items"}</div>
                        <div class="kpi-val" style="color:${if (details.missingCount > 0) "#DC2626" else "#0F172A"};">${details.missingCount}</div>
                        <div class="kpi-sub">${if (details.uncataloguedCount > 0) (if (isArabic) "+${details.uncataloguedCount} غير مسجل" else "+${details.uncataloguedCount} uncatalogued") else (if (isArabic) "العجز المسجل" else "Recorded Deficit")}</div>
                    </div>
                    <div class="kpi-card">
                        <div class="kpi-lbl">${if (isArabic) "الوزن المقروء" else "Scanned Weight"}</div>
                        <div class="kpi-val">${String.format(Locale.US, "%.2f", details.scannedWeightGrams)} <span class="unit">g</span></div>
                        <div class="kpi-sub">${if (isArabic) "إجمالي الوزن الفعلي" else "Total Physical Weight"}</div>
                    </div>
                    <div class="kpi-card gold">
                        <div class="kpi-lbl">${if (isArabic) "القيمة التقديرية" else "Estimated Value"}</div>
                        <div class="kpi-val" style="color:#92400E;">${String.format(Locale.US, "$%,.0f", details.estimatedValueUsd)}</div>
                        <div class="kpi-sub">${if (isArabic) "تقييم المخزون المقروء" else "Scanned Valuation"}</div>
                    </div>
                </div>

                <!-- 3. Visual Distribution Bar -->
                <div class="dist-card">
                    <div class="dist-bar">
                        <div class="dist-seg-matched" style="width:${matchedBarPct}%;"></div>
                        <div class="dist-seg-missing" style="width:${missingBarPct}%;"></div>
                        <div class="dist-seg-uncat" style="width:${uncatBarPct}%;"></div>
                    </div>
                    <div class="dist-legend">
                        <span><span class="legend-dot" style="background:#10B981;"></span>${if (isArabic) "مطابق:" else "Matched:"} <strong>${details.matchedCount}</strong> ($matchedBarPct%)</span>
                        <span><span class="legend-dot" style="background:#EF4444;"></span>${if (isArabic) "مفقود:" else "Missing:"} <strong>${details.missingCount}</strong> ($missingBarPct%)</span>
                        <span><span class="legend-dot" style="background:#F59E0B;"></span>${if (isArabic) "غير مسجل:" else "Uncatalogued:"} <strong>${details.uncataloguedCount}</strong> ($uncatBarPct%)</span>
                    </div>
                </div>

                <!-- 4. Discrepancies Spotlight / Verification Certificate -->
                $discrepancyHtml

                <!-- 5. Karat Breakdown Matrix -->
                $karatRowsHtml

                <!-- 6. Full Audited Items Log -->
                <div class="section-header">
                    <span class="section-bar"></span>
                    <h2>${if (isArabic) "السجل التفصيلي للقطع المشمولة بالجرد" else "Complete Audited Items Log"}</h2>
                </div>
                <div class="table-card">
                    <table>
                        <thead>
                            <tr>
                                <th style="width:36px;">#</th>
                                <th>${if (isArabic) "وصف القطعة" else "Item Description"}</th>
                                <th>${if (isArabic) "العيار" else "Karat"}</th>
                                <th>EPC</th>
                                <th class="num">${if (isArabic) "الوزن" else "Weight"}</th>
                                <th class="num">${if (isArabic) "القيمة" else "Value"}</th>
                                <th style="text-align:center;">${if (isArabic) "حالة المطابقة" else "Status"}</th>
                            </tr>
                        </thead>
                        <tbody>
                            $rowsHtml
                        </tbody>
                        <tfoot>
                            <tr class="total-row">
                                <td colspan="4">${if (isArabic) "إجمالي السجل (${details.items.size} قطعة)" else "Total Log (${details.items.size} Items)"}</td>
                                <td class="num">${String.format(Locale.US, "%.2f g", totalItemsWeight)}</td>
                                <td class="num">${String.format(Locale.US, "$%,.2f", totalItemsValue)}</td>
                                <td></td>
                            </tr>
                        </tfoot>
                    </table>
                </div>

                <!-- 7. Official Signatures -->
                <div class="signatures">
                    <div class="sig-box">
                        <div class="sig-title">${if (isArabic) "القائم بالجرد / أمين العهدة" else "Inventory Auditor"}</div>
                        <div class="sig-name">${escapeHtml(details.auditorName)}</div>
                        <div class="sig-line">${if (isArabic) "التوقيع" else "Signature"}</div>
                    </div>
                    <div class="sig-box">
                        <div class="sig-title">${if (isArabic) "مدير الفرع / المشرف" else "Branch Manager"}</div>
                        <div class="sig-name">${escapeHtml(resolvedBranch)}</div>
                        <div class="sig-line">${if (isArabic) "التوقيع" else "Signature"}</div>
                    </div>
                    <div class="sig-box">
                        <div class="sig-title">${if (isArabic) "المراجعة والاعتماد" else "Audit Approval & Stamp"}</div>
                        <div class="sig-name">${escapeHtml(entityName)}</div>
                        <div class="sig-line">${if (isArabic) "الختم الرسمي" else "Official Stamp"}</div>
                    </div>
                </div>

                <div class="footer">
                    <span>${escapeHtml(entityName)} • MIRA ID™ Smart Jewelry ERP</span>
                    <span>${if (isArabic) "تاريخ الطباعة:" else "Printed:"} $generatedAt • #${escapeHtml(details.reportCode)}</span>
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
                    val attributes = PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .build()
                    printManager.print(jobName, adapter, attributes)
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
                    appendLine("الجهة / المنشأة,رقم التقرير,التاريخ,الفرع,الموظف,نسبة المطابقة %,المطابق,المتوقع,المفقود,غير مسجل,الوزن المقروء (جم),القيمة التقديرية ($)")
                } else {
                    appendLine("Entity / Organization,Report ID,Date,Branch,Auditor,Match Rate %,Matched,Expected,Missing,Uncatalogued,Scanned Weight (g),Est. Value ($)")
                }
                appendLine(
                    listOf(
                        csvCell(entityName),
                        csvCell(details.reportCode),
                        csvCell(details.createdAt),
                        csvCell(resolvedBranch),
                        csvCell(details.auditorName),
                        String.format(Locale.US, "%.1f", details.matchRatePercent),
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
                        appendLine("العيار,المطابق,المتوقع,غير مسجل,الوزن المقروء (جم),القيمة التقديرية ($)")
                    } else {
                        appendLine("Karat,Matched,Expected,Uncatalogued,Scanned Weight (g),Est. Value ($)")
                    }
                    karatStats.forEach { (karat, agg) ->
                        appendLine(
                            listOf(
                                csvCell(karat),
                                agg.matched.toString(),
                                agg.expected.toString(),
                                agg.uncatalogued.toString(),
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
