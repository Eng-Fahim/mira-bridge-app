package com.example.uhf.fragment;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
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
 * شاشة إعدادات قارئ RFID - نسخة متطورة
 * تدعم: اكتشاف تلقائي، إضافة يدوي، اختبار اتصال، إعدادات متقدمة، ماسح بلوتوث
 */
public class MiraReaderSetupFragment extends MiraBaseFragment {

    private static final String TAG = "MiraReaderSetup";
    
    private RecyclerView rvDevices;
    private MaterialButton btnAddDevice;
    private MaterialButton btnTestConnection;
    private MaterialButton btnDetectAuto;
    private MaterialButton btnBluetoothScan;
    private TextView tvActiveDeviceStatus;
    
    private RFIDManager rfidManager;
    private DeviceAdapter deviceAdapter;
    private BluetoothAdapter bluetoothAdapter;
    private final List<BluetoothDevice> discoveredBtDevices = new ArrayList<>();
    
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
        
        rvDevices = view.findViewById(R.id.rvReaderDevices);
        btnAddDevice = view.findViewById(R.id.btnAddReader);
        btnTestConnection = view.findViewById(R.id.btnTestConnection);
        btnDetectAuto = view.findViewById(R.id.btnDetectAuto);
        btnBluetoothScan = view.findViewById(R.id.btnBluetoothScan);
        tvActiveDeviceStatus = view.findViewById(R.id.tvActiveDeviceStatus);
        
        // ✅ تسجيل مستقبل اكتشاف البلوتوث
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        requireContext().registerReceiver(bluetoothDiscoveryReceiver, filter);
        
        deviceAdapter = new DeviceAdapter(rfidManager.getAllDevices(), this::showDeviceOptions);
        rvDevices.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvDevices.setAdapter(deviceAdapter);
        
        btnAddDevice.setOnClickListener(v -> showAddDeviceDialog());
        btnTestConnection.setOnClickListener(v -> testActiveConnection());
        btnDetectAuto.setOnClickListener(v -> autoDetectDevice());
        
        if (btnBluetoothScan != null) {
            btnBluetoothScan.setOnClickListener(v -> startBluetoothDiscovery());
        }
        
