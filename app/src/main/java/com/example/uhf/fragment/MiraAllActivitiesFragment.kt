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
import androidx.fragment.app.Fragment
import com.example.uhf.R
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.api.MiraApiClient
import com.example.uhf.dialog.ActivityDetailsBottomSheet
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.ui.dashboard.UnifiedOperationCard
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import com.example.uhf.tools.SecureSessionManager
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
        private const val PAGE_SIZE = 30
    }

    private data class ActivityCategorySpec(
        val key: String,
        val labelAr: String,
        val labelEn: String,
        val icon: ImageVector,
        val color: Color
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
        val searchSnapshot = currentSearchState.trim()
        val filterSnapshot = currentFilterState

        // قراءة بيانات المستخدم ودوره لضمان تطبيق العزل للموظفين والشمولية للمدراء
        val sm = context?.let { SecureSessionManager.getInstance(it) }
        val userId = readIntPropSafe(sm, "userId", "id")
        val username = readStringPropSafe(sm, "username", "fullName", "name")
        val role = readStringPropSafe(sm, "role", "userRole", "position").lowercase(Locale.ROOT)
        val managerKeywords = setOf(
            "admin", "super_admin", "superadmin", "owner", "manager",
            "branch_manager", "general_manager", "supervisor", "director",
            "accountant", "auditor", "مدير", "مشرف", "مالك", "محاسب"
        )
        val isManager = role.isNotBlank() && managerKeywords.any { role.contains(it) }

        executor.execute {
            try {
                val branchId = branchesManager?.activeBranchId ?: 0
                val response = client.getUnifiedOperations(
                    branchId,
                    userId,
                    username,
                    isManager,
                    pageToLoad,
                    PAGE_SIZE,
                    searchSnapshot,
                    filterSnapshot
                )

                postToMain {
                    handleResponse(response, reset)
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

    private fun readStringPropSafe(target: Any?, vararg names: String): String {
        if (target == null) return ""
        for (name in names) {
            try {
                val getter = "get" + name.replaceFirstChar { it.uppercaseChar() }
                val m = target.javaClass.methods.firstOrNull {
                    (it.name == getter || it.name == name) && it.parameterCount == 0
                }
                val v = m?.invoke(target)?.toString()?.trim()
                if (!v.isNullOrEmpty() && v != "null") return v
            } catch (_: Exception) {
            }
        }
        return ""
    }

    private fun readIntPropSafe(target: Any?, vararg names: String): Int {
        if (target == null) return 0
        for (name in names) {
            try {
                val getter = "get" + name.replaceFirstChar { it.uppercaseChar() }
                val m = target.javaClass.methods.firstOrNull {
                    (it.name == getter || it.name == name) && it.parameterCount == 0
                }
                val v = m?.invoke(target)
                if (v is Number) return v.toInt()
            } catch (_: Exception) {
            }
        }
        return 0
    }


    private fun handleResponse(response: MiraApiClient.ApiResponse?, reset: Boolean) {
        if (isDestroyed) return
        finishLoading()

        if (response == null || !response.isSuccess || response.data == null) {
            val err = response?.error?.takeIf { it.isNotBlank() }
                ?: (if (isArabic) "فشل الاتصال بالخادم" else "Server connection failed")
            showError(err)
            return
        }

        val dataObj = response.data!!
        val activities = dataObj.optJSONArray("activities") ?: JSONArray()
        errorMessageState = null

        if (reset) {
            allActivities.clear()
        }

        for (i in 0 until activities.length()) {
            activities.optJSONObject(i)?.let { allActivities.add(it) }
        }

        totalCountState = allActivities.size
        val serverTotal = dataObj.optInt("total", -1)
        if (serverTotal >= 0) {
            totalOnServerState = serverTotal
        }
        hasMorePages = dataObj.optBoolean("has_more", false)

        applyLocalFilters()
    }

    private fun loadMore() {
        if (isLoadingMore || !hasMorePages || isRequestInFlight.get()) return
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
        if (currentFilterState != filter) {
            currentFilterState = filter
            loadActivities(reset = true)
        }
    }

    private fun onSearchChanged(query: String) {
        currentSearchState = query
        applyLocalFilters()
    }

    private fun applyLocalFilters() {
        val searchLower = currentSearchState.trim().lowercase(Locale.ROOT)
        val filterLower = currentFilterState.lowercase(Locale.ROOT)

        val result = ArrayList<JSONObject>()
        for (act in allActivities) {
            if (filterLower != "all") {
                val t = act.optString("type", "").lowercase(Locale.ROOT)
                if (!t.contains(filterLower)) continue
            }
            if (searchLower.isNotEmpty() && !act.toString().lowercase(Locale.ROOT).contains(searchLower)) {
                continue
            }
            result.add(act)
        }

        filteredActivitiesState.clear()
        filteredActivitiesState.addAll(result)
    }

    private fun showError(message: String?) {
        if (isDestroyed) return
        val msg = message?.trim()?.takeIf { it.isNotEmpty() }
            ?: (if (isArabic) "تعذّر تحميل العمليات" else "Failed to load activities")

        if (allActivities.isEmpty()) {
            filteredActivitiesState.clear()
            totalCountState = 0
            errorMessageState = msg
        }
    }

    private fun showActivityDetails(activity: JSONObject) {
        try {
            if (!isAdded) return
            val sheet = ActivityDetailsBottomSheet.newInstance(activity, isArabic)
            sheet.setOnActionClickListener(object : ActivityDetailsBottomSheet.OnActionClickListener {
                override fun onPrintInvoice(activityData: JSONObject) {
                    if (!isAdded || context == null) return
                    try {
                        val receiptFragment = MiraReceiptFragment.newInstance(activityData.toString())
                        val fm = parentFragmentManager
                        if (!fm.isStateSaved) receiptFragment.show(fm, "MiraReceiptFragment")
                    } catch (_: Exception) {
                        Toast.makeText(
                            context,
                            if (isArabic) "تعذر فتح الفاتورة" else "Unable to open receipt",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onOpenRefund(activityData: JSONObject) {
                    if (!isAdded) return
                    try {
                        val salesFragment = MiraPosFragment().apply {
                            arguments = Bundle().apply {
                                putString("refund_data_json", activityData.toString())
                                putBoolean("auto_open_refund", true)
                            }
                        }
                        (getActivity() as? UHFMainActivity)?.loadFragment(salesFragment, "Sales")
                    } catch (_: Exception) {
                    }
                }
            })
            sheet.show(parentFragmentManager, "ActivityDetails")
        } catch (e: Throwable) {
            Log.e(TAG, "details error", e)
        }
    }

    private fun exportActivities() {
        val snapshot = ArrayList(filteredActivitiesState)
        if (snapshot.isEmpty()) {
            Toast.makeText(
                requireContext(),
                if (isArabic) "⚠️ لا توجد عمليات للتصدير" else "⚠️ No activities to export",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val ctx = appContext ?: return
        isExportingState = true

        executor.execute {
            try {
                val dir = File(ctx.getExternalFilesDir(null), "exports")
                if (!dir.exists()) dir.mkdirs()

                val fileName = "operations_" +
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
                val file = File(dir, fileName)

                FileWriter(file).use { writer ->
                    writer.append("Date,Type,Reference,Customer_User,Amount,Currency,Status\n")
                    for (act in snapshot) {
                        writer.append(escapeCsv(act.optString("created_at", act.optString("timestamp", "")))).append(",")
                        writer.append(escapeCsv(act.optString("type", ""))).append(",")
                        writer.append(escapeCsv(act.optString("sale_number", act.optString("id", "")))).append(",")
                        writer.append(escapeCsv(act.optString("customer_name", act.optString("user", "")))).append(",")
                        writer.append(escapeCsv(act.optString("final_amount", act.optString("amount", "")))).append(",")
                        writer.append(escapeCsv(act.optString("currency", "YER"))).append(",")
                        writer.append(escapeCsv(act.optString("refund_status", act.optString("status", "completed")))).append("\n")
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
        } else value
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
                ActivityCategorySpec("refund", "المرتجعات", "Refunds", Icons.Rounded.AssignmentReturn, Color(0xFFEF4444)),
                ActivityCategorySpec("scrap", "شراء كسر", "Scrap Gold", Icons.Rounded.Scale, Color(0xFFF59E0B)),
                ActivityCategorySpec("transfer", "التحويلات", "Transfers", Icons.Rounded.SwapHoriz, Color(0xFFA855F7)),
                ActivityCategorySpec("inventory", "الجرد", "Inventory", Icons.Rounded.Inventory2, Color(0xFF06B6D4))
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
                                        if (isAdded) parentFragmentManager.popBackStack()
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
                                    text = if (isAr) "سجل جميع العمليات" else "All Operations Log",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subtitle = when {
                                    totalOnServerState > 0 -> {
                                        if (isAr) "معروض ${filteredActivitiesState.size} من أصل $totalOnServerState عملية"
                                        else "Showing ${filteredActivitiesState.size} of $totalOnServerState operations"
                                    }
                                    totalCountState > 0 -> {
                                        if (isAr) "إجمالي العمليات: $totalCountState"
                                        else "Total operations: $totalCountState"
                                    }
                                    else -> {
                                        if (isAr) "السجل الموحد للمبيعات والعمليات"
                                        else "Unified sales & operations feed"
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
                                text = if (isAr) "ابحث برقم الفاتورة، اسم العميل، أو العملية..."
                                else "Search by invoice #, customer, or operation...",
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
                                        loadActivities(reset = true)
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
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboardController?.hide()
                            loadActivities(reset = true)
                        }),
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
                                        else "Loading operations...",
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
                                        loadActivities(reset = true)
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
                                            val saleNo = act.optString("sale_number", "")
                                            val id = act.optString("id", "")
                                            val ts = act.optString("created_at", act.optString("timestamp", ""))
                                            if (saleNo.isNotEmpty()) "sale_$saleNo"
                                            else if (id.isNotEmpty()) "op_${id}_$ts"
                                            else "row_${ts}_${act.hashCode()}"
                                        }
                                    ) { activity ->
                                        UnifiedOperationCard(
                                            item = activity,
                                            isArabic = isAr,
                                            maskAmount = false,
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
                    isError -> if (isArabic) "تعذّر تحميل العمليات" else "Unable to Load Operations"
                    hasActiveFilter -> if (isArabic) "لا توجد نتائج مطابقة" else "No Matching Operations"
                    else -> if (isArabic) "لا توجد عمليات مسجلة بعد" else "No Recorded Operations Yet"
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
                    else -> if (isArabic) "ستظهر هنا جميع عمليات البيع والمرتجعات والتحويلات فور تنفيذها"
                    else "Sales, refunds, and transfers will appear here"
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
}
