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

import android.content.Intent;  // ✅ أضف هذا السطر

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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;  // ✅ أضف هذا السطر
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

// ✅ أضف هذه الاستيرادات في أعلى الملف
import com.example.uhf.manager.MiraLocalItemCache;
import com.example.uhf.manager.MiraItemSyncManager;


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
    private SessionManager sessionManager;  // ✅ أضف هذا السطر
    
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
            sessionManager = new SessionManager(ctx);  // ✅ أضف هذا السطر
            
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
    try {
        if (!isAdded() || getActivity() == null) return;
        
        // ✅ الطريقة 1: استخدام BroadcastReceiver
        Intent refreshIntent = new Intent("com.example.uhf.REFRESH_DASHBOARD");
        refreshIntent.putExtra("source", "refund");
        refreshIntent.putExtra("branch_id", getActiveBranchId());
        refreshIntent.putExtra("timestamp", System.currentTimeMillis());
        getActivity().sendBroadcast(refreshIntent);
        
        // ✅ الطريقة 2: استخدام LocalBroadcastManager إذا كان متاحاً
        try {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
                .sendBroadcast(refreshIntent);
        } catch (Exception e) {
            Log.d(TAG, "LocalBroadcast not available: " + e.getMessage());
        }
        
        // ✅ الطريقة 3: استخدام SharedPreferences كإشارة
        Context ctx = getContext();
        if (ctx != null) {
            SharedPreferences prefs = ctx.getSharedPreferences("app_refresh_flags", Context.MODE_PRIVATE);
            prefs.edit()
                .putLong("last_data_change", System.currentTimeMillis())
                .putString("last_change_type", "refund")
                .putInt("last_change_branch_id", getActiveBranchId())
                .apply();
        }
        
        Log.d(TAG, "✅ Dashboard refresh triggered after refund");
        
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
            // ✅ مزامنة القطع إذا مر أكثر من ساعة
        MiraItemSyncManager syncManager = MiraItemSyncManager.getInstance(requireContext());
        long lastSync = MiraLocalItemCache.getInstance(requireContext()).getLastSyncTime();
        
        if (System.currentTimeMillis() - lastSync > 3600000) { // ساعة
            syncManager.syncAllItems(new MiraItemSyncManager.SyncCallback() {
                @Override
                public void onSyncComplete(int itemCount) {
                    Log.d(TAG, "✅ Synced " + itemCount + " items for offline use");
                }
                
                @Override
                public void onSyncError(String error) {
                    Log.e(TAG, "Sync error: " + error);
                }
            });
        }
        
       // ✅ بدلاً من syncPendingSales() التلقائية
        // نعرض إشعار للمستخدم
        checkAndPromptPendingSales();
        
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

    /**
 * ✅ البحث عن قطعة - يدعم الأوفلاين
 */
private void processRingEpc(final String code, final String source) {
    if (code == null || code.trim().isEmpty() || !isAdded()) return;
    final String cleanCode = code.trim();
     // ✅✅✅ فحص إضافي: القطعة مباعة محلياً؟
    if (isItemSoldLocally(cleanCode)) {
        safeRun(() -> {
            dismissSearchLoadingDialog();
            showSuccessMessage(
                isArabic ? "❌ قطعة مباعة محلياً" : "❌ Item Sold Locally",
                isArabic ? "هذه القطعة تم بيعها محلياً ولم تتم مزامنتها بعد.\nيرجى المزامنة أولاً قبل إعادة البيع." : 
                           "This item was sold locally and hasn't been synced yet.\nPlease sync first before re-selling.",
                false
            );
            playAlertSound(2);
        });
        synchronized (pendingEpcs) {
            pendingEpcs.remove(cleanCode);
        }
        return;
    }
    
    
    synchronized (pendingEpcs) {
        if (pendingEpcs.contains(cleanCode)) return;
        pendingEpcs.add(cleanCode);
    }
    
    playAlertSound(1);
    
    safeRun(() -> {
        showSearchLoadingDialog(cleanCode);
        if (tvCartStatus != null) tvCartStatus.setText(isArabic ? "🔍 جاري البحث..." : "🔍 Searching...");
    });
    
    new Thread(() -> {
        try {
            Context context = getContext();
            if (context == null) return;
            
            MiraItemSyncManager syncManager = MiraItemSyncManager.getInstance(context);
            final int branchId = getActiveBranchId();  // ✅ جلب الفرع الحالي
            
            // ✅✅✅ استخدام الدالة الجديدة مع branchId
            syncManager.findItem(cleanCode, branchId, new MiraItemSyncManager.ItemLookupCallback() {
                @Override
                public void onItemFound(JSONObject item, boolean fromServer) {
                    safeRun(() -> {
                        dismissSearchLoadingDialog();
                        
                        // ✅ تحقق إضافي من branch_id
                        int itemBranchId = item.optInt("branch_id", -1);
                        if (itemBranchId > 0 && itemBranchId != branchId) {
                            showSuccessMessage(
                                isArabic ? "❌ قطعة في فرع آخر" : "❌ Item in Another Branch",
                                isArabic ? "هذه القطعة موجودة في فرع مختلف ولا يمكن بيعها من هنا" : 
                                           "This item is in a different branch and cannot be sold here",
                                false
                            );
                            playAlertSound(2);
                            return;
                        }
                        
                        if (!fromServer) {
                            showToast(isArabic ? "📦 تم العثور عليه محلياً" : "📦 Found locally");
                        }
                        
                        parseItemResponse(cleanCode, item);
                    });
                }
                
                @Override
                public void onItemNotFound() {
                    safeRun(() -> {
                        dismissSearchLoadingDialog();
                        handleItemNotFound(cleanCode);
                    });
                }
            });
        } catch (Exception e) {
            safeRun(() -> {
                dismissSearchLoadingDialog();
                handleItemNotFound(cleanCode);
            });
        } finally {
            synchronized (pendingEpcs) {
                pendingEpcs.remove(cleanCode);
            }
        }
    }).start();
}

/**
 * ✅✅✅ عرض تفاصيل العمليات المعلقة قبل المزامنة
 */
private void showPendingSalesDetailsBeforeSync() {
    try {
        Context ctx = getContext();
        if (ctx == null) return;
        
        int currentBranchId = getActiveBranchId();
        String prefsName = "pos_offline_sales_branch_" + currentBranchId;
        SharedPreferences prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        String existingJson = prefs.getString("pending_sales", "[]");
        JSONArray arr = new JSONArray(existingJson);
        
        if (arr.length() == 0) {
            showToast(isArabic ? "📋 لا توجد عمليات معلقة" : "📋 No pending sales");
            return;
        }
        
        StringBuilder details = new StringBuilder();
        details.append(isArabic ? "📋 العمليات المعلقة:\n\n" : "📋 Pending Sales:\n\n");
        
        for (int i = 0; i < arr.length(); i++) {
            JSONObject sale = arr.optJSONObject(i);
            if (sale == null) continue;
            
            String saleId = sale.optString("sale_id", "");
            String customerName = sale.optString("customer_name", "");
            String username = sale.optString("username", "");
            double amount = sale.optDouble("final_amount", 0);
            int itemCount = sale.optInt("item_count", 0);
            long createdAt = sale.optLong("created_at", 0);
            
            details.append(i + 1).append(". ")
                .append(saleId).append("\n")
                .append("   👤 العميل: ").append(customerName).append("\n")
                .append("   🧑‍💼 البائع: ").append(username).append("\n")
                .append("   💰 المبلغ: ").append(priceManager.formatPriceDefault(amount)).append("\n")
                .append("   📦 القطع: ").append(itemCount).append("\n\n");
        }
        
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "📋 تفاصيل العمليات قبل المزامنة" : "📋 Pending Sales Details")
            .setMessage(details.toString())
            .setPositiveButton(isArabic ? "✅ مزامنة الآن" : "✅ Sync Now", (dialog, which) -> {
                dialog.dismiss();
                syncPendingSales();
            })
            .setNegativeButton(isArabic ? "⏳ لاحقاً" : "⏳ Later", null)
            .show();
    } catch (Exception e) {
        Log.e(TAG, "showPendingSalesDetailsBeforeSync error: " + e.getMessage());
    }
}

