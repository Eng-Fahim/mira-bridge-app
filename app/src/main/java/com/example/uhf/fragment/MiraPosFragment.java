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
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.os.Handler;
import android.os.Looper;

import androidx.activity.result.ActivityResultLauncher;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;  // ✅ أضف هذا الاستيراد
import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.dialog.POSCheckoutDialog;
import com.example.uhf.dialog.RefundDialog;
import com.example.uhf.manager.SmartScaleManager;
import com.example.uhf.manager.POSManager;
import com.example.uhf.manager.PendingSalesManager;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.adapter.CartAdapter;
import com.example.uhf.model.CartItem;
import com.example.uhf.tools.UIHelper;
import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.fragment.MiraReceiptFragment;
import com.example.uhf.manager.MiraLocalItemCache;
import com.example.uhf.manager.MiraItemSyncManager;
import com.example.uhf.utils.ScaleSimulationHelper;

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
    
    // ✅ المتغيرات
    private boolean isSyncing = false;
    private boolean isCheckoutDialogShowing = false;
    private AlertDialog searchLoadingDialog;
    private SessionManager sessionManager;
    private SmartScaleManager scaleManager;
    private ScaleSimulationHelper simulationHelper;
    private POSManager posManager;
    private PendingSalesManager pendingSalesManager;
    private RefundDialog refundDialog;
    
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
    private Button btnOpenScanner, btnPosRFIDMode, btnPosRefund;
    private RecyclerView rvCartItems;
    private View layoutCartEmptyState;

    private boolean isRFIDModeActive = false;
    private boolean isArabic;
    private MiraApiClient.ApiResponse lastSaleResponse = null;
    private boolean isRefundable = false;
    private double liveScaleWeight = 0.0;

    private final List<CartItem> cartList = new ArrayList<>();
    private final Set<String> scannedEpcs = new HashSet<>();
    private CartAdapter cartAdapter;

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
            sessionManager = new SessionManager(ctx);
            posManager = new POSManager(ctx);
            pendingSalesManager = new PendingSalesManager(ctx);
            
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
            updateCartSummary();
        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error: " + e.getMessage(), e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setActivePOSFragment(this);
            }
            if (scaleManager != null) {
                scaleManager.connect();
            }
            if (tvCartStatus != null && cartList.isEmpty()) {
                tvCartStatus.setText(isArabic ? getString(R.string.pos_ready_scan) : "Ready to scan...");
            }
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
            if (rfidManager != null) rfidManager.setListener(null);
            if (scaleManager != null) scaleManager.cleanup();
            if (simulationHelper != null) simulationHelper.cleanup();
        } catch (Exception e) {
            Log.e(TAG, "onDestroyView error: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            stopRFIDScan();
            if (scaleManager != null) scaleManager.cleanup();
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
        
        // ✅ مستمع الميزان
        if (tvScaleStatus != null) {
            tvScaleStatus.setOnClickListener(v -> {
                if (scaleManager != null) scaleManager.showSettingsDialog();
            });
        }
        
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
            scaleManager = new SmartScaleManager(requireContext(), new SmartScaleManager.ScaleCallback() {
                @Override
                public void onWeightReceived(double weight) {
                    liveScaleWeight = weight;
                    safeRun(() -> {
                        if (tvScaleStatus != null) {
                            tvScaleStatus.setText(String.format(Locale.US, "⚖️ %.2fg", weight));
                            tvScaleStatus.setTextColor(Color.parseColor("#2ECC71"));
                        }
                        updateCartItemsScaleWeight(weight);
                    });
                }

                @Override
                public void onScaleConnected(String name) {
                    safeRun(() -> {
                        if (tvScaleStatus != null) {
                            tvScaleStatus.setText("⚖️ " + name);
                            tvScaleStatus.setTextColor(Color.parseColor("#2ECC71"));
                        }
                        showToast(isArabic ? "✅ تم الاتصال: " + name : "✅ Connected: " + name);
                    });
                }

                @Override
                public void onScaleDisconnected() {
                    liveScaleWeight = 0.0;
                    safeRun(() -> {
                        if (tvScaleStatus != null) {
                            tvScaleStatus.setText(isArabic ? "⚖️ غير متصل" : "⚖️ Offline");
                            tvScaleStatus.setTextColor(Color.parseColor("#E74C3C"));
                        }
                    });
                }

                @Override
                public void onScaleError(String error) {
                    Log.e(TAG, "Scale Error: " + error);
                }
            });

            simulationHelper = new ScaleSimulationHelper(requireContext(), new ScaleSimulationHelper.SimulationCallback() {
                @Override
                public void onWeightReceived(double weight, String label) {
                    safeRun(() -> {
                        liveScaleWeight = weight;
                        if (tvScaleStatus != null) {
                            tvScaleStatus.setText(String.format(Locale.US, "⚖️ %.2fg (محاكاة)", weight));
                            tvScaleStatus.setTextColor(Color.parseColor("#2ECC71"));
                        }
                        updateCartItemsScaleWeight(weight);
                        showToast("📦 " + label + ": " + weight + "g");
                    });
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "initSmartScale error: " + e.getMessage());
        }
    }

    private void updateCartItemsScaleWeight(double currentScaleWeight) {
        if (cartList.isEmpty() || currentScaleWeight <= 0) return;
        
        CartItem topItem = cartList.get(0);
        if (topItem.scaleWeight != currentScaleWeight) {
            topItem.scaleWeight = currentScaleWeight;
            if (priceManager != null && topItem.weight > 0) {
                topItem.price = priceManager.calculateGoldPrice(getKaratNumber(topItem.karat), currentScaleWeight);
            }
            if (cartAdapter != null) {
                cartAdapter.notifyItemChanged(0);
            }
            updateCartSummary();
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
            showSearchLoadingDialog(cleanCode);
            if (tvCartStatus != null) tvCartStatus.setText(isArabic ? "🔍 جاري البحث..." : "🔍 Searching...");
        });
        
        new Thread(() -> {
            try {
                Context context = getContext();
                if (context == null) return;
                
                MiraItemSyncManager syncManager = MiraItemSyncManager.getInstance(context);
                final int branchId = getActiveBranchId();
                
                syncManager.findItem(cleanCode, branchId, new MiraItemSyncManager.ItemLookupCallback() {
                    @Override
                    public void onItemFound(JSONObject item, boolean fromServer) {
                        safeRun(() -> {
                            dismissSearchLoadingDialog();
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

    private void parseItemResponse(String code, JSONObject data) {
        try {
            JSONObject item = data.has("item") ? data.optJSONObject("item") : data;
            if (item == null) {
                handleItemNotFound(code);
                return;
            }
            
            String vaultStatus = item.optString("status", item.optString("vault_status", "")).toLowerCase();
            if ("sold".equals(vaultStatus) || "exited".equals(vaultStatus)) {
                safeRun(() -> {
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

    private boolean isItemAlreadyInCart(String epc, String serialNo, String gtin) {
        synchronized (cartList) {
            for (CartItem item : cartList) {
                if (item.epc != null && epc != null && item.epc.equalsIgnoreCase(epc)) return true;
                if (item.serialNo != null && serialNo != null && !serialNo.isEmpty() 
                    && item.serialNo.equalsIgnoreCase(serialNo)) return true;
                if (item.gtin != null && gtin != null && !gtin.isEmpty() 
                    && item.gtin.equalsIgnoreCase(gtin)) return true;
            }
        }
        return false;
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
                posManager.executeCheckout(cartList, data, getActiveBranchId(), new POSManager.CheckoutCallback() {
                    @Override
                    public void onSuccess(MiraApiClient.ApiResponse response) {
                        safeRun(() -> {
                            removeSoldItemsFromCache();
                            playAlertSound(1);
                            clearCart();
                            lastSaleResponse = response;
                            showReceipt(response);
                            notifyDashboardAndInventoryUpdate("sale");
                        });
                    }

                    @Override
                    public void onOfflineSuccess(String saleId) {
                        safeRun(() -> {
                            removeSoldItemsFromCache();
                            playAlertSound(1);
                            clearCart();
                            showOfflineSaleSuccess(saleId);
                            notifyDashboardAndInventoryUpdate("offline_sale");
                        });
                    }

                    @Override
                    public void onError(String error) {
                        safeRun(() -> {
                            showRetryDialog(
                                isArabic ? "❌ فشل البيع" : "❌ Sale Failed",
                                error,
                                null
                            );
                        });
                    }
                });
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

    private void showReceipt(MiraApiClient.ApiResponse response) {
        try {
            if (!isAdded() || getActivity() == null) return;
            
            String receiptJson = "{}";
            if (response != null && response.data != null) {
                receiptJson = response.data.toString();
            } else if (response != null && response.getBody() != null) {
                receiptJson = response.getBody();
            }
            
            MiraReceiptFragment receiptFragment = MiraReceiptFragment.newInstance(receiptJson);
            FragmentManager fm = getParentFragmentManager();
            if (!fm.isStateSaved()) {
                receiptFragment.show(fm, "MiraReceiptFragment");
            }
        } catch (Exception e) {
            Log.e(TAG, "showReceipt error: " + e.getMessage());
        }
    }

    private void showOfflineSaleSuccess(String saleId) {
        safeRun(() -> {
            showSuccessMessage(
                isArabic ? "✅ تم البيع (أوفلاين)" : "✅ Sale Completed (Offline)",
                isArabic ? "تم حفظ البيع محلياً وسيتم المزامنة لاحقاً" : "Sale saved locally, will sync later",
                true
            );
        });
    }

    private void showRetryDialog(String title, String message, Runnable retryAction) {
        try {
            Context ctx = getContext();
            if (ctx == null || !isAdded()) return;
            
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

    private void showRefundDialog() {
        if (!isAdded() || getContext() == null) return;
        
        try {
            refundDialog = new RefundDialog(requireContext(), new RefundDialog.RefundCallback() {
                @Override
                public void onRefundConfirm(String code, String reason) {
                    processRefund(code, reason);
                }

                @Override
                public void onBarcodeScan() {
                    ScanOptions options = new ScanOptions();
                    options.setPrompt(isArabic ? "📷 وجه الكاميرا نحو باركود القطعة" : "📷 Aim camera at item barcode");
                    options.setBeepEnabled(false);
                    options.setOrientationLocked(true);
                    refundBarcodeLauncher.launch(options);
                }
            });
            refundDialog.show();
        } catch (Exception e) {
            Log.e(TAG, "showRefundDialog error: " + e.getMessage());
        }
    }

    private void processRefund(String code, String reason) {
        final AlertDialog loadingDialog = showProfessionalLoadingDialog();
        
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("epc", code);
                payload.put("serial_no", code);
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
                        notifyDashboardAndInventoryUpdate("refund");
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

    private void notifyDashboardAndInventoryUpdate(String source) {
        try {
            if (!isAdded() || getContext() == null) return;
            
            Context ctx = getContext();
            int branchId = getActiveBranchId();
            long timestamp = System.currentTimeMillis();
            
            Intent salesIntent = new Intent("com.example.uhf.REFRESH_SALES");
            salesIntent.setPackage(ctx.getPackageName());
            salesIntent.putExtra("source", source);
            salesIntent.putExtra("branch_id", branchId);
            salesIntent.putExtra("timestamp", timestamp);
            ctx.sendBroadcast(salesIntent);
            
            Intent inventoryIntent = new Intent("com.example.uhf.REFRESH_INVENTORY");
            inventoryIntent.setPackage(ctx.getPackageName());
            inventoryIntent.putExtra("source", source);
            inventoryIntent.putExtra("branch_id", branchId);
            inventoryIntent.putExtra("timestamp", timestamp);
            ctx.sendBroadcast(inventoryIntent);
            
            Intent dashboardIntent = new Intent("com.example.uhf.REFRESH_DASHBOARD");
            dashboardIntent.setPackage(ctx.getPackageName());
            dashboardIntent.putExtra("source", source);
            dashboardIntent.putExtra("branch_id", branchId);
            dashboardIntent.putExtra("timestamp", timestamp);
            ctx.sendBroadcast(dashboardIntent);
            
        } catch (Exception e) {
            Log.e(TAG, "notifyDashboardAndInventoryUpdate error: " + e.getMessage());
        }
    }

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

    private final ActivityResultLauncher<ScanOptions> refundBarcodeLauncher = registerForActivityResult(
        new ScanContract(),
        result -> {
            if (result.getContents() != null && isAdded()) {
                if (refundDialog != null) {
                    refundDialog.showRefundItemInfo(
                        result.getContents(),
                        isArabic ? "✅ تم مسح الباركود" : "✅ Barcode scanned"
                    );
                }
            }
        }
    );
}
