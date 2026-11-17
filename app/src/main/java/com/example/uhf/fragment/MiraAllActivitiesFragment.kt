package com.example.uhf.fragment

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.example.uhf.api.MiraApiClient
import com.example.uhf.dialog.ActivityDetailsBottomSheet
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.ui.activities.*
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  شاشة جميع العمليات — Kotlin + Compose
 * ───────────────────────────────────────────────────────────────────────────
 *  ✅ سلوك مطابق للنسخة Java (pagination, filter, search, export)
 *  ✅ تصميم Compose حديث: TopBar متدرج + بحث مخصص + شرائح فلترة
 *  ✅ LazyColumn بتحميل تدريجي تلقائي عند الوصول لنهاية القائمة
 *  ✅ حالات: Loading / Empty / Error / NoResults — كل واحدة بمكوّن مستقل
 * ═══════════════════════════════════════════════════════════════════════════
 */
class MiraAllActivitiesFragment : Fragment() {

    companion object {
        private const val TAG = "MiraAllActivities"
        private const val PAGE_SIZE = 20
        private const val MAX_PAGE_SIZE = 200
    }

    // ── Managers & threading ────────────────────────────────────────
    private var apiClient: MiraApiClient? = null
    private var branchesManager: MiraBranchesManager? = null
    private var executor: ExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isRequestInFlight = AtomicBoolean(false)
    private var isDestroyed = false

    // ── Internal (non-compose) state ────────────────────────────────
    private val allActivities = mutableListOf<JSONObject>()
    private var currentPage = 1
    private var hasMorePages = true
    private var totalOnServer = -1
    private var isLoadingMore = false

    // ── Compose state ───────────────────────────────────────────────
    private var isArabicState by mutableStateOf(true)
    private var activitiesState by mutableStateOf<List<JSONObject>>(emptyList())
    private var currentFilterState by mutableStateOf("all")
    private var currentSearchState by mutableStateOf("")
    private var isLoadingState by mutableStateOf(false)
    private var isRefreshingState by mutableStateOf(false)
    private var errorMessageState by mutableStateOf<String?>(null)
    private var totalCountState by mutableIntStateOf(0)

