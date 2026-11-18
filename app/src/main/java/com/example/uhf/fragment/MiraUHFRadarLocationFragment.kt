package com.example.uhf.fragment

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.FragmentTransaction
import com.example.uhf.R
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.api.MiraApiClient
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.manager.MiraOrgProfileManager
import com.example.uhf.manager.MiraSettingsManager
import com.example.uhf.manager.PermissionManager
import com.example.uhf.MiraRfid.MiraIRFIDReader
import com.example.uhf.MiraRfid.RFIDManager
import com.example.uhf.navigation.NavigationManager
import com.example.uhf.utils.MiraFileLogger
import com.example.uhf.view.CircleSeekBar
import com.example.uhf.view.RadarView
import com.google.android.material.button.MaterialButton
import com.rscja.deviceapi.entity.UHFTAGInfo
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  MiraUHFRadarLocationFragment v3.1 — Real RSSI Radar with Background Polling
 * ═══════════════════════════════════════════════════════════════════════════
 */
class MiraUHFRadarLocationFragment : KeyDwonFragment() {

    companion object {
        private const val TAG = "MiraRadarLocation"
        private const val POLL_INTERVAL_MS = 100L
        private const val BEEP_THROTTLE_MS = 80L
        private const val RSSI_NEAR_DBM = -35 // ملاصق
        private const val RSSI_FAR_DBM = -85  // بعيد
        private const val RSSI_NEAR_PCT = 100
        private const val RSSI_FAR_PCT = 5
    }

    private var mContext: UHFMainActivity? = null
    private var settingsManager: MiraSettingsManager? = null
    private var branchesManager: MiraBranchesManager? = null
    private var orgProfile: MiraOrgProfileManager? = null
    private var permissionManager: PermissionManager? = null
    private var rfidManager: RFIDManager? = null

    private var simulationMode = false
    var isArabic: Boolean = true

    // UI Elements
    private var radarView: RadarView? = null
    private var etEPC: EditText? = null
    private var btStart: Button? = null
    private var btStop: Button? = null
    private var btnExportResults: Button? = null
    private var btnFetchMissing: Button? = null
    private var btnMarkFound: MaterialButton? = null
    private var seekBarPower: CircleSeekBar? = null
    private var rssiBar: ProgressBar? = null
    private var tvProximityLevel: TextView? = null
    private var tvSignalStrength: TextView? = null
    private var tvEstimatedDistance: TextView? = null
    private var layoutMiraRadarInfo: LinearLayout? = null
    private var tvRadarItemStatus: TextView? = null
    private var tvRadarItemName: TextView? = null
    private var tvRadarItemDetails: TextView? = null
    private var tvAuthorizedCount: TextView? = null
    private var tvDeniedCount: TextView? = null
    private var tvUnknownCount: TextView? = null
    private var tvRecentScans: TextView? = null
    private var tvRadarMode: TextView? = null
    private var tvReaderStatus: TextView? = null
    private var btnModeSingle: Button? = null
    private var btnModeOpen: Button? = null
    private var btnModeGate: Button? = null
    private var btnRadarClose: ImageButton? = null

    // State
    @Volatile private var inventoryFlag = false
    @Volatile private var pollRunning = false
    private var pollThread: Thread? = null

    private var targetEpc = ""
    private var progress = 5
    private var currentRssi = 0
    @Volatile private var authorizedCount = 0
    @Volatile private var deniedCount = 0
    @Volatile private var unknownCount = 0

    private val recentScans = ArrayList<String>()
    private var vibrator: Vibrator? = null
    private var lastBeepTime = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var backPressedCallback: OnBackPressedCallback? = null

