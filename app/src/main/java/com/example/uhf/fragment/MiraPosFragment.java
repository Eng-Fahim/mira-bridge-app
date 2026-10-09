package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;

import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

import android.widget.TextView;
import android.widget.Toast;


import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.activity.OnBackPressedCallback;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputLayout;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.adapter.CartAdapter;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.dialog.POSCheckoutDialog;
import com.example.uhf.dialog.RefundDialog;
import com.example.uhf.manager.GateSaleBridge;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.utils.MiraExecutorService;
import com.example.uhf.manager.MiraItemSyncManager;
import com.example.uhf.manager.MiraLocalItemCache;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.POSManager;
import com.example.uhf.manager.PendingSalesManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.manager.PosCartManager;
import com.example.uhf.manager.PosHeroController;
import com.example.uhf.manager.PosPriceResolver;
import com.example.uhf.manager.PosUIManager;
import com.example.uhf.manager.SmartScaleManager;
import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.model.CartItem;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.tools.UIHelper;
import com.example.uhf.utils.ScaleSimulationHelper;
import com.example.uhf.navigation.NavigationManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MiraPosFragment v5.0 — Refactored Edition
 */
public class MiraPosFragment extends MiraBaseFragment {

    private static final String TAG = "MiraPosFragment";
    private static final String REFUND_DEBUG = "REFUND_DEBUG";
    
    /** ✅ عندما true — يتوقف تلقائياً بعد أول قراءة ناجحة */
    private volatile boolean stopAfterFirstRead = false;
    /** ✅ [جديد] حماية ذرّية لوضع One Shot */
    private final java.util.concurrent.atomic.AtomicBoolean oneShotConsumed 
        = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final int KEYCODE_RFID_TRIGGER_1 = 139;
    private static final int KEYCODE_RFID_TRIGGER_2 = 280;
    private static final int KEYCODE_RFID_TRIGGER_3 = 293;
    /** ✅ [جديد] منفّذ واحد لعمليات SDK — لا ننشئ Threads جديدة */
    private final java.util.concurrent.ExecutorService sdkExecutor 
        = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "POS-SDK");
            t.setDaemon(true);
            return t;
        });
    private static final long SCAN_COOLDOWN_MS = 1500;
    private static final long REFUND_MAX_DAYS = 30;
    private static final long LAST_SALE_VALIDITY_MS = 5 * 60 * 1000;
    private static final long ITEM_ADDED_DISPLAY_MS = 1000;
    private static final long AUTO_ADD_DELAY_MS = 350;
    private static final int  AUTO_ADD_MIN_LEN = 6;
    
    // ⭐ حالة وضع المسح الموحدة
    private enum ScanMode { NONE, RFID, MANUAL, GS1 }
    private volatile ScanMode currentScanMode = ScanMode.NONE;
    // ═══════════════════════════════════════════════════════
    // Managers
    // ═══════════════════════════════════════════════════════
    private SessionManager sessionManager;
    private SmartScaleManager scaleManager;
    private ScaleSimulationHelper simulationHelper;
    private POSManager posManager;
    private PendingSalesManager pendingSalesManager;
    private RefundDialog refundDialog;
    private MiraExecutorService miraExecutor;
    private MiraApiClient apiClient;
    private MiraPriceManager priceManager;
    private MiraBranchesManager branchesManager;
    private PermissionManager permissionManager;

    private PosCartManager cartManager;
    private PosPriceResolver priceResolver;
    private PosHeroController heroController;

    private RFIDManager rfidManager;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ═══════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════
    private volatile boolean isSyncing = false;
    private volatile boolean isCheckoutDialogShowing = false;
    private volatile boolean isRFIDScanActive = false;
    private volatile boolean isRFIDModeActive = false;
    private volatile double liveScaleWeight = 0.0;

    private final Set<String> pendingEpcs = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastScannedTimes = new ConcurrentHashMap<>();

    private volatile MiraApiClient.ApiResponse lastSaleResponse = null;
    private volatile long lastSaleResponseTime = 0;

    private final AtomicReference<RefundItem> selectedRefundItem = new AtomicReference<>(null);

    private ImageButton btnPosRefund;
    private ImageButton btnPosLastReceipt;
    
    private ImageButton btnPosClose;
    private OnBackPressedCallback backPressedCallback;


    // ═══════════════════════════════════════════════════════
    // Views
    // ═══════════════════════════════════════════════════════
    private TextView tvScaleStatus, tvItemCount, tvTotalAmount, tvCartStatus;
    private TextInputLayout tilManualEpc;
    private EditText etManualEpc;
    private TextView btnAddManual;
    private TextView btnCheckout;
    private TextView btnClearCart;

    private RecyclerView rvCartItems;
    private View layoutCartEmptyState;
    private View posHeroContainer;
    private View posManualInputContainer;
    private View posSummaryStrip;
    private View posFooterSticky;

    private View btnPosScanGs1, btnPosScanRfid, btnPosScanManual;

    private CartAdapter cartAdapter;

    // ═══════════════════════════════════════════════════════
    // Helper classes
    // ═══════════════════════════════════════════════════════

    private static final class RefundItem {
        final String epc, serialNo, title;
        final int itemId;
        final long saleId;
        final double originalPrice;

        RefundItem(String epc, String serialNo, int itemId, long saleId,
                   double originalPrice, String title) {
            this.epc = epc;
            this.serialNo = serialNo;
            this.itemId = itemId;
            this.saleId = saleId;
            this.originalPrice = originalPrice;
            this.title = title;
        }
    }

    private static final class RefundEligibility {
        final boolean isEligible;
        final String reason;
        final long saleId;
        final int itemId;
        final double originalPrice;
        final String originalSaleDate;

        private RefundEligibility(boolean isEligible, String reason, long saleId,
                                  int itemId, double originalPrice, String originalSaleDate) {
            this.isEligible = isEligible;
            this.reason = reason;
            this.saleId = saleId;
            this.itemId = itemId;
            this.originalPrice = originalPrice;
            this.originalSaleDate = originalSaleDate;
        }

        static RefundEligibility eligible(long saleId, int itemId, double price, String date) {
            return new RefundEligibility(true, null, saleId, itemId, price, date);
        }

        static RefundEligibility notEligible(String reason) {
            return new RefundEligibility(false, reason, 0, 0, 0, "");
        }
    }

    // ═══════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
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
            Context ctx = requireContext().getApplicationContext();

            branchesManager = MiraBranchesManager.getInstance(ctx);
            priceManager = MiraPriceManager.getInstance(ctx);
            permissionManager = PermissionManager.getInstance(ctx);
            sessionManager = new SessionManager(ctx);
            posManager = new POSManager(ctx);
            pendingSalesManager = new PendingSalesManager(ctx);

            miraExecutor = MiraExecutorService.getInstance();
            if (miraExecutor == null || !miraExecutor.isHealthy()) {
                Log.e(TAG, "MiraExecutorService unhealthy");
                showToast(getString(R.string.pos_msg_engine_error));
                return;
            }

            priceResolver = new PosPriceResolver(priceManager);
            cartManager = PosCartManager.getInstance();

            rfidManager = RFIDManager.getInstance(ctx);
            if (rfidManager != null) {
                rfidManager.setListener(new MiraIRFIDReader.OnTagReadListener() {
                    @Override
                    public void onTagRead(String epc, int rssi) {
                        if (isRFIDScanActive && isRFIDModeActive) onRingScanned(epc);
                    }
                    @Override public void onScanStarted() {}
                    @Override public void onScanStopped() {}
                    @Override
                    public void onConnectionChanged(boolean connected) {
                        safeRun(() -> {
                            if (btnPosScanRfid != null && isRFIDModeActive) {
                                btnPosScanRfid.setAlpha(connected ? 1.0f : 0.5f);
                            }
                        });
                    }
                    @Override public void onError(String error) {
                        Log.e(TAG, "RFID Error: " + error);
                    }
                });
            }

            apiClient = MiraApiClient.getInstance(ctx);

            bindViews(view);
            setupHeroController(view);
            setupCartListener();
            setupRecyclerView();
            setupListeners();
            applyPermissions();
            // ✅ [جديد] إخفاء زر RFID على الأجهزة التي لا تحتوي على قارئ مدمج
try {
    boolean hasRfid = com.example.uhf.tools.DeviceDetector.isRfidHardwareSupported();
    if (!hasRfid && btnPosScanRfid != null) {
        btnPosScanRfid.setVisibility(View.GONE);
        Log.i(TAG, "📱 Phone mode: RFID button hidden");
    } else if (hasRfid && btnPosScanRfid != null) {
        btnPosScanRfid.setVisibility(View.VISIBLE);
        Log.i(TAG, "📡 Reader mode: RFID button visible");
    }
} catch (Exception e) {
    Log.w(TAG, "RFID button visibility error: " + e.getMessage());
}
            initSmartScale();
            updateCartSummary();

            heroController.setState(PosHeroController.State.IDLE);
            setupBackPressedHandler();

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
            View fab = getActivity().findViewById(R.id.fabActionCenter);
            if (fab != null) {
                fab.setVisibility(View.GONE);
                Log.d(TAG, "🙈 FAB hidden in POS");
            }
        }
        if (scaleManager != null) scaleManager.connect();
        updateCartSummary();
// ✅ تقليل الطاقة في وضع POS لمنع القراءة الزائدة
applyLowPowerForPos();

    } catch (Exception e) {
        Log.e(TAG, "onResume error: " + e.getMessage());
    }
}
/**
 * ✅ يقلل قوة الإرسال في وضع POS لمنع قراءة القطع المجاورة
 *    يُستدعى عند فتح POS
 *    يُعاد التعيين عند الإغلاق (onDestroyView)
 */
