package com.example.uhf.ui.inventory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiraInventoryScreen(
    viewModel: MiraInventoryViewModel
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    MiraInventoryTheme(isArabic = state.isArabic) {
        Scaffold(
            containerColor = MiraPalette.NavyDeep,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                InventoryTopBar(
                    state = state,
                    onSelectCategory = viewModel::selectCategory,
                    onSyncOffline = viewModel::syncOfflineQueue,
                    onOpenArchive = viewModel::openArchive,
                    onReloadCatalog = { viewModel.loadCatalog(checkRestoreAfterLoad = false) }
                )
            },
            bottomBar = {
                ScannerControlDock(
                    state = state,
                    onToggleScan = viewModel::toggleScanning,
                    onPowerChange = viewModel::setAntennaPower,
                    onTriggerModeChange = viewModel::setTriggerMode,
                    onCommit = viewModel::commitCurrentInventory,
                    onReset = viewModel::resetCurrentAudit
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. تنبيه استرجاع الجلسة السابقة
                item(key = "restore_banner") {
                    AnimatedVisibility(visible = state.restoreBanner != null) {
                        state.restoreBanner?.let { banner ->
                            RestoreSessionCard(
                                isArabic = state.isArabic,
                                banner = banner,
                                onRestore = viewModel::restoreSavedSession,
                                onDismiss = viewModel::dismissRestoreBanner
                            )
                        }
                    }
                }

                // 2. شريط تحميل أو خطأ الكتالوج
                if (state.isCatalogLoading || state.catalogError != null) {
                    item(key = "catalog_status") {
                        CatalogStatusBanner(
                            isArabic = state.isArabic,
                            isLoading = state.isCatalogLoading,
                            error = state.catalogError,
                            onRetry = { viewModel.loadCatalog(checkRestoreAfterLoad = false) }
                        )
                    }
                }

                // 3. لوحة القياس والوزن وتفصيل العيارات
                item(key = "telemetry_card") {
                    TelemetryDashboardCard(
                        state = state,
                        onMetricClick = viewModel::selectFilterTab
                    )
                }

                // 4. شريط الفلترة والبحث الحي
                item(key = "filter_and_search") {
                    FilterAndSearchSection(
                        state = state,
                        onTabSelected = viewModel::selectFilterTab,
                        onQueryChanged = viewModel::updateSearchQuery
                    )
                }

                // 5. قائمة القطع الافتراضية عالية الأداء (Virtualized LazyColumn)
                if (state.visibleItems.isEmpty() && !state.isCatalogLoading) {
                    item(key = "empty_placeholder") {
                        EmptyInventoryPlaceholder(isArabic = state.isArabic, tab = state.activeTab)
                    }
                } else {
                    items(
                        items = state.visibleItems,
                        key = { it.epc }
                    ) { row ->
                        JewelryInventoryItemCard(
                            isArabic = state.isArabic,
                            item = row,
                            onLocateRadar = { viewModel.openRadarForItem(row) },
                            onVerifyTid = { viewModel.verifyTagTid(row) }
                        )
                    }
                }
            }
        }

        // نافذة رادار تتبع القطعة المفقودة (Geiger RSSI Radar)
        state.radarTarget?.let { target ->
            ModalBottomSheet(
                onDismissRequest = viewModel::closeRadar,
                containerColor = MiraPalette.NavySurface,
                contentColor = MiraPalette.TextPrimary
            ) {
                RssiRadarSheetContent(
                    isArabic = state.isArabic,
                    target = target,
                    onClose = viewModel::closeRadar
                )
            }
        }

        // نافذة نتيجة فحص أصالة TID
        state.tidVerificationResult?.let { (epc, resultText) ->
            AlertDialog(
                onDismissRequest = viewModel::dismissTidResult,
                containerColor = MiraPalette.NavyCard,
                title = {
                    Text(
                        text = if (state.isArabic) "فحص أصالة الشريحة (TID)" else "Chip TID Verification",
                        color = MiraPalette.GoldPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "EPC: $epc",
                            color = MiraPalette.TextSecondary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = resultText,
                            color = MiraPalette.TextPrimary,
                            fontSize = 14.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissTidResult) {
                        Text(
                            text = if (state.isArabic) "إغلاق" else "Close",
                            color = MiraPalette.GoldPrimary
                        )
                    }
                }
            )
        }

        // نافذة تقرير الاعتماد النهائي
        state.commitReport?.let { report ->
            CommitReportDialog(
                isArabic = state.isArabic,
                report = report,
                onDismiss = viewModel::dismissCommitReport
            )
        }

        // نافذة أرشيف جلسات الجرد السابقة
                if (state.showArchiveSheet) {
            val context = androidx.compose.ui.platform.LocalContext.current
            SessionReportExportActions(
            isArabic = state.isArabic,
            reportCode = state.lastCommittedReportDetails?.reportCode,
            onPrintPdf = { viewModel.printCurrentSessionReport(context) },
            onExportCsv = { viewModel.exportCurrentSessionReportCsv(context) }
            )

            ModalBottomSheet(
                onDismissRequest = {
                    viewModel.closeArchiveReportDetails()
                    viewModel.closeArchive()
                },
                containerColor = MiraPalette.NavySurface,
                contentColor = MiraPalette.TextPrimary
            ) {
                if (state.selectedArchiveReportId != null) {
                    ArchiveReportDetailsSheetContent(
                        isArabic = state.isArabic,
                        isLoading = state.isLoadingArchiveDetails,
                        errorMessage = state.archiveDetailsError,
                        details = state.archiveReportDetails,
                        onBack = viewModel::closeArchiveReportDetails,
                        onRetry = {
                            state.selectedArchiveReportId?.let { viewModel.openArchiveReportDetails(it) }
                        },
                        onPrintPdf = { viewModel.printSelectedArchiveReport(context) },
                        onExportCsv = { viewModel.exportSelectedArchiveReportCsv(context) }
                    )
                } else {
                    // قائمة التقارير المؤرشفة الحالية — عند النقر على أي تقرير استدعِ:
                    // viewModel.openArchiveReportDetails(
                    //     reportId = report.id,
                    //     fallbackCode = report.code,
                    //     fallbackDate = report.date,
                    //     fallbackBranch = report.branchName,
                    //     fallbackMatched = report.matchedCount,
                    //     fallbackExpected = report.expectedCount,
                    //     fallbackMissing = report.missingCount,
                    //     fallbackWeight = report.scannedWeight
                    // )
                    ArchiveReportsListContent(
                        state = state,
                        onSelectReport = { id, code, date, branch, matched, expected, missing, weight ->
                            viewModel.openArchiveReportDetails(
                                reportId = id,
                                fallbackCode = code,
                                fallbackDate = date,
                                fallbackBranch = branch,
                                fallbackMatched = matched,
                                fallbackExpected = expected,
                                fallbackMissing = missing,
                                fallbackWeight = weight
                            )
                        },
                        onClose = viewModel::closeArchive
                    )
                }
            }
        }

     // نافذة إعدادات القارئ والزناد (تُفتح من شريط التطبيق أو من UHFMainActivity)
        if (state.showScannerSettingsSheet) {
            ModalBottomSheet(
                onDismissRequest = viewModel::closeScannerSettingsSheet,
                containerColor = MiraPalette.NavySurface,
                contentColor = MiraPalette.TextPrimary
            ) {
                ScannerSettingsSheetContent(
                    state = state,
                    onPowerChange = viewModel::setAntennaPower,
                    onTriggerModeChange = viewModel::setTriggerMode,
                    onClose = viewModel::closeScannerSettingsSheet
                )
            }
        }

        // نافذة إعدادات جلسة الجرد والفئات (تُفتح من UHFMainActivity)
        if (state.showSessionSettingsSheet) {
            ModalBottomSheet(
                onDismissRequest = viewModel::closeSessionSettingsSheet,
                containerColor = MiraPalette.NavySurface,
                contentColor = MiraPalette.TextPrimary
            ) {
                SessionSettingsSheetContent(
                    state = state,
                    onSelectCategory = { cat ->
                        viewModel.selectCategory(cat)
                        viewModel.closeSessionSettingsSheet()
                    },
                    onSaveSnapshot = {
                        viewModel.saveCurrentSessionSnapshot()
                        viewModel.closeSessionSettingsSheet()
                    },
                    onResetAudit = {
                        viewModel.resetCurrentAudit()
                        viewModel.closeSessionSettingsSheet()
                    },
                    onClose = viewModel::closeSessionSettingsSheet
                )
            }
        }
        }
    }
}

