package com.example.uhf.fragment;

import com.example.uhf.tools.SessionManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.dialog.POSCheckoutDialog;
import com.example.uhf.engine.SmartScaleConnector;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.UIHelper;
import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.RFIDManager;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraPosFragment extends MiraBaseFragment {

    private static final String TAG = "MiraPosFragment";
    private static final String PENDING_SALES_PREFS = "pos_pending_sales";
    private boolean isCheckoutDialogShowing = false; 
    private AlertDialog searchLoadingDialog;

    
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private MiraPriceManager priceManager;
    private MiraBranchesManager branchesManager;
    private PermissionManager permissionManager;

    private RFIDManager rfidManager;
    private boolean isRFIDScanActive = false;
    private final Set<String> pendingEpcs = new HashSet<>();
    
    private final Map<String, Long> lastScannedTimes = new HashMap<>();

    private TextView tvScaleStatus, tvItemCount, tvTotalAmount, tvCartStatus;
    private TextInputLayout tilManualEpc;
    private EditText etManualEpc;
    private Button btnAddManual, btnCheckout, btnClearCart;
    private Button btnOpenScanner, btnPosRFIDMode;
    private RecyclerView rvCartItems;
    private View layoutCartEmptyState;

    private boolean isRFIDModeActive = false;
    private boolean isArabic;

    public static class CartItem {
        public String epc, name, karat, status, serialNo, gtin, location;
        public double weight, scaleWeight, price;
        public boolean isAvailable;
        public int itemId;

        public CartItem(String epc, String name, String karat, double weight,
                        double scaleWeight, double price, boolean isAvailable, String status) {
            this.epc = epc;
            this.name = name;
            this.karat = karat;
            this.weight = weight;
            this.scaleWeight = scaleWeight;
            this.price = price;
            this.isAvailable = isAvailable;
            this.status = status;
        }
    }

    private final List<CartItem> cartList = new ArrayList<>();
    private final Set<String> scannedEpcs = new HashSet<>();
    private CartAdapter cartAdapter;
    private SmartScaleConnector scaleConnector;
    private double liveScaleWeight = 0.0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_pos, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Context ctx = requireContext();
        branchesManager = MiraBranchesManager.getInstance(ctx);
        priceManager = MiraPriceManager.getInstance(ctx);
        permissionManager = PermissionManager.getInstance(ctx);
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        rfidManager = RFIDManager.getInstance(ctx);
        rfidManager.setListener(new MiraIRFIDReader.OnTagReadListener() {
            @Override
            public void onTagRead(String epc, int rssi) {
                if (isRFIDScanActive) {
                    onRingScanned(epc);
                }
            }
            @Override
            public void onScanStarted() {
                Log.i(TAG, "RFID Scan Started");
            }
            @Override
            public void onScanStopped() {
                Log.i(TAG, "RFID Scan Stopped");
            }
            @Override
            public void onConnectionChanged(boolean connected) {
                safeRun(() -> {
                    if (btnPosRFIDMode != null && isRFIDModeActive) {
                        btnPosRFIDMode.setAlpha(connected ? 1.0f : 0.5f);
                    }
                });
            }
            @Override
            public void onError(String error) {
                Log.e(TAG, "RFID Error: " + error);
            }
        });

        bindViews(view);
        setupRecyclerView();
        setupListeners();
        applyPermissions();
        initSmartScale();
        checkPendingSales();
        updateCartSummary();
    }

    private void applyPermissions() {
        boolean canUsePos = permissionManager.canView("pos") || permissionManager.isPosOperator() || permissionManager.isManager();
        
        if (btnCheckout != null) btnCheckout.setEnabled(false);
        if (btnAddManual != null) btnAddManual.setEnabled(canUsePos);
        if (btnOpenScanner != null) btnOpenScanner.setEnabled(canUsePos);
        if (btnPosRFIDMode != null) btnPosRFIDMode.setEnabled(canUsePos);
        if (etManualEpc != null) etManualEpc.setEnabled(canUsePos);
        
        if (!canUsePos) {
            if (tvCartStatus != null) {
                tvCartStatus.setText(isArabic ? "⛔ غير مصرح لك باستخدام نقطة البيع" : "⛔ Access Denied");
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setActivePOSFragment(this);
        }

        if (scaleConnector != null) {
            scaleConnector.connect();
        }

        if (tvCartStatus != null && cartList.isEmpty()) {
            tvCartStatus.setText(isArabic ? getString(R.string.pos_ready_scan) : "Ready to scan items...");
        }

        syncPendingSales();
    }

    @Override
public void onPause() {
    super.onPause();
    stopRFIDScan();
    isCheckoutDialogShowing = false; // ✅ إعادة تعيين عند الخروج من الشاشة
    
    if (scaleConnector != null) {
        scaleConnector.disconnect();
    }
    
    if (getActivity() instanceof UHFMainActivity) {
        ((UHFMainActivity) getActivity()).setActivePOSFragment(null);
    }
}


// ==================== 6. تنظيف في onDestroyView ====================

@Override
public void onDestroyView() {
    super.onDestroyView();
    stopRFIDScan();
    isCheckoutDialogShowing = false;
    dismissSearchLoadingDialog(); // ✅ إغلاق شريط التحميل
    if (rfidManager != null) {
        rfidManager.setListener(null);
    }
}

@Override
public void onDestroy() {
    super.onDestroy();
    stopRFIDScan();
    if (scaleConnector != null) scaleConnector.disconnect();
    if (rfidManager != null) rfidManager.release();
}
    private int getActiveBranchId() {
        try {
            return branchesManager != null ? branchesManager.getActiveBranchId() : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    private int getKaratNumber(String karat) {
        if (karat == null) return 21;
        switch (karat.toUpperCase()) {
            case "24K": return 24;
            case "22K": return 22;
            case "21K": return 21;
            case "18K": return 18;
            case "14K": return 14;
            default: return 21;
        }
    }

    private void safeRun(Runnable r) {
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (isAdded()) r.run();
            });
        }
    }

    private void showToast(String m) {
        if (getContext() != null && isAdded()) {
            Toast.makeText(getContext(), m, Toast.LENGTH_SHORT).show();
        }
    }

    private void playAlertSound(int id) {
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).playSound(id);
        }
    }

    private void bindViews(View view) {
        tvScaleStatus = view.findViewById(R.id.tvPosScaleStatus);
        tvItemCount = view.findViewById(R.id.tvPosItemCount);
        tvTotalAmount = view.findViewById(R.id.tvPosTotalAmount);
        tvCartStatus = view.findViewById(R.id.tvPosCartStatus);
        tilManualEpc = view.findViewById(R.id.tilPosManualEpc);
        etManualEpc = view.findViewById(R.id.etPosManualEpc);
        btnAddManual = view.findViewById(R.id.btnPosAddManual);
        btnCheckout = view.findViewById(R.id.btnPosCheckout);
        btnClearCart = view.findViewById(R.id.btnPosClearCart);
        btnOpenScanner = view.findViewById(R.id.btnOpenScanner);
        btnPosRFIDMode = view.findViewById(R.id.btnPosRFIDMode);
        rvCartItems = view.findViewById(R.id.rvPosCartItems);
        layoutCartEmptyState = view.findViewById(R.id.layoutCartEmptyState);
        
        applyLanguage();
    }

    private void applyLanguage() {
        if (etManualEpc != null) etManualEpc.setHint(null);

        if (isArabic) {
            if (tilManualEpc != null) tilManualEpc.setHint(getString(R.string.pos_manual_hint));
            if (btnCheckout != null) btnCheckout.setText(R.string.pos_checkout);
            if (btnClearCart != null) btnClearCart.setText(R.string.pos_clear);
            if (btnOpenScanner != null) btnOpenScanner.setText(R.string.pos_scan_gs1);
            if (btnPosRFIDMode != null && !isRFIDModeActive) btnPosRFIDMode.setText(R.string.pos_scan_rfid);
            if (btnAddManual != null) btnAddManual.setText(R.string.pos_add);
        } else {
            if (tilManualEpc != null) tilManualEpc.setHint("Enter EPC or Barcode...");
            if (btnCheckout != null) btnCheckout.setText("Checkout");
            if (btnClearCart != null) btnClearCart.setText("Clear");
            if (btnOpenScanner != null) btnOpenScanner.setText("📷 Scan GS1");
            if (btnPosRFIDMode != null && !isRFIDModeActive) btnPosRFIDMode.setText("📡 Scan RFID");
            if (btnAddManual != null) btnAddManual.setText("Add");
        }
    }

    private void setupRecyclerView() {
        if (rvCartItems != null) {
            rvCartItems.setLayoutManager(new LinearLayoutManager(getContext()));
            cartAdapter = new CartAdapter(cartList, this::removeItemFromCart, isArabic, priceManager);
            rvCartItems.setAdapter(cartAdapter);
        }
    }

    private void setupListeners() {
        if (etManualEpc != null) {
            etManualEpc.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    submitManualEpc();
                    return true;
                }
                return false;
            });
        }

        if (btnAddManual != null) btnAddManual.setOnClickListener(v -> {
            if (permissionManager.canView("pos") || permissionManager.isPosOperator() || permissionManager.isManager()) {
                submitManualEpc();
            } else {
                showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            }
        });
        
        if (btnClearCart != null) btnClearCart.setOnClickListener(v -> {
            UIHelper.hideKeyboard(getActivity());
            clearCart();
        });
        
        if (btnCheckout != null) btnCheckout.setOnClickListener(v -> {
            if (permissionManager.canView("pos") || permissionManager.isPosOperator() || permissionManager.isManager()) {
                UIHelper.hideKeyboard(getActivity());
                processCheckoutAndStockUpdate();
            } else {
                showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            }
        });
        
        if (btnOpenScanner != null) btnOpenScanner.setOnClickListener(v -> {
            if (permissionManager.canView("pos") || permissionManager.isPosOperator() || permissionManager.isManager()) {
                UIHelper.hideKeyboard(getActivity());
                openBarcodeScanner();
            } else {
                showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            }
        });
        
        if (btnPosRFIDMode != null) btnPosRFIDMode.setOnClickListener(v -> {
            if (permissionManager.canView("pos") || permissionManager.isPosOperator() || permissionManager.isManager()) {
                UIHelper.hideKeyboard(getActivity());
                toggleRFIDMode();
            } else {
                showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            }
        });
    }

    private void submitManualEpc() {
        if (!(permissionManager.canView("pos") || permissionManager.isPosOperator() || permissionManager.isManager())) {
            showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            return;
        }
        
        UIHelper.hideKeyboard(getActivity());
        String epc = etManualEpc != null ? etManualEpc.getText().toString().trim() : "";
        if (!epc.isEmpty()) {
            if (scannedEpcs.contains(epc)) {
                showToast(isArabic ? "⚠️ هذه القطعة مضافة مسبقاً في السلة!" : "⚠️ Item already in cart!");
                playAlertSound(2);
                return;
            }
            processRingEpc(epc, "manual");
            if (etManualEpc != null) etManualEpc.setText("");
        } else {
            showToast(isArabic ? "أدخل EPC أو باركود" : "Enter EPC or barcode");
        }
    }

    private void initSmartScale() {
        scaleConnector = new SmartScaleConnector();
        scaleConnector.setListener(new SmartScaleConnector.ScaleListener() {
            @Override
            public void onWeightReceived(double w) {
                liveScaleWeight = w;
                safeRun(() -> {
                    if (tvScaleStatus != null) {
                        tvScaleStatus.setText(String.format(Locale.US, "⚖️ %.2fg", w));
                    }
                });
            }
            @Override
            public void onScaleConnected(String n) {
                safeRun(() -> {
                    if (tvScaleStatus != null) tvScaleStatus.setText("⚖️ " + n);
                });
            }
            @Override
            public void onScaleDisconnected() {
                liveScaleWeight = 0.0;
                safeRun(() -> {
                    if (tvScaleStatus != null) tvScaleStatus.setText(isArabic ? "⚖️ غير متصل" : "⚖️ Offline");
                });
            }
            @Override
            public void onScaleError(String e) {
                Log.e(TAG, "Scale Error: " + e);
            }
        });
    }

    private void openBarcodeScanner() {
        if (!isAdded()) return;
        MiraBarcodeScannerFragment sf = new MiraBarcodeScannerFragment();
        sf.setOnBarcodeScannedListener((data, format) -> {
            onBarcodeScanned(data);
            if (getParentFragmentManager().getBackStackEntryCount() > 0) {
                getParentFragmentManager().popBackStack();
            }
        });
        View view = getView();
        int cid = (view != null && view.getParent() instanceof View) ?
                ((View) view.getParent()).getId() : android.R.id.content;
        getParentFragmentManager().beginTransaction()
                .replace(cid, sf)
                .addToBackStack("barcode_scanner")
                .commit();
    }

    private void toggleRFIDMode() {
        isRFIDModeActive = !isRFIDModeActive;
        if (isRFIDModeActive) {
            startRFIDScan();
            if (btnPosRFIDMode != null) btnPosRFIDMode.setText(isArabic ? "📡 RFID نشط" : "📡 RFID Active");
            if (tvCartStatus != null) tvCartStatus.setText(isArabic ? "📡 جاهز لمسح RFID..." : "📡 Ready for RFID...");
        } else {
            stopRFIDScan();
            if (btnPosRFIDMode != null) btnPosRFIDMode.setText(isArabic ? getString(R.string.pos_scan_rfid) : "📡 Scan RFID");
            if (tvCartStatus != null) tvCartStatus.setText(isArabic ? getString(R.string.pos_ready_scan) : "Ready to scan...");
        }
    }

    private void startRFIDScan() {
        isRFIDScanActive = true;
        rfidManager.startScan();
    }

    private void stopRFIDScan() {
        isRFIDScanActive = false;
        rfidManager.stopScan();
    }

    public void onRingScanned(String epc) {
        long currentTime = System.currentTimeMillis();
        Long lastTime = lastScannedTimes.get(epc);
        if (lastTime != null && (currentTime - lastTime < 1500)) return;
        lastScannedTimes.put(epc, currentTime);

        if (scannedEpcs.contains(epc)) {
            safeRun(() -> {
                showToast(isArabic ? "⚠️ هذه القطعة مضافة مسبقاً!" : "⚠️ Item already in cart!");
                playAlertSound(2);
            });
            return;
        }
        processRingEpc(epc, "rfid");
    }

    public void onBarcodeScanned(String data) {
        long currentTime = System.currentTimeMillis();
        Long lastTime = lastScannedTimes.get(data);
        if (lastTime != null && (currentTime - lastTime < 1500)) return;
        lastScannedTimes.put(data, currentTime);

        if (scannedEpcs.contains(data)) {
            safeRun(() -> {
                showToast(isArabic ? "⚠️ هذه القطعة مضافة مسبقاً!" : "⚠️ Item already in cart!");
                playAlertSound(2);
            });
            return;
        }
        processRingEpc(data, "barcode");
    }

    private void processRingEpc(final String code, final String source) {
    synchronized (pendingEpcs) {
        if (pendingEpcs.contains(code)) return;
        pendingEpcs.add(code);
    }
    
    playAlertSound(1);
    
    // ✅ إظهار شريط تحميل احترافي أثناء البحث
    safeRun(() -> {
        showSearchLoadingDialog(code);
        if (tvCartStatus != null) {
            tvCartStatus.setText(isArabic ? "🔍 جاري البحث عن القطعة..." : "🔍 Searching for item...");
        }
    });
    
    new Thread(() -> {
        try {
            Context context = getContext();
            if (context == null) return;
            MiraApiClient client = MiraApiClient.getInstance(context);
            JSONObject extraParams = new JSONObject();
            extraParams.put("source", source);
            extraParams.put("branch_id", getActiveBranchId());
            if (liveScaleWeight > 0) extraParams.put("scale_weight", liveScaleWeight);
            
            // ✅ محاكاة وقت انتظار قصير لتحسين UX (اختياري)
            Thread.sleep(300);
            
            MiraApiClient.ApiResponse response = client.authorize(code, "pos_sale", extraParams);
            
            safeRun(() -> dismissSearchLoadingDialog());
            
            if (response != null && response.isSuccess && response.data != null
                    && response.data.optJSONObject("item") != null) {
                parseItemResponse(code, response.data);
            } else {
                handleItemNotFound(code);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error: " + e.getMessage());
            safeRun(() -> dismissSearchLoadingDialog());
            handleItemNotFound(code);
        } finally {
            synchronized (pendingEpcs) {
                pendingEpcs.remove(code);
            }
        }
    }).start();
}

// ==================== 2. شريط تحميل البحث الاحترافي ====================

private void showSearchLoadingDialog(String code) {
    Context ctx = getContext();
    if (ctx == null || !isAdded()) return;
    
    dismissSearchLoadingDialog();
    
    AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
    
    MaterialCardView cardView = new MaterialCardView(ctx);
    cardView.setRadius(24);
    cardView.setCardElevation(10);
    cardView.setStrokeWidth(0);
    
    LinearLayout layout = new LinearLayout(ctx);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(50, 40, 50, 40);
    layout.setGravity(android.view.Gravity.CENTER);
    
    // أيقونة بحث متحركة
    TextView tvIcon = new TextView(ctx);
    tvIcon.setText("🔍");
    tvIcon.setTextSize(36);
    tvIcon.setGravity(android.view.Gravity.CENTER);
    layout.addView(tvIcon);
    
    // شريط تقدم
    ProgressBar progressBar = new ProgressBar(ctx);
    progressBar.setIndeterminate(true);
    LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(100, 100);
    pbParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
    pbParams.topMargin = 16;
    progressBar.setLayoutParams(pbParams);
    layout.addView(progressBar);
    
    // نص التحميل
    TextView tvLoading = new TextView(ctx);
    tvLoading.setText(isArabic ? "جاري البحث عن القطعة..." : "Searching for item...");
    tvLoading.setTextSize(16);
    tvLoading.setTypeface(null, android.graphics.Typeface.BOLD);
    tvLoading.setGravity(android.view.Gravity.CENTER);
    tvLoading.setPadding(0, 16, 0, 8);
    tvLoading.setTextColor(0xFF1A1A2E);
    layout.addView(tvLoading);
    
    // عرض الكود
    TextView tvCode = new TextView(ctx);
    tvCode.setText(code);
    tvCode.setTextSize(13);
    tvCode.setGravity(android.view.Gravity.CENTER);
    tvCode.setAlpha(0.7f);
    tvCode.setTextColor(0xFF555555);
    layout.addView(tvCode);
    
    cardView.addView(layout);
    builder.setView(cardView);
    builder.setCancelable(false);
    
    searchLoadingDialog = builder.create();
    if (searchLoadingDialog.getWindow() != null) {
        searchLoadingDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }
    
    searchLoadingDialog.show();
}

private void dismissSearchLoadingDialog() {
    try {
        if (searchLoadingDialog != null && searchLoadingDialog.isShowing()) {
            searchLoadingDialog.dismiss();
        }
    } catch (Exception e) {
        Log.e(TAG, "dismissSearchLoadingDialog error: " + e.getMessage());
    } finally {
        searchLoadingDialog = null;
    }
}

// ==================== 3. رسائل نجاح متطورة ====================

private void showSuccessMessage(String title, String message, boolean isSuccess) {
    Context ctx = getContext();
    if (ctx == null || !isAdded()) return;
    
    AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
    
    MaterialCardView cardView = new MaterialCardView(ctx);
    cardView.setRadius(24);
    cardView.setCardElevation(10);
    
    LinearLayout layout = new LinearLayout(ctx);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(40, 30, 40, 30);
    layout.setGravity(android.view.Gravity.CENTER);
    
    // أيقونة حسب النوع
    TextView tvIcon = new TextView(ctx);
    tvIcon.setText(isSuccess ? "✅" : "❌");
    tvIcon.setTextSize(48);
    tvIcon.setGravity(android.view.Gravity.CENTER);
    layout.addView(tvIcon);
    
    // العنوان
    TextView tvTitle = new TextView(ctx);
    tvTitle.setText(title);
    tvTitle.setTextSize(18);
    tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
    tvTitle.setGravity(android.view.Gravity.CENTER);
    tvTitle.setPadding(0, 12, 0, 8);
    tvTitle.setTextColor(isSuccess ? 0xFF22C55E : 0xFFEF4444);
    layout.addView(tvTitle);
    
    // الرسالة
    TextView tvMessage = new TextView(ctx);
    tvMessage.setText(message);
    tvMessage.setTextSize(14);
    tvMessage.setGravity(android.view.Gravity.CENTER);
    tvMessage.setPadding(0, 4, 0, 16);
    tvMessage.setTextColor(0xFF555555);
    layout.addView(tvMessage);
    
    cardView.addView(layout);
    builder.setView(cardView);
    builder.setPositiveButton(isArabic ? "حسناً" : "OK", (d, w) -> d.dismiss());
    builder.setCancelable(false);
    builder.show();
}

    
// ==================== 5. تحسين parseItemResponse مع رسائل أوضح ====================

private void parseItemResponse(String code, JSONObject data) {
    try {
        JSONArray tamperAlerts = data.optJSONArray("tamper_alerts");
        if (tamperAlerts != null && tamperAlerts.length() > 0) {
            safeRun(() -> {
                com.example.uhf.tools.TamperAlertHelper.showTamperAlert(getContext(), tamperAlerts, code);
                playAlertSound(2);
            });
            if (com.example.uhf.tools.TamperAlertHelper.hasSecurityAlert(tamperAlerts)) {
                safeRun(() -> {
                    showSuccessMessage(
                        isArabic ? "🚨 تحذير أمني" : "🚨 Security Alert",
                        isArabic ? "تم اكتشاف عبث بالقطعة!\nتم منع العملية." : 
                                   "Tamper detected!\nOperation blocked.",
                        false
                    );
                    if (tvCartStatus != null) tvCartStatus.setText(isArabic ? "🚨 عبث مكتشف" : "🚨 Tamper detected");
                });
                return;
            }
        }
        
        JSONObject item = data.optJSONObject("item");
        JSONObject barcode = item != null ? item.optJSONObject("barcode") : null;
        String vaultStatus = item != null ? item.optString("status", "").toLowerCase() : "";
        boolean isAvailable = !vaultStatus.equals("sold") && !vaultStatus.equals("reserved");
        String title = item != null ? item.optString("title", isArabic ? "قطعة" : "Item") : (isArabic ? "قطعة" : "Item");
        String karat = item != null ? item.optString("karat", "21K") : "21K";
        double weight = item != null ? item.optDouble("weight", item.optDouble("gross_weight", 0)) : 0;
        double price = item != null ? item.optDouble("dynamic_retail_price", item.optDouble("retail_price", item.optDouble("price", 0))) : 0;
        if (price <= 0 && weight > 0) {
            price = priceManager.calculateGoldPrice(getKaratNumber(karat), weight);
        }
        
        CartItem cartItem = new CartItem(code, title, karat, weight, liveScaleWeight, price, isAvailable, vaultStatus.toUpperCase());
        cartItem.serialNo = item != null ? item.optString("serial", code) : code;
        cartItem.gtin = (barcode != null) ? barcode.optString("gtin13", "") : (item != null ? item.optString("gtin", "") : "");
        cartItem.location = item != null ? item.optString("location", "") : "";
        cartItem.itemId = item != null ? item.optInt("id", 0) : 0;
        
        // ✅ إنشاء متغير final للسعر
final double finalPrice = price;
final String finalTitle = title;

safeRun(() -> {
    if (!isAvailable) {
        showSuccessMessage(
            isArabic ? "❌ قطعة غير متاحة" : "❌ Item Unavailable",
            isArabic ? "حالة القطعة: " + vaultStatus : "Item status: " + vaultStatus,
            false
        );
        updateCartSummary();
        return;
    }
    
    scannedEpcs.add(code);
    cartList.add(0, cartItem);
    if (cartAdapter != null) cartAdapter.notifyItemInserted(0);
    if (rvCartItems != null) rvCartItems.scrollToPosition(0);
    
    // ✅ رسالة نجاح سريعة
    showSuccessMessage(
        isArabic ? "✅ تمت الإضافة" : "✅ Added",
        finalTitle + " - " + priceManager.formatPriceDefault(finalPrice),
        true
    );
    
    updateCartSummary();
});
    } catch (Exception e) {
        Log.e(TAG, "Parse error: " + e.getMessage());
        handleItemNotFound(code);
    }
}

    // ==================== 4. تحسين handleItemNotFound ====================

private void handleItemNotFound(String code) {
    safeRun(() -> {
        showSuccessMessage(
            isArabic ? "❌ القطعة غير موجودة" : "❌ Item Not Found",
            isArabic ? "لم يتم العثور على القطعة بالكود:\n" + code : 
                       "Item not found with code:\n" + code,
            false
        );
        playAlertSound(2);
        updateCartSummary();
    });
}

    private void updateCartSummary() {
        int count = cartList.size();
        double sum = 0;
        boolean bad = false;
        for (CartItem i : cartList) {
            sum += i.price;
            if (!i.isAvailable) bad = true;
        }
        final int fc = count;
        final double fs = sum;
        final boolean fb = bad;
        safeRun(() -> {
            if (layoutCartEmptyState != null) layoutCartEmptyState.setVisibility(fc == 0 ? View.VISIBLE : View.GONE);
            if (tvItemCount != null) tvItemCount.setText(String.valueOf(fc));
            if (tvTotalAmount != null) tvTotalAmount.setText(priceManager.formatPriceDefault(fs));
            if (tvCartStatus != null) {
                if (fc == 0) tvCartStatus.setText(isArabic ? getString(R.string.pos_ready_scan) : "Ready to scan...");
                else if (fb) tvCartStatus.setText(isArabic ? "⚠️ قطعة غير متاحة!" : "⚠️ Unavailable item!");
                else tvCartStatus.setText(isArabic ? "✓ جاهز للبيع" : "✓ Ready for sale");
            }
            if (btnCheckout != null) {
                btnCheckout.setEnabled(fc > 0 && !fb && (permissionManager.canView("pos") || permissionManager.isPosOperator() || permissionManager.isManager()));
                btnCheckout.setAlpha(fc > 0 && !fb ? 1f : 0.5f);
            }
        });
    }

    private void removeItemFromCart(int pos) {
        if (pos >= 0 && pos < cartList.size()) {
            scannedEpcs.remove(cartList.get(pos).epc);
            cartList.remove(pos);
            if (cartAdapter != null) cartAdapter.notifyItemRemoved(pos);
            updateCartSummary();
        }
    }

    private void clearCart() {
        int size = cartList.size();
        if (size > 0) {
            cartList.clear();
            scannedEpcs.clear();
            if (cartAdapter != null) cartAdapter.notifyItemRangeRemoved(0, size);
            updateCartSummary();
        }
    }

    private double getCartTotal() {
        double t = 0;
        for (CartItem i : cartList) t += i.price;
        return t;
    }

private void processCheckoutAndStockUpdate() {
    if (!(permissionManager.canView("pos") || permissionManager.isPosOperator() || permissionManager.isManager())) {
        showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
        return;
    }
    if (cartList.isEmpty()) {
        showToast(isArabic ? "السلة فارغة" : "Cart is empty");
        return;
    }
    
    // ✅ منع فتح الديالوج مرتين
    if (isCheckoutDialogShowing) {
        Log.d(TAG, "Checkout dialog already showing");
        return;
    }
    
    if (!isAdded() || getActivity() == null) return;
    UIHelper.hideKeyboard(getActivity());
    
    isCheckoutDialogShowing = true;
    
    try {
        POSCheckoutDialog dialog = POSCheckoutDialog.newInstance(getCartTotal(), cartList.size());
        
        dialog.setCheckoutListener(data -> {
            isCheckoutDialogShowing = false;
            executeCheckout(data);
        });
        
        // ✅ استخدام OnDialogDismissListener الصحيح
        dialog.setOnDismissListener(() -> {
            isCheckoutDialogShowing = false;
            Log.d(TAG, "Checkout dialog dismissed");
        });
        
        FragmentManager fm = getParentFragmentManager();
        
        if (!fm.isStateSaved()) {
            dialog.show(fm, "POSCheckoutDialog");
        } else {
            isCheckoutDialogShowing = false;
        }
    } catch (Exception e) {
        isCheckoutDialogShowing = false;
        showToast(isArabic ? "خطأ" : "Error: " + e.getMessage());
    }
}
    private void executeCheckout(POSCheckoutDialog.POSCheckoutData data) {
    if (!isAdded()) return;

    final AlertDialog loadingDialog = showProfessionalLoadingDialog();
    if (loadingDialog == null) return;

    setButtonsEnabled(false);

    new Thread(() -> {
        try {
            Context ctx = getContext();
            if (ctx == null) return;
            MiraApiClient client = MiraApiClient.getInstance(ctx);

            SessionManager sessionManager = new SessionManager(ctx);
            int userId = sessionManager.getUserId();
            String username = sessionManager.getUsername();
            int branchId = getActiveBranchId();

            // ✅ الحصول على العملة المحلية وسعر الصرف
            String localCurrency = priceManager.getDefaultCurrency();
            double exchangeRate = priceManager.getExchangeRate(localCurrency);

            JSONObject payload = new JSONObject();
            payload.put("action", "COMPLETE_POS_SALE");
            payload.put("gate_id", client.getGateId());
            payload.put("branch_id", branchId);
            payload.put("user_id", userId);
            payload.put("username", username);

            JSONObject customer = new JSONObject();
            customer.put("name", data.customerName);
            customer.put("phone", data.customerPhone);
            customer.put("email", data.customerEmail != null ? data.customerEmail : "");
            customer.put("address", data.customerAddress != null ? data.customerAddress : "");
            customer.put("notes", data.customerNotes != null ? data.customerNotes : "");
            customer.put("payment_method", data.paymentMethod);
            payload.put("customer", customer);

            JSONArray itemsArr = new JSONArray();
            for (CartItem item : cartList) {
                JSONObject obj = new JSONObject();
                obj.put("epc", item.epc);
                obj.put("serial_no", item.serialNo != null ? item.serialNo : item.epc);
                obj.put("title", item.name);
                obj.put("karat", item.karat);
                obj.put("weight", item.scaleWeight > 0 ? item.scaleWeight : item.weight);
                
                // ✅ السعر بالدولار (الأساسي) والمحلي
                obj.put("sale_price_usd", item.price);
                obj.put("sale_price", priceManager.convertFromUSD(item.price, localCurrency));
                obj.put("item_id", item.itemId > 0 ? item.itemId : 0);
                itemsArr.put(obj);
            }
            payload.put("items", itemsArr);

            // ✅ حساب المبالغ بالعملتين
            double subtotalUSD = data.originalTotal;
            double discountAmountUSD = data.discountAmount;
            double finalAmountUSD = data.finalAmount;
            
            double subtotalLocal = priceManager.convertFromUSD(subtotalUSD, localCurrency);
            double discountAmountLocal = priceManager.convertFromUSD(discountAmountUSD, localCurrency);
            double finalAmountLocal = priceManager.convertFromUSD(finalAmountUSD, localCurrency);

            JSONObject payment = new JSONObject();
            payment.put("subtotal_usd", subtotalUSD);
            payment.put("subtotal", subtotalLocal);
            payment.put("discount_percent", data.discount);
            payment.put("discount_amount_usd", discountAmountUSD);
            payment.put("discount_amount", discountAmountLocal);
            payment.put("final_amount_usd", finalAmountUSD);
            payment.put("final_amount", finalAmountLocal);
            payment.put("account_code", data.accountCode);
            payment.put("currency", localCurrency);
            payment.put("base_currency", "USD");
            payment.put("exchange_rate", exchangeRate);
            payload.put("payment", payment);

            MiraApiClient.ApiResponse response = client.checkout(payload);

            safeRun(() -> {
                dismissDialog(loadingDialog);
                setButtonsEnabled(true);

                if (response != null && response.isSuccess) {
                    playAlertSound(1);
                    int soldCount = cartList.size();
                    double finalAmount = finalAmountLocal;
                    String paymentMethod = data.paymentMethod;
                    clearCart();
                    autoGenerateAccountingEntry(response.data);
                    showSuccessDialog(finalAmountUSD, soldCount, paymentMethod);
                    if (response.data != null) {
                        String json = response.data.toString();
                        if (getActivity() instanceof UHFMainActivity)
                            ((UHFMainActivity) getActivity()).openReceiptFragment(json);
                    }
                } else {
                    String errorMsg = "";
                    if (response != null && response.error != null) errorMsg = response.error;
                    else if (response != null && response.data != null) errorMsg = response.data.optString("message", "");
                    if (errorMsg.isEmpty()) errorMsg = isArabic ? "فشل الاتصال بالسيرفر" : "Server connection failed";
                    showRetryDialog(
                        isArabic ? "❌ فشل إتمام البيع" : "❌ Sale Failed",
                        errorMsg,
                        () -> executeCheckout(data)
                    );
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Checkout Error: " + e.getMessage());
            safeRun(() -> {
                dismissDialog(loadingDialog);
                setButtonsEnabled(true);
                saveFullSaleLocally(data);
                clearCart();
                showRetryDialog(
                    isArabic ? "❌ خطأ في الاتصال" : "❌ Connection Error",
                    isArabic ? "تم حفظ العملية محلياً.\nسيتم المزامنة تلقائياً عند توفر الاتصال." :
                            "Sale saved locally.\nWill sync automatically when connection is available.",
                    null
                );
            });
        }
    }).start();
}


    private void autoGenerateAccountingEntry(JSONObject saleData) {
        if (saleData == null) return;
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("sale_id", saleData.optInt("sale_id", 0));
                payload.put("sale_number", saleData.optString("sale_number", ""));
                payload.put("final_amount", saleData.optDouble("final_amount", 0));
                payload.put("branch_id", getActiveBranchId());
                
                MiraApiClient.getInstance(requireContext()).createJournalEntry(payload);
            } catch (Exception e) {
                Log.e(TAG, "Auto entry error: " + e.getMessage());
            }
        });
    }


    private void saveFullSaleLocally(POSCheckoutDialog.POSCheckoutData data) {
    if (getContext() == null) return;
    try {
        SharedPreferences p = getContext().getSharedPreferences(PENDING_SALES_PREFS, Context.MODE_PRIVATE);
        JSONArray arr = new JSONArray(p.getString("pending", "[]"));
        JSONObject sale = new JSONObject();
        
        // ✅ حفظ العملة وسعر الصرف
        String localCurrency = priceManager.getDefaultCurrency();
        double exchangeRate = priceManager.getExchangeRate(localCurrency);
        
        sale.put("id", System.currentTimeMillis());
        sale.put("customer_name", data.customerName);
        sale.put("customer_phone", data.customerPhone);
        sale.put("customer_email", data.customerEmail);
        sale.put("customer_address", data.customerAddress);
        sale.put("customer_notes", data.customerNotes);
        sale.put("payment_method", data.paymentMethod);
        sale.put("account_code", data.accountCode);
        sale.put("currency", localCurrency);
        sale.put("base_currency", "USD");
        sale.put("exchange_rate", exchangeRate);
        sale.put("original_total_usd", data.originalTotal);
        sale.put("original_total", priceManager.convertFromUSD(data.originalTotal, localCurrency));
        sale.put("discount", data.discount);
        sale.put("discount_amount_usd", data.discountAmount);
        sale.put("discount_amount", priceManager.convertFromUSD(data.discountAmount, localCurrency));
        sale.put("final_amount_usd", data.finalAmount);
        sale.put("final_amount", priceManager.convertFromUSD(data.finalAmount, localCurrency));
        sale.put("item_count", cartList.size());
        sale.put("branch_id", getActiveBranchId());
        sale.put("created_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        
        JSONArray itemsArr = new JSONArray();
        for (CartItem item : cartList) {
            JSONObject obj = new JSONObject();
            obj.put("epc", item.epc);
            obj.put("serial_no", item.serialNo != null ? item.serialNo : item.epc);
            obj.put("title", item.name);
            obj.put("karat", item.karat);
            obj.put("weight", item.scaleWeight > 0 ? item.scaleWeight : item.weight);
            obj.put("price_usd", item.price);
            obj.put("price", priceManager.convertFromUSD(item.price, localCurrency));
            if (item.itemId > 0) obj.put("item_id", item.itemId);
            itemsArr.put(obj);
        }
        sale.put("items", itemsArr);
        arr.put(sale);
        p.edit().putString("pending", arr.toString()).apply();
    } catch (Exception e) {
        Log.e(TAG, "Save local sale error: " + e.getMessage());
    }
}

    private void checkPendingSales() {
        if (getContext() == null) return;
        try {
            JSONArray arr = new JSONArray(getContext()
                    .getSharedPreferences(PENDING_SALES_PREFS, Context.MODE_PRIVATE)
                    .getString("pending", "[]"));
            if (arr.length() > 0) {
                safeRun(() -> {
                    if (tvCartStatus != null) {
                        tvCartStatus.setText("⚠️ " + arr.length() + " " + (isArabic ? "عملية معلقة" : "pending sales"));
                    }
                });
            }
        } catch (Exception ignored) {}
    }

    private void syncPendingSales() {
    if (getContext() == null) return;
    new Thread(() -> {
        try {
            SharedPreferences p = getContext().getSharedPreferences(PENDING_SALES_PREFS, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(p.getString("pending", "[]"));
            if (arr.length() == 0) return;
            MiraApiClient client = MiraApiClient.getInstance(getContext());
            JSONArray remainingArr = new JSONArray();
            int synced = 0;
            int branchId = getActiveBranchId();
            
            for (int i = 0; i < arr.length(); i++) {
                try {
                    JSONObject sale = arr.getJSONObject(i);
                    JSONObject payload = new JSONObject();
                    payload.put("action", "COMPLETE_POS_SALE");
                    payload.put("gate_id", client.getGateId());
                    payload.put("branch_id", sale.optInt("branch_id", branchId));
                    
                    JSONObject customer = new JSONObject();
                    customer.put("name", sale.optString("customer_name", ""));
                    customer.put("phone", sale.optString("customer_phone", ""));
                    customer.put("email", sale.optString("customer_email", ""));
                    customer.put("address", sale.optString("customer_address", ""));
                    customer.put("notes", sale.optString("customer_notes", ""));
                    customer.put("payment_method", sale.optString("payment_method", "CASH"));
                    payload.put("customer", customer);
                    
                    payload.put("items", sale.optJSONArray("items"));
                    
                    JSONObject payment = new JSONObject();
                    payment.put("subtotal_usd", sale.optDouble("original_total_usd", 0));
                    payment.put("subtotal", sale.optDouble("original_total", 0));
                    payment.put("discount_percent", sale.optDouble("discount", 0));
                    payment.put("discount_amount_usd", sale.optDouble("discount_amount_usd", 0));
                    payment.put("discount_amount", sale.optDouble("discount_amount", 0));
                    payment.put("final_amount_usd", sale.optDouble("final_amount_usd", 0));
                    payment.put("final_amount", sale.optDouble("final_amount", 0));
                    payment.put("account_code", sale.optString("account_code", "110101"));
                    payment.put("currency", sale.optString("currency", "USD"));
                    payment.put("base_currency", "USD");
                    payment.put("exchange_rate", sale.optDouble("exchange_rate", 1.0));
                    payload.put("payment", payment);
                    
                    MiraApiClient.ApiResponse response = client.checkout(payload);
                    if (response != null && response.isSuccess) synced++;
                    else remainingArr.put(sale);
                } catch (Exception e) {
                    remainingArr.put(arr.getJSONObject(i));
                }
            }
            p.edit().putString("pending", remainingArr.toString()).apply();
            final int s = synced;
            final int r = remainingArr.length();
            safeRun(() -> {
                if (s > 0) showToast(isArabic ? "✅ تمت مزامنة " + s + " عملية" : "✅ Synced " + s + " sales");
                if (r > 0) {
                    if (tvCartStatus != null) tvCartStatus.setText("⚠️ " + r + " " + (isArabic ? "معلقة" : "pending"));
                } else {
                    if (tvCartStatus != null) tvCartStatus.setText(isArabic ? getString(R.string.pos_ready_scan) : "Ready to scan...");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Sync pending error: " + e.getMessage());
        }
    }).start();
}
    private AlertDialog showProfessionalLoadingDialog() {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return null;

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        
        MaterialCardView cardView = new MaterialCardView(ctx);
        cardView.setRadius(20);
        cardView.setCardElevation(8);
        cardView.setStrokeWidth(0);

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 36, 40, 36);
        layout.setGravity(android.view.Gravity.CENTER);

        ProgressBar progressBar = new ProgressBar(ctx);
        progressBar.setIndeterminate(true);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(90, 90);
        pbParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        progressBar.setLayoutParams(pbParams);
        layout.addView(progressBar);

        TextView tvLoading = new TextView(ctx);
        tvLoading.setText(isArabic ? "جاري إتمام البيع..." : "Processing sale...");
        tvLoading.setTextSize(17);
        tvLoading.setTypeface(null, android.graphics.Typeface.BOLD);
        tvLoading.setGravity(android.view.Gravity.CENTER);
        tvLoading.setPadding(0, 20, 0, 6);
        layout.addView(tvLoading);

        TextView tvSubtext = new TextView(ctx);
        tvSubtext.setText(isArabic ? "يرجى الانتظار، يتم تحديث المخزون وتسجيل القيود المحاسبية" :
                "Please wait, updating inventory & registering accounting entries");
        tvSubtext.setTextSize(12);
        tvSubtext.setGravity(android.view.Gravity.CENTER);
        tvSubtext.setAlpha(0.7f);
        tvSubtext.setPadding(0, 4, 0, 0);
        layout.addView(tvSubtext);

        cardView.addView(layout);
        builder.setView(cardView);
        builder.setCancelable(false);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        }
        dialog.show();
        return dialog;
    }

    private void setButtonsEnabled(boolean enabled) {
        safeRun(() -> {
            boolean canUsePos = permissionManager.canView("pos") || permissionManager.isPosOperator() || permissionManager.isManager();
            if (btnCheckout != null) {
                btnCheckout.setEnabled(enabled && canUsePos && !cartList.isEmpty());
                btnCheckout.setAlpha(enabled && canUsePos && !cartList.isEmpty() ? 1.0f : 0.5f);
            }
            if (btnClearCart != null) btnClearCart.setEnabled(enabled);
            if (btnAddManual != null) btnAddManual.setEnabled(enabled && canUsePos);
            if (btnOpenScanner != null) btnOpenScanner.setEnabled(enabled && canUsePos);
            if (btnPosRFIDMode != null) btnPosRFIDMode.setEnabled(enabled && canUsePos);
            if (etManualEpc != null) etManualEpc.setEnabled(enabled && canUsePos);
        });
    }

    private void showRetryDialog(String title, String message, Runnable retryAction) {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(title).setMessage(message).setCancelable(false);
        if (retryAction != null) {
            builder.setPositiveButton(isArabic ? "🔄 إعادة المحاولة" : "🔄 Retry", (dialog, which) -> {
                dialog.dismiss();
                retryAction.run();
            });
        }
        builder.setNegativeButton(isArabic ? "إغلاق" : "Close", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    protected void dismissDialog(AlertDialog dialog) {
        try {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        } catch (Exception e) {
            Log.e(TAG, "dismissDialog error: " + e.getMessage());
        }
    }

    private void showSuccessDialog(double amount, int itemCount, String paymentMethod) {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(30, 25, 30, 10);

        TextView tvIcon = new TextView(ctx);
        tvIcon.setText("🎉");
        tvIcon.setTextSize(44);
        tvIcon.setGravity(android.view.Gravity.CENTER);
        layout.addView(tvIcon);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(isArabic ? "تمت عملية البيع بنجاح!" : "Sale Completed!");
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setGravity(android.view.Gravity.CENTER);
        tvTitle.setPadding(0, 10, 0, 20);
        layout.addView(tvTitle);

        MaterialCardView card = new MaterialCardView(ctx);
        card.setRadius(12);
        card.setCardElevation(2);
        
        LinearLayout cardLayout = new LinearLayout(ctx);
        cardLayout.setOrientation(LinearLayout.VERTICAL);
        cardLayout.setPadding(16, 16, 16, 16);
        addDetailRow(cardLayout, isArabic ? "📦 عدد القطع:" : "📦 Items:", String.valueOf(itemCount));
        addDetailRow(cardLayout, isArabic ? "💰 المبلغ الإجمالي:" : "💰 Total:", priceManager.formatPriceDefault(amount));
        addDetailRow(cardLayout, isArabic ? "💳 طريقة الدفع:" : "💳 Payment:", paymentMethod);
        card.addView(cardLayout);
        layout.addView(card);

        builder.setView(layout);
        builder.setPositiveButton(isArabic ? "متابعة" : "Continue", (d, w) -> d.dismiss());
        builder.setCancelable(false);
        builder.show();
    }

    private void addDetailRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 6, 0, 6);
        
        TextView tvLabel = new TextView(requireContext());
        tvLabel.setText(label);
        tvLabel.setTextSize(13);
        tvLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        row.addView(tvLabel);

        TextView tvValue = new TextView(requireContext());
        tvValue.setText(value);
        tvValue.setTextSize(13);
        tvValue.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(tvValue);

        parent.addView(row);
    }

    private static class CartAdapter extends RecyclerView.Adapter<CartAdapter.ViewHolder> {
        interface OnRemoveListener {
            void onRemove(int position);
        }

        private final List<CartItem> items;
        private final OnRemoveListener removeListener;
        private final boolean isArabic;
        private final MiraPriceManager priceManager;

        CartAdapter(List<CartItem> items, OnRemoveListener listener, boolean isArabic, MiraPriceManager priceManager) {
            this.items = items;
            this.removeListener = listener;
            this.isArabic = isArabic;
            this.priceManager = priceManager;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_pos_cart_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
            CartItem item = items.get(pos);
            if (h.tvTitle != null) h.tvTitle.setText(item.name);
            if (h.tvEpc != null) h.tvEpc.setText("EPC: " + item.epc);
            if (h.tvKaratWeight != null) {
                h.tvKaratWeight.setText(String.format(Locale.US, "%s | %.2fg", item.karat,
                        item.scaleWeight > 0 ? item.scaleWeight : item.weight));
            }
            if (h.tvPrice != null) h.tvPrice.setText(priceManager.formatPriceDefault(item.price));
            if (h.tvStatus != null) {
                h.tvStatus.setText(item.isAvailable ? (isArabic ? "✅ متاحة" : "✅ Available") : (isArabic ? "❌ غير متاحة" : "❌ Unavailable"));
            }
            if (h.btnRemove != null) {
                h.btnRemove.setOnClickListener(v -> {
                    int p = h.getAdapterPosition();
                    if (p != RecyclerView.NO_POSITION) removeListener.onRemove(p);
                });
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle, tvEpc, tvKaratWeight, tvPrice, tvStatus;
            View btnRemove;

            ViewHolder(View v) {
                super(v);
                tvTitle = v.findViewById(R.id.tvRowItemTitle);
                tvEpc = v.findViewById(R.id.tvRowItemEpc);
                tvKaratWeight = v.findViewById(R.id.tvRowItemKaratWeight);
                tvPrice = v.findViewById(R.id.tvRowItemPrice);
                tvStatus = v.findViewById(R.id.tvRowItemStatus);
                btnRemove = v.findViewById(R.id.btnRowRemove);
            }
        }
    }
                }
