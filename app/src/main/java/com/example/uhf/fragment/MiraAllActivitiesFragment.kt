package com.example.uhf.fragment

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.example.uhf.api.MiraApiClient
import com.example.uhf.dialog.ActivityDetailsBottomSheet
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.tools.SessionManager
import com.example.uhf.ui.activities.*
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MiraAllActivitiesFragment : Fragment() {

    private var apiClient: MiraApiClient? = null
    private var sessionManager: SessionManager? = null
    private var branchesManager: MiraBranchesManager? = null
    private var localExecutor: ExecutorService? = null

    private var isArabicState by mutableStateOf(true)
    private var isRefreshingState by mutableStateOf(false)
    private var isLoadingState by mutableStateOf(true)
    private var isErrorState by mutableStateOf(false)
    private var errorMessageState by mutableStateOf("")

    private var searchQueryState by mutableStateOf("")
    private var selectedFilterState by mutableStateOf("all")
    private var activitiesListState by mutableStateOf<List<JSONObject>>(emptyList())

    private var currentBranchId = 1

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
        try {
            val ctx = requireContext().getApplicationContext()
            apiClient = MiraApiClient.getInstance(ctx)
            sessionManager = SessionManager(ctx)
            branchesManager = MiraBranchesManager.getInstance(ctx)
            localExecutor = Executors.newSingleThreadExecutor()

            isArabicState = Locale.getDefault().language == "ar"
            currentBranchId = branchesManager?.activeBranchId ?: sessionManager?.branchId ?: 1

            setupBackPress()
            fetchActivities()
        } catch (e: Exception) {
            Log.e("MiraAllActivities", "Init error", e)
        }
    }

    private fun fetchActivities() {
        val client = apiClient ?: return
        isLoadingState = true
        isErrorState = false

        localExecutor?.execute {
            try {
                val response = client.getUnifiedActivity(currentBranchId, 100)
                requireActivity().runOnUiThread {
                    isLoadingState = false
                    isRefreshingState = false
                    if (response != null && response.isSuccess && response.data != null) {
                        val arr = extractActivitiesArray(response.data)
                        val list = mutableListOf<JSONObject>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                arr.optJSONObject(i)?.let { list.add(it) }
                            }
                        }
                        activitiesListState = list
                    } else {
                        isErrorState = true
                        errorMessageState = response?.error ?: (if (isArabicState) "فشل جلب العمليات" else "Failed to load activities")
                    }
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    isLoadingState = false
                    isRefreshingState = false
                    isErrorState = true
                    errorMessageState = e.message ?: "Unknown error"
                }
            }
        }
    }

    private fun extractActivitiesArray(top: JSONObject): JSONArray? {
        top.optJSONArray("activities")?.let { return it }
        top.optJSONArray("data")?.let { return it }
        top.optJSONArray("items")?.let { return it }
        top.optJSONArray("logs")?.let { return it }
        val nested = top.optJSONObject("data")
        if (nested != null) {
            nested.optJSONArray("activities")?.let { return it }
            nested.optJSONArray("items")?.let { return it }
        }
        return null
    }

    @Composable
    private fun AllActivitiesScreen() {
        val colors = MiraTheme.colors
        val filteredList = remember(activitiesListState, searchQueryState, selectedFilterState) {
            activitiesListState.filter { item ->
                val type = item.optString("type", "").lowercase(Locale.ROOT)
                val matchesFilter = when (selectedFilterState) {
                    "sale" -> type.contains("sale") || type.contains("pos")
                    "inventory" -> type.contains("inventory") || type.contains("stock")
                    "transfer" -> type.contains("transfer") || type.contains("swap")
                    "rfid" -> type.contains("rfid") || type.contains("scan") || type.contains("gate")
                    "system" -> type.contains("system") || type.contains("login") || type.contains("logout")
                    else -> true
                }
                val query = searchQueryState.trim().lowercase(Locale.ROOT)
                val matchesQuery = query.isEmpty() ||
                        item.optString("title", "").lowercase(Locale.ROOT).contains(query) ||
                        item.optString("details", "").lowercase(Locale.ROOT).contains(query) ||
                        item.optString("user", "").lowercase(Locale.ROOT).contains(query)

                matchesFilter && matchesQuery
            }
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
                    AllActivitiesTopBar(
                        isArabic = isArabicState,
                        totalCount = activitiesListState.size,
                        visibleCount = filteredList.size,
                        isRefreshing = isRefreshingState,
                        onBack = { requireActivity().onBackPressedDispatcher.onBackPressed() },
                        onExport = {
                            Toast.makeText(context, if (isArabicState) "جاري التصدير..." else "Exporting...", Toast.LENGTH_SHORT).show()
                        },
                        onRefresh = {
                            isRefreshingState = true
                            fetchActivities()
                        }
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    ActivitySearchBar(
                        query = searchQueryState,
                        placeholder = if (isArabicState) "ابحث في العمليات..." else "Search activities...",
                        onQueryChange = { searchQueryState = it }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    ActivityFilterChips(
                        isArabic = isArabicState,
                        selected = selectedFilterState,
                        onSelected = { selectedFilterState = it },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        when {
                            isLoadingState -> ActivityLoadingState()
                            isErrorState -> ActivityErrorState(
                                isArabic = isArabicState,
                                message = errorMessageState,
                                onRetry = { fetchActivities() }
                            )
                            filteredList.isEmpty() -> ActivityEmptyState(
                                isArabic = isArabicState,
                                icon = "📭",
                                title = if (isArabicState) "لا توجد عمليات" else "No activities found",
                                subtitle = if (isArabicState) "لم يتم العثور على أي عمليات تطابق بحثك" else "No activities match your criteria"
                            )
                            else -> {
                                LazyColumn(
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(filteredList, key = { it.optString("id", it.hashCode().toString()) }) { item ->
                                        ActivityListItem(
                                            activity = item,
                                            isArabic = isArabicState,
                                            onClick = { showDetailsBottomSheet(item) }
                                        )
                                    }
                                    item {
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

    private fun showDetailsBottomSheet(activity: JSONObject) {
        try {
            val sheet = ActivityDetailsBottomSheet.newInstance(activity, isArabicState)
            sheet.show(parentFragmentManager, "ActivityDetails")
        } catch (e: Exception) {
            Log.e("MiraAllActivities", "Show sheet error", e)
        }
    }

    private fun setupBackPress() {
        try {
            requireActivity().getOnBackPressedDispatcher().addCallback(
                getViewLifecycleOwner(),
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        try {
                            parentFragmentManager.popBackStack()
                        } catch (e: Exception) {
                            requireActivity().finish()
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("MiraAllActivities", "Back press error", e)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            localExecutor?.shutdownNow()
        } catch (_: Exception) {}
    }
}