// ==================== 1. شريط التطبيق العلوي ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InventoryTopBar(
    state: InventoryUiState,
    onSelectCategory: (String) -> Unit,
    onSyncOffline: () -> Unit,
    onOpenArchive: () -> Unit,
    onReloadCatalog: () -> Unit
) {
    var categoryMenuExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MiraPalette.NavySurface,
            titleContentColor = MiraPalette.TextPrimary
        ),
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (state.isArabic) "جرد المجوهرات الذكي" else "MIRA Smart Audit",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = MiraPalette.GoldPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(
                                if (state.isReaderConnected) MiraPalette.EmeraldMatched
                                else MiraPalette.CrimsonMissing
                            )
                    )
                }
                Text(
                    text = if (state.isArabic) "فرع #${state.branchId} • ${state.selectedCategory.ifBlank { "جرد شامل" }}"
                    else "Branch #${state.branchId} • ${state.selectedCategory.ifBlank { "Full Audit" }}",
                    fontSize = 11.sp,
                    color = MiraPalette.TextSecondary
                )
            }
        },
        actions = {
            // اختيار الفئة / العيار
            Box {
                IconButton(onClick = { categoryMenuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Rounded.FilterList,
                        contentDescription = "Category",
                        tint = if (state.selectedCategory.isNotEmpty()) MiraPalette.GoldPrimary else MiraPalette.TextSecondary
                    )
                }
                DropdownMenu(
                    expanded = categoryMenuExpanded,
                    onDismissRequest = { categoryMenuExpanded = false },
                    modifier = Modifier.background(MiraPalette.NavyCard)
                ) {
                    state.availableCategories.forEach { cat ->
                        val label = cat.ifBlank {
                            if (state.isArabic) "الكل (جرد شامل)" else "All Categories"
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = label,
                                    color = if (cat == state.selectedCategory) MiraPalette.GoldPrimary else MiraPalette.TextPrimary,
                                    fontWeight = if (cat == state.selectedCategory) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                categoryMenuExpanded = false
                                onSelectCategory(cat)
                            }
                        )
                    }
                }
            }

            IconButton(onClick = onReloadCatalog) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Reload Catalog",
                    tint = MiraPalette.TextSecondary
                )
            }

            IconButton(onClick = onSyncOffline, enabled = !state.isSyncingOffline) {
                Icon(
                    imageVector = Icons.Rounded.Sync,
                    contentDescription = "Sync Offline",
                    tint = MiraPalette.CyanExtra
                )
            }

            IconButton(onClick = onOpenArchive) {
                Icon(
                    imageVector = Icons.Rounded.History,
                    contentDescription = "Archive",
                    tint = MiraPalette.GoldLight
                )
            }
        }
    )
}

