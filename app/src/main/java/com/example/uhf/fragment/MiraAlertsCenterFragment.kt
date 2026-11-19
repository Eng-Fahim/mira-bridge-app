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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.api.MiraApiClient
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MiraAlertsCenterFragment : Fragment() {

    companion object {
        private const val TAG = "MiraAlertsCenter"
        private const val ARG_INITIAL_ALERTS = "initial_alerts_json"
        private const val ARG_IS_ARABIC = "is_arabic"
        private const val PREFS_RESOLVED_ALERTS = "mira_resolved_alerts_v1"

        fun newInstance(initialAlertsJson: String?, isArabic: Boolean): MiraAlertsCenterFragment {
            return MiraAlertsCenterFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_INITIAL_ALERTS, initialAlertsJson)
                    putBoolean(ARG_IS_ARABIC, isArabic)
                }
            }
        }
    }

    data class SystemAlertItem(
        val id: String,
        val title: String,
        val description: String,
        val category: String,
        val severity: String,
        val referenceCode: String,
        val createdAt: String,
        val isResolved: Boolean = false,
        val resolutionNote: String = ""
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var isArabic = true

    private var alertsState by mutableStateOf<List<SystemAlertItem>>(emptyList())
    private var selectedCategoryState by mutableStateOf("all")
    private var showResolvedState by mutableStateOf(false)
    private var isLoadingState by mutableStateOf(false)
    private var alertBeingResolvedState by mutableStateOf<SystemAlertItem?>(null)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        isArabic = arguments?.getBoolean(ARG_IS_ARABIC, true) ?: true
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val dir = if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides dir) {
                    MiraJewelryTheme {
                        AlertsCenterScreen()
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        parseInitialAlerts(arguments?.getString(ARG_INITIAL_ALERTS))
        fetchLiveAlerts()
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroyView()
    }

    private fun getResolvedMap(): MutableMap<String, String> {
        val prefs = context?.getSharedPreferences(PREFS_RESOLVED_ALERTS, Context.MODE_PRIVATE)
            ?: return mutableMapOf()
        val map = mutableMapOf<String, String>()
        prefs.all.forEach { (k, v) ->
            if (v is String) map[k] = v
        }
        return map
    }

    private fun saveResolvedAlertLocally(alertId: String, note: String) {
        context?.getSharedPreferences(PREFS_RESOLVED_ALERTS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(alertId, note)
            ?.apply()
    }

    private fun parseInitialAlerts(rawJson: String?) {
        if (rawJson.isNullOrBlank()) return
        try {
            val arr = JSONArray(rawJson)
            val resolvedMap = getResolvedMap()
            val list = mutableListOf<SystemAlertItem>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { obj ->
                    list.add(mapJsonToAlert(obj, i, resolvedMap))
                }
            }
            if (list.isNotEmpty()) {
                alertsState = list
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseInitialAlerts error: ${e.message}")
        }
    }

    private fun fetchLiveAlerts() {
        val ctx = context ?: return
        isLoadingState = true
        val branchId = MiraBranchesManager.getInstance(ctx).activeBranchId.takeIf { it > 0 } ?: 1
        val resolvedMap = getResolvedMap()

        executor.execute {
            val collected = mutableListOf<SystemAlertItem>()
            try {
                val api = MiraApiClient.getInstance(ctx)

                // استدعاء getBranchAlerts إن وُجدت في MiraApiClient، وإلا الرجوع لملخص الداشبورد
                val alertsResp = invokeGetBranchAlerts(api, branchId)
                if (alertsResp != null && alertsResp.isSuccess) {
                    val root: JSONObject = alertsResp.data
                    val data = root.optJSONObject("data") ?: root
                    val itemsArr = data.optJSONArray("alerts") ?: data.optJSONArray("items")
                    if (itemsArr != null) {
                        for (i in 0 until itemsArr.length()) {
                            itemsArr.optJSONObject(i)?.let {
                                collected.add(mapJsonToAlert(it, i, resolvedMap))
                            }
                        }
                    }
                }

                if (collected.isEmpty()) {
                    val dashResp = api.getDashboardSummarySmart(branchId)
                    if (dashResp != null && dashResp.isSuccess) {
                        val root: JSONObject = dashResp.data
                        val data = root.optJSONObject("data") ?: root
                        val alertsObj = data.optJSONObject("alerts")
                        val itemsArr = alertsObj?.optJSONArray("items")
                            ?: data.optJSONArray("alerts_list")
                            ?: data.optJSONArray("notifications")

                        if (itemsArr != null) {
                            for (i in 0 until itemsArr.length()) {
                                itemsArr.optJSONObject(i)?.let {
                                    collected.add(mapJsonToAlert(it, i, resolvedMap))
                                }
                            }
                        }

                        val reportedCount = alertsObj?.optInt("count", 0) ?: data.optInt("alerts_count", 0)
                        if (collected.isEmpty() && reportedCount > 0) {
                            val msg = alertsObj?.optString("latest_message", "")?.takeIf { it.isNotBlank() }
                                ?: (if (isArabic) "توجد $reportedCount تنبيهات تشغيلية وأمنية تتطلب المراجعة في الفرع"
                                else "$reportedCount operational/security alerts require review")
                            val id = "summary_alert_${branchId}_$reportedCount"
                            collected.add(
                                SystemAlertItem(
                                    id = id,
                                    title = if (isArabic) "تنبيهات الفرع المعلّقة ($reportedCount)"
                                    else "Pending Branch Alerts ($reportedCount)",
                                    description = msg,
                                    category = "system",
                                    severity = "warning",
                                    referenceCode = "BR-$branchId",
                                    createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()),
                                    isResolved = resolvedMap.containsKey(id),
                                    resolutionNote = resolvedMap[id] ?: ""
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchLiveAlerts error: ${e.message}")
            }

            mainHandler.post {
                if (isAdded) {
                    isLoadingState = false
                    if (collected.isNotEmpty()) {
                        alertsState = collected
                    }
                }
            }
        }
    }

    private fun invokeGetBranchAlerts(api: MiraApiClient, branchId: Int): MiraApiClient.ApiResponse? {
        return try {
            val m = api.javaClass.methods.firstOrNull {
                it.name == "getBranchAlerts" && it.parameterCount == 2
            } ?: return null
            m.invoke(api, branchId, true) as? MiraApiClient.ApiResponse
        } catch (_: Exception) {
            null
        }
    }

    private fun invokeResolveAlert(
        api: MiraApiClient,
        branchId: Int,
        alertId: String,
        note: String,
        resolveAll: Boolean
    ) {
        try {
            val m = api.javaClass.methods.firstOrNull {
                it.name == "resolveAlert" && it.parameterCount == 4
            }
            if (m != null) {
                m.invoke(api, branchId, alertId, note, resolveAll)
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveAlert remote sync skipped: ${e.message}")
        }
    }

    private fun mapJsonToAlert(obj: JSONObject, index: Int, resolvedMap: Map<String, String>): SystemAlertItem {
        val id = obj.optString("id", "").ifBlank {
            obj.optString("alert_id", "alert_${index}_${obj.optString("title", "").hashCode()}")
        }
        val title = obj.optString("title", obj.optString("subject", if (isArabic) "تنبيه نظام" else "System Alert"))
        val desc = obj.optString("message", obj.optString("description", obj.optString("content", "")))
        val rawType = obj.optString("type", obj.optString("category", "system")).lowercase(Locale.ROOT)
        val category = when {
            rawType.contains("gate") || rawType.contains("security") || rawType.contains("rfid") -> "security"
            rawType.contains("inv") || rawType.contains("stock") || rawType.contains("missing") -> "inventory"
            rawType.contains("trans") -> "transfers"
            rawType.contains("gold") || rawType.contains("rate") -> "rates"
            else -> "system"
        }
        val severity = obj.optString("severity", obj.optString("level", "warning")).lowercase(Locale.ROOT)
        val ref = obj.optString("reference", obj.optString("reference_code", obj.optString("serial_number", "")))
        val createdAt = obj.optString("created_at", obj.optString("timestamp", ""))

        val serverResolved = obj.optBoolean("is_resolved", false) || obj.optString("status") == "resolved"
        val localNote = resolvedMap[id]
        return SystemAlertItem(
            id = id,
            title = title,
            description = desc,
            category = category,
            severity = severity,
            referenceCode = ref,
            createdAt = createdAt,
            isResolved = serverResolved || localNote != null,
            resolutionNote = localNote ?: obj.optString("resolution_note", "")
        )
    }

    private fun resolveAlert(alert: SystemAlertItem, note: String) {
        val finalNote = note.ifBlank {
            if (isArabic) "تمت المراجعة والمعالجة بواسطة الإدارة" else "Reviewed and resolved by management"
        }
        saveResolvedAlertLocally(alert.id, finalNote)

        alertsState = alertsState.map {
            if (it.id == alert.id) it.copy(isResolved = true, resolutionNote = finalNote) else it
        }

        val ctx = context ?: return
        val branchId = MiraBranchesManager.getInstance(ctx).activeBranchId.takeIf { it > 0 } ?: 1

        executor.execute {
            val api = MiraApiClient.getInstance(ctx)
            invokeResolveAlert(api, branchId, alert.id, finalNote, false)
        }

        Toast.makeText(
            ctx,
            if (isArabic) "✅ تم حل التنبيه وتوثيقه" else "✅ Alert resolved & logged",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun resolveAllPending() {
        val defaultNote = if (isArabic) "تم اعتماد وحل جميع التنبيهات دفعة واحدة" else "Batch resolved all alerts"
        alertsState = alertsState.map {
            if (!it.isResolved) {
                saveResolvedAlertLocally(it.id, defaultNote)
                it.copy(isResolved = true, resolutionNote = defaultNote)
            } else it
        }

        val ctx = context ?: return
        val branchId = MiraBranchesManager.getInstance(ctx).activeBranchId.takeIf { it > 0 } ?: 1

        executor.execute {
            val api = MiraApiClient.getInstance(ctx)
            invokeResolveAlert(api, branchId, "", defaultNote, true)
        }

        Toast.makeText(
            ctx,
            if (isArabic) "✅ تم حل جميع التنبيهات المعلّقة" else "✅ All pending alerts resolved",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun resolveFragmentByCandidates(vararg classNames: String, fallback: () -> Fragment): Fragment {
        for (name in classNames) {
            try {
                val clazz = Class.forName(name)
                val instance = clazz.getDeclaredConstructor().newInstance()
                if (instance is Fragment) return instance
            } catch (_: Exception) {
            }
        }
        return fallback()
    }

    private fun navigateToAlertSource(alert: SystemAlertItem) {
        val host = activity as? UHFMainActivity ?: return
        when (alert.category) {
            "security" -> {
                val frag = resolveFragmentByCandidates(
                    "com.example.uhf.fragment.MiraSecureGateFragment",
                    "com.example.uhf.fragment.MiraGatesFragment",
                    "com.example.uhf.fragment.MiraGateControlFragment"
                ) { MiraAllActivitiesFragment() }
                host.loadFragment(frag, "Gates")
            }
            "inventory" -> {
                val frag = resolveFragmentByCandidates(
                    "com.example.uhf.fragment.MiraInventoryDashboardFragment",
                    "com.example.uhf.fragment.MiraInventoryProFragment"
                ) { MiraAllActivitiesFragment() }
                host.loadFragment(frag, "MiraInventory")
            }
            "transfers" -> {
                val frag = resolveFragmentByCandidates(
                    "com.example.uhf.fragment.MiraTransferFragment"
                ) { MiraAllActivitiesFragment() }
                host.loadFragment(frag, "Transfers")
            }
            "rates" -> host.loadFragment(MiraGoldCurrencyFragment(), "GoldRates")
            else -> host.loadFragment(MiraAllActivitiesFragment(), "AllActivities")
        }
    }

    @Composable
    private fun AlertsCenterScreen() {
        val colors = MiraTheme.colors
        val pendingCount = remember(alertsState) { alertsState.count { !it.isResolved } }

        val filteredList = remember(alertsState, selectedCategoryState, showResolvedState) {
            alertsState.filter { item ->
                val matchesStatus = if (showResolvedState) item.isResolved else !item.isResolved
                val matchesCat = selectedCategoryState == "all" || item.category == selectedCategoryState
                matchesStatus && matchesCat
            }
        }

        Surface(modifier = Modifier.fillMaxSize(), color = colors.backgroundTop) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(listOf(colors.backgroundTop, colors.backgroundMid))
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        IconButton(
                            onClick = { parentFragmentManager.popBackStack() },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(colors.cardSurface)
                        ) {
                            Icon(
                                imageVector = if (isArabic) Icons.AutoMirrored.Rounded.ArrowForward
                                else Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = colors.goldPrimary
                            )
                        }
                        Column {
                            Text(
                                text = if (isArabic) "مركز التنبيهات والمعالجة" else "Alerts & Resolution Center",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = colors.textPrimary
                            )
                            Text(
                                text = if (isArabic) "بانتظار المراجعة: $pendingCount تنبيه"
                                else "Pending review: $pendingCount alerts",
                                fontSize = 12.sp,
                                color = if (pendingCount > 0) Color(0xFFF59E0B) else Color(0xFF10B981)
                            )
                        }
                    }

                    if (pendingCount > 0) {
                        TextButton(onClick = { resolveAllPending() }) {
                            Text(
                                text = if (isArabic) "حل الكل" else "Resolve All",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusTabChip(
                        label = if (isArabic) "تتطلب حلاً ($pendingCount)" else "Action Required ($pendingCount)",
                        selected = !showResolvedState,
                        accent = Color(0xFFF59E0B),
                        modifier = Modifier.weight(1f),
                        onClick = { showResolvedState = false }
                    )
                    StatusTabChip(
                        label = if (isArabic) "تم حلّها (${alertsState.count { it.isResolved }})"
                        else "Resolved (${alertsState.count { it.isResolved }})",
                        selected = showResolvedState,
                        accent = Color(0xFF10B981),
                        modifier = Modifier.weight(1f),
                        onClick = { showResolvedState = true }
                    )
                }

                val categories = listOf(
                    "all" to (if (isArabic) "الكل" else "All"),
                    "security" to (if (isArabic) "الأمن والبوابات" else "Security"),
                    "inventory" to (if (isArabic) "الجرد والمخزون" else "Inventory"),
                    "transfers" to (if (isArabic) "التحويلات" else "Transfers"),
                    "rates" to (if (isArabic) "الأسعار" else "Rates")
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categories) { (key, title) ->
                        val sel = selectedCategoryState == key
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (sel) colors.goldPrimary.copy(alpha = 0.2f) else colors.cardSurface,
                            border = BorderStroke(1.dp, if (sel) colors.goldPrimary else colors.cardBorder),
                            modifier = Modifier.clickable { selectedCategoryState = key }
                        ) {
                            Text(
                                text = title,
                                fontSize = 12.sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                color = if (sel) colors.goldPrimary else colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                if (isLoadingState && alertsState.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.goldPrimary)
                    }
                } else if (filteredList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Rounded.VerifiedUser,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(54.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = if (isArabic) "لا توجد تنبيهات في هذا القسم" else "No alerts in this section",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textSecondary
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredList, key = { it.id }) { alert ->
                            AlertActionCard(
                                alert = alert,
                                isArabic = isArabic,
                                onResolveClick = { alertBeingResolvedState = alert },
                                onInspectClick = { navigateToAlertSource(alert) }
                            )
                        }
                    }
                }
            }
        }

        alertBeingResolvedState?.let { targetAlert ->
            ResolveAlertDialog(
                alert = targetAlert,
                isArabic = isArabic,
                onDismiss = { alertBeingResolvedState = null },
                onConfirmResolve = { note ->
                    resolveAlert(targetAlert, note)
                    alertBeingResolvedState = null
                }
            )
        }
    }

    @Composable
    private fun StatusTabChip(
        label: String,
        selected: Boolean,
        accent: Color,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (selected) accent.copy(alpha = 0.18f) else MiraTheme.colors.cardSurface,
            border = BorderStroke(1.dp, if (selected) accent else MiraTheme.colors.cardBorder),
            modifier = modifier.clickable { onClick() }
        ) {
            Box(
                modifier = Modifier.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) accent else MiraTheme.colors.textSecondary
                )
            }
        }
    }

    @Composable
    private fun AlertActionCard(
        alert: SystemAlertItem,
        isArabic: Boolean,
        onResolveClick: () -> Unit,
        onInspectClick: () -> Unit
    ) {
        val colors = MiraTheme.colors
        val severityColor = when {
            alert.isResolved -> Color(0xFF10B981)
            alert.severity == "critical" -> Color(0xFFEF4444)
            else -> Color(0xFFF59E0B)
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, severityColor.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (alert.isResolved) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = severityColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = alert.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.textPrimary
                        )
                    }
                    if (alert.referenceCode.isNotBlank()) {
                        Text(
                            text = alert.referenceCode,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.goldPrimary
                        )
                    }
                }

                if (alert.description.isNotBlank()) {
                    Text(
                        text = alert.description,
                        fontSize = 12.5.sp,
                        color = colors.textSecondary,
                        lineHeight = 18.sp
                    )
                }

                if (alert.isResolved && alert.resolutionNote.isNotBlank()) {
                    Text(
                        text = (if (isArabic) "إجراء الحل: " else "Resolution: ") + alert.resolutionNote,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10B981)
                    )
                }

                if (!alert.isResolved) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onResolveClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF10B981),
                                contentColor = Color(0xFF050914)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.TaskAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isArabic) "حل التنبيه" else "Resolve",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = onInspectClick,
                            border = BorderStroke(1.dp, colors.goldPrimary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (isArabic) "فحص المصدر" else "Inspect",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.goldPrimary
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ResolveAlertDialog(
        alert: SystemAlertItem,
        isArabic: Boolean,
        onDismiss: () -> Unit,
        onConfirmResolve: (String) -> Unit
    ) {
        var noteText by remember { mutableStateOf("") }
        val quickTemplates = if (isArabic) {
            listOf("تمت المطابقة والتحقق ميدانياً", "تم تصحيح البيانات في النظام", "تنبيه تجريبي — تم الإغلاق")
        } else {
            listOf("Verified & matched physically", "Data corrected in system", "Test alert — closed")
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = MiraTheme.colors.cardSurface,
            title = {
                Text(
                    text = if (isArabic) "توثيق حل التنبيه" else "Resolve Alert",
                    fontWeight = FontWeight.Bold,
                    color = MiraTheme.colors.textPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = alert.title,
                        fontSize = 13.sp,
                        color = MiraTheme.colors.goldPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    quickTemplates.forEach { tpl ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MiraTheme.colors.backgroundTop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { noteText = tpl }
                        ) {
                            Text(
                                text = tpl,
                                fontSize = 11.5.sp,
                                color = MiraTheme.colors.textSecondary,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        placeholder = {
                            Text(
                                text = if (isArabic) "اكتب ملاحظة المعالجة..." else "Enter resolution note...",
                                fontSize = 12.sp
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onConfirmResolve(noteText) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                ) {
                    Text(
                        text = if (isArabic) "تأكيد الحل" else "Confirm",
                        color = Color(0xFF050914),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(text = if (isArabic) "إلغاء" else "Cancel", color = MiraTheme.colors.textSecondary)
                }
            }
        )
    }
}
