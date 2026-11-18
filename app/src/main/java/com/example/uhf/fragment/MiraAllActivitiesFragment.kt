package com.example.uhf.fragment

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import androidx.fragment.app.Fragment
import com.example.uhf.R
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.api.MiraApiClient
import com.example.uhf.dialog.ActivityDetailsBottomSheet
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MiraAllActivitiesFragment : Fragment() {

    companion object {
        private const val TAG = "MiraAllActivities"
        private const val PAGE_SIZE = 20
        private const val MAX_PAGE_SIZE = 200
    }

    private data class ActivityCategorySpec(
        val key: String,
        val labelAr: String,
        val labelEn: String,
        val icon: ImageVector,
        val color: Color
    )

    private data class FormattedActivityDisplay(
        val title: String,
        val details: String,
        val user: String,
        val timestamp: String,
        val amountText: String?,
        val badgeText: String?,
        val status: String,
        val visualSpec: ActivityCategorySpec
    )

    private val allActivities = ArrayList<JSONObject>()
    private val filteredActivitiesState = mutableStateListOf<JSONObject>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val isRequestInFlight = AtomicBoolean(false)

    private var isArabic = true
    private var currentPage = 1
    @Volatile private var isLoadingMore = false
    @Volatile private var hasMorePages = true
    @Volatile private var isDestroyed = false

    private var apiClient: MiraApiClient? = null
    private var branchesManager: MiraBranchesManager? = null
    private var appContext: Context? = null

    private var currentFilterState by mutableStateOf("all")
    private var currentSearchState by mutableStateOf("")
    private var isInitialLoadingState by mutableStateOf(false)
    private var isRefreshingState by mutableStateOf(false)
    private var isLoadingMoreState by mutableStateOf(false)
    private var isExportingState by mutableStateOf(false)
    private var errorMessageState by mutableStateOf<String?>(null)
    private var totalCountState by mutableIntStateOf(0)
    private var totalOnServerState by mutableIntStateOf(-1)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MiraJewelryTheme {
                    AllActivitiesScreen()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        isDestroyed = false
        appContext = requireContext().applicationContext
        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar") ||
            Locale.getDefault().language == "ar"
        view.layoutDirection = if (isArabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

        apiClient = MiraApiClient.getInstance(requireContext())
        branchesManager = MiraBranchesManager.getInstance(requireContext())

        loadActivities(reset = true)
    }

    override fun onResume() {
        super.onResume()
        (activity as? UHFMainActivity)?.setBottomNavVisible(false)
        activity?.findViewById<View>(R.id.appBarLayout)?.visibility = View.GONE
    }

    override fun onPause() {
        super.onPause()
        (activity as? UHFMainActivity)?.setBottomNavVisible(true)
        activity?.findViewById<View>(R.id.appBarLayout)?.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        isDestroyed = true

        if (!executor.isShutdown) {
            executor.shutdownNow()
        }
        mainHandler.removeCallbacksAndMessages(null)

        super.onDestroyView()
    }

    private fun loadActivities(reset: Boolean) {
        val client = apiClient
        if (client == null) {
            showError(if (isArabic) "العميل غير مهيأ" else "Client not initialized")
            return
        }

        if (!isRequestInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "loadActivities skipped — request already in flight")
            isRefreshingState = false
            return
        }

        if (reset) {
            currentPage = 1
            hasMorePages = true
            isLoadingMore = false
            isLoadingMoreState = false
            totalOnServerState = -1
            errorMessageState = null
            if (allActivities.isEmpty()) {
                isInitialLoadingState = true
            } else {
                isRefreshingState = true
            }
        } else {
            isLoadingMoreState = true
        }

        val pageToLoad = currentPage
        val requestLimit = minOf(PAGE_SIZE * pageToLoad, MAX_PAGE_SIZE)

        executor.execute {
            try {
                val branchId = branchesManager?.activeBranchId ?: 0
                val response = client.getUnifiedActivity(branchId, requestLimit)

                postToMain {
                    handleResponse(response, reset, pageToLoad, requestLimit)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "loadActivities exception", e)
                postToMain {
                    finishLoading()
                    showError(e.message)
                }
            } finally {
                isRequestInFlight.set(false)
            }
        }
    }

    private fun handleResponse(
        response: MiraApiClient.ApiResponse?,
        reset: Boolean,
        pageToLoad: Int,
        requestLimit: Int
    ) {
        if (isDestroyed) return
        finishLoading()

        if (response == null) {
            showError(if (isArabic) "فشل الاتصال بالخادم" else "Server connection failed")
            return
        }

        if (!response.isSuccess) {
            val err = if (!response.error.isNullOrEmpty()) response.error else "HTTP ${response.code}"
            showError(err)
            return
        }

        val dataObj = response.data
        if (dataObj == null) {
            showError(if (isArabic) "استجابة غير صالحة من الخادم" else "Invalid server response")
            return
        }

        val activities = extractActivitiesArray(dataObj)
        if (activities == null) {
            showError(if (isArabic) "بنية الاستجابة غير متوقعة" else "Unexpected response structure")
            return
        }

        errorMessageState = null

        if (reset) {
            allActivities.clear()
        }

        for (i in 0 until activities.length()) {
            try {
                activities.optJSONObject(i)?.let { allActivities.add(it) }
            } catch (_: Throwable) {
                Log.w(TAG, "skip malformed activity at index $i")
            }
        }

        totalCountState = allActivities.size

        val serverTotal = dataObj.optInt("total", -1)
        if (serverTotal >= 0) {
            totalOnServerState = serverTotal
            hasMorePages = allActivities.size < serverTotal
        } else {
            hasMorePages = activities.length() >= minOf(PAGE_SIZE * pageToLoad, MAX_PAGE_SIZE)
        }

        applyFilters()
    }

    private fun extractActivitiesArray(top: JSONObject): JSONArray? {
        top.optJSONArray("activities")?.let { return it }
        top.optJSONArray("data")?.let { return it }
        top.optJSONArray("items")?.let { return it }
        top.optJSONArray("logs")?.let { return it }
        top.optJSONArray("result")?.let { return it }

        val dataObj = top.optJSONObject("data")
        if (dataObj != null) {
            dataObj.optJSONArray("activities")?.let { return it }
            dataObj.optJSONArray("items")?.let { return it }
            dataObj.optJSONArray("logs")?.let { return it }
        }
        return null
    }

    private fun loadMore() {
        if (isLoadingMore || !hasMorePages) return
        if (isRequestInFlight.get()) return

        isLoadingMore = true
        currentPage++
        loadActivities(reset = false)
    }

    private fun finishLoading() {
        isLoadingMore = false
        isInitialLoadingState = false
        isRefreshingState = false
        isLoadingMoreState = false
    }

    private fun selectFilter(filter: String) {
        currentFilterState = filter
        applyFilters()
    }

    private fun onSearchChanged(query: String) {
        currentSearchState = query
        applyFilters()
    }

    private fun applyFilters() {
        val searchLower = currentSearchState.trim().lowercase(Locale.ROOT)
        val filterLower = currentFilterState.lowercase(Locale.ROOT)

        val result = ArrayList<JSONObject>()
        for (act in allActivities) {
            if (!matchesType(act, filterLower)) continue
            if (!matchesSearch(act, searchLower)) continue
            result.add(act)
        }

        filteredActivitiesState.clear()
        filteredActivitiesState.addAll(result)
    }

    private fun matchesType(act: JSONObject, filterLower: String): Boolean {
        if (filterLower == "all") return true
        val type = (
            act.optString("type", "") + " " +
                act.optString("action", "") + " " +
                act.optString("event", "")
            ).lowercase(Locale.ROOT)
        return type.contains(filterLower)
    }

    private fun matchesSearch(act: JSONObject, searchLower: String): Boolean {
        if (searchLower.isEmpty()) return true
        val display = formatActivity(act, isArabic)
        val combined = listOf(
            display.title,
            display.details,
            display.user,
            display.status,
            display.badgeText ?: "",
            act.toString()
        ).joinToString(" ").lowercase(Locale.ROOT)
        return combined.contains(searchLower)
    }

    private fun showError(message: String?) {
        if (isDestroyed) return
        val msg = if (message.isNullOrBlank()) {
            if (isArabic) "تعذّر تحميل العمليات" else "Failed to load activities"
        } else {
            message.trim()
        }

        allActivities.clear()
        filteredActivitiesState.clear()
        totalCountState = 0
        errorMessageState = msg

        if (isAdded && context != null) {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun showActivityDetails(activity: JSONObject) {
        try {
            if (!isAdded) return
            val sheet = ActivityDetailsBottomSheet.newInstance(activity, isArabic)
            sheet.show(parentFragmentManager, "ActivityDetails")
        } catch (e: Throwable) {
            Log.e(TAG, "details error", e)
        }
    }

    private fun exportActivities() {
        val snapshot = ArrayList(filteredActivitiesState)
        if (snapshot.isEmpty()) {
            if (isAdded && context != null) {
                Toast.makeText(
                    requireContext(),
                    if (isArabic) "⚠️ لا توجد عمليات للتصدير" else "⚠️ No activities to export",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        val ctx = appContext ?: return
        isExportingState = true

        executor.execute {
            try {
                val dir = File(ctx.getExternalFilesDir(null), "exports")
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "could not create exports dir")
                }

                val fileName = "activities_" +
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
                    ".csv"
                val file = File(dir, fileName)

                FileWriter(file).use { writer ->
                    writer.append("Timestamp,Type,Title,Details,User,Amount,Status\n")
                    for (act in snapshot) {
                        val formatted = formatActivity(act, isArabic)
                        writer.append(escapeCsv(formatted.timestamp)).append(",")
                        writer.append(escapeCsv(act.optString("type", act.optString("action", "")))).append(",")
                        writer.append(escapeCsv(formatted.title)).append(",")
                        writer.append(escapeCsv(formatted.details)).append(",")
                        writer.append(escapeCsv(formatted.user)).append(",")
                        writer.append(escapeCsv(formatted.amountText ?: "")).append(",")
                        writer.append(escapeCsv(formatted.status)).append("\n")
                    }
                    writer.flush()
                }

                postToMain {
                    isExportingState = false
                    if (isAdded && context != null) {
                        Toast.makeText(
                            requireContext(),
                            (if (isArabic) "✅ تم التصدير إلى: " else "✅ Exported to: ") + file.absolutePath,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "export error", e)
                postToMain {
                    isExportingState = false
                    if (isAdded && context != null) {
                        Toast.makeText(
                            requireContext(),
                            if (isArabic) "❌ فشل التصدير" else "❌ Export failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun escapeCsv(value: String?): String {
        if (value == null) return ""
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    private fun postToMain(block: () -> Unit) {
        mainHandler.post {
            if (isDestroyed) return@post
            try {
                block()
            } catch (e: Throwable) {
                Log.e(TAG, "main-thread runnable crashed", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  تنسيق العملية ومنع ظهور نصوص JSON الخام
    // ═══════════════════════════════════════════════════════════════════

    private fun formatActivity(act: JSONObject, isArabic: Boolean): FormattedActivityDisplay {
        val actionKey = act.optString("action", "")
            .ifBlank { act.optString("type", "") }
            .ifBlank { act.optString("event", "") }
            .uppercase(Locale.US)

        val visualSpec = resolveVisualSpecForType(actionKey.lowercase(Locale.ROOT))

        val nestedDetails = extractNestedJson(act, "details")
            ?: extractNestedJson(act, "meta")
            ?: extractNestedJson(act, "payload")
            ?: act

        val explicitTitle = if (isArabic) {
            act.optString("title_ar", "").ifBlank { act.optString("title", "") }
        } else {
            act.optString("title_en", "").ifBlank { act.optString("title", "") }
        }

        val title = if (explicitTitle.isNotBlank() && !isRawEventKey(explicitTitle)) {
            explicitTitle
        } else {
            resolveFriendlyTitle(actionKey, isArabic)
        }

        val saleNumber = nestedDetails.optString("sale_number")
            .ifBlank { act.optString("sale_number") }
            .ifBlank { nestedDetails.optString("invoice_number") }

        val entryId = nestedDetails.optString("entry_id")
            .ifBlank { act.optString("entry_id") }

        val rawAmount = firstNonZeroDouble(
            act.optDouble("amount", Double.NaN),
            nestedDetails.optDouble("amount", Double.NaN),
            nestedDetails.optDouble("total_debit", Double.NaN),
            nestedDetails.optDouble("total_amount", Double.NaN)
        )

        val pureWeight = firstNonZeroDouble(
            nestedDetails.optDouble("pure_weight", Double.NaN),
            nestedDetails.optDouble("weight", Double.NaN),
            act.optDouble("weight", Double.NaN)
        )

        val currency = act.optString("currency")
            .ifBlank { nestedDetails.optString("currency") }
            .trim()

        val summaryParts = mutableListOf<String>()
        if (saleNumber.isNotBlank()) {
            summaryParts.add(if (isArabic) "فاتورة: $saleNumber" else "Invoice: $saleNumber")
        }
        if (entryId.isNotBlank()) {
            summaryParts.add(if (isArabic) "قيد #$entryId" else "Entry #$entryId")
        }
        if (!rawAmount.isNaN()) {
            val amtFormatted = String.format(Locale.US, "%,.2f %s", rawAmount, currency).trim()
            summaryParts.add(if (isArabic) "القيمة: $amtFormatted" else "Amount: $amtFormatted")
        }
        if (!pureWeight.isNaN()) {
            val wtFormatted = String.format(Locale.US, "%.2f", pureWeight)
            summaryParts.add(if (isArabic) "الوزن الصافي: $wtFormatted جم" else "Pure: ${wtFormatted}g")
        }

        val rawDetailsStr = if (isArabic) {
            act.optString("details_ar", "").ifBlank { act.optString("details", "") }
        } else {
            act.optString("details_en", "").ifBlank { act.optString("details", "") }
        }.trim()

        val details = when {
            summaryParts.isNotEmpty() -> summaryParts.joinToString("  •  ")
            rawDetailsStr.isNotEmpty() && !looksLikeRawJson(rawDetailsStr) -> rawDetailsStr
            else -> if (isArabic) "عملية مسجلة في النظام" else "System recorded activity"
        }

        val amountText = if (!rawAmount.isNaN()) {
            String.format(Locale.US, "%,.2f %s", rawAmount, currency).trim()
        } else {
            null
        }

        val badgeText = when {
            saleNumber.isNotBlank() -> saleNumber
            entryId.isNotBlank() -> "#$entryId"
            else -> null
        }

        val user = act.optString("user", "").ifBlank { act.optString("username", "") }
        val timestamp = act.optString("timestamp", "").ifBlank { act.optString("created_at", "") }
        val status = act.optString("status", "")

        return FormattedActivityDisplay(
            title = title,
            details = details,
            user = user,
            timestamp = timestamp,
            amountText = amountText,
            badgeText = badgeText,
            status = status,
            visualSpec = visualSpec
        )
    }

    private fun isRawEventKey(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.uppercase(Locale.US) == trimmed && trimmed.contains("_")
    }

    private fun looksLikeRawJson(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("{") || t.startsWith("[") || t.contains("\":")
    }

    private fun extractNestedJson(parent: JSONObject, key: String): JSONObject? {
        parent.optJSONObject(key)?.let { return it }
        val str = parent.optString(key, "").trim()
        if (str.startsWith("{")) {
            return try {
                JSONObject(str)
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun firstNonZeroDouble(vararg values: Double): Double {
        for (v in values) {
            if (!v.isNaN() && v != 0.0) return v
        }
        return Double.NaN
    }

    private fun resolveFriendlyTitle(actionKey: String, isArabic: Boolean): String {
        return when {
            actionKey.contains("REFUND") && actionKey.contains("JOURNAL") ->
                if (isArabic) "قيد يومية لمرتجع مبيعات" else "Sales Refund Journal Entry"
            actionKey.contains("REFUND") ->
                if (isArabic) "مرتجع فاتورة مبيعات" else "Sales Refund"
            actionKey.contains("SALE") && actionKey.contains("JOURNAL") ->
                if (isArabic) "قيد يومية لفاتورة بيع" else "Sales Journal Entry"
            actionKey.contains("SALE_COMPLETED") || actionKey.contains("POS_SALE") ->
                if (isArabic) "إتمام عملية بيع" else "Sale Completed"
            actionKey.contains("SCRAP") ->
                if (isArabic) "عملية شراء كسر ذهب" else "Scrap Gold Operation"
            actionKey.contains("TRANSFER") ->
                if (isArabic) "تحويل قطع بين الفروع" else "Branch Transfer"
            actionKey.contains("INVENTORY") || actionKey.contains("AUDIT") ->
                if (isArabic) "عملية جرد ومطابقة" else "Inventory Audit"
            actionKey.contains("RFID") || actionKey.contains("ENCODE") ->
                if (isArabic) "تكويد وقراءة شرائح RFID" else "RFID Tag Operation"
            actionKey.isBlank() ->
                if (isArabic) "عملية في النظام" else "System Activity"
            else -> {
                val cleaned = actionKey.replace('_', ' ').lowercase(Locale.US).trim()
                cleaned.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose UI
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun AllActivitiesScreen() {
        val colors = MiraTheme.colors
        val isAr = isArabic
        val keyboardController = LocalSoftwareKeyboardController.current
        val listState = rememberLazyListState()

        val shouldLoadMore by remember {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                totalItems > 0 && lastVisibleIndex >= totalItems - 3
            }
        }

        LaunchedEffect(shouldLoadMore) {
            if (shouldLoadMore && !isLoadingMoreState && hasMorePages) {
                loadMore()
            }
        }

        val categories = remember {
            listOf(
                ActivityCategorySpec("all", "الكل", "All", Icons.Rounded.Apps, Color(0xFFD4AF37)),
                ActivityCategorySpec("sale", "المبيعات", "Sales", Icons.Rounded.PointOfSale, Color(0xFF10B981)),
                ActivityCategorySpec("inventory", "الجرد", "Inventory", Icons.Rounded.Inventory2, Color(0xFF06B6D4)),
                ActivityCategorySpec("transfer", "التحويلات", "Transfers", Icons.Rounded.SwapHoriz, Color(0xFFF59E0B)),
                ActivityCategorySpec("rfid", "RFID", "RFID", Icons.Rounded.Contactless, Color(0xFF3B82F6)),
                ActivityCategorySpec("system", "النظام", "System", Icons.Rounded.SettingsSuggest, Color(0xFFA855F7))
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
                                        if (isAdded) {
                                            parentFragmentManager.popBackStack()
                                        }
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
                                    text = if (isAr) "سجل جميع العمليات" else "All Activities Log",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subtitle = when {
                                    totalOnServerState > 0 -> {
                                        if (isAr) "معروض ${filteredActivitiesState.size} من أصل $totalOnServerState عملية"
                                        else "Showing ${filteredActivitiesState.size} of $totalOnServerState activities"
                                    }
                                    totalCountState > 0 -> {
                                        if (isAr) "إجمالي العمليات المحملة: $totalCountState"
                                        else "Loaded activities: $totalCountState"
                                    }
                                    else -> {
                                        if (isAr) "مراقبة العمليات اللحظية للفرع"
                                        else "Real-time branch activity feed"
                                    }
                                }
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.goldPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clickable(enabled = !isRefreshingState && !isInitialLoadingState) {
                                        loadActivities(reset = true)
                                    },
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

                            Surface(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clickable(enabled = !isExportingState) {
                                        exportActivities()
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = colors.goldPrimary.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.55f))
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (isExportingState) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = colors.goldPrimary
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.FileDownload,
                                            contentDescription = null,
                                            tint = colors.goldPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = isRefreshingState) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                            color = colors.goldPrimary,
                            trackColor = Color.Transparent
                        )
                    }

                    OutlinedTextField(
                        value = currentSearchState,
                        onValueChange = { onSearchChanged(it) },
                        singleLine = true,
                        placeholder = {
                            Text(
                                text = if (isAr) "ابحث بالعنوان، التفاصيل، الموظف، أو الحالة..."
                                else "Search by title, details, user, or status...",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = colors.goldPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = {
                            if (currentSearchState.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        onSearchChanged("")
                                        keyboardController?.hide()
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
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )

                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(categories) { cat ->
                            val selected = currentFilterState == cat.key
                            val label = if (isAr) cat.labelAr else cat.labelEn

                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { selectFilter(cat.key) },
                                shape = RoundedCornerShape(50),
                                color = if (selected) cat.color.copy(alpha = 0.22f)
                                else colors.cardSurface,
                                border = BorderStroke(
                                    if (selected) 1.5.dp else 1.dp,
                                    if (selected) cat.color
                                    else colors.textSecondary.copy(alpha = 0.2f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = cat.icon,
                                        contentDescription = null,
                                        tint = if (selected) cat.color else colors.textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        color = if (selected) cat.color else colors.textSecondary
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        when {
                            isInitialLoadingState -> {
                                Column(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(color = colors.goldPrimary)
                                    Text(
                                        text = if (isAr) "جاري تحميل سجل العمليات..."
                                        else "Loading activities...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.textSecondary
                                    )
                                }
                            }

                            filteredActivitiesState.isEmpty() -> {
                                EmptyOrErrorStateView(
                                    isArabic = isAr,
                                    errorMessage = errorMessageState,
                                    hasActiveFilter = currentFilterState != "all" || currentSearchState.isNotEmpty(),
                                    onRetry = { loadActivities(reset = true) },
                                    onClearFilters = {
                                        currentFilterState = "all"
                                        currentSearchState = ""
                                        applyFilters()
                                    }
                                )
                            }

                            else -> {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(
                                        start = 16.dp,
                                        end = 16.dp,
                                        top = 4.dp,
                                        bottom = 24.dp
                                    ),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(
                                        items = filteredActivitiesState,
                                        key = { act ->
                                            val id = act.optString("id", "")
                                            val ts = act.optString("timestamp", "")
                                            val type = act.optString("type", act.optString("action", ""))
                                            if (id.isNotEmpty()) "${type}_$id"
                                            else "${type}_${ts}_${act.hashCode()}"
                                        }
                                    ) { activity ->
                                        ActivityItemCard(
                                            activity = activity,
                                            isArabic = isAr,
                                            onClick = { showActivityDetails(activity) }
                                        )
                                    }

                                    if (isLoadingMoreState) {
                                        item {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 14.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(20.dp),
                                                        strokeWidth = 2.dp,
                                                        color = colors.goldPrimary
                                                    )
                                                    Text(
                                                        text = if (isAr) "جاري تحميل المزيد..."
                                                        else "Loading more...",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = colors.textSecondary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ActivityItemCard(
        activity: JSONObject,
        isArabic: Boolean,
        onClick: () -> Unit
    ) {
        val colors = MiraTheme.colors
        val formatted = remember(activity, isArabic) { formatActivity(activity, isArabic) }
        val visualSpec = formatted.visualSpec

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, visualSpec.color.copy(alpha = 0.28f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(visualSpec.color.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = visualSpec.icon,
                        contentDescription = null,
                        tint = visualSpec.color,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatted.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        if (formatted.amountText != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF10B981).copy(alpha = 0.16f)
                            ) {
                                Text(
                                    text = formatted.amountText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF10B981),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        } else if (formatted.badgeText != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = colors.goldPrimary.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = formatted.badgeText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.goldPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    if (formatted.details.isNotEmpty()) {
                        Text(
                            text = formatted.details,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (formatted.user.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.Person,
                                        contentDescription = null,
                                        tint = colors.goldPrimary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = formatted.user,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            if (formatted.timestamp.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.Schedule,
                                        contentDescription = null,
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = formatted.timestamp,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        if (formatted.status.isNotEmpty()) {
                            val statusColor = resolveStatusColor(formatted.status)
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = statusColor.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = formatted.status.uppercase(Locale.US),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EmptyOrErrorStateView(
        isArabic: Boolean,
        errorMessage: String?,
        hasActiveFilter: Boolean,
        onRetry: () -> Unit,
        onClearFilters: () -> Unit
    ) {
        val colors = MiraTheme.colors
        val isError = !errorMessage.isNullOrBlank()

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
                    .background(
                        if (isError) Color(0xFFEF4444).copy(alpha = 0.15f)
                        else colors.goldPrimary.copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isError) Icons.Rounded.CloudOff else Icons.Rounded.HistoryToggleOff,
                    contentDescription = null,
                    tint = if (isError) Color(0xFFEF4444) else colors.goldPrimary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = when {
                    isError -> if (isArabic) "تعذّر تحميل العمليات" else "Unable to Load Activities"
                    hasActiveFilter -> if (isArabic) "لا توجد نتائج مطابقة" else "No Matching Activities"
                    else -> if (isArabic) "لا توجد عمليات مسجلة بعد" else "No Recorded Activities Yet"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = colors.textPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = when {
                    isError -> errorMessage!!
                    hasActiveFilter -> if (isArabic) "جرّب تغيير كلمة البحث أو نوع الفلتر المحدد"
                    else "Try adjusting your search query or selected filter"
                    else -> if (isArabic) "ستظهر هنا جميع عمليات البيع والجرد والتكويد فور تنفيذها"
                    else "Sales, inventory, and encoding operations will appear here"
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(18.dp))

            if (hasActiveFilter && !isError) {
                OutlinedButton(
                    onClick = onClearFilters,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colors.goldPrimary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.goldPrimary)
                ) {
                    Icon(Icons.Rounded.FilterAltOff, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isArabic) "إعادة ضبط الفلاتر" else "Reset Filters",
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Button(
                    onClick = onRetry,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.goldPrimary,
                        contentColor = Color(0xFF0A0F1D)
                    )
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isArabic) "إعادة المحاولة" else "Retry",
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }

    private fun resolveVisualSpecForType(type: String): ActivityCategorySpec {
        return when {
            type.contains("refund") -> ActivityCategorySpec(
                "refund", "مرتجع", "Refund", Icons.Rounded.AssignmentReturn, Color(0xFFF59E0B)
            )
            type.contains("sale") || type.contains("pos") -> ActivityCategorySpec(
                "sale", "مبيعات", "Sale", Icons.Rounded.PointOfSale, Color(0xFF10B981)
            )
            type.contains("scrap") || type.contains("buy") -> ActivityCategorySpec(
                "scrap", "شراء كسر", "Scrap Buy", Icons.Rounded.Scale, Color(0xFFF59E0B)
            )
            type.contains("inventory") || type.contains("audit") -> ActivityCategorySpec(
                "inventory", "جرد", "Inventory", Icons.Rounded.Inventory2, Color(0xFF06B6D4)
            )
            type.contains("transfer") || type.contains("move") -> ActivityCategorySpec(
                "transfer", "تحويل", "Transfer", Icons.Rounded.SwapHoriz, Color(0xFFF97316)
            )
            type.contains("rfid") || type.contains("encode") || type.contains("tag") -> ActivityCategorySpec(
                "rfid", "RFID", "RFID", Icons.Rounded.Contactless, Color(0xFFD4AF37)
            )
            else -> ActivityCategorySpec(
                "system", "النظام", "System", Icons.Rounded.SettingsSuggest, Color(0xFFA855F7)
            )
        }
    }

    private fun resolveStatusColor(status: String): Color {
        val s = status.lowercase(Locale.ROOT)
        return when {
            s.contains("success") || s.contains("complete") || s.contains("done") || s.contains("approved") ->
                Color(0xFF22C55E)
            s.contains("pending") || s.contains("wait") || s.contains("sync") ->
                Color(0xFFF59E0B)
            s.contains("fail") || s.contains("error") || s.contains("reject") || s.contains("cancel") ->
                Color(0xFFEF4444)
            else -> Color(0xFF3B82F6)
        }
    }
}
