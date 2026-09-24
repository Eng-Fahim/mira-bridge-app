package com.example.uhf.fragment;

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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.card.MaterialCardView;
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
import com.example.uhf.tools.SessionManager;
import com.example.uhf.tools.UIHelper;
import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.manager.MiraLocalItemCache;
import com.example.uhf.manager.MiraItemSyncManager;
import com.example.uhf.utils.MiraExecutorService;
import com.example.uhf.utils.ScaleSimulationHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MiraPosFragment - الإصدار 5.0 (الحصين ضد التعليق)
 *
 * ✅ الإصلاحات v5.0:
 *  1. Pre-compute للقيم المالية على UI Thread (منع تعليق SharedPreferences)
 *  2. Timeout 30s على عملية البيع (لا انتظار للأبد)
 *  3. Logging شامل لكل خطوة (تتبع دقيق)
 *  4. AtomicBoolean لمنع التنفيذ المزدوج
 *  5. Fallback Executor إذا فشل miraExecutor
 *  6. checkoutWithLongTimeout (60 ثانية للـ read)
 *  7. MiraPriceManager Singleton حقيقي (بدون Thread-Leak)
 */
public class MiraPosFragment extends MiraBaseFragment {

    private static final String TAG = "MiraPosFragment";
    private static final String POS_DEBUG = "POS_DEBUG";
    private static final String REFUND_DEBUG = "REFUND_DEBUG";
    private static final String CHECKOUT_TRACE = "CHECKOUT_TRACE";
    private static final String BUILD_PAYLOAD = "BUILD_PAYLOAD";

    // ✅ ثوابت
    private static final long SCAN_COOLDOWN_MS = 1500;
    private static final long REFUND_MAX_DAYS = 30;
    private static final int MAX_VIEW_DEPTH = 50;
    private static final long LAST_SALE_VALIDITY_MS = 5 * 60 * 1000;
    private static final long CHECKOUT_TIMEOUT_MS = 30_000;

    // ✅ حالة أساسية
    private volatile boolean isSyncing = false;
    private volatile boolean isCheckoutDialogShowing = false;
    private final AtomicBoolean isCheckoutInProgress = new AtomicBoolean(false);

    private SessionManager sessionManager;
    private SmartScaleManager scaleManager;
    private ScaleSimulationHelper simulationHelper;
    private POSManager posManager;
    private PendingSalesManager pendingSalesManager;
    private RefundDialog refundDialog;

    private MiraExecutorService miraExecutor;
    private MiraApiClient apiClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ⭐ Executor احتياطي
    private final ExecutorService fallbackExecutor = Executors.newFixedThreadPool(2);

    private MiraPriceManager priceManager;
    private MiraBranchesManager branchesManager;
    private PermissionManager permissionManager;

    private RFIDManager rfidManager;
    private volatile boolean isRFIDScanActive = false;
    private volatile boolean isRFIDModeActive = false;

    private final Set<String> pendingEpcs = new CopyOnWriteArraySet<>();
    private final Map<String, Long> lastScannedTimes = new ConcurrentHashMap<>();

    // ✅ Views
    private TextView tvScaleStatus, tvItemCount, tvTotalAmount, tvCartStatus;
    private TextInputLayout tilManualEpc;
    private EditText etManualEpc;
    private Button btnAddManual, btnCheckout, btnClearCart;
    private Button btnOpenScanner, btnPosRFIDMode, btnPosRefund;
    private RecyclerView rvCartItems;
    private View layoutCartEmptyState;

    private boolean isArabic;

    private volatile MiraApiClient.ApiResponse lastSaleResponse = null;
    private volatile long lastSaleResponseTime = 0;

    private volatile double liveScaleWeight = 0.0;

    private final List<CartItem> cartList = new CopyOnWriteArrayList<>();
    private final Set<String> scannedEpcs = new CopyOnWriteArraySet<>();

    private CartAdapter cartAdapter;

    private final AtomicReference<RefundItem> selectedRefundItem =
            new AtomicReference<>(null);

    private BottomSheetDialog activeCartSheet;

    // ⭐ Timeout runnable
    private Runnable checkoutTimeoutRunnable;

    // ==================== كلاسات مساعدة ====================

    private static final class RefundItem {
        final String epc;
        final String serialNo;
        final int itemId;
        final long saleId;
        final double originalPrice;
        final String title;

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

    // ==================== دورة الحياة ====================

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
            Context ctx = requireContext().getApplicationContext();

            branchesManager = MiraBranchesManager.getInstance(ctx);
            priceManager = MiraPriceManager.getInstance(ctx);
            permissionManager = PermissionManager.getInstance(ctx);
            isArabic = Locale.getDefault().getLanguage().equals("ar");
            sessionManager = new SessionManager(ctx);
            posManager = new POSManager(ctx);
            pendingSalesManager = new PendingSalesManager(ctx);

            miraExecutor = MiraExecutorService.getInstance();
            if (miraExecutor == null || !miraExecutor.isHealthy()) {
                Log.e(TAG, "MiraExecutorService unhealthy");
                showToast(isArabic ? "⚠️ خطأ في تهيئة المحرك" : "⚠️ Engine init error");
                return;
            }

