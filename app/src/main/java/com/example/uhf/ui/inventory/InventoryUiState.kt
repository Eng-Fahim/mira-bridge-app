package com.example.uhf.ui.inventory

import androidx.compose.runtime.Immutable
import com.example.uhf.model.InventoryReport
import com.example.uhf.model.ScannedItemDisplay

enum class InventoryFilterTab {
    ALL,
    MATCHED,
    MISSING,
    UNCATALOGUED,
    WRONG_CATEGORY
}

enum class ItemAuditStatus {
    MATCHED,         // موجود في الكتالوج ومطابق للفئة
    MISSING,         // متوقع في الكتالوج ولم يُقرأ بعد
    UNCATALOGUED,    // شريحة غير مسجلة في كتالوج الفرع
    WRONG_CATEGORY   // مسجل في الفرع لكن بعيار/فئة مختلفة عن الفلتر المختار
}

enum class TriggerMode {
    TOGGLE, // ضغطة للبدء وضغطة للإيقاف
    HOLD    // المسح طالما الزناد مضغوط
}

@Immutable
data class InventoryDisplayRow(
    val epc: String,
    val gtin: String = "",
    val serial: String = "",
    val title: String,
    val karat: String,
    val weight: Double,
    val price: Double,
    val readCount: Int,
    val rssi: Int,
    val lastSeenTime: String,
    val status: ItemAuditStatus
)

@Immutable
data class KaratSummary(
    val karat: String,
    val matchedCount: Int,
    val expectedCount: Int,
    val scannedWeightGrams: Double
)

@Immutable
data class RestoreBannerState(
    val itemCount: Int,
    val expectedCount: Int,
    val category: String,
    val timeAgoText: String,
    val previewText: String
)

@Immutable
data class RadarTargetState(
    val epc: String,
    val title: String,
    val karat: String,
    val weight: Double,
    val currentRssi: Int, // -100 (بعيد جداً) إلى -30 (ملاصق للقارئ)
    val lastPingTimestamp: Long
) {
    val proximityPercent: Int
        get() = ((currentRssi.coerceIn(-95, -30) + 95) * 100 / 65).coerceIn(0, 100)
}

@Immutable
data class CommitReportUi(
    val sessionId: String,
    val accuracy: String,
    val riskLevel: String,
    val recommendation: String,
    val expectedCount: Int,
    val foundCount: Int,
    val missingCount: Int
)

@Immutable
data class InventoryUiState(
    val isArabic: Boolean = true,
    val branchId: Int = 0,
    val branchName: String = "",
    val isReaderConnected: Boolean = false,
    val isScanning: Boolean = false,
    val isCatalogLoading: Boolean = false,
    val catalogError: String? = null,

    // إعدادات الجرد والعتاد
    val selectedCategory: String = "", // فارغ = جرد شامل لكل العيارات
    val availableCategories: List<String> = emptyList(),
    val powerDbm: Int = 27,
    val triggerMode: TriggerMode = TriggerMode.TOGGLE,

    // الإحصائيات والمؤشرات الحية
    val expectedCount: Int = 0,
    val matchedCount: Int = 0,
    val missingCount: Int = 0,
    val uncataloguedCount: Int = 0,
    val wrongCategoryCount: Int = 0,
    val totalReads: Int = 0,
    val readsPerSecond: Int = 0,
    val elapsedSeconds: Long = 0L,
    val scannedWeightGrams: Double = 0.0,
    val expectedWeightGrams: Double = 0.0,
    val scannedValueUsd: Double = 0.0,
    val karatSummaries: List<KaratSummary> = emptyList(),

    // الفلترة والبحث والقائمة المعروضة
    val activeTab: InventoryFilterTab = InventoryFilterTab.ALL,
    val searchQuery: String = "",
    val visibleItems: List<InventoryDisplayRow> = emptyList(),

    // النوافذ والحالات التفاعلية
    val restoreBanner: RestoreBannerState? = null,
    val radarTarget: RadarTargetState? = null,
    val tidVerificationResult: Pair<String, String>? = null, // (EPC, Result Message)
    val isCommitting: Boolean = false,
    val isSyncingOffline: Boolean = false,
    val commitReport: CommitReportUi? = null,
    val showArchiveSheet: Boolean = false,
    val isArchiveLoading: Boolean = false,
    val archiveReports: List<InventoryReport> = emptyList(),
    val selectedArchiveDetails: Pair<String, List<ScannedItemDisplay>>? = null,
    val snackbarMessage: String? = null
    val showScannerSettingsSheet: Boolean = false,
    val showSessionSettingsSheet: Boolean = false,

) {
    val accuracyPercent: Float
        get() = if (expectedCount > 0) {
            (matchedCount.toFloat() / expectedCount.toFloat()).coerceIn(0f, 1f)
        } else 0f
}