// ==================== 2. لوحة القياس وتفصيل العيارات ====================

@Composable
private fun TelemetryDashboardCard(
    state: InventoryUiState,
    onMetricClick: (InventoryFilterTab) -> Unit
) {
    val animatedProgress by animateFloatAsState(
        targetValue = state.accuracyPercent,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "accuracy_ring"
    )

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MiraPalette.NavyCard),
        border = BorderStroke(1.dp, MiraPalette.BorderSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // حلقة نسبة المطابقة
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(76.dp)) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawArc(
                            color = MiraPalette.NavyElevated,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = MiraPalette.GoldPrimary,
                            startAngle = -90f,
                            sweepAngle = 360f * animatedProgress,
                            useCenter = false,
                            style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${(animatedProgress * 100).toInt()}%",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            color = MiraPalette.GoldPrimary
                        )
                        Text(
                            text = if (state.isArabic) "مطابقة" else "Match",
                            fontSize = 10.sp,
                            color = MiraPalette.TextSecondary
                        )
                    }
                }

                // شبكة العدادات الأربعة التفاعلية
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MetricMiniPill(
                            modifier = Modifier.weight(1f),
                            label = if (state.isArabic) "المتوقع" else "Expected",
                            value = state.expectedCount.toString(),
                            accent = MiraPalette.TextPrimary,
                            onClick = { onMetricClick(InventoryFilterTab.ALL) }
                        )
                        MetricMiniPill(
                            modifier = Modifier.weight(1f),
                            label = if (state.isArabic) "المطابق" else "Matched",
                            value = state.matchedCount.toString(),
                            accent = MiraPalette.EmeraldMatched,
                            onClick = { onMetricClick(InventoryFilterTab.MATCHED) }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MetricMiniPill(
                            modifier = Modifier.weight(1f),
                            label = if (state.isArabic) "المفقود" else "Missing",
                            value = state.missingCount.toString(),
                            accent = MiraPalette.CrimsonMissing,
                            onClick = { onMetricClick(InventoryFilterTab.MISSING) }
                        )
                        MetricMiniPill(
                            modifier = Modifier.weight(1f),
                            label = if (state.isArabic) "زائد/مخالف" else "Extra/Alert",
                            value = (state.uncataloguedCount + state.wrongCategoryCount).toString(),
                            accent = MiraPalette.AmberWarning,
                            onClick = {
                                onMetricClick(
                                    if (state.wrongCategoryCount > 0) InventoryFilterTab.WRONG_CATEGORY
                                    else InventoryFilterTab.UNCATALOGUED
                                )
                            }
                        )
                    }
                }
            }

            HorizontalDivider(color = MiraPalette.BorderSubtle.copy(alpha = 0.6f))

            // شريط الأوزان والقيمة وسرعة القراءة
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (state.isArabic) "الوزن المقروء / المتوقع" else "Scanned / Expected Wt",
                        fontSize = 10.sp,
                        color = MiraPalette.TextSecondary
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "%.2fg / %.2fg",
                            state.scannedWeightGrams,
                            state.expectedWeightGrams
                        ),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MiraPalette.GoldLight
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (state.isArabic) "القيمة التقديرية" else "Est. Value",
                        fontSize = 10.sp,
                        color = MiraPalette.TextSecondary
                    )
                    Text(
                        text = String.format(Locale.US, "$%,.0f", state.scannedValueUsd),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MiraPalette.EmeraldMatched
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatDuration(state.elapsedSeconds),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MiraPalette.TextPrimary
                    )
                    Text(
                        text = "${state.readsPerSecond} tag/s",
                        fontSize = 10.sp,
                        color = if (state.isScanning) MiraPalette.CyanExtra else MiraPalette.TextMuted
                    )
                }
            }

            // تفصيل العيارات الأفقي (24K, 22K, 21K, 18K...)
            if (state.karatSummaries.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.karatSummaries, key = { it.karat }) { ks ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MiraPalette.NavyElevated,
                            border = BorderStroke(1.dp, MiraPalette.GoldDark.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = ks.karat,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 12.sp,
                                    color = MiraPalette.GoldPrimary
                                )
                                Text(
                                    text = "${ks.matchedCount}/${ks.expectedCount}",
                                    fontSize = 11.sp,
                                    color = MiraPalette.TextPrimary
                                )
                                Text(
                                    text = String.format(Locale.US, "(%.1fg)", ks.scannedWeightGrams),
                                    fontSize = 11.sp,
                                    color = MiraPalette.TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricMiniPill(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    accent: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = MiraPalette.NavyElevated
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, fontSize = 11.sp, color = MiraPalette.TextSecondary)
            Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accent)
        }
    }
}

// ==================== 3. شريط الفلترة والبحث ====================