            rfidManager = RFIDManager.getInstance(ctx);
            if (rfidManager != null) {
                rfidManager.setListener(new MiraIRFIDReader.OnTagReadListener() {
                    @Override
                    public void onTagRead(String epc, int rssi) {
                        if (isRFIDScanActive && isRFIDModeActive) {
                            onRingScanned(epc);
                        }
                    }
                    @Override public void onScanStarted() {}
                    @Override public void onScanStopped() {}
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
                tvCartStatus.setText(isArabic ?
                        getString(R.string.pos_ready_scan) : "Ready to scan...");
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

            if (activeCartSheet != null && activeCartSheet.isShowing()) {
                activeCartSheet.dismiss();
                activeCartSheet = null;
            }

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
            isCheckoutInProgress.set(false);

            if (rfidManager != null) rfidManager.setListener(null);
            if (scaleManager != null) scaleManager.cleanup();
            if (simulationHelper != null) simulationHelper.cleanup();

            if (activeCartSheet != null && activeCartSheet.isShowing()) {
                activeCartSheet.dismiss();
                activeCartSheet = null;
            }

            if (rvCartItems != null) {
                rvCartItems.setAdapter(null);
                rvCartItems.setLayoutManager(null);
            }
            cartAdapter = null;

            pendingEpcs.clear();
            lastScannedTimes.clear();
            selectedRefundItem.set(null);

            // ✅ إزالة timeout
            if (checkoutTimeoutRunnable != null && mainHandler != null) {
                mainHandler.removeCallbacks(checkoutTimeoutRunnable);
                checkoutTimeoutRunnable = null;
            }

            if (mainHandler != null) {
                mainHandler.removeCallbacksAndMessages(null);
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
            if (scaleManager != null) scaleManager.cleanup();
            // لا نُطلق fallbackExecutor — قد يكون مستخدماً من أمثلة أخرى
            // لكن نُغلق إذا كانت العملية الأخيرة
            if (fallbackExecutor != null && !fallbackExecutor.isShutdown()) {
                fallbackExecutor.shutdown();
            }
        } catch (Exception e) {
            Log.e(TAG, "onDestroy error: " + e.getMessage());
        }
    }

    // ==================== Views ====================

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

        if (etManualEpc != null) {
            etManualEpc.clearFocus();
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
                if (btnPosRFIDMode != null && !isRFIDModeActive)
                    btnPosRFIDMode.setText(R.string.pos_scan_rfid);
                if (btnAddManual != null) btnAddManual.setText(R.string.pos_add);
            } else {
                if (tilManualEpc != null) tilManualEpc.setHint("Enter EPC or Barcode...");
                if (btnCheckout != null) btnCheckout.setText("Checkout");
                if (btnClearCart != null) btnClearCart.setText("Clear");
                if (btnOpenScanner != null) btnOpenScanner.setText("📷 Scan GS1");
                if (btnPosRFIDMode != null && !isRFIDModeActive)
                    btnPosRFIDMode.setText("📡 Scan RFID");
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
                cartAdapter = new CartAdapter(cartList, this::removeItemFromCart,
                        isArabic, priceManager);
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
                    if (actionId == EditorInfo.IME_ACTION_DONE
                            || actionId == EditorInfo.IME_ACTION_SEARCH
                            || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                            && event.getAction() == KeyEvent.ACTION_DOWN)) {
                        submitManualEpc();
                        return true;
                    }
                    return false;
                });
            }

            if (btnAddManual != null) btnAddManual.setOnClickListener(v -> submitManualEpc());
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
            if (btnPosRefund != null) btnPosRefund.setOnClickListener(v -> showRefundDialog());
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

    // ==================== البحث عن القطعة ====================

