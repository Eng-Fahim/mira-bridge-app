package com.example.uhf.fragment;

import com.example.uhf.tools.SessionManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

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
import com.example.uhf.manager.MiraLocalItemCache;
import com.example.uhf.manager.MiraItemSyncManager;
import com.example.uhf.utils.MiraExecutorService;
import com.example.uhf.utils.ScaleSimulationHelper;
import com.example.uhf.viewmodel.MiraPosViewModel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  MiraPosFragment — الإصدار 4.0 (ViewModel-Integrated)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  🎯 الترقية الرئيسية:
 *  - كل بيانات POS انتقلت إلى MiraPosViewModel
 *  - السلة تبقى عبر التنقلات
 *  - RFID/الوزن/حالة الإرجاع محفوظة
 *  - Root View Cache للأداء
 *  - LiveData يُحدّث الواجهة تلقائياً
 *
 *  ✅ محافظ على:
 *  - MiraExecutorService (Singleton)
 *  - MiraBaseFragment (dialogs helpers)
 *  - كل منطق البيع والإرجاع
 *  - SmartScale + Simulation
 *  - Barcode + RFID scanning
 */
public class MiraPosFragment extends MiraBaseFragment {

    private static final String TAG = "MiraPosFragment";

    // ═══════════════════════════════════════════════════════════════════
    // 🎯 ViewModel — المصدر الوحيد للحقيقة
    // ═══════════════════════════════════════════════════════════════════
    private MiraPosViewModel viewModel;

    // ═══════════════════════════════════════════════════════════════════
    // 🖼️ Root View Cache
    // ═══════════════════════════════════════════════════════════════════
    private View rootView;
    private boolean isViewInitialized = false;

    // ==================== المتغيرات المحلية (لا تحتاج حفظ) ====================
    private boolean isSyncing = false;
    private AlertDialog searchLoadingDialog;
    private SessionManager sessionManager;
    private SmartScaleManager scaleManager;
    private ScaleSimulationHelper simulationHelper;
    private POSManager posManager;
    private PendingSalesManager pendingSalesManager;
    private RefundDialog refundDialog;

    // ✅ مدير الخيوط المشترك (Singleton)
    private MiraExecutorService miraExecutor;
    private MiraApiClient apiClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MiraPriceManager priceManager;
    private MiraBranchesManager branchesManager;
    private PermissionManager permissionManager;

    private RFIDManager rfidManager;
    private boolean isRFIDScanActive = false;

    // ✅ Views
    private TextView tvScaleStatus, tvItemCount, tvTotalAmount, tvCartStatus;
    private TextInputLayout tilManualEpc;
    private EditText etManualEpc;
    private Button btnAddManual, btnCheckout, btnClearCart;
    private Button btnOpenScanner, btnPosRFIDMode, btnPosRefund;
    private RecyclerView rvCartItems;
    private View layoutCartEmptyState;

    private CartAdapter cartAdapter;

    // ═══════════════════════════════════════════════════════════════════
    // ⚠️ المتغيرات التالية انتقلت إلى MiraPosViewModel:
    // ---------------------------------------------------------------
    // ❌ private final List<CartItem> cartList
    // ❌ private final Set<String> scannedEpcs
    // ❌ private final Set<String> pendingEpcs
    // ❌ private final Map<String, Long> lastScannedTimes
    // ❌ private boolean isRFIDModeActive
    // ❌ private boolean isRefundable
    // ❌ private double liveScaleWeight
    // ❌ private MiraApiClient.ApiResponse lastSaleResponse
    // ❌ private boolean isCheckoutDialogShowing
    // ═══════════════════════════════════════════════════════════════════

    // ==================== دورة الحياة ====================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // ✅ Root View Cache
        if (rootView == null) {
            try {
                rootView = inflater.inflate(R.layout.fragment_mira_pos, container, false);
            } catch (Exception e) {
                Log.e(TAG, "onCreateView inflate error: " + e.getMessage(), e);
                return null;
            }
        } else {
            if (rootView.getParent() != null) {
                ((ViewGroup) rootView.getParent()).removeView(rootView);
            }
        }

        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            Context ctx = requireContext();

            // ✅ الحصول على ViewModel (Activity-scoped)
            viewModel = new ViewModelProvider(requireActivity())
                    .get(MiraPosViewModel.class);

            // ✅ تهيئة المديرين
            branchesManager = MiraBranchesManager.getInstance(ctx);
            priceManager = MiraPriceManager.getInstance(ctx);
            permissionManager = PermissionManager.getInstance(ctx);
            isArabic = Locale.getDefault().getLanguage().equals("ar");
            sessionManager = new SessionManager(ctx);
            posManager = new POSManager(ctx);
            pendingSalesManager = new PendingSalesManager(ctx);

            // ✅ مدير الخيوط المشترك
            miraExecutor = MiraExecutorService.getInstance();