@Composable
private fun FilterAndSearchSection(
    state: InventoryUiState,
    onTabSelected: (InventoryFilterTab) -> Unit,
    onQueryChanged: (String) -> Unit
) {
    val tabs = listOf(
        Triple(
            InventoryFilterTab.ALL,
            if (state.isArabic) "الكل" else "All",
            state.expectedCount + state.uncataloguedCount + state.wrongCategoryCount
        ),
        Triple(
            InventoryFilterTab.MATCHED,
            if (state.isArabic) "مطابق" else "Matched",
            state.matchedCount
        ),
        Triple(
            InventoryFilterTab.MISSING,
            if (state.isArabic) "مفقود" else "Missing",
            state.missingCount
        ),
        Triple(
            InventoryFilterTab.UNCATALOGUED,
            if (state.isArabic) "غير مسجل" else "Extra",
            state.uncataloguedCount
        ),
        Triple(
            InventoryFilterTab.WRONG_CATEGORY,
            if (state.isArabic) "خارج الفئة" else "Wrong Cat.",
            state.wrongCategoryCount
        )
    )

    val selectedIndex = tabs.indexOfFirst { it.first == state.activeTab }.coerceAtLeast(0)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScrollableTabRow(
            selectedTabIndex = selectedIndex,
            containerColor = MiraPalette.NavySurface,
            contentColor = MiraPalette.GoldPrimary,
            edgePadding = 4.dp,
            divider = {}
        ) {
            tabs.forEachIndexed { index, (tab, title, count) ->
                Tab(
                    selected = index == selectedIndex,
                    onClick = { onTabSelected(tab) },
                    text = {
                        Text(
                            text = "$title ($count)",
                            fontSize = 12.sp,
                            fontWeight = if (index == selectedIndex) FontWeight.Bold else FontWeight.Medium,
                            color = if (index == selectedIndex) MiraPalette.GoldPrimary else MiraPalette.TextSecondary
                        )
                    }
                )
            }
        }

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            placeholder = {
                Text(
                    text = if (state.isArabic) "بحث بالاسم، EPC، الباركود، أو الرقم التسلسلي..."
                    else "Search by title, EPC, GTIN, or serial...",
                    fontSize = 12.sp,
                    color = MiraPalette.TextMuted
                )
            },
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = MiraPalette.TextSecondary)
            },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onQueryChanged("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = null, tint = MiraPalette.TextSecondary)
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MiraPalette.GoldPrimary,
                unfocusedBorderColor = MiraPalette.BorderSubtle,
                focusedContainerColor = MiraPalette.NavySurface,
                unfocusedContainerColor = MiraPalette.NavySurface,
                focusedTextColor = MiraPalette.TextPrimary,
                unfocusedTextColor = MiraPalette.TextPrimary
            )
        )
    }
}

// ==================== 4. بطاقة القطعة الذهبية ====================

@Composable
private fun JewelryInventoryItemCard(
    isArabic: Boolean,
    item: InventoryDisplayRow,
    onLocateRadar: () -> Unit,
    onVerifyTid: () -> Unit
) {
    val (statusColor, statusBg, statusLabel) = when (item.status) {
        ItemAuditStatus.MATCHED -> Triple(
            MiraPalette.EmeraldMatched,
            MiraPalette.EmeraldContainer,
            if (isArabic) "مطابق" else "Matched"
        )
        ItemAuditStatus.MISSING -> Triple(
            MiraPalette.CrimsonMissing,
            MiraPalette.CrimsonContainer,
            if (isArabic) "مفقود" else "Missing"
        )
        ItemAuditStatus.UNCATALOGUED -> Triple(
            MiraPalette.CyanExtra,
            MiraPalette.CyanContainer,
            if (isArabic) "غير مسجل" else "Uncatalogued"
        )
        ItemAuditStatus.WRONG_CATEGORY -> Triple(
            MiraPalette.AmberWarning,
            MiraPalette.AmberContainer,
            if (isArabic) "خارج الفئة" else "Wrong Category"
        )
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MiraPalette.NavyCard),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MiraPalette.GoldContainer
                    ) {
                        Text(
                            text = item.karat,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = MiraPalette.GoldPrimary
                        )
                    }

                    Text(
                        text = item.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MiraPalette.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusBg
                ) {
                    Text(
                        text = statusLabel,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor
                    )
                }
            }

            // EPC + الوزن + السعر + عداد القراءات
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.epc,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MiraPalette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.weight > 0) {
                        Text(
                            text = String.format(Locale.US, "%.2fg", item.weight),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiraPalette.GoldLight
                        )
                    }
                    if (item.price > 0) {
                        Text(
                            text = String.format(Locale.US, "$%.0f", item.price),
                            fontSize = 12.sp,
                            color = MiraPalette.EmeraldMatched
                        )
                    }
                    if (item.readCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MiraPalette.NavyElevated
                        ) {
                            Text(
                                text = "×${item.readCount} • ${item.rssi}dBm",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                color = MiraPalette.TextPrimary
                            )
                        }
                    }
                }
            }

            // أزرار الإجراءات السريعة (تتبع بالرادار للمفقود / فحص TID)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onLocateRadar,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Radar,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (item.status == ItemAuditStatus.MISSING) MiraPalette.CrimsonMissing else MiraPalette.CyanExtra
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (isArabic) "تتبع بالرادار" else "Radar Find",
                        fontSize = 11.sp,
                        color = if (item.status == ItemAuditStatus.MISSING) MiraPalette.CrimsonMissing else MiraPalette.CyanExtra
                    )
                }

                Spacer(Modifier.width(6.dp))

                TextButton(
                    onClick = onVerifyTid,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Verified,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MiraPalette.GoldPrimary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (isArabic) "فحص TID" else "Verify TID",
                        fontSize = 11.sp,
                        color = MiraPalette.GoldPrimary
                    )
                }
            }
        }
    }
}

// ==================== 5. شريط التحكم السفلي (Scanner Dock) ====================