/**
 * ✅ التحقق الشامل من تكرار القطعة في السلة
 * يفحص EPC و Serial و GTIN معاً
 */
private boolean isItemAlreadyInCart(String epc, String serialNo, String gtin) {
    synchronized (cartList) {
        for (CartItem item : cartList) {
            // ✅ فحص EPC
            if (item.epc != null && epc != null && item.epc.equalsIgnoreCase(epc)) {
                return true;
            }
            
            // ✅ فحص Serial Number
            if (item.serialNo != null && serialNo != null && !serialNo.isEmpty() 
                && item.serialNo.equalsIgnoreCase(serialNo)) {
                return true;
            }
            
            // ✅ فحص GTIN
            if (item.gtin != null && gtin != null && !gtin.isEmpty() 
                && item.gtin.equalsIgnoreCase(gtin)) {
                return true;
            }
            
            // ✅ فحص إضافي: أي تطابق جزئي
            if (item.epc != null && epc != null 
                && (item.epc.contains(epc) || epc.contains(item.epc))) {
                return true;
            }
        }
    }
    return false;
}

/**
 * ✅ تحليل استجابة القطعة - معدلة للعمل مع JSONObject مباشرة
 */
private void parseItemResponse(String code, JSONObject data) {
    try {
        JSONObject item;
        if (data.has("item")) {
            item = data.optJSONObject("item");
        } else {
            item = data;
        }
        
        if (item == null) {
            handleItemNotFound(code);
            return;
        }
        
        String vaultStatus = item.optString("status", item.optString("vault_status", "")).toLowerCase();
        
        if ("sold".equals(vaultStatus) || "exited".equals(vaultStatus)) {
            safeRun(() -> {
                dismissSearchLoadingDialog();
                showSuccessMessage(
                    isArabic ? "❌ قطعة مباعة" : "❌ Item Sold",
                    isArabic ? "هذه القطعة تم بيعها بالفعل ولا يمكن بيعها مرة أخرى" : 
                               "This item has already been sold",
                    false
                );
                playAlertSound(2);
            });
            return;
        }
        
        boolean isAvailable = !vaultStatus.equals("sold") && !vaultStatus.equals("reserved");
        String title = item.optString("title", item.optString("item_title", isArabic ? "قطعة" : "Item"));
        String karat = item.optString("karat", item.optString("gold_karat", "21K"));
        double weight = item.optDouble("weight", item.optDouble("gross_weight", item.optDouble("gross_weight_grams", 0)));
        double price = item.optDouble("retail_price", item.optDouble("retail_price_usd", item.optDouble("price", 0)));
        
        if (price <= 0 && weight > 0 && priceManager != null) {
            price = priceManager.calculateGoldPrice(getKaratNumber(karat), weight);
        }
        
        CartItem cartItem = new CartItem(code, title, karat, weight, liveScaleWeight, price, isAvailable, vaultStatus.toUpperCase());
        cartItem.serialNo = item.optString("serial", item.optString("serial_number", ""));
        cartItem.gtin = item.optString("gtin", item.optString("gtin13", ""));
        cartItem.location = item.optString("location", item.optString("current_location", ""));
        cartItem.itemId = item.optInt("id", item.optInt("item_id", 0));
        
        // ✅✅✅ **الفحص الشامل الجديد**
        boolean alreadyInCart = isItemAlreadyInCart(cartItem.epc, cartItem.serialNo, cartItem.gtin);
        
        safeRun(() -> {
            if (alreadyInCart) {
                showToast(isArabic ? "⚠️ هذه القطعة مضافة مسبقاً!" : "⚠️ Item already in cart!");
                playAlertSound(2);
                return;
            }
            
            if (!isAvailable) {
                showSuccessMessage(
                    isArabic ? "❌ قطعة غير متاحة" : "❌ Item Unavailable",
                    isArabic ? "حالة القطعة: " + vaultStatus : "Item status: " + vaultStatus,
                    false
                );
                updateCartSummary();
                return;
            }
            
            // ✅✅✅ إضافة جميع المعرفات إلى scannedEpcs
            scannedEpcs.add(code);
            if (cartItem.serialNo != null && !cartItem.serialNo.isEmpty()) {
                scannedEpcs.add(cartItem.serialNo);
            }
            if (cartItem.gtin != null && !cartItem.gtin.isEmpty()) {
                scannedEpcs.add(cartItem.gtin);
            }
            
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
        
        // ✅ استبدال بـ MaterialAlertDialogBuilder
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(ctx);
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
        
        // ✅ استبدال بـ MaterialAlertDialogBuilder
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(ctx);
        builder.setView(cardView);
        builder.setPositiveButton(isArabic ? "حسناً" : "OK", (d, w) -> d.dismiss());
        builder.setCancelable(false);
        builder.show();
    } catch (Exception e) {
        Log.e(TAG, "showSuccessMessage error: " + e.getMessage());
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
            CartItem item = cartList.get(pos);
            
            // ✅✅✅ إزالة جميع المعرفات
            scannedEpcs.remove(item.epc);
            if (item.serialNo != null && !item.serialNo.isEmpty()) {
                scannedEpcs.remove(item.serialNo);
            }
            if (item.gtin != null && !item.gtin.isEmpty()) {
                scannedEpcs.remove(item.gtin);
            }
            
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

    /**
 * ✅✅✅ تنفيذ البيع - مع دعم كامل للأوفلاين
 */
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
                
                // ✅ بناء payload
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
                
                // ✅✅✅ حفظ البيع محلياً أولاً
                final String localSaleId = saveSaleLocally(payload, data);
                
                // ✅ محاولة الإرسال للسيرفر
                MiraApiClient.ApiResponse response = null;
                boolean isNetworkError = false;
                String errorMsg = "";
                
                try {
                    response = client.checkout(payload);
                    
                    if (response != null) {
                        isNetworkError = response.isNetworkError;
                        errorMsg = response.error != null ? response.error : 
                                  (response.message != null ? response.message : "Unknown error");
                    }
                } catch (Exception e) {
                    // ✅ فشل الاتصال
                    isNetworkError = true;
                    errorMsg = e.getMessage();
                }
                
                final boolean finalIsNetworkError = isNetworkError;
                final boolean finalIsSuccess = response != null && response.isSuccess;
                final String finalErrorMsg = errorMsg;
                final MiraApiClient.ApiResponse finalResponse = response;
                
                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    setButtonsEnabled(true);
                    
                    if (finalIsSuccess) {
    // ✅ نجاح البيع أونلاين
    removeSaleFromLocal(localSaleId);
    removeSoldItemsFromCache(); // 👈 أضف هذا السطر هنا لتحديث الكاش محلياً
    
    playAlertSound(1);
    clearCart();
    showReceipt(finalResponse);
    
} else if (finalIsNetworkError || finalResponse == null) {
    // ✅ حفظ البيع أوفلاين
    removeSoldItemsFromCache(); // 👈 وأضف هذا السطر هنا أيضاً
    
    playAlertSound(1);
    clearCart();
    showOfflineSaleSuccess(localSaleId, data);
}
 else {
                        // ❌ خطأ من السيرفر (ليس أوفلاين) - إعادة محاولة
                        showRetryDialog(
                            isArabic ? "❌ فشل البيع" : "❌ Sale Failed",
                            finalErrorMsg,
                            () -> retrySale(localSaleId, payload)
                        );
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "executeCheckout error: " + e.getMessage(), e);
                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    setButtonsEnabled(true);
                    showRetryDialog(
                        isArabic ? "❌ خطأ" : "❌ Error",
                        e.getMessage(),
                        null
                    );
                });
            }
        }).start();
    } catch (Exception e) {
        Log.e(TAG, "executeCheckout error: " + e.getMessage());
    }
}

