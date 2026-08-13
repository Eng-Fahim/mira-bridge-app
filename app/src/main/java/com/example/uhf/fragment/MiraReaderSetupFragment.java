package com.example.uhf.fragment;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.SoundPool;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
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

/**
 * شاشة إعدادات قارئ RFID - نسخة متطورة كاملة
 * تدعم: اكتشاف تلقائي، إضافة يدوي، اختبار اتصال، إعدادات متقدمة،
 * ماسح بلوتوث، أصوات واهتزازات، بطاقات مخصصة
 */
public class MiraReaderSetupFragment extends MiraBaseFragment {

    private static final String TAG = "MiraReaderSetup";
    private static final String PREFS_NAME = "mira_reader_ui_settings";
    
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
    
    // 🔊 الصوت والاهتزاز
    private SoundPool soundPool;
    private int soundSuccessId, soundErrorId, soundClickId;
    private Vibrator vibrator;
    private boolean isSoundEnabled = true;
    private boolean isVibrationEnabled = true;
    private SharedPreferences uiPrefs;
    
    private final BroadcastReceiver bluetoothDiscoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && !discoveredBtDevices.contains(device)) {
                    discoveredBtDevices.add(device);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                showDiscoveredBluetoothDevices();
            }
        }
    };
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_reader_setup, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        rfidManager = RFIDManager.getInstance(requireContext());
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        uiPrefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        // ✅ تحميل إعدادات الصوت والاهتزاز
        isSoundEnabled = uiPrefs.getBoolean("sound_enabled", true);
        isVibrationEnabled = uiPrefs.getBoolean("vibration_enabled", true);
        
        // ✅ تهيئة الصوت
        initSound();
        
        // ✅ تهيئة الاهتزاز
        vibrator = (Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);
        
        // ✅ تسجيل مستقبل اكتشاف البلوتوث
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        requireContext().registerReceiver(bluetoothDiscoveryReceiver, filter);
        
        // ✅ ربط العناصر
        rvDevices = view.findViewById(R.id.rvReaderDevices);
        btnAddDevice = view.findViewById(R.id.btnAddReader);
        btnTestConnection = view.findViewById(R.id.btnTestConnection);
        btnDetectAuto = view.findViewById(R.id.btnDetectAuto);
        btnBluetoothScan = view.findViewById(R.id.btnBluetoothScan);
        btnSoundToggle = view.findViewById(R.id.btnSoundToggle);
        btnVibrationToggle = view.findViewById(R.id.btnVibrationToggle);
        tvActiveDeviceStatus = view.findViewById(R.id.tvActiveDeviceStatus);
        
        deviceAdapter = new DeviceAdapter(rfidManager.getAllDevices(), this::showDeviceOptions);
        rvDevices.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvDevices.setAdapter(deviceAdapter);
        
        // ✅ الأزرار
        btnAddDevice.setOnClickListener(v -> { playClickSound(); vibrateShort(); showAddDeviceDialog(); });
        btnTestConnection.setOnClickListener(v -> { playClickSound(); vibrateShort(); testActiveConnection(); });
        btnDetectAuto.setOnClickListener(v -> { playClickSound(); vibrateShort(); autoDetectDevice(); });
        
        if (btnBluetoothScan != null) {
            btnBluetoothScan.setOnClickListener(v -> { playClickSound(); vibrateShort(); startBluetoothDiscovery(); });
        }
        
        // ✅ أزرار الصوت والاهتزاز
        updateSoundButtonText();
        updateVibrationButtonText();
        
        if (btnSoundToggle != null) {
            btnSoundToggle.setOnClickListener(v -> {
                isSoundEnabled = !isSoundEnabled;
                uiPrefs.edit().putBoolean("sound_enabled", isSoundEnabled).apply();
                updateSoundButtonText();
                playClickSound();
            });
        }
        
        if (btnVibrationToggle != null) {
            btnVibrationToggle.setOnClickListener(v -> {
                isVibrationEnabled = !isVibrationEnabled;
                uiPrefs.edit().putBoolean("vibration_enabled", isVibrationEnabled).apply();
                updateVibrationButtonText();
                if (isVibrationEnabled) vibrateShort();
            });
        }
        
        updateActiveDeviceStatus();
    }
    
    // ==================== 🔊 الصوت والاهتزاز ====================
    
    private void initSound() {
        try {
            soundPool = new SoundPool.Builder().setMaxStreams(3).build();
            soundSuccessId = soundPool.load(requireContext(), R.raw.barcodebeep, 1);
            soundErrorId = soundPool.load(requireContext(), R.raw.serror, 1);
            soundClickId = soundPool.load(requireContext(), R.raw.barcodebeep, 2);
        } catch (Exception e) {
            Log.e(TAG, "Sound init error: " + e.getMessage());
        }
    }
    
    private void playSuccessSound() {
        if (soundPool != null && isSoundEnabled) {
            soundPool.play(soundSuccessId, 0.7f, 0.7f, 1, 0, 1.0f);
        }
    }
    
    private void playErrorSound() {
        if (soundPool != null && isSoundEnabled) {
            soundPool.play(soundErrorId, 0.8f, 0.8f, 1, 0, 1.0f);
        }
    }
    
    private void playClickSound() {
        if (soundPool != null && isSoundEnabled) {
            soundPool.play(soundClickId, 0.5f, 0.5f, 1, 0, 1.0f);
        }
    }
    
    private void vibrateShort() {
        if (vibrator != null && vibrator.hasVibrator() && isVibrationEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(50);
            }
        }
    }
    
    private void vibrateSuccess() {
        if (vibrator != null && vibrator.hasVibrator() && isVibrationEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(
                    new long[]{0, 50, 50, 50}, -1));
            } else {
                vibrator.vibrate(new long[]{0, 50, 50, 50}, -1);
            }
        }
    }
    
    private void vibrateError() {
        if (vibrator != null && vibrator.hasVibrator() && isVibrationEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(
                    new long[]{0, 100, 100, 100, 100, 100}, -1));
            } else {
                vibrator.vibrate(new long[]{0, 100, 100, 100, 100, 100}, -1);
            }
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
        
        RFIDDevice active = rfidManager.getActiveDevice();
        if (active != null) {
            boolean connected = rfidManager.getActiveReader() != null && 
                               rfidManager.getActiveReader().isConnected();
            tvActiveDeviceStatus.setText(
                (isArabic ? "الجهاز النشط: " : "Active: ") + active.getDisplayName() + 
                (connected ? " ✅" : " ⚠️"));
        } else {
            tvActiveDeviceStatus.setText(isArabic ? "لا يوجد جهاز نشط" : "No active device");
        }
    }
    
    // ==================== البحث عن البلوتوث ====================
    
    private void startBluetoothDiscovery() {
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
        
        discoveredBtDevices.clear();
        Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
        discoveredBtDevices.addAll(paired);
        
        bluetoothAdapter.startDiscovery();
        showLoadingDialog(isArabic ? "🔍 جاري البحث عن أجهزة البلوتوث..." : "🔍 Searching for Bluetooth devices...");
    }
    
    private void showDiscoveredBluetoothDevices() {
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
            String name = d.getName() != null ? d.getName() : "Unknown";
            names[i] = name + " (" + d.getAddress() + ")";
        }
        
        new AlertDialog.Builder(requireContext())
            .setTitle(isArabic ? "الأجهزة المكتشفة (" + discoveredBtDevices.size() + ")" : "Discovered Devices (" + discoveredBtDevices.size() + ")")
            .setItems(names, (d, which) -> {
                BluetoothDevice selected = discoveredBtDevices.get(which);
                showAddressInputDialog(MiraIRFIDReader.ReaderType.GENERIC_BLUETOOTH, selected.getAddress());
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
    }
    
    // ==================== إضافة جهاز ====================
    
    private void showAddDeviceDialog() {
        MiraIRFIDReader.ReaderType[] types = MiraIRFIDReader.ReaderType.values();
        String[] names = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            names[i] = types[i].getFullName();
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(isArabic ? "إضافة جهاز RFID" : "Add RFID Device");
        builder.setItems(names, (d, which) -> {
            MiraIRFIDReader.ReaderType selected = types[which];
            if (selected.isBuiltIn) {
                showAdvancedSettingsDialog(selected, "");
            } else {
                showAddressInputDialog(selected, null);
            }
        });
        builder.setNegativeButton(isArabic ? "إلغاء" : "Cancel", null);
        builder.show();
    }
    
    private void showAddressInputDialog(MiraIRFIDReader.ReaderType type, String prefillAddress) {
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
    }
    
    private void showAdvancedSettingsDialog(MiraIRFIDReader.ReaderType type, String address) {
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
            RFIDDevice device = new RFIDDevice(type, address);
            
            String customName = etName.getText() != null ? etName.getText().toString().trim() : "";
            device.setCustomName(!customName.isEmpty() ? customName : type.displayName);
            
            try { device.setPowerDbm(Integer.parseInt(etPower.getText().toString().trim())); } catch (Exception ignored) {}
            try { device.setRssiThreshold(Integer.parseInt(etRssi.getText().toString().trim())); } catch (Exception ignored) {}
            try { device.setScanIntervalMs(Integer.parseInt(etInterval.getText().toString().trim())); } catch (Exception ignored) {}
            
            saveNewDevice(device);
        });
        builder.setNegativeButton(isArabic ? "إلغاء" : "Cancel", null);
        builder.show();
    }
    
    private void saveNewDevice(RFIDDevice device) {
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
    }
    
    private void saveNewDevice(MiraIRFIDReader.ReaderType type, String address) {
        RFIDDevice device = new RFIDDevice(type, address);
        device.setCustomName(type.displayName);
        saveNewDevice(device);
    }
    
    private void autoDetectDevice() {
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
    }
    
    private void testActiveConnection() {
        RFIDDevice active = rfidManager.getActiveDevice();
        if (active == null) {
            playErrorSound();
            vibrateError();
            showToast(isArabic ? "لا يوجد جهاز محفوظ" : "No saved device");
            return;
        }
        
        showLoadingDialog(isArabic ? "جاري اختبار الاتصال..." : "Testing connection...");
        
        new Thread(() -> {
            boolean success = rfidManager.testConnection(active);
            safeRun(() -> {
                dismissDialog(null);
                if (success) {
                    playSuccessSound();
                    vibrateSuccess();
                    showToast("✅ " + (isArabic ? "الاتصال ناجح" : "Connected"));
                } else {
                    playErrorSound();
                    vibrateError();
                    showToast("❌ " + (isArabic ? "فشل الاتصال" : "Connection failed"));
                }
                updateActiveDeviceStatus();
            });
        }).start();
    }
    
    private void showDeviceOptions(RFIDDevice device) {
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
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
    }
    
    private void showEditDeviceDialog(RFIDDevice device) {
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
                try { device.setPowerDbm(Integer.parseInt(etPower.getText().toString())); } catch (Exception ignored) {}
                try { device.setRssiThreshold(Integer.parseInt(etRssi.getText().toString())); } catch (Exception ignored) {}
                try { device.setScanIntervalMs(Integer.parseInt(etInterval.getText().toString())); } catch (Exception ignored) {}
                rfidManager.saveDevice(device);
                playSuccessSound();
                vibrateSuccess();
                refreshDeviceList();
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
    }
    
    private void showDeviceInfo(RFIDDevice device) {
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
    }
    
    private void setDefaultDevice(RFIDDevice device) {
        for (RFIDDevice d : rfidManager.getAllDevices()) {
            d.setDefault(d == device);
        }
        playSuccessSound();
        vibrateSuccess();
        refreshDeviceList();
        updateActiveDeviceStatus();
        showToast(isArabic ? "✅ تم التعيين كافتراضي" : "✅ Set as default");
    }
    
    private void testDevice(RFIDDevice device) {
        showLoadingDialog(isArabic ? "جاري الاختبار..." : "Testing...");
        new Thread(() -> {
            boolean success = rfidManager.testConnection(device);
            safeRun(() -> {
                dismissDialog(null);
                if (success) {
                    playSuccessSound();
                    vibrateSuccess();
                    showToast("✅ " + (isArabic ? "متصل" : "Connected"));
                } else {
                    playErrorSound();
                    vibrateError();
                    showToast("❌ " + (isArabic ? "غير متصل" : "Disconnected"));
                }
            });
        }).start();
    }
    
    private void refreshDeviceList() {
        deviceAdapter.updateList(rfidManager.getAllDevices());
    }
    
    private void safeRun(Runnable r) {
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (isAdded()) r.run();
            });
        }
    }
    
    private void showToast(String msg) {
        if (getContext() != null && isAdded()) {
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
        }
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try {
            requireContext().unregisterReceiver(bluetoothDiscoveryReceiver);
        } catch (Exception ignored) {}
        if (bluetoothAdapter != null) {
            bluetoothAdapter.cancelDiscovery();
        }
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
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
            this.devices = devices;
            this.clickListener = listener;
        }
        
        void updateList(List<RFIDDevice> newDevices) {
            this.devices = newDevices;
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