        updateActiveDeviceStatus();
    }
    
    /**
     * ✅ تحديث حالة الجهاز النشط في الواجهة
     */
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
    
    /**
     * ✅ البحث عن أجهزة البلوتوث القريبة
     */
    private void startBluetoothDiscovery() {
        if (bluetoothAdapter == null) {
            showToast(isArabic ? "الجهاز لا يدعم البلوتوث" : "Bluetooth not supported");
            return;
        }
        
        if (!bluetoothAdapter.isEnabled()) {
            showToast(isArabic ? "يرجى تفعيل البلوتوث أولاً" : "Please enable Bluetooth first");
            return;
        }
        
        discoveredBtDevices.clear();
        
        // ✅ الأجهزة المقترنة أولاً
        Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
        discoveredBtDevices.addAll(paired);
        
        // ✅ بدء البحث
        bluetoothAdapter.startDiscovery();
        showLoadingDialog(isArabic ? "🔍 جاري البحث عن أجهزة البلوتوث..." : "🔍 Searching for Bluetooth devices...");
    }
    
    /**
     * ✅ عرض الأجهزة المكتشفة
     */
    private void showDiscoveredBluetoothDevices() {
        dismissDialog(null);
        
        if (discoveredBtDevices.isEmpty()) {
            showToast(isArabic ? "لم يتم العثور على أجهزة" : "No devices found");
            return;
        }
        
        String[] names = new String[discoveredBtDevices.size()];
        for (int i = 0; i < discoveredBtDevices.size(); i++) {
            BluetoothDevice d = discoveredBtDevices.get(i);
            names[i] = d.getName() + " (" + d.getAddress() + ")";
        }
        
        new AlertDialog.Builder(requireContext())
            .setTitle(isArabic ? "الأجهزة المكتشفة" : "Discovered Devices")
            .setItems(names, (d, which) -> {
                BluetoothDevice selected = discoveredBtDevices.get(which);
                showAddressInputDialog(MiraIRFIDReader.ReaderType.GENERIC_BLUETOOTH, selected.getAddress());
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
    }
    
    /**
     * ✅ إضافة جهاز مع إعدادات متقدمة
     */
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
    
    /**
     * ✅ إدخال عنوان الجهاز
     */
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
    
    /**
     * ✅ إعدادات متقدمة للجهاز
     */
    private void showAdvancedSettingsDialog(MiraIRFIDReader.ReaderType type, String address) {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 10);
        
        // الاسم المخصص
        TextInputEditText etName = new TextInputEditText(requireContext());
        etName.setHint(isArabic ? "اسم مخصص (اختياري)" : "Custom name (optional)");
        layout.addView(etName);
        
        // قوة الإرسال
        TextInputEditText etPower = new TextInputEditText(requireContext());
        etPower.setHint(isArabic ? "قوة الإرسال (dBm) - افتراضي 28" : "Power (dBm) - default 28");
        etPower.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        layout.addView(etPower);
        
        // حساسية RSSI
        TextInputEditText etRssi = new TextInputEditText(requireContext());
        etRssi.setHint(isArabic ? "عتبة RSSI - افتراضي -70" : "RSSI Threshold - default -70");
        etRssi.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        layout.addView(etRssi);
        
        // فترة المسح
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
            if (!customName.isEmpty()) device.setCustomName(customName);
            else device.setCustomName(type.displayName);
            
            try {
                int power = Integer.parseInt(etPower.getText().toString().trim());
                device.setPowerDbm(power);
            } catch (Exception ignored) {}
            
            try {
                int rssi = Integer.parseInt(etRssi.getText().toString().trim());
                device.setRssiThreshold(rssi);
            } catch (Exception ignored) {}
            
            try {
                int interval = Integer.parseInt(etInterval.getText().toString().trim());
                device.setScanIntervalMs(interval);
            } catch (Exception ignored) {}
            
            saveNewDevice(device);
        });
        builder.setNegativeButton(isArabic ? "إلغاء" : "Cancel", null);
        builder.show();
    }
    
    private void saveNewDevice(RFIDDevice device) {
        boolean saved = rfidManager.saveDevice(device);
        if (saved) {
            refreshDeviceList();
            updateActiveDeviceStatus();
            showToast(isArabic ? "✅ تم حفظ الجهاز" : "✅ Device saved");
        }
    }
    
    private void saveNewDevice(MiraIRFIDReader.ReaderType type, String address) {
        RFIDDevice device = new RFIDDevice(type, address);
        device.setCustomName(type.displayName);
        saveNewDevice(device);
    }
    
    private void autoDetectDevice() {
        MiraIRFIDReader.ReaderType detected = rfidManager.detectDeviceType();
        
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
            showToast(isArabic ? "لا يوجد جهاز محفوظ" : "No saved device");
            return;
        }
        
        showLoadingDialog(isArabic ? "جاري اختبار الاتصال..." : "Testing connection...");
        
        new Thread(() -> {
            boolean success = rfidManager.testConnection(active);
            safeRun(() -> {
                dismissDialog(null);
                showToast(success ? "✅ الاتصال ناجح" : "❌ فشل الاتصال");
                updateActiveDeviceStatus();
            });
        }).start();
    }
    
    private void showDeviceOptions(RFIDDevice device) {
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
                    case 4: rfidManager.deleteDevice(device); refreshDeviceList(); updateActiveDeviceStatus(); break;
                }
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
    }
    
    /**
     * ✅ تعديل إعدادات جهاز موجود
     */
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
                refreshDeviceList();
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
    }
    
    /**
     * ✅ عرض معلومات الجهاز
     */
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
                showToast(success ? "✅ متصل" : "❌ غير متصل");
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