    private void processRingEpc(final String code, final String source) {
        if (code == null || code.trim().isEmpty() || !isAdded()) return;
        final String cleanCode = code.trim();

        if (!pendingEpcs.add(cleanCode)) {
            return;
        }

        playAlertSound(1);

        safeRun(() -> {
            if (tvCartStatus != null)
                tvCartStatus.setText(isArabic ? "🔍 جاري البحث..." : "🔍 Searching...");
        });

        try {
            miraExecutor.getNetworkExecutor().execute(() -> {
                try {
                    Context context = getContext();
                    if (context == null) return;

                    MiraItemSyncManager syncManager = MiraItemSyncManager.getInstance(context);
                    final int branchId = getActiveBranchId();

                    syncManager.findItem(cleanCode, branchId,
                            new MiraItemSyncManager.ItemLookupCallback() {
                        @Override
                        public void onItemFound(JSONObject item, boolean fromServer) {
                            safeRun(() -> {
                                int itemBranchId = item.optInt("branch_id", -1);
                                if (itemBranchId > 0 && itemBranchId != branchId) {
                                    showToast(isArabic ? "❌ قطعة في فرع آخر" :
                                            "❌ Item in Another Branch");
                                    playAlertSound(2);
                                    updateCartSummary();
                                    return;
                                }
                                parseItemResponse(cleanCode, item);
                            });
                        }

                        @Override
                        public void onItemNotFound() {
                            safeRun(() -> {
                                showToast(isArabic ? "❌ القطعة غير موجودة" :
                                        "❌ Item Not Found");
                                playAlertSound(2);
                                updateCartSummary();
                            });
                        }
                    });
                } catch (Exception e) {
                    safeRun(() -> {
                        showToast(isArabic ? "⚠️ خطأ في البحث" : "⚠️ Search error");
                        updateCartSummary();
                    });
                } finally {
                    pendingEpcs.remove(cleanCode);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "processRingEpc error: " + e.getMessage());
            pendingEpcs.remove(cleanCode);
        }
    }

    private void parseItemResponse(String code, JSONObject data) {
        try {
            JSONObject item = data.has("item") ? data.optJSONObject("item") : data;
            if (item == null) {
                showToast(isArabic ? "❌ القطعة غير موجودة" : "❌ Item Not Found");
                playAlertSound(2);
                updateCartSummary();
                return;
            }

            String vaultStatus = item.optString("status",
                    item.optString("vault_status", "")).toLowerCase();

            if ("sold".equals(vaultStatus) || "exited".equals(vaultStatus)) {
                showToast(isArabic ? "❌ القطعة مباعة مسبقاً" : "❌ Item already sold");
                playAlertSound(2);
                updateCartSummary();
                return;
            }

            boolean isAvailable = !vaultStatus.equals("sold")
                    && !vaultStatus.equals("reserved");

            String title = item.optString("title",
                    item.optString("item_title", isArabic ? "قطعة" : "Item"));
            String karat = item.optString("karat", item.optString("gold_karat", "21K"));
            double weight = item.optDouble("weight",
                    item.optDouble("gross_weight",
                            item.optDouble("gross_weight_grams", 0)));

            double priceLocal   = item.optDouble("retail_price_local", 0);
            double priceMain    = item.optDouble("retail_price", 0);
            double priceUSD     = item.optDouble("retail_price_usd", 0);
            double priceGeneric = item.optDouble("price", 0);

            double price = 0;
            String priceSource = "none";

            if (priceUSD > 0 && priceUSD < 100_000_000) {
                price = priceUSD;
                priceSource = "retail_price_usd";
            } else if (priceLocal > 0 && priceLocal < 100_000_000) {
                double exchangeRate = (priceManager != null)
                        ? priceManager.getExchangeRate("YER")
                        : 530.0;
                if (exchangeRate <= 0) exchangeRate = 530.0;
                price = priceLocal / exchangeRate;
                priceSource = "retail_price_local/" + exchangeRate;
            } else if (priceMain > 0 && priceMain < 100_000_000) {
                price = priceMain;
                priceSource = "retail_price";
            } else if (priceGeneric > 0 && priceGeneric < 100_000_000) {
                price = priceGeneric;
                priceSource = "price";
            } else {
                price = 0;
                priceSource = "invalid";
            }

            if (price <= 0 && weight > 0 && priceManager != null) {
                price = priceManager.calculateGoldPrice(getKaratNumber(karat), weight);
            }

            CartItem cartItem = new CartItem(code, title, karat, weight, liveScaleWeight,
                    price, isAvailable, vaultStatus.toUpperCase());

            cartItem.serialNo = extractString(item,
                    "serial", "serial_number", "serialNo", "sn");
            cartItem.gtin = extractString(item,
                    "gtin", "gtin13", "barcode", "code");
            cartItem.location = extractString(item,
                    "location", "current_location", "zone");
            cartItem.itemId = item.optInt("id",
                    item.optInt("item_id",
                            item.optInt("product_id", 0)));

            String tid = extractString(item, "tid", "tag_tid", "rfid_tid");
            if (!tid.isEmpty() && cartItem.serialNo.isEmpty()) {
                cartItem.serialNo = tid;
            }

            String rfidEpc = extractString(item, "rfid_epc", "epc_final");
            if (!rfidEpc.isEmpty()) {
                cartItem.epc = rfidEpc;
            }

            boolean alreadyInCart = isItemAlreadyInCart(cartItem.epc, cartItem.serialNo,
                    cartItem.gtin);

            safeRun(() -> {
                if (alreadyInCart) {
                    showToast(isArabic ? "⚠️ هذه القطعة مضافة مسبقاً!" :
                            "⚠️ Item already in cart!");
                    playAlertSound(2);
                    updateCartSummary();
                    return;
                }

                if (!isAvailable) {
                    showToast(isArabic ? "❌ قطعة غير متاحة" : "❌ Item Unavailable");
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

                playAlertSound(1);
                showToast(isArabic ? "✅ تمت الإضافة: " + title : "✅ Added: " + title);
            });
        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage(), e);
            showToast(isArabic ? "❌ خطأ في معالجة القطعة" : "❌ Error processing item");
            updateCartSummary();
        }
    }

    private String extractString(JSONObject obj, String... keys) {
        if (obj == null || keys == null) return "";
        for (String key : keys) {
            try {
                String value = obj.optString(key, "");
                if (value != null && !value.isEmpty() && !"null".equals(value)) {
                    return value;
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    private boolean isItemAlreadyInCart(String epc, String serialNo, String gtin) {
        for (CartItem item : cartList) {
            if (item.epc != null && epc != null && item.epc.equalsIgnoreCase(epc)) return true;
            if (item.serialNo != null && serialNo != null && !serialNo.isEmpty()
                    && item.serialNo.equalsIgnoreCase(serialNo)) return true;
            if (item.gtin != null && gtin != null && !gtin.isEmpty()
                    && item.gtin.equalsIgnoreCase(gtin)) return true;
        }
        return false;
    }

    // ==================== Cart ====================

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
                if (layoutCartEmptyState != null)
                    layoutCartEmptyState.setVisibility(fc == 0 ? View.VISIBLE : View.GONE);
                if (tvItemCount != null) tvItemCount.setText(String.valueOf(fc));
                if (tvTotalAmount != null)
                    tvTotalAmount.setText(priceManager.formatPriceDefault(fs));
                if (tvCartStatus != null) {
                    if (fc == 0)
                        tvCartStatus.setText(isArabic ?
                                getString(R.string.pos_ready_scan) : "Ready to scan...");
                    else if (fb)
                        tvCartStatus.setText(isArabic ?
                                "⚠️ قطعة غير متاحة!" : "⚠️ Unavailable item!");
                    else
                        tvCartStatus.setText(isArabic ? "✓ جاهز للبيع" : "✓ Ready for sale");
                }
                if (btnCheckout != null) {
                    btnCheckout.setEnabled(fc > 0 && !fb && hasPosPermission());
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

    // ==================== Checkout ====================

    private void processCheckoutAndStockUpdate() {
        try {
            if (!hasPosPermission()) {
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

            POSCheckoutDialog dialog = POSCheckoutDialog.newInstance(
                    getCartTotal(), cartList.size());

            dialog.setCheckoutListener(data -> {
                isCheckoutDialogShowing = false;
                executeCheckoutDirectly(data);
            });

            dialog.setOnDismissListener(() -> isCheckoutDialogShowing = false);

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
     * ✅ executeCheckoutDirectly v5.0
     * - Pre-compute على UI Thread
     * - Timeout 30s
     * - Logging شامل
     */
    private void executeCheckoutDirectly(POSCheckoutDialog.POSCheckoutData data) {
        Log.e(CHECKOUT_TRACE, "1️⃣ Entered executeCheckoutDirectly");

        try {
            if (!isAdded() || data == null) {
                Log.e(CHECKOUT_TRACE, "❌ data null or not added");
                return;
            }
            Log.e(CHECKOUT_TRACE, "2️⃣ data valid");

            // ═══════════════════════════════════════════════════════
            // ✅ Pre-compute على UI Thread
            // ═══════════════════════════════════════════════════════
            // ✅ [الحل] Pre-compute مع متغيرات غير final
String _currencyCode = "YER";
double _exchangeRate = 530.0;

try {
    long t0 = System.currentTimeMillis();

    if (priceManager != null) {
        _currencyCode = priceManager.getDefaultCurrency();
        Log.e(CHECKOUT_TRACE, "3️⃣ currencyCode=" + _currencyCode);

        _exchangeRate = priceManager.getExchangeRate(_currencyCode);
        Log.e(CHECKOUT_TRACE, "4️⃣ exchangeRate=" + _exchangeRate);
    }

    long elapsed = System.currentTimeMillis() - t0;
    Log.e(CHECKOUT_TRACE, "5️⃣ Pre-compute took: " + elapsed + "ms");

} catch (Exception e) {
    Log.e(CHECKOUT_TRACE, "❌ Pre-compute error: " + e.getMessage(), e);
    _currencyCode = "YER";
    _exchangeRate = 530.0;
}

// ✅ الآن final — لا يمكن إعادة إسنادها
final String currencyCode = _currencyCode;
final double exchangeRate = _exchangeRate;
final double subtotalLocal = data.originalTotal * exchangeRate;
final double discountLocal = data.discountAmount * exchangeRate;
final double finalLocal = data.finalAmount * exchangeRate;

            setButtonsEnabled(false);
            Log.e(CHECKOUT_TRACE, "6️⃣ buttons disabled");

            final AlertDialog loadingDialog = showProfessionalLoadingDialog();
            Log.e(CHECKOUT_TRACE, "7️⃣ dialog shown");

            // ═══════════════════════════════════════════════════════
            // ✅ Timeout Safety
            // ═══════════════════════════════════════════════════════
            final AtomicBoolean finished = new AtomicBoolean(false);

            checkoutTimeoutRunnable = () -> {
                if (!finished.get()) {
                    Log.e(CHECKOUT_TRACE, "⏰ TIMEOUT after 30s");
                    safeRun(() -> {
                        dismissDialog(loadingDialog);
                        setButtonsEnabled(true);
                        showRetryDialog(
                                isArabic ? "⏰ انتهت المهلة" : "⏰ Timeout",
                                isArabic ? "لم يستجب السيرفر. حاول مرة أخرى."
                                        : "Server not responding. Try again.",
                                () -> executeCheckoutDirectly(data));
                    });
                }
            };
            mainHandler.postDelayed(checkoutTimeoutRunnable, CHECKOUT_TIMEOUT_MS);

            // ═══════════════════════════════════════════════════════
            // ✅ Background Execution
            // ═══════════════════════════════════════════════════════
            miraExecutor.getNetworkExecutor().execute(() -> {
                Log.e(CHECKOUT_TRACE, "8️⃣ THREAD STARTED");

                try {
                    Context ctx = getContext();
                    if (ctx == null) {
                        Log.e(CHECKOUT_TRACE, "❌ ctx null");
                        finished.set(true);
                        if (checkoutTimeoutRunnable != null) {
                            mainHandler.removeCallbacks(checkoutTimeoutRunnable);
                        }
                        return;
                    }
                    Log.e(CHECKOUT_TRACE, "9️⃣ ctx obtained");

                    MiraApiClient client = MiraApiClient.getInstance(ctx);
                    Log.e(CHECKOUT_TRACE, "🔟 client obtained");

                    // ✅ بناء Payload
                    Log.e(CHECKOUT_TRACE, "1️⃣1️⃣ BEFORE buildCheckoutPayload");
                    long tBuild = System.currentTimeMillis();

                    JSONObject payload = buildCheckoutPayload(client, data,
                            currencyCode, exchangeRate,
                            subtotalLocal, discountLocal, finalLocal);

                    long buildTime = System.currentTimeMillis() - tBuild;
                    Log.e(CHECKOUT_TRACE, "1️⃣2️⃣ AFTER buildCheckoutPayload (" 
                            + buildTime + "ms) - size=" + payload.toString().length());

                    // ✅ تنفيذ Checkout
                    Log.e(CHECKOUT_TRACE, "1️⃣3️⃣ BEFORE checkout");
                    long tCheckout = System.currentTimeMillis();

                    MiraApiClient.ApiResponse response =
                            client.checkoutWithLongTimeout(payload);

                    long checkoutTime = System.currentTimeMillis() - tCheckout;
                    Log.e(CHECKOUT_TRACE, "1️⃣4️⃣ AFTER checkout in " + checkoutTime 
                            + "ms, code=" + (response != null ? response.code : "null"));

                    // ✅ إلغاء Timeout
                    finished.set(true);
                    if (checkoutTimeoutRunnable != null) {
                        mainHandler.removeCallbacks(checkoutTimeoutRunnable);
                    }

                    final MiraApiClient.ApiResponse finalResponse = response;

                    // ✅ معالجة النتيجة
                    safeRun(() -> {
                        dismissDialog(loadingDialog);
                        setButtonsEnabled(true);

                        if (finalResponse != null && finalResponse.isSuccess) {
                            Log.e(CHECKOUT_TRACE, "✅ SUCCESS");
                            playAlertSound(1);
                            lastSaleResponse = finalResponse;
                            lastSaleResponseTime = System.currentTimeMillis();
                            clearCart();
                            showReceipt(finalResponse);
                            notifyDashboardAndInventoryUpdate("sale");
                            showToast(isArabic ? "✅ تم البيع بنجاح" : "✅ Sale Successful");

                        } else if (finalResponse != null && finalResponse.isNetworkError) {
                            Log.e(CHECKOUT_TRACE, "✅ OFFLINE");
                            playAlertSound(1);
                            lastSaleResponse = finalResponse;
                            lastSaleResponseTime = System.currentTimeMillis();
                            clearCart();
                            showToast(isArabic ? "✅ تم البيع (أوفلاين)" : "✅ Sale (Offline)");
                            notifyDashboardAndInventoryUpdate("sale_offline");

                        } else {
                            String errorMsg = finalResponse != null && finalResponse.error != null
                                    ? finalResponse.error : "Unknown error";
                            Log.e(CHECKOUT_TRACE, "❌ FAILED: " + errorMsg);
                            showRetryDialog(
                                    isArabic ? "❌ فشل البيع" : "❌ Sale Failed",
                                    errorMsg,
                                    () -> executeCheckoutDirectly(data));
                        }
                    });

                } catch (Exception e) {
                    Log.e(CHECKOUT_TRACE, "❌❌❌ EXCEPTION: " + e.getMessage(), e);
                    finished.set(true);
                    if (checkoutTimeoutRunnable != null) {
                        mainHandler.removeCallbacks(checkoutTimeoutRunnable);
                    }

                    safeRun(() -> {
                        dismissDialog(loadingDialog);
                        setButtonsEnabled(true);
                        showRetryDialog(
                                isArabic ? "❌ خطأ" : "❌ Error",
                                e.getMessage(),
                                () -> executeCheckoutDirectly(data));
                    });
                }
            });

            Log.e(CHECKOUT_TRACE, "1️⃣5️⃣ executor.execute() returned");

        } catch (Exception e) {
            Log.e(CHECKOUT_TRACE, "❌ OUTER: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ buildCheckoutPayload v2.0
     * - لا يستدعي priceManager (القيم محسوبة مسبقاً)
     */
    private JSONObject buildCheckoutPayload(MiraApiClient client,
                                             POSCheckoutDialog.POSCheckoutData data,
                                             String currencyCode,
                                             double exchangeRate,
                                             double subtotalLocal,
                                             double discountLocal,
                                             double finalLocal)
            throws Exception {

        Log.e(BUILD_PAYLOAD, "A. Start");

        JSONObject payload = new JSONObject();
        payload.put("action", "COMPLETE_POS_SALE");
        payload.put("gate_id", client.getGateId());
        payload.put("branch_id", getActiveBranchId());
        payload.put("user_id", sessionManager != null ? sessionManager.getUserId() : 0);
        payload.put("username", sessionManager != null ? sessionManager.getUsername() : "");

        Log.e(BUILD_PAYLOAD, "B. Basic fields done");

        String customerName = (data.customerName != null && !data.customerName.trim().isEmpty())
                ? data.customerName.trim()
                : (isArabic ? "عميل نقدي" : "Cash Customer");

        String customerPhone = (data.customerPhone != null && !data.customerPhone.trim().isEmpty()
                && !"—".equals(data.customerPhone.trim())
                && !"-".equals(data.customerPhone.trim()))
                ? data.customerPhone.trim()
                : "";

        payload.put("customer_name", customerName);
        payload.put("customer_phone", customerPhone);

        JSONObject customer = new JSONObject();
        customer.put("name", customerName);
        customer.put("phone", customerPhone);
        payload.put("customer", customer);

        Log.e(BUILD_PAYLOAD, "C. Customer done: " + customerName);

        // ✅ القطع
        JSONArray itemsArr = new JSONArray();
        for (CartItem item : cartList) {
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
        payload.put("item_count", cartList.size());

        Log.e(BUILD_PAYLOAD, "D. Items done: " + cartList.size());

        // ✅ القيم المالية — Pre-computed
        double subtotalUsd = data.originalTotal;
        double discountUsd = data.discountAmount;
        double finalUsd = data.finalAmount;

        payload.put("subtotal_usd", subtotalUsd);
        payload.put("discount_usd", discountUsd);
        payload.put("total_usd", finalUsd);
        payload.put("total_amount_usd", finalUsd);
        payload.put("subtotal", subtotalLocal);
        payload.put("discount", discountLocal);
        payload.put("total", finalLocal);
        payload.put("total_amount", finalLocal);

        Log.e(BUILD_PAYLOAD, "E. Amounts done");

        // ✅ payment
        JSONObject payment = new JSONObject();
        payment.put("subtotal_usd", subtotalUsd);
        payment.put("discount_amount_usd", discountUsd);
        payment.put("final_amount_usd", finalUsd);
        payment.put("subtotal", subtotalLocal);
        payment.put("discount_amount", discountLocal);
        payment.put("final_amount", finalLocal);
        payment.put("exchange_rate", exchangeRate);
        payment.put("currency_code", currencyCode);
        payment.put("payment_method", data.paymentMethod != null ? data.paymentMethod : "CASH");
        payload.put("payment", payment);

        payload.put("notes", isArabic ? "بيع POS" : "POS Sale");

        Log.e(BUILD_PAYLOAD, "Z. Complete - size=" + payload.toString().length());

        return payload;
    }

    private void showReceipt(MiraApiClient.ApiResponse response) {
        try {
            if (!isAdded() || getActivity() == null) return;

            String receiptJson = "{}";
            if (response != null) {
                if (response.data != null) {
                    receiptJson = response.data.toString();
                } else if (response.getBody() != null && !response.getBody().isEmpty()) {
                    receiptJson = response.getBody();
                }
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

    private void showRetryDialog(String title, String message, Runnable retryAction) {
        try {
            Context ctx = getContext();
            if (ctx == null || !isAdded()) return;

            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(ctx);
            builder.setTitle(title).setMessage(message).setCancelable(false);
            if (retryAction != null) {
                builder.setPositiveButton(isArabic ? "🔄 إعادة المحاولة" : "🔄 Retry",
                        (dialog, which) -> {
                            dialog.dismiss();
                            retryAction.run();
                        });
            }
            builder.setNegativeButton(isArabic ? "إغلاق" : "Close",
                    (dialog, which) -> dialog.dismiss());
            builder.show();
        } catch (Exception e) {
            Log.e(TAG, "showRetryDialog error: " + e.getMessage());
        }
    }

    // ==================== Refund ====================

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
                        options.setPrompt(isArabic ? "📷 وجه الكاميرا نحو باركود القطعة" :
                                "📷 Aim camera at item barcode");
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
        Log.d(REFUND_DEBUG, "🔍 Searching for refund: " + cleanCode);

        selectedRefundItem.set(null);

        try {
            miraExecutor.getNetworkExecutor().execute(() -> {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("code", cleanCode);
                    payload.put("search_type", "auto");
                    payload.put("branch_id", getActiveBranchId());
                    payload.put("context", "refund_check");
                    payload.put("gate_id", apiClient != null ? apiClient.getGateId() : "");
                    payload.put("user_id", sessionManager != null ? sessionManager.getUserId() : 0);
                    payload.put("username", sessionManager != null ?
                            sessionManager.getUsername() : "");

                    MiraApiClient client = MiraApiClient.getInstance(requireContext());
                    MiraApiClient.ApiResponse response = client.searchItem(payload);

                    safeRun(() -> handleRefundSearchResponse(response, cleanCode));
                } catch (Exception e) {
                    Log.e(TAG, "searchItemForRefund error: " + e.getMessage(), e);
                    safeRun(() -> showRefundItemErrorInDialog(
                            isArabic ? "⚠️ خطأ في البحث" : "⚠️ Search error"));
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "searchItemForRefund error: " + e.getMessage());
        }
    }

    private void handleRefundSearchResponse(MiraApiClient.ApiResponse response, String code) {
        if (!isAdded()) return;

        if (response != null && response.isSuccess && response.data != null) {
            JSONObject itemData = response.data.optJSONObject("item");
            if (itemData == null) itemData = response.data;

            RefundEligibility eligibility = checkRefundEligibility(itemData);

            if (eligibility.isEligible) {
                selectedRefundItem.set(new RefundItem(
                        itemData.optString("epc", code),
                        itemData.optString("serial", itemData.optString("serial_number", "")),
                        eligibility.itemId,
                        eligibility.saleId,
                        eligibility.originalPrice,
                        itemData.optString("title", itemData.optString("item_title", ""))
                ));

                showRefundItemInfoInDialog(itemData, eligibility);
            } else {
                showRefundItemErrorInDialog(eligibility.reason);
            }
        } else {
            String errorMsg = response != null && response.error != null
                    ? response.error : "Item not found";
            showRefundItemErrorInDialog(errorMsg);
        }
    }

    private RefundEligibility checkRefundEligibility(JSONObject itemData) {
        if (itemData == null) {
            return RefundEligibility.notEligible(isArabic ?
                    "⚠️ لا توجد بيانات" : "⚠️ No data");
        }

        try {
            String status = itemData.optString("status",
                    itemData.optString("vault_status", "")).toLowerCase();

            if (!"sold".equals(status)) {
                return RefundEligibility.notEligible(isArabic
                        ? "⚠️ القطعة غير مباعة (حالتها: " + status + ")"
                        : "⚠️ Item not sold (status: " + status + ")");
            }

            String lastAction = itemData.optString("last_action", "").toLowerCase();
            if (!lastAction.isEmpty() && !"sale".equals(lastAction)
                    && !"pos_sale".equals(lastAction)) {
                return RefundEligibility.notEligible(isArabic
                        ? "⚠️ آخر عملية ليست بيعاً (آخر عملية: " + lastAction + ")"
                        : "⚠️ Last action is not a sale (" + lastAction + ")");
            }

            String saleDateStr = itemData.optString("sale_date",
                    itemData.optString("sold_at", ""));
            if (!saleDateStr.isEmpty()) {
                Long daysDiff = parseDaysDifference(saleDateStr);
                if (daysDiff != null && daysDiff > REFUND_MAX_DAYS) {
                    return RefundEligibility.notEligible(isArabic
                            ? "⚠️ انتهت فترة الإرجاع (" + daysDiff + " يوم)"
                            : "⚠️ Refund period expired (" + daysDiff + " days)");
                }
            }

            boolean alreadyRefunded = itemData.optBoolean("is_refunded", false);
            if (alreadyRefunded) {
                return RefundEligibility.notEligible(isArabic
                        ? "⚠️ تم إرجاع هذه القطعة مسبقاً"
                        : "⚠️ Item already refunded");
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
            RefundDialog dialog = refundDialog;
            if (dialog == null || !dialog.isShowing()) return;

            String title = itemData.optString("title",
                    itemData.optString("item_title", isArabic ? "قطعة ذهبية" : "Gold Item"));
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

            dialog.showRefundItemInfo(title, details);
        } catch (Exception e) {
            Log.e(TAG, "showRefundItemInfoInDialog error: " + e.getMessage());
        }
    }

    private void showRefundItemErrorInDialog(String message) {
        try {
            RefundDialog dialog = refundDialog;
            if (dialog == null) return;
            dialog.showRefundItemError(message);
        } catch (Exception e) {
            Log.e(TAG, "showRefundItemErrorInDialog error: " + e.getMessage());
        }
    }

    private void processRefund(String code, String reason) {
        final AlertDialog loadingDialog = showProfessionalLoadingDialog();
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
                    payload.put("item_id", itemToRefund != null ? itemToRefund.itemId : 0);
                    payload.put("sale_id", itemToRefund != null ? itemToRefund.saleId : 0);
                    payload.put("original_price_usd",
                            itemToRefund != null ? itemToRefund.originalPrice : 0);

                    payload.put("reason", reason != null ? reason : "customer_request");
                    payload.put("refund_type", "full");
                    payload.put("branch_id", getActiveBranchId());
                    payload.put("user_id", sessionManager != null ?
                            sessionManager.getUserId() : 0);
                    payload.put("gate_id", apiClient != null ? apiClient.getGateId() : "");
                    payload.put("username", sessionManager != null ?
                            sessionManager.getUsername() : "");
                    payload.put("timestamp", System.currentTimeMillis());

                    MiraApiClient client = MiraApiClient.getInstance(requireContext());
                    MiraApiClient.ApiResponse response = client.processRefund(payload);

                    safeRun(() -> {
                        dismissDialog(loadingDialog);
                        if (refundDialog != null && refundDialog.isShowing()) {
                            refundDialog.dismiss();
                        }

                        if (response != null && response.isSuccess) {
                            restoreItemToCache(code, itemToRefund);

                            showSuccessMessage(
                                    isArabic ? "✅ تم الإرجاع" : "✅ Refund Successful",
                                    isArabic ? "تم إرجاع القطعة بنجاح إلى المخزون" :
                                            "Item successfully returned to inventory",
                                    true);

                            notifyDashboardAndInventoryUpdate("refund");
                            selectedRefundItem.set(null);
                        } else {
                            String err = response != null && response.error != null
                                    ? response.error : "Unknown error";
                            showSuccessMessage(
                                    isArabic ? "❌ فشل الإرجاع" : "❌ Refund Failed",
                                    err, false);
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "processRefund error: " + e.getMessage(), e);
                    safeRun(() -> {
                        dismissDialog(loadingDialog);
                        showSuccessMessage(isArabic ? "❌ خطأ" : "❌ Error",
                                e.getMessage(), false);
                    });
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "processRefund error: " + e.getMessage());
        }
    }

    private void restoreItemToCache(String code, RefundItem item) {
        try {
            Context ctx = getContext();
            if (ctx == null) return;

            MiraLocalItemCache cache = MiraLocalItemCache.getInstance(ctx);

            Set<String> ids = new HashSet<>();
            if (item != null) {
                if (item.epc != null && !item.epc.isEmpty()) ids.add(item.epc);
                if (item.serialNo != null && !item.serialNo.isEmpty()) ids.add(item.serialNo);
            }
            if (code != null && !code.isEmpty()) ids.add(code);

            for (String id : ids) {
                cache.markItemAsAvailable(id);
                Log.d(TAG, "✅ Item restored to cache: " + id);
            }
        } catch (Exception e) {
            Log.e(TAG, "restoreItemToCache error: " + e.getMessage());
        }
    }

    // ==================== Notifications ====================

    private void notifyDashboardAndInventoryUpdate(String source) {
        try {
            if (!isAdded() || getContext() == null) return;

            Context ctx = getContext();
            int branchId = getActiveBranchId();
            long timestamp = System.currentTimeMillis();

            sendBroadcastSafely(ctx, "com.example.uhf.REFRESH_SALES", source,
                    branchId, timestamp);
            sendBroadcastSafely(ctx, "com.example.uhf.REFRESH_INVENTORY", source,
                    branchId, timestamp);
            sendBroadcastSafely(ctx, "com.example.uhf.REFRESH_DASHBOARD", source,
                    branchId, timestamp);

        } catch (Exception e) {
            Log.e(TAG, "notifyDashboardAndInventoryUpdate error: " + e.getMessage());
        }
    }

    private void sendBroadcastSafely(Context ctx, String action, String source,
                                      int branchId, long timestamp) {
        try {
            Intent intent = new Intent(action);
            intent.setPackage(ctx.getPackageName());
            intent.putExtra("source", source);
            intent.putExtra("branch_id", branchId);
            intent.putExtra("timestamp", timestamp);
            ctx.sendBroadcast(intent);
        } catch (Exception e) {
            Log.w(TAG, "sendBroadcastSafely error for " + action, e);
        }
    }

    // ==================== Loading Dialog ====================

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
            tvLoading.setText(isArabic ? "جاري إتمام العملية..." : "Processing...");
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
            Log.e(TAG, "showProfessionalLoadingDialog error: " + e.getMessage());
            return null;
        }
    }

    protected void dismissDialog(AlertDialog dialog) {
        try {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        } catch (Exception e) {
            Log.e(TAG, "dismissDialog error: " + e.getMessage());
        }
    }

    // ==================== Permissions ====================

    private void setButtonsEnabled(boolean enabled) {
        safeRun(() -> {
            try {
                boolean canUsePos = hasPosPermission();
                if (btnCheckout != null)
                    btnCheckout.setEnabled(enabled && canUsePos && !cartList.isEmpty());
                if (btnClearCart != null) btnClearCart.setEnabled(enabled);
                if (btnAddManual != null) btnAddManual.setEnabled(enabled && canUsePos);
                if (btnOpenScanner != null) btnOpenScanner.setEnabled(enabled && canUsePos);
                if (btnPosRFIDMode != null) btnPosRFIDMode.setEnabled(enabled && canUsePos);
                if (etManualEpc != null) etManualEpc.setEnabled(enabled && canUsePos);
            } catch (Exception e) {
                Log.e(TAG, "setButtonsEnabled error: " + e.getMessage());
            }
        });
    }

    private void applyPermissions() {
        try {
            boolean canUsePos = hasPosPermission();

            if (btnCheckout != null) btnCheckout.setEnabled(false);
            if (btnAddManual != null) btnAddManual.setEnabled(canUsePos);
            if (btnOpenScanner != null) btnOpenScanner.setEnabled(canUsePos);
            if (btnPosRFIDMode != null) btnPosRFIDMode.setEnabled(canUsePos);
            if (etManualEpc != null) etManualEpc.setEnabled(canUsePos);

            if (btnPosRefund != null) {
                boolean canRefund = permissionManager != null && (
                        permissionManager.canEdit("pos")
                                || permissionManager.isAdmin()
                                || permissionManager.isManager());
                btnPosRefund.setVisibility(canRefund ? View.VISIBLE : View.GONE);
            }

            if (!canUsePos && tvCartStatus != null) {
                tvCartStatus.setText(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            }
        } catch (Exception e) {
            Log.e(TAG, "applyPermissions error: " + e.getMessage());
        }
    }

    // ==================== Public APIs ====================

    public void showCartBottomSheet() {
        try {
            if (!isAdded() || getContext() == null) return;
            if (cartList.isEmpty()) {
                showToast(isArabic ? "🛒 السلة فارغة" : "🛒 Cart is empty");
                return;
            }

            activeCartSheet = new BottomSheetDialog(requireContext());

            LinearLayout root = new LinearLayout(requireContext());
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(24, 24, 24, 24);

            TextView title = new TextView(requireContext());
            title.setText(isArabic ? "🛒 سلة البيع (" + cartList.size() + ")"
                    : "🛒 Cart (" + cartList.size() + ")");
            title.setTextSize(18);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setPadding(0, 0, 0, 16);
            root.addView(title);

            RecyclerView rv = new RecyclerView(requireContext());
            rv.setLayoutManager(new LinearLayoutManager(requireContext()));
            rv.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            CartAdapter sheetAdapter = new CartAdapter(
                    cartList,
                    pos -> {
                        removeItemFromCart(pos);
                        if (activeCartSheet != null && activeCartSheet.isShowing()) {
                            activeCartSheet.dismiss();
                        }
                        if (!cartList.isEmpty()) {
                            showCartBottomSheet();
                        }
                    },
                    isArabic,
                    priceManager);
            rv.setAdapter(sheetAdapter);
            root.addView(rv);

            TextView totalView = new TextView(requireContext());
            totalView.setText(isArabic ? "الإجمالي: " +
                    priceManager.formatPriceDefault(getCartTotal())
                    : "Total: " + priceManager.formatPriceDefault(getCartTotal()));
            totalView.setTextSize(16);
            totalView.setTypeface(null, android.graphics.Typeface.BOLD);
            totalView.setPadding(0, 16, 0, 0);
            totalView.setTextColor(Color.parseColor("#FFD700"));
            root.addView(totalView);

            activeCartSheet.setContentView(root);
            activeCartSheet.show();

        } catch (Exception e) {
            Log.e(TAG, "showCartBottomSheet error: " + e.getMessage(), e);
        }
    }

    public void showDiscountDialog() {
        try {
            if (!isAdded() || cartList.isEmpty()) {
                showToast(isArabic ? "السلة فارغة" : "Cart is empty");
                return;
            }
            processCheckoutAndStockUpdate();
        } catch (Exception e) {
            Log.e(TAG, "showDiscountDialog error: " + e.getMessage());
        }
    }

    public void showLastReceiptDialog() {
        try {
            if (lastSaleResponse == null ||
                    (System.currentTimeMillis() - lastSaleResponseTime) > LAST_SALE_VALIDITY_MS) {
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
            if (etManualEpc != null) {
                etManualEpc.requestFocus();
                etManualEpc.performClick();
                if (getActivity() != null) {
                    UIHelper.showKeyboard(getActivity(), etManualEpc);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "focusSearchField error: " + e.getMessage());
        }
    }

    // ==================== ActivityResultLauncher ====================

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

    // ==================== دوال مساعدة ====================

    private void showToast(String msg) {
        if (msg == null || msg.isEmpty()) return;
        safeRun(() -> {
            try {
                if (getContext() != null && isAdded()) {
                    Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "showToast error: " + e.getMessage());
            }
        });
    }

    private void safeRun(Runnable r) {
        try {
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (isAdded() && !isRemoving() && !isDetached()) {
                        try {
                            r.run();
                        } catch (Exception e) {
                            Log.e(TAG, "safeRun inner error: " + e.getMessage());
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
            return branchesManager != null ? branchesManager.getActiveBranchId() : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    private boolean hasPosPermission() {
        try {
            return permissionManager != null && (
                    permissionManager.canView("pos")
                    || permissionManager.isPosOperator()
                    || permissionManager.isManager()
            );
        } catch (Exception e) {
            Log.e(TAG, "hasPosPermission error: " + e.getMessage());
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

    private void stopRFIDScan() {
        try {
            if (rfidManager == null) return;
            isRFIDScanActive = false;
            rfidManager.stopScan();
        } catch (Exception e) {
            Log.e(TAG, "stopRFIDScan error: " + e.getMessage());
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

    private void toggleRFIDMode() {
        try {
            if (!isAdded() || rfidManager == null) return;

            isRFIDModeActive = !isRFIDModeActive;
            if (isRFIDModeActive) {
                startRFIDScan();
                if (btnPosRFIDMode != null)
                    btnPosRFIDMode.setText(isArabic ? "📡 RFID نشط" : "📡 RFID Active");
                if (tvCartStatus != null)
                    tvCartStatus.setText(isArabic ? "📡 جاهز لمسح RFID..." : "📡 Ready for RFID...");
            } else {
                stopRFIDScan();
                if (btnPosRFIDMode != null)
                    btnPosRFIDMode.setText(isArabic ?
                            getString(R.string.pos_scan_rfid) : "📡 Scan RFID");
                if (tvCartStatus != null)
                    tvCartStatus.setText(isArabic ?
                            getString(R.string.pos_ready_scan) : "Ready to scan...");
            }
        } catch (Exception e) {
            Log.e(TAG, "toggleRFIDMode error: " + e.getMessage());
            isRFIDModeActive = false;
        }
    }

    public void onRingScanned(String epc) {
        try {
            if (epc == null || epc.trim().isEmpty()) return;
            if (!isRFIDScanActive) return;

            long currentTime = System.currentTimeMillis();
            Long lastTime = lastScannedTimes.get(epc);
            if (lastTime != null && (currentTime - lastTime < SCAN_COOLDOWN_MS)) return;
            lastScannedTimes.put(epc, currentTime);

            if (scannedEpcs.contains(epc)) {
                showToast(isArabic ? "⚠️ مضافة مسبقاً!" : "⚠️ Already in cart!");
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

            long currentTime = System.currentTimeMillis();
            Long lastTime = lastScannedTimes.get(data);
            if (lastTime != null && (currentTime - lastTime < SCAN_COOLDOWN_MS)) return;
            lastScannedTimes.put(data, currentTime);

            if (scannedEpcs.contains(data)) {
                showToast(isArabic ? "⚠️ مضافة مسبقاً!" : "⚠️ Already in cart!");
                playAlertSound(2);
                return;
            }
            processRingEpc(data, "barcode");
        } catch (Exception e) {
            Log.e(TAG, "onBarcodeScanned error: " + e.getMessage());
        }
    }

    private void submitManualEpc() {
        try {
            if (!hasPosPermission()) {
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

                if (getActivity() != null && etManualEpc != null) {
                    UIHelper.hideKeyboard(getActivity());
                    etManualEpc.clearFocus();
                }
            } else {
                showToast(isArabic ? "أدخل EPC أو باركود" : "Enter EPC or barcode");
            }
        } catch (Exception e) {
            Log.e(TAG, "submitManualEpc error: " + e.getMessage());
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
                    if (etManualEpc != null) etManualEpc.setText(data);

                    if (isAdded() && data != null && !data.isEmpty()) {
                        processRingEpc(data, "barcode");
                        if (etManualEpc != null) etManualEpc.setText("");
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

            if (!fm.isStateSaved()) {
                fm.beginTransaction()
                        .replace(containerId, sf)
                        .addToBackStack("barcode_scanner")
                        .commit();
            }
        } catch (Exception e) {
            Log.e(TAG, "openBarcodeScanner error: " + e.getMessage());
            showToast(isArabic ? "⚠️ تعذر فتح الماسح" : "⚠️ Unable to open scanner");
        }
    }

    private void initSmartScale() {
        try {
            scaleManager = new SmartScaleManager(requireContext(),
                    new SmartScaleManager.ScaleCallback() {
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

            simulationHelper = new ScaleSimulationHelper(requireContext(),
                    new ScaleSimulationHelper.SimulationCallback() {
                @Override
                public void onWeightReceived(double weight, String label) {
                    safeRun(() -> {
                        liveScaleWeight = weight;
                        if (tvScaleStatus != null) {
                            tvScaleStatus.setText(String.format(Locale.US,
                                    "⚖️ %.2fg (محاكاة)", weight));
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

        try {
            CartItem topItem = cartList.get(0);
            if (topItem.scaleWeight != currentScaleWeight) {
                topItem.scaleWeight = currentScaleWeight;

                if (cartAdapter != null) {
                    cartAdapter.notifyItemChanged(0);
                }
                updateCartSummary();
            }
        } catch (Exception e) {
            Log.e(TAG, "updateCartItemsScaleWeight error: " + e.getMessage());
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
}