    private enum class RadarMode {
        SINGLE_TARGET, OPEN_SCAN, SECURITY_GATE, MISSING_SEARCH
    }
    @Volatile private var currentMode = RadarMode.SINGLE_TARGET

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_mira_uhf_radar_location, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        try {
            mContext = activity as? UHFMainActivity
            if (mContext == null) return

            mContext?.currentFragment = this

            context?.let {
                vibrator = it.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            settingsManager = MiraSettingsManager.getInstance(mContext!!)
            branchesManager = MiraBranchesManager.getInstance(mContext!!)
            orgProfile = MiraOrgProfileManager.getInstance(mContext!!)
            permissionManager = PermissionManager.getInstance(mContext!!)
            rfidManager = RFIDManager.getInstance(mContext!!)

            isArabic = AppCompatDelegate.getApplicationLocales()
                .toLanguageTags().contains("ar")

            simulationMode = false

            initViews()
            applyLanguage()
            applyPermissions()
            setupListeners()
            setupBackPressHandler()
            restoreState()
            updateReaderStatus()

        } catch (e: Exception) {
            MiraFileLogger.logError(TAG, "onActivityCreated error", e)
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            (activity as? UHFMainActivity)?.let {
                it.setBottomNavVisible(false)
                it.findViewById<View>(R.id.fabActionCenter)?.visibility = View.GONE
            }
        } catch (e: Exception) {
            MiraFileLogger.logError(TAG, "onStart error", e)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            (activity as? UHFMainActivity)?.let {
                it.setBottomNavVisible(true)
                it.findViewById<View>(R.id.fabActionCenter)?.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            MiraFileLogger.logError(TAG, "onStop error", e)
        }
    }

    override fun onResume() {
        super.onResume()
        updateReaderStatus()
        updateModeUI()
    }

    override fun onPause() {
        super.onPause()
        try { stopLocated() } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { stopLocated() } catch (_: Exception) {}

        backPressedCallback?.let {
            try {
                it.isEnabled = false
                it.remove()
            } catch (_: Exception) {}
            backPressedCallback = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            settingsManager?.unregisterListener("radar_fragment")
        } catch (_: Exception) {}
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            isArabic = newConfig.locale.language == "ar"
            applyLanguage()
            applyPermissions()
            updateReaderStatus()

            fragmentManager?.beginTransaction()?.detach(this)?.attach(this)?.commit()
            view?.post { restoreState() }
        } catch (_: Exception) {}
    }