// ==================== الدوال المساعدة ====================

/**
 * ✅✅✅ حفظ البيع محلياً
 */
private String saveSaleLocally(JSONObject payload, POSCheckoutDialog.POSCheckoutData data) {
    try {
        Context ctx = getContext();
        if (ctx == null) return "";
        
        String saleId = "SALE_" + System.currentTimeMillis();
        int branchId = getActiveBranchId();
        int userId = sessionManager != null ? sessionManager.getUserId() : 0;
        String username = sessionManager != null ? sessionManager.getUsername() : "";
        
        JSONObject localSale = new JSONObject();
        localSale.put("sale_id", saleId);
        localSale.put("payload", payload);
        localSale.put("customer_name", data.customerName);
        localSale.put("payment_method", data.paymentMethod);
        localSale.put("final_amount", data.finalAmount);
        localSale.put("item_count", cartList.size());
        localSale.put("created_at", System.currentTimeMillis());
        localSale.put("status", "pending");
        
        // ✅✅✅ معلومات الفرع والمستخدم
        localSale.put("branch_id", branchId);
        localSale.put("user_id", userId);
        localSale.put("username", username);
        
        // ✅✅✅ تفاصيل القطع
        JSONArray itemsArray = new JSONArray();
        for (CartItem item : cartList) {
            JSONObject itemObj = new JSONObject();
            itemObj.put("epc", item.epc);
            itemObj.put("title", item.name);
            itemObj.put("price", item.price);
            itemsArray.put(itemObj);
        }
        localSale.put("items", itemsArray);
        
        // ✅✅✅ حالة المزامنة
        localSale.put("sync_status", "pending"); // pending, syncing, synced, failed
        localSale.put("sync_attempts", 0);
        localSale.put("last_sync_attempt", 0);
        localSale.put("last_error", "");
        
        // ✅✅✅ حفظ في ملف منفصل لكل فرع
        String prefsName = "pos_offline_sales_branch_" + branchId;
        SharedPreferences prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        String existingJson = prefs.getString("pending_sales", "[]");
        JSONArray arr = new JSONArray(existingJson);
        
        // إزالة أي عملية قديمة بنفس الـ ID
        for (int i = 0; i < arr.length(); i++) {
            if (arr.optJSONObject(i).optString("sale_id").equals(saleId)) {
                arr.remove(i);
                break;
            }
        }
        
        arr.put(localSale);
        prefs.edit().putString("pending_sales", arr.toString()).apply();
        
        // ✅✅✅ حفظ في السجل العام أيضاً
        saveToSyncLog(saleId, branchId, userId, username, data.finalAmount, cartList.size(), "pending", "تم الحفظ محلياً");
        
        return saleId;
    } catch (Exception e) {
        Log.e(TAG, "saveSaleLocally error: " + e.getMessage());
        return "";
    }
}