@Composable
private fun ScannerControlDock(
    state: InventoryUiState,
    onToggleScan: () -> Unit,
    onPowerChange: (Int) -> Unit,
    onTriggerModeChange: (TriggerMode) -> Unit,
    onCommit: () -> Unit,
    onReset: () -> Unit
) {
    var showPowerControls by remember { mutableStateOf(false) }

    Surface(
        color = MiraPalette.NavySurface,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, MiraPalette.BorderSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnimatedVisibility(visible = showPowerControls) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiraPalette.NavyCard)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (state.isArabic) "قوة إشارة القارئ (UHF Power)" else "UHF Antenna Power",
                            fontSize = 12.sp,
                            color = MiraPalette.TextSecondary
                        )
                        Text(
                            text = "${state.powerDbm} dBm",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiraPalette.GoldPrimary
                        )
                    }
                    Slider(
                        value = state.powerDbm.toFloat(),
                        onValueChange = { onPowerChange(it.toInt()) },
                        valueRange = 5f..30f,
                        steps = 24,
                        colors = SliderDefaults.colors(
                            thumbColor = MiraPalette.GoldPrimary,
                            activeTrackColor = MiraPalette.GoldPrimary
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.triggerMode == TriggerMode.TOGGLE,
                            onClick = { onTriggerModeChange(TriggerMode.TOGGLE) },
                            label = {
                                Text(
                                    text = if (state.isArabic) "زناد تبديل (Toggle)" else "Toggle Trigger",
                                    fontSize = 11.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MiraPalette.GoldContainer,
                                selectedLabelColor = MiraPalette.GoldPrimary
                            )
                        )
                        FilterChip(
                            selected = state.triggerMode == TriggerMode.HOLD,
                            onClick = { onTriggerModeChange(TriggerMode.HOLD) },
                            label = {
                                Text(
                                    text = if (state.isArabic) "ضغط مستمر (Hold)" else "Hold Trigger",
                                    fontSize = 11.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MiraPalette.GoldContainer,
                                selectedLabelColor = MiraPalette.GoldPrimary
                            )
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // زر ضبط الطاقة والزناد
                IconButton(
                    onClick = { showPowerControls = !showPowerControls },
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiraPalette.NavyCard)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = "Power Settings",
                        tint = MiraPalette.GoldPrimary
                    )
                }

                // الزر الرئيسي لبدء/إيقاف الجرد
                Button(
                    onClick = onToggleScan,
                    modifier = Modifier
                        .weight(1.4f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isScanning) MiraPalette.CrimsonMissing else MiraPalette.GoldPrimary,
                        contentColor = if (state.isScanning) Color.White else MiraPalette.NavyDeep
                    )
                ) {
                    Icon(
                        imageVector = if (state.isScanning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (state.isScanning) {
                            if (state.isArabic) "إيقاف الجرد" else "Stop Scan"
                        } else {
                            if (state.isArabic) "بدء الجرد" else "Start Scan"
                        },
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                }

                // زر اعتماد الجرد
                Button(
                    onClick = onCommit,
                    enabled = !state.isCommitting && (state.matchedCount + state.uncataloguedCount + state.wrongCategoryCount) > 0,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MiraPalette.EmeraldMatched,
                        contentColor = MiraPalette.NavyDeep
                    )
                ) {
                    if (state.isCommitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MiraPalette.NavyDeep
                        )
                    } else {
                        Icon(Icons.Rounded.CloudDone, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (state.isArabic) "اعتماد" else "Commit",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                // زر تصفير الجرد
                IconButton(
                    onClick = onReset,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiraPalette.NavyCard)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RestartAlt,
                        contentDescription = "Reset",
                        tint = MiraPalette.TextSecondary
                    )
                }
            }
        }
    }
}

// ==================== 6. رادار تتبع القطع المفقودة (Geiger RSSI Radar) ====================

@Composable
private fun RssiRadarSheetContent(
    isArabic: Boolean,
    target: RadarTargetState,
    onClose: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_scale"
    )

    val proximity = target.proximityPercent
    val radarColor = when {
        proximity >= 75 -> MiraPalette.EmeraldMatched
        proximity >= 40 -> MiraPalette.GoldPrimary
        else -> MiraPalette.CrimsonMissing
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = if (isArabic) "رادار تتبع القطعة المفقودة" else "Geiger Item Finder",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = MiraPalette.GoldPrimary
        )
        Text(
            text = "${target.title} (${target.karat})",
            fontSize = 14.sp,
            color = MiraPalette.TextPrimary
        )
        Text(
            text = target.epc,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MiraPalette.TextSecondary
        )

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(190.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = radarColor.copy(alpha = 0.15f),
                    radius = size.minDimension / 2f
                )
                drawCircle(
                    color = radarColor.copy(alpha = 0.35f * (1f - pulseScale)),
                    radius = (size.minDimension / 2f) * pulseScale
                )
                drawArc(
                    color = radarColor,
                    startAngle = -90f,
                    sweepAngle = 360f * (proximity / 100f),
                    useCenter = false,
                    style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.Sensors,
                    contentDescription = null,
                    tint = radarColor,
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = "$proximity%",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 26.sp,
                    color = radarColor
                )
                Text(
                    text = "${target.currentRssi} dBm",
                    fontSize = 12.sp,
                    color = MiraPalette.TextSecondary
                )
            }
        }

        Text(
            text = if (isArabic) "حرّك القارئ ببطء حول الخزائن والفاترينات؛ تزداد النسبة وسرعة النغمة كلما اقتربت من القطعة."
            else "Sweep the reader slowly near display trays; percentage and beep rate increase as you get closer.",
            fontSize = 12.sp,
            color = MiraPalette.TextSecondary
        )

        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MiraPalette.NavyCard)
        ) {
            Text(
                text = if (isArabic) "إنهاء التتبع" else "Done",
                color = MiraPalette.GoldPrimary
            )
        }
    }
}

