package com.example.uhf.fragment

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.uhf.MiraRfid.RFIDManager
import com.example.uhf.api.MiraApiClient
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.ui.inventory.MiraInventoryScreen
import com.example.uhf.ui.inventory.MiraInventoryViewModel
import com.example.uhf.utils.MiraUhfSoundEngine
import com.example.uhf.utils.SoundManager
import com.google.android.material.bottomappbar.BottomAppBar
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.Locale

class MiraInventoryProFragment : Fragment() {

    companion object {
        private const val TAG = "MiraInventoryProFrag"
        // أكواد أزرار الزناد القياسية لأجهزة Chainway C72 / VH-C83
        private val TRIGGER_KEYCODES = setOf(139, 280, 293, KeyEvent.KEYCODE_F9)
    }

    private val viewModel: MiraInventoryViewModel by viewModels()
    private var rfidManager: RFIDManager? = null
    private var soundManager: SoundManager? = null
    private var uhfSoundEngine: MiraUhfSoundEngine? = null
    private var lastGeigerBeepMs = 0L
    private var isClosing = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val isArabic = Locale.getDefault().language.startsWith("ar")

        rfidManager = runCatching { RFIDManager.getInstance(ctx) }.getOrNull()
        soundManager = runCatching { SoundManager(ctx) }.getOrNull()
        uhfSoundEngine = MiraUhfSoundEngine()

        val apiClient = runCatching { MiraApiClient.getInstance(ctx) }.getOrNull()
        val branchesManager = runCatching { MiraBranchesManager.getInstance(ctx) }.getOrNull()

        // ضبط Session S1 / Target A لتقليل تداخل الشرائح في صواني المجوهرات الكثيفة
        rfidManager?.configureDenseJewelrySession()

        viewModel.initDependencies(
            isArabicLang = isArabic,
            api = apiClient,
            branches = branchesManager,
            sound = soundManager,
            rfid = rfidManager,
            hwOps = createHardwareBridge()
        )

        // تحميل طاقة الانتينا المحفوظة مسبقاً في القارئ
        rfidManager?.power?.let { savedPower ->
            if (savedPower in 5..30) {
                viewModel.setAntennaPower(savedPower)
            }
        }

        return ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            isFocusableInTouchMode = true
            requestFocus()

            setOnKeyListener { _, keyCode, event ->
                if (keyCode in TRIGGER_KEYCODES) {
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> {
                            if (event.repeatCount == 0) viewModel.onPhysicalTriggerDown()
                            true
                        }
                        KeyEvent.ACTION_UP -> {
                            viewModel.onPhysicalTriggerUp()
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }

            setContent {
                MiraInventoryScreen(
                    viewModel = viewModel,
                    onNavigateBack = { exitInventoryScreen() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isClosing = false
        setHostBottomBarVisible(false)
    }

    override fun onPause() {
        super.onPause()
        viewModel.saveCurrentSessionSnapshot()
        setHostBottomBarVisible(true)
    }

    override fun onDestroyView() {
        setHostBottomBarVisible(true)
        uhfSoundEngine?.release()
        uhfSoundEngine = null
        super.onDestroyView()
    }

    /**
     * الخروج الآمن والمنظم من شاشة الجرد دون حلقة رجوع أو انهيار
     */
    fun exitInventoryScreen() {
        if (isClosing || !isAdded) return
        isClosing = true

        if (viewModel.isScanning()) {
            viewModel.stopScanning()
        }
        setHostBottomBarVisible(true)

        if (parentFragmentManager.backStackEntryCount > 0) {
            parentFragmentManager.popBackStack()
        } else {
            activity?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    /**
     * إخفاء / إظهار شريط التنقل السفلي الخاص بـ UHFMainActivity لمنع تغطية أزرار الجرد
     */
    private fun setHostBottomBarVisible(visible: Boolean) {
        val rootView = activity?.window?.decorView ?: return
        val targetVisibility = if (visible) View.VISIBLE else View.GONE

        fun traverse(view: View) {
            val idName = runCatching {
                if (view.id != View.NO_ID) resources.getResourceEntryName(view.id).lowercase() else ""
            }.getOrDefault("")

            if (view is BottomNavigationView ||
                view is BottomAppBar ||
                idName.contains("bottom_nav") ||
                idName.contains("bottombar") ||
                idName.contains("bottom_bar") ||
                idName.contains("nav_bar_container") ||
                idName.contains("fab_scan") ||
                idName.contains("fab_inventory")
            ) {
                view.visibility = targetVisibility
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    traverse(view.getChildAt(i))
                }
            }
        }
        traverse(rootView)
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode in TRIGGER_KEYCODES && (event == null || event.repeatCount == 0)) {
            viewModel.onPhysicalTriggerDown()
            return true
        }
        return false
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode in TRIGGER_KEYCODES) {
            viewModel.onPhysicalTriggerUp()
            return true
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════
    //  واجهة التوافق الكاملة مع UHFMainActivity.java
    // ═══════════════════════════════════════════════════════════

    fun showInventoryScannerSettings() {
        if (!isAdded) return
        viewModel.openScannerSettingsSheet()
    }

    fun showInventorySessionSettings() {
        if (!isAdded) return
        viewModel.openSessionSettingsSheet()
    }

    fun getScannedCount(): Int {
        if (!isAdded) return 0
        return viewModel.getScannedCount()
    }

    fun isCurrentlyScanning(): Boolean {
        if (!isAdded) return false
        return viewModel.isScanning()
    }

    fun isScanning(): Boolean {
        if (!isAdded) return false
        return viewModel.isScanning()
    }

    fun stopScanning() {
        if (!isAdded) return
        viewModel.stopScanning()
    }

    fun showArchiveReportsPublic() {
        if (!isAdded) return
        viewModel.openArchive()
    }

    private fun createHardwareBridge() = object : MiraInventoryViewModel.HardwareOperations {
        override fun applyAntennaPower(powerDbm: Int): Boolean {
            return rfidManager?.setPower(powerDbm) ?: false
        }

        override fun readTagTidDirect(epc: String): String? {
            val mgr = rfidManager ?: return null
            return try {
                val reader = mgr.activeReader
                if (reader != null) {
                    val m1 = reader.javaClass.methods.firstOrNull {
                        it.name == "readTid" && it.parameterTypes.size == 1
                    }
                    if (m1 != null) return m1.invoke(reader, epc) as? String

                    val m0 = reader.javaClass.methods.firstOrNull {
                        it.name == "readTid" && it.parameterTypes.isEmpty()
                    }
                    if (m0 != null) return m0.invoke(reader) as? String
                }

                val rawSdk = mgr.rawSdkReader
                if (rawSdk != null) {
                    rawSdk.readData(
                        "00000000",
                        com.rscja.deviceapi.RFIDWithUHFUART.Bank_TID,
                        0,
                        6
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "readTagTidDirect error: ${e.message}")
                null
            }
        }

        override fun playGeigerTick(proximityPercent: Int) {
            val now = SystemClock.elapsedRealtime()
            val intervalMs = (550L - (proximityPercent * 4.5).toLong()).coerceIn(85L, 550L)
            if (now - lastGeigerBeepMs >= intervalMs) {
                lastGeigerBeepMs = now
                uhfSoundEngine?.playGeigerPing(proximityPercent)
            }
        }
    }
}