/**
 * ✅✅✅ حفظ سجل المزامنة المفصل
 */
private void saveToSyncLog(String saleId, int branchId, int userId, String username, 
                           double amount, int itemCount, String status, String message) {
    try {
        Context ctx = getContext();
        if (ctx == null) return;
        
        String prefsName = "pos_sync_log_branch_" + branchId;
        SharedPreferences prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        String existingJson = prefs.getString("sync_log", "[]");
        JSONArray arr = new JSONArray(existingJson);
        
        JSONObject logEntry = new JSONObject();
        logEntry.put("sale_id", saleId);
        logEntry.put("branch_id", branchId);
        logEntry.put("user_id", userId);
        logEntry.put("username", username);
        logEntry.put("amount", amount);
        logEntry.put("item_count", itemCount);
        logEntry.put("status", status); // pending, syncing, synced, failed
        logEntry.put("message", message);
        logEntry.put("timestamp", System.currentTimeMillis());
        
        arr.put(logEntry);
        
        // ✅ الاحتفاظ بآخر 100 سجل فقط
        while (arr.length() > 100) {
            arr.remove(0);
        }
        
        prefs.edit().putString("sync_log", arr.toString()).apply();
    } catch (Exception e) {
        Log.e(TAG, "saveToSyncLog error: " + e.getMessage());
    }
}
/**
 * ✅✅✅ إزالة البيع من المحلي
 */
private void removeSaleFromLocal(String saleId) {
    try {
        Context ctx = getContext();
        if (ctx == null || saleId == null || saleId.isEmpty()) return;
        
        // ✅ استخدم ملف الفرع الحالي
        int currentBranchId = getActiveBranchId();
        String prefsName = "pos_offline_sales_branch_" + currentBranchId;
        SharedPreferences prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        String existingJson = prefs.getString("pending_sales", "[]");
        JSONArray arr = new JSONArray(existingJson);
        JSONArray newArr = new JSONArray();
        
        for (int i = 0; i < arr.length(); i++) {
            JSONObject sale = arr.optJSONObject(i);
            if (sale != null && !sale.optString("sale_id").equals(saleId)) {
                newArr.put(sale);
            }
        }
        
        prefs.edit().putString("pending_sales", newArr.toString()).apply();
        updatePendingSalesBadge(newArr.length());
        
        Log.d(TAG, "✅ Sale removed from local: " + saleId);
    } catch (Exception e) {
        Log.e(TAG, "removeSaleFromLocal error: " + e.getMessage());
    }
}

/**
 * ✅✅✅ عرض نجاح البيع الأوفلاين
 */
private void showOfflineSaleSuccess(String saleId, POSCheckoutDialog.POSCheckoutData data) {
    try {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return;
        
        String message = isArabic ? 
            "✅ تم البيع وحفظه محلياً!\n\n" +
            "🔑 رقم العملية: " + saleId + "\n" +
            "💰 المبلغ: " + priceManager.formatPriceDefault(data.finalAmount) + "\n" +
            "👤 العميل: " + data.customerName + "\n" +
            "💳 الدفع: " + data.paymentMethod + "\n\n" +
            "📡 سيتم المزامنة تلقائياً عند عودة الاتصال"
            :
            "✅ Sale saved locally!\n\n" +
            "🔑 Sale ID: " + saleId + "\n" +
            "💰 Amount: " + priceManager.formatPriceDefault(data.finalAmount) + "\n" +
            "👤 Customer: " + data.customerName + "\n" +
            "💳 Payment: " + data.paymentMethod + "\n\n" +
            "📡 Will sync automatically when connection returns";
        
        showSuccessMessage(
            isArabic ? "✅ تم البيع (أوفلاين)" : "✅ Sale Completed (Offline)",
            message,
            true
        );
    } catch (Exception e) {
        Log.e(TAG, "showOfflineSaleSuccess error: " + e.getMessage());
    }
}