// ==================== 7. مكونات الاسترجاع والأرشيف والتقارير ====================

@Composable
private fun RestoreSessionCard(
    isArabic: Boolean,
    banner: RestoreBannerState,
    onRestore: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MiraPalette.NavyElevated),
        border = BorderStroke(1.dp, MiraPalette.GoldPrimary)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MiraPalette.GoldPrimary)
                Text(
                    text = if (isArabic) "توجد جلسة جرد غير مكتملة (${banner.itemCount} قطعة • منذ ${banner.timeAgoText})"
                    else "Unfinished audit found (${banner.itemCount} items • ${banner.timeAgoText} ago)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MiraPalette.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = if (isArabic) "تجاهل" else "Discard",
                        color = MiraPalette.TextSecondary
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onRestore,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MiraPalette.GoldPrimary,
                        contentColor = MiraPalette.NavyDeep
                    )
                ) {
                    Text(
                        text = if (isArabic) "استرجاع ومتابعة" else "Restore & Resume",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogStatusBanner(
    isArabic: Boolean,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MiraPalette.NavyCard)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (isLoading) {
                Text(
                    text = if (isArabic) "جاري مزامنة كتالوج مجوهرات الفرع..." else "Syncing branch jewelry catalog...",
                    fontSize = 12.sp,
                    color = MiraPalette.GoldLight
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MiraPalette.GoldPrimary
                )
            } else if (error != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MiraPalette.CrimsonMissing)
                    Text(
                        text = error,
                        fontSize = 12.sp,
                        color = MiraPalette.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRetry) {
                        Text(if (isArabic) "إعادة" else "Retry", color = MiraPalette.GoldPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyInventoryPlaceholder(isArabic: Boolean, tab: InventoryFilterTab) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = when (tab) {
                InventoryFilterTab.MISSING -> if (isArabic) "ممتاز! لا توجد قطع مفقودة" else "Great! No missing items"
                InventoryFilterTab.UNCATALOGUED -> if (isArabic) "لا توجد شرائح غير مسجلة" else "No uncatalogued tags"
                InventoryFilterTab.WRONG_CATEGORY -> if (isArabic) "لا توجد قطع خارج الفئة" else "No out-of-category items"
                else -> if (isArabic) "اضغط «بدء الجرد» أو اسحب زناد القارئ" else "Tap Start Scan or pull reader trigger"
            },
            color = MiraPalette.TextSecondary,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun ScannerSettingsSheetContent(
    state: InventoryUiState,
    onPowerChange: (Int) -> Unit,
    onTriggerModeChange: (TriggerMode) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (state.isArabic) "إعدادات قارئ UHF والزناد" else "UHF Scanner & Trigger Settings",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = MiraPalette.GoldPrimary
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = MiraPalette.TextSecondary)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (state.isArabic) "قوة إشارة الانتينا" else "Antenna Output Power",
                color = MiraPalette.TextSecondary,
                fontSize = 13.sp
            )
            Text(
                text = "${state.powerDbm} dBm",
                color = MiraPalette.GoldPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        Slider(
            value = state.powerDbm.toFloat(),
            onValueChange = { onPowerChange(it.toInt()) },
            valueRange = 5f..30f,
            steps = 24,
            colors = SliderDefaults.colors(
                thumbColor = MiraPalette.GoldPrimary,
                activeTrackColor = MiraPalette.GoldPrimary
            )
        )

        Text(
            text = if (state.isArabic) "وضع الزناد الفيزيائي (Trigger Mode)" else "Physical Trigger Mode",
            color = MiraPalette.TextSecondary,
            fontSize = 13.sp
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
                selected = state.triggerMode == TriggerMode.TOGGLE,
                onClick = { onTriggerModeChange(TriggerMode.TOGGLE) },
                label = {
                    Text(if (state.isArabic) "ضغطة تشغيل / ضغطة إيقاف" else "Toggle (Click Start/Stop)")
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MiraPalette.GoldContainer,
                    selectedLabelColor = MiraPalette.GoldPrimary
                )
            )
            FilterChip(
                selected = state.triggerMode == TriggerMode.HOLD,
                onClick = { onTriggerModeChange(TriggerMode.HOLD) },
                label = {
                    Text(if (state.isArabic) "ضغط مستمر" else "Hold to Scan")
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MiraPalette.GoldContainer,
                    selectedLabelColor = MiraPalette.GoldPrimary
                )
            )
        }

        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MiraPalette.GoldPrimary,
                contentColor = MiraPalette.NavyDeep
            )
        ) {
            Text(if (state.isArabic) "حفظ وإغلاق" else "Apply & Close", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SessionSettingsSheetContent(
    state: InventoryUiState,
    onSelectCategory: (String) -> Unit,
    onSaveSnapshot: () -> Unit,
    onResetAudit: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (state.isArabic) "إعدادات جلسة الجرد" else "Audit Session Settings",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = MiraPalette.GoldPrimary
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = MiraPalette.TextSecondary)
            }
        }

        Text(
            text = if (state.isArabic) "نطاق الجرد (شامل أو حسب العيار)" else "Audit Scope (Full or by Karat)",
            color = MiraPalette.TextSecondary,
            fontSize = 13.sp
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.availableCategories, key = { it }) { cat ->
                val label = cat.ifBlank { if (state.isArabic) "جرد شامل" else "Full Audit" }
                FilterChip(
                    selected = cat == state.selectedCategory,
                    onClick = { onSelectCategory(cat) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MiraPalette.GoldContainer,
                        selectedLabelColor = MiraPalette.GoldPrimary
                    )
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onSaveSnapshot,
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, MiraPalette.GoldPrimary)
            ) {
                Text(
                    text = if (state.isArabic) "حفظ مسودة الجلسة" else "Save Draft",
                    color = MiraPalette.GoldPrimary
                )
            }

            Button(
                onClick = onResetAudit,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MiraPalette.CrimsonMissing)
            ) {
                Text(
                    text = if (state.isArabic) "تصفير الجرد" else "Reset Audit",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}


@Composable
private fun CommitReportDialog(
    isArabic: Boolean,
    report: CommitReportUi,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MiraPalette.NavyCard,
        icon = {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MiraPalette.EmeraldMatched,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = if (isArabic) "تم اعتماد تقرير الجرد" else "Inventory Audit Committed",
                color = MiraPalette.GoldPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (isArabic) "رقم الجلسة: ${report.sessionId}" else "Session: ${report.sessionId}",
                    fontSize = 12.sp,
                    color = MiraPalette.TextSecondary
                )
                Text(
                    text = if (isArabic) "نسبة المطابقة: ${report.accuracy}" else "Accuracy: ${report.accuracy}",
                    fontWeight = FontWeight.Bold,
                    color = MiraPalette.EmeraldMatched
                )
                Text(
                    text = if (isArabic) "المتوقع: ${report.expectedCount} | المطابق: ${report.foundCount} | المفقود: ${report.missingCount}"
                    else "Expected: ${report.expectedCount} | Found: ${report.foundCount} | Missing: ${report.missingCount}",
                    fontSize = 13.sp,
                    color = MiraPalette.TextPrimary
                )
                Text(
                    text = report.recommendation,
                    fontSize = 12.sp,
                    color = MiraPalette.GoldLight
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MiraPalette.GoldPrimary,
                    contentColor = MiraPalette.NavyDeep
                )
            ) {
                Text(if (isArabic) "تم" else "Done", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun ArchiveReportDetailsSheetContent(
    isArabic: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    details: ArchiveReportDetails?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPrintPdf: () -> Unit,
    onExportCsv: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf<ArchiveItemStatus?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // شريط العنوان وزر العودة لقائمة الأرشيف
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        tint = MiraPalette.GoldPrimary
                    )
                }
                Column {
                    Text(
                        text = if (isArabic) "تفاصيل تقرير الجرد" else "Audit Report Details",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MiraPalette.GoldPrimary
                    )
                    if (details != null) {
                        Text(
                            text = "#${details.reportCode} • ${details.createdAt}",
                            fontSize = 12.sp,
                            color = MiraPalette.TextSecondary
                        )
                    }
                }
            }
        }

        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MiraPalette.GoldPrimary)
                }
            }

            errorMessage != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = errorMessage,
                        color = MiraPalette.CrimsonMissing,
                        fontSize = 13.sp
                    )
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = MiraPalette.GoldPrimary)
                    ) {
                        Text(
                            text = if (isArabic) "إعادة المحاولة" else "Retry",
                            color = MiraPalette.NavyDeep,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            details != null -> {
                // أزرار الطباعة / PDF وتصدير CSV
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onPrintPdf,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MiraPalette.GoldPrimary,
                            contentColor = MiraPalette.NavyDeep
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Print,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isArabic) "طباعة / PDF" else "Print / PDF",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    OutlinedButton(
                        onClick = onExportCsv,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, MiraPalette.GoldPrimary)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FileDownload,
                            contentDescription = null,
                            tint = MiraPalette.GoldPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isArabic) "تصدير CSV" else "Export CSV",
                            color = MiraPalette.GoldPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                // بطاقة ملخص المؤشرات
                Surface(
                    color = MiraPalette.NavyDeep,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MiraPalette.GoldPrimary.copy(alpha = 0.25f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${if (isArabic) "الفرع:" else "Branch:"} ${details.branchName}",
                                fontSize = 12.sp,
                                color = MiraPalette.TextSecondary
                            )
                            Text(
                                text = "${if (isArabic) "الموظف:" else "Auditor:"} ${details.auditorName}",
                                fontSize = 12.sp,
                                color = MiraPalette.TextSecondary
                            )
                        }

                        HorizontalDivider(color = MiraPalette.TextSecondary.copy(alpha = 0.15f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            ArchiveMetricBadge(
                                label = if (isArabic) "نسبة المطابقة" else "Match",
                                value = String.format(Locale.US, "%.1f%%", details.matchRatePercent),
                                color = MiraPalette.GoldPrimary
                            )
                            ArchiveMetricBadge(
                                label = if (isArabic) "مطابق" else "Matched",
                                value = "${details.matchedCount}/${details.expectedCount}",
                                color = MiraPalette.EmeraldMatched
                            )
                            ArchiveMetricBadge(
                                label = if (isArabic) "مفقود" else "Missing",
                                value = "${details.missingCount}",
                                color = MiraPalette.CrimsonMissing
                            )
                            ArchiveMetricBadge(
                                label = if (isArabic) "الوزن" else "Weight",
                                value = String.format(Locale.US, "%.1fg", details.scannedWeightGrams),
                                color = MiraPalette.TextPrimary
                            )
                        }
                    }
                }

                // شرائح فلترة القطع داخل التقرير
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedFilter == null,
                            onClick = { selectedFilter = null },
                            label = {
                                Text(if (isArabic) "الكل (${details.items.size})" else "All (${details.items.size})")
                            }
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == ArchiveItemStatus.MATCHED,
                            onClick = { selectedFilter = ArchiveItemStatus.MATCHED },
                            label = {
                                Text(if (isArabic) "مطابق (${details.matchedCount})" else "Matched (${details.matchedCount})")
                            }
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == ArchiveItemStatus.MISSING,
                            onClick = { selectedFilter = ArchiveItemStatus.MISSING },
                            label = {
                                Text(if (isArabic) "مفقود (${details.missingCount})" else "Missing (${details.missingCount})")
                            }
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == ArchiveItemStatus.UNCATALOGUED,
                            onClick = { selectedFilter = ArchiveItemStatus.UNCATALOGUED },
                            label = {
                                Text(if (isArabic) "غير مسجل (${details.uncataloguedCount})" else "Uncatalogued (${details.uncataloguedCount})")
                            }
                        )
                    }
                }

                // قائمة القطع المفصلة
                val filteredItems = remember(details.items, selectedFilter) {
                    if (selectedFilter == null) details.items
                    else details.items.filter { it.status == selectedFilter }
                }

                if (filteredItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isArabic) "لا توجد قطع مسجلة ضمن هذا التصنيف" else "No items in this filter",
                            color = MiraPalette.TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredItems, key = { "${it.epc}_${it.status}" }) { item ->
                            val (statusText, statusColor) = when (item.status) {
                                ArchiveItemStatus.MATCHED ->
                                    (if (isArabic) "مطابق" else "Matched") to MiraPalette.EmeraldMatched
                                ArchiveItemStatus.MISSING ->
                                    (if (isArabic) "مفقود" else "Missing") to MiraPalette.CrimsonMissing
                                ArchiveItemStatus.UNCATALOGUED ->
                                    (if (isArabic) "غير مسجل" else "Uncatalogued") to MiraPalette.AmberWarning
                            }

                            Surface(
                                color = MiraPalette.NavyDeep.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.3f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = item.name,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp,
                                                color = MiraPalette.TextPrimary
                                            )
                                            if (item.karat.isNotBlank()) {
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = item.karat,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MiraPalette.GoldPrimary
                                                )
                                            }
                                        }
                                        Text(
                                            text = item.epc,
                                            fontSize = 11.sp,
                                            color = MiraPalette.TextSecondary
                                        )
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = statusText,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = statusColor
                                        )
                                        Text(
                                            text = String.format(Locale.US, "%.2f g", item.weightGrams),
                                            fontSize = 12.sp,
                                            color = MiraPalette.TextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ArchiveMetricBadge(
    label: String,
    value: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = color
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = MiraPalette.TextSecondary
        )
    }
}