    // ═══════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val direction = if (isArabicState) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                MiraJewelryTheme { MiraAllActivitiesScreen() }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            isDestroyed = false
            isArabicState = Locale.getDefault().language == "ar"
            apiClient = MiraApiClient.getInstance(requireContext())
            branchesManager = MiraBranchesManager.getInstance(requireContext())
            executor = Executors.newFixedThreadPool(2)
            loadActivities(reset = true)
        } catch (t: Throwable) {
            Log.e(TAG, "onViewCreated error", t)
        }
    }

    override fun onDestroyView() {
        isDestroyed = true
        try { executor?.shutdownNow() } catch (_: Exception) {}
        executor = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Loading
    // ═══════════════════════════════════════════════════════════════════

    private fun loadActivities(reset: Boolean) {
        val client = apiClient
        val exec = executor
        if (client == null || exec == null) {
            showError(if (isArabicState) "العميل غير مهيأ" else "Client not initialized")
            return
        }
        if (!isRequestInFlight.compareAndSet(false, true)) {
            isRefreshingState = false
            return
        }

        if (reset) {
            currentPage = 1
            hasMorePages = true
            isLoadingMore = false
            totalOnServer = -1
            allActivities.clear()
            errorMessageState = null
        }

        isLoadingState = allActivities.isEmpty()
        val pageToLoad = currentPage
        val requestLimit = minOf(PAGE_SIZE * pageToLoad, MAX_PAGE_SIZE)

        exec.execute {
            try {
                val branchId = branchesManager?.activeBranchId ?: 0
                Log.d(TAG, "→ loadActivities | branch=$branchId page=$pageToLoad limit=$requestLimit reset=$reset")

                val response = client.getUnifiedActivity(branchId, requestLimit)
                postToMain { handleResponse(response, reset, pageToLoad) }
            } catch (t: Throwable) {
                Log.e(TAG, "loadActivities exception", t)
                postToMain {
                    finishLoading()
                    showError(t.message)
                }
            } finally {
                isRequestInFlight.set(false)
            }
        }
    }

    private fun handleResponse(
        response: MiraApiClient.ApiResponse?,
        reset: Boolean,
        pageToLoad: Int
    ) {
        if (isDestroyed) return
        finishLoading()

        if (response == null) {
            showError(if (isArabicState) "فشل الاتصال بالخادم" else "Server connection failed")
            return
        }
        if (!response.isSuccess) {
            val err = response.error?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code}"
            showError(err)
            return
        }
        val data = response.data
        if (data == null) {
            showError(if (isArabicState) "استجابة غير صالحة من الخادم" else "Invalid server response")
            return
        }

        val activities: JSONArray = extractActivitiesArray(data)
            ?: run {
                showError(if (isArabicState) "بنية الاستجابة غير متوقعة" else "Unexpected response structure")
                return
            }

        if (reset) allActivities.clear()
        for (i in 0 until activities.length()) {
            activities.optJSONObject(i)?.let { allActivities.add(it) }
        }

        // hasMorePages
        val serverTotal = data.optInt("total", -1)
        hasMorePages = if (serverTotal >= 0) {
            totalOnServer = serverTotal
            allActivities.size < serverTotal
        } else {
            activities.length() >= minOf(PAGE_SIZE * pageToLoad, MAX_PAGE_SIZE)
        }

        errorMessageState = null
        applyFilters()
    }

    private fun extractActivitiesArray(top: JSONObject): JSONArray? {
        top.optJSONArray("activities")?.let { return it }
        top.optJSONArray("data")?.let { return it }
        top.optJSONArray("items")?.let { return it }
        top.optJSONArray("logs")?.let { return it }
        top.optJSONArray("result")?.let { return it }
        top.optJSONObject("data")?.let { nested ->
            nested.optJSONArray("activities")?.let { return it }
            nested.optJSONArray("items")?.let { return it }
            nested.optJSONArray("logs")?.let { return it }
        }
        return null
    }

    private fun loadMore() {
        if (isLoadingMore || !hasMorePages || isRequestInFlight.get()) return
        isLoadingMore = true
        currentPage++
        loadActivities(reset = false)
    }

    private fun finishLoading() {
        isLoadingMore = false
        isLoadingState = false
        isRefreshingState = false
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Filters (in-memory)
    // ═══════════════════════════════════════════════════════════════════

    private fun applyFilters() {
        val filtered = allActivities.filter { matchesType(it) && matchesSearch(it) }
        totalCountState = allActivities.size
        activitiesState = filtered
    }

    private fun matchesType(act: JSONObject): Boolean {
        if (currentFilterState == "all") return true
        val type = act.optString("type", "").lowercase(Locale.ROOT)
        val filter = currentFilterState.lowercase(Locale.ROOT)
        return type == filter || type.contains(filter)
    }

    private fun matchesSearch(act: JSONObject): Boolean {
        if (currentSearchState.isEmpty()) return true
        val fields = arrayOf(
            "title_ar", "title_en", "details_ar", "details_en",
            "user", "username", "title", "details", "status"
        )
        return fields.any {
            act.optString(it, "").lowercase(Locale.ROOT).contains(currentSearchState)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Actions
    // ═══════════════════════════════════════════════════════════════════

    private fun onFilterSelected(filter: String) {
        currentFilterState = filter
        applyFilters()
    }

    private fun onSearchChanged(query: String) {
        currentSearchState = query.trim().lowercase(Locale.ROOT)
        applyFilters()
    }

    private fun onRefresh() {
        isRefreshingState = true
        loadActivities(reset = true)
    }

    private fun onBack() {
        try { parentFragmentManager.popBackStack() } catch (_: Exception) {}
    }

    private fun onActivityClick(activity: JSONObject) {
        try {
            val sheet = ActivityDetailsBottomSheet.newInstance(activity, isArabicState)
            sheet.show(parentFragmentManager, "ActivityDetails")
        } catch (e: Exception) {
            Log.e(TAG, "details error", e)
        }
    }

    private fun showError(message: String?) {
        if (isDestroyed) return
        val msg = message?.takeIf { it.isNotBlank() }
            ?: (if (isArabicState) "تعذّر تحميل العمليات" else "Failed to load activities")

        allActivities.clear()
        activitiesState = emptyList()
        totalCountState = 0
        errorMessageState = msg

        if (isAdded) {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun postToMain(r: Runnable) {
        mainHandler.post {
            if (isDestroyed) return@post
            try { r.run() } catch (t: Throwable) { Log.e(TAG, "main crash", t) }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CSV Export
    // ═══════════════════════════════════════════════════════════════════

    private fun exportActivities() {
        val list = activitiesState
        if (list.isEmpty()) {
            Toast.makeText(
                requireContext(),
                if (isArabicState) "⚠️ لا توجد عمليات للتصدير" else "⚠️ No activities to export",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val exec = executor ?: return
        val ctx = requireContext().applicationContext

        exec.execute {
            try {
                val dir = File(ctx.getExternalFilesDir(null), "exports").apply { mkdirs() }
                val fileName = "activities_${
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                }.csv"
                val file = File(dir, fileName)

                FileWriter(file).use { w ->
                    w.append("Timestamp,Type,Title,Details,User,Amount,Currency,Status\n")
                    for (a in list) {
                        w.append(escapeCsv(a.optString("timestamp", ""))).append(",")
                        w.append(escapeCsv(a.optString("type", ""))).append(",")
                        w.append(escapeCsv(a.optString("title_ar", a.optString("title_en", "")))).append(",")
                        w.append(escapeCsv(a.optString("details_ar", a.optString("details_en", "")))).append(",")
                        w.append(escapeCsv(a.optString("user", a.optString("username", "")))).append(",")
                        w.append(escapeCsv(a.optDouble("amount", 0.0).toString())).append(",")
                        w.append(escapeCsv(a.optString("currency", ""))).append(",")
                        w.append(escapeCsv(a.optString("status", ""))).append("\n")
                    }
                    w.flush()
                }

                postToMain {
                    Toast.makeText(
                        ctx,
                        (if (isArabicState) "✅ تم التصدير إلى: " else "✅ Exported to: ") + file.absolutePath,
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "export error", t)
                postToMain {
                    Toast.makeText(
                        ctx,
                        if (isArabicState) "❌ فشل التصدير" else "❌ Export failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun escapeCsv(v: String?): String {
        if (v.isNullOrEmpty()) return ""
        return if (v.contains(",") || v.contains("\"") || v.contains("\n"))
            "\"${v.replace("\"", "\"\"")}\"" else v
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Screen
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun MiraAllActivitiesScreen() {
        val colors = MiraTheme.colors
        val listState = rememberLazyListState()

        // كشف نهاية القائمة لتحميل المزيد
        val shouldLoadMore by remember {
            derivedStateOf {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
            }
        }
        LaunchedEffect(shouldLoadMore) {
            if (shouldLoadMore) loadMore()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(colors.backgroundTop, colors.backgroundMid, colors.backgroundTop)
                    )
                )
        ) {
            AllActivitiesTopBar(
                isArabic = isArabicState,
                totalCount = totalCountState,
                visibleCount = activitiesState.size,
                isRefreshing = isRefreshingState,
                onBack = ::onBack,
                onExport = ::exportActivities,
                onRefresh = ::onRefresh
            )

            ActivitySearchBar(
                query = currentSearchState,
                placeholder = if (isArabicState) "ابحث في العمليات…" else "Search activities…",
                onQueryChange = ::onSearchChanged
            )

            ActivityFilterChips(
                isArabic = isArabicState,
                selected = currentFilterState,
                onSelected = ::onFilterSelected,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    isLoadingState && activitiesState.isEmpty() ->
                        ActivityLoadingState()

                    errorMessageState != null ->
                        ActivityErrorState(
                            isArabic = isArabicState,
                            message = errorMessageState!!,
                            onRetry = ::onRefresh
                        )

                    activitiesState.isEmpty() && currentSearchState.isEmpty() && currentFilterState == "all" ->
                        ActivityEmptyState(
                            isArabic = isArabicState,
                            icon = "📭",
                            title = if (isArabicState) "لا توجد عمليات" else "No activities",
                            subtitle = if (isArabicState) "ستظهر هنا العمليات فور تسجيلها" else "Activities will appear here once recorded"
                        )

                    activitiesState.isEmpty() ->
                        ActivityEmptyState(
                            isArabic = isArabicState,
                            icon = "🔍",
                            title = if (isArabicState) "لا نتائج مطابقة" else "No matching results",
                            subtitle = if (isArabicState) "جرّب تغيير الفلتر أو البحث" else "Try a different filter or search"
                        )

                    else -> {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(
                                items = activitiesState,
                                key = { it.optString("id", it.hashCode().toString()) }
                            ) { act ->
                                ActivityListItem(
                                    activity = act,
                                    isArabic = isArabicState,
                                    onClick = { onActivityClick(act) }
                                )
                            }

                            if (hasMorePages) {
                                item(key = "__load_more__") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.size(22.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                            } else if (activitiesState.isNotEmpty()) {
                                item(key = "__end__") {
                                    EndOfListBadge(isArabic = isArabicState)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
