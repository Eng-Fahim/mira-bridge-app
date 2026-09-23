package com.example.uhf.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.scanner.ManualInputHandler;
import com.example.uhf.scanner.RfidScanHandler;
import com.example.uhf.scanner.ScanMethod;
import com.example.uhf.scanner.ScanResult;
import com.example.uhf.scanner.UnifiedScanManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ✅ حوار عرض تقدم المسح
 * - يعرض: متوقع / ممسوح
 * - يسمح بالتبديل بين الطرق الثلاث
 * - يكتمل عند مسح كل القطع
 */
public class ScanProgressDialog extends Dialog {
    
    private static final String TAG = "ScanProgressDialog";
    
    // ==================== Callbacks ====================
    
    public interface OnScanCompletedListener {
        void onCompleted(int scannedCount);
        void onCancelled();
        void onError(String message);
    }
    
    // ==================== Views ====================
    
    private TextView tvTitle;
    private TextView tvPhaseLabel;
    private TextView tvCounter;
    private TextView tvStatusMessage;
    private RecyclerView rvScannedItems;
    private LinearLayout layoutMethodButtons;
    private Button btnRfid, btnGs1, btnManual;
    private Button btnComplete, btnCancel;
    
    // Manual input (inline)
    private LinearLayout layoutManualInput;
    private EditText etManualInput;
    private Button btnSubmitManual;
    private Button btnCloseManual;
    
    // ==================== State ====================
    
    private final Context context;
    private final boolean isArabic;
    private final int transferId;
    private final String transferNumber;
    private final String phase; // "dispatch" or "receive"
    private final OnScanCompletedListener listener;
    
    private UnifiedScanManager scanManager;
    private RfidScanHandler rfidHandler;
    private ManualInputHandler manualHandler;
    
    private ScanMethod activeMethod = ScanMethod.RFID;
    private int expectedCount = 0;
    private int scannedCount = 0;
    
    private final List<ScanResult> scannedItems = new ArrayList<>();
    private ScanItemsAdapter adapter;
    
    // ==================== Constructor ====================
    
    public ScanProgressDialog(@NonNull Context context,
                              boolean isArabic,
                              int transferId,
                              String transferNumber,
                              String phase,
                              OnScanCompletedListener listener) {
        super(context);
        this.context = context;
        this.isArabic = isArabic;
        this.transferId = transferId;
        this.transferNumber = transferNumber;
        this.phase = phase;
        this.listener = listener;
    }
    
