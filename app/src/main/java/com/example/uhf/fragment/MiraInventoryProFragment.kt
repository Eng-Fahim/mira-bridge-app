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
import com.example.uhf.utils.SoundManager
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
    private var lastGeigerBeepMs = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val isArabic = Locale.getDefault().language.startsWith("ar")

        rfidManager = runCatching { RFIDManager.getInstance(ctx) }.getOrNull()
        soundManager = runCatching { SoundManager.getInstance(ctx) }.getOrNull()
        val apiClient = runCatching { MiraApiClient.getInstance(ctx) }.getOrNull()
        val branchesManager = runCatching { MiraBranchesManager.getInstance(ctx) }.getOrNull()

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
                MiraInventoryScreen(viewModel = viewModel)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.saveCurrentSessionSnapshot()
    }

    /**
     * دوال متوافقة لاستقبال أحداث الزناد إذا كانت MainActivity تمررها للـ Fragment مباشرة
     */
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

    private fun createHardwareBridge() = object : MiraInventoryViewModel.HardwareOperations {
        override fun applyAntennaPower(powerDbm: Int): Boolean {
            return rfidManager?.setPower(powerDbm) ?: false
        }

        override fun readTagTidDirect(epc: String): String? {
            val mgr = rfidManager ?: return null
            return try {
                // 1. المحاولة عبر MiraIRFIDReader إن كانت دالة readTid معرفة فيه
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

                // 2. القراءة المباشرة من Chainway SDK (Bank_TID, ptr=0, len=6 words = 12 bytes)
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
            val intervalMs = (600L - (proximityPercent * 5L)).coerceIn(90L, 600L)
            if (now - lastGeigerBeepMs >= intervalMs) {
                lastGeigerBeepMs = now
                soundManager?.playBeep(SoundManager.FeedbackType.KNOWN_ITEM)
            }
        }
    }
}