            // ✅ تهيئة RFID (مرة واحدة فقط)
            if (rfidManager == null) {
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
                                if (btnPosRFIDMode != null 
                                        && Boolean.TRUE.equals(viewModel.getIsRFIDModeActive().getValue())) {
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
            }

            apiClient = MiraApiClient.getInstance(ctx);

            // ✅ تهيئة Views (مرة واحدة فقط)
            if (!isViewInitialized) {
                bindViews(view);
                setupRecyclerView();
                setupListeners();
                applyPermissions();
                initSmartScale();
                isViewInitialized = true;
                Log.d(TAG, "Views initialized for the first time");
            } else {
                Log.d(TAG, "Views already initialized — reusing");
            }

            // ✅ مراقبة LiveData (يُنفَّذ في كل مرة — آمن)
            observeViewModel();

            // ✅ تحديث أولي
            updateCartSummaryFromVM();

            // ✅ استعادة حالة RFID إن كانت نشطة
            restoreRFIDStateIfNeeded();

        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ مراقبة كل LiveData
     */
    private void observeViewModel() {
        if (viewModel == null) return;

        // 🛒 السلة — تُحدّث Adapter تلقائياً
        viewModel.getCartList().observe(getViewLifecycleOwner(), items -> {
            if (items == null) items = new ArrayList<>();
            cartAdapter.submitList(new ArrayList<>(items));
            updateCartSummaryFromVM();
        });

        // 💰 المجموع
        viewModel.getCartTotal().observe(getViewLifecycleOwner(), total -> {
            if (tvTotalAmount != null && total != null) {
                try {
                    tvTotalAmount.setText(priceManager.formatPriceDefault(total));
                } catch (Exception e) {
                    tvTotalAmount.setText(String.format(Locale.US, "$%.2f", total));
                }
            }
        });

        // 🔢 عدد القطع
        viewModel.getCartItemCount().observe(getViewLifecycleOwner(), count -> {
            if (tvItemCount != null && count != null) {
                tvItemCount.setText(String.valueOf(count));
            }
        });

        // 📡 حالة RFID
        viewModel.getIsRFIDModeActive().observe(getViewLifecycleOwner(), active -> {
            boolean isActive = Boolean.TRUE.equals(active);
            if (btnPosRFIDMode != null) {
                btnPosRFIDMode.setText(isActive
                        ? (isArabic ? "📡 RFID نشط" : "📡 RFID Active")
                        : (isArabic ? getString(R.string.pos_scan_rfid) : "📡 Scan RFID"));
            }
            if (tvCartStatus != null && isActive) {
                tvCartStatus.setText(isArabic ? "📡 جاهز لمسح RFID..." : "📡 Ready for RFID...");
            }
        });

        // ⚖️ وزن الميزان
        viewModel.getLiveScaleWeight().observe(getViewLifecycleOwner(), weight -> {
            if (tvScaleStatus != null && weight != null && weight > 0) {
                tvScaleStatus.setText(String.format(Locale.US, "⚖️ %.2fg", weight));
                tvScaleStatus.setTextColor(Color.parseColor("#2ECC71"));
            }
        });

        // ⏳ حوار الدفع
        viewModel.getIsCheckoutDialogShowing().observe(getViewLifecycleOwner(), showing -> {
            // لا حاجة لعمل شيء — العلم يُدار من ViewModel
        });
    }

    /**
     * ✅ استعادة حالة RFID النشطة بعد العودة
     */
    private void restoreRFIDStateIfNeeded() {
        if (viewModel == null) return;

        if (Boolean.TRUE.equals(viewModel.getIsRFIDModeActive().getValue())) {
            Log.d(TAG, "Restoring active RFID mode");
            isRFIDScanActive = true;
            if (rfidManager != null) {
                rfidManager.startScan();
            }
        }
    }

    // ==================== دورة الحياة - onResume/onPause ====================

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
            if (tvCartStatus != null) {
                int count = viewModel != null 
                        ? viewModel.getCartItemCountValue() : 0;
                if (count == 0) {
                    tvCartStatus.setText(isArabic 
                            ? getString(R.string.pos_ready_scan) 
                            : "Ready to scan...");
                }
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
            if (viewModel != null) {
                viewModel.setCheckoutDialogShowing(false);
            }
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setActivePOSFragment(null);
            }
        } catch (Exception e) {
            Log.e(TAG, "onPause error: " + e.getMessage());
        }
    }

    // ==================== دورة الحياة - التنظيف ====================

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try {
            // ✅ إيقاف RFID إذا لم يكن نشطاً في ViewModel
            if (viewModel != null 
                    && !Boolean.TRUE.equals(viewModel.getIsRFIDModeActive().getValue())) {
                stopRFIDScan();
            }
            
            if (viewModel != null) {
                viewModel.setCheckoutDialogShowing(false);
            }
            
            // ⚠️ لا نُلغي listener إذا الجرد نشط
            if (rfidManager != null 
                    && (viewModel == null 
                        || !Boolean.TRUE.equals(viewModel.getIsRFIDModeActive().getValue()))) {
                rfidManager.setListener(null);
            }
            
            if (scaleManager != null) {
                scaleManager.cleanup();
                scaleManager = null;
            }
            
            if (simulationHelper != null) {
                simulationHelper.cleanup();
                simulationHelper = null;
            }

            // ✅ إعادة تعيين حالة التهيئة
            isViewInitialized = false;

            // ✅ إزالة rootView من parent
            if (rootView != null && rootView.getParent() != null) {
                ((ViewGroup) rootView.getParent()).removeView(rootView);
            }

        } catch (Exception e) {
            Log.e(TAG, "onDestroyView error: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            stopRFIDScan();
            if (scaleManager != null) {
                scaleManager.cleanup();
                scaleManager = null;
            }
            if (rfidManager != null) {
                rfidManager.release();
                rfidManager = null;
            }
            rootView = null;
        } catch (Exception e) {
            Log.e(TAG, "onDestroy error: " + e.getMessage());
        }
    }
// ==================== [بداية الجزء 3.2] ====================

// ═══════════════════════════════════════════════════════════════════
//  أدوات مساعدة (Helpers)
// ═══════════════════════════════════════════════════════════════════

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

/**
 * ✅ safeRun — تشغيل آمن على UI Thread
 */
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

// ═══════════════════════════════════════════════════════════════════
//  تهيئة الواجهة
// ═══════════════════════════════════════════════════════════════════

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
            if (btnPosRFIDMode != null && !isRFIDModeCurrentlyActive()) {
                btnPosRFIDMode.setText(R.string.pos_scan_rfid);
            }
            if (btnAddManual != null) btnAddManual.setText(R.string.pos_add);
        } else {
            if (tilManualEpc != null) tilManualEpc.setHint("Enter EPC or Barcode...");
            if (btnCheckout != null) btnCheckout.setText("Checkout");
            if (btnClearCart != null) btnClearCart.setText("Clear");
            if (btnOpenScanner != null) btnOpenScanner.setText("📷 Scan GS1");
            if (btnPosRFIDMode != null && !isRFIDModeCurrentlyActive()) {
                btnPosRFIDMode.setText("📡 Scan RFID");
            }
            if (btnAddManual != null) btnAddManual.setText("Add");
        }
    } catch (Exception e) {
        Log.e(TAG, "applyLanguage error: " + e.getMessage());
    }
}

private boolean isRFIDModeCurrentlyActive() {
    return viewModel != null 
            && Boolean.TRUE.equals(viewModel.getIsRFIDModeActive().getValue());
}