    // ==================== Lifecycle ====================
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_scan_progress);
        
        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }
        
        setCancelable(false);
        setCanceledOnTouchOutside(false);
        
        initManagers();
        initViews();
        setupRecyclerView();
        setupListeners();
        
        // ✅ ابدأ الجلسة
        startSession();
    }
    
    @Override
    public void dismiss() {
        try {
            if (rfidHandler != null && rfidHandler.isScanning()) {
                rfidHandler.stopScanning();
            }
        } catch (Exception ignored) {}
        
        super.dismiss();
    }
    
    // ==================== Init ====================
    
    private void initManagers() {
        scanManager = UnifiedScanManager.getInstance(context);
        rfidHandler = new RfidScanHandler(context);
        manualHandler = new ManualInputHandler(context);
    }
    
    private void initViews() {
        tvTitle = findViewById(R.id.tvScanProgressTitle);
        tvPhaseLabel = findViewById(R.id.tvScanPhaseLabel);
        tvCounter = findViewById(R.id.tvScanCounter);
        tvStatusMessage = findViewById(R.id.tvScanStatusMessage);
        rvScannedItems = findViewById(R.id.rvScannedItems);
        layoutMethodButtons = findViewById(R.id.layoutMethodButtons);
        btnRfid = findViewById(R.id.btnMethodRfid);
        btnGs1 = findViewById(R.id.btnMethodGs1);
        btnManual = findViewById(R.id.btnMethodManual);
        btnComplete = findViewById(R.id.btnCompleteScan);
        btnCancel = findViewById(R.id.btnCancelScan);
        
        // Manual inline
        layoutManualInput = findViewById(R.id.layoutManualInput);
        etManualInput = findViewById(R.id.etManualInput);
        btnSubmitManual = findViewById(R.id.btnSubmitManual);
        btnCloseManual = findViewById(R.id.btnCloseManualInput);
        
        // ✅ النصوص
        updateLabels();
    }
    
    private void updateLabels() {
        if (isArabic) {
            tvTitle.setText(phase.equals("dispatch") 
                ? "📤 إرسال الشحنة" 
                : "📥 استلام الشحنة");
            tvPhaseLabel.setText(transferNumber);
            btnRfid.setText("📡 RFID");
            btnGs1.setText("📷 كاميرا");
            btnManual.setText("⌨️ يدوي");
            btnComplete.setText("✅ إتمام");
            btnCancel.setText("إلغاء");
            etManualInput.setHint("EPC / Serial / item_id");
            btnSubmitManual.setText("إضافة");
        } else {
            tvTitle.setText(phase.equals("dispatch") 
                ? "📤 Dispatch Shipment" 
                : "📥 Receive Shipment");
            tvPhaseLabel.setText(transferNumber);
            btnRfid.setText("📡 RFID");
            btnGs1.setText("📷 Camera");
            btnManual.setText("⌨️ Manual");
            btnComplete.setText("✅ Complete");
            btnCancel.setText("Cancel");
            etManualInput.setHint("EPC / Serial / item_id");
            btnSubmitManual.setText("Add");
        }
        
        updateCounter();
    }
    
    private void updateCounter() {
        String text = String.format(Locale.US, "%d / %d", scannedCount, expectedCount);
        tvCounter.setText(text);
        
        // ✅ لون حسب الحالة
        if (expectedCount > 0 && scannedCount >= expectedCount) {
            tvCounter.setTextColor(Color.parseColor("#4CAF50"));
            btnComplete.setEnabled(true);
            btnComplete.setAlpha(1.0f);
        } else {
            tvCounter.setTextColor(Color.parseColor("#1976D2"));
            btnComplete.setEnabled(false);
            btnComplete.setAlpha(0.5f);
        }
    }
    
    private void setupRecyclerView() {
        adapter = new ScanItemsAdapter(scannedItems, isArabic);
        rvScannedItems.setLayoutManager(new LinearLayoutManager(context));
        rvScannedItems.setAdapter(adapter);
    }
    
    // ==================== Session ====================
    
    private void startSession() {
        Log.d(TAG, "Starting scan session: transfer=" + transferId + " phase=" + phase);
        
        scanManager.startSession(
            transferId,
            phase,
            ScanMethod.RFID,
            new UnifiedScanManager.ScanCallback() {
                @Override
                public void onScanResult(ScanResult result) {
                    handleScanResult(result);
                }
                
                @Override
                public void onSessionStarted(int expected) {
                    expectedCount = expected;
                    scannedCount = 0;
                    scannedItems.clear();
                    adapter.notifyDataSetChanged();
                    updateCounter();
                    
                    Log.d(TAG, "✅ Session started. Expected: " + expectedCount);
                }
                
                @Override
                public void onSessionError(String error) {
                    Log.e(TAG, "Session error: " + error);
                    tvStatusMessage.setText("❌ " + error);
                    tvStatusMessage.setVisibility(View.VISIBLE);
                    
                    if (listener != null) {
                        listener.onError(error);
                    }
                }
                
                @Override
                public void onSessionCompleted(int count) {
                    Log.d(TAG, "✅ Session completed. Scanned: " + count);
                    
                    if (listener != null) {
                        listener.onCompleted(count);
                    }
                }
                
                @Override
                public void onSessionCancelled() {
                    if (listener != null) {
                        listener.onCancelled();
                    }
                }
            }
        );
    }
    
    private void handleScanResult(ScanResult result) {
        if (result == null) return;
        
        switch (result.getStatus()) {
            case SCANNED:
                addScannedItem(result);
                scannedCount = result.getScannedCount();
                expectedCount = result.getExpectedCount();
                updateCounter();
                showStatusMessage(result.getMessage(), "#4CAF50");
                
                // ✅ إذا اكتمل الكل
                if (result.isAllScanned()) {
                    showStatusMessage(
                        isArabic ? "✅ كل القطع ممسوحة — جاهز للإتمام" 
                                 : "✅ All scanned — ready to complete",
                        "#4CAF50"
                    );
                }
                break;
                
            case ALREADY_SCANNED:
                showStatusMessage(
                    isArabic ? "⚠️ ممسوحة مسبقاً" : "⚠️ Already scanned",
                    "#FF9800"
                );
                break;
                
            case NOT_FOUND:
                showStatusMessage(
                    isArabic ? "❌ القطعة غير مسجلة في النظام" 
                             : "❌ Item not registered",
                    "#F44336"
                );
                break;
                
            case EXTRA:
                showStatusMessage(
                    isArabic ? "⚠️ القطعة غير مدرجة في هذه الشحنة" 
                             : "⚠️ Item not in this transfer",
                    "#FF9800"
                );
                break;
                
            case ERROR:
                showStatusMessage(
                    "❌ " + result.getMessage(),
                    "#F44336"
                );
                break;
        }
    }
    
    private void addScannedItem(ScanResult result) {
        // ✅ أضف للقائمة إن لم يكن موجوداً
        for (ScanResult r : scannedItems) {
            if (r.getItemId().equals(result.getItemId())) {
                return; // موجود
            }
        }
        
        scannedItems.add(result);
        adapter.notifyItemInserted(scannedItems.size() - 1);
        rvScannedItems.scrollToPosition(scannedItems.size() - 1);
    }
    
    private void showStatusMessage(String msg, String colorHex) {
        if (tvStatusMessage == null) return;
        
        tvStatusMessage.setText(msg);
        try {
            tvStatusMessage.setTextColor(Color.parseColor(colorHex));
        } catch (Exception ignored) {}
        tvStatusMessage.setVisibility(View.VISIBLE);
        
        // ✅ اخفِ بعد 3 ثواني
        tvStatusMessage.postDelayed(() -> {
            if (tvStatusMessage != null) {
                tvStatusMessage.setVisibility(View.GONE);
            }
        }, 3000);
    }
    
    // ==================== Listeners ====================
    
    private void setupListeners() {
        btnRfid.setOnClickListener(v -> switchMethod(ScanMethod.RFID));
        btnGs1.setOnClickListener(v -> switchMethod(ScanMethod.GS1));
        btnManual.setOnClickListener(v -> switchMethod(ScanMethod.MANUAL));
        
        btnComplete.setOnClickListener(v -> completeSession());
        btnCancel.setOnClickListener(v -> cancelAndDismiss());
        
        // Manual input
        btnSubmitManual.setOnClickListener(v -> submitManualInput());
        btnCloseManual.setOnClickListener(v -> hideManualInput());
    }
    
    private void switchMethod(ScanMethod method) {
        if (activeMethod == method) return;
        
        Log.d(TAG, "Switching method to: " + method);
        
        // 1) أوقف RFID
        stopRfidIfActive();
        
        activeMethod = method;
        scanManager.setCurrentMethod(method);
        
        // 2) أظهر الحالة الجديدة
        switch (method) {
            case RFID:
                hideManualInput();
                startRfidScanning();
                highlightButton(btnRfid);
                break;
                
            case GS1:
                hideManualInput();
                openBarcodeCamera();
                highlightButton(btnGs1);
                break;
                
            case MANUAL:
                showManualInput();
                highlightButton(btnManual);
                break;
        }
    }
    
    private void highlightButton(Button selected) {
        Button[] buttons = {btnRfid, btnGs1, btnManual};
        for (Button b : buttons) {
            if (b == selected) {
                b.setAlpha(1.0f);
                b.setBackgroundColor(Color.parseColor("#1976D2"));
                b.setTextColor(Color.WHITE);
            } else {
                b.setAlpha(0.5f);
                b.setBackgroundColor(Color.parseColor("#EEEEEE"));
                b.setTextColor(Color.parseColor("#616161"));
            }
        }
    }
    
    private void stopRfidIfActive() {
        try {
            if (rfidHandler != null && rfidHandler.isScanning()) {
                rfidHandler.stopScanning();
            }
        } catch (Exception ignored) {}
    }
    
    // ==================== RFID ====================
    
    private void startRfidScanning() {
        try {
            rfidHandler.startScanning();
            showStatusMessage(
                isArabic ? "📡 RFID نشط — وجّه القارئ نحو القطع" 
                         : "📡 RFID active — point reader at items",
                "#1976D2"
            );
        } catch (Exception e) {
            Log.e(TAG, "RFID start error: " + e.getMessage());
        }
    }
    
    // ==================== GS1 Camera ====================
    
    private void openBarcodeCamera() {
    try {
        BarcodeCameraDialog cameraDialog = new BarcodeCameraDialog(
            activity,
            isArabic,
            new BarcodeCameraDialog.OnBarcodeScannedListener() {
                @Override
                public void onBarcodeScanned(String rawBarcode) {
                    Log.d(TAG, "📷 Barcode scanned: " + rawBarcode);
                    
                    // ✅ استخدم الحقل الموجود بدلاً من إنشاء handler جديد
                    if (barcodeHandler != null) {
                        barcodeHandler.processRawBarcode(rawBarcode);
                    } else {
                        Log.e(TAG, "barcodeHandler is null!");
                    }
                }
                
                @Override
                public void onClosed() {
                    Log.d(TAG, "Camera dialog closed");
                }
            }
        );
        
        cameraDialog.show();
        
        showStatusMessage(
            isArabic ? "📷 وجّه الكاميرا نحو الباركود" 
                     : "📷 Point camera at barcode",
            "#388E3C"
        );
        
    } catch (Exception e) {
        Log.e(TAG, "Camera open error: " + e.getMessage());
        Toast.makeText(activity,
            isArabic ? "خطأ في فتح الكاميرا" : "Camera error",
            Toast.LENGTH_SHORT).show();
    }
}
    
    // ==================== Manual Input ====================
    
    private void showManualInput() {
        layoutManualInput.setVisibility(View.VISIBLE);
        etManualInput.requestFocus();
        
        // ✅ أظهر الكيبورد
        try {
            android.view.inputmethod.InputMethodManager imm = 
                (android.view.inputmethod.InputMethodManager) 
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(etManualInput, 
                    android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        } catch (Exception ignored) {}
    }
    
    private void hideManualInput() {
        layoutManualInput.setVisibility(View.GONE);
        etManualInput.setText("");
        etManualInput.clearFocus();
        
        // ✅ اخفِ الكيبورد
        try {
            android.view.inputmethod.InputMethodManager imm = 
                (android.view.inputmethod.InputMethodManager) 
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(
                    etManualInput.getWindowToken(), 0);
            }
        } catch (Exception ignored) {}
    }
    
    private void submitManualInput() {
        String input = etManualInput.getText().toString().trim();
        
        if (!manualHandler.isValid(input)) {
            String error = manualHandler.getValidationError(input);
            etManualInput.setError(error);
            return;
        }
        
        Log.d(TAG, "⌨️ Manual input: " + input);
        
        // ✅ أرسل للمسح
        manualHandler.submit(input);
        
        // ✅ امسح الحقل
        etManualInput.setText("");
        etManualInput.requestFocus();
    }
    
    // ==================== Complete ====================
    
    private void completeSession() {
        if (scannedCount < expectedCount) {
            showStatusMessage(
                isArabic 
                    ? "⚠️ لم تُمسح جميع القطع (" + scannedCount + "/" + expectedCount + ")"
                    : "⚠️ Not all scanned (" + scannedCount + "/" + expectedCount + ")",
                "#FF9800"
            );
            return;
        }
        
        showStatusMessage(
            isArabic ? "⏳ جاري الإتمام..." : "⏳ Completing...",
            "#1976D2"
        );
        
        stopRfidIfActive();
        
        // ✅ استدعِ الإتمام
        scanManager.completeSession();
    }
    
    private void cancelAndDismiss() {
        // ✅ اسأل قبل الإلغاء
        new androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(isArabic ? "إلغاء المسح؟" : "Cancel scan?")
            .setMessage(isArabic 
                ? "سيتم إلغاء جلسة المسح الحالية"
                : "Current scan session will be cancelled")
            .setPositiveButton(
                isArabic ? "نعم" : "Yes",
                (d, w) -> {
                    stopRfidIfActive();
                    scanManager.cancelSession();
                    dismiss();
                }
            )
            .setNegativeButton(isArabic ? "لا" : "No", null)
            .show();
    }
    
    // ==================== Adapter ====================
    
    /**
     * ✅ Adapter لعرض القطع الممسوحة
     */
    private static class ScanItemsAdapter extends RecyclerView.Adapter<ScanItemsAdapter.VH> {
        
        private final List<ScanResult> items;
        private final boolean isArabic;
        
        ScanItemsAdapter(List<ScanResult> items, boolean isArabic) {
            this.items = items;
            this.isArabic = isArabic;
        }
        
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_scan_row, parent, false);
            return new VH(v);
        }
        
        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            ScanResult item = items.get(position);
            
            h.tvIndex.setText(String.valueOf(position + 1));
            h.tvTitle.setText(item.getTitle() != null && !item.getTitle().isEmpty()
                ? item.getTitle() 
                : "قطعة");
            h.tvSerial.setText(item.getSerial());
            
            // ✅ Karat + Weight
            StringBuilder sb = new StringBuilder();
            if (item.getGoldKarat() != null && !item.getGoldKarat().isEmpty()) {
                sb.append(item.getGoldKarat());
            }
            if (item.getWeight() > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(String.format(Locale.US, "%.2f g", item.getWeight()));
            }
            h.tvDetails.setText(sb.toString());
            
            // ✅ أيقونة الطريقة
            String methodIcon;
            switch (item.getMethod()) {
                case "rfid": methodIcon = "📡"; break;
                case "gs1": methodIcon = "📷"; break;
                case "manual": methodIcon = "⌨️"; break;
                default: methodIcon = "✅"; break;
            }
            h.tvMethodIcon.setText(methodIcon);
            
            // ✅ لون البطاقة
            int color;
            switch (item.getStatus()) {
                case SCANNED:
                    color = Color.parseColor("#E8F5E9"); // أخضر فاتح
                    break;
                case ALREADY_SCANNED:
                case EXTRA:
                    color = Color.parseColor("#FFF3E0"); // برتقالي فاتح
                    break;
                case NOT_FOUND:
                case ERROR:
                    color = Color.parseColor("#FFEBEE"); // أحمر فاتح
                    break;
                default:
                    color = Color.parseColor("#F5F5F5");
                    break;
            }
            h.itemView.setBackgroundColor(color);
        }
        
        @Override
        public int getItemCount() { return items.size(); }
        
        static class VH extends RecyclerView.ViewHolder {
            TextView tvIndex, tvTitle, tvSerial, tvDetails, tvMethodIcon;
            
            VH(View v) {
                super(v);
                tvIndex = v.findViewById(R.id.tvScanRowIndex);
                tvTitle = v.findViewById(R.id.tvScanRowTitle);
                tvSerial = v.findViewById(R.id.tvScanRowSerial);
                tvDetails = v.findViewById(R.id.tvScanRowDetails);
                tvMethodIcon = v.findViewById(R.id.tvScanRowMethod);
            }
        }
    }
}