/**
 * ✅✅✅ إعادة محاولة البيع
 */
private void retrySale(String saleId, JSONObject payload) {
    if (!isAdded()) return;
    
    final AlertDialog loadingDialog = showProfessionalLoadingDialog();
    if (loadingDialog == null) return;
    
    new Thread(() -> {
        try {
            Context ctx = getContext();
            if (ctx == null) return;
            MiraApiClient client = MiraApiClient.getInstance(ctx);
            
            MiraApiClient.ApiResponse response = client.checkout(payload);
            
            safeRun(() -> {
                dismissDialog(loadingDialog);
                
                if (response != null && response.isSuccess) {
                    // ✅ نجاح - إزالة من المحلي
                    removeSaleFromLocal(saleId);
                    playAlertSound(1);
                    showReceipt(response);
                } else {
                    String errorMsg = response != null && response.error != null ? response.error : "Unknown error";
                    showRetryDialog(
                        isArabic ? "❌ فشل البيع" : "❌ Sale Failed",
                        errorMsg,
                        () -> retrySale(saleId, payload)
                    );
                }
            });
        } catch (Exception e) {
            safeRun(() -> {
                dismissDialog(loadingDialog);
                showRetryDialog(
                    isArabic ? "❌ خطأ" : "❌ Error",
                    e.getMessage(),
                    () -> retrySale(saleId, payload)
                );
            });
        }
    }).start();
}


/**
 * ✅✅✅ تحديث عداد المبيعات المعلقة
 */
