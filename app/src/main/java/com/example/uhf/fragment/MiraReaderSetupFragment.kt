package com.example.uhf.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.uhf.MiraRfid.MiraIRFIDReader
import com.example.uhf.MiraRfid.MiraReaderSettings
import com.example.uhf.MiraRfid.RFIDManager
import com.example.uhf.MiraRfid.model.RFIDDevice
import com.example.uhf.MiraRfid.readers.ChainwayReader
import com.example.uhf.MiraRfid.readers.SimulatorReader
import com.example.uhf.R
import com.example.uhf.manager.PermissionManager
import com.example.uhf.utils.MiraExecutorService
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * MiraReaderSetupFragment - إدارة وتهيئة أجهزة قراءة UHF RFID
 */
class MiraReaderSetupFragment : MiraBaseFragment() {

    companion object {
        private const val TAG = "MiraReaderSetup"
        private const val PREFS_NAME = "mira_reader_ui_settings"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"

        private const val COLOR_ENABLED = "#16A34A"
        private const val COLOR_DISABLED = "#EF4444"
        private const val COLOR_GOLD_BORDER = "#D4AF37"
        private const val COLOR_DEFAULT_BORDER = "#E5E7EB"
    }

    // ==================== Views ====================
    private var rvDevices: RecyclerView? = null
    private var btnAddDevice: MaterialButton? = null
    private var btnAdvancedSettings: MaterialButton? = null
    private var btnApplySettings: MaterialButton? = null
    private var btnTestConnection: MaterialButton? = null
    private var btnDetectAuto: MaterialButton? = null
    private var btnBluetoothScan: MaterialButton? = null
    private var btnSoundToggle: MaterialButton? = null
    private var btnVibrationToggle: MaterialButton? = null
    private var tvActiveDeviceStatus: TextView? = null

    // ==================== Managers & Hardware ====================
    private var rfidManager: RFIDManager? = null
    private var permissionManager: PermissionManager? = null
    private var readerSettings: MiraReaderSettings? = null
    private var deviceAdapter: DeviceAdapter? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var uiPrefs: SharedPreferences? = null

    private val discoveredBtDevices = mutableListOf<BluetoothDevice>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ioExecutor: ExecutorService? = null

    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null

    @Volatile private var isSoundEnabled = true
    @Volatile private var isVibrationEnabled = true
    @Volatile private var isReceiverRegistered = false
    @Volatile private var isViewDestroyed = false
    @Volatile private var isDiscoveringBt = false

