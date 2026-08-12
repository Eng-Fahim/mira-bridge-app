package com.example.uhf.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

public class MiraReaderSetupFragment extends MiraBaseFragment {

    private RecyclerView rvDevices;
    private MaterialButton btnAddDevice;
    private MaterialButton btnTestConnection;
    private MaterialButton btnDetectAuto;
    
    private RFIDManager rfidManager;
    private DeviceAdapter deviceAdapter;
    
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
        
        rvDevices = view.findViewById(R.id.rvReaderDevices);
        btnAddDevice = view.findViewById(R.id.btnAddReader);
        btnTestConnection = view.findViewById(R.id.btnTestConnection);
        btnDetectAuto = view.findViewById(R.id.btnDetectAuto);
        
        deviceAdapter = new DeviceAdapter(rfidManager.getAllDevices(), this::showDeviceOptions);
        rvDevices.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvDevices.setAdapter(deviceAdapter);
        
        btnAddDevice.setOnClickListener(v -> showAddDeviceDialog());
        btnTestConnection.setOnClickListener(v -> testActiveConnection());
        btnDetectAuto.setOnClickListener(v -> autoDetectDevice());
    }
    
    private void showAddDeviceDialog() {
        MiraIRFIDReader.ReaderType[] types = MiraIRFIDReader.ReaderType.values();
        String[] names = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            names[i] = types[i].displayName + " (" + types[i].description + ")";
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(isArabic ? "إضافة جهاز RFID" : "Add RFID Device");
        builder.setItems(names, (d, which) -> {
            MiraIRFIDReader.ReaderType selected = types[which];
            if (selected.isBuiltIn) {
                saveNewDevice(selected, "");
            } else {
                showAddressInputDialog(selected);
            }
        });
        builder.setNegativeButton(isArabic ? "إلغاء" : "Cancel", null);
        builder.show();
    }
    
    private void showAddressInputDialog(MiraIRFIDReader.ReaderType type) {
        TextInputEditText input = new TextInputEditText(requireContext());
        input.setHint(isArabic ? "MAC Address أو IP:Port" : "MAC Address or IP:Port");
        
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(type.displayName);
        builder.setView(input);
        builder.setPositiveButton(isArabic ? "حفظ" : "Save", (d, w) -> {
            String address = input.getText() != null ? input.getText().toString().trim() : "";
            saveNewDevice(type, address);
        });
        builder.setNegativeButton(isArabic ? "إلغاء" : "Cancel", null);
        builder.show();
    }
    
    private void saveNewDevice(MiraIRFIDReader.ReaderType type, String address) {
        RFIDDevice device = new RFIDDevice(type, address);
        device.setCustomName(type.displayName);
        boolean saved = rfidManager.saveDevice(device);
        if (saved) {
            refreshDeviceList();
            showToast(isArabic ? "✅ تم حفظ الجهاز" : "✅ Device saved");
        }
    }
    
    private void autoDetectDevice() {
        MiraIRFIDReader.ReaderType detected = rfidManager.detectDeviceType();
        
        String message = isArabic ? "تم اكتشاف: " + detected.displayName 
            : "Detected: " + detected.displayName;
        
        new AlertDialog.Builder(requireContext())
            .setTitle(isArabic ? "نتيجة الاكتشاف" : "Detection Result")
            .setMessage(message)
            .setPositiveButton(isArabic ? "استخدام هذا الجهاز" : "Use This Device", (d, w) -> {
                saveNewDevice(detected, "");
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
                showToast(success ? "✅ الاتصال ناجح" : "❌ فشل الاتصال");
            });
        }).start();
    }
    
    private void showDeviceOptions(RFIDDevice device) {
        new AlertDialog.Builder(requireContext())
            .setTitle(device.getDisplayName())
            .setItems(new String[]{
                isArabic ? "تعيين كجهاز افتراضي" : "Set as Default",
                isArabic ? "اختبار الاتصال" : "Test Connection",
                isArabic ? "حذف" : "Delete"
            }, (d, which) -> {
                switch (which) {
                    case 0: setDefaultDevice(device); break;
                    case 1: testDevice(device); break;
                    case 2: rfidManager.deleteDevice(device); refreshDeviceList(); break;
                }
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
    }
    
    private void setDefaultDevice(RFIDDevice device) {
        for (RFIDDevice d : rfidManager.getAllDevices()) {
            d.setDefault(d == device);
        }
        refreshDeviceList();
        showToast(isArabic ? "✅ تم التعيين كافتراضي" : "✅ Set as default");
    }
    
    private void testDevice(RFIDDevice device) {
        showLoadingDialog(isArabic ? "جاري الاختبار..." : "Testing...");
        new Thread(() -> {
            boolean success = rfidManager.testConnection(device);
            safeRun(() -> {
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
                .inflate(android.R.layout.simple_list_item_2, parent, false);
            return new ViewHolder(v);
        }
        
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            RFIDDevice device = devices.get(position);
            holder.text1.setText(device.getDisplayName());
            holder.text2.setText(device.getType().description + 
                (device.isDefault() ? " ✓ " + (isArabic ? "افتراضي" : "Default") : ""));
            holder.itemView.setOnClickListener(v -> clickListener.onDeviceClick(device));
        }
        
        @Override
        public int getItemCount() {
            return devices.size();
        }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            android.widget.TextView text1, text2;
            ViewHolder(View v) {
                super(v);
                text1 = v.findViewById(android.R.id.text1);
                text2 = v.findViewById(android.R.id.text2);
            }
        }
    }
}
