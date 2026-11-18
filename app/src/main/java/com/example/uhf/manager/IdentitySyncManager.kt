package com.example.uhf.manager

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.example.uhf.api.MiraApiClient
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.pow

/**
 * IdentitySyncManager - الإصدار 4.1 (Kotlin Enterprise Edition)
 */
class IdentitySyncManager(
    context: Context,
    private val executor: ExecutorService
) {
    companion object {
        private const val TAG = "IdentitySyncManager"
        private const val OFFLINE_PREFS = "identity_studio_cache"

        // ==================== Prefs Keys ====================
        private const val KEY_PENDING_ENCODES = "pending_encodes"
        private const val KEY_DRAFT = "current_draft"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_TOTAL_SYNCED = "total_synced"
        private const val KEY_TOTAL_FAILED = "total_failed"
        private const val KEY_RETRY_SUCCESSES = "retry_successes"
        private const val KEY_LAST_BACKOFF_MS = "last_backoff_ms"

        // ==================== Item Meta Keys ====================
        private const val META_ID = "_id"
        private const val META_ATTEMPTS = "_sync_attempts"
        private const val META_SAVED_AT = "_saved_at"
        private const val META_LAST_ATTEMPT = "_last_attempt"
        private const val META_LAST_ERROR = "_last_error"
        private const val META_STATUS = "_sync_status"
        private const val META_NEXT_RETRY_AT = "_next_retry_at"
        private const val META_SYNCED_AT = "_synced_at"

        // ==================== Status Values ====================
        const val STATUS_PENDING = "pending"
        const val STATUS_SYNCING = "syncing"
        const val STATUS_FAILED = "failed"
        const val STATUS_FAILED_MAX = "failed_max_attempts"
        const val STATUS_SYNCED = "synced"

        // ==================== Constants ====================
        private const val MAX_PENDING_ITEMS = 100
        private const val MAX_SYNC_ATTEMPTS = 5
        private const val INITIAL_RETRY_DELAY_MS = 2000L
        private const val MAX_RETRY_DELAY_MS = 60000L
    }

    // ==================== Callbacks ====================

    interface SyncCallback {
        fun onSyncComplete(syncedCount: Int)
        fun onSyncError(error: String?)
    }

    interface DetailedSyncCallback {
        fun onSyncStart(totalCount: Int)
        fun onItemSynced(itemIndex: Int, totalCount: Int, epc: String)
        fun onItemFailed(itemIndex: Int, totalCount: Int, epc: String, error: String)
        fun onSyncComplete(syncedCount: Int, failedCount: Int)
        fun onSyncError(error: String?)
    }

    interface SingleItemCallback {
        fun onSuccess(epc: String)
        fun onError(epc: String, error: String)
    }

    interface SyncLogsCallback {
        fun onLoaded(logs: List<SyncLogItem>)
    }

    interface StatsCallback {
        fun onLoaded(stats: SyncStats)
    }

    // ==================== Data Models ====================

    class SyncLogItem private constructor(b: Builder) {
        @JvmField val id: String = b.id
        @JvmField val epc: String = b.epc
        @JvmField val title: String = b.title
        @JvmField val karat: String = b.karat
        @JvmField val grossWeight: Double = b.grossWeight
        @JvmField val priceUSD: Double = b.priceUSD
        @JvmField val category: String = b.category
        @JvmField val location: String = b.location
        @JvmField val branchId: Int = b.branchId
        @JvmField val userId: Int = b.userId
        @JvmField val savedAt: Long = b.savedAt
        @JvmField val lastAttempt: Long = b.lastAttempt
        @JvmField val nextRetryAt: Long = b.nextRetryAt
        @JvmField val attempts: Int = b.attempts
        @JvmField val status: String = b.status
        @JvmField val lastError: String = b.lastError
        @JvmField val payload: JSONObject = b.payload

        fun getFormattedSavedAt(): String {
            return formatDate(savedAt, "dd/MM/yyyy HH:mm")
        }

        fun getFormattedLastAttempt(): String {
            return if (lastAttempt <= 0L) "-" else formatDate(lastAttempt, "dd/MM HH:mm")
        }

        fun getFormattedNextRetry(): String {
            return if (nextRetryAt <= 0L) "-" else formatDate(nextRetryAt, "dd/MM HH:mm")
        }

        fun isPending(): Boolean {
            return STATUS_PENDING == status || STATUS_SYNCING == status
        }

        fun isFailed(): Boolean {
            return STATUS_FAILED == status || STATUS_FAILED_MAX == status
        }

        fun canRetry(): Boolean {
            return isFailed() && attempts < MAX_SYNC_ATTEMPTS
        }

        class Builder {
            internal var id: String = ""
            internal var epc: String = ""
            internal var title: String = ""
            internal var karat: String = ""
            internal var grossWeight: Double = 0.0
            internal var priceUSD: Double = 0.0
            internal var category: String = ""
            internal var location: String = ""
            internal var branchId: Int = 0
            internal var userId: Int = 0
            internal var savedAt: Long = 0L
            internal var lastAttempt: Long = 0L
            internal var nextRetryAt: Long = 0L
            internal var attempts: Int = 0
            internal var status: String = STATUS_PENDING
            internal var lastError: String = ""
            internal var payload: JSONObject = JSONObject()

            fun id(v: String) = apply { this.id = v }
            fun epc(v: String) = apply { this.epc = v }
            fun title(v: String) = apply { this.title = v }
            fun karat(v: String) = apply { this.karat = v }
            fun grossWeight(v: Double) = apply { this.grossWeight = v }
            fun priceUSD(v: Double) = apply { this.priceUSD = v }
            fun category(v: String) = apply { this.category = v }
            fun location(v: String) = apply { this.location = v }
            fun branchId(v: Int) = apply { this.branchId = v }
            fun userId(v: Int) = apply { this.userId = v }
            fun savedAt(v: Long) = apply { this.savedAt = v }
            fun lastAttempt(v: Long) = apply { this.lastAttempt = v }
            fun nextRetryAt(v: Long) = apply { this.nextRetryAt = v }
            fun attempts(v: Int) = apply { this.attempts = v }
            fun status(v: String) = apply { this.status = v }
            fun lastError(v: String) = apply { this.lastError = v }
            fun payload(v: JSONObject) = apply { this.payload = v }

            fun build(): SyncLogItem = SyncLogItem(this)
        }

        companion object {
            private fun formatDate(ms: Long, pattern: String): String {
                return try {
                    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
                } catch (_: Exception) {
                    ""
                }
            }
        }
    }

    class SyncStats(
        @JvmField val lastSyncTime: Long,
        @JvmField val totalSynced: Int,
        @JvmField val totalFailed: Int,
        @JvmField val retrySuccesses: Int,
        @JvmField val pending: Int,
        @JvmField val lastBackoffMs: Long
    ) {
        fun getSuccessRate(): String {
            val total = totalSynced + totalFailed
            if (total == 0) return "—"
            return String.format(Locale.US, "%.1f%%", (totalSynced * 100.0) / total)
        }

        @JvmOverloads
        fun getLastSyncFormatted(isArabic: Boolean = true): String {
            if (lastSyncTime <= 0L) {
                return if (isArabic) "أبداً" else "Never"
            }
            return try {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(lastSyncTime))
            } catch (_: Exception) {
                "—"
            }
        }
    }

    // ==================== Fields ====================
    private val appContext: Context = context.applicationContext
    private val offlineCache: SharedPreferences =
        appContext.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE)
    private val apiClient: MiraApiClient = MiraApiClient.getInstance(appContext)

    private val isSyncing = AtomicBoolean(false)
    private val isShutdown = AtomicBoolean(false)
    private val cachedPendingCount = AtomicInteger(-1)
    private val pendingLock = Any()

    private val inFlightItemIds: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap())

    private val pendingStatsSynced = AtomicInteger(0)
    private val pendingStatsFailed = AtomicInteger(0)

    // ==================== CREATE ====================

    fun saveEncodeOffline(payload: JSONObject?) {
        if (payload == null) {
            Log.w(TAG, "saveEncodeOffline: payload is null")
            return
        }

        synchronized(pendingLock) {
            try {
                val json = offlineCache.getString(KEY_PENDING_ENCODES, "[]") ?: "[]"
                val arr = JSONArray(json)

                val enriched = JSONObject(payload.toString())
                var targetEpc = enriched.optString("epc", "").trim().uppercase(Locale.US)
                if (targetEpc.isEmpty()) {
                    targetEpc = "TEMP_${UUID.randomUUID().toString().substring(0, 8).uppercase(Locale.US)}"
                }
                enriched.put("epc", targetEpc)

                for (i in arr.length() - 1 downTo 0) {
                    val existing = arr.optJSONObject(i) ?: continue
                    val existingEpc = existing.optString("epc", "").trim()
                    val existingStatus = existing.optString(META_STATUS, STATUS_PENDING)
                    val isPending = STATUS_PENDING == existingStatus || STATUS_SYNCING == existingStatus

                    if (targetEpc.equals(existingEpc, ignoreCase = true) && isPending) {
                        arr.remove(i)
                        Log.d(TAG, "Replaced pending record for EPC=$targetEpc")
                    }
                }

                enriched.put(META_ID, "SYNC_${UUID.randomUUID()}")
                enriched.put(META_ATTEMPTS, 0)
                enriched.put(META_SAVED_AT, System.currentTimeMillis())
                enriched.put(META_LAST_ATTEMPT, 0L)
                enriched.put(META_LAST_ERROR, "")
                enriched.put(META_STATUS, STATUS_PENDING)
                enriched.put(META_NEXT_RETRY_AT, 0L)

                arr.put(enriched)

                while (arr.length() > MAX_PENDING_ITEMS) {
                    arr.remove(0)
                }

                offlineCache.edit()
                    .putString(KEY_PENDING_ENCODES, arr.toString())
                    .apply()

                cachedPendingCount.set(arr.length())
                Log.d(TAG, "✅ Saved offline: EPC=$targetEpc, total=${arr.length()}")
            } catch (e: Exception) {
                Log.e(TAG, "saveEncodeOffline error: ${e.message}", e)
            }
        }
    }

    // ==================== READ ====================

    val pendingCount: Int
        get() {
            val cached = cachedPendingCount.get()
            if (cached >= 0) return cached

            return try {
                val json = offlineCache.getString(KEY_PENDING_ENCODES, "[]") ?: "[]"
                val count = JSONArray(json).length()
                cachedPendingCount.set(count)
                count
            } catch (_: Exception) {
                0
            }
        }

    fun getPendingItems(): JSONArray {
        synchronized(pendingLock) {
            return try {
                val json = offlineCache.getString(KEY_PENDING_ENCODES, "[]") ?: "[]"
                JSONArray(json)
            } catch (_: Exception) {
                JSONArray()
            }
        }
    }

    fun getAllSyncLogsAsync(callback: SyncLogsCallback?) {
        if (callback == null || executor.isShutdown) return
        executor.execute {
            val logs = getAllSyncLogsInternal()
            callback.onLoaded(logs)
        }
    }

    fun getAllSyncLogs(): List<SyncLogItem> {
        return getAllSyncLogsInternal()
    }

    private fun getAllSyncLogsInternal(): List<SyncLogItem> {
        synchronized(pendingLock) {
            val list = ArrayList<SyncLogItem>()
            try {
                val json = offlineCache.getString(KEY_PENDING_ENCODES, "[]") ?: "[]"
                val arr = JSONArray(json)

                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val item = SyncLogItem.Builder()
                        .id(obj.optString(META_ID, "ID_$i"))
                        .epc(obj.optString("epc", ""))
                        .title(obj.optString("title", ""))
                        .karat(obj.optString("purity_code", obj.optString("karat", "")))
                        .grossWeight(obj.optDouble("gross_weight", 0.0))
                        .priceUSD(
                            obj.optDouble(
                                "retail_price_usd",
                                obj.optDouble("retail_price", 0.0)
                            )
                        )
                        .category(obj.optString("category", ""))
                        .location(obj.optString("location", ""))
                        .branchId(obj.optInt("branch_id", 0))
                        .userId(obj.optInt("user_id", 0))
                        .savedAt(obj.optLong(META_SAVED_AT, 0L))
                        .lastAttempt(obj.optLong(META_LAST_ATTEMPT, 0L))
                        .nextRetryAt(obj.optLong(META_NEXT_RETRY_AT, 0L))
                        .attempts(obj.optInt(META_ATTEMPTS, 0))
                        .status(obj.optString(META_STATUS, STATUS_PENDING))
                        .lastError(obj.optString(META_LAST_ERROR, ""))
                        .payload(obj)
                        .build()

                    list.add(item)
                }
            } catch (e: Exception) {
                Log.e(TAG, "getAllSyncLogsInternal error: ${e.message}")
            }
            return list
        }
    }

    fun getSyncLogById(id: String?): SyncLogItem? {
        if (id == null) return null
        return getAllSyncLogsInternal().firstOrNull { id == it.id }
    }

    // ==================== UPDATE ====================

    fun updateSyncLog(id: String?, newPayload: JSONObject?): Boolean {
        if (id == null || newPayload == null) return false

        synchronized(pendingLock) {
            try {
                val json = offlineCache.getString(KEY_PENDING_ENCODES, "[]") ?: "[]"
                val arr = JSONArray(json)
                var updated = false

                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i)
                    if (obj != null && id == obj.optString(META_ID)) {
                        val cloned = JSONObject(newPayload.toString())
                        cloned.put(META_ID, id)
                        cloned.put(META_ATTEMPTS, obj.optInt(META_ATTEMPTS, 0))
                        cloned.put(META_SAVED_AT, obj.optLong(META_SAVED_AT, System.currentTimeMillis()))
                        cloned.put(META_LAST_ATTEMPT, 0L)

                        val oldError = obj.optString(META_LAST_ERROR, "")
                        if (oldError.isNotEmpty()) {
                            cloned.put("_previous_error", oldError)
                        }
                        cloned.put(META_LAST_ERROR, "")
                        cloned.put(META_STATUS, STATUS_PENDING)
                        cloned.put(META_NEXT_RETRY_AT, 0L)

                        arr.put(i, cloned)
                        updated = true
                        break
                    }
                }

                if (updated) {
                    offlineCache.edit()
                        .putString(KEY_PENDING_ENCODES, arr.toString())
                        .apply()
                    cachedPendingCount.set(-1)
                    Log.d(TAG, "✅ Updated sync log: $id")
                }
                return updated
            } catch (e: Exception) {
                Log.e(TAG, "updateSyncLog error: ${e.message}")
                return false
            }
        }
    }

    // ==================== DELETE ====================

    fun deleteSyncLog(id: String?): Boolean {
        if (id == null) return false

        synchronized(pendingLock) {
            try {
                val json = offlineCache.getString(KEY_PENDING_ENCODES, "[]") ?: "[]"
                val arr = JSONArray(json)
                val newArr = JSONArray()
                var deleted = false

                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (id == obj.optString(META_ID)) {
                        deleted = true
                    } else {
                        newArr.put(obj)
                    }
                }

                if (deleted) {
                    offlineCache.edit()
                        .putString(KEY_PENDING_ENCODES, newArr.toString())
                        .apply()
                    cachedPendingCount.set(newArr.length())
                    Log.d(TAG, "✅ Deleted sync log: $id")
                }
                return deleted
            } catch (e: Exception) {
                Log.e(TAG, "deleteSyncLog error: ${e.message}")
                return false
            }
        }
    }

    fun removePendingEncode(epc: String?) {
        if (epc.isNullOrBlank()) return

        synchronized(pendingLock) {
            try {
                val json = offlineCache.getString(KEY_PENDING_ENCODES, "[]") ?: "[]"
                val arr = JSONArray(json)
                val newArr = JSONArray()
                val cleanEpc = epc.trim()
                var removed = false

                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (!removed && cleanEpc.equals(obj.optString("epc", "").trim(), ignoreCase = true)) {
                        removed = true
                        continue
                    }
                    newArr.put(obj)
                }

                offlineCache.edit()
                    .putString(KEY_PENDING_ENCODES, newArr.toString())
                    .apply()
                cachedPendingCount.set(newArr.length())
            } catch (e: Exception) {
                Log.e(TAG, "removePendingEncode error", e)
            }
        }
    }

    fun clearAllPending() {
        synchronized(pendingLock) {
            try {
                offlineCache.edit().putString(KEY_PENDING_ENCODES, "[]").apply()
                cachedPendingCount.set(0)
            } catch (e: Exception) {
                Log.e(TAG, "clearAllPending error", e)
            }
        }
    }

    // ==================== SYNC ====================

    fun syncPendingEncodes(branchId: Int, callback: SyncCallback?) {
        syncPendingEncodesDetailed(branchId, object : DetailedSyncCallback {
            override fun onSyncStart(totalCount: Int) {}
            override fun onItemSynced(itemIndex: Int, totalCount: Int, epc: String) {}
            override fun onItemFailed(itemIndex: Int, totalCount: Int, epc: String, error: String) {}
            override fun onSyncComplete(syncedCount: Int, failedCount: Int) {
                callback?.onSyncComplete(syncedCount)
            }
            override fun onSyncError(error: String?) {
                callback?.onSyncError(error)
            }
        })
    }

    @JvmOverloads
    fun syncPendingEncodesDetailed(
        branchId: Int,
        callback: DetailedSyncCallback?,
        respectRetrySchedule: Boolean = false
    ) {
        if (isShutdown.get()) {
            callback?.onSyncError("Manager is shutdown")
            return
        }

        if (!isSyncing.compareAndSet(false, true)) {
            Log.w(TAG, "⚠️ Sync already in progress")
            callback?.onSyncError("Sync already in progress")
            return
        }

        if (executor.isShutdown) {
            isSyncing.set(false)
            callback?.onSyncError("Executor is shutdown")
            return
        }

        executor.execute {
            try {
                if (!isNetworkAvailable()) {
                    callback?.onSyncError("No network connection")
                    return@execute
                }

                val arr = synchronized(pendingLock) {
                    val json = offlineCache.getString(KEY_PENDING_ENCODES, "[]") ?: "[]"
                    JSONArray(json)
                }

                val totalCount = arr.length()
                if (totalCount == 0) {
                    callback?.onSyncComplete(0, 0)
                    return@execute
                }

                callback?.onSyncStart(totalCount)

                val remainingArr = JSONArray()
                var synced = 0
                var failed = 0
                var retrySuccessesThisRun = 0
                val now = System.currentTimeMillis()

                for (i in 0 until arr.length()) {
                    if (isShutdown.get()) {
                        for (j in i until arr.length()) {
                            arr.optJSONObject(j)?.let { remainingArr.put(it) }
                        }
                        break
                    }

                    val rawObj = arr.optJSONObject(i) ?: continue
                    val payload = JSONObject(rawObj.toString())
                    val epc = payload.optString("epc", "Unknown")
                    val attempts = payload.optInt(META_ATTEMPTS, 0)
                    val nextRetryAt = payload.optLong(META_NEXT_RETRY_AT, 0L)

                    if (attempts >= MAX_SYNC_ATTEMPTS) {
                        payload.put(META_STATUS, STATUS_FAILED_MAX)
                        remainingArr.put(payload)
                        failed++
                        callback?.onItemFailed(i, totalCount, epc, "Max attempts reached")
                        continue
                    }

                    if (respectRetrySchedule && nextRetryAt > now) {
                        remainingArr.put(payload)
                        continue
                    }

                    try {
                        if (!payload.has("branch_id") || payload.optInt("branch_id", 0) <= 0) {
                            payload.put("branch_id", branchId)
                        }

                        val newAttempts = attempts + 1
                        payload.put(META_ATTEMPTS, newAttempts)
                        payload.put(META_LAST_ATTEMPT, System.currentTimeMillis())
                        payload.put(META_STATUS, STATUS_SYNCING)

                        val response = apiClient.encodeItem(payload)

                        if (response != null && response.isSuccess) {
                            synced++
                            if (newAttempts > 1) {
                                retrySuccessesThisRun++
                            }
                            payload.put(META_STATUS, STATUS_SYNCED)
                            payload.put(META_SYNCED_AT, System.currentTimeMillis())
                            callback?.onItemSynced(i, totalCount, epc)
                        } else {
                            val errorMsg = extractCleanErrorMessage(response)
                            val finalStatus = if (newAttempts >= MAX_SYNC_ATTEMPTS) {
                                STATUS_FAILED_MAX
                            } else {
                                STATUS_FAILED
                            }
                            val backoff = calculateBackoffDelay(attempts)

                            payload.put(META_STATUS, finalStatus)
                            payload.put(META_LAST_ERROR, errorMsg)
                            payload.put(META_NEXT_RETRY_AT, System.currentTimeMillis() + backoff)

                            remainingArr.put(payload)
                            failed++
                            callback?.onItemFailed(i, totalCount, epc, errorMsg)

                            if (response != null && response.isNetworkError) {
                                Log.w(TAG, "Network error — aborting remaining sync")
                                for (j in (i + 1) until arr.length()) {
                                    arr.optJSONObject(j)?.let { remainingArr.put(it) }
                                }
                                break
                            }
                        }
                    } catch (e: Exception) {
                        val newAttempts = payload.optInt(META_ATTEMPTS, attempts + 1)
                        val finalStatus = if (newAttempts >= MAX_SYNC_ATTEMPTS) {
                            STATUS_FAILED_MAX
                        } else {
                            STATUS_FAILED
                        }
                        val errText = e.message ?: "unknown"
                        payload.put(META_STATUS, finalStatus)
                        payload.put(META_LAST_ERROR, errText)
                        remainingArr.put(payload)
                        failed++
                        callback?.onItemFailed(i, totalCount, epc, errText)
                    }
                }

                synchronized(pendingLock) {
                    offlineCache.edit()
                        .putString(KEY_PENDING_ENCODES, remainingArr.toString())
                        .apply()
                    cachedPendingCount.set(remainingArr.length())
                }

                accumulateStats(synced, failed, retrySuccessesThisRun)
                callback?.onSyncComplete(synced, failed)
            } catch (e: Exception) {
                Log.e(TAG, "syncPendingEncodesDetailed error: ${e.message}", e)
                callback?.onSyncError(e.message ?: "Unknown sync error")
            } finally {
                isSyncing.set(false)
                flushStatsIfNeeded()
            }
        }
    }

    fun syncSingleItem(id: String?, branchId: Int, callback: SingleItemCallback?) {
        if (id.isNullOrBlank()) {
            callback?.onError("", "Invalid ID")
            return
        }

        if (isShutdown.get()) {
            callback?.onError("", "Manager is shutdown")
            return
        }

        if (isSyncing.get()) {
            callback?.onError("", "Sync already in progress")
            return
        }

        if (!inFlightItemIds.add(id)) {
            callback?.onError("", "Item sync already in progress")
            return
        }

        if (executor.isShutdown) {
            inFlightItemIds.remove(id)
            callback?.onError("", "Executor is shutdown")
            return
        }

        executor.execute {
            try {
                if (!isNetworkAvailable()) {
                    callback?.onError("", "No network")
                    return@execute
                }

                val item = getSyncLogById(id)
                if (item == null) {
                    callback?.onError("", "Item not found")
                    return@execute
                }

                if (item.attempts >= MAX_SYNC_ATTEMPTS) {
                    callback?.onError(item.epc, "Max attempts reached")
                    return@execute
                }

                val payload = JSONObject(item.payload.toString())
                if (!payload.has("branch_id") || payload.optInt("branch_id", 0) <= 0) {
                    payload.put("branch_id", branchId)
                }

                val newAttempts = item.attempts + 1
                payload.put(META_ATTEMPTS, newAttempts)
                payload.put(META_LAST_ATTEMPT, System.currentTimeMillis())
                payload.put(META_STATUS, STATUS_SYNCING)

                val response = apiClient.encodeItem(payload)

                if (response != null && response.isSuccess) {
                    deleteSyncLog(id)
                    accumulateStats(
                        synced = 1,
                        failed = 0,
                        retrySuccesses = if (newAttempts > 1) 1 else 0
                    )
                    flushStatsIfNeeded()
                    callback?.onSuccess(item.epc)
                } else {
                    val errorMsg = extractCleanErrorMessage(response)
                    val finalStatus = if (newAttempts >= MAX_SYNC_ATTEMPTS) {
                        STATUS_FAILED_MAX
                    } else {
                        STATUS_FAILED
                    }
                    payload.put(META_STATUS, finalStatus)
                    payload.put(META_LAST_ERROR, errorMsg)
                    payload.put(
                        META_NEXT_RETRY_AT,
                        System.currentTimeMillis() + calculateBackoffDelay(item.attempts)
                    )
                    updateSyncLogPayload(id, payload)
                    accumulateStats(synced = 0, failed = 1, retrySuccesses = 0)
                    flushStatsIfNeeded()
                    callback?.onError(item.epc, errorMsg)
                }
            } catch (e: Exception) {
                callback?.onError("", e.message ?: "unknown")
            } finally {
                inFlightItemIds.remove(id)
            }
        }
    }

    fun retryItemNow(id: String?, branchId: Int, callback: SingleItemCallback?) {
        if (id.isNullOrBlank()) {
            callback?.onError("", "Invalid ID")
            return
        }

        synchronized(pendingLock) {
            try {
                val json = offlineCache.getString(KEY_PENDING_ENCODES, "[]") ?: "[]"
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i)
                    if (obj != null && id == obj.optString(META_ID)) {
                        obj.put(META_NEXT_RETRY_AT, 0L)
                        break
                    }
                }
                offlineCache.edit()
                    .putString(KEY_PENDING_ENCODES, arr.toString())
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "retryItemNow pre-error: ${e.message}")
            }
        }

        syncSingleItem(id, branchId, callback)
    }

    private fun updateSyncLogPayload(id: String, newPayload: JSONObject) {
        synchronized(pendingLock) {
            try {
                val json = offlineCache.getString(KEY_PENDING_ENCODES, "[]") ?: "[]"
                val arr = JSONArray(json)

                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i)
                    if (obj != null && id == obj.optString(META_ID)) {
                        arr.put(i, newPayload)
                        break
                    }
                }

                offlineCache.edit()
                    .putString(KEY_PENDING_ENCODES, arr.toString())
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "updateSyncLogPayload error: ${e.message}")
            }
        }
    }

    // ==================== Utilities ====================

    fun isEpcDuplicateLocally(epc: String?): Boolean {
        if (epc.isNullOrBlank()) return false
        val cleanEpc = epc.trim().uppercase(Locale.US)

        synchronized(pendingLock) {
            try {
                val pendingJson = offlineCache.getString(KEY_PENDING_ENCODES, "[]") ?: "[]"
                val pendingArray = JSONArray(pendingJson)

                for (i in 0 until pendingArray.length()) {
                    val item = pendingArray.optJSONObject(i) ?: continue
                    val storedEpc = item.optString("epc", "").trim().uppercase(Locale.US)
                    if (cleanEpc == storedEpc) return true
                }

                val draftJson = offlineCache.getString(KEY_DRAFT, null)
                if (draftJson != null) {
                    val draft = JSONObject(draftJson)
                    val draftEpc = draft.optString("epc", "").trim().uppercase(Locale.US)
                    if (cleanEpc == draftEpc) return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "isEpcDuplicateLocally error: ${e.message}")
            }
        }
        return false
    }

    @Deprecated("Use getStatsAsync() instead")
    fun getSyncStats(): String {
        return try {
            val lastSync = offlineCache.getLong(KEY_LAST_SYNC_TIME, 0L)
            val totalSynced = offlineCache.getInt(KEY_TOTAL_SYNCED, 0)
            val totalFailed = offlineCache.getInt(KEY_TOTAL_FAILED, 0)

            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val lastSyncStr = if (lastSync > 0L) sdf.format(Date(lastSync)) else "Never"

            String.format(
                Locale.US,
                "Last Sync: %s\nTotal Synced: %d\nTotal Failed: %d\nPending: %d",
                lastSyncStr,
                totalSynced,
                totalFailed,
                pendingCount
            )
        } catch (_: Exception) {
            "Stats unavailable"
        }
    }

    fun getStatsAsync(callback: StatsCallback?) {
        if (callback == null || executor.isShutdown) return
        executor.execute {
            try {
                val lastSync = offlineCache.getLong(KEY_LAST_SYNC_TIME, 0L)
                val totalSynced = offlineCache.getInt(KEY_TOTAL_SYNCED, 0)
                val totalFailed = offlineCache.getInt(KEY_TOTAL_FAILED, 0)
                val retrySuccesses = offlineCache.getInt(KEY_RETRY_SUCCESSES, 0)
                val lastBackoff = offlineCache.getLong(KEY_LAST_BACKOFF_MS, 0L)

                val stats = SyncStats(
                    lastSync,
                    totalSynced,
                    totalFailed,
                    retrySuccesses,
                    pendingCount,
                    lastBackoff
                )
                callback.onLoaded(stats)
            } catch (_: Exception) {
                callback.onLoaded(SyncStats(0L, 0, 0, 0, 0, 0L))
            }
        }
    }

    fun isSyncing(): Boolean {
        return isSyncing.get()
    }

    fun isShutdown(): Boolean {
        return isShutdown.get()
    }

    fun shutdown() {
        isShutdown.set(true)
        flushStatsIfNeeded()
        Log.d(TAG, "IdentitySyncManager shutdown")
    }

    // ==================== Private Helpers ====================

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val capabilities = cm.getNetworkCapabilities(network) ?: return false
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                info != null && info.isConnected
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun extractCleanErrorMessage(response: MiraApiClient.ApiResponse?): String {
    if (response == null) return "Unknown error"

    val data = response.data
    if (data != null) {
        try {
            val errorObj = data.optJSONObject("error")
            if (errorObj != null) {
                val msgAr = errorObj.optString("message", "")
                val msgEn = errorObj.optString("message_en", "")
                var msg = if (msgAr.isNotEmpty()) msgAr else msgEn

                val details = errorObj.optJSONObject("details")
                if (details != null) {
                    val suggestion = details.optString("suggestion", "")
                    if (suggestion.isNotEmpty() && !msg.contains(suggestion)) {
                        msg = "$msg\n\n💡 $suggestion"
                    }
                }
                if (msg.isNotEmpty()) return msg
            }

            val rootMsg = data.optString("message", "")
            if (rootMsg.isNotEmpty() && !rootMsg.startsWith("{")) {
                return rootMsg
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractCleanErrorMessage: data parse error", e)
        }
    }

    val respMsg = response.message
    if (!respMsg.isNullOrEmpty() && !respMsg.startsWith("{")) {
        return respMsg
    }

    val respErr = response.error
    if (!respErr.isNullOrEmpty() && !respErr.startsWith("{")) {
        return respErr
    }

    if (!respErr.isNullOrBlank() && respErr.trim().startsWith("{")) {
        try {
            val json = JSONObject(respErr)
            val errorObj = json.optJSONObject("error")
            if (errorObj != null) {
                val msgAr = errorObj.optString("message", "")
                val msgEn = errorObj.optString("message_en", "")
                val msg = if (msgAr.isNotEmpty()) msgAr else msgEn
                if (msg.isNotEmpty()) return msg
            }
        } catch (_: Exception) {
        }
    }

    return "❌ فشل العملية"
}

    private fun calculateBackoffDelay(attempts: Int): Long {
        val delay = INITIAL_RETRY_DELAY_MS * 2.0.pow(attempts.toDouble()).toLong()
        return min(delay, MAX_RETRY_DELAY_MS)
    }

    private fun accumulateStats(synced: Int, failed: Int, retrySuccesses: Int) {
        pendingStatsSynced.addAndGet(synced)
        pendingStatsFailed.addAndGet(failed)
        if (retrySuccesses > 0) {
            try {
                val current = offlineCache.getInt(KEY_RETRY_SUCCESSES, 0)
                offlineCache.edit()
                    .putInt(KEY_RETRY_SUCCESSES, current + retrySuccesses)
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "accumulateStats retry error", e)
            }
        }
    }

    private fun flushStatsIfNeeded() {
        val sync = pendingStatsSynced.getAndSet(0)
        val fail = pendingStatsFailed.getAndSet(0)
        if (sync == 0 && fail == 0) return

        try {
            val totalSynced = offlineCache.getInt(KEY_TOTAL_SYNCED, 0) + sync
            val totalFailed = offlineCache.getInt(KEY_TOTAL_FAILED, 0) + fail

            offlineCache.edit()
                .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
                .putInt(KEY_TOTAL_SYNCED, totalSynced)
                .putInt(KEY_TOTAL_FAILED, totalFailed)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "flushStatsIfNeeded error", e)
        }
    }

    private fun saveSyncStats(synced: Int, failed: Int) {
        accumulateStats(synced, failed, 0)
        flushStatsIfNeeded()
    }

    fun getHealthReport(): String {
        return buildString {
            append("🏥 IdentitySyncManager Health\n")
            append("─────────────\n")
            append("Executor: ").append(if (executor.isShutdown) "SHUTDOWN" else "OK").append("\n")
            append("Syncing: ").append(isSyncing.get()).append("\n")
            append("Shutdown: ").append(isShutdown.get()).append("\n")
            append("Pending: ").append(pendingCount).append("\n")
            append("Network: ").append(if (isNetworkAvailable()) "OK" else "OFFLINE").append("\n")
        }
    }
}
