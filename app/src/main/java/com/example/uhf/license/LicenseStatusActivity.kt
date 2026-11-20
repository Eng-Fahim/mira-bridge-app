package com.example.uhf.license

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class LicenseStatusActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LicenseStatus"
        private const val AUTO_REFRESH_INTERVAL_MS = 30_000L
    }

    private enum class StatusKind {
        LOADING,
        VALID,
        WARNING_EXPIRED,
        ERROR_TAMPERED,
        ERROR_INVALID,
        ERROR_CONNECTION
    }

    private data class LicenseUiState(
        val statusKind: StatusKind = StatusKind.LOADING,
        val title: String = "",
        val subtitle: String = "",
        val usersText: String = "—",
        val devicesText: String = "—",
        val typeText: String = "—",
        val daysText: String = "—",
        val daysWarning: Boolean = false,
        val validUntilText: String = "—",
        val technicalDetails: String = "",
        val isFromCache: Boolean = false
    )

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            Log.d(TAG, "Auto-refresh tick")
            loadLicenseStatus(showToastOnInvalid = false)
            refreshHandler.postDelayed(this, AUTO_REFRESH_INTERVAL_MS)
        }
    }

    private var isArabic: Boolean = true
    private var uiState by mutableStateOf(LicenseUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar") ||
            resources.configuration.locales[0].language == "ar"

        uiState = LicenseUiState(
            statusKind = StatusKind.LOADING,
            title = if (isArabic) "جارٍ التحقق..." else "Verifying...",
            subtitle = if (isArabic) "يتم الاتصال بالخادم" else "Connecting to server"
        )

        setContent {
            MiraJewelryTheme {
                val dir = if (isArabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalLayoutDirection provides
                        if (dir == View.LAYOUT_DIRECTION_RTL) androidx.compose.ui.unit.LayoutDirection.Rtl
                        else androidx.compose.ui.unit.LayoutDirection.Ltr
                ) {
                    LicenseStatusScreen(
                        state = uiState,
                        isArabic = isArabic,
                        onRefresh = { loadLicenseStatus(showToastOnInvalid = true) },
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume — starting auto-refresh")
        loadLicenseStatus(showToastOnInvalid = false)
        refreshHandler.removeCallbacks(autoRefreshRunnable)
        refreshHandler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause — stopping auto-refresh")
        refreshHandler.removeCallbacks(autoRefreshRunnable)
    }

    override fun onDestroy() {
        refreshHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun loadLicenseStatus(showToastOnInvalid: Boolean) {
        uiState = uiState.copy(
            statusKind = StatusKind.LOADING,
            title = if (isArabic) "جارٍ التحقق..." else "Verifying...",
            subtitle = if (isArabic) "يتم الاتصال بالخادم" else "Connecting to server"
        )

        Thread({
            try {
                val fetch = MiraBlobFetcher.fetch(this)
                if (!fetch.success) {
                    val cached = MiraBlobStorage.load(this)
                    runOnUiThread {
                        uiState = buildConnectionErrorState(fetch.code, fetch.message, cached)
                    }
                    return@Thread
                }

                val data: MiraBlobStorage.BlobData? = fetch.blobData
                val current: MiraBlobStorage.BlobData? = MiraBlobStorage.load(this)
                val verify = MiraBlobVerifier.verifyAndValidate(data, current)

                if (verify.valid && data != null) {
                    MiraBlobStorage.save(this, data)
                }

                runOnUiThread {
                    if (verify.valid && data != null) {
                        uiState = buildValidState(data)
                    } else {
                        uiState = buildInvalidState(verify.code, verify.message, data)
                        if (showToastOnInvalid && !verify.message.isNullOrBlank()) {
                            Toast.makeText(this, verify.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadLicenseStatus error", e)
                val cached = MiraBlobStorage.load(this)
                runOnUiThread {
                    uiState = buildConnectionErrorState(
                        "exception",
                        e.message ?: if (isArabic) "خطأ غير متوقع" else "Unexpected error",
                        cached
                    )
                }
            }
        }, "LicenseStatusLoader").start()
    }

    private fun buildValidState(data: MiraBlobStorage.BlobData): LicenseUiState {
        val daysLeft = calculateDaysLeft(data.validUntil)
        return LicenseUiState(
            statusKind = StatusKind.VALID,
            title = if (isArabic) "الترخيص سليم" else "License Valid",
            subtitle = if (isArabic) "جميع الفحوصات ناجحة" else "All checks passed",
            usersText = formatQuota(data.activeUsers, data.maxUsers),
            devicesText = formatQuota(data.activeDevices, data.maxDevices),
            typeText = formatLicenseType(data),
            daysText = daysLeft.coerceAtLeast(0).toString(),
            daysWarning = daysLeft <= 30,
            validUntilText = formatDate(data.validUntil),
            technicalDetails = buildTechnicalDetails(data, extraNote = null),
            isFromCache = false
        )
    }

    private fun buildInvalidState(
        code: String?,
        message: String?,
        data: MiraBlobStorage.BlobData?
    ): LicenseUiState {
        val isExpired = "expired" == code
        val isTampered = "invalid_signature" == code ||
            "fingerprint_mismatch" == code ||
            "version_rollback" == code

        val (kind, title, subtitle) = when {
            isExpired -> Triple(
                StatusKind.WARNING_EXPIRED,
                if (isArabic) "انتهى الترخيص" else "License Expired",
                if (isArabic) "تواصل مع ميرا للتجديد" else "Contact MIRA to renew"
            )
            isTampered -> Triple(
                StatusKind.ERROR_TAMPERED,
                if (isArabic) "ترخيص مُعدَّل" else "License Tampered",
                if (isArabic) "التوقيع الرقمي غير صحيح — قد يكون الملف مُعدَّلاً"
                else "Invalid signature — file may be tampered"
            )
            else -> Triple(
                StatusKind.ERROR_INVALID,
                if (isArabic) "ترخيص غير صالح" else "License Invalid",
                message ?: if (isArabic) "فشل التحقق من الترخيص" else "License validation failed"
            )
        }

        val daysLeft = data?.let { calculateDaysLeft(it.validUntil) } ?: 0
        val extra = if (data != null) {
            "\n\n❌ ${if (isArabic) "سبب الرفض" else "Reason"}: ${code ?: "—"} — ${message ?: ""}"
        } else {
            "Error: ${code ?: "—"} — ${message ?: ""}"
        }

        return LicenseUiState(
            statusKind = kind,
            title = title,
            subtitle = subtitle,
            usersText = data?.let { formatQuota(it.activeUsers, it.maxUsers) } ?: "—",
            devicesText = data?.let { formatQuota(it.activeDevices, it.maxDevices) } ?: "—",
            typeText = data?.let { formatLicenseType(it) } ?: "—",
            daysText = if (data != null) daysLeft.coerceAtLeast(0).toString() else "—",
            daysWarning = data != null && daysLeft <= 30,
            validUntilText = data?.let { formatDate(it.validUntil) } ?: "—",
            technicalDetails = data?.let { buildTechnicalDetails(it, extra) } ?: extra,
            isFromCache = false
        )
    }

    private fun buildConnectionErrorState(
        code: String?,
        message: String?,
        cached: MiraBlobStorage.BlobData?
    ): LicenseUiState {
        val friendlyMessage = when (code) {
            "dns", "network" -> if (isArabic) "تحقق من اتصالك بالإنترنت" else "Check your internet connection"
            "timeout" -> if (isArabic) "انتهت مهلة الاتصال — حاول مرة أخرى" else "Connection timed out — try again"
            "ssl" -> if (isArabic) "فشل الاتصال الآمن" else "Secure connection failed"
            else -> if (isArabic) "تعذر الاتصال بالخادم" else "Could not connect to server"
        }

        val daysLeft = cached?.let { calculateDaysLeft(it.validUntil) } ?: 0
        val tech = buildString {
            append("Error: ${code ?: "—"}\n${message ?: ""}")
            if (cached != null) {
                append("\n\n")
                append(buildTechnicalDetails(cached, extraNote = null))
            }
        }

        return LicenseUiState(
            statusKind = StatusKind.ERROR_CONNECTION,
            title = if (isArabic) "فشل الاتصال" else "Connection Failed",
            subtitle = friendlyMessage,
            usersText = cached?.let { formatQuota(it.activeUsers, it.maxUsers) } ?: "—",
            devicesText = cached?.let { formatQuota(it.activeDevices, it.maxDevices) } ?: "—",
            typeText = cached?.let { formatLicenseType(it) } ?: "—",
            daysText = if (cached != null) daysLeft.coerceAtLeast(0).toString() else "—",
            daysWarning = cached != null && daysLeft <= 30,
            validUntilText = cached?.let { formatDate(it.validUntil) } ?: "—",
            technicalDetails = tech,
            isFromCache = cached != null
        )
    }

    private fun formatQuota(active: Int, max: Int): String {
        return when {
            active >= 0 && max > 0 -> "$active / $max"
            max > 0 -> "— / $max"
            else -> "—"
        }
    }

    private fun formatLicenseType(data: MiraBlobStorage.BlobData): String {
        return try {
            val obj = JSONObject(data.blobJson)
            when (obj.optString("license_type", "custom").lowercase(Locale.ROOT)) {
                "trial" -> if (isArabic) "تجريبي" else "Trial"
                "starter" -> if (isArabic) "أساسي" else "Starter"
                "professional" -> if (isArabic) "احترافي" else "Professional"
                "enterprise" -> if (isArabic) "مؤسسي" else "Enterprise"
                "founder" -> if (isArabic) "مؤسس" else "Founder"
                else -> if (isArabic) "مخصص" else "Custom"
            }
        } catch (_: Exception) {
            "—"
        }
    }

    private fun calculateDaysLeft(validUntil: String?): Int {
        if (validUntil.isNullOrBlank()) return 0
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val expiry = sdf.parse(validUntil) ?: return 0
            val diffMs = expiry.time - System.currentTimeMillis()
            TimeUnit.MILLISECONDS.toDays(diffMs).toInt()
        } catch (_: Exception) {
            0
        }
    }

    private fun formatDate(date: String?): String {
        if (date.isNullOrBlank()) return "—"
        return try {
            val input = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val output = SimpleDateFormat(
                if (isArabic) "dd MMMM yyyy" else "MMM dd, yyyy",
                Locale(if (isArabic) "ar" else "en")
            )
            val parsed = input.parse(date)
            parsed?.let { output.format(it) } ?: date
        } catch (_: Exception) {
            date
        }
    }

    private fun buildTechnicalDetails(data: MiraBlobStorage.BlobData, extraNote: String?): String {
        val storedFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Date(data.storedAt))
        return buildString {
            append("License ID:  #${data.licenseId}\n")
            append("Blob Version: v${data.version}\n")
            append("Key ID:       ${data.keyId}\n")
            append("Fingerprint:  ${truncate(data.fingerprintB64, 24)}\n")
            append("Signature:    ${truncate(data.signatureB64, 24)}\n")
            append("Stored At:    $storedFormatted")
            if (!extraNote.isNullOrBlank()) append(extraNote)
        }
    }

    private fun truncate(s: String?, max: Int): String {
        if (s.isNullOrEmpty()) return "—"
        return if (s.length > max) s.substring(0, max) + "…" else s
    }

    @Composable
    private fun LicenseStatusScreen(
        state: LicenseUiState,
        isArabic: Boolean,
        onRefresh: () -> Unit,
        onClose: () -> Unit
    ) {
        val colors = MiraTheme.colors
        var detailsExpanded by remember { mutableStateOf(false) }

        val refreshAngle by animateFloatAsState(
            targetValue = if (state.statusKind == StatusKind.LOADING) 360f else 0f,
            animationSpec = spring(stiffness = Spring.StiffnessVeryLow),
            label = "refreshSpin"
        )

        val (bannerBg, bannerBorder, accentColor, statusIcon) = when (state.statusKind) {
            StatusKind.LOADING -> Quad(
                Color(0xFF0E7490).copy(alpha = 0.24f),
                Color(0xFF06B6D4).copy(alpha = 0.65f),
                Color(0xFF22D3EE),
                Icons.Rounded.Sync
            )
            StatusKind.VALID -> Quad(
                Color(0xFF065F46).copy(alpha = 0.28f),
                Color(0xFF10B981).copy(alpha = 0.65f),
                Color(0xFF34D399),
                Icons.Rounded.Verified
            )
            StatusKind.WARNING_EXPIRED -> Quad(
                Color(0xFF92400E).copy(alpha = 0.30f),
                Color(0xFFF59E0B).copy(alpha = 0.70f),
                Color(0xFFFBBF24),
                Icons.Rounded.Schedule
            )
            StatusKind.ERROR_TAMPERED, StatusKind.ERROR_INVALID -> Quad(
                Color(0xFF7F1D1D).copy(alpha = 0.34f),
                Color(0xFFEF4444).copy(alpha = 0.75f),
                Color(0xFFF87171),
                Icons.Rounded.GppBad
            )
            StatusKind.ERROR_CONNECTION -> Quad(
                Color(0xFF78350F).copy(alpha = 0.28f),
                Color(0xFFF59E0B).copy(alpha = 0.60f),
                Color(0xFFFBBF24),
                Icons.Rounded.CloudOff
            )
        }

        val animatedBorder by animateColorAsState(targetValue = bannerBorder, label = "bannerBorder")

        Scaffold(
            containerColor = colors.backgroundTop,
            topBar = {
                Surface(
                    color = colors.cardSurface,
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(colors.backgroundMid)
                            ) {
                                Icon(
                                    imageVector = if (isArabic) Icons.Rounded.ArrowForward else Icons.Rounded.ArrowBack,
                                    contentDescription = null,
                                    tint = colors.textPrimary
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "MIRA License Center",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colors.goldPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (isArabic) "حالة الترخيص" else "License Status",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                            }
                        }

                        IconButton(
                            onClick = onRefresh,
                            enabled = state.statusKind != StatusKind.LOADING,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(colors.backgroundMid)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = colors.goldPrimary,
                                modifier = Modifier.rotate(if (state.statusKind == StatusKind.LOADING) refreshAngle else 0f)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Hero Status Banner
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = bannerBg,
                    border = BorderStroke(1.3.dp, animatedBorder)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        accentColor.copy(alpha = 0.14f),
                                        colors.cardSurface.copy(alpha = 0.90f)
                                    )
                                )
                            )
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (state.statusKind == StatusKind.LOADING) {
                                CircularProgressIndicator(
                                    color = accentColor,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(30.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = statusIcon,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        Text(
                            text = state.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = accentColor,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = state.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center
                        )

                        if (state.isFromCache) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF06B6D4).copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.45f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Storage,
                                        contentDescription = null,
                                        tint = Color(0xFF22D3EE),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = if (isArabic) "عرض نسخة محفوظة محلياً" else "Showing cached license",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF22D3EE)
                                    )
                                }
                            }
                        }
                    }
                }

                // 2x2 Metrics Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricTile(
                        title = if (isArabic) "المستخدمون" else "Users",
                        value = state.usersText,
                        icon = Icons.Rounded.Group,
                        accent = Color(0xFF38BDF8),
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        title = if (isArabic) "الأجهزة" else "Devices",
                        value = state.devicesText,
                        icon = Icons.Rounded.Devices,
                        accent = Color(0xFF06B6D4),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricTile(
                        title = if (isArabic) "نوع الترخيص" else "License Type",
                        value = state.typeText,
                        icon = Icons.Rounded.WorkspacePremium,
                        accent = colors.goldPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        title = if (isArabic) "يوم متبقٍ" else "Days Left",
                        value = state.daysText,
                        icon = Icons.Rounded.EventAvailable,
                        accent = if (state.daysWarning) Color(0xFFF59E0B) else Color(0xFF10B981),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Valid Until Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = colors.cardSurface,
                    border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.28f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(colors.goldPrimary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CalendarMonth,
                                    contentDescription = null,
                                    tint = colors.goldPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = if (isArabic) "صالح حتى" else "Valid Until",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.textSecondary
                            )
                        }

                        Text(
                            text = state.validUntilText,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.textPrimary
                        )
                    }
                }

                // Technical Details Expandable Section
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = colors.backgroundMid,
                    border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.20f))
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { detailsExpanded = !detailsExpanded }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Terminal,
                                    contentDescription = null,
                                    tint = colors.goldPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isArabic) "التفاصيل التقنية" else "Technical Details",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary
                                )
                            }
                            Icon(
                                imageVector = if (detailsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = colors.textSecondary
                            )
                        }

                        AnimatedVisibility(
                            visible = detailsExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = colors.backgroundTop
                            ) {
                                Text(
                                    text = state.technicalDetails.ifBlank { "—" },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = colors.textSecondary,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }

                // Action Buttons Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onRefresh,
                        enabled = state.statusKind != StatusKind.LOADING,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.goldPrimary,
                            contentColor = Color(0xFF0A0F1D)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isArabic) "تحديث" else "Refresh",
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    OutlinedButton(
                        onClick = onClose,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isArabic) "إغلاق" else "Close",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun MetricTile(
        title: String,
        value: String,
        icon: ImageVector,
        accent: Color,
        modifier: Modifier = Modifier
    ) {
        val colors = MiraTheme.colors
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            color = colors.cardSurface,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.30f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