    private fun setupBackPressHandler() {
        if (activity == null) return
        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleCustomBack()
            }
        }
        requireActivity().onBackPressedDispatcher
            .addCallback(viewLifecycleOwner, backPressedCallback!!)
    }

    private fun handleCustomBack() {
        try {
            if (inventoryFlag) {
                stopLocated()
                return
            }
            navigateAway()
        } catch (e: Exception) {
            navigateAway()
        }
    }

    private fun navigateAway() {
        try {
            stopLocated()
            (activity as? UHFMainActivity)?.let { activity ->
                if (activity.navigationManager != null) {
                    activity.navigationManager.goToDashboard()
                } else {
                    activity.loadFragmentDirectly(
                        MiraDashboardFragment(),
                        NavigationManager.TAG_DASHBOARD
                    )
                }
            }
        } catch (e: Exception) {
            MiraFileLogger.logError(TAG, "navigateAway error", e)
        }
    }

    override fun myOnKeyDwon(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != 139 && keyCode != 280 && keyCode != 293) {
            return false
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (inventoryFlag) {
                Log.i(TAG, "🎯 Trigger: stop radar")
                stopLocated()
            } else {
                Log.i(TAG, "🎯 Trigger: start radar")
                startLocated()
            }
            return true
        }

        if (event.action == KeyEvent.ACTION_UP) {
            return true
        }

        return false
    }

    private fun applyPermissions() {
        val canUse = canUseRadar()
        val canMark = canMarkFound()

        btStart?.isEnabled = canUse
        btnFetchMissing?.isEnabled = canUse
        btnMarkFound?.isEnabled = canMark

        if (!canUse && tvRadarMode != null) {
            tvRadarMode?.text = if (isArabic) "⛔ وضع العرض فقط" else "⛔ View only mode"
        }
    }

    private fun canUseRadar(): Boolean {
        return try {
            val pm = permissionManager ?: return true
            pm.canView("radar") || pm.isSecurity || pm.isGateSecurity || pm.isManager
        } catch (e: Exception) {
            false
        }
    }

    private fun canMarkFound(): Boolean {
        return try {
            val pm = permissionManager ?: return true
            pm.canEdit("inventory") || pm.isManager
        } catch (e: Exception) {
            false
        }
    }

    private fun initViews() {
        val v = view ?: return

        radarView = v.findViewById(R.id.radarView)
        etEPC = v.findViewById(R.id.etRadarEPC)
        btStart = v.findViewById(R.id.btRadarStart)
        btStop = v.findViewById(R.id.btRadarStop)
        seekBarPower = v.findViewById(R.id.seekBarPower)
        rssiBar = v.findViewById(R.id.rssiBar)
        btnExportResults = v.findViewById(R.id.btnExportResults)
        btnFetchMissing = v.findViewById(R.id.btnFetchMissing)
        btnMarkFound = v.findViewById(R.id.btnMarkFound)
        btnRadarClose = v.findViewById(R.id.btnRadarClose)

        tvProximityLevel = v.findViewById(R.id.tvProximityLevel)
        tvSignalStrength = v.findViewById(R.id.tvSignalStrength)
        tvEstimatedDistance = v.findViewById(R.id.tvEstimatedDistance)
        tvReaderStatus = v.findViewById(R.id.tvReaderStatus)

        layoutMiraRadarInfo = v.findViewById(R.id.layoutMiraRadarInfo)
        tvRadarItemStatus = v.findViewById(R.id.tvRadarItemStatus)
        tvRadarItemName = v.findViewById(R.id.tvRadarItemName)
        tvRadarItemDetails = v.findViewById(R.id.tvRadarItemDetails)
        tvAuthorizedCount = v.findViewById(R.id.tvAuthorizedCount)
        tvDeniedCount = v.findViewById(R.id.tvDeniedCount)
        tvUnknownCount = v.findViewById(R.id.tvUnknownCount)
        tvRecentScans = v.findViewById(R.id.tvRecentScans)
        tvRadarMode = v.findViewById(R.id.tvRadarMode)

        btnModeSingle = v.findViewById(R.id.btnModeSingle)
        btnModeOpen = v.findViewById(R.id.btnModeOpen)
        btnModeGate = v.findViewById(R.id.btnModeGate)

        seekBarPower?.isEnabled = false
        seekBarPower?.progress = 5
        updateModeUI()
    }

    private fun applyLanguage() {
        btStart?.text = if (isArabic) "▶ بدء التتبع" else "▶ Start Tracking"
        btStop?.text = if (isArabic) "⏹ إيقاف" else "⏹ Stop"
        etEPC?.hint = if (isArabic) "أدخل EPC أو اختر قطعة مفقودة" else "Enter EPC or select missing item"
        btnExportResults?.text = if (isArabic) "📤 تصدير النتائج" else "📤 Export"
        btnFetchMissing?.text = if (isArabic) "🔍 المفقودات" else "🔍 Missing"
        btnMarkFound?.text = if (isArabic) "✅ تم العثور" else "✅ Mark Found"
        btnModeSingle?.text = if (isArabic) "🎯 محدد" else "🎯 Target"
        btnModeOpen?.text = if (isArabic) "📡 مفتوح" else "📡 Open"
        btnModeGate?.text = if (isArabic) "🔍 مفقودات" else "🔍 Missing"
        tvEstimatedDistance?.text = if (isArabic) "📏 المسافة: غير معروفة" else "📏 Distance: Unknown"

        view?.layoutDirection = if (isArabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    }

    private fun updateReaderStatus() {
        val statusView = tvReaderStatus ?: return
        var hardwareAvailable = false
        var readerConnected = false

        try {
            rfidManager?.activeReader?.let { reader ->
                hardwareAvailable = true
                readerConnected = reader.isConnected
            }
        } catch (_: Exception) {}

        when {
            !hardwareAvailable -> {
                statusView.text = if (isArabic) "📡 القارئ: غير متوفر" else "📡 Reader: Not Available"
                statusView.setTextColor(0xFFEF4444.toInt())
            }
            !readerConnected -> {
                statusView.text = if (isArabic) "📡 القارئ: غير متصل" else "📡 Reader: Disconnected"
                statusView.setTextColor(0xFFF59E0B.toInt())
            }
            else -> {
                statusView.text = if (isArabic) "📡 القارئ: متصل ✓" else "📡 Reader: Connected ✓"
                statusView.setTextColor(0xFF22C55E.toInt())
            }
        }
    }

    private fun setupListeners() {
        btStart?.setOnClickListener {
            if (canUseRadar()) startLocated()
            else toast(if (isArabic) "⛔ غير مصرح" else "⛔ Access Denied")
        }

        btStop?.setOnClickListener { stopLocated() }
        btnExportResults?.setOnClickListener { exportScanResults() }
        btnFetchMissing?.setOnClickListener { showMissingItemsDialog() }

        btnMarkFound?.setOnClickListener {
            if (canMarkFound()) markCurrentItemAsFound()
            else toast(if (isArabic) "⛔ غير مصرح" else "⛔ Access Denied")
        }

        btnModeSingle?.setOnClickListener { switchMode(RadarMode.SINGLE_TARGET) }
        btnModeOpen?.setOnClickListener { switchMode(RadarMode.OPEN_SCAN) }
        btnModeGate?.setOnClickListener { switchMode(RadarMode.MISSING_SEARCH) }

        btnRadarClose?.setOnClickListener { handleCustomBack() }
    }

    private fun restoreState() {
        view?.post {
            try {
                val ctx = mContext ?: return@post
                val list = ctx.tagList ?: return@post
                val index = ctx.selectIndex
                if (index < 0 || index >= list.size) return@post

                val tag = list[index] ?: return@post
                val selectItem = tag.epc
                if (!TextUtils.isEmpty(selectItem)) {
                    etEPC?.setText(selectItem)
                    targetEpc = selectItem!!
                }
            } catch (_: Exception) {}
        }
    }

    private fun switchMode(mode: RadarMode) {
        currentMode = mode
        if (inventoryFlag) {
            stopLocated()
            startLocated()
        }
        updateModeUI()
        toast(getModeName(mode))
    }

    private fun getModeName(mode: RadarMode): String {
        var modeName = when (mode) {
            RadarMode.SINGLE_TARGET -> if (isArabic) "🎯 تتبع قطعة محددة" else "🎯 Single Target"
            RadarMode.OPEN_SCAN -> if (isArabic) "📡 مسح مفتوح" else "📡 Open Scan"
            RadarMode.MISSING_SEARCH -> if (isArabic) "🔍 كاشف المفقودات" else "🔍 Missing Scanner"
            RadarMode.SECURITY_GATE -> if (isArabic) "🛡️ بوابة أمنية" else "🛡️ Security Gate"
        }
        if (inventoryFlag) {
            modeName += if (isArabic) " (نشط)" else " (active)"
        }
        return modeName
    }

    private fun updateModeUI() {
        tvRadarMode?.text = getModeName(currentMode)
        btnModeSingle?.alpha = if (currentMode == RadarMode.SINGLE_TARGET) 1f else 0.5f
        btnModeOpen?.alpha = if (currentMode == RadarMode.OPEN_SCAN) 1f else 0.5f
        btnModeGate?.alpha = if (currentMode == RadarMode.MISSING_SEARCH) 1f else 0.5f
    }

    private fun startLocated() {
        if (inventoryFlag) return
        if (!canUseRadar()) {
            toast(if (isArabic) "⛔ غير مصرح" else "⛔ Access Denied")
            return
        }

        updateReaderStatus()
        radarView?.clearPanel()

        targetEpc = etEPC?.text?.toString()?.trim() ?: ""

        authorizedCount = 0
        deniedCount = 0
        unknownCount = 0
        recentScans.clear()
        updateCounters()

        tvRecentScans?.text = ""
        layoutMiraRadarInfo?.visibility = View.GONE
        btnMarkFound?.visibility = View.GONE

        inventoryFlag = true
        pollRunning = true
        updateButtonsForRunning(true)

        radarView?.startRadar()
        setupSeekBar()
        updateModeUI()

        if (!TextUtils.isEmpty(targetEpc)) {
            queryMiraItem(targetEpc, 75)
        }

        val epcFilter = if (currentMode == RadarMode.OPEN_SCAN) "" else targetEpc

        pollThread = Thread({
            Log.d(TAG, "🔍 RSSI poll thread started")
            while (pollRunning && inventoryFlag && !Thread.currentThread().isInterrupted) {
                try {
                    val tagInfo = readOneTag()
                    if (tagInfo != null) {
                        val epc = tagInfo.epc
                        val rssiDbm = parseRssi(tagInfo.rssi)

                        if (!TextUtils.isEmpty(epc) && matchesFilter(epc, epcFilter)) {
                            val prox = mapRssiToProximity(rssiDbm)

                            mainHandler.post {
                                if (!inventoryFlag) return@post
                                currentRssi = prox
                                updateProximityUI(prox)
                                handleGeigerAudioFeedback(prox)
                                queryMiraItem(epc, prox)
                            }
                        }
                    }
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Poll error: " + e.message)
                    try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                }
            }
            Log.d(TAG, "🔍 RSSI poll thread ended")
        }, "Radar-RSSI-Poll")

        pollThread?.isDaemon = true
        pollThread?.start()
    }

    private fun readOneTag(): UHFTAGInfo? {
        return try {
            mContext?.mReader?.inventorySingleTag()
        } catch (e: Exception) {
            Log.w(TAG, "readOneTag error: " + e.message)
            null
        }
    }

    private fun matchesFilter(epc: String?, filter: String): Boolean {
        if (TextUtils.isEmpty(filter)) return true
        return epc != null && (epc.contains(filter) || filter.contains(epc))
    }

    private fun mapRssiToProximity(rssiDbm: Int): Int {
        if (rssiDbm >= RSSI_NEAR_DBM) return RSSI_NEAR_PCT
        if (rssiDbm <= RSSI_FAR_DBM) return RSSI_FAR_PCT

        val ratio = (rssiDbm - RSSI_FAR_DBM).toDouble() / (RSSI_NEAR_DBM - RSSI_FAR_DBM).toDouble()
        val pct = (RSSI_FAR_PCT + ratio * (RSSI_NEAR_PCT - RSSI_FAR_PCT)).toInt()
        return pct.coerceIn(RSSI_FAR_PCT, RSSI_NEAR_PCT)
    }

    private fun parseRssi(rssiStr: String?): Int {
        if (rssiStr.isNullOrEmpty()) return -50
        return try {
            val cleaned = rssiStr
                .replace("dBm", "", ignoreCase = true)
                .replace("dB", "", ignoreCase = true)
                .trim()
            val valNum = cleaned.toInt()
            if (valNum > 0) -valNum else valNum
        } catch (_: NumberFormatException) {
            -50
        }
    }

    private fun setupSeekBar() {
        seekBarPower?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                progress = p
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                try {
                    mContext?.mReader?.setDynamicDistance(35 - progress)
                } catch (e: Exception) {
                    MiraFileLogger.logError(TAG, "setDynamicDistance error", e)
                }
            }
        })
        seekBarPower?.isEnabled = true
    }

    private fun stopLocated() {
        if (!inventoryFlag) return
        Log.d(TAG, "⏹️ Stopping radar...")

        inventoryFlag = false
        pollRunning = false

        val t = pollThread
        pollThread = null
        t?.let {
            it.interrupt()
            try { it.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }

        try {
            mContext?.mReader?.let { reader ->
                try { reader.stopRadarLocation() } catch (_: Exception) {}
                try { reader.stopInventory() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            MiraFileLogger.logError(TAG, "stopLocated hardware error", e)
        }

        try { radarView?.stopRadar() } catch (_: Exception) {}

        updateButtonsForRunning(false)
        seekBarPower?.let {
            it.setOnSeekBarChangeListener(null)
            it.progress = 5
            it.isEnabled = false
        }

        resetProximityUI()
        updateModeUI()
    }

    private fun updateButtonsForRunning(running: Boolean) {
        btStart?.isEnabled = !running
        btStop?.isEnabled = running
        etEPC?.isEnabled = !running
    }

    private fun handleGeigerAudioFeedback(proximityPct: Int) {
        val now = System.currentTimeMillis()
        val interval = Math.max(BEEP_THROTTLE_MS, 1000L - (proximityPct * 9L))

        if (now - lastBeepTime > interval) {
            lastBeepTime = now
            val soundEnabled = settingsManager?.getBoolean("sound_on_scan", true) ?: true
            if (soundEnabled) {
                try { mContext?.playSound(1) } catch (_: Exception) {}
            }
            if (proximityPct > 75) {
                try { vibrator?.vibrate(40) } catch (_: Exception) {}
            }
        }
    }

    private fun updateProximityUI(pct: Int) {
        currentRssi = pct
        rssiBar?.progress = pct
        tvSignalStrength?.text = "$pct%"

        tvEstimatedDistance?.text = (if (isArabic) "📏 المسافة: " else "📏 Distance: ") + getEstimatedDistance(pct)

        if (tvProximityLevel != null) {
            when {
                pct > 80 -> {
                    tvProximityLevel?.text = if (isArabic) "🔥 ملاصق جداً!" else "🔥 Extremely Close!"
                    tvProximityLevel?.setTextColor(0xFF22C55E.toInt())
                    if (canMarkFound()) btnMarkFound?.visibility = View.VISIBLE
                }
                pct > 50 -> {
                    tvProximityLevel?.text = if (isArabic) "📍 النطاق القريب" else "📍 Near Range"
                    tvProximityLevel?.setTextColor(0xFFF59E0B.toInt())
                }
                else -> {
                    tvProximityLevel?.text = if (isArabic) "🌐 إشارة ضعيفة" else "🌐 Weak Signal"
                    tvProximityLevel?.setTextColor(0xFFEF4444.toInt())
                }
            }
        }
    }

    private fun resetProximityUI() {
        rssiBar?.progress = 0
        tvSignalStrength?.text = "0%"
        tvProximityLevel?.text = if (isArabic) "متوقف" else "Stopped"
        tvEstimatedDistance?.text = if (isArabic) "📏 المسافة: غير معروفة" else "📏 Distance: Unknown"
    }

    private fun getEstimatedDistance(pct: Int): String {
        return when {
            pct > 90 -> if (isArabic) "أقل من 30 سم" else "< 30 cm"
            pct > 80 -> if (isArabic) "30-50 سم" else "30-50 cm"
            pct > 65 -> if (isArabic) "50-100 سم" else "50-100 cm"
            pct > 50 -> if (isArabic) "1-2 متر" else "1-2 m"
            pct > 35 -> if (isArabic) "2-3 متر" else "2-3 m"
            pct > 20 -> if (isArabic) "3-5 متر" else "3-5 m"
            else -> if (isArabic) "أكثر من 5 متر" else "> 5 m"
        }
    }

    private fun updateCounters() {
        tvAuthorizedCount?.text = authorizedCount.toString()
        tvDeniedCount?.text = deniedCount.toString()
        tvUnknownCount?.text = unknownCount.toString()
    }

    private fun addToRecentScans(epc: String?, status: String) {
        val icon = when (status) {
            "AUTHORIZED" -> "✅"
            "DENIED" -> "🚨"
            else -> "⚠️"
        }
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val shortEpc = if (epc != null && epc.length > 14) epc.substring(0, 14) + "..." else epc.toString()

        recentScans.add(0, "$icon $shortEpc | $time")
        if (recentScans.size > 5) recentScans.removeAt(recentScans.size - 1)

        tvRecentScans?.let { view ->
            val sb = StringBuilder()
            for (s in recentScans) sb.append(s).append("\n")
            view.text = sb.toString()
        }
    }

    private fun animateDetection(isAuthorized: Boolean) {
        val view = radarView ?: return
        try {
            val color = if (isAuthorized) 0xFF22C55E.toInt() else 0xFFEF4444.toInt()
            ObjectAnimator.ofFloat(view, "alpha", 1f, 0.4f, 1f).setDuration(300).start()
            view.setBorderColor(color)
        } catch (_: Exception) {}
    }

    private fun queryMiraItem(epc: String, rssiPct: Int) {
        try {
            if (settingsManager?.getBoolean("auto_query_mira", true) == false) return
        } catch (_: Exception) {}

        Executors.newSingleThreadExecutor().execute {
            try {
                val client = MiraApiClient.getInstance(mContext) ?: return@execute
                val extraParams = JSONObject()
                branchesManager?.let { extraParams.put("branch_id", it.activeBranchId) }

                val response = client.authorize(epc, "radar_locate", extraParams)
                if (activity != null && response != null && response.isSuccess && response.data != null) {
                    val decision = response.data?.optJSONObject("decision")
                    val item = response.data?.optJSONObject("item")
                    val allowed = decision?.optBoolean("allowed", false) == true
                    val status = if (allowed) "AUTHORIZED" else "DENIED"

                    activity?.runOnUiThread {
                        if (!inventoryFlag) return@runOnUiThread
                        updateMiraRadarInfo(epc, decision, item)
                        addToRecentScans(epc, status)
                        animateDetection(allowed)
                    }
                } else {
                    activity?.runOnUiThread {
                        if (!inventoryFlag) return@runOnUiThread
                        unknownCount++
                        updateCounters()
                        addToRecentScans(epc, "UNKNOWN")
                    }
                }
            } catch (e: Exception) {
                MiraFileLogger.logError(TAG, "MIRA Query Exception", e)
            }
        }
    }

    private fun updateMiraRadarInfo(epc: String, decision: JSONObject?, item: JSONObject?) {
        try {
            val showCard = settingsManager?.getBoolean("show_mira_card", true) ?: true
            if (!showCard) {
                layoutMiraRadarInfo?.visibility = View.GONE
                return
            }

            layoutMiraRadarInfo?.visibility = View.VISIBLE

            val allowed = decision?.optBoolean("allowed", false) == true
            val itemTitle = item?.optString("title", if (isArabic) "غير معروف" else "Unknown") ?: (if (isArabic) "غير معروف" else "Unknown")
            val karat = item?.optString("karat", "") ?: ""
            val weight = item?.optString("weight", "") ?: ""
            val status = item?.optString("status", "") ?: ""

            if (item != null && allowed) authorizedCount++
            else if (item != null && !allowed) deniedCount++
            else unknownCount++

            updateCounters()

            if (tvRadarItemStatus != null) {
                if (allowed && item != null) {
                    tvRadarItemStatus?.text = if (isArabic) "✅ مصرحة بالوجود" else "✅ Authorized"
                    tvRadarItemStatus?.setTextColor(0xFF22C55E.toInt())
                } else if (!allowed && item != null) {
                    tvRadarItemStatus?.text = if (isArabic) "🚨 قطعة غير مخصصة للفرع" else "🚨 Unauthorized"
                    tvRadarItemStatus?.setTextColor(0xFFEF4444.toInt())
                } else {
                    tvRadarItemStatus?.text = if (isArabic) "⚠️ مفقودة / غير مسجلة" else "⚠️ Missing/Unregistered"
                    tvRadarItemStatus?.setTextColor(0xFFF59E0B.toInt())
                }
            }

            tvRadarItemName?.text = "📦 $itemTitle"

            tvRadarItemDetails?.let { view ->
                val details = StringBuilder("🏷️ ")
                    .append(if (epc.length > 16) epc.substring(0, 16) else epc)
                if (karat.isNotEmpty()) details.append(" | 💎 ").append(karat)
                if (weight.isNotEmpty()) details.append(" | ⚖️ ").append(weight).append("g")
                if (status.isNotEmpty()) details.append(" | 📋 ").append(status)
                view.text = details.toString()
            }
        } catch (_: Exception) {}
    }

    private fun markCurrentItemAsFound() {
        if (!canMarkFound()) {
            toast(if (isArabic) "⛔ غير مصرح" else "⛔ Access Denied")
            return
        }
        if (TextUtils.isEmpty(targetEpc)) return

        toast(if (isArabic) "جاري تحديث الحالة..." else "Updating status...")

        Executors.newSingleThreadExecutor().execute {
            try {
                val client = MiraApiClient.getInstance(mContext) ?: return@execute
                val params = JSONObject().apply {
                    put("epc", targetEpc)
                    put("status", "AVAILABLE")
                    branchesManager?.let { put("branch_id", it.activeBranchId) }
                }

                client.authorize(targetEpc, "mark_found", params)

                activity?.runOnUiThread {
                    toast(if (isArabic) "🎉 تم تأكيد موقع القطعة!" else "🎉 Item located!")
                    stopLocated()
                }
            } catch (e: Exception) {
                MiraFileLogger.logError(TAG, "Failed to mark item as found", e)
            }
        }
    }

    private fun showMissingItemsDialog() {
        val ctx = context ?: return
        val loading = AlertDialog.Builder(ctx)
            .setMessage(if (isArabic) "جاري جلب القطع المفقودة..." else "Fetching missing items...")
            .setCancelable(false)
            .show()

        Executors.newSingleThreadExecutor().execute {
            try {
                val client = MiraApiClient.getInstance(ctx)
                if (client == null) {
                    activity?.runOnUiThread { try { loading.dismiss() } catch (_: Exception) {} }
                    return@execute
                }

                val branchId = branchesManager?.activeBranchId ?: 0
                val apiResponse = client.getReportData("audit", "", "", branchId)
                val response = if (apiResponse?.isSuccess == true) apiResponse.data else null

                activity?.runOnUiThread {
                    try { loading.dismiss() } catch (_: Exception) {}
                    if (response != null && response.has("audit_summary")) {
                        parseAndSelectMissingItem(response)
                    } else {
                        toast(if (isArabic) "لا توجد قطع مفقودة مسجلة" else "No missing items recorded")
                    }
                }
            } catch (_: Exception) {
                activity?.runOnUiThread {
                    try { loading.dismiss() } catch (_: Exception) {}
                    toast(if (isArabic) "خطأ في الاتصال" else "Connection failed")
                }
            }
        }
    }

    private fun parseAndSelectMissingItem(data: JSONObject) {
        val missingArray = data.optJSONArray("missing_details")
        val displayList = ArrayList<String>()
        val epcList = ArrayList<String>()

        if (missingArray != null && missingArray.length() > 0) {
            for (i in 0 until missingArray.length()) {
                val obj = missingArray.optJSONObject(i) ?: continue
                val epc = obj.optString("epc", "")
                val title = obj.optString("title", if (isArabic) "قطعة مفقودة" else "Missing item")
                val weight = obj.optString("weight", "")

                displayList.add("💎 $title (${weight}g)\n🏷️ $epc")
                epcList.add(epc)
            }
        } else {
            displayList.add(if (isArabic) "💎 خاتم سوليتير (4.2g)\n🏷️ E200001982130124" else "💎 Solitaire Ring (4.2g)\n🏷️ E200001982130124")
            epcList.add("E200001982130124")
        }

        AlertDialog.Builder(requireContext())
            .setTitle(if (isArabic) "اختر القطعة المفقودة" else "Select Missing Item")
            .setItems(displayList.toArray(arrayOfNulls<String>(0))) { _, which ->
                val selectedEpc = epcList[which]
                etEPC?.setText(selectedEpc)
                targetEpc = selectedEpc
                switchMode(RadarMode.SINGLE_TARGET)
                startLocated()
            }
            .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
            .show()
    }

    private fun exportScanResults() {
        if (recentScans.isEmpty()) {
            toast(if (isArabic) "لا توجد نتائج للتصدير" else "No results to export")
            return
        }

        val csv = StringBuilder("Status,EPC,Time\n")
        for (scan in recentScans) {
            csv.append(
                scan.replace("✅ ", "AUTHORIZED,")
                    .replace("🚨 ", "DENIED,")
                    .replace("⚠️ ", "UNKNOWN,")
                    .replace(" | ", ",")
            ).append("\n")
        }

        try {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "MIRA Radar Export - " + (branchesManager?.activeBranchDisplayName ?: ""))
                putExtra(Intent.EXTRA_TEXT, csv.toString())
            }
            startActivity(Intent.createChooser(share, if (isArabic) "تصدير نتائج التتبع" else "Export Tracking Results"))
        } catch (_: Exception) {}
    }

    private fun toast(msg: String) {
        try {
            if (isAdded && context != null) {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {}
    }
}
