package com.example.uhf.fragment;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.MiraRfid.model.RFIDDevice;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MiraReaderSetupFragment extends MiraBaseFragment {

    private static final String TAG = "MiraReaderSetup";
    private static final String PREFS_NAME = "mira_reader_ui_settings";
    private static final int PERMISSION_REQUEST_CODE = 100;
    
    private RecyclerView rvDevices;
    private MaterialButton btnAddDevice;
    private MaterialButton btnTestConnection;
    private MaterialButton btnDetectAuto;
    private MaterialButton btnBluetoothScan;
    private MaterialButton btnSoundToggle;
    private MaterialButton btnVibrationToggle;
    private TextView tvActiveDeviceStatus;
    
    private RFIDManager rfidManager;
    private DeviceAdapter deviceAdapter;
    private BluetoothAdapter bluetoothAdapter;
    private final List<BluetoothDevice> discoveredBtDevices = new ArrayList<>();
    
    // الصوت والاهتزاز
    private ToneGenerator toneGenerator;
    private Vibrator vibrator;
    private boolean isSoundEnabled = true;
    private boolean isVibrationEnabled = true;
    private SharedPreferences uiPrefs;
    private boolean isReceiverRegistered = false;
    
    private final BroadcastReceiver bluetoothDiscoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (ContextCompat.checkSelfPermission(context, 
                                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                if (!discoveredBtDevices.contains(device)) {
                                    discoveredBtDevices.add(device);
                                }
                            }
                        } else {
                            if (!discoveredBtDevices.contains(device)) {
                                discoveredBtDevices.add(device);
                            }
                        }
                    }
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    showDiscoveredBluetoothDevices();
                }
            } catch (Exception e) {
                Log.e(TAG, "Bluetooth receiver error: " + e.getMessage());
            }
        }
    };
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        try {
            return inflater.inflate(R.layout.fragment_mira_reader_setup, container, false);
        } catch (Exception e) {
            Log.e(TAG, "Error inflating layout: " + e.getMessage());
            return null;
        }
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        try {
            super.onViewCreated(view, savedInstanceState);
            initializeFragment(view);
        } catch (Exception e) {
            Log.e(TAG, "Error in onViewCreated: " + e.getMessage());
            showErrorDialog("حدث خطأ في تهيئة الشاشة: " + e.getMessage());
        }
    }
    
    private void initializeFragment(View view) {
        try {
            // تهيئة المديرين
            rfidManager = RFIDManager.getInstance(requireContext());
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            uiPrefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            
            // تحميل الإعدادات
            isSoundEnabled = uiPrefs.getBoolean("sound_enabled", true);
            isVibrationEnabled = uiPrefs.getBoolean("vibration_enabled", true);
            
            // تهيئة الصوت والاهتزاز
            initSoundAndVibration();
            
            // تسجيل مستقبل البلوتوث
            registerBluetoothReceiver();
            
            // ربط العناصر
            bindViews(view);
            
            // تهيئة القائمة
            setupRecyclerView();
            
            // تهيئة الأزرار
            setupButtons();
            
            // تحديث حالة الجهاز النشط
            updateActiveDeviceStatus();
            
        } catch (Exception e) {
            Log.e(TAG, "Initialize error: " + e.getMessage());
            showErrorDialog("خطأ في التهيئة: " + e.getMessage());
        }
    }
    
    private void initSoundAndVibration() {
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            vibrator = (Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Exception e) {
            Log.e(TAG, "Sound init error: " + e.getMessage());
            isSoundEnabled = false;
        }
    }
    
    private void registerBluetoothReceiver() {
        try {
            if (!isReceiverRegistered) {
                IntentFilter filter = new IntentFilter();
                filter.addAction(BluetoothDevice.ACTION_FOUND);
                filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                requireContext().registerReceiver(bluetoothDiscoveryReceiver, filter);
                isReceiverRegistered = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Register receiver error: " + e.getMessage());
        }
    }
    
    private void bindViews(View view) {
        rvDevices = view.findViewById(R.id.rvReaderDevices);
        btnAddDevice = view.findViewById(R.id.btnAddReader);
        btnTestConnection = view.findViewById(R.id.btnTestConnection);
        btnDetectAuto = view.findViewById(R.id.btnDetectAuto);
        btnBluetoothScan = view.findViewById(R.id.btnBluetoothScan);
        btnSoundToggle = view.findViewById(R.id.btnSoundToggle);
        btnVibrationToggle = view.findViewById(R.id.btnVibrationToggle);
        tvActiveDeviceStatus = view.findViewById(R.id.tvActiveDeviceStatus);
    }
    
    private void setupRecyclerView() {
        try {
            List<RFIDDevice> devices = rfidManager.getAllDevices();
            if (devices == null) {
                devices = new ArrayList<>();
            }
            deviceAdapter = new DeviceAdapter(devices, this::showDeviceOptions);
            rvDevices.setLayoutManager(new LinearLayoutManager(requireContext()));
            rvDevices.setAdapter(deviceAdapter);
        } catch (Exception e) {
            Log.e(TAG, "RecyclerView setup error: " + e.getMessage());
        }
    }
    
    private void setupButtons() {
        try {
            if (btnAddDevice != null) {
                btnAddDevice.setOnClickListener(v -> {
                    playClickSound();
                    vibrateShort();
                    showAddDeviceDialog();
                });
            }
            
            if (btnTestConnection != null) {
                btnTestConnection.setOnClickListener(v -> {
                    playClickSound();
                    vibrateShort();
                    testActiveConnection();
                });
            }
            
            if (btnDetectAuto != null) {
                btnDetectAuto.setOnClickListener(v -> {
                    playClickSound();
                    vibrateShort();
                    autoDetectDevice();
                });
            }
            
            if (btnBluetoothScan != null) {
                btnBluetoothScan.setOnClickListener(v -> {
                    playClickSound();
                    vibrateShort();
                    startBluetoothDiscovery();
                });
            }
            
            updateSoundButtonText();
            updateVibrationButtonText();
            
            if (btnSoundToggle != null) {
                btnSoundToggle.setOnClickListener(v -> {
                    isSoundEnabled = !isSoundEnabled;
                    uiPrefs.edit().putBoolean("sound_enabled", isSoundEnabled).apply();
                    updateSoundButtonText();
                    if (isSoundEnabled) {
                        playClickSound();
                    }
                });
            }
            
            if (btnVibrationToggle != null) {
                btnVibrationToggle.setOnClickListener(v -> {
                    isVibrationEnabled = !isVibrationEnabled;
                    uiPrefs.edit().putBoolean("vibration_enabled", isVibrationEnabled).apply();
                    updateVibrationButtonText();
                    if (isVibrationEnabled) {
                        vibrateShort();
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Button setup error: " + e.getMessage());
        }
    }
    
    // ==================== الصوت والاهتزاز ====================
    
    private void playSuccessSound() {
        try {
            if (toneGenerator != null && isSoundEnabled) {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
            }
        } catch (Exception e) {
            Log.e(TAG, "Play success sound error: " + e.getMessage());
        }
    }
    
    private void playErrorSound() {
        try {
            if (toneGenerator != null && isSoundEnabled) {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 300);
            }
        } catch (Exception e) {
            Log.e(TAG, "Play error sound error: " + e.getMessage());
        }
    }
    
    private void playClickSound() {
        try {
            if (toneGenerator != null && isSoundEnabled) {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 50);
            }
        } catch (Exception e) {
            Log.e(TAG, "Play click sound error: " + e.getMessage());
        }
    }
    
    private void vibrateShort() {
        try {
            if (vibrator != null && vibrator.hasVibrator() && isVibrationEnabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(50);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Vibrate short error: " + e.getMessage());
        }
    }
    
    private void vibrateSuccess() {
        try {
            if (vibrator != null && vibrator.hasVibrator() && isVibrationEnabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 50, 50, 50}, -1));
                } else {
                    vibrator.vibrate(new long[]{0, 50, 50, 50}, -1);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Vibrate success error: " + e.getMessage());
        }
    }
    
    private void vibrateError() {
        try {
            if (vibrator != null && vibrator.hasVibrator() && isVibrationEnabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 100, 100, 100, 100, 100}, -1));
                } else {
                    vibrator.vibrate(new long[]{0, 100, 100, 100, 100, 100}, -1);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Vibrate error error: " + e.getMessage());
        }
    }
    
    private void updateSoundButtonText() {
        if (btnSoundToggle != null) {
            btnSoundToggle.setText(isSoundEnabled ? "🔊 الصوت: مفعل" : "🔇 الصوت: معطل");
        }
    }
    
    private void updateVibrationButtonText() {
        if (btnVibrationToggle != null) {
            btnVibrationToggle.setText(isVibrationEnabled ? "📳 الاهتزاز: مفعل" : "📴 الاهتزاز: معطل");
        }
    }
    
    // ==================== حالة الجهاز النشط ====================
    
    private void updateActiveDeviceStatus() {
        if (tvActiveDeviceStatus == null) return;
        
        try {
            RFIDDevice active = rfidManager.getActiveDevice();
            if (active != null) {
                boolean connected = false;
                try {
                    connected = rfidManager.getActiveReader() != null && 
                               rfidManager.getActiveReader().isConnected();
                } catch (Exception e) {
                    Log.e(TAG, "Check connection error: " + e.getMessage());
                }
                
                tvActiveDeviceStatus.setText(
                    (isArabic ? "الجهاز النشط: " : "Active: ") + active.getDisplayName() + 
                    (connected ? " ✅" : " ⚠️"));
            } else {
                tvActiveDeviceStatus.setText(isArabic ? "لا يوجد جهاز نشط" : "No active device");
            }
        } catch (Exception e) {
            Log.e(TAG, "Update status error: " + e.getMessage());
            tvActiveDeviceStatus.setText(isArabic ? "خطأ في تحديث الحالة" : "Error updating status");
        }
    }
    
    // ==================== البحث عن البلوتوث ====================
    
    private void startBluetoothDiscovery() {
        try {
            if (bluetoothAdapter == null) {
                playErrorSound();
                vibrateError();
                showToast(isArabic ? "الجهاز لا يدعم البلوتوث" : "Bluetooth not supported");
                return;
            }
            
            if (!bluetoothAdapter.isEnabled()) {
                playErrorSound();
                vibrateError();
                showToast(isArabic ? "يرجى تفعيل البلوتوث أولاً" : "Please enable Bluetooth first");
                return;
            }
            
            // التحقق من الأذونات للأندرويد 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(requireContext(), 
                    Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(requireActivity(),
                        new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT},
                        PERMISSION_REQUEST_CODE);
                    return;
                }
            }
            
            discoveredBtDevices.clear();
            
            // إضافة الأجهزة المقترنة
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(requireContext(), 
                        Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
                        if (paired != null) {
                            discoveredBtDevices.addAll(paired);
                        }
                    }
                } else {
                    Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
                    if (paired != null) {
                        discoveredBtDevices.addAll(paired);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Get paired devices error: " + e.getMessage());
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(requireContext(), 
                    Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothAdapter.startDiscovery();
                }
            } else {
                bluetoothAdapter.startDiscovery();
            }
            
            showLoadingDialog(isArabic ? "🔍 جاري البحث عن أجهزة البلوتوث..." : "🔍 Searching for Bluetooth devices...");
            
        } catch (Exception e) {
            Log.e(TAG, "Bluetooth discovery error: " + e.getMessage());
            showErrorDialog("خطأ في البحث عن أجهزة البلوتوث: " + e.getMessage());
        }
    }
    
    private void showDiscoveredBluetoothDevices() {
        try {
            dismissDialog(null);
            
            if (discoveredBtDevices.isEmpty()) {
                playErrorSound();
                vibrateError();
                showToast(isArabic ? "لم يتم العثور على أجهزة" : "No devices found");
                return;
            }
            
            playSuccessSound();
            
            String[] names = new String[discoveredBtDevices.size()];
            for (int i = 0; i < discoveredBtDevices.size(); i++) {
                BluetoothDevice d = discoveredBtDevices.get(i);
                String name = "Unknown";
                String address = "Unknown";
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ContextCompat.checkSelfPermission(requireContext(), 
                            Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            name = d.getName() != null ? d.getName() : "Unknown";
                        }
                    } else {
                        name = d.getName() != null ? d.getName() : "Unknown";
                    }
                    address = d.getAddress();
                } catch (Exception e) {
                    Log.e(TAG, "Get device info error: " + e.getMessage());
                }
                names[i] = name + " (" + address + ")";
            }
            
            new AlertDialog.Builder(requireContext())
                .setTitle(isArabic ? "الأجهزة المكتشفة (" + discoveredBtDevices.size() + ")" : 
                    "Discovered Devices (" + discoveredBtDevices.size() + ")")
                .setItems(names, (d, which) -> {
                    try {
                        BluetoothDevice selected = discoveredBtDevices.get(which);
                        String address = selected.getAddress();
                        showAddressInputDialog(MiraIRFIDReader.ReaderType.GENERIC_BLUETOOTH, address);
                    } catch (Exception e) {
                        Log.e(TAG, "Select device error: " + e.getMessage());
                    }
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
                
        } catch (Exception e) {
            Log.e(TAG, "Show discovered devices error: " + e.getMessage());
        }
    }
    
    // ==================== إضافة جهاز ====================
    
    private void showAddDeviceDialog() {
        try {
            MiraIRFIDReader.ReaderType[] types = MiraIRFIDReader.ReaderType.values();
            String[] names = new String[types.length];
            for (int i = 0; i < types.length; i++) {
                names[i] = types[i].getFullName();
            }
            
            AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
            builder.setTitle(isArabic ? "إضافة جهاز RFID" : "Add RFID Device");
            builder.setItems(names, (d, which) -> {
                try {
                    MiraIRFIDReader.ReaderType selected = types[which];
                    if (selected.isBuiltIn) {
                        showAdvancedSettingsDialog(selected, "");
                    } else {
                        showAddressInputDialog(selected, null);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Select type error: " + e.getMessage());
                }
            });
            builder.setNegativeButton(isArabic ? "إلغاء" : "Cancel", null);
            builder.show();
        } catch (Exception e) {
            Log.e(TAG, "Show add dialog error: " + e.getMessage());
        }
    }
    
    private void showAddressInputDialog(MiraIRFIDReader.ReaderType type, String prefillAddress) {
        try {
            LinearLayout layout = new LinearLayout(requireContext());
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(40, 20, 40, 10);
            
            TextInputEditText input = new TextInputEditText(requireContext());
            input.setHint(isArabic ? "MAC Address أو IP:Port" : "MAC Address or IP:Port");
            if (prefillAddress != null) input.setText(prefillAddress);
            layout.addView(input);
            
            AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
            builder.setTitle(type.displayName);
            builder.setView(layout);
            builder.setPositiveButton(isArabic ? "متابعة" : "Next", (d, w) -> {
                String address = input.getText() != null ? input.getText().toString().trim() : "";
                showAdvancedSettingsDialog(type, address);
            });
            builder.setNegativeButton(isArabic ? "إلغاء" : "Cancel", null);
            builder.show();
        } catch (Exception e) {
            Log.e(TAG, "Show address dialog error: " + e.getMessage());
        }
    }
    
    private void showAdvancedSettingsDialog(MiraIRFIDReader.ReaderType type, String address) {
        try {
            LinearLayout layout = new LinearLayout(requireContext());
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(40, 20, 40, 10);
            
            TextInputEditText etName = new TextInputEditText(requireContext());
            etName.setHint(isArabic ? "اسم مخصص (اختياري)" : "Custom name (optional)");
            layout.addView(etName);
            
            TextInputEditText etPower = new TextInputEditText(requireContext());
            etPower.setHint(isArabic ? "قوة الإرسال (dBm) - افتراضي 28" : "Power (dBm) - default 28");
            etPower.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            layout.addView(etPower);
            
            TextInputEditText etRssi = new TextInputEditText(requireContext());
            etRssi.setHint(isArabic ? "عتبة RSSI - افتراضي -70" : "RSSI Threshold - default -70");
            etRssi.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            layout.addView(etRssi);
            
            TextInputEditText etInterval = new TextInputEditText(requireContext());
            etInterval.setHint(isArabic ? "فترة المسح (ms) - افتراضي 200" : "Scan interval (ms) - default 200");
            etInterval.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            layout.addView(etInterval);
            
            AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
            builder.setTitle(type.displayName + " - " + (isArabic ? "إعدادات متقدمة" : "Advanced Settings"));
            builder.setView(layout);
            builder.setPositiveButton(isArabic ? "حفظ" : "Save", (d, w) -> {
                try {
                    RFIDDevice device = new RFIDDevice(type, address);
                    
                    String customName = etName.getText() != null ? etName.getText().toString().trim() : "";
                    device.setCustomName(!customName.isEmpty() ? customName : type.displayName);
                    
                    try { 
                        String powerText = etPower.getText() != null ? etPower.getText().toString().trim() : "";
                        if (!powerText.isEmpty()) device.setPowerDbm(Integer.parseInt(powerText)); 
                    } catch (Exception ignored) {}
                    
                    try { 
                        String rssiText = etRssi.getText() != null ? etRssi.getText().toString().trim() : "";
                        if (!rssiText.isEmpty()) device.setRssiThreshold(Integer.parseInt(rssiText)); 
                    } catch (Exception ignored) {}
                    
                    try { 
                        String intervalText = etInterval.getText() != null ? etInterval.getText().toString().trim() : "";
                        if (!intervalText.isEmpty()) device.setScanIntervalMs(Integer.parseInt(intervalText)); 
                    } catch (Exception ignored) {}
                    
                    saveNewDevice(device);
                } catch (Exception e) {
                    Log.e(TAG, "Save device error: " + e.getMessage());
                    showErrorDialog("خطأ في حفظ الجهاز: " + e.getMessage());
                }
            });
            builder.setNegativeButton(isArabic ? "إلغاء" : "Cancel", null);
            builder.show();
        } catch (Exception e) {
            Log.e(TAG, "Show advanced dialog error: " + e.getMessage());
        }
    }
    
    private void saveNewDevice(RFIDDevice device) {
        try {
            boolean saved = rfidManager.saveDevice(device);
            if (saved) {
                playSuccessSound();
                vibrateSuccess();
                refreshDeviceList();
                updateActiveDeviceStatus();
                showToast(isArabic ? "✅ تم حفظ الجهاز" : "✅ Device saved");
            } else {
                playErrorSound();
                vibrateError();
                showToast(isArabic ? "❌ فشل الحفظ" : "❌ Save failed");
            }
        } catch (Exception e) {
            Log.e(TAG, "Save device error: " + e.getMessage());
            showErrorDialog("خطأ في حفظ الجهاز: " + e.getMessage());
        }
    }
    
    private void autoDetectDevice() {
        try {
            MiraIRFIDReader.ReaderType detected = rfidManager.detectDeviceType();
            
            playSuccessSound();
            vibrateShort();
            
            String message = isArabic ? "تم اكتشاف: " + detected.displayName 
                : "Detected: " + detected.displayName;
            
            new AlertDialog.Builder(requireContext())
                .setTitle(isArabic ? "نتيجة الاكتشاف" : "Detection Result")
                .setMessage(message)
                .setPositiveButton(isArabic ? "استخدام هذا الجهاز" : "Use This Device", (d, w) -> {
                    if (detected.isBuiltIn) {
                        showAdvancedSettingsDialog(detected, "");
                    } else {
                        showAddressInputDialog(detected, null);
                    }
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
        } catch (Exception e) {
            Log.e(TAG, "Auto detect error: " + e.getMessage());
            showErrorDialog("خطأ في الاكتشاف التلقائي: " + e.getMessage());
        }
    }
    
    private void testActiveConnection() {
        try {
            RFIDDevice active = rfidManager.getActiveDevice();
            if (active == null) {
                playErrorSound();
                vibrateError();
                showToast(isArabic ? "لا يوجد جهاز محفوظ" : "No saved device");
                return;
            }
            
            showLoadingDialog(isArabic ? "جاري اختبار الاتصال..." : "Testing connection...");
            
            new Thread(() -> {
                boolean success = false;
                try {
                    success = rfidManager.testConnection(active);
                } catch (Exception e) {
                    Log.e(TAG, "Test connection error: " + e.getMessage());
                }
                
                final boolean finalSuccess = success;
                safeRun(() -> {
                    try {
                        dismissDialog(null);
                        if (finalSuccess) {
                            playSuccessSound();
                            vibrateSuccess();
                            showToast("✅ " + (isArabic ? "الاتصال ناجح" : "Connected"));
                        } else {
                            playErrorSound();
                            vibrateError();
                            showToast("❌ " + (isArabic ? "فشل الاتصال" : "Connection failed"));
                        }
                        updateActiveDeviceStatus();
                    } catch (Exception e) {
                        Log.e(TAG, "Test result error: " + e.getMessage());
                    }
                });
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Test active connection error: " + e.getMessage());
        }
    }
    
    private void showDeviceOptions(RFIDDevice device) {
        try {
            playClickSound();
            vibrateShort();
            
            String[] options = {
                isArabic ? "تعيين كجهاز افتراضي" : "Set as Default",
                isArabic ? "اختبار الاتصال" : "Test Connection",
                isArabic ? "تعديل الإعدادات" : "Edit Settings",
                isArabic ? "عرض المعلومات" : "View Info",
                isArabic ? "حذف" : "Delete"
            };
            
            new AlertDialog.Builder(requireContext())
                .setTitle(device.getDisplayName())
                .setItems(options, (d, which) -> {
                    try {
                        switch (which) {
                            case 0: setDefaultDevice(device); break;
                            case 1: testDevice(device); break;
                            case 2: showEditDeviceDialog(device); break;
                            case 3: showDeviceInfo(device); break;
                            case 4: 
                                rfidManager.deleteDevice(device); 
                                playClickSound();
                                vibrateShort();
                                refreshDeviceList(); 
                                updateActiveDeviceStatus(); 
                                break;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Device option error: " + e.getMessage());
                    }
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
        } catch (Exception e) {
            Log.e(TAG, "Show device options error: " + e.getMessage());
        }
    }
    
    private void showEditDeviceDialog(RFIDDevice device) {
        try {
            LinearLayout layout = new LinearLayout(requireContext());
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(40, 20, 40, 10);
            
            TextInputEditText etPower = new TextInputEditText(requireContext());
            etPower.setHint(isArabic ? "قوة الإرسال (dBm)" : "Power (dBm)");
            etPower.setText(String.valueOf(device.getPowerDbm()));
            layout.addView(etPower);
            
            TextInputEditText etRssi = new TextInputEditText(requireContext());
            etRssi.setHint(isArabic ? "عتبة RSSI" : "RSSI Threshold");
            etRssi.setText(String.valueOf(device.getRssiThreshold()));
            layout.addView(etRssi);
            
            TextInputEditText etInterval = new TextInputEditText(requireContext());
            etInterval.setHint(isArabic ? "فترة المسح (ms)" : "Scan interval (ms)");
            etInterval.setText(String.valueOf(device.getScanIntervalMs()));
            layout.addView(etInterval);
            
            new AlertDialog.Builder(requireContext())
                .setTitle(device.getDisplayName())
                .setView(layout)
                .setPositiveButton(isArabic ? "حفظ" : "Save", (d, w) -> {
                    try {
                        try { device.setPowerDbm(Integer.parseInt(etPower.getText().toString())); } catch (Exception ignored) {}
                        try { device.setRssiThreshold(Integer.parseInt(etRssi.getText().toString())); } catch (Exception ignored) {}
                        try { device.setScanIntervalMs(Integer.parseInt(etInterval.getText().toString())); } catch (Exception ignored) {}
                        rfidManager.saveDevice(device);
                        playSuccessSound();
                        vibrateSuccess();
                        refreshDeviceList();
                    } catch (Exception e) {
                        Log.e(TAG, "Edit save error: " + e.getMessage());
                    }
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
        } catch (Exception e) {
            Log.e(TAG, "Show edit dialog error: " + e.getMessage());
        }
    }
    
    private void showDeviceInfo(RFIDDevice device) {
        try {
            String info = 
                (isArabic ? "النوع: " : "Type: ") + device.getType().displayName + "\n" +
                (isArabic ? "الوصف: " : "Description: ") + device.getType().description + "\n" +
                (isArabic ? "العنوان: " : "Address: ") + (device.getAddress().isEmpty() ? "—" : device.getAddress()) + "\n" +
                (isArabic ? "القوة: " : "Power: ") + device.getPowerDbm() + " dBm\n" +
                (isArabic ? "RSSI: " : "RSSI: ") + device.getRssiThreshold() + "\n" +
                (isArabic ? "فترة المسح: " : "Interval: ") + device.getScanIntervalMs() + "ms\n" +
                (isArabic ? "افتراضي: " : "Default: ") + (device.isDefault() ? "✅" : "❌");
            
            new AlertDialog.Builder(requireContext())
                .setTitle(device.getDisplayName())
                .setMessage(info)
                .setPositiveButton(isArabic ? "إغلاق" : "Close", null)
                .show();
        } catch (Exception e) {
            Log.e(TAG, "Show device info error: " + e.getMessage());
        }
    }
    
    private void setDefaultDevice(RFIDDevice device) {
        try {
            for (RFIDDevice d : rfidManager.getAllDevices()) {
                d.setDefault(d == device);
            }
            playSuccessSound();
            vibrateSuccess();
            refreshDeviceList();
            updateActiveDeviceStatus();
            showToast(isArabic ? "✅ تم التعيين كافتراضي" : "✅ Set as default");
        } catch (Exception e) {
            Log.e(TAG, "Set default error: " + e.getMessage());
        }
    }
    
    private void testDevice(RFIDDevice device) {
        try {
            showLoadingDialog(isArabic ? "جاري الاختبار..." : "Testing...");
            new Thread(() -> {
                boolean success = false;
                try {
                    success = rfidManager.testConnection(device);
                } catch (Exception e) {
                    Log.e(TAG, "Test device error: " + e.getMessage());
                }
                
                final boolean finalSuccess = success;
                safeRun(() -> {
                    try {
                        dismissDialog(null);
                        if (finalSuccess) {
                            playSuccessSound();
                            vibrateSuccess();
                            showToast("✅ " + (isArabic ? "متصل" : "Connected"));
                        } else {
                            playErrorSound();
                            vibrateError();
                            showToast("❌ " + (isArabic ? "غير متصل" : "Disconnected"));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Test device result error: " + e.getMessage());
                    }
                });
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Test device error: " + e.getMessage());
        }
    }
    
    private void refreshDeviceList() {
        try {
            if (deviceAdapter != null) {
                List<RFIDDevice> devices = rfidManager.getAllDevices();
                if (devices == null) {
                    devices = new ArrayList<>();
                }
                deviceAdapter.updateList(devices);
            }
        } catch (Exception e) {
            Log.e(TAG, "Refresh list error: " + e.getMessage());
        }
    }
    
    private void safeRun(Runnable r) {
        try {
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (isAdded()) r.run();
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Safe run error: " + e.getMessage());
        }
    }
    
    private void showToast(String msg) {
        try {
            if (getContext() != null && isAdded()) {
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Show toast error: " + e.getMessage());
        }
    }
    
    private void showErrorDialog(String message) {
        try {
            if (isAdded() && getContext() != null) {
                new AlertDialog.Builder(getContext())
                    .setTitle(isArabic ? "خطأ" : "Error")
                    .setMessage(message)
                    .setPositiveButton(isArabic ? "موافق" : "OK", null)
                    .show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Show error dialog error: " + e.getMessage());
        }
    }
    
    @Override
    public void onDestroyView() {
        cleanupResources();
        super.onDestroyView();
    }
    
    private void cleanupResources() {
        try {
            if (isReceiverRegistered) {
                try {
                    requireContext().unregisterReceiver(bluetoothDiscoveryReceiver);
                } catch (Exception e) {
                    Log.e(TAG, "Unregister receiver error: " + e.getMessage());
                }
                isReceiverRegistered = false;
            }
            
            if (bluetoothAdapter != null) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ContextCompat.checkSelfPermission(requireContext(), 
                            Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                            bluetoothAdapter.cancelDiscovery();
                        }
                    } else {
                        bluetoothAdapter.cancelDiscovery();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Cancel discovery error: " + e.getMessage());
                }
            }
            
            if (toneGenerator != null) {
                try {
                    toneGenerator.release();
                    toneGenerator = null;
                } catch (Exception e) {
                    Log.e(TAG, "Release tone generator error: " + e.getMessage());
                }
            }
            
            if (discoveredBtDevices != null) {
                discoveredBtDevices.clear();
            }
            
            deviceAdapter = null;
        } catch (Exception e) {
            Log.e(TAG, "Cleanup error: " + e.getMessage());
        }
    }
    
    // ==================== Adapter ====================
    
    private interface OnDeviceClickListener {
        void onDeviceClick(RFIDDevice device);
    }
    
    private class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {
        
        private List<RFIDDevice> devices;
        private final OnDeviceClickListener clickListener;
        
        DeviceAdapter(List<RFIDDevice> devices, OnDeviceClickListener listener) {
            this.devices = devices != null ? devices : new ArrayList<>();
            this.clickListener = listener;
        }
        
        void updateList(List<RFIDDevice> newDevices) {
            this.devices = newDevices != null ? newDevices : new ArrayList<>();
            notifyDataSetChanged();
        }
        
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_reader_device, parent, false);
            return new ViewHolder(v);
        }
        
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            try {
                RFIDDevice device = devices.get(position);
                holder.tvDeviceName.setText(device.getDisplayName());
                holder.tvDeviceType.setText(device.getType().description);
                holder.tvDevicePower.setText(device.getPowerDbm() + " dBm");
                
                if (device.isDefault()) {
                    holder.tvDefaultBadge.setVisibility(View.VISIBLE);
                } else {
                    holder.tvDefaultBadge.setVisibility(View.GONE);
                }
                
                holder.itemView.setOnClickListener(v -> clickListener.onDeviceClick(device));
            } catch (Exception e) {
                Log.e(TAG, "Bind view holder error: " + e.getMessage());
            }
        }
        
        @Override
        public int getItemCount() {
            return devices.size();
        }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvDeviceName, tvDeviceType, tvDevicePower, tvDefaultBadge;
            MaterialCardView cardView;
            
            ViewHolder(View v) {
                super(v);
                tvDeviceName = v.findViewById(R.id.tvDeviceName);
                tvDeviceType = v.findViewById(R.id.tvDeviceType);
                tvDevicePower = v.findViewById(R.id.tvDevicePower);
                tvDefaultBadge = v.findViewById(R.id.tvDefaultBadge);
                cardView = v.findViewById(R.id.cardDevice);
            }
        }
    }
                                                                                         }
