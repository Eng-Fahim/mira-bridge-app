package com.example.uhf.ui.inventory

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.uhf.MiraRfid.RFIDManager
import com.example.uhf.api.MiraApiClient
import com.example.uhf.engine.MiraInventoryEngine
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.manager.inventory.InventoryCatalogManager
import com.example.uhf.manager.inventory.InventoryPersistenceManager
import com.example.uhf.manager.inventory.InventoryScanController
import com.example.uhf.manager.inventory.InventorySessionManager
import com.example.uhf.model.InventoryItem
import com.example.uhf.model.InventoryReport
import com.example.uhf.model.ScannedItemDisplay
import com.example.uhf.utils.MiraUhfSoundEngine
import com.example.uhf.utils.SoundManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MiraInventoryViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MiraInventoryVM"
        private const val UI_COALESCE_INTERVAL_MS = 120L
        private const val AUTO_SAVE_INTERVAL_MS = 15_000L
        private const val CATALOG_CACHE_TTL_MS = 5 * 60_000L // 5 دقائق كاش ذكي في الذاكرة
        private const val SORT_THROTTLE_MS = 500L
    }

    /**
     * جسر للتعامل الآمن مع دوال العتاد الخاصة بـ RFIDManager (طاقة الانتينا وقراءة TID)
     */
    interface HardwareOperations {
        fun applyAntennaPower(powerDbm: Int): Boolean
        fun readTagTidDirect(epc: String): String?
        fun playGeigerTick(proximityPercent: Int)
    }

    private var hardwareOps: HardwareOperations? = null
    private var apiClient: MiraApiClient? = null
    private var branchesManager: MiraBranchesManager? = null
    private var soundManager: SoundManager? = null
    private var uhfSoundEngine: MiraUhfSoundEngine? = null
    private var rfidManager: RFIDManager? = null

    // أولوية قصوى لمعالجة نبضات UHF لمنع أي بطء أو فقدان للشرائح أثناء المسح
    private val processingExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Mira-UHF-FastStream").apply {
            priority = Thread.MAX_PRIORITY - 1
        }
    }

    // أولوية منخفضة للشبكة والـ IO حتى لا تنافس قارئ RFID على المعالج
    private val ioExecutor: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "Mira-Net-LowPrio").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    private val opticalVerifiedEpcs = ConcurrentHashMap.newKeySet<String>()

    private var isArabic: Boolean = true
    private var initialized = false

    private lateinit var catalogManager: InventoryCatalogManager
    private lateinit var scanController: InventoryScanController
    private lateinit var sessionManager: InventorySessionManager
    private lateinit var persistenceManager: InventoryPersistenceManager
    private lateinit var inventoryEngine: MiraInventoryEngine

    // مخازن الحالة السريعة في الذاكرة (O(1) Thread-Safe)
    private val fullCatalog = ConcurrentHashMap<String, InventoryItem>()
    private val activeTargetCatalog = ConcurrentHashMap<String, InventoryItem>()
    private val rowsByEpc = ConcurrentHashMap<String, InventoryDisplayRow>()

    private val isUiDirty = AtomicBoolean(false)
    private val readsInCurrentSecond = AtomicInteger(0)
    private var cachedPendingSession: Triple<JSONArray, Int, String>? = null

    private var lastCatalogBranchId: Int = -1
    private var lastCatalogLoadedAtMs: Long = 0L
    private var lastSortTimestampMs: Long = 0L

    private var activeSegmentStartMs: Long = 0L
    private var accumulatedScanDurationMs: Long = 0L
    private var lastAutoSaveMs: Long = 0L
    private var tickerJob: Job? = null

    private val timeFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    private val _uiState = MutableStateFlow(InventoryUiState())
    val uiState: StateFlow<InventoryUiState> = _uiState.asStateFlow()

    fun initDependencies(
        isArabicLang: Boolean,
        api: MiraApiClient?,
        branches: MiraBranchesManager?,
        sound: SoundManager?,
        rfid: RFIDManager?,
        hwOps: HardwareOperations
    ) {
        if (initialized) {
            this.rfidManager = rfid
            this.hardwareOps = hwOps
            if (rfid != null) scanController.attachRfidManager(rfid)

            val currentBranch = branches?.activeBranchId ?: _uiState.value.branchId
            if (fullCatalog.isEmpty() || currentBranch != _uiState.value.branchId) {
                loadCatalog(checkRestoreAfterLoad = false, forceRefresh = currentBranch != _uiState.value.branchId)
            }
            return
        }
        initialized = true
        this.isArabic = isArabicLang
        this.apiClient = api
        this.branchesManager = branches
        this.soundManager = sound
        this.uhfSoundEngine = MiraUhfSoundEngine()
        this.rfidManager = rfid
        this.hardwareOps = hwOps

        val appCtx = getApplication<Application>()
        catalogManager = InventoryCatalogManager(appCtx, isArabic)
        scanController = InventoryScanController(appCtx, isArabic, processingExecutor, ioExecutor)
        sessionManager = InventorySessionManager(appCtx, isArabic)
        persistenceManager = InventoryPersistenceManager(appCtx, isArabic, ioExecutor)

        val sessionId = "SESS-${UUID.randomUUID().toString().take(8).uppercase(Locale.US)}"
        inventoryEngine = MiraInventoryEngine(appCtx, sessionId, persistenceManager.deviceId)

        scanController.setInventoryEngine(inventoryEngine)
        if (rfid != null) scanController.attachRfidManager(rfid)

        val categories = if (isArabic) {
            listOf("", "عيار 24", "عيار 22", "عيار 21", "عيار 18", "عيار 14", "سبائك", "أونصة", "فضة", "ألماس", "أخرى")
        } else {
            listOf("", "24K", "22K", "21K", "18K", "14K", "BULLION", "OUNCE", "SILVER", "DIAMOND", "OTHER")
        }

        val branchId = branches?.activeBranchId ?: 0
        _uiState.update {
            it.copy(
                isArabic = isArabic,
                branchId = branchId,
                availableCategories = categories,
                isReaderConnected = rfid != null
            )
        }

        setupScanControllerCallbacks()
        startUiCoalescingTicker()
        loadCatalog(checkRestoreAfterLoad = true, forceRefresh = false)
    }

    // ==================== استقبال أحداث القارئ (UHF Stream) ====================

    private fun setupScanControllerCallbacks() {
        scanController.setCallback(object : InventoryScanController.ScanCallback {
            override fun onScanStarted() {
                activeSegmentStartMs = SystemClock.elapsedRealtime()
                _uiState.update { it.copy(isScanning = true, restoreBanner = null) }
                isUiDirty.set(true)
            }

            override fun onScanStopped() {
                if (activeSegmentStartMs > 0L) {
                    accumulatedScanDurationMs += (SystemClock.elapsedRealtime() - activeSegmentStartMs)
                    activeSegmentStartMs = 0L
                }
                _uiState.update { it.copy(isScanning = false, readsPerSecond = 0) }
                ioExecutor.execute { saveCurrentSessionSnapshot() }
                flushSnapshotToUi(force = true)
            }

            override fun onTagRead(normalizedEpc: String, rssi: Int) {
                handleTagEvent(normalizedEpc, rssi, isNewUniqueHint = true)
            }

            override fun onDuplicateTag(epc: String) {
                handleTagEvent(epc, -55, isNewUniqueHint = false)
            }

            override fun onTagDebounced(epc: String) {
                val canonical = InventoryItem.normalizeEpc(epc)
                if (canonical.isEmpty()) return

                // إذا كانت القطعة غير موجودة أو مصفّرة (MISSING بعد التصفير)، تُسجل كقراءة جديدة فوراً
                val existing = rowsByEpc[canonical]
                if (existing == null || existing.status == ItemAuditStatus.MISSING) {
                    handleTagEvent(canonical, -55, isNewUniqueHint = false)
                    return
                }

                readsInCurrentSecond.incrementAndGet()
                uhfSoundEngine?.playDuplicateTick()

                val radar = _uiState.value.radarTarget
                if (radar != null && radar.epc.equals(canonical, ignoreCase = true)) {
                    updateRadarPing(canonical, existing.rssi)
                }
            }

            override fun onError(error: String) {
                _uiState.update { it.copy(isScanning = false, snackbarMessage = error) }
            }

            override fun onConnectionChanged(isConnected: Boolean) {
                _uiState.update { it.copy(isReaderConnected = isConnected) }
            }

            override fun onExpectedCountChanged(expectedCount: Int) {
                isUiDirty.set(true)
            }
        })
    }

    private fun handleTagEvent(epc: String, rssi: Int, isNewUniqueHint: Boolean) {
        val canonicalEpc = InventoryItem.normalizeEpc(epc)
        if (canonicalEpc.isEmpty()) return

        readsInCurrentSecond.incrementAndGet()
        val nowStr = timeFormatter.get()?.format(Date()) ?: ""

        var isFirstDiscovery = false
        var resolvedStatus = ItemAuditStatus.UNCATALOGUED

        rowsByEpc.compute(canonicalEpc) { _, existing ->
            if (existing != null) {
                val newStatus = if (existing.status == ItemAuditStatus.MISSING) {
                    isFirstDiscovery = true
                    ItemAuditStatus.MATCHED
                } else {
                    existing.status
                }
                resolvedStatus = newStatus
                existing.copy(
                    readCount = existing.readCount + 1,
                    rssi = rssi,
                    lastSeenTime = nowStr,
                    status = newStatus
                )
            } else {
                isFirstDiscovery = true
                val targetItem = activeTargetCatalog[canonicalEpc]
                val fullCatalogItem = fullCatalog[canonicalEpc]

                when {
                    targetItem != null -> {
                        resolvedStatus = ItemAuditStatus.MATCHED
                        InventoryDisplayRow(
                            epc = canonicalEpc,
                            gtin = targetItem.gtin,
                            serial = targetItem.serial,
                            title = targetItem.title.ifBlank { if (isArabic) "قطعة ذهبية" else "Gold Item" },
                            karat = targetItem.karat.ifBlank { "—" },
                            weight = targetItem.weight,
                            price = targetItem.price,
                            readCount = 1,
                            rssi = rssi,
                            lastSeenTime = nowStr,
                            status = ItemAuditStatus.MATCHED
                        )
                    }
                    fullCatalogItem != null -> {
                        resolvedStatus = ItemAuditStatus.WRONG_CATEGORY
                        InventoryDisplayRow(
                            epc = canonicalEpc,
                            gtin = fullCatalogItem.gtin,
                            serial = fullCatalogItem.serial,
                            title = fullCatalogItem.title.ifBlank { if (isArabic) "قطعة خارج الفئة" else "Out-of-Category Item" },
                            karat = fullCatalogItem.karat.ifBlank { "—" },
                            weight = fullCatalogItem.weight,
                            price = fullCatalogItem.price,
                            readCount = 1,
                            rssi = rssi,
                            lastSeenTime = nowStr,
                            status = ItemAuditStatus.WRONG_CATEGORY
                        )
                    }
                    else -> {
                        resolvedStatus = ItemAuditStatus.UNCATALOGUED
                        InventoryDisplayRow(
                            epc = canonicalEpc,
                            title = if (isArabic) "قطعة غير مسجلة بالنظام" else "Uncatalogued Tag",
                            karat = "—",
                            weight = 0.0,
                            price = 0.0,
                            readCount = 1,
                            rssi = rssi,
                            lastSeenTime = nowStr,
                            status = ItemAuditStatus.UNCATALOGUED
                        )
                    }
                }
            }
        }

        if (isFirstDiscovery) {
            playFeedbackForStatus(resolvedStatus)
            val branchId = _uiState.value.branchId
            persistenceManager.saveTransactionLocally(
                inventoryEngine.clientSessionId,
                canonicalEpc,
                rssi,
                persistenceManager.deviceId,
                persistenceManager.currentUserId,
                branchId
            )
        } else {
            uhfSoundEngine?.playDuplicateTick()
        }

        val radar = _uiState.value.radarTarget
        if (radar != null && radar.epc == canonicalEpc) {
            updateRadarPing(canonicalEpc, rssi)
        }

        isUiDirty.set(true)
    }

    private fun playFeedbackForStatus(status: ItemAuditStatus) {
        val engine = uhfSoundEngine ?: return
        try {
            when (status) {
                ItemAuditStatus.MATCHED -> engine.playMatchTick()
                ItemAuditStatus.WRONG_CATEGORY -> engine.playAlertChirp()
                ItemAuditStatus.UNCATALOGUED -> engine.playAlertChirp()
                else -> Unit
            }
        } catch (e: Exception) {
            Log.w(TAG, "UHF Sound feedback error: ${e.message}")
        }
    }

    // ==================== محرك تجميع الإطارات (120ms Coalescing) ====================

    private fun startUiCoalescingTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch(Dispatchers.Default) {
            var secondCounterMs = 0L
            while (isActive) {
                delay(UI_COALESCE_INTERVAL_MS)
                secondCounterMs += UI_COALESCE_INTERVAL_MS

                val scanning = _uiState.value.isScanning
                if (secondCounterMs >= 1000L) {
                    val rps = readsInCurrentSecond.getAndSet(0)
                    val elapsedSec = calculateTotalElapsedMs() / 1000L
                    _uiState.update {
                        it.copy(
                            readsPerSecond = if (scanning) rps else 0,
                            elapsedSeconds = elapsedSec
                        )
                    }
                    secondCounterMs = 0L

                    val now = SystemClock.elapsedRealtime()
                    if (scanning && now - lastAutoSaveMs >= AUTO_SAVE_INTERVAL_MS) {
                        lastAutoSaveMs = now
                        // حفظ المسودة في خيط الخلفية لمنع أي تجميد للواجهة مع آلاف القطع
                        ioExecutor.execute { saveCurrentSessionSnapshot() }
                    }
                }

                if (isUiDirty.compareAndSet(true, false)) {
                    flushSnapshotToUi(force = false)
                }
            }
        }
    }

    private fun flushSnapshotToUi(force: Boolean) {
        val state = _uiState.value
        val query = state.searchQuery.trim().lowercase(Locale.US)
        val tab = state.activeTab

        var matched = 0
        var missing = 0
        var uncatalogued = 0
        var wrongCat = 0
        var scannedWeight = 0.0
        var expectedWeight = 0.0
        var scannedValue = 0.0

        val karatMap = LinkedHashMap<String, Triple<Int, Int, Double>>()
        val filteredList = ArrayList<InventoryDisplayRow>(minOf(rowsByEpc.size, 500))

        // سقف عرض بصري (400 بطاقة بدون بحث / 1000 مع البحث) لضمان 60fps حتى مع 10,000 قطعة
        val maxRenderLimit = if (query.isEmpty()) 400 else 1000

        for (row in rowsByEpc.values) {
            val kKey = row.karat.ifBlank { "—" }
            val prevK = karatMap[kKey] ?: Triple(0, 0, 0.0)

            when (row.status) {
                ItemAuditStatus.MATCHED -> {
                    matched++
                    scannedWeight += row.weight
                    expectedWeight += row.weight
                    scannedValue += row.price
                    karatMap[kKey] = Triple(prevK.first + 1, prevK.second + 1, prevK.third + row.weight)
                }
                ItemAuditStatus.MISSING -> {
                    missing++
                    expectedWeight += row.weight
                    karatMap[kKey] = Triple(prevK.first, prevK.second + 1, prevK.third)
                }
                ItemAuditStatus.UNCATALOGUED -> {
                    uncatalogued++
                }
                ItemAuditStatus.WRONG_CATEGORY -> {
                    wrongCat++
                }
            }

            if (filteredList.size < maxRenderLimit) {
                val matchesTab = when (tab) {
                    InventoryFilterTab.ALL -> true
                    InventoryFilterTab.MATCHED -> row.status == ItemAuditStatus.MATCHED
                    InventoryFilterTab.MISSING -> row.status == ItemAuditStatus.MISSING
                    InventoryFilterTab.UNCATALOGUED -> row.status == ItemAuditStatus.UNCATALOGUED
                    InventoryFilterTab.WRONG_CATEGORY -> row.status == ItemAuditStatus.WRONG_CATEGORY
                }

                if (matchesTab) {
                    val matchesSearch = query.isEmpty() ||
                        row.title.lowercase(Locale.US).contains(query) ||
                        row.epc.lowercase(Locale.US).contains(query) ||
                        row.gtin.lowercase(Locale.US).contains(query) ||
                        row.serial.lowercase(Locale.US).contains(query)

                    if (matchesSearch) {
                        filteredList.add(row)
                    }
                }
            }
        }

        val now = SystemClock.elapsedRealtime()
        if (force || (now - lastSortTimestampMs) >= SORT_THROTTLE_MS) {
            lastSortTimestampMs = now
            filteredList.sortWith(
                compareBy<InventoryDisplayRow> {
                    when (it.status) {
                        ItemAuditStatus.WRONG_CATEGORY -> 0
                        ItemAuditStatus.UNCATALOGUED -> 1
                        ItemAuditStatus.MATCHED -> 2
                        ItemAuditStatus.MISSING -> 3
                    }
                }.thenByDescending { it.lastSeenTime }
            )
        }

        val karatSummaries = karatMap.entries
            .filter { it.key != "—" && it.value.second > 0 }
            .map { (k, stats) ->
                KaratSummary(
                    karat = k,
                    matchedCount = stats.first,
                    expectedCount = stats.second,
                    scannedWeightGrams = stats.third
                )
            }

        _uiState.update {
            it.copy(
                expectedCount = activeTargetCatalog.size,
                matchedCount = matched,
                missingCount = missing,
                uncataloguedCount = uncatalogued,
                wrongCategoryCount = wrongCat,
                totalReads = inventoryEngine.totalReads,
                scannedWeightGrams = scannedWeight,
                expectedWeightGrams = expectedWeight,
                scannedValueUsd = scannedValue,
                karatSummaries = karatSummaries,
                visibleItems = filteredList
            )
        }
    }

    // ==================== تحميل الكتالوج وإدارة الفئات (مع الكاش الذكي والتحميل المتسلسل) ====================

    fun loadCatalog(checkRestoreAfterLoad: Boolean = false, forceRefresh: Boolean = false) {
        val api = apiClient ?: return
        val branches = branchesManager ?: return
        val activeBranchId = branches.activeBranchId

        // حماية القارئ: لا تشغل الشبكة أو تعيد بناء الكتالوج أثناء المسح النشط
        if (_uiState.value.isScanning) return

        // استخدام الكاش الفوري (O(1)) إذا لم تنتهِ صلاحيته (5 دقائق) ولم يُطلب تحديث قسري
        val now = SystemClock.elapsedRealtime()
        val isCacheFresh = !forceRefresh &&
            fullCatalog.isNotEmpty() &&
            lastCatalogBranchId == activeBranchId &&
            (now - lastCatalogLoadedAtMs) < CATALOG_CACHE_TTL_MS

        if (isCacheFresh) {
            applyCategoryFilter(_uiState.value.selectedCategory, preserveScanned = true)
            if (checkRestoreAfterLoad) checkPendingSessionRestore()
            return
        }

        _uiState.update {
            it.copy(
                isCatalogLoading = true,
                catalogError = null,
                branchId = activeBranchId
            )
        }

        ioExecutor.execute {
            catalogManager.fetchCatalog(api, branches, object : InventoryCatalogManager.CatalogCallback {
                override fun onCatalogLoaded(catalog: MutableMap<String, InventoryItem>) {
                    if (catalog.isNotEmpty()) {
                        val cleaned = LinkedHashMap<String, InventoryItem>(catalog.size)
                        catalog.forEach { (k, v) ->
                            val norm = InventoryItem.normalizeEpc(k)
                            if (norm.isNotEmpty()) {
                                cleaned[norm] = v.apply {
                                    if (karat.isNullOrBlank()) karat = "21K"
                                }
                            }
                        }
                        if (cleaned.isNotEmpty()) {
                            lastCatalogBranchId = activeBranchId
                            lastCatalogLoadedAtMs = SystemClock.elapsedRealtime()
                            applyLoadedCatalogSuccess(cleaned, activeBranchId, checkRestoreAfterLoad)
                            return
                        }
                    }

                    fetchCatalogSmartFallback(api, activeBranchId, null, checkRestoreAfterLoad)
                }

                override fun onError(error: String) {
                    fetchCatalogSmartFallback(api, activeBranchId, error, checkRestoreAfterLoad)
                }
            })
        }
    }

    /**
     * قارئ احتياطي كسول (Lazy Sequential) ينفذ طلباً واحداً تلو الآخر ويتوقف فور أول استجابة ناجحة
     */
    private fun fetchCatalogSmartFallback(
        api: MiraApiClient,
        branchId: Int,
        primaryError: String?,
        checkRestoreAfterLoad: Boolean
    ) {
        try {
            val fetchStrategies = listOf(
                { runCatching { api.getInventoryExpectedCatalog(branchId, "") }.getOrNull() },
                { runCatching { api.getBranchItemsListWithLongTimeout(branchId) }.getOrNull() },
                { runCatching { api.getMyBranchInventoryItems("") }.getOrNull() }
            )

            val parsedMap = LinkedHashMap<String, InventoryItem>()
            val barcodeMap = HashMap<String, String>()
            var lastError = primaryError
            var confirmedEmptyBranch = false

            for (strategy in fetchStrategies) {
                if (_uiState.value.isScanning) return

                val resp = strategy() ?: continue
                if (!resp.isSuccess) {
                    if (!resp.error.isNullOrBlank()) lastError = resp.error
                    continue
                }

                val root = resp.data ?: continue
                val itemsArray = extractJewelryItemsArray(root) ?: continue

                if (itemsArray.length() == 0) {
                    confirmedEmptyBranch = true
                    break
                }

                for (i in 0 until itemsArray.length()) {
                    val obj = itemsArray.optJSONObject(i) ?: continue

                    val vaultStatus = obj.optString(
                        "vault_status",
                        obj.optString("status", "in_stock")
                    ).trim().lowercase(Locale.US)

                    if (vaultStatus in setOf("sold", "returned", "deleted", "transferred", "melted", "scrap")) {
                        continue
                    }

                    val itemBranchId = obj.optInt("branch_id", branchId)
                    if (branchId > 0 && itemBranchId > 0 && itemBranchId != branchId) {
                        continue
                    }

                    val rawEpc = firstNonBlank(obj, "rfid_epc", "epc", "tag_epc", "uid", "rfid")
                    val normEpc = InventoryItem.normalizeEpc(rawEpc)
                    if (normEpc.isEmpty()) continue

                    val title = firstNonBlank(
                        obj,
                        "item_title", "title", "name", "item_name", "description"
                    ).ifBlank { if (isArabic) "قطعة مجوهرات" else "Jewelry Item" }

                    val rawKarat = firstNonBlank(obj, "gold_karat", "karat", "purity_code", "purity")
                    val formattedKarat = normalizeKaratLabel(rawKarat)

                    val weight = firstPositiveDouble(
                        obj,
                        "net_weight_grams", "gross_weight_grams", "weight_grams", "weight", "net_weight", "gross_weight"
                    )

                    val price = firstPositiveDouble(
                        obj,
                        "retail_price_usd", "selling_price", "price_usd", "price", "estimated_value", "total_price"
                    )

                    val barcode = firstNonBlank(obj, "barcode", "gtin", "sku", "item_code")
                    val serial = firstNonBlank(obj, "serial_no", "serial", "id")
                    val category = firstNonBlank(obj, "category", "item_type", "type", "gold_karat")

                    val item = InventoryItem(
                        normEpc,
                        barcode,
                        serial,
                        title,
                        formattedKarat
                    ).apply {
                        this.weight = weight
                        this.price = price
                        this.category = category
                    }

                    parsedMap[normEpc] = item
                    if (barcode.isNotBlank()) {
                        barcodeMap[barcode.trim()] = normEpc
                    }
                }

                if (parsedMap.isNotEmpty()) break
            }

            if (parsedMap.isNotEmpty() || confirmedEmptyBranch) {
                lastCatalogBranchId = branchId
                lastCatalogLoadedAtMs = SystemClock.elapsedRealtime()
                if (barcodeMap.isNotEmpty()) {
                    scanController.setBarcodeToEpcMap(barcodeMap)
                }
                applyLoadedCatalogSuccess(parsedMap, branchId, checkRestoreAfterLoad)
            } else {
                _uiState.update {
                    it.copy(
                        isCatalogLoading = false,
                        catalogError = lastError ?: if (isArabic) "تعذر تحميل مخزون الفرع" else "Failed to load branch catalog"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchCatalogSmartFallback error: ${e.message}", e)
            _uiState.update {
                it.copy(
                    isCatalogLoading = false,
                    catalogError = e.message ?: primaryError
                )
            }
        }
    }

    private fun applyLoadedCatalogSuccess(
        loadedMap: Map<String, InventoryItem>,
        branchId: Int,
        checkRestoreAfterLoad: Boolean
    ) {
        fullCatalog.clear()
        fullCatalog.putAll(loadedMap)

        if (catalogManager.barcodeToEpcMap.isNotEmpty()) {
            scanController.setBarcodeToEpcMap(catalogManager.barcodeToEpcMap)
        }

        applyCategoryFilter(_uiState.value.selectedCategory, preserveScanned = true)

        _uiState.update {
            it.copy(
                isCatalogLoading = false,
                catalogError = null,
                branchId = branchId
            )
        }

        if (checkRestoreAfterLoad) {
            checkPendingSessionRestore()
        }
    }

    private fun extractJewelryItemsArray(root: JSONObject): JSONArray? {
        val candidateKeys = listOf("items", "jewelry_items", "catalog", "expected_items", "data", "records")
        for (key in candidateKeys) {
            val arr = root.optJSONArray(key)
            if (arr != null) return arr
        }
        val dataObj = root.optJSONObject("data")
        if (dataObj != null) {
            for (key in candidateKeys) {
                val arr = dataObj.optJSONArray(key)
                if (arr != null) return arr
            }
        }
        return null
    }

    private fun firstNonBlank(obj: JSONObject, vararg keys: String): String {
        for (k in keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                val v = obj.optString(k, "").trim()
                if (v.isNotEmpty() && !v.equals("null", ignoreCase = true)) {
                    return v
                }
            }
        }
        return ""
    }

    private fun firstPositiveDouble(obj: JSONObject, vararg keys: String): Double {
        for (k in keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                val v = obj.optDouble(k, 0.0)
                if (!v.isNaN() && v > 0.0) return v
            }
        }
        return 0.0
    }

    private fun normalizeKaratLabel(raw: String): String {
        val clean = raw.trim()
        if (clean.isEmpty()) return "—"
        val digits = clean.replace(Regex("[^0-9.]"), "")
        return if (digits.isNotEmpty()) {
            if (isArabic) "عيار $digits" else "${digits}K"
        } else {
            clean
        }
    }

    fun selectCategory(category: String) {
        if (_uiState.value.isScanning) {
            _uiState.update {
                it.copy(
                    snackbarMessage = if (isArabic) "أوقف المسح أولاً لتغيير فئة الجرد"
                    else "Stop scanning before changing audit category"
                )
            }
            return
        }
        applyCategoryFilter(category, preserveScanned = false)
    }

    private fun applyCategoryFilter(category: String, preserveScanned: Boolean) {
        val filtered = if (category.isBlank()) {
            fullCatalog
        } else {
            fullCatalog.filterValues { item -> matchesCategorySmart(item, category) }
        }

        activeTargetCatalog.clear()
        filtered.forEach { (k, v) ->
            activeTargetCatalog[InventoryItem.normalizeEpc(k)] = v
        }
        inventoryEngine.setCatalog(activeTargetCatalog)

        val alreadyScanned = if (preserveScanned) {
            rowsByEpc.values.filter { it.status != ItemAuditStatus.MISSING }.associateBy { it.epc }
        } else {
            inventoryEngine.clearSession()
            accumulatedScanDurationMs = 0L
            emptyMap()
        }

        rowsByEpc.clear()

        // 1. إدراج كل القطع المتوقعة في الكتالوج كـ MISSING مبدئياً
        for ((epc, item) in activeTargetCatalog) {
            val existingScan = alreadyScanned[epc]
            if (existingScan != null) {
                rowsByEpc[epc] = existingScan.copy(
                    title = item.title.ifBlank { existingScan.title },
                    karat = item.karat.ifBlank { existingScan.karat },
                    weight = if (item.weight > 0) item.weight else existingScan.weight,
                    price = if (item.price > 0) item.price else existingScan.price,
                    status = ItemAuditStatus.MATCHED
                )
            } else {
                rowsByEpc[epc] = InventoryDisplayRow(
                    epc = epc,
                    gtin = item.gtin,
                    serial = item.serial,
                    title = item.title.ifBlank { if (isArabic) "قطعة ذهبية" else "Gold Item" },
                    karat = item.karat.ifBlank { "—" },
                    weight = item.weight,
                    price = item.price,
                    readCount = 0,
                    rssi = -100,
                    lastSeenTime = "",
                    status = ItemAuditStatus.MISSING
                )
            }
        }

        // 2. إعادة تصنيف أي قطع ممسوحة مسبقاً وليست ضمن الفئة المستهدفة
        for ((epc, scannedRow) in alreadyScanned) {
            if (!rowsByEpc.containsKey(epc)) {
                val catItem = fullCatalog[epc]
                if (catItem != null) {
                    rowsByEpc[epc] = scannedRow.copy(
                        title = catItem.title.ifBlank { scannedRow.title },
                        karat = catItem.karat.ifBlank { scannedRow.karat },
                        weight = catItem.weight,
                        price = catItem.price,
                        status = ItemAuditStatus.WRONG_CATEGORY
                    )
                } else {
                    rowsByEpc[epc] = scannedRow.copy(status = ItemAuditStatus.UNCATALOGUED)
                }
            }
        }

        _uiState.update { it.copy(selectedCategory = category) }
        flushSnapshotToUi(force = true)
    }

    private fun matchesCategorySmart(item: InventoryItem, selectedCategory: String): Boolean {
        val target = selectedCategory.trim().lowercase(Locale.US)
        if (target.isEmpty()) return true

        val itemKarat = (item.karat ?: "").trim().lowercase(Locale.US)
        val itemCat = (item.category ?: "").trim().lowercase(Locale.US)
        val itemTitle = (item.title ?: "").trim().lowercase(Locale.US)
        val combined = "$itemKarat $itemCat $itemTitle"

        val targetDigits = target.replace(Regex("[^0-9]"), "")
        if (targetDigits.isNotEmpty()) {
            val itemDigits = itemKarat.replace(Regex("[^0-9]"), "")
            if (itemDigits == targetDigits) return true
            if (combined.contains(targetDigits)) return true
            return false
        }

        return when {
            target.contains("سبائك") || target.contains("bullion") ->
                combined.contains("سبيكة") || combined.contains("سبائك") || combined.contains("bullion") || combined.contains("bar")
            target.contains("أونصة") || target.contains("ounce") ->
                combined.contains("أونصة") || combined.contains("اونصة") || combined.contains("ounce") || combined.contains("oz")
            target.contains("فضة") || target.contains("silver") ->
                combined.contains("فضة") || combined.contains("silver") || combined.contains("925")
            target.contains("ألماس") || target.contains("diamond") ->
                combined.contains("ألماس") || combined.contains("الماس") || combined.contains("diamond")
            else -> combined.contains(target)
        }
    }

    // ==================== التحكم بالمسح والزناد الفيزيائي ====================

    fun toggleScanning() {
        if (_uiState.value.isScanning) {
            stopScanning()
        } else {
            startScanning()
        }
    }

    fun startScanning() {
        if (_uiState.value.isScanning) return
        hardwareOps?.applyAntennaPower(_uiState.value.powerDbm)
        val started = scanController.startScanning(activeTargetCatalog)
        if (!started) {
            _uiState.update {
                it.copy(
                    snackbarMessage = if (isArabic) "تعذر بدء القارئ — تأكد من الاتصال"
                    else "Unable to start reader — check connection"
                )
            }
        }
    }

    fun stopScanning() {
        if (!_uiState.value.isScanning) return
        scanController.stopScanning()
    }

    fun onPhysicalTriggerDown() {
        val state = _uiState.value
        if (state.commitReport != null || state.showArchiveSheet) return

        when (state.triggerMode) {
            TriggerMode.TOGGLE -> toggleScanning()
            TriggerMode.HOLD -> if (!state.isScanning) startScanning()
        }
    }

    fun onPhysicalTriggerUp() {
        val state = _uiState.value
        if (state.triggerMode == TriggerMode.HOLD && state.isScanning) {
            stopScanning()
        }
    }

    fun setTriggerMode(mode: TriggerMode) {
        _uiState.update { it.copy(triggerMode = mode) }
    }

    fun setAntennaPower(powerDbm: Int) {
        val clamped = powerDbm.coerceIn(5, 30)
        scanController.currentPowerDbm = clamped
        hardwareOps?.applyAntennaPower(clamped)
        _uiState.update { it.copy(powerDbm = clamped) }
    }

    /**
     * تصفير الجرد بالكامل:
     * - تبقى قطع الكتالوج المتوقعة معروضة كـ MISSING مع تصفير عداداتها وأوزانها
     * - تُحذف القطع الزائدة غير المسجلة (UNCATALOGUED)
     * - تُمسح المسودة المؤقتة قبل إيقاف المسح لمنع أي إعادة حفظ متزامنة
     */
    fun resetCurrentAudit() {
        opticalVerifiedEpcs.clear()
        cachedPendingSession = null
        activeSegmentStartMs = 0L
        accumulatedScanDurationMs = 0L
        readsInCurrentSecond.set(0)
        inventoryEngine.clearSession()

        applyCategoryFilter(_uiState.value.selectedCategory, preserveScanned = false)
        sessionManager.clearSession()

        if (_uiState.value.isScanning) {
            stopScanning()
        }

        _uiState.update {
            it.copy(
                elapsedSeconds = 0L,
                readsPerSecond = 0,
                matchedCount = 0,
                scannedWeightGrams = 0.0,
                scannedValueUsd = 0.0,
                uncataloguedCount = 0,
                wrongCategoryCount = 0,
                restoreBanner = null,
                snackbarMessage = if (isArabic) "تم تصفير الجرد لبدء جلسة جديدة نظيفة"
                else "Audit reset for a clean new session"
            )
        }
        flushSnapshotToUi(force = true)
    }

    // ==================== البحث والفلترة ====================

    fun selectFilterTab(tab: InventoryFilterTab) {
        _uiState.update { it.copy(activeTab = tab) }
        flushSnapshotToUi(force = true)
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        flushSnapshotToUi(force = true)
    }

    // ==================== رادار تتبع القطع المفقودة (Geiger RSSI Radar) ====================

    fun openRadarForItem(item: InventoryDisplayRow) {
        _uiState.update {
            it.copy(
                radarTarget = RadarTargetState(
                    epc = item.epc,
                    title = item.title,
                    karat = item.karat,
                    weight = item.weight,
                    currentRssi = if (item.rssi > -100) item.rssi else -95,
                    lastPingTimestamp = System.currentTimeMillis()
                )
            )
        }
        if (!_uiState.value.isScanning) {
            startScanning()
        }
    }

    fun closeRadar() {
        _uiState.update { it.copy(radarTarget = null) }
    }

    private fun updateRadarPing(epc: String, rssi: Int) {
        val current = _uiState.value.radarTarget ?: return
        if (current.epc != epc) return
        val updated = current.copy(
            currentRssi = rssi,
            lastPingTimestamp = System.currentTimeMillis()
        )
        _uiState.update { it.copy(radarTarget = updated) }
        hardwareOps?.playGeigerTick(updated.proximityPercent)
    }

    // ==================== فحص أصالة الشريحة (TID Direct Read) ====================

    fun verifyTagTid(item: InventoryDisplayRow) {
        ioExecutor.execute {
            val wasScanning = scanController.isScanning
            if (wasScanning) {
                scanController.suspendForDirectRead()
            }
            try {
                val tid = hardwareOps?.readTagTidDirect(item.epc)
                val msg = if (!tid.isNullOrBlank()) {
                    if (isArabic) "✅ شريحة أصلية موثقة\nTID: $tid"
                    else "✅ Authentic Verified Chip\nTID: $tid"
                } else {
                    if (isArabic) "⚠️ تعذر قراءة ذاكرة TID — قرّب القطعة من القارئ وحاول مجدداً"
                    else "⚠️ Could not read TID memory — move item closer to reader"
                }
                _uiState.update { it.copy(tidVerificationResult = item.epc to msg) }
            } finally {
                if (wasScanning) {
                    scanController.resumeAfterDirectRead()
                }
            }
        }
    }

    fun dismissTidResult() {
        _uiState.update { it.copy(tidVerificationResult = null) }
    }

    // ==================== الماسح البصري المجهري البديل (3×3 مم) والتنبيهات الأمنية ====================

    fun onMicroOpticalCodeScanned(parsed: ParsedOpticalCode) {
        if (parsed.candidateKeys.isEmpty()) return

        val matchedCatalogItem = fullCatalog.values.firstOrNull { item ->
            val keys = setOf(
                item.epc.uppercase(Locale.US),
                item.gtin.uppercase(Locale.US),
                item.serial.uppercase(Locale.US)
            ).filter { it.isNotEmpty() }

            parsed.candidateKeys.any { cand -> cand.uppercase(Locale.US) in keys }
        }

        if (matchedCatalogItem != null) {
            opticalVerifiedEpcs.add(matchedCatalogItem.epc)
            handleTagEvent(
                epc = matchedCatalogItem.epc,
                rssi = -45,
                isNewUniqueHint = true
            )

            uhfSoundEngine?.playMatchTick()
            _uiState.update {
                it.copy(
                    snackbarMessage = if (it.isArabic)
                        "✅ تم التحقق البصري (3×3مم): ${matchedCatalogItem.title}"
                    else
                        "✅ Micro-code verified: ${matchedCatalogItem.title}"
                )
            }
        } else {
            val fallbackCode = parsed.primaryCode.uppercase(Locale.US)
            opticalVerifiedEpcs.add(fallbackCode)
            handleTagEvent(
                epc = fallbackCode,
                rssi = -45,
                isNewUniqueHint = true
            )

            uhfSoundEngine?.playAlertChirp()
            _uiState.update {
                it.copy(
                    snackbarMessage = if (it.isArabic)
                        "⚠️ كود بصري غير مدرج في كتالوج الفرع: $fallbackCode"
                    else
                        "⚠️ Optical code not in branch catalog: $fallbackCode"
                )
            }
        }
    }

    fun triggerSecurityAnomalyAlert(title: String, reason: String) {
        uhfSoundEngine?.playAlertChirp()
        _uiState.update {
            it.copy(
                snackbarMessage = "🚨 تنبيه أمني ($title): $reason"
            )
        }
    }

    // ==================== حفظ واسترجاع الجلسة المؤقتة ====================

    private fun checkPendingSessionRestore() {
        if (!sessionManager.hasMeaningfulSession(1, 5_000L)) return
        sessionManager.tryRestoreSession(object : InventorySessionManager.SessionCallback {
            override fun onSessionFound(
                items: JSONArray,
                expectedCount: Int,
                category: String,
                timeAgoMillis: Long
            ) {
                cachedPendingSession = Triple(items, expectedCount, category)
                _uiState.update {
                    it.copy(
                        restoreBanner = RestoreBannerState(
                            itemCount = items.length(),
                            expectedCount = expectedCount,
                            category = category,
                            timeAgoText = sessionManager.formatTimeAgo(timeAgoMillis),
                            previewText = sessionManager.buildSessionPreview(items, expectedCount, category)
                        )
                    )
                }
            }

            override fun onNoSession() {
                cachedPendingSession = null
            }
        })
    }

    fun restoreSavedSession() {
        val pending = cachedPendingSession ?: return
        val (itemsJson, _, savedCategory) = pending

        opticalVerifiedEpcs.clear()
        applyCategoryFilter(savedCategory, preserveScanned = false)

        for (i in 0 until itemsJson.length()) {
            val obj = itemsJson.optJSONObject(i) ?: continue
            val epc = InventoryItem.normalizeEpc(obj.optString("epc", ""))
            if (epc.isEmpty()) continue

            val count = obj.optInt("count", 1).coerceAtLeast(1)
            val rssi = obj.optInt("rssi", -55)
            val time = obj.optString("time", "")

            inventoryEngine.addEpc(epc, rssi)

            val targetItem = activeTargetCatalog[epc]
            val fullItem = fullCatalog[epc]

            val status = when {
                targetItem != null -> ItemAuditStatus.MATCHED
                fullItem != null -> ItemAuditStatus.WRONG_CATEGORY
                else -> ItemAuditStatus.UNCATALOGUED
            }

            val refItem = targetItem ?: fullItem
            rowsByEpc[epc] = InventoryDisplayRow(
                epc = epc,
                gtin = refItem?.gtin ?: "",
                serial = refItem?.serial ?: "",
                title = refItem?.title?.ifBlank { obj.optString("title", epc) }
                    ?: obj.optString("title", epc),
                karat = refItem?.karat?.ifBlank { obj.optString("karat", "—") }
                    ?: obj.optString("karat", "—"),
                weight = refItem?.weight ?: obj.optDouble("weight", 0.0),
                price = refItem?.price ?: obj.optDouble("price", 0.0),
                readCount = count,
                rssi = rssi,
                lastSeenTime = time,
                status = status
            )
        }

        cachedPendingSession = null
        _uiState.update {
            it.copy(
                restoreBanner = null,
                snackbarMessage = if (isArabic) "تم استرجاع الجلسة السابقة بنجاح"
                else "Previous session restored successfully"
            )
        }
        flushSnapshotToUi(force = true)
    }

    /**
     * تجاهل الجلسة السابقة: يحذف المسودة نهائياً ويصفّر الشاشة بالكامل لبدء جلسة نظيفة
     */
    fun dismissRestoreBanner() {
        opticalVerifiedEpcs.clear()
        cachedPendingSession = null
        activeSegmentStartMs = 0L
        accumulatedScanDurationMs = 0L
        readsInCurrentSecond.set(0)
        inventoryEngine.clearSession()

        applyCategoryFilter(_uiState.value.selectedCategory, preserveScanned = false)
        sessionManager.clearSession()

        if (_uiState.value.isScanning) {
            stopScanning()
        }

        _uiState.update {
            it.copy(
                restoreBanner = null,
                elapsedSeconds = 0L,
                snackbarMessage = if (isArabic) "تم تجاهل الجلسة السابقة وبدء جرد جديد"
                else "Previous session discarded; starting fresh"
            )
        }
        flushSnapshotToUi(force = true)
    }

    fun saveCurrentSessionSnapshot() {
        val scannedDisplays = buildScannedDisplayList()
        if (scannedDisplays.isEmpty()) return
        sessionManager.saveSession(
            activeTargetCatalog.size,
            calculateTotalElapsedMs(),
            _uiState.value.selectedCategory,
            scannedDisplays
        )
    }

    // ==================== الاعتماد والمزامنة والأرشيف ====================

    fun commitCurrentInventory() {
        val api = apiClient ?: return
        if (_uiState.value.isCommitting) return
        if (_uiState.value.isScanning) stopScanning()

        val scannedDisplays = buildScannedDisplayList()
        if (scannedDisplays.isEmpty()) {
            _uiState.update {
                it.copy(
                    snackbarMessage = if (isArabic) "لا توجد قطع ممسوحة لاعتماد الجرد"
                    else "No scanned items to commit"
                )
            }
            return
        }

        _uiState.update { it.copy(isCommitting = true) }

        val state = _uiState.value
        val snapshotItemsForExport = rowsByEpc.values.map { row ->
            ArchiveReportItemDetail(
                epc = row.epc,
                name = row.title,
                karat = row.karat,
                weightGrams = row.weight,
                estimatedValue = row.price,
                status = when (row.status) {
                    ItemAuditStatus.MATCHED -> ArchiveItemStatus.MATCHED
                    ItemAuditStatus.MISSING -> ArchiveItemStatus.MISSING
                    ItemAuditStatus.UNCATALOGUED,
                    ItemAuditStatus.WRONG_CATEGORY -> ArchiveItemStatus.UNCATALOGUED
                }
            )
        }

        persistenceManager.commitInventory(
            inventoryEngine.clientSessionId,
            state.branchId,
            state.expectedCount,
            state.totalReads,
            state.matchedCount,
            scannedDisplays,
            api,
            object : InventoryPersistenceManager.CommitCallback {
                override fun onCommitSuccess(report: JSONObject) {
                    sessionManager.clearSession()
                    opticalVerifiedEpcs.clear()

                    val finalSessionId = report.optString("session_id", inventoryEngine.clientSessionId)
                    val expected = report.optInt("expected_count", state.expectedCount)
                    val found = report.optInt("found_count", state.matchedCount)
                    val missing = report.optInt("missing_count", state.missingCount)

                    val reportUi = CommitReportUi(
                        sessionId = finalSessionId,
                        accuracy = report.optString("accuracy", "${(state.accuracyPercent * 100).toInt()}%"),
                        riskLevel = report.optString("risk_level", "low"),
                        recommendation = report.optString(
                            "recommendation",
                            if (isArabic) "تم اعتماد الجرد وحفظه في السجل الرسمي"
                            else "Inventory committed and archived successfully"
                        ),
                        expectedCount = expected,
                        foundCount = found,
                        missingCount = missing
                    )

                    captureCommittedSessionReport(
                        serverReportCode = finalSessionId,
                        branchName = if (isArabic) "فرع #${state.branchId}" else "Branch #${state.branchId}",
                        auditorName = "-",
                        expectedCount = expected,
                        missingCount = missing,
                        scannedWeightGrams = state.scannedWeightGrams,
                        estimatedValueUsd = state.scannedValueUsd,
                        sessionItems = snapshotItemsForExport
                    )

                    _uiState.update {
                        it.copy(isCommitting = false, commitReport = reportUi)
                    }
                }

                override fun onCommitError(error: String) {
                    _uiState.update {
                        it.copy(isCommitting = false, snackbarMessage = error)
                    }
                }
            }
        )
    }

    fun dismissCommitReport() {
        _uiState.update { it.copy(commitReport = null) }
    }

    fun syncOfflineQueue() {
        val api = apiClient ?: return
        _uiState.update { it.copy(isSyncingOffline = true) }
        persistenceManager.syncOfflineTransactions(api, object : InventoryPersistenceManager.SyncCallback {
            override fun onSyncComplete(syncedCount: Int) {
                val msg = if (isArabic) "تمت مزامنة $syncedCount حركة غير متصلة"
                else "Synced $syncedCount offline records"
                _uiState.update { it.copy(isSyncingOffline = false, snackbarMessage = msg) }
            }

            override fun onSyncError(error: String) {
                _uiState.update { it.copy(isSyncingOffline = false, snackbarMessage = error) }
            }
        })
    }

    fun openArchive() {
        val api = apiClient ?: return
        _uiState.update { it.copy(showArchiveSheet = true, isArchiveLoading = true) }
        persistenceManager.fetchArchiveReports(
            api,
            _uiState.value.branchId,
            object : InventoryPersistenceManager.ArchiveCallback {
                override fun onArchiveLoaded(reports: MutableList<InventoryReport>) {
                    _uiState.update {
                        it.copy(isArchiveLoading = false, archiveReports = reports.toList())
                    }
                }

                override fun onArchiveError(error: String) {
                    _uiState.update {
                        it.copy(isArchiveLoading = false, snackbarMessage = error)
                    }
                }
            }
        )
    }

    fun closeArchive() {
        _uiState.update { it.copy(showArchiveSheet = false, selectedArchiveDetails = null) }
    }

    fun loadArchiveReportDetails(sessionId: String) {
        val api = apiClient ?: return
        persistenceManager.fetchReportDetails(api, sessionId, object : InventoryPersistenceManager.ReportDetailsCallback {
            override fun onDetailsLoaded(items: MutableList<ScannedItemDisplay>) {
                _uiState.update {
                    it.copy(selectedArchiveDetails = sessionId to items.toList())
                }
            }

            override fun onError(error: String) {
                _uiState.update { it.copy(snackbarMessage = error) }
            }
        })
    }

    fun deleteArchiveReport(sessionId: String) {
        val api = apiClient ?: return
        persistenceManager.deleteReport(api, sessionId, object : InventoryPersistenceManager.DeleteCallback {
            override fun onDeleteSuccess() {
                openArchive()
            }

            override fun onDeleteError(error: String) {
                _uiState.update { it.copy(snackbarMessage = error) }
            }
        })
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    // ==================== دوال الربط مع UHFMainActivity ====================

    fun getScannedCount(): Int {
        val s = _uiState.value
        return s.matchedCount + s.uncataloguedCount + s.wrongCategoryCount
    }

    fun isScanning(): Boolean = _uiState.value.isScanning

    fun openScannerSettingsSheet() {
        _uiState.update { it.copy(showScannerSettingsSheet = true) }
    }

    fun closeScannerSettingsSheet() {
        _uiState.update { it.copy(showScannerSettingsSheet = false) }
    }

    fun openSessionSettingsSheet() {
        _uiState.update { it.copy(showSessionSettingsSheet = true) }
    }

    fun closeSessionSettingsSheet() {
        _uiState.update { it.copy(showSessionSettingsSheet = false) }
    }

    private fun calculateTotalElapsedMs(): Long {
        val running = if (activeSegmentStartMs > 0L) {
            SystemClock.elapsedRealtime() - activeSegmentStartMs
        } else 0L
        return accumulatedScanDurationMs + running
    }

    private fun buildScannedDisplayList(): List<ScannedItemDisplay> {
        return rowsByEpc.values
            .filter { it.status != ItemAuditStatus.MISSING }
            .map { row ->
                ScannedItemDisplay(
                    row.epc,
                    row.title,
                    row.karat,
                    row.readCount,
                    row.rssi,
                    row.lastSeenTime
                ).apply {
                    weight = row.weight
                    price = row.price
                }
            }
    }

    override fun onCleared() {
        super.onCleared()
        tickerJob?.cancel()
        saveCurrentSessionSnapshot()
        if (initialized) {
            scanController.cleanup()
            persistenceManager.cleanup()
        }
        uhfSoundEngine?.release()
        uhfSoundEngine = null
        processingExecutor.shutdownNow()
        ioExecutor.shutdownNow()
    }

    // ==================== تفاصيل التقرير المؤرشف والطباعة والتصدير ====================

    fun openArchiveReportDetails(
        reportId: String,
        fallbackCode: String = reportId,
        fallbackDate: String = "",
        fallbackBranch: String = "",
        fallbackMatched: Int = 0,
        fallbackExpected: Int = 0,
        fallbackMissing: Int = 0,
        fallbackWeight: Double = 0.0
    ) {
        _uiState.update {
            it.copy(
                selectedArchiveReportId = reportId,
                isLoadingArchiveDetails = true,
                archiveDetailsError = null,
                archiveReportDetails = null
            )
        }

        val api = apiClient
        if (api == null) {
            _uiState.update {
                it.copy(
                    isLoadingArchiveDetails = false,
                    archiveDetailsError = if (it.isArabic) "تعذر الاتصال بالخادم" else "API client unavailable"
                )
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val method = api.javaClass.methods.firstOrNull {
                    (it.name == "getInventoryReportDetails" || it.name == "getAuditReportDetails") &&
                        it.parameterTypes.size == 2
                }

                if (method != null) {
                    val callbackType = method.parameterTypes[1]
                    val proxy = java.lang.reflect.Proxy.newProxyInstance(
                        callbackType.classLoader,
                        arrayOf(callbackType)
                    ) { _, m, args ->
                        when (m.name) {
                            "onSuccess" -> {
                                val json = args?.firstOrNull() as? JSONObject
                                val parsed = parseArchiveDetailsJson(
                                    json = json,
                                    reportId = reportId,
                                    fallbackCode = fallbackCode,
                                    fallbackDate = fallbackDate,
                                    fallbackBranch = fallbackBranch,
                                    fallbackMatched = fallbackMatched,
                                    fallbackExpected = fallbackExpected,
                                    fallbackMissing = fallbackMissing,
                                    fallbackWeight = fallbackWeight
                                )
                                _uiState.update {
                                    it.copy(
                                        isLoadingArchiveDetails = false,
                                        archiveReportDetails = parsed,
                                        archiveDetailsError = null
                                    )
                                }
                            }
                            "onError" -> {
                                val err = args?.firstOrNull()?.toString()
                                    ?: if (_uiState.value.isArabic) "فشل تحميل تفاصيل التقرير" else "Failed to load report details"
                                _uiState.update {
                                    it.copy(
                                        isLoadingArchiveDetails = false,
                                        archiveDetailsError = err
                                    )
                                }
                            }
                        }
                        null
                    }
                    method.invoke(api, reportId, proxy)
                } else {
                    val summaryOnly = ArchiveReportDetails(
                        reportId = reportId,
                        reportCode = fallbackCode,
                        createdAt = fallbackDate,
                        branchName = fallbackBranch,
                        auditorName = "-",
                        expectedCount = fallbackExpected,
                        matchedCount = fallbackMatched,
                        missingCount = fallbackMissing,
                        uncataloguedCount = 0,
                        scannedWeightGrams = fallbackWeight,
                        estimatedValueUsd = 0.0,
                        items = emptyList()
                    )
                    _uiState.update {
                        it.copy(
                            isLoadingArchiveDetails = false,
                            archiveReportDetails = summaryOnly
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingArchiveDetails = false,
                        archiveDetailsError = e.message
                    )
                }
            }
        }
    }

    fun closeArchiveReportDetails() {
        _uiState.update {
            it.copy(
                selectedArchiveReportId = null,
                archiveReportDetails = null,
                isLoadingArchiveDetails = false,
                archiveDetailsError = null
            )
        }
    }

    fun printSelectedArchiveReport(context: Context) {
        val details = _uiState.value.archiveReportDetails ?: return
        InventoryReportExporter.printOrSavePdf(context, details, _uiState.value.isArabic)
    }

    fun exportSelectedArchiveReportCsv(context: Context) {
        val details = _uiState.value.archiveReportDetails ?: return
        InventoryReportExporter.exportCsv(context, details, _uiState.value.isArabic)
    }

    private fun parseArchiveDetailsJson(
        json: JSONObject?,
        reportId: String,
        fallbackCode: String,
        fallbackDate: String,
        fallbackBranch: String,
        fallbackMatched: Int,
        fallbackExpected: Int,
        fallbackMissing: Int,
        fallbackWeight: Double
    ): ArchiveReportDetails {
        val root = json?.optJSONObject("data") ?: json?.optJSONObject("report") ?: json
        val itemsList = mutableListOf<ArchiveReportItemDetail>()

        fun parseArray(arr: JSONArray?, defaultStatus: ArchiveItemStatus) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val statusStr = obj.optString("status", "").lowercase(Locale.US)
                val status = when {
                    statusStr.contains("miss") -> ArchiveItemStatus.MISSING
                    statusStr.contains("uncat") || statusStr.contains("unknown") -> ArchiveItemStatus.UNCATALOGUED
                    statusStr.contains("match") || statusStr.contains("found") -> ArchiveItemStatus.MATCHED
                    else -> defaultStatus
                }
                itemsList.add(
                    ArchiveReportItemDetail(
                        epc = obj.optString("rfid_epc", obj.optString("epc", obj.optString("tag_epc", "-"))),
                        name = obj.optString("item_title", obj.optString("name", obj.optString("item_name", obj.optString("title", "قطعة مجوهرات")))),
                        karat = obj.optString("gold_karat", obj.optString("karat", obj.optString("purity", ""))),
                        weightGrams = obj.optDouble("net_weight_grams", obj.optDouble("weight", obj.optDouble("weight_grams", 0.0))),
                        estimatedValue = obj.optDouble("retail_price_usd", obj.optDouble("price", obj.optDouble("estimated_value", 0.0))),
                        status = status
                    )
                )
            }
        }

        parseArray(root?.optJSONArray("items"), ArchiveItemStatus.MATCHED)
        parseArray(root?.optJSONArray("matched_items"), ArchiveItemStatus.MATCHED)
        parseArray(root?.optJSONArray("missing_items"), ArchiveItemStatus.MISSING)
        parseArray(root?.optJSONArray("uncatalogued_items"), ArchiveItemStatus.UNCATALOGUED)

        return ArchiveReportDetails(
            reportId = reportId,
            reportCode = root?.optString("report_number", root.optString("code", fallbackCode)) ?: fallbackCode,
            createdAt = root?.optString("created_at", root.optString("date", fallbackDate)) ?: fallbackDate,
            branchName = root?.optString("branch_name", fallbackBranch) ?: fallbackBranch,
            auditorName = root?.optString("auditor_name", root.optString("user_name", "-")) ?: "-",
            expectedCount = root?.optInt("expected_count", fallbackExpected) ?: fallbackExpected,
            matchedCount = root?.optInt("matched_count", fallbackMatched) ?: fallbackMatched,
            missingCount = root?.optInt("missing_count", fallbackMissing) ?: fallbackMissing,
            uncataloguedCount = root?.optInt("uncatalogued_count", 0) ?: 0,
            scannedWeightGrams = root?.optDouble("scanned_weight", fallbackWeight) ?: fallbackWeight,
            estimatedValueUsd = root?.optDouble("estimated_value", 0.0) ?: 0.0,
            items = itemsList
        )
    }

    // ==================== طباعة وتصدير ملخص الجلسة الحالية ====================

    fun captureCommittedSessionReport(
        serverReportCode: String? = null,
        branchName: String = "",
        auditorName: String = "-",
        expectedCount: Int = 0,
        missingCount: Int = 0,
        scannedWeightGrams: Double = 0.0,
        estimatedValueUsd: Double = 0.0,
        sessionItems: List<ArchiveReportItemDetail> = emptyList()
    ) {
        val s = _uiState.value
        val now = Date()
        val autoCode = serverReportCode?.takeIf { it.isNotBlank() }
            ?: "INV-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(now)}"
        val formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(now)

        val resolvedExpected = if (expectedCount > 0) expectedCount else (s.matchedCount + missingCount)

        val details = ArchiveReportDetails(
            reportId = autoCode,
            reportCode = autoCode,
            createdAt = formattedDate,
            branchName = branchName.ifBlank { if (s.isArabic) "الفرع الحالي" else "Current Branch" },
            auditorName = auditorName,
            expectedCount = resolvedExpected,
            matchedCount = s.matchedCount,
            missingCount = missingCount,
            uncataloguedCount = s.uncataloguedCount + s.wrongCategoryCount,
            scannedWeightGrams = scannedWeightGrams,
            estimatedValueUsd = estimatedValueUsd,
            items = sessionItems
        )

        _uiState.update { it.copy(lastCommittedReportDetails = details) }
    }

    fun printCurrentSessionReport(context: Context) {
        val s = _uiState.value
        val details = s.lastCommittedReportDetails ?: buildFallbackCurrentSessionDetails()
        InventoryReportExporter.printOrSavePdf(context, details, s.isArabic)
    }

    fun exportCurrentSessionReportCsv(context: Context) {
        val s = _uiState.value
        val details = s.lastCommittedReportDetails ?: buildFallbackCurrentSessionDetails()
        InventoryReportExporter.exportCsv(context, details, s.isArabic)
    }

    private fun buildFallbackCurrentSessionDetails(): ArchiveReportDetails {
        val s = _uiState.value
        val now = Date()
        val code = "INV-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(now)}"
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(now)

        return ArchiveReportDetails(
            reportId = code,
            reportCode = code,
            createdAt = dateStr,
            branchName = if (s.isArabic) "الفرع الحالي" else "Current Branch",
            auditorName = "-",
            expectedCount = s.expectedCount,
            matchedCount = s.matchedCount,
            missingCount = s.missingCount,
            uncataloguedCount = s.uncataloguedCount + s.wrongCategoryCount,
            scannedWeightGrams = s.scannedWeightGrams,
            estimatedValueUsd = s.scannedValueUsd,
            items = emptyList()
        )
    }
}