private void setupRecyclerView() {
    try {
        if (rvCartItems != null) {
            rvCartItems.setLayoutManager(new LinearLayoutManager(getContext()));

            // ✅ CartAdapter الجديد — لا يستقبل قائمة
            cartAdapter = new CartAdapter(
                    this::removeItemFromCart,
                    isArabic,
                    priceManager
            );

            rvCartItems.setAdapter(cartAdapter);
            rvCartItems.setHasFixedSize(false);
        }
    } catch (Exception e) {
        Log.e(TAG, "setupRecyclerView error: " + e.getMessage());
    }
}

// ═══════════════════════════════════════════════════════════════════
//  المستمعون (Listeners)
// ═══════════════════════════════════════════════════════════════════

private void setupListeners() {
    try {
        if (etManualEpc != null) {
            etManualEpc.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_SEARCH
                        || (event != null 
                            && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                            && event.getAction() == KeyEvent.ACTION_DOWN)) {
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
                    showToast(isArabic 
                            ? "⚠️ تعذر فتح الماسح" 
                            : "⚠️ Unable to open scanner");
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

// ═══════════════════════════════════════════════════════════════════
//  إدخال EPC يدوياً
// ═══════════════════════════════════════════════════════════════════

private void submitManualEpc() {
    try {
        if (!(permissionManager.canView("pos")
                || permissionManager.isPosOperator()
                || permissionManager.isManager())) {
            showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            return;
        }

        UIHelper.hideKeyboard(getActivity());
        String epc = etManualEpc != null 
                ? etManualEpc.getText().toString().trim() : "";

        if (!epc.isEmpty()) {
            // ✅ فحص التكرار من ViewModel
            if (viewModel != null && viewModel.getScannedEpcs().contains(epc)) {
                showToast(isArabic 
                        ? "⚠️ هذه القطعة مضافة مسبقاً!" 
                        : "⚠️ Item already in cart!");
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

// ═══════════════════════════════════════════════════════════════════
//  الميزان الذكي
// ═══════════════════════════════════════════════════════════════════

private void initSmartScale() {
    try {
        scaleManager = new SmartScaleManager(requireContext(),
                new SmartScaleManager.ScaleCallback() {
            @Override
            public void onWeightReceived(double weight) {
                // ✅ حفظ في ViewModel
                if (viewModel != null) {
                    viewModel.setScaleWeight(weight);
                    updateCartItemsScaleWeight(weight);
                }
            }

            @Override
            public void onScaleConnected(String name) {
                safeRun(() -> {
                    if (tvScaleStatus != null) {
                        tvScaleStatus.setText("⚖️ " + name);
                        tvScaleStatus.setTextColor(Color.parseColor("#2ECC71"));
                    }
                    showToast(isArabic 
                            ? "✅ تم الاتصال: " + name 
                            : "✅ Connected: " + name);
                });
            }

            @Override
            public void onScaleDisconnected() {
                if (viewModel != null) {
                    viewModel.setScaleWeight(0.0);
                }
                safeRun(() -> {
                    if (tvScaleStatus != null) {
                        tvScaleStatus.setText(isArabic 
                                ? "⚖️ غير متصل" 
                                : "⚖️ Offline");
                        tvScaleStatus.setTextColor(Color.parseColor("#E74C3C"));
                    }
                });
            }

            @Override
            public void onScaleError(String error) {
                Log.e(TAG, "Scale Error: " + error);
            }
        });

        simulationHelper = new ScaleSimulationHelper(requireContext(),
                new ScaleSimulationHelper.SimulationCallback() {
            @Override
            public void onWeightReceived(double weight, String label) {
                safeRun(() -> {
                    if (viewModel != null) {
                        viewModel.setScaleWeight(weight);
                        updateCartItemsScaleWeight(weight);
                    }
                    if (tvScaleStatus != null) {
                        tvScaleStatus.setText(String.format(Locale.US,
                                "⚖️ %.2fg (محاكاة)", weight));
                        tvScaleStatus.setTextColor(Color.parseColor("#2ECC71"));
                    }
                    showToast("📦 " + label + ": " + weight + "g");
                });
            }
        });

    } catch (Exception e) {
        Log.e(TAG, "initSmartScale error: " + e.getMessage());
    }
}

/**
 * ✅ تحديث وزن القطعة الأولى عند تغير الميزان (يُفوّض لـ ViewModel)
 */
private void updateCartItemsScaleWeight(double currentScaleWeight) {
    if (viewModel == null) return;

    viewModel.updateTopItemScaleWeight(currentScaleWeight,
            (karat, weight) -> {
                // ✅ حساب السعر عبر PriceCalculator
                if (priceManager != null) {
                    return priceManager.calculateGoldPrice(
                            getKaratNumber(karat), weight);
                }
                return 0.0;
            });
}

// ═══════════════════════════════════════════════════════════════════
//  فتح ماسح الباركود
// ═══════════════════════════════════════════════════════════════════

private void openBarcodeScanner() {
    try {
        if (!isAdded() || getContext() == null) return;
        if (getParentFragmentManager() == null) return;
        if (getParentFragmentManager().isStateSaved()) return;

        MiraBarcodeScannerFragment sf = new MiraBarcodeScannerFragment();
        sf.setOnBarcodeScannedListener((data, format) -> {
            try {
                onBarcodeScanned(data);
                if (isAdded() && getParentFragmentManager() != null
                        && getParentFragmentManager().getBackStackEntryCount() > 0) {
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
        showToast(isArabic 
                ? "⚠️ تعذر فتح الماسح" 
                : "⚠️ Unable to open scanner");
    }
}

// ═══════════════════════════════════════════════════════════════════
//  RFID Mode
// ═══════════════════════════════════════════════════════════════════

private void toggleRFIDMode() {
    try {
        if (!isAdded() || rfidManager == null || viewModel == null) return;

        boolean currentlyActive = isRFIDModeCurrentlyActive();
        boolean newState = !currentlyActive;

        // ✅ حفظ في ViewModel
        viewModel.setRFIDMode(newState);

        if (newState) {
            startRFIDScan();
        } else {
            stopRFIDScan();
        }
    } catch (Exception e) {
        Log.e(TAG, "toggleRFIDMode error: " + e.getMessage());
        if (viewModel != null) {
            viewModel.setRFIDMode(false);
        }
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

// ═══════════════════════════════════════════════════════════════════
//  استقبال القراءات (RFID + Barcode)
// ═══════════════════════════════════════════════════════════════════

public void onRingScanned(String epc) {
    try {
        if (epc == null || epc.trim().isEmpty() || viewModel == null) return;

        // ✅ فحص Debounce من ViewModel
        if (viewModel.isDebounceActive(epc)) return;
        viewModel.updateScanTime(epc);

        // ✅ فحص التكرار
        if (viewModel.getScannedEpcs().contains(epc)) {
            safeRun(() -> {
                showToast(isArabic 
                        ? "⚠️ مضافة مسبقاً!" 
                        : "⚠️ Already in cart!");
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
        if (data == null || data.trim().isEmpty() || viewModel == null) return;

        // ✅ فحص Debounce من ViewModel
        if (viewModel.isDebounceActive(data)) return;
        viewModel.updateScanTime(data);

        // ✅ فحص التكرار
        if (viewModel.getScannedEpcs().contains(data)) {
            safeRun(() -> {
                showToast(isArabic 
                        ? "⚠️ مضافة مسبقاً!" 
                        : "⚠️ Already in cart!");
                playAlertSound(2);
            });
            return;
        }
        processRingEpc(data, "barcode");
    } catch (Exception e) {
        Log.e(TAG, "onBarcodeScanned error: " + e.getMessage());
    }
}

// ═══════════════════════════════════════════════════════════════════
//  البحث عن القطعة (Core Logic)
// ═══════════════════════════════════════════════════════════════════

private void processRingEpc(final String code, final String source) {
    if (code == null || code.trim().isEmpty() || !isAdded()) return;
    if (viewModel == null) return;

    final String cleanCode = code.trim();

    // ✅ فحص Pending من ViewModel (بدل synchronized يدوي)
    if (!viewModel.startPendingSearch(cleanCode)) {
        Log.d(TAG, "Search already pending for: " + cleanCode);
        return;
    }

    playAlertSound(1);

    safeRun(() -> {
        showSearchLoadingDialog(cleanCode);
        if (tvCartStatus != null) {
            tvCartStatus.setText(isArabic 
                    ? "🔍 جاري البحث..." 
                    : "🔍 Searching...");
        }
    });

    // ✅ استخدام networkExecutor المشترك
    miraExecutor.getNetworkExecutor().execute(() -> {
        try {
            Context context = getContext();
            if (context == null) {
                viewModel.finishPendingSearch(cleanCode);
                return;
            }

            MiraItemSyncManager syncManager = MiraItemSyncManager.getInstance(context);
            final int branchId = getActiveBranchId();

            syncManager.findItem(cleanCode, branchId,
                    new MiraItemSyncManager.ItemLookupCallback() {
                @Override
                public void onItemFound(JSONObject item, boolean fromServer) {
                    safeRun(() -> {
                        dismissSearchLoadingDialog();
                        int itemBranchId = item.optInt("branch_id", -1);
                        if (itemBranchId > 0 && itemBranchId != branchId) {
                            showSuccessMessage(
                                    isArabic 
                                            ? "❌ قطعة في فرع آخر" 
                                            : "❌ Item in Another Branch",
                                    isArabic 
                                            ? "هذه القطعة موجودة في فرع مختلف" 
                                            : "This item is in a different branch",
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
            Log.e(TAG, "processRingEpc error: " + e.getMessage());
            safeRun(() -> {
                dismissSearchLoadingDialog();
                handleItemNotFound(cleanCode);
            });
        } finally {
            // ✅ إزالة Pending من ViewModel
            if (viewModel != null) {
                viewModel.finishPendingSearch(cleanCode);
            }
        }
    });
}

private void parseItemResponse(String code, JSONObject data) {
    try {
        if (viewModel == null) return;

        JSONObject item = data.has("item") ? data.optJSONObject("item") : data;
        if (item == null) {
            handleItemNotFound(code);
            return;
        }

        String vaultStatus = item.optString("status",
                item.optString("vault_status", "")).toLowerCase();

        if ("sold".equals(vaultStatus) || "exited".equals(vaultStatus)) {
            safeRun(() -> {
                showSuccessMessage(
                        isArabic ? "❌ قطعة مباعة" : "❌ Item Sold",
                        isArabic 
                                ? "هذه القطعة تم بيعها بالفعل" 
                                : "This item has already been sold",
                        false
                );
                playAlertSound(2);
            });
            return;
        }

        boolean isAvailable = !vaultStatus.equals("sold") 
                && !vaultStatus.equals("reserved");
        String title = item.optString("title",
                item.optString("item_title", isArabic ? "قطعة" : "Item"));
        String karat = item.optString("karat", 
                item.optString("gold_karat", "21K"));
        double weight = item.optDouble("weight",
                item.optDouble("gross_weight",
                        item.optDouble("gross_weight_grams", 0)));
        double price = item.optDouble("retail_price",
                item.optDouble("retail_price_usd", item.optDouble("price", 0)));

        if (price <= 0 && weight > 0 && priceManager != null) {
            price = priceManager.calculateGoldPrice(getKaratNumber(karat), weight);
        }

        // ✅ إنشاء CartItem
        CartItem cartItem = new CartItem(code, title, karat, weight, 0.0,
                price, isAvailable, vaultStatus.toUpperCase());
        cartItem.serialNo = item.optString("serial", 
                item.optString("serial_number", ""));
        cartItem.gtin = item.optString("gtin", item.optString("gtin13", ""));
        cartItem.location = item.optString("location", 
                item.optString("current_location", ""));
        cartItem.itemId = item.optInt("id", item.optInt("item_id", 0));

        final CartItem finalItem = cartItem;

        safeRun(() -> {
            if (!isAvailable) {
                showSuccessMessage(
                        isArabic ? "❌ قطعة غير متاحة" : "❌ Item Unavailable",
                        isArabic 
                                ? "حالة القطعة: " + vaultStatus 
                                : "Item status: " + vaultStatus,
                        false
                );
                updateCartSummaryFromVM();
                return;
            }

            // ✅ إضافة للسلة عبر ViewModel
            MiraPosViewModel.CartAddResult result = viewModel.addToCart(finalItem);

            if (result == MiraPosViewModel.CartAddResult.DUPLICATE) {
                showToast(isArabic 
                        ? "⚠️ هذه القطعة مضافة مسبقاً!" 
                        : "⚠️ Item already in cart!");
                playAlertSound(2);
                return;
            }

            // ✅ نجحت الإضافة — تمرير للأعلى
            if (rvCartItems != null) {
                rvCartItems.scrollToPosition(0);
            }
            playAlertSound(1);
        });
    } catch (Exception e) {
        Log.e(TAG, "Parse error: " + e.getMessage());
        handleItemNotFound(code);
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Dialog البحث
// ═══════════════════════════════════════════════════════════════════

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
        LinearLayout.LayoutParams pbParams = 
                new LinearLayout.LayoutParams(100, 100);
        pbParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        pbParams.topMargin = 16;
        progressBar.setLayoutParams(pbParams);
        layout.addView(progressBar);

        TextView tvLoading = new TextView(ctx);
        tvLoading.setText(isArabic 
                ? "جاري البحث عن القطعة..." 
                : "Searching for item...");
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
            searchLoadingDialog.getWindow().setBackgroundDrawable(
                    new ColorDrawable(Color.TRANSPARENT));
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
                isArabic 
                        ? "لم يتم العثور على القطعة بالكود:\n" + code 
                        : "Item not found with code:\n" + code,
                false
        );
        playAlertSound(2);
        updateCartSummaryFromVM();
    });
}

// ═══════════════════════════════════════════════════════════════════
//  السلة — تحديث وحذف
// ═══════════════════════════════════════════════════════════════════

/**
 * ✅ تحديث الواجهة من ViewModel
 */
private void updateCartSummaryFromVM() {
    try {
        if (viewModel == null) return;

        int count = viewModel.getCartItemCountValue();
        double total = viewModel.getCartTotalValue();
        List<CartItem> items = viewModel.getCartList().getValue();

        boolean hasBadItem = false;
        if (items != null) {
            for (CartItem item : items) {
                if (!item.isAvailable) {
                    hasBadItem = true;
                    break;
                }
            }
        }

        final int fc = count;
        final double fs = total;
        final boolean fb = hasBadItem;

        safeRun(() -> {
            if (layoutCartEmptyState != null) {
                layoutCartEmptyState.setVisibility(fc == 0 ? View.VISIBLE : View.GONE);
            }
            if (tvItemCount != null) {
                tvItemCount.setText(String.valueOf(fc));
            }
            if (tvTotalAmount != null) {
                try {
                    tvTotalAmount.setText(priceManager.formatPriceDefault(fs));
                } catch (Exception e) {
                    tvTotalAmount.setText(String.format(Locale.US, "$%.2f", fs));
                }
            }
            if (tvCartStatus != null) {
                if (fc == 0) {
                    tvCartStatus.setText(isArabic 
                            ? getString(R.string.pos_ready_scan) 
                            : "Ready to scan...");
                } else if (fb) {
                    tvCartStatus.setText(isArabic 
                            ? "⚠️ قطعة غير متاحة!" 
                            : "⚠️ Unavailable item!");
                } else {
                    tvCartStatus.setText(isArabic 
                            ? "✓ جاهز للبيع" 
                            : "✓ Ready for sale");
                }
            }
            if (btnCheckout != null) {
                boolean canUsePos = permissionManager.canView("pos")
                        || permissionManager.isPosOperator()
                        || permissionManager.isManager();
                btnCheckout.setEnabled(fc > 0 && !fb && canUsePos);
                btnCheckout.setAlpha(fc > 0 && !fb ? 1f : 0.5f);
            }
        });
    } catch (Exception e) {
        Log.e(TAG, "updateCartSummaryFromVM error: " + e.getMessage());
    }
}

/**
 * ✅ حذف قطعة من السلة (يُفوّض لـ ViewModel)
 */
private void removeItemFromCart(int pos) {
    try {
        if (viewModel == null) return;
        viewModel.removeFromCart(pos);
    } catch (Exception e) {
        Log.e(TAG, "removeItemFromCart error: " + e.getMessage());
    }
}

/**
 * ✅ تفريغ السلة (يُفوّض لـ ViewModel)
 */
private void clearCart() {
    try {
        if (viewModel == null) return;
        viewModel.clearCart();
    } catch (Exception e) {
        Log.e(TAG, "clearCart error: " + e.getMessage());
    }
}

/**
 * ✅ الحصول على المجموع من ViewModel
 */
private double getCartTotal() {
    return viewModel != null ? viewModel.getCartTotalValue() : 0.0;
}
    // ==================== [بداية الجزء 3.3] ====================

    // ═══════════════════════════════════════════════════════════════════
    //  إتمام البيع
    // ═══════════════════════════════════════════════════════════════════

    private void processCheckoutAndStockUpdate() {
        try {
            if (!(permissionManager.canView("pos")
                    || permissionManager.isPosOperator()
                    || permissionManager.isManager())) {
                showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
                return;
            }

            List<CartItem> items = viewModel != null 
                    ? viewModel.getCartList().getValue() : null;
            if (items == null || items.isEmpty()) {
                showToast(isArabic ? "السلة فارغة" : "Cart is empty");
                return;
            }

            // ✅ فحص من ViewModel
            if (viewModel.isCheckoutDialogShowingValue()) {
                return;
            }

            if (!isAdded() || getActivity() == null) return;

            UIHelper.hideKeyboard(getActivity());

            // ✅ تسجيل في ViewModel
            viewModel.setCheckoutDialogShowing(true);

            POSCheckoutDialog dialog = POSCheckoutDialog.newInstance(
                    getCartTotal(), items.size());

            dialog.setCheckoutListener(data -> {
                viewModel.setCheckoutDialogShowing(false);
                executeCheckoutDirectly(data);
            });

            dialog.setOnDismissListener(() -> {
                if (viewModel != null) {
                    viewModel.setCheckoutDialogShowing(false);
                }
            });

            FragmentManager fm = getParentFragmentManager();
            if (!fm.isStateSaved()) {
                dialog.show(fm, "POSCheckoutDialog");
            } else {
                viewModel.setCheckoutDialogShowing(false);
            }

        } catch (Exception e) {
            if (viewModel != null) {
                viewModel.setCheckoutDialogShowing(false);
            }
            Log.e(TAG, "processCheckout error: " + e.getMessage());
            showToast(isArabic 
                    ? "خطأ: " + e.getMessage() 
                    : "Error: " + e.getMessage());
        }
    }

    /**
     * ✅ تنفيذ البيع مباشرة — مع Payload كامل
     */
    private void executeCheckoutDirectly(POSCheckoutDialog.POSCheckoutData data) {
        try {
            if (!isAdded() || viewModel == null) return;

            final AlertDialog loadingDialog = showProfessionalLoadingDialog();
            if (loadingDialog == null) return;

            setButtonsEnabled(false);

            // ✅ أخذ نسخة من السلة قبل الاستخدام (thread-safety)
            List<CartItem> itemsSnapshot = viewModel.getCartList().getValue();
            if (itemsSnapshot == null) itemsSnapshot = new ArrayList<>();
            final List<CartItem> cartSnapshot = new ArrayList<>(itemsSnapshot);

            // ✅ استخدام networkExecutor المشترك
            miraExecutor.getNetworkExecutor().execute(() -> {
                try {
                    Context ctx = getContext();
                    if (ctx == null) return;

                    MiraApiClient client = MiraApiClient.getInstance(ctx);

                    JSONObject payload = new JSONObject();
                    payload.put("action", "COMPLETE_POS_SALE");
                    payload.put("gate_id", client.getGateId());
                    payload.put("branch_id", getActiveBranchId());
                    payload.put("user_id", sessionManager.getUserId());
                    payload.put("username", sessionManager.getUsername());

                    payload.put("customer_name",
                            data.customerName != null && !data.customerName.isEmpty()
                                    ? data.customerName : "عميل نقدي");
                    payload.put("customer_phone",
                            data.customerPhone != null ? data.customerPhone : "-");

                    JSONObject customer = new JSONObject();
                    customer.put("name", payload.getString("customer_name"));
                    customer.put("phone", payload.getString("customer_phone"));
                    customer.put("payment_method", data.paymentMethod);
                    payload.put("customer", customer);

                    JSONArray itemsArr = new JSONArray();
                    for (CartItem item : cartSnapshot) {
                        JSONObject obj = new JSONObject();
                        obj.put("epc", item.epc);
                        obj.put("title", item.name);
                        obj.put("karat", item.karat);
                        obj.put("weight", item.scaleWeight > 0 
                                ? item.scaleWeight : item.weight);
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
                    payment.put("currency_code", "YER");
                    payment.put("payment_method", data.paymentMethod);
                    payload.put("payment", payment);

                    Log.e("POS_DEBUG", "🚨 SENDING CHECKOUT");
                    Log.e("POS_DEBUG", "URL: " + client.getApiUrl() + "pos/checkout");
                    Log.e("POS_DEBUG", "Gate ID: " + client.getGateId());
                    Log.e("POS_DEBUG", "Payload: " + payload.toString());

                    MiraApiClient.ApiResponse response = client.checkout(payload);

                    Log.e("POS_DEBUG", "🚨 RESPONSE: code=" 
                            + (response != null ? response.code : "null")
                            + ", success=" + (response != null && response.isSuccess)
                            + ", networkError=" 
                            + (response != null && response.isNetworkError));

                    final MiraApiClient.ApiResponse finalResponse = response;

                    safeRun(() -> {
                        if (loadingDialog != null && loadingDialog.isShowing()) {
                            loadingDialog.dismiss();
                        }
                        setButtonsEnabled(true);

                        if (finalResponse != null && finalResponse.isSuccess) {
                            // ✅ نجاح
                            handleCheckoutSuccess(finalResponse);
                        } else if (finalResponse != null 
                                && finalResponse.isNetworkError) {
                            // ✅ Offline
                            handleCheckoutOffline();
                        } else {
                            // ❌ فشل
                            String errorMsg = finalResponse != null 
                                    && finalResponse.error != null
                                    ? finalResponse.error : "Unknown error";
                            showRetryDialog(
                                    isArabic ? "❌ فشل البيع" : "❌ Sale Failed",
                                    errorMsg,
                                    () -> executeCheckoutDirectly(data)
                            );
                        }
                    });

                } catch (Exception e) {
                    Log.e(TAG, "executeCheckoutDirectly error: " + e.getMessage(), e);
                    safeRun(() -> {
                        if (loadingDialog != null && loadingDialog.isShowing()) {
                            loadingDialog.dismiss();
                        }
                        setButtonsEnabled(true);
                        showRetryDialog(
                                isArabic ? "❌ خطأ" : "❌ Error",
                                e.getMessage(),
                                () -> executeCheckoutDirectly(data)
                        );
                    });
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "executeCheckoutDirectly outer error: " + e.getMessage());
        }
    }

    /**
     * ✅ معالجة نجاح البيع (Online)
     */
    private void handleCheckoutSuccess(MiraApiClient.ApiResponse response) {
        try {
            if (viewModel == null) return;

            playAlertSound(1);

            // ✅ حفظ استجابة البيع في ViewModel (للإرجاع)
            String responseJson = "{}";
            if (response.data != null) {
                responseJson = response.data.toString();
            } else if (response.getBody() != null) {
                responseJson = response.getBody();
            }
            viewModel.setLastSaleResponseJson(responseJson);
            viewModel.setRefundable(true);

            // ✅ تفريغ السلة (عبر ViewModel)
            viewModel.clearCart();

            // ✅ عرض الإيصال
            showReceipt(responseJson);

            // ✅ إشعار التحديثات
            notifyDashboardAndInventoryUpdate("sale");

            showToast(isArabic ? "✅ تم البيع بنجاح" : "✅ Sale Successful");

        } catch (Exception e) {
            Log.e(TAG, "handleCheckoutSuccess error: " + e.getMessage());
        }
    }

    /**
     * ✅ معالجة نجاح البيع (Offline)
     */
    private void handleCheckoutOffline() {
        try {
            if (viewModel == null) return;

            playAlertSound(1);

            // ✅ تفريغ السلة (عبر ViewModel)
            viewModel.clearCart();

            showToast(isArabic 
                    ? "✅ تم البيع (أوفلاين)" 
                    : "✅ Sale (Offline)");

            notifyDashboardAndInventoryUpdate("sale_offline");

        } catch (Exception e) {
            Log.e(TAG, "handleCheckoutOffline error: " + e.getMessage());
        }
    }

    /**
     * ✅ عرض الإيصال
     */
    private void showReceipt(String receiptJson) {
        try {
            if (!isAdded() || getActivity() == null) return;
            if (receiptJson == null) receiptJson = "{}";

            MiraReceiptFragment receiptFragment = 
                    MiraReceiptFragment.newInstance(receiptJson);

            FragmentManager fm = getParentFragmentManager();
            if (!fm.isStateSaved()) {
                receiptFragment.show(fm, "MiraReceiptFragment");
            }
        } catch (Exception e) {
            Log.e(TAG, "showReceipt error: " + e.getMessage());
        }
    }

    /**
     * ✅ حوار إعادة المحاولة
     */
    private void showRetryDialog(String title, String message, Runnable retryAction) {
        try {
            Context ctx = getContext();
            if (ctx == null || !isAdded()) return;

            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(ctx);
            builder.setTitle(title).setMessage(message).setCancelable(false);

            if (retryAction != null) {
                builder.setPositiveButton(
                        isArabic ? "🔄 إعادة المحاولة" : "🔄 Retry",
                        (dialog, which) -> {
                            dialog.dismiss();
                            retryAction.run();
                        });
            }
            builder.setNegativeButton(
                    isArabic ? "إغلاق" : "Close",
                    (dialog, which) -> dialog.dismiss());
            builder.show();
        } catch (Exception e) {
            Log.e(TAG, "showRetryDialog error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  الإرجاع (Refund)
    // ═══════════════════════════════════════════════════════════════════

    private void showRefundDialog() {
        if (!isAdded() || getContext() == null) return;

        try {
            refundDialog = new RefundDialog(requireContext(), 
                    new RefundDialog.RefundCallback() {
                @Override
                public void onRefundConfirm(String code, String reason) {
                    processRefund(code, reason);
                }

                @Override
                public void onBarcodeScan() {
                    ScanOptions options = new ScanOptions();
                    options.setPrompt(isArabic 
                            ? "📷 وجه الكاميرا نحو باركود القطعة"
                            : "📷 Aim camera at item barcode");
                    options.setBeepEnabled(false);
                    options.setOrientationLocked(true);
                    refundBarcodeLauncher.launch(options);
                }

                @Override
                public void onCodeChanged(String code) {
                    searchItemForRefund(code);
                }
            });
            refundDialog.show();
        } catch (Exception e) {
            Log.e(TAG, "showRefundDialog error: " + e.getMessage());
        }
    }

    /**
     * ✅ البحث عن القطعة للتحقق من إمكانية الإرجاع
     */
    private void searchItemForRefund(String code) {
        if (code == null || code.trim().isEmpty()) return;

        Log.e("REFUND_DEBUG", "🔍 Searching for refund: " + code);

        miraExecutor.getNetworkExecutor().execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("epc", code);
                payload.put("serial_no", code);
                payload.put("branch_id", getActiveBranchId());
                payload.put("context", "refund_check");
                payload.put("gate_id", apiClient != null ? apiClient.getGateId() : "");
                payload.put("user_id", 
                        sessionManager != null ? sessionManager.getUserId() : 0);
                payload.put("username", 
                        sessionManager != null ? sessionManager.getUsername() : "");

                MiraApiClient client = MiraApiClient.getInstance(requireContext());
                MiraApiClient.ApiResponse response = client.searchItem(payload);

                Log.e("REFUND_DEBUG", "Response code: " 
                        + (response != null ? response.code : "null"));

                safeRun(() -> {
                    if (response != null && response.isSuccess 
                            && response.data != null) {
                        JSONObject itemData = response.data.optJSONObject("item");
                        if (itemData == null) {
                            itemData = response.data;
                        }

                        boolean isRefundable = itemData.optBoolean(
                                "is_refundable", false);
                        String itemStatus = itemData.optString("status",
                                itemData.optString("vault_status", ""));

                        Log.e("REFUND_DEBUG", "is_refundable: " + isRefundable 
                                + ", status: " + itemStatus);

                        if (isRefundable) {
                            // ✅ حفظ حالة الإرجاع
                            if (viewModel != null) {
                                viewModel.setRefundable(true);
                            }
                            showRefundItemInfoInDialog(itemData);
                        } else {
                            if (viewModel != null) {
                                viewModel.setRefundable(false);
                            }
                            showRefundItemErrorInDialog(
                                    isArabic 
                                            ? "⚠️ القطعة غير صالحة للإرجاع (حالتها: " 
                                                + itemStatus + ")"
                                            : "⚠️ Item not eligible (status: " 
                                                + itemStatus + ")"
                            );
                        }
                    } else {
                        String errorMsg = response != null && response.error != null
                                ? response.error : "Unknown error";
                        showRefundItemErrorInDialog(errorMsg);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "searchItemForRefund error: " + e.getMessage(), e);
                safeRun(() -> showRefundItemErrorInDialog(
                        isArabic ? "⚠️ خطأ في البحث" : "⚠️ Search error"
                ));
            }
        });
    }

    private void showRefundItemInfoInDialog(JSONObject itemData) {
        try {
            if (refundDialog == null) return;

            String title = itemData.optString("title",
                    itemData.optString("item_title", 
                            isArabic ? "قطعة ذهبية" : "Gold Item"));
            double price = itemData.optDouble("retail_price_usd",
                    itemData.optDouble("retail_price", 0.0));

            String priceStr = priceManager != null
                    ? priceManager.formatPriceDefault(price)
                    : String.format(Locale.US, "$%.2f", price);

            refundDialog.showRefundItemInfo(title, priceStr);
        } catch (Exception e) {
            Log.e(TAG, "showRefundItemInfoInDialog error: " + e.getMessage());
        }
    }

    private void showRefundItemErrorInDialog(String message) {
        try {
            if (refundDialog == null) return;
            refundDialog.showRefundItemError(message);
        } catch (Exception e) {
            Log.e(TAG, "showRefundItemErrorInDialog error: " + e.getMessage());
        }
    }

    /**
     * ✅ تنفيذ الإرجاع — مع Payload كامل
     */
    private void processRefund(String code, String reason) {
        final AlertDialog loadingDialog = showProfessionalLoadingDialog();

        miraExecutor.getNetworkExecutor().execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("epc", code);
                payload.put("serial_no", code);
                payload.put("reason", reason);
                payload.put("branch_id", getActiveBranchId());
                payload.put("user_id", 
                        sessionManager != null ? sessionManager.getUserId() : 0);
                payload.put("gate_id", 
                        apiClient != null ? apiClient.getGateId() : "");
                payload.put("username", 
                        sessionManager != null ? sessionManager.getUsername() : "");

                Log.e("REFUND_DEBUG", "🚨 SENDING REFUND");
                Log.e("REFUND_DEBUG", "Payload: " + payload.toString());

                MiraApiClient client = MiraApiClient.getInstance(requireContext());
                MiraApiClient.ApiResponse response = client.processRefund(payload);

                Log.e("REFUND_DEBUG", "Response code: " 
                        + (response != null ? response.code : "null"));

                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    if (refundDialog != null && refundDialog.isShowing()) {
                        refundDialog.dismiss();
                    }

                    if (response != null && response.isSuccess) {
                        showSuccessMessage(
                                isArabic 
                                        ? "✅ تم الإرجاع" 
                                        : "✅ Refund Successful",
                                isArabic 
                                        ? "تم إرجاع القطعة بنجاح إلى المخزون"
                                        : "Item successfully returned to inventory",
                                true
                        );
                        notifyDashboardAndInventoryUpdate("refund");
                    } else {
                        String err = response != null && response.error != null
                                ? response.error : "Unknown error";
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
                    showSuccessMessage(
                            isArabic ? "❌ خطأ" : "❌ Error",
                            e.getMessage(),
                            false
                    );
                });
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    //  الإشعارات والكاش
    // ═══════════════════════════════════════════════════════════════════

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
            if (ctx == null || viewModel == null) return;

            List<CartItem> items = viewModel.getCartList().getValue();
            if (items == null) return;

            MiraLocalItemCache cache = MiraLocalItemCache.getInstance(ctx);
            for (CartItem item : items) {
                cache.markItemAsSold(item.epc);
            }
        } catch (Exception e) {
            Log.e(TAG, "removeSoldItemsFromCache error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Dialogs مساعدة
    // ═══════════════════════════════════════════════════════════════════

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
            tvLoading.setText(isArabic 
                    ? "جاري إتمام البيع..." 
                    : "Processing sale...");
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
                dialog.getWindow().setBackgroundDrawable(
                        new ColorDrawable(Color.TRANSPARENT));
            }
            dialog.show();
            return dialog;
        } catch (Exception e) {
            Log.e(TAG, "showProfessionalLoadingDialog error: " + e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  التفعيل والصلاحيات
    // ═══════════════════════════════════════════════════════════════════

    private void setButtonsEnabled(boolean enabled) {
        safeRun(() -> {
            try {
                boolean canUsePos = permissionManager.canView("pos")
                        || permissionManager.isPosOperator()
                        || permissionManager.isManager();
                int itemCount = viewModel != null 
                        ? viewModel.getCartItemCountValue() : 0;

                if (btnCheckout != null) {
                    btnCheckout.setEnabled(
                            enabled && canUsePos && itemCount > 0);
                }
                if (btnClearCart != null) btnClearCart.setEnabled(enabled);
                if (btnAddManual != null) {
                    btnAddManual.setEnabled(enabled && canUsePos);
                }
                if (btnOpenScanner != null) {
                    btnOpenScanner.setEnabled(enabled && canUsePos);
                }
                if (btnPosRFIDMode != null) {
                    btnPosRFIDMode.setEnabled(enabled && canUsePos);
                }
                if (etManualEpc != null) {
                    etManualEpc.setEnabled(enabled && canUsePos);
                }
            } catch (Exception e) {
                Log.e(TAG, "setButtonsEnabled error: " + e.getMessage());
            }
        });
    }

    private void applyPermissions() {
        try {
            boolean canUsePos = permissionManager.canView("pos")
                    || permissionManager.isPosOperator()
                    || permissionManager.isManager();

            if (btnCheckout != null) btnCheckout.setEnabled(false);
            if (btnAddManual != null) btnAddManual.setEnabled(canUsePos);
            if (btnOpenScanner != null) btnOpenScanner.setEnabled(canUsePos);
            if (btnPosRFIDMode != null) btnPosRFIDMode.setEnabled(canUsePos);
            if (etManualEpc != null) etManualEpc.setEnabled(canUsePos);

            if (btnPosRefund != null) {
                boolean canRefund = permissionManager.canEdit("pos")
                        || permissionManager.isAdmin()
                        || permissionManager.isManager();
                btnPosRefund.setVisibility(canRefund 
                        ? View.VISIBLE : View.GONE);
            }

            if (!canUsePos && tvCartStatus != null) {
                tvCartStatus.setText(isArabic 
                        ? "⛔ غير مصرح" 
                        : "⛔ Access Denied");
            }
        } catch (Exception e) {
            Log.e(TAG, "applyPermissions error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ActivityResultLauncher للإرجاع
    // ═══════════════════════════════════════════════════════════════════

    private final ActivityResultLauncher<ScanOptions> refundBarcodeLauncher =
            registerForActivityResult(new ScanContract(), result -> {
        if (result.getContents() != null && isAdded()) {
            if (refundDialog != null) {
                refundDialog.showRefundItemInfo(
                        result.getContents(),
                        isArabic 
                                ? "✅ تم مسح الباركود" 
                                : "✅ Barcode scanned"
                );
                searchItemForRefund(result.getContents());
            }
        }
    });

    // ═══════════════════════════════════════════════════════════════════
    //  أدوات مساعدة (Helpers)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * ✅ الحصول على وزن الميزان الحالي (من ViewModel)
     */
    private double getLiveScaleWeight() {
        return viewModel != null ? viewModel.getScaleWeightValue() : 0.0;
    }

    /**
     * ✅ الحصول على EPCs الممسوحة (من ViewModel)
     */
    private Set<String> getScannedEpcs() {
        return viewModel != null 
                ? viewModel.getScannedEpcs() 
                : new java.util.HashSet<>();
    }

    /**
     * ✅ الحصول على lastScannedTimes (من ViewModel)
     */
    private Map<String, Long> getLastScannedTimes() {
        return viewModel != null 
                ? viewModel.getLastScannedTimes() 
                : new java.util.HashMap<>();
    }

    /**
     * ✅ الحصول على pendingEpcs (من ViewModel)
     */
    private Set<String> getPendingEpcs() {
        return viewModel != null 
                ? viewModel.getPendingEpcs() 
                : new java.util.HashSet<>();
    }

} // ✅ نهاية الكلاس MiraPosFragment