private void applyLowPowerForPos() {
    sdkExecutor.execute(() -> {
        try {
            com.example.uhf.MiraRfid.MiraReaderSettings settings =
                    com.example.uhf.MiraRfid.MiraReaderSettings.getInstance(requireContext());

            // ✅ احفظ قيمة Power الأصلية للاستعادة لاحقاً
            int originalPower = settings.getPowerDbm();

            // ✅ Power منخفض لـ POS = 15 dBm (مدى ~10-15 سم فقط)
            int posPower = 15;

            if (originalPower != posPower) {
                settings.setPowerDbm(posPower);

                // طبّق على القارئ
                com.rscja.deviceapi.RFIDWithUHFUART reader =
                        com.example.uhf.activity.BaseTabFragmentActivity.getSharedReader();
                if (reader != null) {
                    try {
                        reader.setPower(posPower);
                        Log.i(TAG, "⚡ POS Low-Power applied: " + posPower + " dBm");
                    } catch (Exception e) {
                        Log.w(TAG, "setPower failed", e);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "applyLowPowerForPos error", e);
        }
    });
}

/**
 * ✅ استعادة قوة الإرسال الأصلية عند إغلاق POS
 */
private void restoreOriginalPower() {
    sdkExecutor.execute(() -> {
        try {
            com.example.uhf.MiraRfid.MiraReaderSettings settings =
                    com.example.uhf.MiraRfid.MiraReaderSettings.getInstance(requireContext());

            // ✅ استعادة 27 dBm
            settings.setPowerDbm(27);

            com.rscja.deviceapi.RFIDWithUHFUART reader =
                    com.example.uhf.activity.BaseTabFragmentActivity.getSharedReader();
            if (reader != null) {
                try {
                    reader.setPower(27);
                    Log.i(TAG, "⚡ Original power restored: 27 dBm");
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "restoreOriginalPower error", e);
        }
    });
}
@Override
public void onPause() {
    super.onPause();
    try {
        // ⭐ عالج كل الأوضاع (RFID + MANUAL + GS1)
        if (currentScanMode != ScanMode.NONE) {
            switchScanMode(ScanMode.NONE);
        } else {
            // احتياط
            stopRFIDScan();
            isRFIDModeActive = false;
            // احتياط: أخفِ الإدخال اليدوي لو ظاهر
            try {
                if (posManualInputContainer != null
                        && posManualInputContainer.getVisibility() == View.VISIBLE) {
                    hideManualInput();
                }
            } catch (Exception ignored) {}
        }

        isCheckoutDialogShowing = false;
        if (heroController != null) {
            heroController.setState(PosHeroController.State.IDLE);
        }
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setActivePOSFragment(null);
            View fab = getActivity().findViewById(R.id.fabActionCenter);
            if (fab != null) {
                fab.setVisibility(View.VISIBLE);
                Log.d(TAG, "👁️ FAB restored");
            }
        }
    } catch (Exception e) {
        Log.e(TAG, "onPause error: " + e.getMessage());
    }
}

    @Override
    public void onStart() {
        super.onStart();
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setBottomNavVisible(false);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setBottomNavVisible(true);
        }
    }
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try {
            stopRFIDScan();
            isCheckoutDialogShowing = false;
            
            // ⭐ إزالة معالج زر الرجوع
if (backPressedCallback != null) {
    backPressedCallback.setEnabled(false);
    backPressedCallback.remove();
    backPressedCallback = null;
    Log.d(TAG, "🗑️ Back press handler removed");
}

            if (rfidManager != null) rfidManager.setListener(null);
            if (scaleManager != null) scaleManager.cleanup();
            if (simulationHelper != null) simulationHelper.cleanup();

            if (heroController != null) {
                heroController.cleanup();
                heroController = null;
            }

            if (rvCartItems != null) {
                rvCartItems.setAdapter(null);
                rvCartItems.setLayoutManager(null);
            }
            cartAdapter = null;

            pendingEpcs.clear();
            lastScannedTimes.clear();
            selectedRefundItem.set(null);
            restoreOriginalPower();
// ⭐ تصفير الحالة
currentScanMode = ScanMode.NONE;
isRFIDModeActive = false;
            if (mainHandler != null) {
                mainHandler.removeCallbacksAndMessages(null);
            }
        } catch (Exception e) {
            Log.e(TAG, "onDestroyView error: " + e.getMessage());
        }
        // ✅ [جديد] أوقف منفّذ SDK
try {
    if (sdkExecutor != null && !sdkExecutor.isShutdown()) {
        sdkExecutor.shutdown();
    }
} catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            stopRFIDScan();
            if (scaleManager != null) scaleManager.cleanup();
        } catch (Exception e) {
            Log.e(TAG, "onDestroy error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    // Views Binding
    // ═══════════════════════════════════════════════════════

    private void bindViews(View view) {
        tvScaleStatus = view.findViewById(R.id.tvPosScaleStatus);
        tvItemCount = view.findViewById(R.id.tvPosItemCount);
        tvTotalAmount = view.findViewById(R.id.tvPosTotalAmount);
        tvCartStatus = view.findViewById(R.id.tvPosCartStatus);
        tilManualEpc = view.findViewById(R.id.tilPosManualEpc);
        etManualEpc = view.findViewById(R.id.etPosManualEpc);
        btnAddManual = view.findViewById(R.id.btnPosAddManual);
        if (btnAddManual != null) {
            btnAddManual.setVisibility(View.GONE);
        }
        btnCheckout = view.findViewById(R.id.btnPosCheckout);
        btnClearCart = view.findViewById(R.id.btnPosClearCart);
        btnPosRefund = view.findViewById(R.id.btnPosRefund);
        btnPosLastReceipt = view.findViewById(R.id.btnPosLastReceipt);
        btnPosClose = view.findViewById(R.id.btnPosClose);

        rvCartItems = view.findViewById(R.id.rvPosCartItems);
        layoutCartEmptyState = view.findViewById(R.id.layoutCartEmptyState);
        posHeroContainer = view.findViewById(R.id.posHeroContainer);
        posManualInputContainer = view.findViewById(R.id.posManualInputContainer);
        posSummaryStrip = view.findViewById(R.id.posSummaryStrip);
        posFooterSticky = view.findViewById(R.id.posFooterSticky);

        btnPosScanGs1 = view.findViewById(R.id.btnPosScanGs1);
        btnPosScanRfid = view.findViewById(R.id.btnPosScanRfid);
        btnPosScanManual = view.findViewById(R.id.btnPosScanManual);

        if (tvScaleStatus != null) {
            tvScaleStatus.setOnClickListener(v -> {
                if (scaleManager != null) scaleManager.showSettingsDialog();
            });
        }
        if (etManualEpc != null) etManualEpc.clearFocus();
    }

    private void setupHeroController(View view) {
        try {
            heroController = new PosHeroController(
                    view.findViewById(R.id.posHeroGlow),
                    view.findViewById(R.id.posHeroRing),
                    view.findViewById(R.id.ivPosHeroIcon),
                    view.findViewById(R.id.ivPosHeroSparkleTop),
                    view.findViewById(R.id.ivPosHeroSparkleBottom),
                    view.findViewById(R.id.tvPosHeroTitleAr),
                    view.findViewById(R.id.tvPosHeroTitleEn),
                    view.findViewById(R.id.posHeroDivider),
                    view.findViewById(R.id.tvPosHeroMessage),
                    view.findViewById(R.id.tvPosHeroHint),
                    view.findViewById(R.id.tvPosRfidCount),
                    view.findViewById(R.id.btnPosScanGs1),
                    view.findViewById(R.id.btnPosScanRfid),
                    view.findViewById(R.id.btnPosScanManual),
                    view.findViewById(R.id.ivPosGs1Icon),
                    view.findViewById(R.id.ivPosRfidIcon),
                    view.findViewById(R.id.ivPosManualIcon),
                    view.findViewById(R.id.tvPosGs1Label),
                    view.findViewById(R.id.tvPosRfidLabel),
                    view.findViewById(R.id.tvPosManualLabel)
            );
        } catch (Exception e) {
            Log.e(TAG, "setupHeroController error: " + e.getMessage(), e);
        }
    }

    private void setupCartListener() {
        if (cartManager == null) return;

        cartManager.setListener(new PosCartManager.OnCartChangedListener() {
            @Override
public void onCartChanged(int count, double totalUsd, boolean hasUnavailable) {
    updateCartSummaryFromManager(count, totalUsd, hasUnavailable);

    // ✅ [إصلاح] أعِد فرض ظهور القائمة
    safeRun(() -> {
        if (rvCartItems != null && count > 0) {
            rvCartItems.setVisibility(View.VISIBLE);
        }
        if (posFooterSticky != null && count > 0) {
            posFooterSticky.setVisibility(View.VISIBLE);
            posFooterSticky.setAlpha(1f);
            posFooterSticky.setTranslationY(0f);
        }
    });
}

            @Override
public void onItemAdded(int position) {
    if (cartAdapter != null) cartAdapter.notifyItemInserted(position);
    if (rvCartItems != null) rvCartItems.scrollToPosition(0);

    // ✅ [إصلاح] حدّث الملخص فوراً — يُظهر السلة
    updateCartSummary();
}

            @Override
            public void onItemRemoved(int position) {
                if (cartAdapter != null) cartAdapter.notifyItemRemoved(position);
            }

            @Override
            public void onCartCleared() {
                if (cartAdapter != null) cartAdapter.notifyDataSetChanged();
            }
        });
    }

    private void setupRecyclerView() {
        try {
            if (rvCartItems != null && cartManager != null) {
                rvCartItems.setLayoutManager(new LinearLayoutManager(getContext()));
                cartAdapter = new CartAdapter(
                        cartManager.getItems(),
                        position -> cartManager.removeItemAt(position),
                        isArabic,
                        priceManager
                );
                rvCartItems.setAdapter(cartAdapter);
            }
        } catch (Exception e) {
            Log.e(TAG, "setupRecyclerView error: " + e.getMessage());
        }
    }

    private void setupListeners() {
        try {
            // ✅ إضافة تلقائية بعد 350ms من آخر حرف (≥ 6 أحرف)
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

                final Runnable[] pendingAuto = new Runnable[1];
                etManualEpc.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                    @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        if (pendingAuto[0] != null) {
                            mainHandler.removeCallbacks(pendingAuto[0]);
                        }
                        final String code = s.toString().trim();
                        if (code.length() < AUTO_ADD_MIN_LEN) return;

                        pendingAuto[0] = () -> {
                            if (etManualEpc != null
                                    && code.equals(etManualEpc.getText()
                                            .toString().trim())
                                    && isAdded()) {
                                submitManualEpc();
                            }
                        };
                        mainHandler.postDelayed(pendingAuto[0], AUTO_ADD_DELAY_MS);
                    }
                });
            }

            if (btnClearCart != null) {
                btnClearCart.setOnClickListener(v -> {
                    UIHelper.hideKeyboard(getActivity());
                    cartManager.clear();
                });
            }

            if (btnCheckout != null) {
                btnCheckout.setOnClickListener(v -> {
                    UIHelper.hideKeyboard(getActivity());
                    processCheckoutAndStockUpdate();
                });
            }

            // ⭐ GS1 — Barcode