    // ==================== Permission Launcher ====================
    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (!isViewAlive()) return@registerForActivityResult
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startBluetoothDiscoveryInternal()
        } else {
            playErrorSound()
            vibrateError()
            showToast(
                if (isArabic) "⛔ يلزم منح صلاحية البلوتوث للبحث عن الأجهزة"
                else "⛔ Bluetooth permission is required to scan devices"
            )
        }
    }

    // ==================== Bluetooth Discovery Receiver ====================
    private val bluetoothDiscoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isViewAlive() || context == null || intent == null) return
            try {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        if (!hasBluetoothConnectPermission(context)) return
                        val device: BluetoothDevice? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE,
                                    BluetoothDevice::class.java
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }

                        if (device != null && discoveredBtDevices.none { it.address == device.address }) {
                            discoveredBtDevices.add(device)
                        }
                    }

                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        if (isDiscoveringBt) {
                            isDiscoveringBt = false
                            showDiscoveredBluetoothDevices()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bluetooth receiver error: ${e.message}", e)
            }
        }
    }

    // ==================== Lifecycle ====================

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return try {
            inflater.inflate(R.layout.fragment_mira_reader_setup, container, false)
        } catch (e: Exception) {
            Log.e(TAG, "Error inflating layout: ${e.message}", e)
            null
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isViewDestroyed = false
        try {
            initializeFragment(view)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onViewCreated: ${e.message}", e)
            showErrorDialog(
                if (isArabic) "حدث خطأ في تهيئة الشاشة: ${e.message}"
                else "Screen initialization error: ${e.message}"
            )
        }
    }

    private fun initializeFragment(view: View) {
        val ctx = requireContext()

        ioExecutor = try {
            val miraExec = MiraExecutorService.getInstance()
            if (miraExec != null && miraExec.isHealthy) {
                miraExec.backgroundExecutor
            } else {
                Executors.newSingleThreadExecutor()
            }
        } catch (_: Exception) {
            Executors.newSingleThreadExecutor()
        }

        rfidManager = RFIDManager.getInstance(ctx)
        permissionManager = PermissionManager.getInstance(ctx)
        readerSettings = try {
            MiraReaderSettings.getInstance(ctx)
        } catch (_: Exception) {
            null
        }

        bluetoothAdapter = try {
            val btManager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            btManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
        } catch (_: Exception) {
            null
        }

        uiPrefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also { prefs ->
            isSoundEnabled = prefs.getBoolean(KEY_SOUND_ENABLED, true)
            isVibrationEnabled = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
        }

        initSoundAndVibration()
        registerBluetoothReceiver()
        bindViews(view)
        setupRecyclerView()
        setupButtons()
        applyPermissions()
        updateActiveDeviceStatus()
    }

    private fun canManageReaders(): Boolean {
        val pm = permissionManager ?: return true
        return try {
            pm.isAdmin ||
                pm.isInventoryManager ||
                pm.canEdit("rfid") ||
                pm.canEdit("settings")
        } catch (_: Exception) {
            false
        }
    }

    private fun applyPermissions() {
        val canManage = canManageReaders()
        val alphaVal = if (canManage) 1.0f else 0.5f

        listOf(btnAddDevice, btnAdvancedSettings, btnApplySettings, btnDetectAuto).forEach { btn ->
            btn?.isEnabled = canManage
            btn?.alpha = alphaVal
        }

        if (!canManage) {
            tvActiveDeviceStatus?.text =
                if (isArabic) "⛔ وضع القراءة فقط — لا تملك صلاحية التعديل"
                else "⛔ Read-only mode — insufficient permissions"
        }
    }

    private fun initSoundAndVibration() {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator unavailable: ${e.message}")
        }

        try {
            val ctx = requireContext()
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibrator unavailable: ${e.message}")
        }
    }

    private fun registerBluetoothReceiver() {
        if (isReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(
                    bluetoothDiscoveryReceiver,
                    filter,
                    Context.RECEIVER_EXPORTED
                )
            } else {
                requireContext().registerReceiver(bluetoothDiscoveryReceiver, filter)
            }
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Register receiver error: ${e.message}", e)
        }
    }

    private fun bindViews(view: View) {
        rvDevices = view.findViewById(R.id.rvReaderDevices)
        btnAddDevice = view.findViewById(R.id.btnAddReader)
        btnAdvancedSettings = view.findViewById(R.id.btnAdvancedSettings)
        btnTestConnection = view.findViewById(R.id.btnTestConnection)
        btnDetectAuto = view.findViewById(R.id.btnDetectAuto)
        btnBluetoothScan = view.findViewById(R.id.btnBluetoothScan)
        btnSoundToggle = view.findViewById(R.id.btnSoundToggle)
        btnVibrationToggle = view.findViewById(R.id.btnVibrationToggle)
        tvActiveDeviceStatus = view.findViewById(R.id.tvActiveDeviceStatus)
        btnApplySettings = view.findViewById(R.id.btnApplySettings)
    }

    private fun setupRecyclerView() {
        val rv = rvDevices ?: return
        val devices = rfidManager?.allDevices ?: emptyList()
        deviceAdapter = DeviceAdapter(devices) { device ->
            showDeviceOptions(device)
        }
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = deviceAdapter
    }

    private fun setupButtons() {
        btnAddDevice?.setOnClickListener {
            if (canManageReaders()) {
                playClickSound()
                vibrateShort()
                showAddDeviceDialog()
            } else {
                showToast(if (isArabic) "⛔ غير مصرح" else "⛔ Access Denied")
            }
        }

        btnAdvancedSettings?.setOnClickListener {
            if (canManageReaders()) {
                playClickSound()
                vibrateShort()
                showAdvancedReaderSettings()
            } else {
                showToast(if (isArabic) "⛔ غير مصرح" else "⛔ Access Denied")
            }
        }

        btnApplySettings?.setOnClickListener {
            if (canManageReaders()) {
                playClickSound()
                vibrateShort()
                applySettingsImmediately()
            } else {
                showToast(if (isArabic) "⛔ غير مصرح" else "⛔ Access Denied")
            }
        }

        btnTestConnection?.setOnClickListener {
            playClickSound()
            vibrateShort()
            testActiveConnection()
        }

        btnDetectAuto?.setOnClickListener {
            if (canManageReaders()) {
                playClickSound()
                vibrateShort()
                autoDetectDevice()
            } else {
                showToast(if (isArabic) "⛔ غير مصرح" else "⛔ Access Denied")
            }
        }

        btnBluetoothScan?.setOnClickListener {
            playClickSound()
            vibrateShort()
            startBluetoothDiscovery()
        }

        updateSoundButtonText()
        updateVibrationButtonText()

        btnSoundToggle?.setOnClickListener {
            isSoundEnabled = !isSoundEnabled
            uiPrefs?.edit()?.putBoolean(KEY_SOUND_ENABLED, isSoundEnabled)?.apply()
            updateSoundButtonText()
            if (isSoundEnabled) playClickSound()
        }

        btnVibrationToggle?.setOnClickListener {
            isVibrationEnabled = !isVibrationEnabled
            uiPrefs?.edit()?.putBoolean(KEY_VIBRATION_ENABLED, isVibrationEnabled)?.apply()
            updateVibrationButtonText()
            if (isVibrationEnabled) vibrateShort()
        }
    }

    // ==================== الصوت والاهتزاز ====================

    private fun playSuccessSound() {
        try {
            if (isSoundEnabled) {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Play success sound error: ${e.message}")
        }
    }

    private fun playErrorSound() {
        try {
            if (isSoundEnabled) {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Play error sound error: ${e.message}")
        }
    }

    private fun playClickSound() {
        try {
            if (isSoundEnabled) {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Play click sound error: ${e.message}")
        }
    }

    private fun vibrateShort() {
        vibratePattern(longArrayOf(0, 50))
    }

    private fun vibrateSuccess() {
        vibratePattern(longArrayOf(0, 50, 50, 50))
    }

    private fun vibrateError() {
        vibratePattern(longArrayOf(0, 100, 100, 100, 100, 100))
    }

    private fun vibratePattern(timings: LongArray) {
        try {
            val vib = vibrator ?: return
            if (!isVibrationEnabled || !vib.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(timings, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(timings, -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibrate error: ${e.message}")
        }
    }

    private fun updateSoundButtonText() {
        val btn = btnSoundToggle ?: return
        if (isSoundEnabled) {
            btn.text = if (isArabic) "🔊 الصوت: مفعل" else "🔊 Sound: ON"
            btn.strokeColor = ColorStateList.valueOf(Color.parseColor(COLOR_ENABLED))
            btn.setTextColor(Color.parseColor(COLOR_ENABLED))
        } else {
            btn.text = if (isArabic) "🔇 الصوت: معطل" else "🔇 Sound: OFF"
            btn.strokeColor = ColorStateList.valueOf(Color.parseColor(COLOR_DISABLED))
            btn.setTextColor(Color.parseColor(COLOR_DISABLED))
        }
    }

    private fun updateVibrationButtonText() {
        val btn = btnVibrationToggle ?: return
        if (isVibrationEnabled) {
            btn.text = if (isArabic) "📳 الاهتزاز: مفعل" else "📳 Vibration: ON"
            btn.strokeColor = ColorStateList.valueOf(Color.parseColor(COLOR_ENABLED))
            btn.setTextColor(Color.parseColor(COLOR_ENABLED))
        } else {
            btn.text = if (isArabic) "📴 الاهتزاز: معطل" else "📴 Vibration: OFF"
            btn.strokeColor = ColorStateList.valueOf(Color.parseColor(COLOR_DISABLED))
            btn.setTextColor(Color.parseColor(COLOR_DISABLED))
        }
    }

    private fun updateActiveDeviceStatus() {
        val tv = tvActiveDeviceStatus ?: return
        try {
            val active = rfidManager?.activeDevice
            if (active != null) {
                val connected = try {
                    rfidManager?.activeReader?.isConnected == true
                } catch (_: Exception) {
                    false
                }

                val statusBadge = if (connected) {
                    if (isArabic) " ✅ متصل" else " ✅ Connected"
                } else {
                    if (isArabic) " ⚠️ غير متصل" else " ⚠️ Disconnected"
                }

                val prefix = if (isArabic) "الجهاز النشط: " else "Active: "
                tv.text = "$prefix${active.displayName} (${active.powerDbm} dBm)$statusBadge"
            } else {
                tv.text = if (isArabic) "لا يوجد جهاز نشط" else "No active device"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update status error: ${e.message}")
            tv.text = if (isArabic) "خطأ في تحديث الحالة" else "Error updating status"
        }
    }

    // ==================== البحث عن البلوتوث ====================

    private fun hasBluetoothConnectPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasBluetoothScanPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startBluetoothDiscovery() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            playErrorSound()
            vibrateError()
            showToast(if (isArabic) "الجهاز لا يدعم البلوتوث" else "Bluetooth not supported")
            return
        }

        if (!adapter.isEnabled) {
            playErrorSound()
            vibrateError()
            showToast(if (isArabic) "يرجى تفعيل البلوتوث أولاً" else "Please enable Bluetooth first")
            return
        }

        val ctx = requireContext()
        if (!hasBluetoothScanPermission(ctx) || !hasBluetoothConnectPermission(ctx)) {
            val requiredPerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            bluetoothPermissionLauncher.launch(requiredPerms)
            return
        }

        startBluetoothDiscoveryInternal()
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothDiscoveryInternal() {
        val adapter = bluetoothAdapter ?: return
        val ctx = context ?: return
        if (!hasBluetoothScanPermission(ctx) || !hasBluetoothConnectPermission(ctx)) return

        try {
            discoveredBtDevices.clear()

            adapter.bondedDevices?.forEach { paired ->
                if (discoveredBtDevices.none { it.address == paired.address }) {
                    discoveredBtDevices.add(paired)
                }
            }

            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }

            isDiscoveringBt = true
            adapter.startDiscovery()

            showLoadingDialog(
                if (isArabic) "🔍 جاري البحث عن أجهزة البلوتوث..."
                else "🔍 Searching for Bluetooth devices..."
            )

            // مهلة أمان 12 ثانية في حال لم يُطلق النظام حدث انتهاء الاكتشاف
            mainHandler.postDelayed({
                if (isDiscoveringBt && isViewAlive()) {
                    isDiscoveringBt = false
                    try {
                        if (hasBluetoothScanPermission(ctx) && adapter.isDiscovering) {
                            adapter.cancelDiscovery()
                        }
                    } catch (_: Exception) {
                    }
                    showDiscoveredBluetoothDevices()
                }
            }, 12_000L)
        } catch (e: Exception) {
            isDiscoveringBt = false
            dismissDialog(null)
            Log.e(TAG, "Bluetooth discovery error: ${e.message}", e)
            showErrorDialog(
                if (isArabic) "خطأ في البحث عن أجهزة البلوتوث: ${e.message}"
                else "Bluetooth discovery error: ${e.message}"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDiscoveredBluetoothDevices() {
        if (!isViewAlive()) return
        try {
            dismissDialog(null)

            if (discoveredBtDevices.isEmpty()) {
                playErrorSound()
                vibrateError()
                showToast(if (isArabic) "لم يتم العثور على أجهزة" else "No devices found")
                return
            }

            playSuccessSound()

            val ctx = requireContext()
            val canReadName = hasBluetoothConnectPermission(ctx)
            val labels = Array(discoveredBtDevices.size) { idx ->
                val d = discoveredBtDevices[idx]
                val name = if (canReadName) (d.name ?: "Unknown") else "Unknown"
                val bondedTag = if (canReadName && d.bondState == BluetoothDevice.BOND_BONDED) {
                    if (isArabic) " [مقترن]" else " [Paired]"
                } else {
                    ""
                }
                "$name$bondedTag\n${d.address}"
            }

            val title = if (isArabic) {
                "الأجهزة المكتشفة (${discoveredBtDevices.size})"
            } else {
                "Discovered Devices (${discoveredBtDevices.size})"
            }

            MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setItems(labels) { _, which ->
                    val selected = discoveredBtDevices.getOrNull(which) ?: return@setItems
                    showAddressInputDialog(
                        MiraIRFIDReader.ReaderType.GENERIC_BLUETOOTH,
                        selected.address
                    )
                }
                .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Show discovered devices error: ${e.message}", e)
        }
    }

    // ==================== إضافة وتعديل الأجهزة ====================

    private fun showAddDeviceDialog() {
        if (!isViewAlive()) return
        try {
            val types = MiraIRFIDReader.ReaderType.values()
            val names = Array(types.size) { i -> types[i].fullName }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (isArabic) "➕ إضافة جهاز RFID" else "➕ Add RFID Device")
                .setItems(names) { _, which ->
                    val selected = types[which]
                    if (selected.isBuiltIn) {
                        showAdvancedSettingsDialog(selected, "")
                    } else {
                        showAddressInputDialog(selected, null)
                    }
                }
                .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Show add dialog error: ${e.message}", e)
        }
    }

    private fun showAddressInputDialog(
        type: MiraIRFIDReader.ReaderType,
        prefillAddress: String?
    ) {
        if (!isViewAlive()) return
        try {
            val ctx = requireContext()
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 24, 48, 8)
            }

            val input = TextInputEditText(ctx).apply {
                hint = if (isArabic) "MAC Address أو IP:Port" else "MAC Address or IP:Port"
                if (!prefillAddress.isNullOrEmpty()) setText(prefillAddress)
            }
            layout.addView(input)

            MaterialAlertDialogBuilder(ctx)
                .setTitle(type.displayName)
                .setView(layout)
                .setPositiveButton(if (isArabic) "متابعة" else "Next") { _, _ ->
                    val address = input.text?.toString()?.trim().orEmpty()
                    if (address.isEmpty()) {
                        showToast(
                            if (isArabic) "⚠️ يرجى إدخال عنوان الجهاز"
                            else "⚠️ Please enter device address"
                        )
                    } else {
                        showAdvancedSettingsDialog(type, address)
                    }
                }
                .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Show address dialog error: ${e.message}", e)
        }
    }

    private fun showAdvancedSettingsDialog(
        type: MiraIRFIDReader.ReaderType,
        address: String
    ) {
        if (!isViewAlive()) return
        try {
            val ctx = requireContext()
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 24, 48, 8)
            }

            val etName = TextInputEditText(ctx).apply {
                hint = if (isArabic) "اسم مخصص (اختياري)" else "Custom name (optional)"
            }
            val etPower = TextInputEditText(ctx).apply {
                hint = if (isArabic) "قوة الإرسال (dBm) - افتراضي 27" else "Power (dBm) - default 27"
                inputType = InputType.TYPE_CLASS_NUMBER
            }
            val etRssi = TextInputEditText(ctx).apply {
                hint = if (isArabic) "عتبة RSSI - افتراضي -75" else "RSSI Threshold - default -75"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            }
            val etInterval = TextInputEditText(ctx).apply {
                hint = if (isArabic) "فترة المسح (ms) - افتراضي 100" else "Scan interval (ms) - default 100"
                inputType = InputType.TYPE_CLASS_NUMBER
            }

            layout.addView(etName)
            layout.addView(etPower)
            layout.addView(etRssi)
            layout.addView(etInterval)

            val title = "${type.displayName} - ${if (isArabic) "إعدادات الجهاز" else "Device Settings"}"

            MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setView(layout)
                .setPositiveButton(if (isArabic) "حفظ" else "Save") { _, _ ->
                    try {
                        val device = RFIDDevice(type, address)

                        val customName = etName.text?.toString()?.trim().orEmpty()
                        device.customName = customName.ifEmpty { type.displayName }

                        etPower.text?.toString()?.trim()?.toIntOrNull()?.let { p ->
                            device.powerDbm = p.coerceIn(5, 33)
                        }
                        etRssi.text?.toString()?.trim()?.toIntOrNull()?.let { r ->
                            device.rssiThreshold = r.coerceIn(-100, -20)
                        }
                        etInterval.text?.toString()?.trim()?.toIntOrNull()?.let { iv ->
                            device.scanIntervalMs = iv.coerceIn(20, 2000)
                        }

                        // تعيينه كافتراضي تلقائياً إذا كان أول جهاز مضاف
                        if (rfidManager?.allDevices.isNullOrEmpty()) {
                            device.isDefault = true
                        }

                        saveNewDevice(device)
                    } catch (e: Exception) {
                        Log.e(TAG, "Save device error: ${e.message}", e)
                        showErrorDialog(
                            if (isArabic) "خطأ في حفظ الجهاز: ${e.message}"
                            else "Error saving device: ${e.message}"
                        )
                    }
                }
                .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Show advanced dialog error: ${e.message}", e)
        }
    }

    private fun saveNewDevice(device: RFIDDevice) {
        val mgr = rfidManager ?: return
        try {
            val saved = mgr.saveDevice(device)
            if (saved) {
                if (device.isDefault) {
                    syncPowerToGlobalSettings(device.powerDbm)
                }
                playSuccessSound()
                vibrateSuccess()
                refreshDeviceList()
                updateActiveDeviceStatus()
                showToast(if (isArabic) "✅ تم حفظ الجهاز" else "✅ Device saved")
            } else {
                playErrorSound()
                vibrateError()
                showToast(if (isArabic) "❌ فشل الحفظ" else "❌ Save failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save device error: ${e.message}", e)
            showErrorDialog(
                if (isArabic) "خطأ في حفظ الجهاز: ${e.message}"
                else "Error saving device: ${e.message}"
            )
        }
    }

    private fun autoDetectDevice() {
        val mgr = rfidManager ?: return
        try {
            val detected = mgr.detectDeviceType()
            playSuccessSound()
            vibrateShort()

            val message = if (isArabic) {
                "تم اكتشاف: ${detected.displayName}\n${detected.description}"
            } else {
                "Detected: ${detected.displayName}\n${detected.description}"
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (isArabic) "🔍 نتيجة الاكتشاف التلقائي" else "🔍 Auto-Detection Result")
                .setMessage(message)
                .setPositiveButton(if (isArabic) "استخدام هذا الجهاز" else "Use This Device") { _, _ ->
                    if (detected.isBuiltIn) {
                        showAdvancedSettingsDialog(detected, "")
                    } else {
                        showAddressInputDialog(detected, null)
                    }
                }
                .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Auto detect error: ${e.message}", e)
            showErrorDialog(
                if (isArabic) "خطأ في الاكتشاف التلقائي: ${e.message}"
                else "Auto-detect error: ${e.message}"
            )
        }
    }

    private fun testActiveConnection() {
        val mgr = rfidManager ?: return
        val active = mgr.activeDevice
        if (active == null) {
            playErrorSound()
            vibrateError()
            showToast(if (isArabic) "لا يوجد جهاز محفوظ" else "No saved device")
            return
        }
        testDevice(active)
    }

    private fun showDeviceOptions(device: RFIDDevice) {
        if (!isViewAlive()) return
        try {
            playClickSound()
            vibrateShort()

            val canManage = canManageReaders()
            val optTest = if (isArabic) "📡 اختبار الاتصال" else "📡 Test Connection"
            val optInfo = if (isArabic) "ℹ️ عرض المعلومات" else "ℹ️ View Info"
            val optDefault = if (isArabic) "⭐ تعيين كجهاز افتراضي" else "⭐ Set as Default"
            val optEdit = if (isArabic) "⚙️ تعديل الإعدادات" else "⚙️ Edit Settings"
            val optDelete = if (isArabic) "🗑️ حذف الجهاز" else "🗑️ Delete Device"

            val optionsList = mutableListOf(optTest, optInfo)
            if (canManage) {
                if (!device.isDefault) optionsList.add(optDefault)
                optionsList.add(optEdit)
                optionsList.add(optDelete)
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(device.displayName)
                .setItems(optionsList.toTypedArray()) { _, which ->
                    when (optionsList[which]) {
                        optTest -> testDevice(device)
                        optInfo -> showDeviceInfo(device)
                        optDefault -> setDefaultDevice(device)
                        optEdit -> showEditDeviceDialog(device)
                        optDelete -> confirmDeleteDevice(device)
                    }
                }
                .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Show device options error: ${e.message}", e)
        }
    }

    private fun confirmDeleteDevice(device: RFIDDevice) {
        if (!isViewAlive()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isArabic) "🗑️ حذف الجهاز" else "🗑️ Delete Device")
            .setMessage(
                if (isArabic) "هل تريد حذف «${device.displayName}» من القائمة؟"
                else "Remove «${device.displayName}» from saved devices?"
            )
            .setPositiveButton(if (isArabic) "حذف" else "Delete") { _, _ ->
                rfidManager?.deleteDevice(device)
                playClickSound()
                vibrateShort()
                refreshDeviceList()
                updateActiveDeviceStatus()
                showToast(if (isArabic) "✅ تم حذف الجهاز" else "✅ Device deleted")
            }
            .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
            .show()
    }

    private fun showEditDeviceDialog(device: RFIDDevice) {
        if (!isViewAlive()) return
        try {
            val ctx = requireContext()
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 24, 48, 8)
            }

            val etName = TextInputEditText(ctx).apply {
                hint = if (isArabic) "اسم الجهاز" else "Device Name"
                setText(device.displayName)
            }
            val etPower = TextInputEditText(ctx).apply {
                hint = if (isArabic) "قوة الإرسال (dBm)" else "Power (dBm)"
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(device.powerDbm.toString())
            }
            val etRssi = TextInputEditText(ctx).apply {
                hint = if (isArabic) "عتبة RSSI" else "RSSI Threshold"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                setText(device.rssiThreshold.toString())
            }
            val etInterval = TextInputEditText(ctx).apply {
                hint = if (isArabic) "فترة المسح (ms)" else "Scan interval (ms)"
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(device.scanIntervalMs.toString())
            }

            layout.addView(etName)
            layout.addView(etPower)
            layout.addView(etRssi)
            layout.addView(etInterval)

            MaterialAlertDialogBuilder(ctx)
                .setTitle(device.displayName)
                .setView(layout)
                .setPositiveButton(if (isArabic) "💾 حفظ" else "💾 Save") { _, _ ->
                    try {
                        val newName = etName.text?.toString()?.trim().orEmpty()
                        if (newName.isNotEmpty()) {
                            device.customName = newName
                        }
                        etPower.text?.toString()?.trim()?.toIntOrNull()?.let { p ->
                            device.powerDbm = p.coerceIn(5, 33)
                        }
                        etRssi.text?.toString()?.trim()?.toIntOrNull()?.let { r ->
                            device.rssiThreshold = r.coerceIn(-100, -20)
                        }
                        etInterval.text?.toString()?.trim()?.toIntOrNull()?.let { iv ->
                            device.scanIntervalMs = iv.coerceIn(20, 2000)
                        }

                        rfidManager?.saveDevice(device)
                        if (device.isDefault) {
                            syncPowerToGlobalSettings(device.powerDbm)
                        }

                        playSuccessSound()
                        vibrateSuccess()
                        refreshDeviceList()
                        updateActiveDeviceStatus()
                        showToast(if (isArabic) "✅ تم تحديث الجهاز" else "✅ Device updated")
                    } catch (e: Exception) {
                        Log.e(TAG, "Edit save error: ${e.message}", e)
                    }
                }
                .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Show edit dialog error: ${e.message}", e)
        }
    }

    private fun showDeviceInfo(device: RFIDDevice) {
        if (!isViewAlive()) return
        try {
            val addressText = device.address.ifEmpty { "—" }
            val defaultBadge = if (device.isDefault) "✅" else "❌"

            val info = if (isArabic) {
                """
                • النوع: ${device.type.displayName}
                • الوصف: ${device.type.description}
                • العنوان: $addressText
                • قوة الإرسال: ${device.powerDbm} dBm
                • عتبة RSSI: ${device.rssiThreshold} dBm
                • فترة المسح: ${device.scanIntervalMs} ms
                • الجهاز الافتراضي: $defaultBadge
                """.trimIndent()
            } else {
                """
                • Type: ${device.type.displayName}
                • Description: ${device.type.description}
                • Address: $addressText
                • TX Power: ${device.powerDbm} dBm
                • RSSI Threshold: ${device.rssiThreshold} dBm
                • Scan Interval: ${device.scanIntervalMs} ms
                • Default Device: $defaultBadge
                """.trimIndent()
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(device.displayName)
                .setMessage(info)
                .setPositiveButton(if (isArabic) "إغلاق" else "Close", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Show device info error: ${e.message}", e)
        }
    }

    private fun setDefaultDevice(device: RFIDDevice) {
        val mgr = rfidManager ?: return
        try {
            val allDevices = mgr.allDevices ?: emptyList()
            for (d in allDevices) {
                val shouldBeDefault = (d === device) ||
                    (d.displayName == device.displayName && d.address == device.address && d.type == device.type)
                if (d.isDefault != shouldBeDefault) {
                    d.isDefault = shouldBeDefault
                    mgr.saveDevice(d)
                }
            }
            if (!device.isDefault) {
                device.isDefault = true
                mgr.saveDevice(device)
            }

            syncPowerToGlobalSettings(device.powerDbm)
            playSuccessSound()
            vibrateSuccess()
            refreshDeviceList()
            updateActiveDeviceStatus()
            showToast(if (isArabic) "✅ تم التعيين كجهاز افتراضي" else "✅ Set as default device")
        } catch (e: Exception) {
            Log.e(TAG, "Set default error: ${e.message}", e)
        }
    }

    private fun testDevice(device: RFIDDevice) {
        val mgr = rfidManager ?: return
        if (!isViewAlive()) return

        showLoadingDialog(if (isArabic) "📡 جاري اختبار الاتصال..." else "📡 Testing connection...")

        ioExecutor?.execute {
            val success = try {
                mgr.testConnection(device)
            } catch (e: Exception) {
                Log.e(TAG, "Test device error: ${e.message}", e)
                false
            }

            safeRun {
                dismissDialog(null)
                if (success) {
                    playSuccessSound()
                    vibrateSuccess()
                    showToast(if (isArabic) "✅ الاتصال ناجح" else "✅ Connected")
                } else {
                    playErrorSound()
                    vibrateError()
                    showToast(if (isArabic) "❌ فشل الاتصال بالقارئ" else "❌ Connection failed")
                }
                updateActiveDeviceStatus()
            }
        }
    }

    private fun refreshDeviceList() {
        try {
            val devices = rfidManager?.allDevices ?: emptyList()
            deviceAdapter?.updateList(devices)
        } catch (e: Exception) {
            Log.e(TAG, "Refresh list error: ${e.message}", e)
        }
    }

    // ==================== الإعدادات المتقدمة للقارئ النشط ====================

    private fun showAdvancedReaderSettings() {
        if (!isViewAlive()) return
        try {
            val reader = rfidManager?.activeReader
            if (reader == null) {
                showToast(if (isArabic) "لا يوجد قارئ نشط" else "No active reader")
                return
            }

            if (reader !is ChainwayReader) {
                showToast(
                    if (isArabic) "الإعدادات المتقدمة متاحة فقط لأجهزة Chainway"
                    else "Advanced settings only available for Chainway devices"
                )
                return
            }

            val ctx = requireContext()
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 24, 48, 8)
            }

            val etPower = TextInputEditText(ctx).apply {
                hint = if (isArabic) "قوة الإرسال (dBm) - النطاق: 10-30" else "TX Power (dBm) - Range: 10-30"
                setText(reader.powerDbm.toString())
                inputType = InputType.TYPE_CLASS_NUMBER
            }
            val etRssi = TextInputEditText(ctx).apply {
                hint = if (isArabic) "عتبة RSSI - النطاق: -100 إلى -30" else "RSSI Threshold - Range: -100 to -30"
                setText(reader.rssiThreshold.toString())
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            }
            val etInterval = TextInputEditText(ctx).apply {
                hint = if (isArabic) "فترة المسح (ms) - الحد الأدنى: 50" else "Scan Interval (ms) - Min: 50"
                setText(reader.scanIntervalMs.toString())
                inputType = InputType.TYPE_CLASS_NUMBER
            }
            val etQValue = TextInputEditText(ctx).apply {
                hint = "Q Value (0 - 15)"
                setText(reader.qValue.toString())
                inputType = InputType.TYPE_CLASS_NUMBER
            }

            layout.addView(etPower)
            layout.addView(etRssi)
            layout.addView(etInterval)
            layout.addView(etQValue)

            // Session Selector
            val sessions = arrayOf("S0", "S1", "S2", "S3")
            val safeSessionIdx = reader.session.coerceIn(0, sessions.lastIndex)
            val tvSession = TextView(ctx).apply {
                text = "Session: ${sessions[safeSessionIdx]}"
                textSize = 14f
                setPadding(0, 16, 0, 8)
            }
            layout.addView(tvSession)

            val sessionLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            sessions.forEachIndexed { idx, label ->
                val btn = MaterialButton(ctx).apply {
                    text = label
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = 6
                    }
                    setOnClickListener {
                        reader.session = idx
                        tvSession.text = "Session: $label"
                        playClickSound()
                    }
                }
                sessionLayout.addView(btn)
            }
            layout.addView(sessionLayout)

            // Target Selector
            val targets = arrayOf("A", "B")
            val safeTargetIdx = reader.target.coerceIn(0, targets.lastIndex)
            val tvTarget = TextView(ctx).apply {
                text = "Target: ${targets[safeTargetIdx]}"
                textSize = 14f
                setPadding(0, 16, 0, 8)
            }
            layout.addView(tvTarget)

            val targetLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            targets.forEachIndexed { idx, label ->
                val btn = MaterialButton(ctx).apply {
                    text = label
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = 6
                    }
                    setOnClickListener {
                        reader.target = idx
                        tvTarget.text = "Target: $label"
                        playClickSound()
                    }
                }
                targetLayout.addView(btn)
            }
            layout.addView(targetLayout)

            MaterialAlertDialogBuilder(ctx)
                .setTitle(if (isArabic) "⚙️ الإعدادات المتقدمة للقارئ" else "⚙️ Advanced Reader Settings")
                .setView(layout)
                .setPositiveButton(if (isArabic) "💾 حفظ وتطبيق" else "💾 Save & Apply") { _, _ ->
                    try {
                        val activeDevice = rfidManager?.activeDevice

                        etPower.text?.toString()?.trim()?.toIntOrNull()?.let { p ->
                            val clamped = p.coerceIn(10, 30)
                            reader.setPower(clamped)
                            activeDevice?.powerDbm = clamped
                            syncPowerToGlobalSettings(clamped)
                        }

                        etRssi.text?.toString()?.trim()?.toIntOrNull()?.let { r ->
                            val clamped = r.coerceIn(-100, -30)
                            reader.setRssiThreshold(clamped)
                            activeDevice?.rssiThreshold = clamped
                        }

                        etInterval.text?.toString()?.trim()?.toIntOrNull()?.let { iv ->
                            val clamped = iv.coerceIn(50, 1000)
                            reader.setScanInterval(clamped)
                            activeDevice?.scanIntervalMs = clamped
                        }

                        etQValue.text?.toString()?.trim()?.toIntOrNull()?.let { q ->
                            reader.qValue = q.coerceIn(0, 15)
                        }

                        if (activeDevice != null) {
                            rfidManager?.saveDevice(activeDevice)
                        }

                        playSuccessSound()
                        vibrateSuccess()
                        refreshDeviceList()
                        updateActiveDeviceStatus()
                        showToast(if (isArabic) "✅ تم حفظ الإعدادات وتطبيقها" else "✅ Settings saved & applied")
                    } catch (e: Exception) {
                        Log.e(TAG, "Save advanced settings error: ${e.message}", e)
                    }
                }
                .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Show advanced settings error: ${e.message}", e)
            showErrorDialog(
                if (isArabic) "خطأ في عرض الإعدادات: ${e.message}"
                else "Error showing settings: ${e.message}"
            )
        }
    }

    private fun applySettingsImmediately() {
        try {
            val reader = rfidManager?.activeReader
            if (reader == null) {
                showToast(if (isArabic) "لا يوجد قارئ نشط" else "No active reader")
                return
            }

            when (reader) {
                is ChainwayReader -> {
                    if (reader.isConnected) {
                        reader.setPower(reader.powerDbm)
                        reader.setRssiThreshold(reader.rssiThreshold)
                        reader.setScanInterval(reader.scanIntervalMs)
                        reader.session = reader.session
                        reader.target = reader.target
                        reader.qValue = reader.qValue
                        syncPowerToGlobalSettings(reader.powerDbm)

                        playSuccessSound()
                        vibrateSuccess()
                        showToast(
                            if (isArabic) "✅ تم تطبيق الإعدادات على القارئ"
                            else "✅ Settings applied to reader"
                        )
                    } else {
                        showToast(
                            if (isArabic) "ℹ️ سيتم تطبيق الإعدادات تلقائياً عند الاتصال"
                            else "ℹ️ Settings will apply automatically on connect"
                        )
                    }
                }

                is SimulatorReader -> {
                    reader.setPower(reader.powerDbm)
                    reader.setRssiThreshold(reader.rssiThreshold)
                    reader.setScanInterval(reader.scanIntervalMs)
                    syncPowerToGlobalSettings(reader.powerDbm)

                    playSuccessSound()
                    vibrateSuccess()
                    showToast(
                        if (isArabic) "✅ تم تطبيق الإعدادات على المحاكي"
                        else "✅ Settings applied to simulator"
                    )
                }

                else -> {
                    val active = rfidManager?.activeDevice
                    if (active != null && reader.isConnected) {
                        reader.setPower(active.powerDbm)
                        reader.setRssiThreshold(active.rssiThreshold)
                        reader.setScanInterval(active.scanIntervalMs)
                        syncPowerToGlobalSettings(active.powerDbm)
                    }
                    playSuccessSound()
                    vibrateSuccess()
                    showToast(if (isArabic) "✅ تم تطبيق الإعدادات" else "✅ Settings applied")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Apply settings error: ${e.message}", e)
            playErrorSound()
            vibrateError()
            showToast(if (isArabic) "❌ فشل تطبيق الإعدادات" else "❌ Failed to apply settings")
        }
    }

    private fun syncPowerToGlobalSettings(powerDbm: Int) {
        try {
            readerSettings?.powerDbm = powerDbm
        } catch (e: Exception) {
            Log.w(TAG, "syncPowerToGlobalSettings warning: ${e.message}")
        }
    }

    // ==================== Utilities & Cleanup ====================

    private fun isViewAlive(): Boolean = isAdded && !isViewDestroyed && context != null

    private fun safeRun(action: () -> Unit) {
        if (!isViewAlive()) return
        mainHandler.post {
            if (isViewAlive()) {
                try {
                    action()
                } catch (e: Exception) {
                    Log.e(TAG, "safeRun error: ${e.message}", e)
                }
            }
        }
    }

    private fun showToast(msg: String) {
        if (!isViewAlive() || msg.isEmpty()) return
        try {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Show toast error: ${e.message}")
        }
    }

    private fun showErrorDialog(message: String) {
        if (!isViewAlive()) return
        try {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (isArabic) "⚠️ تنبيه" else "⚠️ Alert")
                .setMessage(message)
                .setPositiveButton(if (isArabic) "موافق" else "OK", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Show error dialog error: ${e.message}")
        }
    }

    override fun onDestroyView() {
        isViewDestroyed = true
        isDiscoveringBt = false
        mainHandler.removeCallbacksAndMessages(null)
        cleanupResources()
        super.onDestroyView()
    }

    @SuppressLint("MissingPermission")
    private fun cleanupResources() {
        try {
            if (isReceiverRegistered) {
                try {
                    requireContext().unregisterReceiver(bluetoothDiscoveryReceiver)
                } catch (e: Exception) {
                    Log.w(TAG, "Unregister receiver warning: ${e.message}")
                }
                isReceiverRegistered = false
            }

            val adapter = bluetoothAdapter
            val ctx = context
            if (adapter != null && ctx != null && hasBluetoothScanPermission(ctx)) {
                try {
                    if (adapter.isDiscovering) {
                        adapter.cancelDiscovery()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Cancel discovery warning: ${e.message}")
                }
            }

            try {
                toneGenerator?.release()
            } catch (_: Exception) {
            }
            toneGenerator = null

            discoveredBtDevices.clear()
            deviceAdapter = null
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error: ${e.message}", e)
        }
    }

    // ==================== Adapter ====================

    private inner class DeviceAdapter(
        initialDevices: List<RFIDDevice>,
        private val onDeviceClick: (RFIDDevice) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

        private var devices: List<RFIDDevice> = initialDevices

        @SuppressLint("NotifyDataSetChanged")
        fun updateList(newDevices: List<RFIDDevice>) {
            devices = newDevices
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_reader_device, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val device = devices.getOrNull(position) ?: return
            try {
                holder.tvDeviceName.text = device.displayName

                val addressSuffix = if (device.address.isNotEmpty()) " • ${device.address}" else ""
                holder.tvDeviceType.text = "${device.type.description}$addressSuffix"
                holder.tvDevicePower.text = "${device.powerDbm} dBm"

                if (device.isDefault) {
                    holder.tvDefaultBadge.visibility = View.VISIBLE
                    holder.cardView?.strokeColor = Color.parseColor(COLOR_GOLD_BORDER)
                    holder.cardView?.strokeWidth = 3
                } else {
                    holder.tvDefaultBadge.visibility = View.GONE
                    holder.cardView?.strokeColor = Color.parseColor(COLOR_DEFAULT_BORDER)
                    holder.cardView?.strokeWidth = 1
                }

                holder.itemView.setOnClickListener { onDeviceClick(device) }
            } catch (e: Exception) {
                Log.e(TAG, "Bind view holder error: ${e.message}")
            }
        }

        override fun getItemCount(): Int = devices.size

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvDeviceName: TextView = v.findViewById(R.id.tvDeviceName)
            val tvDeviceType: TextView = v.findViewById(R.id.tvDeviceType)
            val tvDevicePower: TextView = v.findViewById(R.id.tvDevicePower)
            val tvDefaultBadge: TextView = v.findViewById(R.id.tvDefaultBadge)
            val cardView: MaterialCardView? = v.findViewById(R.id.cardDevice)
        }
    }
}