@Composable
private fun ArchiveReportsSheetContent(
    state: InventoryUiState,
    onOpenDetails: (String) -> Unit,
    onDeleteReport: (String) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (state.isArabic) "أرشيف تقارير الجرد" else "Inventory Audit Archive",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = MiraPalette.GoldPrimary
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = MiraPalette.TextSecondary)
            }
        }

        if (state.isArchiveLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MiraPalette.GoldPrimary)
            }
        } else if (state.archiveReports.isEmpty()) {
            Text(
                text = if (state.isArabic) "لا توجد تقارير محفوظة لهذا الفرع" else "No archived reports for this branch",
                color = MiraPalette.TextSecondary,
                modifier = Modifier.padding(vertical = 20.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.height(340.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.archiveReports, key = { it.sessionId }) { rep ->
                    Card(
                        modifier = Modifier.clickable { onOpenDetails(rep.sessionId) },
                        colors = CardDefaults.cardColors(containerColor = MiraPalette.NavyCard),
                        border = BorderStroke(1.dp, MiraPalette.BorderSubtle)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = rep.sessionId,
                                    fontWeight = FontWeight.Bold,
                                    color = MiraPalette.GoldLight,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "${rep.date} • ${rep.scannedCount}/${rep.expectedCount}",
                                    color = MiraPalette.TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            IconButton(onClick = { onDeleteReport(rep.sessionId) }) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete",
                                    tint = MiraPalette.CrimsonMissing
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SessionReportExportActions(
    isArabic: Boolean,
    reportCode: String?,
    onPrintPdf: () -> Unit,
    onExportCsv: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MiraPalette.NavyDeep,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MiraPalette.GoldPrimary.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isArabic) "تصدير وطباعة تقرير الاعتماد" else "Export & Print Audit Report",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MiraPalette.GoldPrimary
                )
                if (!reportCode.isNullOrBlank()) {
                    Text(
                        text = "#$reportCode",
                        fontSize = 11.sp,
                        color = MiraPalette.TextSecondary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onPrintPdf,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MiraPalette.GoldPrimary,
                        contentColor = MiraPalette.NavyDeep
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Print,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isArabic) "طباعة / PDF" else "Print / PDF",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                OutlinedButton(
                    onClick = onExportCsv,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, MiraPalette.GoldPrimary)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileDownload,
                        contentDescription = null,
                        tint = MiraPalette.GoldPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isArabic) "تصدير CSV" else "Export CSV",
                        color = MiraPalette.GoldPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
