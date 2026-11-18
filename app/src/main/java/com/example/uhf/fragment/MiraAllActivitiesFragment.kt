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

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  MiraAllActivitiesFragment — Jetpack Compose Luxury Edition
 * ───────────────────────────────────────────────────────────────────────────
 *  شاشة سجل جميع العمليات والنشاطات:
 *   • تصفية فورية حسب النوع (الكل، المبيعات، الجرد، التحويلات، RFID، النظام)
 *   • بحث فوري متعدد الحقول (عربي / إنجليزي / المستخدم / الحالة)
 *   • تحميل تدريجي تلقائي (Infinite Pagination) مع حماية ضد الطلبات المتكررة
 *   • تصدير العمليات المفلترة إلى ملف CSV
 *   • عرض التفاصيل الكاملة عبر ActivityDetailsBottomSheet
 * ═══════════════════════════════════════════════════════════════════════════
 */
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

    // ─── State & Concurrency ───────────────────────────────────────────
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

    // ─── Compose Reactive States ───────────────────────────────────────
    private var currentFilterState by mutableStateOf("all")
    private var currentSearchState by mutableStateOf("")
    private var isInitialLoadingState by mutableStateOf(false)
    private var isRefreshingState by mutableStateOf(false)
    private var isLoadingMoreState by mutableStateOf(false)
    private var isExportingState by mutableStateOf(false)
    private var errorMessageState by mutableStateOf<String?>(null)
    private var totalCountState by mutableIntStateOf(0)
    private var totalOnServerState by mutableIntStateOf(-1)

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

    // ═══════════════════════════════════════════════════════════════════
    //  Data Loading & Pagination
    // ═══════════════════════════════════════════════════════════════════

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

                Log.d(
                    TAG,
                    "→ loadActivities | branchId=$branchId page=$pageToLoad limit=$requestLimit reset=$reset"
                )

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
            Log.e(TAG, "response == null")
            showError(if (isArabic) "فشل الاتصال بالخادم" else "Server connection failed")
            return
        }

        if (!response.isSuccess) {
            val err = if (!response.error.isNullOrEmpty()) response.error else "HTTP ${response.code}"
            Log.e(TAG, "❌ API error | code=${response.code} err=$err")
            showError(err)
            return
        }

        val dataObj = response.data
        if (dataObj == null) {
            Log.e(TAG, "response.data == null | body=${safeBody(response)}")
            showError(if (isArabic) "استجابة غير صالحة من الخادم" else "Invalid server response")
            return
        }

        val activities = extractActivitiesArray(dataObj)
        if (activities == null) {
            Log.e(TAG, "No activities array in response | body=${safeBody(response)}")
            showError(if (isArabic) "بنية الاستجابة غير متوقعة" else "Unexpected response structure")
            return
        }

        errorMessageState = null

        if (reset) {
            allActivities.clear()
        }

        var parsed = 0
        var skipped = 0
        for (i in 0 until activities.length()) {
            try {
                val act = activities.optJSONObject(i)
                if (act != null) {
                    allActivities.add(act)
                    parsed++
                }
            } catch (e: Throwable) {
                skipped++
                Log.w(TAG, "skip malformed activity at index $i")
            }
        }

        totalCountState = allActivities.size

        Log.d(
            TAG,
            "✅ Fetched $parsed activities" +
                (if (skipped > 0) " (skipped $skipped)" else "") +
                " | total_in_list=${allActivities.size}"
        )

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
        Log.d(TAG, "→ loadMore → page=$currentPage")
        loadActivities(reset = false)
    }

    private fun finishLoading() {
        isLoadingMore = false
        isInitialLoadingState = false
        isRefreshingState = false
        isLoadingMoreState = false
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Filtering & Search
    // ═══════════════════════════════════════════════════════════════════

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
        val type = act.optString("type", "").lowercase(Locale.ROOT)
        return filterLower == type || type.contains(filterLower)
    }

    private fun matchesSearch(act: JSONObject, searchLower: String): Boolean {
        if (searchLower.isEmpty()) return true
        val fields = arrayOf(
            "title_ar", "title_en", "details_ar", "details_en",
            "user", "username", "title", "details", "status"
        )
        for (field in fields) {
            val value = act.optString(field, "").lowercase(Locale.ROOT)
            if (value.contains(searchLower)) return true
        }
        return false
    }

    private fun showError(message: String?) {
        if (isDestroyed) return
        val msg = if (message.isNullOrBlank()) {
            if (isArabic) "تعذّر تحميل العمليات" else "Failed to load activities"
        } else {
            message.trim()
        }

        Log.e(TAG, "showError: $msg")
        allActivities.clear()
        filteredActivitiesState.clear()
        totalCountState = 0
        errorMessageState = msg

        if (isAdded && context != null) {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Actions (Details BottomSheet & CSV Export)
    // ═══════════════════════════════════════════════════════════════════

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
                    writer.append("Timestamp,Type,Title,Details,User,Amount,Currency,Status\n")
                    for (act in snapshot) {
                        writer.append(escapeCsv(act.optString("timestamp", ""))).append(",")
                        writer.append(escapeCsv(act.optString("type", ""))).append(",")
                        writer.append(
                            escapeCsv(
                                act.optString("title_ar", act.optString("title_en", ""))
                            )
                        ).append(",")
                        writer.append(
                            escapeCsv(
                                act.optString("details_ar", act.optString("details_en", ""))
                            )
                        ).append(",")
                        writer.append(
                            escapeCsv(
                                act.optString("user", act.optString("username", ""))
                            )
                        ).append(",")
                        writer.append(escapeCsv(act.optDouble("amount", 0.0).toString())).append(",")
                        writer.append(escapeCsv(act.optString("currency", ""))).append(",")
                        writer.append(escapeCsv(act.optString("status", ""))).append("\n")
                    }
                    writer.flush()
                }

                postToMain {
                    isExportingState = false
                    if (isAdded && context != null) {
                        Toast.makeText(
                            requireContext(),
                            (if (isArabic) "✅ تم التصدير إلى: " else "✅ Exported to: ") +
                                file.absolutePath,
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

    private fun safeBody(r: MiraApiClient.ApiResponse): String {
        return try {
            val b = r.getBody()
            when {
                b == null -> "<null>"
                b.length > 300 -> b.substring(0, 300) + "..."
                else -> b
            }
        } catch (_: Throwable) {
            "<unreadable>"
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
    //  Jetpack Compose UI
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun AllActivitiesScreen() {
        val colors = MiraTheme.colors
        val isAr = isArabic
        val keyboardController = LocalSoftwareKeyboardController.current
        val listState = rememberLazyListState()

        // مراقبة الوصول لنهاية القائمة لتحميل الصفحة التالية تلقائياً
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
                            // زر الرجوع
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

                        // أزرار التحديث والتصدير CSV
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

                    // مؤشر تقدم علوي عند التحديث
                    AnimatedVisibility(visible = isRefreshingState) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                            color = colors.goldPrimary,
                            trackColor = Color.Transparent
                        )
                    }

                    // 2. حقل البحث الفوري
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

                    // 3. شريط فلاتر التصنيف (Category Filter Chips)
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

                    // 4. محتوى القائمة أو حالات التحميل / الفراغ / الخطأ
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
                                            val type = act.optString("type", "")
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

                                    // مؤشر تحميل المزيد في أسفل القائمة
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
        val rawType = activity.optString("type", "system").lowercase(Locale.ROOT)
        val visualSpec = resolveVisualSpecForType(rawType)

        val title = if (isArabic) {
            activity.optString("title_ar", "")
                .ifEmpty { activity.optString("title", "") }
                .ifEmpty { activity.optString("title_en", rawType.uppercase(Locale.US)) }
        } else {
            activity.optString("title_en", "")
                .ifEmpty { activity.optString("title", "") }
                .ifEmpty { activity.optString("title_ar", rawType.uppercase(Locale.US)) }
        }

        val details = if (isArabic) {
            activity.optString("details_ar", "")
                .ifEmpty { activity.optString("details", "") }
                .ifEmpty { activity.optString("details_en", "") }
        } else {
            activity.optString("details_en", "")
                .ifEmpty { activity.optString("details", "") }
                .ifEmpty { activity.optString("details_ar", "") }
        }

        val user = activity.optString("user", "")
            .ifEmpty { activity.optString("username", "") }
        val timestamp = activity.optString("timestamp", "")
            .ifEmpty { activity.optString("created_at", "") }
        val amount = activity.optDouble("amount", 0.0)
        val currency = activity.optString("currency", "")
        val status = activity.optString("status", "")

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
                // أيقونة نوع العملية
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
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        if (amount > 0.0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF10B981).copy(alpha = 0.16f)
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%.2f %s", amount, currency).trim(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF10B981),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    if (details.isNotEmpty()) {
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    // شريط البيانات السفلية (المستخدم + الوقت + الحالة)
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
                            if (user.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.Person,
                                        contentDescription = null,
                                        tint = colors.goldPrimary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = user,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            if (timestamp.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.Schedule,
                                        contentDescription = null,
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = timestamp,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        if (status.isNotEmpty()) {
                            val statusColor = resolveStatusColor(status)
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = statusColor.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = status.uppercase(Locale.US),
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
