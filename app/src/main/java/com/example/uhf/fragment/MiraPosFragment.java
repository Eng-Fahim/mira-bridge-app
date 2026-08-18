package com.example.uhf.fragment;

import com.example.uhf.tools.SessionManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
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
import com.example.uhf.tools.UIHelper;
import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.RFIDManager;

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

public class MiraPosFragment extends MiraBaseFragment {

    private static final String TAG = "MiraPosFragment";
    private static final String PENDING_SALES_PREFS = "pos_pending_sales";

    // ==================== MiraPriceManager ====================
    private MiraPriceManager priceManager;

    // RFID Manager & Locks
    private RFIDManager rfidManager;
    private boolean isRFIDScanActive = false;
    private final Set<String> pendingEpcs = new HashSet<>();
    
    // ✅ Debouncing
    private final Map<String, Long> lastScannedTimes = new HashMap<>();

    // UI Elements
    private TextView tvScaleStatus, tvItemCount, tvTotalAmount, tvCartStatus;
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
    private MiraBranchesManager branchesManager;

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
        initSmartScale();
        checkPendingSales();
        updateCartSummary();
    }

    @Override
    public void onResume() {
        super.onResume();

        if (getActivity() instanceof UHFMainActivity)
            ((UHFMainActivity) getActivity()).setActivePOSFragment(this);

        // ✅ الاتصال بالميزان عند فتح الواجهة
        if (scaleConnector != null) {
            //scaleConnector.connect();
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
        
        // ✅ إغلاق اتصال الميزان عند التوقف
        if (scaleConnector != null) {
            scaleConnector.disconnect();
        }
        
        if (getActivity() instanceof UHFMainActivity)
            ((UHFMainActivity) getActivity()).setActivePOSFragment(null);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopRFIDScan();
        // ✅ إلغاء المستمع لمنع تسريب الذاكرة
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

    // ==================== دوال مساعدة ====================

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

    // ==================== تهيئة الواجهة ====================

    private void bindViews(View view) {
        tvScaleStatus = view.findViewById(R.id.tvPosScaleStatus);
        tvItemCount = view.findViewById(R.id.tvPosItemCount);
        tvTotalAmount = view.findViewById(R.id.tvPosTotalAmount);
        tvCartStatus = view.findViewById(R.id.tvPosCartStatus);
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
        if (isArabic) {
            if (etManualEpc != null) etManualEpc.setHint(R.string.pos_manual_hint);
            if (btnCheckout != null) btnCheckout.setText(R.string.pos_checkout);
            if (btnClearCart != null) btnClearCart.setText(R.string.pos_clear);
            if (btnOpenScanner != null) btnOpenScanner.setText(R.string.pos_scan_gs1);
            if (btnPosRFIDMode != null && !isRFIDModeActive) btnPosRFIDMode.setText(R.string.pos_scan_rfid);
            if (btnAddManual != null) btnAddManual.setText(R.string.pos_add);
        } else {
            if (etManualEpc != null) etManualEpc.setHint("Enter EPC or barcode manually...");
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

        if (btnAddManual != null) btnAddManual.setOnClickListener(v -> submitManualEpc());
        if (btnClearCart != null) btnClearCart.setOnClickListener(v -> {
            UIHelper.hideKeyboard(getActivity());
            clearCart();
        });
        if (btnCheckout != null) btnCheckout.setOnClickListener(v -> {
            UIHelper.hideKeyboard(getActivity());
            processCheckoutAndStockUpdate();
        });
        if (btnOpenScanner != null) btnOpenScanner.setOnClickListener(v -> {
            UIHelper.hideKeyboard(getActivity());
            openBarcodeScanner();
        });
        if (btnPosRFIDMode != null) btnPosRFIDMode.setOnClickListener(v -> {
            UIHelper.hideKeyboard(getActivity());
            toggleRFIDMode();
        });
    }

    private void submitManualEpc() {
        UIHelper.hideKeyboard(getActivity());
        String epc = etManualEpc != null ? etManualEpc.getText().toString().trim() : "";
        if (!epc.isEmpty()) {
            if (scannedEpcs.contains(epc)) {
                showToast(isArabic ? "⚠️ هذه القطعة مضافة مسبقاً في السلة!" : "⚠️ This item is already in cart!");
                playAlertSound(2);
                return;
            }
            processRingEpc(epc, "manual");
            if (etManualEpc != null) etManualEpc.setText("");
        } else {
            showToast(isArabic ? "أدخل EPC أو باركود" : "Enter EPC or barcode");
        }
    }

    // ==================== الميزان الذكي ====================

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
                Log.e(TAG, "Scale: " + e);
            }
        });
    }

    // ==================== الماسح الضوئي ====================

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

    // ==================== RFID ====================

    private void toggleRFIDMode() {
        isRFIDModeActive = !isRFIDModeActive;
        if (isRFIDModeActive) {
            startRFIDScan();
            if (btnPosRFIDMode != null) btnPosRFIDMode.setText(isArabic ? "📡 RFID نشط" : "📡 RFID Active");
            if (tvCartStatus != null) tvCartStatus.setText(isArabic ? "📡 جاهز لمسح RFID..." : "📡 Ready for RFID scan...");
        } else {
            stopRFIDScan();
            if (btnPosRFIDMode != null) btnPosRFIDMode.setText(isArabic ? getString(R.string.pos_scan_rfid) : "📡 Scan RFID");
            if (tvCartStatus != null) tvCartStatus.setText(isArabic ? getString(R.string.pos_ready_scan) : "Ready to scan items...");
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

    // ==================== مسح القطع مع Debouncing ====================

    public void onRingScanned(String epc) {
        long currentTime = System.currentTimeMillis();
        Long lastTime = lastScannedTimes.get(epc);
        if (lastTime != null && (currentTime - lastTime < 1500)) return;
        lastScannedTimes.put(epc, currentTime);

        if (scannedEpcs.contains(epc)) {
            safeRun(() -> {
                showToast(isArabic ? "⚠️ هذه القطعة مضافة مسبقاً في السلة!" : "⚠️ This item is already in cart!");
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
                showToast(isArabic ? "⚠️ هذه القطعة مضافة مسبقاً في السلة!" : "⚠️ This item is already in cart!");
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
        safeRun(() -> {
            if (tvCartStatus != null) tvCartStatus.setText(isArabic ? "🔍 جاري الاستعلام..." : "🔍 Querying...");
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
                MiraApiClient.ApiResponse response = client.authorize(code, "pos_sale", extraParams);
                if (response != null && response.isSuccess && response.data != null
                        && response.data.optJSONObject("item") != null) {
                    parseItemResponse(code, response.data);
                } else {
                    handleItemNotFound(code);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error: " + e.getMessage());
                handleItemNotFound(code);
            } finally {
                synchronized (pendingEpcs) {
                    pendingEpcs.remove(code);
                }
            }
        }).start();
    }

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
                        showToast(isArabic ? "🚨 تم منع العملية - عبث مكتشف!" : "🚨 Operation blocked - Tamper detected!");
                        if (tvCartStatus != null) tvCartStatus.setText(isArabic ? "🚨 عبث مكتشف - ممنوع" : "🚨 Tamper detected - Blocked");
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
            safeRun(() -> {
                if (!isAvailable) {
                    showToast(isArabic ? "❌ غير متاحة: " + vaultStatus : "❌ Unavailable: " + vaultStatus);
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
            Log.e(TAG, "Parse: " + e.getMessage());
            handleItemNotFound(code);
        }
    }

    private void handleItemNotFound(String code) {
        safeRun(() -> {
            showToast(isArabic ? "❌ غير موجود: " + code : "❌ Not found: " + code);
            updateCartSummary();
        });
    }

    // ==================== إدارة السلة ====================

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
                btnCheckout.setEnabled(fc > 0 && !fb);
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

    // ==================== إتمام البيع ====================

    private void processCheckoutAndStockUpdate() {
        if (cartList.isEmpty()) {
            showToast(isArabic ? "السلة فارغة" : "Cart is empty");
            return;
        }
        if (!isAdded() || getActivity() == null) return;
        UIHelper.hideKeyboard(getActivity());
        try {
            POSCheckoutDialog dialog = POSCheckoutDialog.newInstance(getCartTotal(), cartList.size());
            dialog.setCheckoutListener(this::executeCheckout);
            FragmentManager fm = getChildFragmentManager();
            if (!fm.isStateSaved()) dialog.show(fm, "POSCheckoutDialog");
        } catch (Exception e) {
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
                    obj.put("sale_price", item.price);
                    obj.put("item_id", item.itemId > 0 ? item.itemId : 0);
                    itemsArr.put(obj);
                }
                payload.put("items", itemsArr);

                JSONObject payment = new JSONObject();
                payment.put("subtotal", getCartTotal());
                payment.put("discount_percent", data.discount);
                payment.put("discount_amount", data.discountAmount);
                payment.put("final_amount", data.finalAmount);
                payment.put("account_code", getAccountCode(data.paymentMethod));
                payment.put("currency", "YER");
                payload.put("payment", payment);

                MiraApiClient.ApiResponse response = client.checkout(payload);

                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    setButtonsEnabled(true);

                    if (response != null && response.isSuccess) {
                        playAlertSound(1);
                        int soldCount = cartList.size();
                        double finalAmount = data.finalAmount;
                        String paymentMethod = data.paymentMethod;
                        clearCart();
                        showSuccessDialog(finalAmount, soldCount, paymentMethod);
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

    private String getAccountCode(String paymentMethod) {
        if (paymentMethod == null) return "110101";
        switch (paymentMethod.toUpperCase()) {
            case "CARD": case "NET": case "VISA": case "MASTERCARD": return "110201";
            case "CREDIT": return "110301";
            case "BANK_TRANSFER": return "110202";
            case "CASH": default: return "110101";
        }
    }

    // ==================== دعم العمل دون اتصال ====================

    private void saveFullSaleLocally(POSCheckoutDialog.POSCheckoutData data) {
        if (getContext() == null) return;
        try {
            SharedPreferences p = getContext().getSharedPreferences(PENDING_SALES_PREFS, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(p.getString("pending", "[]"));
            JSONObject sale = new JSONObject();
            sale.put("id", System.currentTimeMillis());
            sale.put("customer_name", data.customerName);
            sale.put("customer_phone", data.customerPhone);
            sale.put("payment_method", data.paymentMethod);
            sale.put("discount", data.discount);
            sale.put("final_amount", data.finalAmount);
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
                obj.put("price", item.price);
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
                        customer.put("payment_method", sale.optString("payment_method", "CASH"));
                        payload.put("customer", customer);
                        payload.put("items", sale.optJSONArray("items"));
                        JSONObject payment = new JSONObject();
                        payment.put("subtotal", sale.optDouble("final_amount", 0));
                        payment.put("discount_percent", sale.optDouble("discount", 0));
                        payment.put("final_amount", sale.optDouble("final_amount", 0));
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

    // ==================== DIALOGS ====================

    private AlertDialog showProfessionalLoadingDialog() {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return null;

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 40);
        layout.setGravity(android.view.Gravity.CENTER);

        ProgressBar progressBar = new ProgressBar(ctx);
        progressBar.setIndeterminate(true);
        progressBar.setScaleX(1.5f);
        progressBar.setScaleY(1.5f);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pbParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        progressBar.setLayoutParams(pbParams);
        layout.addView(progressBar);

        TextView tvLoading = new TextView(ctx);
        tvLoading.setText(isArabic ? "🔄 جاري إتمام البيع..." : "🔄 Processing sale...");
        tvLoading.setTextSize(16);
        tvLoading.setTypeface(null, android.graphics.Typeface.BOLD);
        tvLoading.setGravity(android.view.Gravity.CENTER);
        tvLoading.setPadding(0, 20, 0, 8);
        layout.addView(tvLoading);

        TextView tvSubtext = new TextView(ctx);
        tvSubtext.setText(isArabic ? "يرجى الانتظار...\nيتم تحديث المخزون وتسجيل القيود المحاسبية" :
                "Please wait...\nUpdating inventory and recording journal entries");
        tvSubtext.setTextSize(12);
        tvSubtext.setTextColor(Color.GRAY);
        tvSubtext.setGravity(android.view.Gravity.CENTER);
        tvSubtext.setPadding(0, 8, 0, 0);
        layout.addView(tvSubtext);

        builder.setView(layout);
        builder.setCancelable(false);
        AlertDialog dialog = builder.create();
        dialog.show();
        return dialog;
    }

    private void setButtonsEnabled(boolean enabled) {
        safeRun(() -> {
            if (btnCheckout != null) {
                btnCheckout.setEnabled(enabled && !cartList.isEmpty());
                btnCheckout.setAlpha(enabled && !cartList.isEmpty() ? 1.0f : 0.5f);
            }
            if (btnClearCart != null) btnClearCart.setEnabled(enabled);
            if (btnAddManual != null) btnAddManual.setEnabled(enabled);
            if (btnOpenScanner != null) btnOpenScanner.setEnabled(enabled);
            if (btnPosRFIDMode != null) btnPosRFIDMode.setEnabled(enabled);
            if (etManualEpc != null) etManualEpc.setEnabled(enabled);
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
        tvIcon.setText("✅");
        tvIcon.setTextSize(40);
        tvIcon.setGravity(android.view.Gravity.CENTER);
        layout.addView(tvIcon);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(isArabic ? "تم البيع بنجاح!" : "Sale Completed!");
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setGravity(android.view.Gravity.CENTER);
        tvTitle.setPadding(0, 10, 0, 20);
        layout.addView(tvTitle);

        com.google.android.material.card.MaterialCardView card =
                new com.google.android.material.card.MaterialCardView(ctx);
        card.setRadius(12);
        card.setCardElevation(2);
        card.setPadding(16, 16, 16, 16);
        LinearLayout cardLayout = new LinearLayout(ctx);
        cardLayout.setOrientation(LinearLayout.VERTICAL);
        addDetailRow(cardLayout, isArabic ? "📦 عدد القطع" : "📦 Items", String.valueOf(itemCount));
        addDetailRow(cardLayout, isArabic ? "💰 المبلغ الإجمالي" : "💰 Total Amount", priceManager.formatPriceDefault(amount));
        addDetailRow(cardLayout, isArabic ? "💳 طريقة الدفع" : "💳 Payment", paymentMethod);
        card.addView(cardLayout);
        layout.addView(card);

        builder.setView(layout);
        builder.setPositiveButton(isArabic ? "🎉 متابعة" : "🎉 Continue", (d, w) -> d.dismiss());
        builder.setCancelable(false);
        builder.show();
    }

    private void addDetailRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 4, 0, 4);
        TextView tvLabel = new TextView(requireContext());
        tvLabel.setText(label);
        tvLabel.setTextSize(12);
        tvLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        tvLabel.setMinWidth(150);
        row.addView(tvLabel);
        TextView tvValue = new TextView(requireContext());
        tvValue.setText(value);
        tvValue.setTextSize(12);
        tvValue.setPadding(8, 0, 0, 0);
        row.addView(tvValue);
        parent.addView(row);
    }

    // ==================== Adapter ====================

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
            if (h.tvKaratWeight != null)
                h.tvKaratWeight.setText(String.format(Locale.US, "%s | %.2fg", item.karat,
                        item.scaleWeight > 0 ? item.scaleWeight : item.weight));
            if (h.tvPrice != null) h.tvPrice.setText(priceManager.formatPriceDefault(item.price));
            if (h.tvStatus != null) {
                h.tvStatus.setText(item.isAvailable ? (isArabic ? "✅ متاحة" : "✅ Available") : (isArabic ? "❌ غير متاحة" : "❌ Unavailable"));
            }
            if (h.btnRemove != null) h.btnRemove.setOnClickListener(v -> {
                int p = h.getAdapterPosition();
                if (p != RecyclerView.NO_POSITION) removeListener.onRemove(p);
            });
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