private void updatePendingSalesBadge(int count) {
    try {
        safeRun(() -> {
            if (tvCartStatus != null) {
                if (count > 0) {
                    String pendingText = isArabic ? 
                        "⚠️ " + count + " عملية بيع معلقة" : 
                        "⚠️ " + count + " pending sales";
                    tvCartStatus.setText(pendingText);
                    tvCartStatus.setTextColor(Color.parseColor("#F39C12"));
                } else {
                    tvCartStatus.setTextColor(Color.parseColor("#2ECC71"));
                    if (cartList.isEmpty()) {
                        tvCartStatus.setText(isArabic ? getString(R.string.pos_ready_scan) : "Ready to scan...");
                    } else {
                        tvCartStatus.setText(isArabic ? "✓ جاهز للبيع" : "✓ Ready for sale");
                    }
                }
            }
        });
    } catch (Exception e) {
        Log.e(TAG, "updatePendingSalesBadge error: " + e.getMessage());
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
        
        // ✅ استبدال بـ MaterialAlertDialogBuilder
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(ctx);
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
        
        // ✅ استبدال بـ MaterialAlertDialogBuilder
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(ctx);
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
        Context ctx = getContext();
        if (ctx == null) return;
        
        int currentBranchId = getActiveBranchId();
        String prefsName = "pos_offline_sales_branch_" + currentBranchId;
        JSONArray arr = new JSONArray(ctx
                .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .getString("pending_sales", "[]"));
            if (arr.length() > 0) {
                safeRun(() -> {
                    if (tvCartStatus != null) {
                        tvCartStatus.setText("⚠️ " + arr.length() + " " + (isArabic ? "عملية معلقة" : "pending"));
                    }
                });
            }
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




// ============================================
// ✅ إصلاح زر الإلغاء في نافذة المرتجع
// ============================================
private void showRefundDialog() {
    if (!isAdded() || getContext() == null) return;
    
    try {
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
        
        // ✅✅✅ إصلاح زر الإلغاء
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> {
                Log.d(TAG, "Cancel button clicked");
                try {
                    if (refundDialog != null && refundDialog.isShowing()) {
                        refundDialog.dismiss();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error dismissing refund dialog: " + e.getMessage());
                }
            });
        }
        
        // ✅✅✅ إضافة مستمع للإغلاق
        refundDialog.setOnDismissListener(dialog -> {
            Log.d(TAG, "Refund dialog dismissed");
            // تنظيف المراجع
            etRefundCode = null;
            tvRefundItemTitle = null;
            tvRefundItemStatus = null;
            cardRefundItemInfo = null;
            isRefundable = false;
        });
        
        // ✅✅✅ إضافة إمكانية الإغلاق بالضغط خارج النافذة
        refundDialog.setCancelable(true);
        refundDialog.setCanceledOnTouchOutside(true);
        
        // ✅ مسح باركود
        if (btnScanBarcode != null) {
            btnScanBarcode.setOnClickListener(v -> {
                try {
                    ScanOptions options = new ScanOptions();
                    options.setPrompt(isArabic ? "📷 وجه الكاميرا نحو باركود القطعة" : "📷 Aim camera at item barcode");
                    options.setBeepEnabled(false);
                    options.setOrientationLocked(true);
                    refundBarcodeLauncher.launch(options);
                } catch (Exception e) {
                    Log.e(TAG, "Barcode scanner error: " + e.getMessage());
                    Toast.makeText(getContext(), 
                        isArabic ? "⚠️ تعذر فتح الماسح" : "⚠️ Unable to open scanner", 
                        Toast.LENGTH_SHORT).show();
                }
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
        
    } catch (Exception e) {
        Log.e(TAG, "showRefundDialog error: " + e.getMessage(), e);
        Toast.makeText(getContext(), 
            isArabic ? "⚠️ خطأ في فتح نافذة المرتجع" : "⚠️ Error opening refund dialog", 
            Toast.LENGTH_SHORT).show();
    }
}


/**
 * ✅ عند عودة الاتصال - عرض إشعار للمزامنة
 */
private void checkAndPromptPendingSales() {
    try {
        Context ctx = getContext();
        if (ctx == null) return;
        
        int currentBranchId = getActiveBranchId();
        String prefsName = "pos_offline_sales_branch_" + currentBranchId;
        SharedPreferences prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        String existingJson = prefs.getString("pending_sales", "[]");
        JSONArray arr = new JSONArray(existingJson);
        
        if (arr.length() > 0) {
            // ✅ عرض إشعار للمستخدم
            showSyncPromptDialog(arr.length());
        }
    } catch (Exception e) {
        Log.e(TAG, "checkPendingSales error: " + e.getMessage());
    }
}

/**
 * ✅✅✅ التحقق من أن القطعة ليست مباعة محلياً
 */
private boolean isItemSoldLocally(String epc) {
    try {
        Context ctx = getContext();
        if (ctx == null) return false;
        
        int currentBranchId = getActiveBranchId();
        String prefsName = "pos_offline_sales_branch_" + currentBranchId;
        SharedPreferences prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        String existingJson = prefs.getString("pending_sales", "[]");
        JSONArray arr = new JSONArray(existingJson);
        
        for (int i = 0; i < arr.length(); i++) {
            JSONObject sale = arr.optJSONObject(i);
            if (sale == null) continue;
            
            // ✅ فحص القطع في كل عملية بيع معلقة
            JSONArray items = sale.optJSONArray("items");
            if (items != null) {
                for (int j = 0; j < items.length(); j++) {
                    JSONObject item = items.optJSONObject(j);
                    if (item != null && epc.equals(item.optString("epc", ""))) {
                        return true; // ✅ القطعة مباعة محلياً
                    }
                }
            }
            
            // ✅ فحص payload أيضاً
            JSONObject payload = sale.optJSONObject("payload");
            if (payload != null) {
                JSONArray payloadItems = payload.optJSONArray("items");
                if (payloadItems != null) {
                    for (int j = 0; j < payloadItems.length(); j++) {
                        JSONObject item = payloadItems.optJSONObject(j);
                        if (item != null && epc.equals(item.optString("epc", ""))) {
                            return true;
                        }
                    }
                }
            }
        }
        
        return false;
    } catch (Exception e) {
        Log.e(TAG, "isItemSoldLocally error: " + e.getMessage());
        return false;
    }
}
/**
 * ✅ عرض نافذة المزامنة
 */
 
private void showSyncPromptDialog(int pendingCount) {
    try {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return;
        
        // ✅ بدلاً من رسالة بسيطة، اعرض التفاصيل
        showPendingSalesDetailsBeforeSync();
    } catch (Exception e) {
        Log.e(TAG, "showSyncPromptDialog error: " + e.getMessage());
    }
}
/**
 * ✅ مزامنة المبيعات المعلقة - يدوية مع تأكيد
 */
/**
 * ✅✅✅ مزامنة المبيعات المعلقة - الفرع الحالي فقط مع تفاصيل كاملة
 */
private void syncPendingSales() {
    try {
        Context ctx = getContext();
        if (ctx == null) return;
        
        int currentBranchId = getActiveBranchId();
        int currentUserId = sessionManager != null ? sessionManager.getUserId() : 0;
        
        // ✅✅✅ استخدام ملف الفرع الحالي فقط
        String prefsName = "pos_offline_sales_branch_" + currentBranchId;
        SharedPreferences prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        String existingJson = prefs.getString("pending_sales", "[]");
        JSONArray arr = new JSONArray(existingJson);
        
        if (arr.length() == 0) {
            updatePendingSalesBadge(0);
            showToast(isArabic ? "✅ لا توجد مبيعات معلقة لهذا الفرع" : "✅ No pending sales for this branch");
            return;
        }
        
        final AlertDialog loadingDialog = showProfessionalLoadingDialog();
        
        new Thread(() -> {
            int synced = 0;
            int failed = 0;
            JSONArray remaining = new JSONArray();
            
            // ✅✅✅ تفاصيل المزامنة
            StringBuilder syncDetails = new StringBuilder();
            syncDetails.append(isArabic ? "📋 تفاصيل المزامنة:\n\n" : "📋 Sync Details:\n\n");
            
            for (int i = 0; i < arr.length(); i++) {
                try {
                    JSONObject sale = arr.optJSONObject(i);
                    if (sale == null) continue;
                    
                    String saleId = sale.optString("sale_id", "Unknown");
                    int saleBranchId = sale.optInt("branch_id", -1);
                    int saleUserId = sale.optInt("user_id", -1);
                    String username = sale.optString("username", "Unknown");
                    double amount = sale.optDouble("final_amount", 0);
                    int itemCount = sale.optInt("item_count", 0);
                    
                    // ✅✅✅ التحقق من أن البيع يخص الفرع الحالي
                    if (saleBranchId != currentBranchId) {
                        // تخطي - ليس لهذا الفرع
                        continue;
                    }
                    
                    // ✅✅✅ تحديث حالة المزامنة
                    sale.put("sync_status", "syncing");
                    sale.put("sync_attempts", sale.optInt("sync_attempts", 0) + 1);
                    sale.put("last_sync_attempt", System.currentTimeMillis());
                    
                    saveToSyncLog(saleId, currentBranchId, saleUserId, username, amount, itemCount, 
                                  "syncing", isArabic ? "جاري المزامنة..." : "Syncing...");
                    
                    JSONObject payload = sale.optJSONObject("payload");
                    if (payload == null) {
                        failed++;
                        sale.put("sync_status", "failed");
                        sale.put("last_error", "Payload missing");
                        remaining.put(sale);
                        saveToSyncLog(saleId, currentBranchId, saleUserId, username, amount, itemCount, 
                                      "failed", isArabic ? "فشل - Payload مفقود" : "Failed - Payload missing");
                        continue;
                    }
                    
                    // ✅✅✅ إضافة branch_id و user_id للـ payload
                    payload.put("branch_id", currentBranchId);
                    payload.put("user_id", saleUserId);
                    
                    MiraApiClient client = MiraApiClient.getInstance(ctx);
                    MiraApiClient.ApiResponse response = client.checkout(payload);
                    
                    if (response != null && response.isSuccess) {
                        synced++;
                        sale.put("sync_status", "synced");
                        sale.put("last_error", "");
                        
                        syncDetails.append("✅ ").append(saleId)
                            .append(" | 💰 ").append(priceManager.formatPriceDefault(amount))
                            .append(" | 👤 ").append(username)
                            .append("\n");
                        
                        saveToSyncLog(saleId, currentBranchId, saleUserId, username, amount, itemCount, 
                                      "synced", isArabic ? "تمت المزامنة بنجاح" : "Synced successfully");
                    } else {
                        failed++;
                        String errorMsg = response != null ? (response.error != null ? response.error : "Unknown error") : "Network error";
                        sale.put("sync_status", "failed");
                        sale.put("last_error", errorMsg);
                        remaining.put(sale);
                        
                        syncDetails.append("❌ ").append(saleId)
                            .append(" | ").append(errorMsg)
                            .append("\n");
                        
                        saveToSyncLog(saleId, currentBranchId, saleUserId, username, amount, itemCount, 
                                      "failed", errorMsg);
                    }
                } catch (Exception e) {
    failed++;
    JSONObject sale = arr.optJSONObject(i);
    if (sale != null) {
        try {
            sale.put("sync_status", "failed");
            sale.put("last_error", e.getMessage());
        } catch (org.json.JSONException jsonException) {
            Log.e(TAG, "JSON error: " + jsonException.getMessage());
        }
        remaining.put(sale);
        
        saveToSyncLog(sale.optString("sale_id", "Unknown"), currentBranchId, 
                      sale.optInt("user_id", -1), sale.optString("username", "Unknown"),
                      sale.optDouble("final_amount", 0), sale.optInt("item_count", 0),
                      "failed", e.getMessage());
    }
}
            }
            
            // ✅✅✅ حفظ المتبقي
            prefs.edit().putString("pending_sales", remaining.toString()).apply();
            
            // ✅✅✅ إضافة ملخص
            syncDetails.append("\n").append(isArabic ? "📊 الملخص:\n" : "📊 Summary:\n")
                .append(isArabic ? "✅ ناجح: " : "✅ Success: ").append(synced).append("\n")
                .append(isArabic ? "❌ فشل: " : "❌ Failed: ").append(failed).append("\n")
                .append(isArabic ? "📦 متبقي: " : "📦 Remaining: ").append(remaining.length()).append("\n");
            
            final int finalSynced = synced;
            final int finalFailed = failed;
            final int finalRemaining = remaining.length();
            final String finalDetails = syncDetails.toString();
            
            safeRun(() -> {
                if (loadingDialog != null) dismissDialog(loadingDialog);
                updatePendingSalesBadge(finalRemaining);
                
                // ✅✅✅ عرض النتيجة المفصلة
                showSuccessMessage(
                    finalSynced > 0 ? "✅ المزامنة" : "❌ المزامنة",
                    finalDetails,
                    finalSynced > 0
                );
            });
        }).start();
        
    } catch (Exception e) {
        Log.e(TAG, "syncPendingSales error: " + e.getMessage());
    }
}

/**
 * ✅✅✅ عرض سجل المزامنة المفصل للفرع الحالي
 */
private void showSyncHistoryLog() {
    try {
        Context ctx = getContext();
        if (ctx == null) return;
        
        int currentBranchId = getActiveBranchId();
        String prefsName = "pos_sync_log_branch_" + currentBranchId;
        SharedPreferences prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        String existingJson = prefs.getString("sync_log", "[]");
        JSONArray arr = new JSONArray(existingJson);
        
        if (arr.length() == 0) {
            showToast(isArabic ? "📋 لا يوجد سجل مزامنة" : "📋 No sync history");
            return;
        }
        
        StringBuilder logText = new StringBuilder();
        logText.append(isArabic ? "📋 سجل المزامنة - الفرع الحالي:\n\n" : "📋 Sync History - Current Branch:\n\n");
        
        for (int i = arr.length() - 1; i >= 0; i--) {
            JSONObject entry = arr.optJSONObject(i);
            if (entry == null) continue;
            
            String saleId = entry.optString("sale_id", "");
            String username = entry.optString("username", "");
            String status = entry.optString("status", "");
            String message = entry.optString("message", "");
            double amount = entry.optDouble("amount", 0);
            long timestamp = entry.optLong("timestamp", 0);
            
            String statusEmoji = "❓";
            switch (status) {
                case "synced": statusEmoji = "✅"; break;
                case "syncing": statusEmoji = "⏳"; break;
                case "failed": statusEmoji = "❌"; break;
                case "pending": statusEmoji = "📦"; break;
            }
            
            logText.append(statusEmoji).append(" ").append(saleId)
                .append(" | 💰 ").append(priceManager.formatPriceDefault(amount))
                .append(" | 👤 ").append(username)
                .append("\n  ").append(message)
                .append("\n\n");
        }
        
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "📋 سجل المزامنة" : "📋 Sync History")
            .setMessage(logText.toString())
            .setPositiveButton(isArabic ? "حسناً" : "OK", null)
            .show();
    } catch (Exception e) {
        Log.e(TAG, "showSyncHistoryLog error: " + e.getMessage());
    }
}
/**
 * ✅ البحث عن القطعة للتحقق من إمكانية الإرجاع
 */
private void searchItemForRefund(String code) {
    if (code == null || code.trim().isEmpty()) return;
    
    executor.execute(() -> {
        try {
            JSONObject payload = new JSONObject();
            payload.put("epc", code);
            payload.put("serial_no", code);
            payload.put("branch_id", getActiveBranchId());
            payload.put("context", "refund_check");

            // الاستعلام من API أو الكاش المحلي
            MiraApiClient client = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse response = client.checkRefundEligibility(payload);

            safeRun(() -> {
                if (response != null && response.isSuccess && response.data != null) {
                    showRefundItemInfo(response.data);
                } else {
                    showRefundItemError(isArabic ? "⚠️ القطعة غير صالحة للإرجاع أو غير مباعة" : "⚠️ Item not eligible for refund");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "searchItemForRefund error: " + e.getMessage());
            safeRun(() -> showRefundItemError(isArabic ? "⚠️ خطأ في البحث عن القطعة" : "⚠️ Error searching item"));
        }
    });
}

private void showRefundItemInfo(JSONObject itemData) {
    try {
        if (cardRefundItemInfo != null) cardRefundItemInfo.setVisibility(View.VISIBLE);
        
        String title = itemData.optString("title", isArabic ? "قطعة ذهبية" : "Gold Item");
        String status = itemData.optString("status", "SOLD");
        double price = itemData.optDouble("sold_price", itemData.optDouble("price", 0.0));

        if (tvRefundItemTitle != null) {
            tvRefundItemTitle.setText(title + " (" + priceManager.formatPriceDefault(price) + ")");
        }
        
        if (tvRefundItemStatus != null) {
            tvRefundItemStatus.setText(isArabic ? "حالة القطعة: مباعة ✅" : "Status: SOLD ✅");
            tvRefundItemStatus.setTextColor(Color.parseColor("#2ECC71"));
        }
        
        isRefundable = true;
    } catch (Exception e) {
        Log.e(TAG, "showRefundItemInfo error: " + e.getMessage());
    }
}

private void hideRefundItemInfo() {
    safeRun(() -> {
        if (cardRefundItemInfo != null) cardRefundItemInfo.setVisibility(View.GONE);
        isRefundable = false;
    });
}

private void showRefundItemError(String message) {
    if (cardRefundItemInfo != null) cardRefundItemInfo.setVisibility(View.VISIBLE);
    if (tvRefundItemTitle != null) tvRefundItemTitle.setText(message);
    if (tvRefundItemStatus != null) {
        tvRefundItemStatus.setText(isArabic ? "غير قابلة للإرجاع ❌" : "Not Refundable ❌");
        tvRefundItemStatus.setTextColor(Color.parseColor("#E74C3C"));
    }
    isRefundable = false;
}

private void processRefund(String code, String reason) {
    final AlertDialog loadingDialog = showProfessionalLoadingDialog();
    
    executor.execute(() -> {
        try {
            JSONObject payload = new JSONObject();
            payload.put("action", "PROCESS_REFUND");
            payload.put("epc", code);
            payload.put("reason", reason);
            payload.put("branch_id", getActiveBranchId());
            payload.put("user_id", sessionManager != null ? sessionManager.getUserId() : 0);

            MiraApiClient client = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse response = client.processRefund(payload);

            safeRun(() -> {
                dismissDialog(loadingDialog);
                if (refundDialog != null && refundDialog.isShowing()) {
                    refundDialog.dismiss();
                }
                
                if (response != null && response.isSuccess) {
                    showSuccessMessage(
                        isArabic ? "✅ تم الإرجاع" : "✅ Refund Successful",
                        isArabic ? "تم إرجاع القطعة بنجاح إلى المخزون" : "Item successfully returned to inventory",
                        true
                    );
                    refreshData(); // تحديث الواجهات والداشبورد
                } else {
                    String err = response != null && response.error != null ? response.error : "Unknown error";
                    showSuccessMessage(
                        isArabic ? "❌ فشل الإرجاع" : "❌ Refund Failed",
                        err,
                        false
                    );
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "processRefund error: " + e.getMessage());
            safeRun(() -> {
                dismissDialog(loadingDialog);
                showSuccessMessage(isArabic ? "❌ خطأ" : "❌ Error", e.getMessage(), false);
            });
        }
    });
}

// ✅ دالة تحديث كاش القطع المباعة محلياً
private void removeSoldItemsFromCache() {
    try {
        Context ctx = getContext();
        if (ctx == null) return;
        MiraLocalItemCache cache = MiraLocalItemCache.getInstance(ctx);
        for (CartItem item : cartList) {
            cache.markItemAsSold(item.epc);
        }
    } catch (Exception e) {
        Log.e(TAG, "removeSoldItemsFromCache error: " + e.getMessage());
    }
}

} // 👈 إغلاق كلاس MiraPosFragment
