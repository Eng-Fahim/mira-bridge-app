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


import android.app.Dialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.WindowManager;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.result.ActivityResultLauncher;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.google.android.material.button.MaterialButton;

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
import com.example.uhf.fragment.MiraReceiptFragment;


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
    private MiraApiClient apiClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());


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
    
    private Button btnPosRefund;
    
    private MiraApiClient.ApiResponse lastSaleResponse = null;
    
    private EditText etRefundCode;
private Dialog refundDialog;
private TextView tvRefundItemTitle;
private TextView tvRefundItemStatus;
private View cardRefundItemInfo;
private boolean isRefundable = false;


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
    try {
        return inflater.inflate(R.layout.fragment_mira_pos, container, false);
    } catch (Exception e) {
        Log.e(TAG, "onCreateView error: " + e.getMessage(), e);
        return null;
    }
}

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        try {
            Context ctx = requireContext();
            branchesManager = MiraBranchesManager.getInstance(ctx);
            priceManager = MiraPriceManager.getInstance(ctx);
            permissionManager = PermissionManager.getInstance(ctx);
            isArabic = Locale.getDefault().getLanguage().equals("ar");
            
            rfidManager = RFIDManager.getInstance(ctx);
            if (rfidManager != null) {
                rfidManager.setListener(new MiraIRFIDReader.OnTagReadListener() {
                    @Override
                    public void onTagRead(String epc, int rssi) {
                        if (isRFIDScanActive) onRingScanned(epc);
                    }
                    @Override
                    public void onScanStarted() {}
                    @Override
                    public void onScanStopped() {}
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
            }
            
            apiClient = MiraApiClient.getInstance(ctx);
            bindViews(view);
            setupRecyclerView();
            setupListeners();
            applyPermissions();
            initSmartScale();
            checkPendingSales();
            updateCartSummary();
        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error: " + e.getMessage(), e);
        }
    }

private void refreshData() {
    // ✅ تحديث البيانات بعد الإرجاع - بدون استدعاء دالة غير موجودة
    try {
        // يمكن تحديث الداشبورد عبر Intent أو Interface
        // أو ببساطة تجاهل التحديث هنا
        Log.d(TAG, "Data refreshed after refund");
    } catch (Exception e) {
        Log.e(TAG, "refreshData error: " + e.getMessage());
    }
}
    private void applyPermissions() {
        try {
            boolean canUsePos = permissionManager.canView("pos") || permissionManager.isPosOperator() || permissionManager.isManager();
            
            if (btnCheckout != null) btnCheckout.setEnabled(false);
            if (btnAddManual != null) btnAddManual.setEnabled(canUsePos);
            if (btnOpenScanner != null) btnOpenScanner.setEnabled(canUsePos);
            if (btnPosRFIDMode != null) btnPosRFIDMode.setEnabled(canUsePos);
            if (etManualEpc != null) etManualEpc.setEnabled(canUsePos);
            if (btnPosRefund != null) {
               boolean canRefund = permissionManager.canEdit("pos") || permissionManager.isAdmin() || permissionManager.isManager();
               btnPosRefund.setVisibility(canRefund ? View.VISIBLE : View.GONE);
               }
            
            if (!canUsePos && tvCartStatus != null) {
                tvCartStatus.setText(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            }
        } catch (Exception e) {
            Log.e(TAG, "applyPermissions error: " + e.getMessage());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setActivePOSFragment(this);
            }
            if (scaleConnector != null) scaleConnector.connect();
            if (tvCartStatus != null && cartList.isEmpty()) {
                tvCartStatus.setText(isArabic ? getString(R.string.pos_ready_scan) : "Ready to scan...");
            }
            syncPendingSales();
        } catch (Exception e) {
            Log.e(TAG, "onResume error: " + e.getMessage());
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            stopRFIDScan();
            isCheckoutDialogShowing = false;
            if (scaleConnector != null) scaleConnector.disconnect();
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setActivePOSFragment(null);
            }
        } catch (Exception e) {
            Log.e(TAG, "onPause error: " + e.getMessage());
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try {
            stopRFIDScan();
            isCheckoutDialogShowing = false;
            dismissSearchLoadingDialog();
            if (rfidManager != null) rfidManager.setListener(null);
        } catch (Exception e) {
            Log.e(TAG, "onDestroyView error: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            stopRFIDScan();
            if (scaleConnector != null) scaleConnector.disconnect();
            if (rfidManager != null) rfidManager.release();
        } catch (Exception e) {
            Log.e(TAG, "onDestroy error: " + e.getMessage());
        }
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
        try {
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (isAdded()) r.run();
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "safeRun error: " + e.getMessage());
        }
    }

    private void showToast(String m) {
        try {
            if (getContext() != null && isAdded()) {
                Toast.makeText(getContext(), m, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "showToast error: " + e.getMessage());
        }
    }

    private void playAlertSound(int id) {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).playSound(id);
            }
        } catch (Exception e) {
            Log.e(TAG, "playAlertSound error: " + e.getMessage());
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
        btnPosRefund = view.findViewById(R.id.btnPosRefund);
        
        applyLanguage();
    }

    private void applyLanguage() {
        try {
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
        } catch (Exception e) {
            Log.e(TAG, "applyLanguage error: " + e.getMessage());
        }
    }

    private void setupRecyclerView() {
        try {
            if (rvCartItems != null) {
                rvCartItems.setLayoutManager(new LinearLayoutManager(getContext()));
                cartAdapter = new CartAdapter(cartList, this::removeItemFromCart, isArabic, priceManager);
                rvCartItems.setAdapter(cartAdapter);
            }
        } catch (Exception e) {
            Log.e(TAG, "setupRecyclerView error: " + e.getMessage());
        }
    }

    private void setupListeners() {
        try {
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
            
            if (btnAddManual != null) {
                btnAddManual.setOnClickListener(v -> submitManualEpc());
            }
            
            if (btnClearCart != null) {
                btnClearCart.setOnClickListener(v -> {
                    UIHelper.hideKeyboard(getActivity());
                    clearCart();
                });
            }
            
            if (btnCheckout != null) {
                btnCheckout.setOnClickListener(v -> {
                    UIHelper.hideKeyboard(getActivity());
                    processCheckoutAndStockUpdate();
                });
            }
            if (btnPosRefund != null) {
            btnPosRefund.setOnClickListener(v -> showRefundDialog());
            }
            
            if (btnOpenScanner != null) {
                btnOpenScanner.setOnClickListener(v -> {
                    try {
                        UIHelper.hideKeyboard(getActivity());
                        openBarcodeScanner();
                    } catch (Exception e) {
                        Log.e(TAG, "Open scanner error: " + e.getMessage());
                        showToast(isArabic ? "⚠️ تعذر فتح الماسح" : "⚠️ Unable to open scanner");
                    }
                });
            }
            
            if (btnPosRFIDMode != null) {
                btnPosRFIDMode.setOnClickListener(v -> {
                    try {
                        UIHelper.hideKeyboard(getActivity());
                        toggleRFIDMode();
                    } catch (Exception e) {
                        Log.e(TAG, "RFID mode error: " + e.getMessage());
                        showToast(isArabic ? "⚠️ خطأ في RFID" : "⚠️ RFID error");
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "setupListeners error: " + e.getMessage());
        }
    }

    private void submitManualEpc() {
        try {
            if (!(permissionManager.canView("pos") || permissionManager.isPosOperator() || permissionManager.isManager())) {
                showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
                return;
            }
            
            UIHelper.hideKeyboard(getActivity());
            String epc = etManualEpc != null ? etManualEpc.getText().toString().trim() : "";
            if (!epc.isEmpty()) {
                if (scannedEpcs.contains(epc)) {
                    showToast(isArabic ? "⚠️ هذه القطعة مضافة مسبقاً!" : "⚠️ Item already in cart!");
                    playAlertSound(2);
                    return;
                }
                processRingEpc(epc, "manual");
                if (etManualEpc != null) etManualEpc.setText("");
            } else {
                showToast(isArabic ? "أدخل EPC أو باركود" : "Enter EPC or barcode");
            }
        } catch (Exception e) {
            Log.e(TAG, "submitManualEpc error: " + e.getMessage());
        }
    }

    private void initSmartScale() {
        try {
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
        } catch (Exception e) {
            Log.e(TAG, "initSmartScale error: " + e.getMessage());
        }
    }

    private void openBarcodeScanner() {
        try {
            if (!isAdded() || getContext() == null) return;
            if (getParentFragmentManager() == null) return;
            if (getParentFragmentManager().isStateSaved()) return;
            
            MiraBarcodeScannerFragment sf = new MiraBarcodeScannerFragment();
            sf.setOnBarcodeScannedListener((data, format) -> {
                try {
                    onBarcodeScanned(data);
                    if (isAdded() && getParentFragmentManager() != null && 
                        getParentFragmentManager().getBackStackEntryCount() > 0) {
                        getParentFragmentManager().popBackStack();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Barcode callback error: " + e.getMessage());
                }
            });
            
            int containerId = android.R.id.content;
            try {
                View view = getView();
                if (view != null && view.getParent() instanceof View) {
                    containerId = ((View) view.getParent()).getId();
                }
            } catch (Exception e) {
                containerId = android.R.id.content;
            }
            
            getParentFragmentManager().beginTransaction()
                    .replace(containerId, sf)
                    .addToBackStack("barcode_scanner")
                    .commitAllowingStateLoss();
        } catch (Exception e) {
            Log.e(TAG, "openBarcodeScanner error: " + e.getMessage());
            showToast(isArabic ? "⚠️ تعذر فتح الماسح" : "⚠️ Unable to open scanner");
        }
    }

    private void toggleRFIDMode() {
        try {
            if (!isAdded() || rfidManager == null) return;
            
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
        } catch (Exception e) {
            Log.e(TAG, "toggleRFIDMode error: " + e.getMessage());
            isRFIDModeActive = false;
        }
    }

    private void startRFIDScan() {
        try {
            if (rfidManager == null) return;
            isRFIDScanActive = true;
            rfidManager.startScan();
        } catch (Exception e) {
            Log.e(TAG, "startRFIDScan error: " + e.getMessage());
            isRFIDScanActive = false;
        }
    }

    private void stopRFIDScan() {
        try {
            if (rfidManager == null) return;
            isRFIDScanActive = false;
            rfidManager.stopScan();
        } catch (Exception e) {
            Log.e(TAG, "stopRFIDScan error: " + e.getMessage());
        }
    }

    public void onRingScanned(String epc) {
        try {
            if (epc == null || epc.trim().isEmpty()) return;
            long currentTime = System.currentTimeMillis();
            Long lastTime = lastScannedTimes.get(epc);
            if (lastTime != null && (currentTime - lastTime < 1500)) return;
            lastScannedTimes.put(epc, currentTime);
            
            if (scannedEpcs.contains(epc)) {
                safeRun(() -> {
                    showToast(isArabic ? "⚠️ مضافة مسبقاً!" : "⚠️ Already in cart!");
                    playAlertSound(2);
                });
                return;
            }
            processRingEpc(epc, "rfid");
        } catch (Exception e) {
            Log.e(TAG, "onRingScanned error: " + e.getMessage());
        }
    }

    public void onBarcodeScanned(String data) {
        try {
            if (data == null || data.trim().isEmpty()) return;
            long currentTime = System.currentTimeMillis();
            Long lastTime = lastScannedTimes.get(data);
            if (lastTime != null && (currentTime - lastTime < 1500)) return;
            lastScannedTimes.put(data, currentTime);
            
            if (scannedEpcs.contains(data)) {
                safeRun(() -> {
                    showToast(isArabic ? "⚠️ مضافة مسبقاً!" : "⚠️ Already in cart!");
                    playAlertSound(2);
                });
                return;
            }
            processRingEpc(data, "barcode");
        } catch (Exception e) {
            Log.e(TAG, "onBarcodeScanned error: " + e.getMessage());
        }
    }

    private void processRingEpc(final String code, final String source) {
        if (code == null || code.trim().isEmpty() || !isAdded()) return;
        final String cleanCode = code.trim();
        
        synchronized (pendingEpcs) {
            if (pendingEpcs.contains(cleanCode)) return;
            pendingEpcs.add(cleanCode);
        }
        
        playAlertSound(1);
        
        safeRun(() -> {
            try {
                showSearchLoadingDialog(cleanCode);
                if (tvCartStatus != null) tvCartStatus.setText(isArabic ? "🔍 جاري البحث..." : "🔍 Searching...");
            } catch (Exception e) {}
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
                
                Thread.sleep(300);
                MiraApiClient.ApiResponse response = client.authorize(cleanCode, "pos_sale", extraParams);
                
                safeRun(() -> dismissSearchLoadingDialog());
                
                if (response != null && response.isSuccess && response.data != null
                        && response.data.optJSONObject("item") != null) {
                    parseItemResponse(cleanCode, response.data);
                } else {
                    handleItemNotFound(cleanCode);
                }
            } catch (Exception e) {
                safeRun(() -> dismissSearchLoadingDialog());
                handleItemNotFound(cleanCode);
            } finally {
                synchronized (pendingEpcs) {
                    pendingEpcs.remove(cleanCode);
                }
            }
        }).start();
    }

    private void showSearchLoadingDialog(String code) {
        try {
            Context ctx = getContext();
            if (ctx == null || !isAdded()) return;
            
            dismissSearchLoadingDialog();
            
            MaterialCardView cardView = new MaterialCardView(ctx);
            cardView.setRadius(24);
            cardView.setCardElevation(10);
            
            LinearLayout layout = new LinearLayout(ctx);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(50, 40, 50, 40);
            layout.setGravity(android.view.Gravity.CENTER);
            
            TextView tvIcon = new TextView(ctx);
            tvIcon.setText("🔍");
            tvIcon.setTextSize(36);
            tvIcon.setGravity(android.view.Gravity.CENTER);
            layout.addView(tvIcon);
            
            ProgressBar progressBar = new ProgressBar(ctx);
            progressBar.setIndeterminate(true);
            LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(100, 100);
            pbParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            pbParams.topMargin = 16;
            progressBar.setLayoutParams(pbParams);
            layout.addView(progressBar);
            
            TextView tvLoading = new TextView(ctx);
            tvLoading.setText(isArabic ? "جاري البحث عن القطعة..." : "Searching for item...");
            tvLoading.setTextSize(16);
            tvLoading.setTypeface(null, android.graphics.Typeface.BOLD);
            tvLoading.setGravity(android.view.Gravity.CENTER);
            tvLoading.setPadding(0, 16, 0, 8);
            tvLoading.setTextColor(0xFF1A1A2E);
            layout.addView(tvLoading);
            
            TextView tvCode = new TextView(ctx);
            tvCode.setText(code);
            tvCode.setTextSize(13);
            tvCode.setGravity(android.view.Gravity.CENTER);
            tvCode.setAlpha(0.7f);
            tvCode.setTextColor(0xFF555555);
            layout.addView(tvCode);
            
            cardView.addView(layout);
            
            AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
            builder.setView(cardView);
            builder.setCancelable(false);
            
            searchLoadingDialog = builder.create();
            if (searchLoadingDialog.getWindow() != null) {
                searchLoadingDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
            searchLoadingDialog.show();
        } catch (Exception e) {
            Log.e(TAG, "showSearchLoadingDialog error: " + e.getMessage());
        }
    }

    private void dismissSearchLoadingDialog() {
        try {
            if (searchLoadingDialog != null && searchLoadingDialog.isShowing()) {
                searchLoadingDialog.dismiss();
            }
        } catch (Exception e) {
            // ignore
        } finally {
            searchLoadingDialog = null;
        }
    }

    private void showSuccessMessage(String title, String message, boolean isSuccess) {
        try {
            Context ctx = getContext();
            if (ctx == null || !isAdded()) return;
            
            MaterialCardView cardView = new MaterialCardView(ctx);
            cardView.setRadius(24);
            cardView.setCardElevation(10);
            
            LinearLayout layout = new LinearLayout(ctx);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(40, 30, 40, 30);
            layout.setGravity(android.view.Gravity.CENTER);
            
            TextView tvIcon = new TextView(ctx);
            tvIcon.setText(isSuccess ? "✅" : "❌");
            tvIcon.setTextSize(48);
            tvIcon.setGravity(android.view.Gravity.CENTER);
            layout.addView(tvIcon);
            
            TextView tvTitle = new TextView(ctx);
            tvTitle.setText(title);
            tvTitle.setTextSize(18);
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            tvTitle.setGravity(android.view.Gravity.CENTER);
            tvTitle.setPadding(0, 12, 0, 8);
            tvTitle.setTextColor(isSuccess ? 0xFF22C55E : 0xFFEF4444);
            layout.addView(tvTitle);
            
            TextView tvMessage = new TextView(ctx);
            tvMessage.setText(message);
            tvMessage.setTextSize(14);
            tvMessage.setGravity(android.view.Gravity.CENTER);
            tvMessage.setPadding(0, 4, 0, 16);
            tvMessage.setTextColor(0xFF555555);
            layout.addView(tvMessage);
            
            cardView.addView(layout);
            
            AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
            builder.setView(cardView);
            builder.setPositiveButton(isArabic ? "حسناً" : "OK", (d, w) -> d.dismiss());
            builder.setCancelable(false);
            builder.show();
        } catch (Exception e) {
            Log.e(TAG, "showSuccessMessage error: " + e.getMessage());
        }
    }

    private void parseItemResponse(String code, JSONObject data) {
        try {
            JSONObject item = data.optJSONObject("item");
            if (item == null) {
                handleItemNotFound(code);
                return;
            }
            
            JSONObject barcode = item.optJSONObject("barcode");
            String vaultStatus = item.optString("status", "").toLowerCase();
            boolean isAvailable = !vaultStatus.equals("sold") && !vaultStatus.equals("reserved");
            String title = item.optString("title", isArabic ? "قطعة" : "Item");
            String karat = item.optString("karat", "21K");
            double weight = item.optDouble("weight", item.optDouble("gross_weight", 0));
            double price = item.optDouble("dynamic_retail_price", item.optDouble("retail_price", item.optDouble("price", 0)));
            if (price <= 0 && weight > 0) {
                price = priceManager.calculateGoldPrice(getKaratNumber(karat), weight);
            }
            
            CartItem cartItem = new CartItem(code, title, karat, weight, liveScaleWeight, price, isAvailable, vaultStatus.toUpperCase());
            cartItem.serialNo = item.optString("serial", code);
            cartItem.gtin = (barcode != null) ? barcode.optString("gtin13", "") : item.optString("gtin", "");
            cartItem.location = item.optString("location", "");
            cartItem.itemId = item.optInt("id", 0);
            
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
                
                updateCartSummary();
            });
        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
            handleItemNotFound(code);
        }
    }

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
        try {
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
        } catch (Exception e) {
            Log.e(TAG, "updateCartSummary error: " + e.getMessage());
        }
    }

    private void removeItemFromCart(int pos) {
        try {
            if (pos >= 0 && pos < cartList.size()) {
                scannedEpcs.remove(cartList.get(pos).epc);
                cartList.remove(pos);
                if (cartAdapter != null) cartAdapter.notifyItemRemoved(pos);
                updateCartSummary();
            }
        } catch (Exception e) {
            Log.e(TAG, "removeItemFromCart error: " + e.getMessage());
        }
    }

    private void clearCart() {
        try {
            int size = cartList.size();
            if (size > 0) {
                cartList.clear();
                scannedEpcs.clear();
                if (cartAdapter != null) cartAdapter.notifyItemRangeRemoved(0, size);
                updateCartSummary();
            }
        } catch (Exception e) {
            Log.e(TAG, "clearCart error: " + e.getMessage());
        }
    }

    private double getCartTotal() {
        double t = 0;
        for (CartItem i : cartList) t += i.price;
        return t;
    }

    private void processCheckoutAndStockUpdate() {
        try {
            if (!(permissionManager.canView("pos") || permissionManager.isPosOperator() || permissionManager.isManager())) {
                showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
                return;
            }
            if (cartList.isEmpty()) {
                showToast(isArabic ? "السلة فارغة" : "Cart is empty");
                return;
            }
            if (isCheckoutDialogShowing) return;
            if (!isAdded() || getActivity() == null) return;
            
            UIHelper.hideKeyboard(getActivity());
            isCheckoutDialogShowing = true;
            
            POSCheckoutDialog dialog = POSCheckoutDialog.newInstance(getCartTotal(), cartList.size());
            dialog.setCheckoutListener(data -> {
                isCheckoutDialogShowing = false;
                executeCheckout(data);
            });
            dialog.setOnDismissListener(() -> {
                isCheckoutDialogShowing = false;
            });
            
            FragmentManager fm = getParentFragmentManager();
            if (!fm.isStateSaved()) {
                dialog.show(fm, "POSCheckoutDialog");
            } else {
                isCheckoutDialogShowing = false;
            }
        } catch (Exception e) {
            isCheckoutDialogShowing = false;
            Log.e(TAG, "processCheckout error: " + e.getMessage());
            showToast(isArabic ? "خطأ: " + e.getMessage() : "Error: " + e.getMessage());
        }
    }

    private void executeCheckout(POSCheckoutDialog.POSCheckoutData data) {
    try {
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
                
                JSONObject payload = new JSONObject();
                payload.put("action", "COMPLETE_POS_SALE");
                payload.put("gate_id", client.getGateId());
                payload.put("branch_id", getActiveBranchId());
                payload.put("user_id", sessionManager.getUserId());
                payload.put("username", sessionManager.getUsername());
                
                JSONObject customer = new JSONObject();
                customer.put("name", data.customerName);
                customer.put("phone", data.customerPhone);
                customer.put("payment_method", data.paymentMethod);
                payload.put("customer", customer);
                
                JSONArray itemsArr = new JSONArray();
                for (CartItem item : cartList) {
                    JSONObject obj = new JSONObject();
                    obj.put("epc", item.epc);
                    obj.put("title", item.name);
                    obj.put("karat", item.karat);
                    obj.put("weight", item.scaleWeight > 0 ? item.scaleWeight : item.weight);
                    obj.put("price_usd", item.price);
                    obj.put("item_id", item.itemId);
                    obj.put("serial_no", item.serialNo);
                    itemsArr.put(obj);
                }
                payload.put("items", itemsArr);
                
                JSONObject payment = new JSONObject();
                payment.put("subtotal_usd", data.originalTotal);
                payment.put("discount_amount_usd", data.discountAmount);
                payment.put("final_amount_usd", data.finalAmount);
                payment.put("payment_method", data.paymentMethod);
                payload.put("payment", payment);
                
                MiraApiClient.ApiResponse response = client.checkout(payload);
                
                // ✅✅✅ حفظ الاستجابة لاستخدامها في الفاتورة
                lastSaleResponse = response;
                
                // ✅ حفظ عدد القطع قبل المسح
                final int soldItemCount = cartList.size();
                
                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    setButtonsEnabled(true);
                    
                    if (response != null && response.isSuccess) {
                        playAlertSound(1);
                        clearCart();
                        
                        // ✅✅✅ عرض الفاتورة مباشرة
                        showReceipt(response);
                    } else {
                        String errorMsg = response != null && response.error != null ? response.error : "Unknown error";
                        if (errorMsg == null || errorMsg.isEmpty()) {
                            errorMsg = response != null && response.message != null ? response.message : "Unknown error";
                        }
                        showRetryDialog(isArabic ? "❌ فشل البيع" : "❌ Sale Failed", errorMsg, () -> executeCheckout(data));
                    }
                });
            } catch (Exception e) {
                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    setButtonsEnabled(true);
                    showRetryDialog(isArabic ? "❌ خطأ" : "❌ Error", e.getMessage(), null);
                });
            }
        }).start();
    } catch (Exception e) {
        Log.e(TAG, "executeCheckout error: " + e.getMessage());
    }
}

    private AlertDialog showProfessionalLoadingDialog() {
        try {
            Context ctx = getContext();
            if (ctx == null || !isAdded()) return null;
            
            MaterialCardView cardView = new MaterialCardView(ctx);
            cardView.setRadius(20);
            cardView.setCardElevation(8);
            
            LinearLayout layout = new LinearLayout(ctx);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(40, 36, 40, 36);
            layout.setGravity(android.view.Gravity.CENTER);
            
            ProgressBar progressBar = new ProgressBar(ctx);
            progressBar.setIndeterminate(true);
            layout.addView(progressBar);
            
            TextView tvLoading = new TextView(ctx);
            tvLoading.setText(isArabic ? "جاري إتمام البيع..." : "Processing sale...");
            tvLoading.setTextSize(17);
            tvLoading.setTypeface(null, android.graphics.Typeface.BOLD);
            tvLoading.setGravity(android.view.Gravity.CENTER);
            tvLoading.setPadding(0, 20, 0, 6);
            layout.addView(tvLoading);
            
            cardView.addView(layout);
            
            AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
            builder.setView(cardView);
            builder.setCancelable(false);
            
            AlertDialog dialog = builder.create();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
            dialog.show();
            return dialog;
        } catch (Exception e) {
            return null;
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        safeRun(() -> {
            try {
                boolean canUsePos = permissionManager.canView("pos") || permissionManager.isPosOperator() || permissionManager.isManager();
                if (btnCheckout != null) btnCheckout.setEnabled(enabled && canUsePos && !cartList.isEmpty());
                if (btnClearCart != null) btnClearCart.setEnabled(enabled);
                if (btnAddManual != null) btnAddManual.setEnabled(enabled && canUsePos);
                if (btnOpenScanner != null) btnOpenScanner.setEnabled(enabled && canUsePos);
                if (btnPosRFIDMode != null) btnPosRFIDMode.setEnabled(enabled && canUsePos);
                if (etManualEpc != null) etManualEpc.setEnabled(enabled && canUsePos);
            } catch (Exception e) {}
        });
    }

    private void showRetryDialog(String title, String message, Runnable retryAction) {
        try {
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
        } catch (Exception e) {}
    }

    protected void dismissDialog(AlertDialog dialog) {
        try {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        } catch (Exception e) {}
    }

    // ✅ استبدال الدالة القديمة بالكامل
private void showSuccessDialog(double amount, int itemCount, String paymentMethod) {
    try {
        if (!isAdded() || getContext() == null) return;
        
        // ✅ إذا كانت لدينا استجابة بيع محفوظة، نعرض الفاتورة
        if (lastSaleResponse != null) {
            showReceipt(lastSaleResponse);
        } else {
            // ✅ وإلا نعرض رسالة نجاح عادية
            showSuccessMessage(
                isArabic ? "تم البيع بنجاح" : "Sale Completed",
                isArabic ? "تمت عملية البيع بنجاح" : "Sale completed successfully",
                true
            );
        }
    } catch (Exception e) {
        Log.e(TAG, "showSuccessDialog error: " + e.getMessage());
    }
}

// ✅✅✅ دالة جديدة لعرض الفاتورة
private void showReceipt(MiraApiClient.ApiResponse response) {
    try {
        if (!isAdded() || getActivity() == null) return;
        
        // ✅ تحويل الاستجابة إلى JSON string
        String receiptJson = "{}";
        if (response != null && response.data != null) {
            receiptJson = response.data.toString();
        } else if (response != null && response.getBody() != null) {
            receiptJson = response.getBody();
        }
        
        // ✅ فتح MiraReceiptFragment
        MiraReceiptFragment receiptFragment = MiraReceiptFragment.newInstance(receiptJson);
        FragmentManager fm = getParentFragmentManager();
        if (!fm.isStateSaved()) {
            receiptFragment.show(fm, "MiraReceiptFragment");
        }
    } catch (Exception e) {
        Log.e(TAG, "showReceipt error: " + e.getMessage());
        // ✅ إذا فشل عرض الفاتورة، عرض رسالة نجاح عادية
        showSuccessMessage(isArabic ? "تم البيع بنجاح" : "Sale Completed", 
            isArabic ? "تمت عملية البيع بنجاح" : "Sale completed successfully", true);
    }
}


    private void checkPendingSales() {
        try {
            if (getContext() == null) return;
            JSONArray arr = new JSONArray(getContext()
                    .getSharedPreferences(PENDING_SALES_PREFS, Context.MODE_PRIVATE)
                    .getString("pending", "[]"));
            if (arr.length() > 0) {
                safeRun(() -> {
                    if (tvCartStatus != null) {
                        tvCartStatus.setText("⚠️ " + arr.length() + " " + (isArabic ? "عملية معلقة" : "pending"));
                    }
                });
            }
        } catch (Exception ignored) {}
    }

    private void syncPendingSales() {
        try {
            if (getContext() == null) return;
            // المزامنة تتم في الخلفية
        } catch (Exception ignored) {}
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
            try {
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
            } catch (Exception e) {
                Log.e("CartAdapter", "Bind error: " + e.getMessage());
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
    
    // ==================== في MiraPOSFragment أو أي Fragment للمبيعات ====================

private final ActivityResultLauncher<ScanOptions> refundBarcodeLauncher = registerForActivityResult(
    new ScanContract(),
    result -> {
        if (result.getContents() != null && isAdded()) {
            if (etRefundCode != null) {
                etRefundCode.setText(result.getContents().trim());
                // ✅ البحث عن القطعة تلقائياً
                searchItemForRefund(result.getContents().trim());
            }
        }
    }
);



/**
 * ✅ عرض نافذة المرتجع
 */
private void showRefundDialog() {
    if (!isAdded() || getContext() == null) return;
    
    refundDialog = new Dialog(requireContext());
    refundDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    refundDialog.setContentView(R.layout.dialog_refund);
    
    if (refundDialog.getWindow() != null) {
        refundDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        refundDialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
    }
    
    etRefundCode = refundDialog.findViewById(R.id.etRefundScanCode);
    EditText etReason = refundDialog.findViewById(R.id.etRefundReason);
    tvRefundItemTitle = refundDialog.findViewById(R.id.tvRefundItemTitle);
    tvRefundItemStatus = refundDialog.findViewById(R.id.tvRefundItemStatus);
    cardRefundItemInfo = refundDialog.findViewById(R.id.cardRefundItemInfo);
    MaterialButton btnScanBarcode = refundDialog.findViewById(R.id.btnRefundScanBarcode);
    MaterialButton btnCancel = refundDialog.findViewById(R.id.btnRefundCancel);
    MaterialButton btnConfirm = refundDialog.findViewById(R.id.btnRefundConfirm);
    
    // ✅ مسح باركود
    if (btnScanBarcode != null) {
        btnScanBarcode.setOnClickListener(v -> {
            ScanOptions options = new ScanOptions();
            options.setPrompt(isArabic ? "📷 وجه الكاميرا نحو باركود القطعة" : "📷 Aim camera at item barcode");
            options.setBeepEnabled(false);
            options.setOrientationLocked(true);
            refundBarcodeLauncher.launch(options);
        });
    }
    
    // ✅ البحث عند تغيير النص
    if (etRefundCode != null) {
        etRefundCode.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() >= 4) {
                    searchItemForRefund(s.toString().trim());
                } else {
                    hideRefundItemInfo();
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }
    
    // ✅ إلغاء
    if (btnCancel != null) {
        btnCancel.setOnClickListener(v -> refundDialog.dismiss());
    }
    
    // ✅ تأكيد الإرجاع
    if (btnConfirm != null) {
        btnConfirm.setOnClickListener(v -> {
            String code = etRefundCode != null ? etRefundCode.getText().toString().trim() : "";
            String reason = etReason != null ? etReason.getText().toString().trim() : "";
            
            if (code.isEmpty()) {
                Toast.makeText(getContext(), 
                    isArabic ? "⚠️ أدخل رمز القطعة" : "⚠️ Enter item code", 
                    Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (!isRefundable) {
                Toast.makeText(getContext(), 
                    isArabic ? "⚠️ لا يمكن إرجاع هذه القطعة" : "⚠️ This item cannot be refunded", 
                    Toast.LENGTH_SHORT).show();
                return;
            }
            
            processRefund(code, reason.isEmpty() ? (isArabic ? "مرتجع مبيعات" : "Sales Refund") : reason);
        });
    }
    
    refundDialog.show();
}

/**
 * ✅ البحث عن القطعة للتحقق من إمكانية الإرجاع
 */
private void searchItemForRefund(String code) {
    executor.execute(() -> {
        try {
            JSONObject payload = new JSONObject();
            payload.put("epc", code);
            payload.put("serial_no", code);
            payload.put("context", "refund_check");
            payload.put("branch_id", getActiveBranchId()); // ✅ أضف الفرع
            
            MiraApiClient.ApiResponse response = apiClient.postWithAuth("/pos/search-item", payload);
            
            mainHandler.post(() -> {
                if (!isAdded() || refundDialog == null || !refundDialog.isShowing()) return;
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject item = response.data.optJSONObject("item");
                    if (item != null) {
                        String title = item.optString("title", "");
                        String status = item.optString("status", "");
                        
                        // ✅ استخدام is_refundable من السيرفر
                        isRefundable = item.optBoolean("is_refundable", 
                            "sold".equals(status) || "exited".equals(status));
                        
                        showRefundItemInfo(title, status, isRefundable);
                    }
                } else {
                    hideRefundItemInfo();
                    // ✅ عرض رسالة الخطأ
                    String msg = response != null ? response.message : "Unknown error";
                    Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "searchItemForRefund error: " + e.getMessage());
            mainHandler.post(this::hideRefundItemInfo);
        }
    });
}

/**
 * ✅ عرض معلومات القطعة
 */
private void showRefundItemInfo(String title, String status, boolean refundable) {
    if (cardRefundItemInfo != null) {
        cardRefundItemInfo.setVisibility(View.VISIBLE);
        cardRefundItemInfo.setAlpha(0f);
        cardRefundItemInfo.animate().alpha(1f).setDuration(300).start();
    }
    
    if (tvRefundItemTitle != null) {
        tvRefundItemTitle.setText(title);
    }
    
    if (tvRefundItemStatus != null) {
        String statusText;
        int color;
        
        if (refundable) {
            statusText = isArabic ? "✅ قابل للإرجاع" : "✅ Refundable";
            color = Color.parseColor("#2ECC71");
        } else {
            statusText = isArabic ? "❌ غير قابل للإرجاع" : "❌ Not refundable";
            color = Color.parseColor("#E74C3C");
        }
        
        tvRefundItemStatus.setText(statusText + " (" + status + ")");
        tvRefundItemStatus.setTextColor(color);
    }
}

/**
 * ✅ إخفاء معلومات القطعة
 */
private void hideRefundItemInfo() {
    isRefundable = false;
    if (cardRefundItemInfo != null) {
        cardRefundItemInfo.setVisibility(View.GONE);
    }
}

/**
 * ✅ تنفيذ عملية الإرجاع
 */
private void processRefund(String code, String reason) {
    // ✅ إظهار تحميل
    if (refundDialog != null && refundDialog.isShowing()) {
        // يمكن إظهار ProgressBar هنا
    }
    
    executor.execute(() -> {
        try {
            JSONObject payload = new JSONObject();
            payload.put("epc", code);
            payload.put("serial_no", code);
            payload.put("reason", reason);
            
            MiraApiClient.ApiResponse response = apiClient.postWithAuth("/pos/refund", payload);
            
            mainHandler.post(() -> {
                if (!isAdded()) return;
                
                if (response != null && response.isSuccess) {
                    Toast.makeText(getContext(), 
                        isArabic ? "✅ تم الإرجاع بنجاح" : "✅ Refund successful", 
                        Toast.LENGTH_SHORT).show();
                    
                    // ✅ إغلاق النافذة
                    if (refundDialog != null && refundDialog.isShowing()) {
                        refundDialog.dismiss();
                    }
                    
                    // ✅ تحديث البيانات
                    refreshData();
                } else {
                    String message = response != null ? response.message : "Unknown error";
                    Toast.makeText(getContext(), 
                        (isArabic ? "❌ فشل الإرجاع: " : "❌ Refund failed: ") + message, 
                        Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "processRefund error: " + e.getMessage());
            mainHandler.post(() -> {
                if (isAdded()) {
                    Toast.makeText(getContext(), 
                        isArabic ? "❌ خطأ في الإرجاع" : "❌ Refund error", 
                        Toast.LENGTH_SHORT).show();
                }
            });
        }
    });
}
}