if (btnPosScanGs1 != null) {
    btnPosScanGs1.setOnClickListener(v -> {
        UIHelper.hideKeyboard(getActivity());
        switchScanMode(ScanMode.GS1);
    });
}

// ⭐ RFID — Toggle
if (btnPosScanRfid != null) {
    btnPosScanRfid.setOnClickListener(v -> {
        UIHelper.hideKeyboard(getActivity());

        if (currentScanMode == ScanMode.RFID) {
            switchScanMode(ScanMode.NONE);
        } else {
            switchScanMode(ScanMode.RFID);
        }
    });
}
// ⭐ Manual
if (btnPosScanManual != null) {
    btnPosScanManual.setOnClickListener(v -> {
        UIHelper.hideKeyboard(getActivity());
        // إذا Manual مفتوح → أغلقه | وإلا → افتحه
        if (currentScanMode == ScanMode.MANUAL) {
            switchScanMode(ScanMode.NONE);
        } else {
            switchScanMode(ScanMode.MANUAL);
        }
    });
}

            if (btnPosRefund != null) {
                btnPosRefund.setOnClickListener(v -> {
                    UIHelper.hideKeyboard(getActivity());
                    showRefundDialog();
                });
            }

            if (btnPosLastReceipt != null) {
                btnPosLastReceipt.setOnClickListener(v -> {
                    UIHelper.hideKeyboard(getActivity());
                    showLastReceiptDialog();
                });
            }
            // ⭐ زر الإغلاق في الأعلى
