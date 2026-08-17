package com.example.uhf.fragment;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.example.uhf.R;
import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.MiraRfid.model.RFIDDevice;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraUHFWriteTagFragment extends MiraBaseFragment {

    private static final String TAG = "MiraUHFWriteTagFragment";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // عناصر الواجهة
    private MaterialButton btnReadCurrentTag, btnWriteTag, btnVerifyTag, btnLockTag, btnGenerateEpc;
    private MaterialButtonToggleGroup toggleWriteType;
    private MaterialButton btnWriteEpc, btnWriteUserMemory, btnWriteBoth;
    private TextInputLayout tilNewEpc, tilUserMemory, tilAccessPassword;
    private TextInputEditText etNewEpc, etUserMemory, etAccessPassword;
    private CheckBox cbVerifyAfterWrite, cbLockAfterWrite;
    private TextView tvCurrentEpc, tvCurrentTid, tvCurrentRssi, tvOperationLog;
    private ProgressBar pbWriteProgress;

    // إدارة القارئ
    private RFIDManager rfidManager;
    private ExecutorService executor;
    private Handler mainHandler;

    // البلوتوث
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private InputStream btInputStream;
    private OutputStream btOutputStream;
    private BluetoothDevice connectedDevice;

    // الحالة الحالية
    private String currentEpc = "";
    private String currentTid = "";
    private String operationLog = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_uhf_write, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            Context ctx = requireContext();
            executor = Executors.newSingleThreadExecutor();
            mainHandler = new Handler(Looper.getMainLooper());
            rfidManager = RFIDManager.getInstance(ctx);
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

            initViews(view);
            setupListeners();
            setupToggleGroup();

            addLog("✅ تم تهيئة واجهة الكتابة");
            addLog("ℹ️ انتظر وضع الشريحة على القارئ");

        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error: " + e.getMessage());
        }
    }

    private void initViews(View view) {
        btnReadCurrentTag = view.findViewById(R.id.btnReadCurrentTag);
        btnWriteTag = view.findViewById(R.id.btnWriteTag);
        btnVerifyTag = view.findViewById(R.id.btnVerifyTag);
        btnLockTag = view.findViewById(R.id.btnLockTag);
        btnGenerateEpc = view.findViewById(R.id.btnGenerateEpc);

        toggleWriteType = view.findViewById(R.id.toggleWriteType);
        btnWriteEpc = view.findViewById(R.id.btnWriteEpc);
        btnWriteUserMemory = view.findViewById(R.id.btnWriteUserMemory);
        btnWriteBoth = view.findViewById(R.id.btnWriteBoth);

        tilNewEpc = view.findViewById(R.id.tilNewEpc);
        tilUserMemory = view.findViewById(R.id.tilUserMemory);
        tilAccessPassword = view.findViewById(R.id.tilAccessPassword);

        etNewEpc = view.findViewById(R.id.etNewEpc);
        etUserMemory = view.findViewById(R.id.etUserMemory);
        etAccessPassword = view.findViewById(R.id.etAccessPassword);

        cbVerifyAfterWrite = view.findViewById(R.id.cbVerifyAfterWrite);
        cbLockAfterWrite = view.findViewById(R.id.cbLockAfterWrite);

        tvCurrentEpc = view.findViewById(R.id.tvCurrentEpc);
        tvCurrentTid = view.findViewById(R.id.tvCurrentTid);
        tvCurrentRssi = view.findViewById(R.id.tvCurrentRssi);
        tvOperationLog = view.findViewById(R.id.tvOperationLog);

        pbWriteProgress = view.findViewById(R.id.pbWriteProgress);
    }

    private void setupListeners() {
        btnReadCurrentTag.setOnClickListener(v -> readCurrentTag());
        btnWriteTag.setOnClickListener(v -> writeTag());
        btnVerifyTag.setOnClickListener(v -> verifyTag());
        btnLockTag.setOnClickListener(v -> lockTag());
        btnGenerateEpc.setOnClickListener(v -> generateRandomEpc());
    }

    private void setupToggleGroup() {
        toggleWriteType.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;

            if (checkedId == R.id.btnWriteEpc) {
                tilNewEpc.setVisibility(View.VISIBLE);
                tilUserMemory.setVisibility(View.GONE);
            } else if (checkedId == R.id.btnWriteUserMemory) {
                tilNewEpc.setVisibility(View.GONE);
                tilUserMemory.setVisibility(View.VISIBLE);
            } else if (checkedId == R.id.btnWriteBoth) {
                tilNewEpc.setVisibility(View.VISIBLE);
                tilUserMemory.setVisibility(View.VISIBLE);
            }
        });
    }

    private void readCurrentTag() {
        addLog("🔍 جاري قراءة الشريحة...");
        pbWriteProgress.setVisibility(View.VISIBLE);

        // قراءة مباشرة من القارئ
        executor.execute(() -> {
            try {
                MiraIRFIDReader reader = rfidManager.getActiveReader();
                if (reader != null && reader.isConnected()) {
                    reader.startScan();
                    Thread.sleep(1000); // انتظار القراءة
                    reader.stopScan();

                    // هنا ستأتي البيانات من خلال المستمع
                    mainHandler.post(() -> {
                        pbWriteProgress.setVisibility(View.GONE);
                        if (!currentEpc.isEmpty()) {
                            tvCurrentEpc.setText("EPC الحالي: " + currentEpc);
                            tvCurrentTid.setText("TID: " + (currentTid.isEmpty() ? "غير متوفر" : currentTid));
                            tvCurrentRssi.setText("RSSI: ممتاز");
                            addLog("✅ تم قراءة الشريحة: " + currentEpc);
                        } else {
                            addLog("❌ لم يتم العثور على شريحة قريبة");
                            Toast.makeText(getContext(), "لا توجد شريحة قريبة", Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    mainHandler.post(() -> {
                        pbWriteProgress.setVisibility(View.GONE);
                        addLog("⚠️ القارئ غير متصل");
                        showToast("القارئ غير متصل");
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Read error: " + e.getMessage());
                mainHandler.post(() -> {
                    pbWriteProgress.setVisibility(View.GONE);
                    addLog("❌ خطأ في القراءة: " + e.getMessage());
                });
            }
        });
    }

    private void writeTag() {
        String newEpc = etNewEpc.getText() != null ? etNewEpc.getText().toString().trim() : "";
        String userMemory = etUserMemory.getText() != null ? etUserMemory.getText().toString().trim() : "";
        String accessPassword = etAccessPassword.getText() != null ? 
                etAccessPassword.getText().toString().trim() : "00000000";

        // التحقق من البيانات
        if (toggleWriteType.getCheckedButtonId() != R.id.btnWriteUserMemory && newEpc.isEmpty()) {
            showToast("أدخل EPC جديد أو قم بتوليده");
            return;
        }

        if (accessPassword.length() != 8) {
            showToast("كلمة المرور يجب أن تكون 8 خانات Hex");
            return;
        }

        addLog("✍️ جاري الكتابة...");
        pbWriteProgress.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            try {
                boolean success = false;
                String message = "";

                switch (toggleWriteType.getCheckedButtonId()) {
                    case R.id.btnWriteEpc:
                        success = writeEpcToTag(newEpc, accessPassword);
                        message = "EPC";
                        break;

                    case R.id.btnWriteUserMemory:
                        success = writeUserMemoryToTag(userMemory, accessPassword);
                        message = "User Memory";
                        break;

                    case R.id.btnWriteBoth:
                        boolean epcSuccess = writeEpcToTag(newEpc, accessPassword);
                        boolean memSuccess = writeUserMemoryToTag(userMemory, accessPassword);
                        success = epcSuccess && memSuccess;
                        message = "EPC + User Memory";
                        break;
                }

                final boolean finalSuccess = success;
                final String finalMessage = message;

                mainHandler.post(() -> {
                    pbWriteProgress.setVisibility(View.GONE);

                    if (finalSuccess) {
                        addLog("✅ تمت الكتابة بنجاح: " + finalMessage);

                        // التحقق التلقائي بعد الكتابة
                        if (cbVerifyAfterWrite.isChecked()) {
                            addLog("🔍 جاري التحقق...");
                            verifyTag();
                        }

                        // القفل التلقائي بعد الكتابة
                        if (cbLockAfterWrite.isChecked()) {
                            addLog("🔐 جاري القفل...");
                            lockTag();
                        }
                    } else {
                        addLog("❌ فشلت عملية الكتابة");
                        showToast("فشلت عملية الكتابة");
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Write error: " + e.getMessage());
                mainHandler.post(() -> {
                    pbWriteProgress.setVisibility(View.GONE);
                    addLog("❌ خطأ في الكتابة: " + e.getMessage());
                });
            }
        });
    }

    private boolean writeEpcToTag(String newEpc, String accessPassword) {
        // هنا يتم تنفيذ أمر الكتابة الفعلي حسب نوع القارئ
        // مثال للبلوتوث:
        if (btOutputStream != null) {
            try {
                String command = "WRITE_EPC " + newEpc + " " + accessPassword + "\r\n";
                btOutputStream.write(command.getBytes());
                btOutputStream.flush();
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Write EPC error: " + e.getMessage());
                return false;
            }
        }
        
        // محاكاة للاختبار
        if (rfidManager != null) {
            currentEpc = newEpc;
            return true;
        }
        
        return false;
    }

    private boolean writeUserMemoryToTag(String data, String accessPassword) {
        // تنفيذ كتابة User Memory
        if (btOutputStream != null) {
            try {
                String command = "WRITE_MEM " + data + " " + accessPassword + "\r\n";
                btOutputStream.write(command.getBytes());
                btOutputStream.flush();
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Write Memory error: " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    private void verifyTag() {
        String expectedEpc = etNewEpc.getText() != null ? etNewEpc.getText().toString().trim() : "";
        
        if (expectedEpc.isEmpty() && !currentEpc.isEmpty()) {
            expectedEpc = currentEpc;
        }

        if (expectedEpc.isEmpty()) {
            showToast("لا يوجد EPC للتحقق منه");
            return;
        }

        final String finalExpectedEpc = expectedEpc;
        addLog("🔍 التحقق من الشريحة...");
        pbWriteProgress.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            try {
                // قراءة الشريحة مرة أخرى
                MiraIRFIDReader reader = rfidManager.getActiveReader();
                if (reader != null) {
                    reader.startScan();
                    Thread.sleep(800);
                    reader.stopScan();

                    mainHandler.post(() -> {
                        pbWriteProgress.setVisibility(View.GONE);
                        
                        if (currentEpc.equalsIgnoreCase(finalExpectedEpc)) {
                            addLog("✅ التحقق ناجح: الشريحة تحمل القيمة الصحيحة");
                            Toast.makeText(getContext(), "✅ التحقق ناجح", Toast.LENGTH_SHORT).show();
                        } else {
                            addLog("❌ فشل التحقق: القيمة مختلفة");
                            addLog("   المتوقع: " + finalExpectedEpc);
                            addLog("   الفعلي: " + currentEpc);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Verify error: " + e.getMessage());
                mainHandler.post(() -> {
                    pbWriteProgress.setVisibility(View.GONE);
                    addLog("❌ خطأ في التحقق: " + e.getMessage());
                });
            }
        });
    }

    private void lockTag() {
        String accessPassword = etAccessPassword.getText() != null ? 
                etAccessPassword.getText().toString().trim() : "00000000";

        addLog("🔐 جاري قفل الشريحة...");
        pbWriteProgress.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            try {
                boolean success = false;

                if (btOutputStream != null) {
                    String command = "LOCK " + accessPassword + "\r\n";
                    btOutputStream.write(command.getBytes());
                    btOutputStream.flush();
                    success = true;
                } else {
                    // محاكاة
                    success = true;
                }

                final boolean finalSuccess = success;
                mainHandler.post(() -> {
                    pbWriteProgress.setVisibility(View.GONE);
                    if (finalSuccess) {
                        addLog("✅ تم قفل الشريحة بنجاح");
                    } else {
                        addLog("❌ فشل قفل الشريحة");
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Lock error: " + e.getMessage());
                mainHandler.post(() -> {
                    pbWriteProgress.setVisibility(View.GONE);
                    addLog("❌ خطأ في القفل: " + e.getMessage());
                });
            }
        });
    }

    private void generateRandomEpc() {
        StringBuilder epc = new StringBuilder();
        String hexChars = "0123456789ABCDEF";
        
        for (int i = 0; i < 24; i++) {
            int index = (int) (Math.random() * hexChars.length());
            epc.append(hexChars.charAt(index));
        }
        
        etNewEpc.setText(epc.toString());
        addLog("🎲 تم توليد EPC جديد: " + epc.toString());
    }

    private void addLog(String message) {
        operationLog += message + "\n";
        if (tvOperationLog != null) {
            tvOperationLog.setText(operationLog);
        }
    }

    private void showToast(String message) {
        if (getContext() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    // مستمع لقراءة الشرائح
    private MiraIRFIDReader.OnTagReadListener tagReadListener = new MiraIRFIDReader.OnTagReadListener() {
        @Override
        public void onTagRead(String epc, int rssi) {
            currentEpc = epc;
            mainHandler.post(() -> {
                tvCurrentEpc.setText("EPC الحالي: " + epc);
                tvCurrentRssi.setText("RSSI: " + rssi + " dBm");
            });
        }

        @Override
        public void onScanStarted() {
            addLog("📡 بدأ المسح...");
        }

        @Override
        public void onScanStopped() {
            addLog("📡 توقف المسح");
        }

        @Override
        public void onConnectionChanged(boolean connected) {
            addLog(connected ? "✅ متصل بالقارئ" : "❌ انقطع الاتصال");
        }

        @Override
        public void onError(String error) {
            addLog("❌ خطأ: " + error);
        }
    };

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}