if (btnPosClose != null) {
    btnPosClose.setOnClickListener(v -> {
        UIHelper.hideKeyboard(getActivity());
        handleCustomBack();
    });
}
        } catch (Exception e) {
            Log.e(TAG, "setupListeners error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    // Manual Input
    // ═══════════════════════════════════════════════════════

    private void showManualInput() {
        try {
            if (heroController != null) {
                heroController.setState(PosHeroController.State.MANUAL_ENTRY);
            }
            if (posManualInputContainer != null) {
                posManualInputContainer.setVisibility(View.VISIBLE);
                posManualInputContainer.startAnimation(
                        android.view.animation.AnimationUtils.loadAnimation(
                                requireContext(), R.anim.pos_slide_up));
            }
            if (etManualEpc != null) {
                etManualEpc.requestFocus();
                showKeyboard(etManualEpc);
            }
        } catch (Exception e) {
            Log.e(TAG, "showManualInput error: " + e.getMessage());
        }
    }

    private void hideManualInput() {
        try {
            if (posManualInputContainer != null) {
                posManualInputContainer.setVisibility(View.GONE);
            }
            if (etManualEpc != null) {
                etManualEpc.setText("");
                etManualEpc.clearFocus();
            }
        } catch (Exception e) {
            Log.e(TAG, "hideManualInput error: " + e.getMessage());
        }
    }

    private void submitManualEpc() {
        try {
            if (!hasPosPermission()) {
                showToast(getString(R.string.pos_msg_no_permission));
                return;
            }

            UIHelper.hideKeyboard(getActivity());

            String epc = etManualEpc != null
                    ? etManualEpc.getText().toString().trim() : "";

            if (epc.isEmpty()) {
                showToast(getString(R.string.pos_msg_not_found));
                return;
            }

            if (cartManager != null && cartManager.isIdScanned(epc)) {
                showToast(getString(R.string.pos_msg_already_in_cart));
                playAlertSound(2);
                return;
            }

            processRingEpc(epc, "manual");

            if (etManualEpc != null) etManualEpc.setText("");
            hideManualInput();

            if (heroController != null) {
                heroController.setState(PosHeroController.State.IDLE);
            }
        } catch (Exception e) {
            Log.e(TAG, "submitManualEpc error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    // Process Item
    // ═══════════════════════════════════════════════════════

    private void processRingEpc(final String code, final String source) {
        if (code == null || code.trim().isEmpty() || !isAdded()) return;
        final String cleanCode = code.trim();

        if (!pendingEpcs.add(cleanCode)) return;

        playAlertSound(1);

        safeRun(() -> {
            if (tvCartStatus != null) {
                tvCartStatus.setText(getString(R.string.pos_msg_searching));
            }
        });

        try {
            miraExecutor.getNetworkExecutor().execute(() -> {
                try {
                    Context context = getContext();
                    if (context == null) return;

                    MiraItemSyncManager syncManager =
                            MiraItemSyncManager.getInstance(context);
                    final int branchId = getActiveBranchId();

                    syncManager.findItem(cleanCode, branchId,
                            new MiraItemSyncManager.ItemLookupCallback() {
                        @Override
                        public void onItemFound(JSONObject item, boolean fromServer) {
                            safeRun(() -> {
                                int itemBranchId = item.optInt("branch_id", -1);
                                if (itemBranchId > 0 && itemBranchId != branchId) {
                                    showToast(getString(R.string.pos_msg_other_branch));
                                    playAlertSound(2);
                                    resetHeroAfterDelay();
                                    return;
                                }
                                parseItemResponse(cleanCode, item, source);
                            });
                        }

                        @Override
                        public void onItemNotFound() {
                            safeRun(() -> {
                                showToast(getString(R.string.pos_msg_not_found));
                                playAlertSound(2);
                                setHeroError();
                                resetHeroAfterDelay();
                            });
                        }
                    });
                } catch (Exception e) {
                    safeRun(() -> {
                        Log.e(TAG, "processRingEpc error: " + e.getMessage());
                        setHeroError();
                        resetHeroAfterDelay();
                    });
                } finally {
                    pendingEpcs.remove(cleanCode);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "processRingEpc submit error: " + e.getMessage());
            pendingEpcs.remove(cleanCode);
        }
    }

    private void parseItemResponse(String code, JSONObject data, String source) {
        try {
            JSONObject item = data.has("item") ? data.optJSONObject("item") : data;
            if (item == null) {
                showToast(getString(R.string.pos_msg_not_found));
                playAlertSound(2);
                setHeroError();
                resetHeroAfterDelay();
                return;
            }

            String vaultStatus = item.optString("status",
                    item.optString("vault_status", "")).toLowerCase();

            if ("sold".equals(vaultStatus) || "exited".equals(vaultStatus)) {
                showToast(getString(R.string.pos_msg_already_sold));
                playAlertSound(2);
                setHeroError();
                resetHeroAfterDelay();
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

            int karatNum = getKaratNumber(karat);

            PosPriceResolver.PriceResult priceResult =
                    priceResolver.resolve(item, karatNum, weight);
            double price = priceResult.priceUsd;

            Log.d(TAG, "💰 Price resolved: " + price
                    + " (source=" + priceResult.source + ")");

            CartItem cartItem = new CartItem(code, title, karat, weight,
                    liveScaleWeight, price, isAvailable,
                    vaultStatus.toUpperCase());

            cartItem.serialNo = extractString(item, "serial", "serial_number",
                    "serialNo", "sn");
            cartItem.gtin = extractString(item, "gtin", "gtin13", "barcode", "code");
            cartItem.location = extractString(item, "location",
                    "current_location", "zone");
            cartItem.itemId = item.optInt("id",
                    item.optInt("item_id", item.optInt("product_id", 0)));

            String tid = extractString(item, "tid", "tag_tid", "rfid_tid");
            if (!tid.isEmpty() && cartItem.serialNo.isEmpty()) {
                cartItem.serialNo = tid;
            }
            cartItem.tid = tid;

            String rfidEpcFromServer = extractString(item, "rfid_epc", "epc_final");
            if (!rfidEpcFromServer.isEmpty()) {
                cartItem.rfidEpc = rfidEpcFromServer;
                cartItem.epc = rfidEpcFromServer;
            } else {
                cartItem.rfidEpc = code;
            }

            boolean alreadyInCart = cartManager != null
                    && cartManager.containsItem(cartItem.epc,
                    cartItem.serialNo, cartItem.gtin);

            safeRun(() -> {
                if (alreadyInCart) {
                    showToast(getString(R.string.pos_msg_already_in_cart));
                    playAlertSound(2);
                    resetHeroAfterDelay();
                    return;
                }

                if (!isAvailable) {
                    showToast(getString(R.string.pos_msg_unavailable));
                    resetHeroAfterDelay();
                    return;
                }

                int pos = cartManager.addItem(cartItem);
                if (pos < 0) {
                    showToast(getString(R.string.pos_msg_already_in_cart));
                    resetHeroAfterDelay();
                    return;
                }

                playAlertSound(1);
                showToast(getString(R.string.pos_msg_added, title));

                if (heroController != null) {
                    heroController.setState(PosHeroController.State.ITEM_ADDED);
                    if (source != null && source.equals("rfid")) {
                        heroController.incrementRfidCount();
                    }
                }
                resetHeroAfterDelay();
            });

        } catch (Exception e) {
            Log.e(TAG, "parseItemResponse error: " + e.getMessage(), e);
            safeRun(() -> {
                showToast(getString(R.string.pos_msg_not_found));
                resetHeroAfterDelay();
            });
        }
    }

    private void setHeroError() {
        if (heroController != null) {
            heroController.setState(PosHeroController.State.ERROR);
        }
    }

    private void resetHeroAfterDelay() {
        mainHandler.postDelayed(() -> {
            if (isAdded() && heroController != null) {
                PosHeroController.State s = heroController.getState();
                if (s == PosHeroController.State.ITEM_ADDED
                        || s == PosHeroController.State.ERROR) {
                    heroController.setState(PosHeroController.State.IDLE);
                }
            }
        }, ITEM_ADDED_DISPLAY_MS);
    }

    // ═══════════════════════════════════════════════════════
    // Cart Summary
    // ═══════════════════════════════════════════════════════

    private void updateCartSummary() {
        if (cartManager == null) return;
        updateCartSummaryFromManager(
                cartManager.size(),
                cartManager.getTotalUsd(),
                cartManager.hasUnavailableItems()
        );
    }

    private void updateCartSummaryFromManager(int count, double totalUsd,
                                              boolean hasUnavailable) {
        safeRun(() -> {
            try {
                // Hero visibility
                if (posHeroContainer != null) {
                    posHeroContainer.setVisibility(count == 0
                            ? View.VISIBLE : View.GONE);
                }
                if (rvCartItems != null) {
                    rvCartItems.setVisibility(count > 0
                            ? View.VISIBLE : View.GONE);
                }

                // Footer Sticky
                if (posFooterSticky != null) {
                    if (count > 0) {
                        if (posFooterSticky.getVisibility() != View.VISIBLE) {
                            posFooterSticky.setVisibility(View.VISIBLE);
                            posFooterSticky.setTranslationY(
                                    posFooterSticky.getHeight() > 0
                                            ? posFooterSticky.getHeight() : 300f);
                            posFooterSticky.animate()
                                    .translationY(0f)
                                    .setDuration(300)
                                    .setInterpolator(
                                            new android.view.animation.DecelerateInterpolator())
                                    .start();
                        }
                    } else {
                        if (posFooterSticky.getVisibility() == View.VISIBLE) {
                            posFooterSticky.animate()
                                    .translationY(posFooterSticky.getHeight())
                                    .setDuration(250)
                                    .setInterpolator(
                                            new android.view.animation.AccelerateInterpolator())
                                    .withEndAction(() -> {
                                        if (posFooterSticky != null) {
                                            posFooterSticky.setVisibility(View.GONE);
                                            posFooterSticky.setTranslationY(0f);
                                        }
                                    })
                                    .start();
                        }
                    }
                }

                // Count
                if (tvItemCount != null) {
                    tvItemCount.setText(String.valueOf(count));
                }

                // ✅ Total amount — Bulletproof
                if (tvTotalAmount != null) {
                    String totalText = buildTotalText(count, totalUsd);
                    tvTotalAmount.setText(totalText);
                    tvTotalAmount.setVisibility(View.VISIBLE);

                    Log.e(TAG, "💰 [SUMMARY] count=" + count
                            + " | totalUsd=" + totalUsd
                            + " | text='" + totalText + "'"
                            + " | len=" + totalText.length());
                }

                // Status
                if (tvCartStatus != null) {
                    if (count == 0) {
                        tvCartStatus.setText(getString(R.string.pos_cart_empty));
                    } else if (hasUnavailable) {
                        tvCartStatus.setText(getString(R.string.pos_cart_unavailable));
                    } else {
                        tvCartStatus.setText(getString(R.string.pos_cart_ready));
                    }
                }

                // Summary strip state
                if (posSummaryStrip != null) {
                    posSummaryStrip.setSelected(count > 0);
                }

                // CTA
                if (btnCheckout != null) {
                    boolean enabled = count > 0 && !hasUnavailable
                            && hasPosPermission();
                    btnCheckout.setEnabled(enabled);
                    btnCheckout.setAlpha(enabled ? 1f : 0.5f);
                }
                if (btnClearCart != null) {
                    btnClearCart.setEnabled(count > 0);
                }
            } catch (Exception e) {
                Log.e(TAG, "updateCartSummaryFromManager error: " + e.getMessage());
            }
        });
    }

    /**
     * ✅ بناء نص الإجمالي — مضمون 100%
     */
    private String buildTotalText(int count, double totalUsd) {
        // count == 0 → القيمة الافتراضية
        if (count <= 0) {
            String def = getString(R.string.pos_default_amount);
            if (def == null || def.trim().isEmpty()) {
                return "0";
            }
            return def;
        }

        // count > 0 → حاول عبر priceManager
        if (priceManager != null) {
            try {
                String formatted = priceManager.formatPriceDefault(totalUsd);
                if (formatted != null
                        && !formatted.trim().isEmpty()
                        && !"—".equals(formatted.trim())
                        && hasDigit(formatted)) {
                    return formatted;
                }
                Log.e(TAG, "💰 formatPriceDefault invalid: '" + formatted
                        + "' — using fallback");
            } catch (Exception e) {
                Log.e(TAG, "💰 formatPriceDefault error: " + e.getMessage());
            }

            // Fallback مباشر عبر priceManager
            try {
                String currency = priceManager.getDefaultCurrency();
                double rate = priceManager.getExchangeRate(currency);
                double local = totalUsd * rate;
                return String.format(Locale.US, "%,.2f %s", local, currency);
            } catch (Exception e) {
                Log.e(TAG, "💰 fallback calc error: " + e.getMessage());
            }
        }

        // Fallback نهائي — USD
        return String.format(Locale.US, "$%,.2f", totalUsd);
    }

    private static boolean hasDigit(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════
    // Checkout
    // ═══════════════════════════════════════════════════════

    private void processCheckoutAndStockUpdate() {
        try {
            if (!hasPosPermission()) {
                showToast(getString(R.string.pos_msg_no_permission));
                return;
            }
            if (cartManager == null || cartManager.isEmpty()) {
                showToast(getString(R.string.pos_msg_cart_empty_toast));
                return;
            }
            if (isCheckoutDialogShowing) return;
            if (!isAdded() || getActivity() == null) return;

            UIHelper.hideKeyboard(getActivity());
            isCheckoutDialogShowing = true;

            POSCheckoutDialog dialog = POSCheckoutDialog.newInstance(
                    cartManager.getTotalUsd(), cartManager.size());

            dialog.setCheckoutListener(data -> {
                isCheckoutDialogShowing = false;
                executeCheckoutDirectly(data);
            });

            dialog.setOnDismissListener(() -> {
                isCheckoutDialogShowing = false;
            });

            FragmentManager fm = getParentFragmentManager();
            if (fm != null && !fm.isStateSaved()) {
                dialog.show(fm, "POSCheckoutDialog");
            } else {
                isCheckoutDialogShowing = false;
            }
        } catch (Exception e) {
            isCheckoutDialogShowing = false;
            Log.e(TAG, "processCheckout error: " + e.getMessage(), e);
        }
    }

    private void executeCheckoutDirectly(POSCheckoutDialog.POSCheckoutData data) {
        try {
            if (!isAdded() || data == null) return;

            setButtonsEnabled(false);
            final AlertDialog loadingDialog =
                    PosUIManager.showLoadingDialog(requireContext(), isArabic);

            miraExecutor.getNetworkExecutor().execute(() -> {
                try {
                    Context ctx = getContext();
                    if (ctx == null) return;

                    MiraApiClient client = MiraApiClient.getInstance(ctx);
                    JSONObject payload = buildCheckoutPayload(client, data);
                    MiraApiClient.ApiResponse response = client.checkout(payload);

                    safeRun(() -> {
                        PosUIManager.dismissDialog(loadingDialog);
                        setButtonsEnabled(true);

                        if (response != null && response.isSuccess) {
                            handleSaleSuccess(response, data, false);
                        } else if (response != null && response.isNetworkError) {
                            handleSaleSuccess(response, data, true);
                        } else {
                            String errorMsg = response != null && response.error != null
                                    ? response.error : "Unknown error";
                            PosUIManager.showRetryDialog(
                                    requireContext(),
                                    getString(R.string.pos_dialog_retry_title),
                                    errorMsg,
                                    () -> executeCheckoutDirectly(data),
                                    isArabic
                            );
                        }
                    });

                } catch (Exception e) {
                    Log.e(TAG, "executeCheckoutDirectly error: " + e.getMessage(), e);
                    safeRun(() -> {
                        PosUIManager.dismissDialog(loadingDialog);
                        setButtonsEnabled(true);
                        PosUIManager.showRetryDialog(
                                requireContext(),
                                getString(R.string.pos_dialog_error_title),
                                e.getMessage(),
                                () -> executeCheckoutDirectly(data),
                                isArabic
                        );
                    });
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "executeCheckoutDirectly outer error: " + e.getMessage());
        }
    }

    private void handleSaleSuccess(MiraApiClient.ApiResponse response,
                                   POSCheckoutDialog.POSCheckoutData data,
                                   boolean isOffline) {
        try {
            if (!isAdded()) return;

            playAlertSound(1);
            lastSaleResponse = response;
            lastSaleResponseTime = System.currentTimeMillis();

            final List<CartItem> soldItems = new ArrayList<>(cartManager.getItems());
            final long saleId = isOffline ? 0L : extractSaleIdFromResponse(response);

            GateSaleBridge.onSaleCompleted(
                    requireContext().getApplicationContext(),
                    soldItems,
                    saleId,
                    data.finalAmount,
                    getActiveBranchId(),
                    sessionManager != null ? sessionManager.getUserId() : 0,
                    isOffline
            );

            cartManager.clear();

            if (!isOffline) {
                showReceipt(response);
                PosUIManager.notifyDataChanged(requireContext(),
                        "sale", getActiveBranchId());
                showToast(getString(R.string.pos_msg_sale_success));
            } else {
                showToast(getString(R.string.pos_msg_sale_offline));
                PosUIManager.notifyDataChanged(requireContext(),
                        "sale_offline", getActiveBranchId());
            }

            if (heroController != null) {
                heroController.resetRfidCount();
                heroController.setState(PosHeroController.State.IDLE);
            }
        } catch (Exception e) {
            Log.e(TAG, "handleSaleSuccess error: " + e.getMessage());
        }
    }

    private JSONObject buildCheckoutPayload(MiraApiClient client,
                                            POSCheckoutDialog.POSCheckoutData data)
            throws Exception {

        JSONObject payload = new JSONObject();
        payload.put("action", "COMPLETE_POS_SALE");
        payload.put("gate_id", client.getGateId());
        payload.put("branch_id", getActiveBranchId());
        payload.put("user_id", sessionManager != null ? sessionManager.getUserId() : 0);
        payload.put("username", sessionManager != null
                ? sessionManager.getUsername() : "");

        String customerName = (data.customerName != null
                && !data.customerName.trim().isEmpty())
                ? data.customerName.trim()
                : (isArabic ? "عميل نقدي" : "Cash Customer");

        String customerPhone = (data.customerPhone != null
                && !data.customerPhone.trim().isEmpty()
                && !"—".equals(data.customerPhone.trim()))
                ? data.customerPhone.trim() : "";

        payload.put("customer_name", customerName);
        payload.put("customer_phone", customerPhone);

        JSONObject customerObj = new JSONObject();
        customerObj.put("name", customerName);
        customerObj.put("phone", customerPhone);
        customerObj.put("email", data.customerEmail != null ? data.customerEmail : "");
        customerObj.put("address", data.customerAddress != null ? data.customerAddress : "");
        customerObj.put("notes", data.customerNotes != null ? data.customerNotes : "");
        customerObj.put("payment_method", data.paymentMethod != null ? data.paymentMethod : "CASH");
        payload.put("customer", customerObj);

        JSONArray itemsArr = new JSONArray();
        for (CartItem item : cartManager.getItems()) {
            if (item == null) continue;
            JSONObject obj = item.toJson();
            if (obj.optString("serial_no", "").isEmpty()) {
                String fallback = !item.tid.isEmpty() ? item.tid
                        : !item.rfidEpc.isEmpty() ? item.rfidEpc
                        : item.epc;
                obj.put("serial_no", fallback);
            }
            itemsArr.put(obj);
        }
        payload.put("items", itemsArr);
        payload.put("item_count", cartManager.size());

        String currencyCode = priceManager.getDefaultCurrency();
        double exchangeRate = priceManager.getExchangeRate(currencyCode);

        double subtotalUsd = data.originalTotal;
        double discountUsd = data.discountAmount;
        double finalUsd = data.finalAmount;

        double subtotalLocal = priceManager.convertFromUSD(subtotalUsd, currencyCode);
        double discountLocal = priceManager.convertFromUSD(discountUsd, currencyCode);
        double finalLocal = priceManager.convertFromUSD(finalUsd, currencyCode);

        if (exchangeRate <= 0) {
            exchangeRate = 530.0;
            subtotalLocal = subtotalUsd * exchangeRate;
            discountLocal = discountUsd * exchangeRate;
            finalLocal = finalUsd * exchangeRate;
        }

        payload.put("subtotal_usd", subtotalUsd);
        payload.put("discount_usd", discountUsd);
        payload.put("total_usd", finalUsd);
        payload.put("total_amount_usd", finalUsd);
        payload.put("subtotal", subtotalLocal);
        payload.put("discount", discountLocal);
        payload.put("total", finalLocal);
        payload.put("total_amount", finalLocal);

        JSONObject payment = new JSONObject();
        payment.put("subtotal_usd", subtotalUsd);
        payment.put("discount_amount_usd", discountUsd);
        payment.put("final_amount_usd", finalUsd);
        payment.put("subtotal", subtotalLocal);
        payment.put("discount_amount", discountLocal);
        payment.put("final_amount", finalLocal);
        payment.put("exchange_rate", exchangeRate);
        payment.put("currency_code", currencyCode);
        payment.put("payment_method", data.paymentMethod != null
                ? data.paymentMethod : "CASH");
        payload.put("payment", payment);

        payload.put("notes", isArabic ? "بيع POS" : "POS Sale");
        return payload;
    }

    private void showReceipt(MiraApiClient.ApiResponse response) {
        try {
            if (!isAdded() || getActivity() == null) return;

            String receiptJson = "{}";
            if (response != null) {
                if (response.data != null) receiptJson = response.data.toString();
                else if (response.getBody() != null && !response.getBody().isEmpty()) {
                    receiptJson = response.getBody();
                }
            }

            MiraReceiptFragment receiptFragment =
                    MiraReceiptFragment.newInstance(receiptJson);
            FragmentManager fm = getParentFragmentManager();
            if (fm != null && !fm.isStateSaved()) {
                receiptFragment.show(fm, "MiraReceiptFragment");
            }
        } catch (Exception e) {
            Log.e(TAG, "showReceipt error: " + e.getMessage());
        }
    }

    private long extractSaleIdFromResponse(MiraApiClient.ApiResponse response) {
        if (response == null || response.data == null) return 0L;
        try {
            long id = response.data.optLong("sale_id",
                    response.data.optLong("saleId",
                            response.data.optLong("id", 0L)));
            if (id > 0) return id;

            JSONObject sale = response.data.optJSONObject("sale");
            if (sale != null) {
                id = sale.optLong("id", sale.optLong("sale_id", 0L));
                if (id > 0) return id;
            }

            JSONObject receipt = response.data.optJSONObject("receipt");
            if (receipt != null) {
                id = receipt.optLong("sale_id", receipt.optLong("id", 0L));
                if (id > 0) return id;
            }
        } catch (Exception e) {
            Log.w(TAG, "extractSaleId error: " + e.getMessage());
        }
        return 0L;
    }

    // ═══════════════════════════════════════════════════════
    // Refund
    // ═══════════════════════════════════════════════════════

    private void showRefundDialog() {
        if (!isAdded() || getContext() == null) return;

        try {
            selectedRefundItem.set(null);

            refundDialog = new RefundDialog(requireContext(),
                    new RefundDialog.RefundCallback() {
                @Override
                public void onRefundConfirm(String code, String reason) {
                    processRefund(code, reason);
                }

                @Override
                public void onBarcodeScan() {
                    try {
                        ScanOptions options = new ScanOptions();
                        options.setPrompt(getString(R.string.pos_refund_scan_prompt));
                        options.setBeepEnabled(false);
                        options.setOrientationLocked(true);
                        refundBarcodeLauncher.launch(options);
                    } catch (Exception e) {
                        Log.e(TAG, "onBarcodeScan error: " + e.getMessage());
                    }
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

    private void searchItemForRefund(String code) {
        if (code == null || code.trim().isEmpty()) return;
        final String cleanCode = code.trim();

        selectedRefundItem.set(null);

        try {
            miraExecutor.getNetworkExecutor().execute(() -> {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("code", cleanCode);
                    payload.put("search_type", "auto");
                    payload.put("branch_id", getActiveBranchId());
                    payload.put("context", "refund_check");
                    payload.put("gate_id", apiClient != null
                            ? apiClient.getGateId() : "");
                    payload.put("user_id", sessionManager != null
                            ? sessionManager.getUserId() : 0);
                    payload.put("username", sessionManager != null
                            ? sessionManager.getUsername() : "");

                    MiraApiClient client = MiraApiClient.getInstance(requireContext());
                    MiraApiClient.ApiResponse response = client.searchItem(payload);

                    safeRun(() -> handleRefundSearchResponse(response, cleanCode));
                } catch (Exception e) {
                    Log.e(TAG, "searchItemForRefund error: " + e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "searchItemForRefund submit error: " + e.getMessage());
        }
    }

    private void handleRefundSearchResponse(MiraApiClient.ApiResponse response,
                                            String code) {
        if (!isAdded()) return;

        if (response != null && response.isSuccess && response.data != null) {
            JSONObject itemData = response.data.optJSONObject("item");
            if (itemData == null) itemData = response.data;

            RefundEligibility eligibility = checkRefundEligibility(itemData);

            if (eligibility.isEligible) {
                selectedRefundItem.set(new RefundItem(
                        itemData.optString("epc", code),
                        itemData.optString("serial",
                                itemData.optString("serial_number", "")),
                        eligibility.itemId,
                        eligibility.saleId,
                        eligibility.originalPrice,
                        itemData.optString("title",
                                itemData.optString("item_title", ""))
                ));

                showRefundItemInfoInDialog(itemData, eligibility);
            } else {
                showRefundItemErrorInDialog(eligibility.reason);
            }
        } else {
            String errorMsg = response != null && response.error != null
                    ? response.error : getString(R.string.pos_msg_not_found);
            showRefundItemErrorInDialog(errorMsg);
        }
    }

    private RefundEligibility checkRefundEligibility(JSONObject itemData) {
        if (itemData == null) {
            return RefundEligibility.notEligible(isArabic
                    ? "⚠️ لا توجد بيانات" : "⚠️ No data");
        }

        try {
            String status = itemData.optString("status",
                    itemData.optString("vault_status", "")).toLowerCase();

            if (!"sold".equals(status)) {
                return RefundEligibility.notEligible(isArabic
                        ? "⚠️ القطعة غير مباعة" : "⚠️ Item not sold");
            }

            String lastAction = itemData.optString("last_action", "").toLowerCase();
            if (!lastAction.isEmpty() && !"sale".equals(lastAction)
                    && !"pos_sale".equals(lastAction)) {
                return RefundEligibility.notEligible(isArabic
                        ? "⚠️ آخر عملية ليست بيعاً" : "⚠️ Last action not a sale");
            }

            String saleDateStr = itemData.optString("sale_date",
                    itemData.optString("sold_at", ""));
            if (!saleDateStr.isEmpty()) {
                Long daysDiff = parseDaysDifference(saleDateStr);
                if (daysDiff != null && daysDiff > REFUND_MAX_DAYS) {
                    return RefundEligibility.notEligible(isArabic
                            ? "⚠️ انتهت فترة الإرجاع" : "⚠️ Refund period expired");
                }
            }

            if (itemData.optBoolean("is_refunded", false)) {
                return RefundEligibility.notEligible(isArabic
                        ? "⚠️ تم الإرجاع مسبقاً" : "⚠️ Already refunded");
            }

            return RefundEligibility.eligible(
                    itemData.optLong("sale_id", 0),
                    itemData.optInt("id", itemData.optInt("item_id", 0)),
                    itemData.optDouble("retail_price_usd",
                            itemData.optDouble("retail_price", 0)),
                    saleDateStr);
        } catch (Exception e) {
            Log.e(TAG, "checkRefundEligibility error: " + e.getMessage());
            return RefundEligibility.notEligible(isArabic
                    ? "⚠️ خطأ في التحقق" : "⚠️ Validation error");
        }
    }

    private Long parseDaysDifference(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return null;
        String[] patterns = {
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.US);
                Date d = sdf.parse(dateStr);
                if (d != null) {
                    return (System.currentTimeMillis() - d.getTime())
                            / (1000L * 60 * 60 * 24);
                }
            } catch (ParseException ignored) {}
        }
        return null;
    }

    private void showRefundItemInfoInDialog(JSONObject itemData,
                                            RefundEligibility eligibility) {
        try {
            if (refundDialog == null || !refundDialog.isShowing()) return;

            String title = itemData.optString("title",
                    itemData.optString("item_title",
                            isArabic ? "قطعة ذهبية" : "Gold Item"));
            String epc = itemData.optString("epc", "");
            String serialNo = itemData.optString("serial",
                    itemData.optString("serial_number", ""));
            String karat = itemData.optString("karat", "21K");
            double weight = itemData.optDouble("weight", 0);

            double originalPrice = eligibility.originalPrice > 0
                    ? eligibility.originalPrice
                    : itemData.optDouble("retail_price_usd", 0);

            String priceStr = priceManager != null
                    ? priceManager.formatPriceDefault(originalPrice)
                    : String.format(Locale.US, "$%.2f", originalPrice);

            String details = String.format(Locale.US,
                    "EPC: %s\nSerial: %s\nKarat: %s | Weight: %.2fg\nPrice: %s",
                    epc.isEmpty() ? "—" : epc,
                    serialNo.isEmpty() ? "—" : serialNo,
                    karat, weight, priceStr);

            refundDialog.showRefundItemInfo(title, details);
        } catch (Exception e) {
            Log.e(TAG, "showRefundItemInfoInDialog error: " + e.getMessage());
        }
    }

    private void showRefundItemErrorInDialog(String message) {
        try {
            if (refundDialog != null) refundDialog.showRefundItemError(message);
        } catch (Exception e) {
            Log.e(TAG, "showRefundItemErrorInDialog error: " + e.getMessage());
        }
    }

    private void processRefund(String code, String reason) {
        final AlertDialog loadingDialog =
                PosUIManager.showLoadingDialog(requireContext(), isArabic);
        final RefundItem itemToRefund = selectedRefundItem.get();

        try {
            miraExecutor.getNetworkExecutor().execute(() -> {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("code", code != null ? code.trim() : "");
                    payload.put("epc", itemToRefund != null && itemToRefund.epc != null
                            ? itemToRefund.epc : code);
                    payload.put("serial_no", itemToRefund != null
                            && itemToRefund.serialNo != null
                            ? itemToRefund.serialNo : "");
                    payload.put("item_id", itemToRefund != null
                            ? itemToRefund.itemId : 0);
                    payload.put("sale_id", itemToRefund != null
                            ? itemToRefund.saleId : 0);
                    payload.put("original_price_usd",
                            itemToRefund != null ? itemToRefund.originalPrice : 0);
                    payload.put("reason", reason != null ? reason : "customer_request");
                    payload.put("refund_type", "full");
                    payload.put("branch_id", getActiveBranchId());
                    payload.put("user_id", sessionManager != null
                            ? sessionManager.getUserId() : 0);
                    payload.put("gate_id", apiClient != null
                            ? apiClient.getGateId() : "");
                    payload.put("username", sessionManager != null
                            ? sessionManager.getUsername() : "");
                    payload.put("timestamp", System.currentTimeMillis());

                    MiraApiClient client = MiraApiClient.getInstance(requireContext());
                    MiraApiClient.ApiResponse response = client.processRefund(payload);

                    safeRun(() -> {
                        PosUIManager.dismissDialog(loadingDialog);
                        if (refundDialog != null && refundDialog.isShowing()) {
                            refundDialog.dismiss();
                        }

                        if (response != null && response.isSuccess) {
                            restoreItemToCache(code, itemToRefund);

                            final RefundItem refunded = itemToRefund;
                            GateSaleBridge.onRefundCompleted(
                                    requireContext().getApplicationContext(),
                                    refunded != null ? refunded.epc : code,
                                    refunded != null ? refunded.serialNo : null,
                                    refunded != null ? refunded.saleId : 0L,
                                    getActiveBranchId(),
                                    sessionManager != null
                                            ? sessionManager.getUserId() : 0,
                                    reason
                            );

                            PosUIManager.showResultDialog(
                                    requireContext(),
                                    getString(R.string.pos_dialog_refund_success_title),
                                    getString(R.string.pos_dialog_refund_success_msg),
                                    true,
                                    isArabic
                            );

                            PosUIManager.notifyDataChanged(requireContext(),
                                    "refund", getActiveBranchId());
                            selectedRefundItem.set(null);
                        } else {
                            String err = response != null && response.error != null
                                    ? response.error
                                    : getString(R.string.pos_dialog_refund_failed_msg);
                            PosUIManager.showResultDialog(
                                    requireContext(),
                                    getString(R.string.pos_dialog_refund_failed_title),
                                    err, false, isArabic);
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "processRefund error: " + e.getMessage(), e);
                    safeRun(() -> {
                        PosUIManager.dismissDialog(loadingDialog);
                        PosUIManager.showResultDialog(
                                requireContext(),
                                getString(R.string.pos_dialog_error_title),
                                e.getMessage(), false, isArabic);
                    });
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "processRefund submit error: " + e.getMessage());
        }
    }

    private void restoreItemToCache(String code, RefundItem item) {
        try {
            Context ctx = getContext();
            if (ctx == null) return;
            MiraLocalItemCache cache = MiraLocalItemCache.getInstance(ctx);

            java.util.Set<String> ids = new java.util.HashSet<>();
            if (item != null) {
                if (item.epc != null && !item.epc.isEmpty()) ids.add(item.epc);
                if (item.serialNo != null && !item.serialNo.isEmpty()) {
                    ids.add(item.serialNo);
                }
            }
            if (code != null && !code.isEmpty()) ids.add(code);

            for (String id : ids) {
                cache.markItemAsAvailable(id);
            }
        } catch (Exception e) {
            Log.e(TAG, "restoreItemToCache error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    // Public APIs
    // ═══════════════════════════════════════════════════════

    public void showLastReceiptDialog() {
        try {
            if (lastSaleResponse == null ||
                    (System.currentTimeMillis() - lastSaleResponseTime)
                            > LAST_SALE_VALIDITY_MS) {
                showToast(isArabic ? "لا يوجد إيصال حديث" : "No recent receipt");
                return;
            }
            showReceipt(lastSaleResponse);
        } catch (Exception e) {
            Log.e(TAG, "showLastReceiptDialog error: " + e.getMessage());
        }
    }

    public void focusSearchField() {
        try {
            showManualInput();
        } catch (Exception e) {
            Log.e(TAG, "focusSearchField error: " + e.getMessage());
        }
    }


// ═══════════════════════════════════════════════════════
// Back Press Handler
// ═══════════════════════════════════════════════════════

/**
 * ✅ يعالج زر الرجوع فيزيائيًا — يتفوق على Callback الـActivity
 * لأن OnBackPressedDispatcher يُنفّذ الـCallbacks بترتيب LIFO
 * وgetViewLifecycleOwner يجعل الـcallback مُقيّدًا بحياة الـView
 */
private void setupBackPressedHandler() {
    if (getActivity() == null) return;

    backPressedCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            handleCustomBack();
        }
    };

    requireActivity().getOnBackPressedDispatcher()
            .addCallback(getViewLifecycleOwner(), backPressedCallback);
    Log.d(TAG, "✅ Back press handler registered");
}

/**
 * ✅ المنطق الذكي للرجوع:
 *   1. نافذة الدفع مفتوحة → تجاهل (النافذة تعالج)
 *   2. RFID نشط → أوقفه
 *   3. حقل الإدخال اليدوي ظاهر → أخفِه
 *   4. السلة غير فارغة → اطلب تأكيد
 *   5. خلاف ذلك → انتقل للشاشة السابقة
 */
private void handleCustomBack() {
    try {
        // 1. حوار الدفع
        if (isCheckoutDialogShowing) return;

        // 2. RFID نشط → أوقفه فقط (لا تخرج)
        if (currentScanMode == ScanMode.RFID) {
            switchScanMode(ScanMode.NONE);
            return;
        }

        // 3. Manual نشط → أغلقه
        if (currentScanMode == ScanMode.MANUAL) {
            switchScanMode(ScanMode.NONE);
            return;
        }

        // 4. السلة فيها عناصر → تأكيد
        if (cartManager != null && !cartManager.isEmpty()) {
            showExitConfirmation();
            return;
        }

        // 5. ✅ اخرج فقط عند كل هذه الشروط
        navigateAwayFromPos();
    } catch (Exception e) {
        Log.e(TAG, "handleCustomBack error", e);
    }
}
/**
 * ✅ تأكيد المغادرة عند وجود عناصر في السلة
 */
private void showExitConfirmation() {
    if (!isAdded() || getContext() == null) return;

    new AlertDialog.Builder(requireContext())
            .setTitle(isArabic ? "🛒 مغادرة شاشة البيع؟" : "🛒 Leave POS?")
            .setMessage(isArabic
                    ? "لديك " + cartManager.size() + " عنصر في السلة.\n\n"
                    + "هل تريد المغادرة؟ (سيتم الاحتفاظ بالسلة)"
                    : "You have " + cartManager.size() + " items in cart.\n\n"
                    + "Leave anyway? (Cart will be preserved)")
            .setPositiveButton(isArabic ? "مغادرة" : "Leave",
                    (d, w) -> navigateAwayFromPos())
            .setNegativeButton(isArabic ? "البقاء" : "Stay", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show();
}

/**
 * ✅ الانتقال للشاشة السابقة (Dashboard)
 */
private void navigateAwayFromPos() {
    try {
        // ═══════════════════════════════════════════════
        //  1) أوقف كل شيء أولاً
        // ═══════════════════════════════════════════════
        try { stopRFIDScan(); } catch (Exception ignored) {}
        try { UIKeyboardHide(); } catch (Exception ignored) {}

        currentScanMode = ScanMode.NONE;
        isRFIDModeActive = false;
        isRFIDScanActive = false;

        // ═══════════════════════════════════════════════
        //  2) تحقق من صحة النشاط
        // ═══════════════════════════════════════════════
        if (!isAdded() || isRemoving() || isDetached()) {
            Log.w(TAG, "navigateAwayFromPos: fragment not attached");
            return;
        }

        if (getActivity() == null || getActivity().isFinishing()
                || getActivity().isDestroyed()) {
            Log.w(TAG, "navigateAwayFromPos: activity not valid");
            return;
        }

        if (!(getActivity() instanceof UHFMainActivity)) {
            Log.w(TAG, "navigateAwayFromPos: not in UHFMainActivity");
            try {
                getActivity().onBackPressed();
            } catch (Exception ignored) {}
            return;
        }

        UHFMainActivity activity = (UHFMainActivity) getActivity();

        // ═══════════════════════════════════════════════
        //  3) فعّل التنقل أولاً
        // ═══════════════════════════════════════════════
        activity.setBottomNavVisible(true);
        activity.setNavigationLocked(false);
        activity.clearActiveListeners();

        // ═══════════════════════════════════════════════
        //  4) انتقل للـ Dashboard بأمان
        // ═══════════════════════════════════════════════
        if (activity.getNavigationManager() != null) {
            activity.getNavigationManager().goToDashboard();
            Log.i(TAG, "✅ Navigated to Dashboard");
        } else {
            Log.w(TAG, "NavigationManager null — using fallback");
            try {
                activity.loadFragmentDirectly(
                        new MiraDashboardFragment(),
                        NavigationManager.TAG_DASHBOARD);
                Log.i(TAG, "✅ Navigated to Dashboard (fallback)");
            } catch (Exception e) {
                Log.e(TAG, "Fallback navigation failed", e);
                // ⚠️ لا تستدعي onBackPressed — قد يُظهر الشاشة البيضاء
            }
        }

    } catch (Exception e) {
        Log.e(TAG, "navigateAwayFromPos error", e);
    }
}

/** ✅ إخفاء لوحة المفاتيح بأمان */
private void UIKeyboardHide() {
    try {
        if (getActivity() != null) {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                    getActivity().getSystemService(
                            android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null && getView() != null) {
                imm.hideSoftInputFromWindow(getView().getWindowToken(), 0);
            }
        }
    } catch (Exception ignored) {}
}
    // ═══════════════════════════════════════════════════════
    // RFID & Barcode
    // ═══════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════
// ⭐ إدارة وضع المسح الموحد
// ═══════════════════════════════════════════════════════

/**
 * تبديل ذكي بين أوضاع المسح الثلاثة:
 * - GS1: فتح ماسح الباركود
 * - RFID: تشغيل/إيقاف مسح RFID
 * - MANUAL: إظهار حقل الإدخال اليدوي
 *
 * الميزة: عند التبديل، يتم تنظيف الوضع السابق أولاً
 * حتى لا تتداخل الأوضاع.
 */
private void switchScanMode(ScanMode target) {
    if (!isAdded() || isDetached() || isRemoving()) return;
    if (heroController == null) return;
   

    Log.d(TAG, "🔄 Scan mode: " + currentScanMode + " → " + target);

    // ─────────────────────────────────────
    // 1) تنظيف الوضع الحالي
    // ─────────────────────────────────────
    switch (currentScanMode) {
        case RFID:
            // أوقف RFID
            try {
                stopRFIDScan();
                isRFIDModeActive = false;
            } catch (Exception e) {
                Log.e(TAG, "Stop RFID error: " + e.getMessage());
            }
            break;

        case MANUAL:
            // أخفِ حقل الإدخال اليدوي
            try {
                hideManualInput();
            } catch (Exception e) {
                Log.e(TAG, "Hide manual error: " + e.getMessage());
            }
            break;

        case GS1:
            // لا شيء — الماسح Fragment مؤقت
            break;

        case NONE:
        default:
            // لا شيء
            break;
    }

    // ─────────────────────────────────────
    // 2) تفعيل الوضع الجديد
    // ─────────────────────────────────────
    currentScanMode = target;

    switch (target) {
        case GS1:
            // أظهر حالة GS1 + افتح الماسح
            heroController.setState(PosHeroController.State.GS1_SCANNING);
            openBarcodeScanner();
            // ملاحظة: بعد إغلاق الماسح، onBarcodeScannerResult
            // سيُعيد الوضع إلى NONE تلقائياً
            break;

        case RFID:
            // ابدأ RFID
            heroController.setState(PosHeroController.State.RFID_LISTENING);
            heroController.resetRfidCount();
            isRFIDModeActive = true;
            startRFIDScan();
            break;

        case MANUAL:
            // أظهر حقل الإدخال
            heroController.setState(PosHeroController.State.MANUAL_ENTRY);
            showManualInput();
            break;

        case NONE:
        default:
            // العودة للحالة الطبيعية
            heroController.setState(PosHeroController.State.IDLE);
            break;
    }
}
    

    private void startRFIDScan() {
    try {
        if (rfidManager == null) return;
        
        oneShotConsumed.set(false);
        isRFIDScanActive = true;
        stopAfterFirstRead = true;
        
        // ✅ نفّذ start أيضاً على نفس المنفّذ — يضمن التسلسل
        sdkExecutor.execute(() -> {
            try {
                rfidManager.startScan();
                Log.d(TAG, "✅ RFID started (bg)");
            } catch (Exception e) {
                Log.e(TAG, "startScan bg error: " + e.getMessage());
                isRFIDScanActive = false;
            }
        });
    } catch (Exception e) {
        Log.e(TAG, "startRFIDScan error: " + e.getMessage());
        isRFIDScanActive = false;
    }
}

    private void stopRFIDScan() {
    try {
        if (rfidManager == null) return;
        
        // ✅ علّم الحالة فوراً — لا انتظار
        isRFIDScanActive = false;
        
        // ✅ نفّذ stop على منفّذ واحد (Serialized)
        sdkExecutor.execute(() -> {
            try {
                rfidManager.stopScan();
                Log.d(TAG, "✅ RFID stopped (bg)");
            } catch (Exception e) {
                Log.e(TAG, "stopScan bg error: " + e.getMessage());
            }
        });

    } catch (Exception e) {
        Log.e(TAG, "stopRFIDScan error: " + e.getMessage());
    }
}

    public void onRingScanned(String epc) {
    try {
        // ═══════════════════════════════════════════════
        //  1) فحوصات أساسية
        // ═══════════════════════════════════════════════
        if (epc == null || epc.trim().isEmpty()) return;
        if (!isRFIDScanActive) return;

        // ═══════════════════════════════════════════════
        //  2) Debounce — تجاهل نفس الوسم خلال فترة قصيرة
        // ═══════════════════════════════════════════════
        long now = System.currentTimeMillis();
        Long lastTime = lastScannedTimes.get(epc);
        if (lastTime != null && (now - lastTime < SCAN_COOLDOWN_MS)) return;
        lastScannedTimes.put(epc, now);

        // ═══════════════════════════════════════════════
        //  3) ✅ [حرج] One Shot — مع حماية ذرّية كاملة
        // ═══════════════════════════════════════════════
        if (stopAfterFirstRead) {

            // 3.1) فحص ذرّي: أول خيط فقط سينجح
            if (!oneShotConsumed.compareAndSet(false, true)) {
                Log.i(TAG, "🎯 One-shot: ignoring extra tag " + epc);
                return;
            }

            // 3.2) ✅ [إصلاح حرج] أغلق كل الأعلام فوراً
            //      هذا يمنع أي قراءة إضافية من الدخول للوضع المستمر
            isRFIDScanActive = false;
            stopAfterFirstRead = false;

            Log.i(TAG, "🎯 One-shot: accepted first tag " + epc);

            // 3.3) أوقف القارئ وأعد الوضع لـ IDLE
            switchScanMode(ScanMode.NONE);

            // 3.4) عالج الوسم (سيُضاف للسلة)
            processRingEpc(epc, "rfid");
            return;
        }

        // ═══════════════════════════════════════════════
        //  4) الوضع المستمر — كل القراءات مقبولة
        // ═══════════════════════════════════════════════
        if (cartManager != null && cartManager.isIdScanned(epc)) {
            showToast(getString(R.string.pos_msg_already_in_cart));
            playAlertSound(2);
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

            long now = System.currentTimeMillis();
            Long lastTime = lastScannedTimes.get(data);
            if (lastTime != null && (now - lastTime < SCAN_COOLDOWN_MS)) return;
            lastScannedTimes.put(data, now);

            if (cartManager != null && cartManager.isIdScanned(data)) {
                showToast(getString(R.string.pos_msg_already_in_cart));
                playAlertSound(2);
                return;
            }
            processRingEpc(data, "barcode");
        } catch (Exception e) {
            Log.e(TAG, "onBarcodeScanned error: " + e.getMessage());
        }
    }

    private void openBarcodeScanner() {
        try {
            if (!isAdded() || getContext() == null) return;
            FragmentManager fm = getParentFragmentManager();
            if (fm == null || fm.isStateSaved()) return;

            MiraBarcodeScannerFragment sf = new MiraBarcodeScannerFragment();
            sf.setOnBarcodeScannedListener((data, format) -> {
    try {
        if (isAdded() && data != null && !data.isEmpty()) {
            processRingEpc(data, "barcode");
        }
        // ⭐ أعِد الوضع إلى NONE بعد المسح
        currentScanMode = ScanMode.NONE;
        if (heroController != null) {
            heroController.setState(PosHeroController.State.IDLE);
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
            } catch (Exception ignored) {}

            fm.beginTransaction()
                    .replace(containerId, sf)
                    .addToBackStack("barcode_scanner")
                    .commit();
        } catch (Exception e) {
            Log.e(TAG, "openBarcodeScanner error: " + e.getMessage());
            showToast(isArabic ? "⚠️ تعذر فتح الماسح" : "⚠️ Unable to open scanner");
            if (heroController != null) {
                heroController.setState(PosHeroController.State.IDLE);
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // Scale
    // ═══════════════════════════════════════════════════════

    private void initSmartScale() {
        try {
            scaleManager = new SmartScaleManager(requireContext(),
                    new SmartScaleManager.ScaleCallback() {
                @Override
                public void onWeightReceived(double weight) {
                    liveScaleWeight = weight;
                    safeRun(() -> {
                        if (tvScaleStatus != null) {
                            tvScaleStatus.setText(String.format(Locale.US,
                                    "⚖️ %.2fg", weight));
                        }
                        updateCartItemsScaleWeight(weight);
                    });
                }

                @Override
                public void onScaleConnected(String name) {
                    safeRun(() -> {
                        if (tvScaleStatus != null) {
                            tvScaleStatus.setText("⚖️ " + name);
                        }
                        showToast(getString(R.string.pos_msg_scale_connected, name));
                    });
                }

                @Override
                public void onScaleDisconnected() {
                    liveScaleWeight = 0.0;
                    safeRun(() -> {
                        if (tvScaleStatus != null) {
                            tvScaleStatus.setText(R.string.pos_scale_offline);
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
                        liveScaleWeight = weight;
                        if (tvScaleStatus != null) {
                            tvScaleStatus.setText(String.format(Locale.US,
                                    "⚖️ %.2fg", weight));
                        }
                        updateCartItemsScaleWeight(weight);
                    });
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "initSmartScale error: " + e.getMessage());
        }
    }

    private void updateCartItemsScaleWeight(double currentScaleWeight) {
        if (cartManager == null || cartManager.isEmpty() || currentScaleWeight <= 0) {
            return;
        }
        try {
            CartItem topItem = cartManager.getItems().get(0);
            if (topItem.scaleWeight != currentScaleWeight) {
                topItem.scaleWeight = currentScaleWeight;
                if (cartAdapter != null) cartAdapter.notifyItemChanged(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "updateCartItemsScaleWeight error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    // Permissions
    // ═══════════════════════════════════════════════════════

    private void setButtonsEnabled(boolean enabled) {
        safeRun(() -> {
            try {
                boolean canUse = hasPosPermission();
                if (btnCheckout != null) {
                    btnCheckout.setEnabled(enabled && canUse
                            && cartManager != null && !cartManager.isEmpty());
                }
                if (btnClearCart != null) btnClearCart.setEnabled(enabled);
                if (btnAddManual != null) btnAddManual.setEnabled(enabled && canUse);
                if (btnPosScanGs1 != null) btnPosScanGs1.setEnabled(enabled && canUse);
                if (btnPosScanRfid != null) btnPosScanRfid.setEnabled(enabled && canUse);
                if (btnPosScanManual != null) {
                    btnPosScanManual.setEnabled(enabled && canUse);
                }
                if (etManualEpc != null) etManualEpc.setEnabled(enabled && canUse);
            } catch (Exception e) {
                Log.e(TAG, "setButtonsEnabled error: " + e.getMessage());
            }
        });
    }

    private void applyPermissions() {
        try {
            boolean canUse = hasPosPermission();
            if (btnAddManual != null) btnAddManual.setEnabled(canUse);
            if (btnPosScanGs1 != null) btnPosScanGs1.setEnabled(canUse);
            if (btnPosScanRfid != null) btnPosScanRfid.setEnabled(canUse);
            if (btnPosScanManual != null) btnPosScanManual.setEnabled(canUse);
            if (etManualEpc != null) etManualEpc.setEnabled(canUse);

            if (!canUse && tvCartStatus != null) {
                tvCartStatus.setText(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            }
        } catch (Exception e) {
            Log.e(TAG, "applyPermissions error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    // Utilities
    // ═══════════════════════════════════════════════════════

    private void showToast(String msg) {
        PosUIManager.toast(getContext(), msg);
    }

    private void safeRun(Runnable r) {
        try {
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (isAdded() && !isRemoving() && !isDetached()) {
                        try { r.run(); }
                        catch (Exception e) {
                            Log.e(TAG, "safeRun inner: " + e.getMessage());
                        }
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "safeRun error: " + e.getMessage());
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

    private int getActiveBranchId() {
        try {
            return branchesManager != null
                    ? branchesManager.getActiveBranchId() : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    private boolean hasPosPermission() {
        try {
            return permissionManager != null && (
                    permissionManager.canView("pos")
                            || permissionManager.isPosOperator()
                            || permissionManager.isManager());
        } catch (Exception e) {
            return false;
        }
    }

    private int getKaratNumber(String karat) {
        if (karat == null) return 21;
        String clean = karat.trim().toUpperCase(Locale.US).replace("K", "");
        try {
            int num = Integer.parseInt(clean);
            if (num >= 10 && num <= 24) return num;
        } catch (NumberFormatException ignored) {}
        return 21;
    }

    private String extractString(JSONObject obj, String... keys) {
        if (obj == null || keys == null) return "";
        for (String key : keys) {
            try {
                String v = obj.optString(key, "");
                if (v != null && !v.isEmpty() && !"null".equals(v)) return v;
            } catch (Exception ignored) {}
        }
        return "";
    }

    // ═══════════════════════════════════════════════════════
    // Launcher
    // ═══════════════════════════════════════════════════════

    private final ActivityResultLauncher<ScanOptions> refundBarcodeLauncher =
            registerForActivityResult(new ScanContract(), result -> {
        try {
            if (result != null && result.getContents() != null && isAdded()) {
                if (refundDialog != null && refundDialog.isShowing()) {
                    selectedRefundItem.set(null);
                    searchItemForRefund(result.getContents());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "refundBarcodeLauncher error: " + e.getMessage());
        }
    });

    // ═══════════════════════════════════════════════════════
    // Backward Compatibility
    // ═══════════════════════════════════════════════════════

    @Deprecated
    public void showCartBottomSheet() {
        Log.d(TAG, "showCartBottomSheet: no-op (cart is always visible)");
    }

    @Deprecated
    public void showDiscountDialog() {
        Log.d(TAG, "showDiscountDialog: redirecting to checkout dialog");
        processCheckoutAndStockUpdate();
    }
    
    // ═══════════════════════════════════════════════════════
//  🎯 معالج الزناد الفيزيائي (RFID Trigger)
// ═══════════════════════════════════════════════════════

@Override
public boolean myOnKeyDwon(int keyCode, KeyEvent event) {
    boolean isTrigger = (keyCode == KEYCODE_RFID_TRIGGER_1
            || keyCode == KEYCODE_RFID_TRIGGER_2
            || keyCode == KEYCODE_RFID_TRIGGER_3);

    if (!isTrigger) return false;

    // ✅ ACTION_DOWN — ابدأ المسح
    if (event.getAction() == KeyEvent.ACTION_DOWN
            && event.getRepeatCount() == 0) {

        if (!hasPosPermission()) {
            showToast(getString(R.string.pos_msg_no_permission));
            return true;
        }

        // ✅ لا تبدأ إذا القارئ غير مدعوم
        try {
            if (!com.example.uhf.tools.DeviceDetector.isRfidHardwareSupported()) {
                return true;
            }
        } catch (Exception ignored) {}

        // ✅ ابدأ RFID في وضع One Shot
        if (currentScanMode != ScanMode.RFID) {
            Log.i(TAG, "🎯 Trigger DOWN → starting RFID One-Shot");
            switchScanMode(ScanMode.RFID);
        }
        return true;
    }

    // ✅ ACTION_UP — أوقف المسح
    if (event.getAction() == KeyEvent.ACTION_UP) {
        if (currentScanMode == ScanMode.RFID) {
            Log.i(TAG, "🎯 Trigger UP → stopping RFID");
            switchScanMode(ScanMode.NONE);
        }
        return true;
    }

    return false;
}
                }
