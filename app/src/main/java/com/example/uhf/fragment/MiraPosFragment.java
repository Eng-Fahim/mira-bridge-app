package com.example.uhf.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.dialog.POSCheckoutDialog;
import com.example.uhf.engine.SmartScaleConnector;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.tools.UIHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MiraPosFragment extends Fragment {

    private static final String TAG = "MiraPosFragment";
    private static final String PENDING_SALES_PREFS = "pos_pending_sales";

    private static final Map<String, Double> GOLD_RATES = new HashMap<>();
    static {
        GOLD_RATES.put("24K", 75.0);
        GOLD_RATES.put("22K", 68.75);
        GOLD_RATES.put("21K", 65.62);
        GOLD_RATES.put("18K", 56.25);
    }

    // UI Elements
    private TextView tvScaleStatus, tvItemCount, tvTotalAmount, tvCartStatus;
    private EditText etManualEpc;
    private Button btnAddManual, btnCheckout, btnClearCart;
    private Button btnOpenScanner, btnPosRFIDMode;
    private RecyclerView rvCartItems;

    private boolean isRFIDModeActive = false;
    private boolean isArabic;

    public static class CartItem {
        public String epc, name, karat, status, serialNo, gtin, location;
        public double weight, scaleWeight, price;
        public boolean isAvailable;
        public int itemId;

        public CartItem(String epc, String name, String karat, double weight,
                        double scaleWeight, double price, boolean isAvailable, String status) {
            this.epc = epc; this.name = name; this.karat = karat;
            this.weight = weight; this.scaleWeight = scaleWeight;
            this.price = price; this.isAvailable = isAvailable; this.status = status;
        }
    }

    private final List<CartItem> cartList = new ArrayList<>();
    private final Set<String> scannedEpcs = new HashSet<>();
    private CartAdapter cartAdapter;
    private SmartScaleConnector scaleConnector;
    private double liveScaleWeight = 0.0;
    private MiraBranchesManager branchesManager;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_pos, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        branchesManager = MiraBranchesManager.getInstance(requireContext());
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
    
    if (branchesManager.hasActiveBranch() && tvCartStatus != null) {
        tvCartStatus.setText("📍 " + branchesManager.getActiveBranchDisplayName() + " | " + (isArabic ? "جاهز" : "Ready"));
    }
    
    // ✅ مزامنة الطلبات المعلقة عند الاتصال
    syncPendingSales();
}
    @Override public void onPause() {
        super.onPause();
        if (getActivity() instanceof UHFMainActivity)
            ((UHFMainActivity) getActivity()).setActivePOSFragment(null);
    }

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

        // تطبيق اللغة
        applyLanguage();
    }

    private void applyLanguage() {
        if (isArabic) {
            if (etManualEpc != null) etManualEpc.setHint("أدخل كود EPC أو الباركود يدوياً...");
            if (btnCheckout != null) btnCheckout.setText("إتمام البيع");
            if (btnClearCart != null) btnClearCart.setText("تفريغ");
            if (btnOpenScanner != null) btnOpenScanner.setText("📷 مسح GS1");
            if (btnPosRFIDMode != null && !isRFIDModeActive) btnPosRFIDMode.setText("📡 مسح RFID");
            if (btnAddManual != null) btnAddManual.setText("إضافة");
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
            cartAdapter = new CartAdapter(cartList, this::removeItemFromCart, isArabic);
            rvCartItems.setAdapter(cartAdapter);
        }
    }

    private void setupListeners() {
        if (btnAddManual != null) {
            btnAddManual.setOnClickListener(v -> {
                UIHelper.hideKeyboard(getActivity());
                String epc = etManualEpc != null ? etManualEpc.getText().toString().trim() : "";
                if (!epc.isEmpty()) { processRingEpc(epc, "manual"); if (etManualEpc != null) etManualEpc.setText(""); }
                else showToast(isArabic ? "أدخل EPC أو باركود" : "Enter EPC or barcode");
            });
        }
        if (btnClearCart != null) btnClearCart.setOnClickListener(v -> { UIHelper.hideKeyboard(getActivity()); clearCart(); });
        if (btnCheckout != null) btnCheckout.setOnClickListener(v -> { UIHelper.hideKeyboard(getActivity()); processCheckoutAndStockUpdate(); });
        if (btnOpenScanner != null) btnOpenScanner.setOnClickListener(v -> { UIHelper.hideKeyboard(getActivity()); openBarcodeScanner(); });
        if (btnPosRFIDMode != null) btnPosRFIDMode.setOnClickListener(v -> { UIHelper.hideKeyboard(getActivity()); toggleRFIDMode(); });
    }

    private void initSmartScale() {
        scaleConnector = new SmartScaleConnector();
        scaleConnector.setListener(new SmartScaleConnector.ScaleListener() {
            @Override public void onWeightReceived(double w) {
                liveScaleWeight = w;
                safeRun(() -> {
                    if (tvScaleStatus != null) {
                        tvScaleStatus.setText(String.format(Locale.US, "⚖️ %.2fg", w));
                        tvScaleStatus.setTextColor(Color.parseColor("#4ADE80"));
                    }
                });
            }
            @Override public void onScaleConnected(String n) {
                safeRun(() -> {
                    if (tvScaleStatus != null) { tvScaleStatus.setText("⚖️ " + n); tvScaleStatus.setTextColor(Color.parseColor("#38BDF8")); }
                });
            }
            @Override public void onScaleDisconnected() {
                liveScaleWeight = 0.0;
                safeRun(() -> {
                    if (tvScaleStatus != null) { tvScaleStatus.setText(isArabic ? "⚖️ غير متصل" : "⚖️ Offline"); tvScaleStatus.setTextColor(Color.parseColor("#94A3B8")); }
                });
            }
            @Override public void onScaleError(String e) { Log.e(TAG, "Scale: " + e); }
        });
    }

    private void safeRun(Runnable r) { if (isAdded() && getActivity() != null) getActivity().runOnUiThread(() -> { if (isAdded()) r.run(); }); }

    private void openBarcodeScanner() {
        if (!isAdded()) return;
        MiraBarcodeScannerFragment sf = new MiraBarcodeScannerFragment();
        sf.setOnBarcodeScannedListener((data, format) -> { onBarcodeScanned(data); if (getParentFragmentManager().getBackStackEntryCount() > 0) getParentFragmentManager().popBackStack(); });
        View view = getView();
        int cid = (view != null && view.getParent() instanceof View) ? ((View) view.getParent()).getId() : android.R.id.content;
        getParentFragmentManager().beginTransaction().replace(cid, sf).addToBackStack("barcode_scanner").commit();
    }

    private void toggleRFIDMode() {
        isRFIDModeActive = !isRFIDModeActive;
        if (isRFIDModeActive) {
            if (btnPosRFIDMode != null) { btnPosRFIDMode.setText(isArabic ? "📡 RFID نشط" : "📡 RFID Active"); btnPosRFIDMode.setTextColor(Color.WHITE); }
            if (tvCartStatus != null) { tvCartStatus.setText(isArabic ? "📡 جاهز لمسح RFID..." : "📡 Ready for RFID scan..."); tvCartStatus.setTextColor(Color.parseColor("#4ADE80")); }
            if (getActivity() instanceof UHFMainActivity) ((UHFMainActivity) getActivity()).setActivePOSFragment(this);
        } else {
            if (btnPosRFIDMode != null) { btnPosRFIDMode.setText(isArabic ? "📡 مسح RFID" : "📡 Scan RFID"); btnPosRFIDMode.setTextColor(Color.WHITE); }
            if (tvCartStatus != null) { tvCartStatus.setText(isArabic ? "جاهز لمسح القطع..." : "Ready to scan items..."); tvCartStatus.setTextColor(Color.parseColor("#94A3B8")); }
            if (getActivity() instanceof UHFMainActivity) ((UHFMainActivity) getActivity()).setActivePOSFragment(null);
        }
    }

    public void onRingScanned(String epc) {
        if (scannedEpcs.contains(epc)) { playAlertSound(2); showToast(isArabic ? "مضافة مسبقاً: " + epc : "Already added: " + epc); return; }
        processRingEpc(epc, "rfid");
    }

    public void onBarcodeScanned(String data) {
        if (scannedEpcs.contains(data)) { playAlertSound(2); showToast(isArabic ? "مضافة مسبقاً" : "Already added"); return; }
        processRingEpc(data, "barcode");
    }

    private void processRingEpc(final String code, final String source) {
        playAlertSound(1);
        new Thread(() -> {
            try {
                Context context = getContext(); if (context == null) return;
                MiraApiClient client = MiraApiClient.getInstance(context);
                JSONObject extraParams = new JSONObject();
                extraParams.put("source", source);
                extraParams.put("branch_id", branchesManager.getActiveBranchId());
                if (liveScaleWeight > 0) extraParams.put("scale_weight", liveScaleWeight);

                MiraApiClient.ApiResponse response = client.authorize(code, "pos_sale", extraParams);
                if (response != null && response.isSuccess && response.data != null && response.data.optJSONObject("item") != null) {
                    parseItemResponse(code, response.data);
                } else {
                    handleItemNotFound(code);
                }
            } catch (Exception e) { Log.e(TAG, "Error: " + e.getMessage()); handleItemNotFound(code); }
        }).start();
    }

    private void parseItemResponse(String code, JSONObject data) {
        try {
            JSONObject item = data.optJSONObject("item");
            JSONObject decision = data.optJSONObject("decision");
            JSONObject barcode = item != null ? item.optJSONObject("barcode") : null;
            String vaultStatus = item != null ? item.optString("status", "").toLowerCase() : "";

            boolean isAvailable = !vaultStatus.equals("sold") && !vaultStatus.equals("reserved");
            String title = item != null ? item.optString("title", isArabic ? "قطعة" : "Item") : (isArabic ? "قطعة" : "Item");
            String karat = item != null ? item.optString("karat", "21K") : "21K";
            double weight = item != null ? item.optDouble("weight", item.optDouble("gross_weight", 0)) : 0;
            double price = item != null ? item.optDouble("dynamic_retail_price", item.optDouble("retail_price", item.optDouble("price", 0))) : 0;
            if (price <= 0 && weight > 0) price = weight * GOLD_RATES.getOrDefault(karat.toUpperCase(), 65.0);

            CartItem cartItem = new CartItem(code, title, karat, weight, liveScaleWeight, price, isAvailable, vaultStatus.toUpperCase());
            cartItem.serialNo = item != null ? item.optString("serial", code) : code;
            cartItem.gtin = (barcode != null) ? barcode.optString("gtin13", "") : (item != null ? item.optString("gtin", "") : "");
            cartItem.location = item != null ? item.optString("location", "") : "";
            cartItem.itemId = item != null ? item.optInt("id", 0) : 0;

            safeRun(() -> {
                if (!isAvailable) { showToast(isArabic ? "❌ غير متاحة: " + vaultStatus : "❌ Unavailable: " + vaultStatus); return; }
                scannedEpcs.add(code);
                cartList.add(0, cartItem);
                if (cartAdapter != null) cartAdapter.notifyItemInserted(0);
                if (rvCartItems != null) rvCartItems.scrollToPosition(0);
                updateCartSummary();
            });
        } catch (Exception e) { Log.e(TAG, "Parse: " + e.getMessage()); handleItemNotFound(code); }
    }

    private void handleItemNotFound(String code) {
        safeRun(() -> showToast(isArabic ? "❌ غير موجود: " + code : "❌ Not found: " + code));
    }

    private void updateCartSummary() {
        int count = cartList.size();
        double sum = 0; boolean bad = false;
        for (CartItem i : cartList) { sum += i.price; if (!i.isAvailable) bad = true; }
        final int fc = count; final double fs = sum; final boolean fb = bad;

        safeRun(() -> {
            if (tvItemCount != null) tvItemCount.setText(String.valueOf(fc));
            if (tvTotalAmount != null) tvTotalAmount.setText(String.format(Locale.US, "%,.0f YER", fs));
            if (tvCartStatus != null) {
                if (fc == 0) tvCartStatus.setText(isArabic ? "جاهز لمسح القطع..." : "Ready to scan...");
                else if (fb) tvCartStatus.setText(isArabic ? "⚠️ قطعة غير متاحة!" : "⚠️ Unavailable item!");
                else tvCartStatus.setText(isArabic ? "✓ جاهز للبيع" : "✓ Ready for sale");
                tvCartStatus.setTextColor(fc == 0 ? Color.parseColor("#94A3B8") : fb ? Color.parseColor("#EF4444") : Color.parseColor("#4ADE80"));
            }
            if (btnCheckout != null) { btnCheckout.setEnabled(fc > 0 && !fb); btnCheckout.setAlpha(fc > 0 && !fb ? 1f : 0.5f); }
        });
    }

    private void removeItemFromCart(int pos) {
        if (pos >= 0 && pos < cartList.size()) { scannedEpcs.remove(cartList.get(pos).epc); cartList.remove(pos); if (cartAdapter != null) cartAdapter.notifyItemRemoved(pos); updateCartSummary(); }
    }

    private void clearCart() { cartList.clear(); scannedEpcs.clear(); if (cartAdapter != null) cartAdapter.notifyDataSetChanged(); updateCartSummary(); }
    private double getCartTotal() { double t = 0; for (CartItem i : cartList) t += i.price; return t; }

    private void processCheckoutAndStockUpdate() {
        if (cartList.isEmpty()) { showToast(isArabic ? "السلة فارغة" : "Cart is empty"); return; }
        if (!isAdded() || getActivity() == null) return;
        UIHelper.hideKeyboard(getActivity());

        try {
            POSCheckoutDialog dialog = POSCheckoutDialog.newInstance(getCartTotal(), cartList.size());
            dialog.setCheckoutListener(this::executeCheckout);
            FragmentManager fm = getChildFragmentManager();
            if (!fm.isStateSaved()) dialog.show(fm, "POSCheckoutDialog");
        } catch (Exception e) { showToast(isArabic ? "خطأ" : "Error: " + e.getMessage()); }
    }

    // داخل MiraPosFragment.java - تعديل دالة executeCheckout

private void executeCheckout(POSCheckoutDialog.POSCheckoutData data) {
    if (!isAdded()) return;
    safeRun(() -> { 
        if (btnCheckout != null) { btnCheckout.setEnabled(false); btnCheckout.setAlpha(0.5f); } 
        if (tvCartStatus != null) tvCartStatus.setText(isArabic ? "جاري الترحيل المحاسبي والبيع..." : "Processing & Journaling..."); 
    });

    new Thread(() -> {
        try {
            Context ctx = getContext(); if (ctx == null) return;
            MiraApiClient client = MiraApiClient.getInstance(ctx);
            JSONObject payload = new JSONObject();
            payload.put("action", "COMPLETE_POS_SALE");
            payload.put("gate_id", client.getGateId());
            payload.put("branch_id", branchesManager.getActiveBranchId());

            // بيانات العميل
            JSONObject customer = new JSONObject();
            customer.put("name", data.customerName); 
            customer.put("phone", data.customerPhone); 
            customer.put("payment_method", data.paymentMethod);
            payload.put("customer", customer);

            // أصناف الفاتورة
            JSONArray itemsArr = new JSONArray();
            for (CartItem item : cartList) {
                JSONObject obj = new JSONObject();
                obj.put("epc", item.epc); 
                obj.put("serial_no", item.serialNo); 
                obj.put("title", item.name);
                obj.put("karat", item.karat); 
                obj.put("weight", item.weight); 
                obj.put("sale_price", item.price); 
                obj.put("new_status", "SOLD");
                itemsArr.put(obj);
            }
            payload.put("items", itemsArr);

            // التفاصيل المالية والمحاسبية
            JSONObject payment = new JSONObject();
            payment.put("subtotal", getCartTotal()); 
            payment.put("discount_percent", data.discount); 
            payment.put("final_amount", data.finalAmount);
            
            // 🟢 ربط الحسابات المحاسبية التلقائية حسب طريقة الدفع
            String targetAccountCode;
            switch (data.paymentMethod.toUpperCase()) {
                case "CARD":
                case "NET":
                    targetAccountCode = "110201"; // حـ/ البنك - نقاط البيع
                    break;
                case "CREDIT":
                    targetAccountCode = "110301"; // حـ/ الذمم المدنية - العملاء
                    break;
                case "CASH":
                default:
                    targetAccountCode = "110101"; // حـ/ الصندوق الرئيسي/الفرع
                    break;
            }
            payment.put("account_code", targetAccountCode);
            payload.put("payment", payment);

            MiraApiClient.ApiResponse response = client.checkout(payload);

            safeRun(() -> {
                if (response != null && response.isSuccess) {
                    playAlertSound(1);
                    String json = response.data != null ? response.data.toString() : "{}";
                    clearCart();
                    if (getActivity() instanceof UHFMainActivity) ((UHFMainActivity) getActivity()).openReceiptFragment(json);
                    else showToast(isArabic ? "✅ تم البيع والترحيل المحاسبي" : "✅ Sale & Accounting posted");
                } else {
                    if (tvCartStatus != null) { tvCartStatus.setText("❌ " + (response != null ? response.code : "")); tvCartStatus.setTextColor(Color.RED); }
                    if (btnCheckout != null) { btnCheckout.setEnabled(true); btnCheckout.setAlpha(1f); }
                    showToast(isArabic ? "❌ فشل البيع" : "❌ Failed");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Checkout Error: " + e.getMessage());
            safeRun(() -> { 
                saveFullSaleLocally(data);
                clearCart(); 
                if (btnCheckout != null) { btnCheckout.setEnabled(true); btnCheckout.setAlpha(1f); } 
                showToast(isArabic ? "💾 تم الحفظ محلياً للترحيل لاحقاً" : "💾 Saved locally for later posting"); 
            });
        }
    }).start();
}


    // ==================== OFFLINE SUPPORT ====================

/**
 * حفظ الطلب بالكامل محلياً عند فشل الاتصال
 */
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
        sale.put("created_at", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()));
        
        // حفظ الأصناف
        JSONArray itemsArr = new JSONArray();
        for (CartItem item : cartList) {
            JSONObject obj = new JSONObject();
            obj.put("epc", item.epc);
            obj.put("serial_no", item.serialNo);
            obj.put("title", item.name);
            obj.put("karat", item.karat);
            obj.put("weight", item.weight);
            obj.put("price", item.price);
            itemsArr.put(obj);
        }
        sale.put("items", itemsArr);
        
        arr.put(sale);
        p.edit().putString("pending", arr.toString()).apply();
        
        Log.i(TAG, "💾 Sale saved locally: " + sale.optString("id"));
    } catch (Exception e) {
        Log.e(TAG, "Save local sale error: " + e.getMessage());
    }
}

/**
 * عرض عدد الطلبات المعلقة
 */
private void checkPendingSales() {
    if (getContext() == null) return;
    try {
        JSONArray arr = new JSONArray(getContext().getSharedPreferences(PENDING_SALES_PREFS, Context.MODE_PRIVATE)
            .getString("pending", "[]"));
        if (arr.length() > 0) {
            safeRun(() -> {
                if (tvCartStatus != null) {
                    tvCartStatus.setText("⚠️ " + arr.length() + " " + (isArabic ? "عملية معلقة" : "pending sales"));
                    tvCartStatus.setTextColor(Color.parseColor("#F59E0B"));
                }
            });
        }
    } catch (Exception ignored) {}
}

/**
 * محاولة إرسال الطلبات المعلقة عند الاتصال
 */
private void syncPendingSales() {
    if (getContext() == null) return;
    new Thread(() -> {
        try {
            SharedPreferences p = getContext().getSharedPreferences(PENDING_SALES_PREFS, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(p.getString("pending", "[]"));
            
            if (arr.length() == 0) return;
            
            Log.i(TAG, "🔄 Syncing " + arr.length() + " pending sales...");
            
            MiraApiClient client = MiraApiClient.getInstance(getContext());
            JSONArray remainingArr = new JSONArray();
            int synced = 0;
            
            for (int i = 0; i < arr.length(); i++) {
                try {
                    JSONObject sale = arr.getJSONObject(i);
                    JSONObject payload = new JSONObject();
                    payload.put("action", "COMPLETE_POS_SALE");
                    payload.put("gate_id", client.getGateId());
                    payload.put("branch_id", branchesManager.getActiveBranchId());
                    
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
                    
                    if (response != null && response.isSuccess) {
                        synced++;
                        Log.i(TAG, "✅ Synced sale: " + sale.optString("id"));
                    } else {
                        remainingArr.put(sale);
                    }
                } catch (Exception e) {
                    remainingArr.put(arr.getJSONObject(i));
                }
            }
            
            p.edit().putString("pending", remainingArr.toString()).apply();
            
            final int s = synced;
            final int r = remainingArr.length();
            safeRun(() -> {
                if (s > 0) {
                    showToast(isArabic ? "✅ تمت مزامنة " + s + " عملية" : "✅ Synced " + s + " sales");
                }
                if (r > 0) {
                    if (tvCartStatus != null) {
                        tvCartStatus.setText("⚠️ " + r + " " + (isArabic ? "معلقة" : "pending"));
                        tvCartStatus.setTextColor(Color.parseColor("#F59E0B"));
                    }
                } else {
                    if (tvCartStatus != null) {
                        tvCartStatus.setText(isArabic ? "جاهز لمسح القطع..." : "Ready to scan...");
                        tvCartStatus.setTextColor(Color.parseColor("#94A3B8"));
                    }
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "Sync pending error: " + e.getMessage());
        }
    }).start();
}

    private void checkPendingSales() {
        if (getContext() == null) return;
        try {
            JSONArray arr = new JSONArray(getContext().getSharedPreferences(PENDING_SALES_PREFS, Context.MODE_PRIVATE).getString("pending", "[]"));
            if (arr.length() > 0) safeRun(() -> { if (tvCartStatus != null) { tvCartStatus.setText("⚠️ " + arr.length() + " " + (isArabic ? "معلقة" : "pending")); tvCartStatus.setTextColor(Color.parseColor("#F59E0B")); } });
        } catch (Exception ignored) {}
    }

    private void playAlertSound(int id) { if (getActivity() instanceof UHFMainActivity) ((UHFMainActivity) getActivity()).playSound(id); }
    private void showToast(String m) { if (getContext() != null && isAdded()) Toast.makeText(getContext(), m, Toast.LENGTH_SHORT).show(); }
    @Override public void onDestroy() { super.onDestroy(); if (scaleConnector != null) scaleConnector.disconnect(); }

    // ==================== Adapter ====================

    private static class CartAdapter extends RecyclerView.Adapter<CartAdapter.ViewHolder> {
        interface OnRemoveListener { void onRemove(int position); }
        private final List<CartItem> items;
        private final OnRemoveListener removeListener;
        private final boolean isArabic;

        CartAdapter(List<CartItem> items, OnRemoveListener listener, boolean isArabic) {
            this.items = items; this.removeListener = listener; this.isArabic = isArabic;
        }

        @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_pos_cart_row, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
            CartItem item = items.get(pos);
            if (h.tvTitle != null) h.tvTitle.setText(item.name);
            if (h.tvEpc != null) h.tvEpc.setText("EPC: " + item.epc);
            if (h.tvKaratWeight != null) h.tvKaratWeight.setText(String.format(Locale.US, "%s | %.2fg", item.karat, item.scaleWeight > 0 ? item.scaleWeight : item.weight));
            if (h.tvPrice != null) h.tvPrice.setText(String.format(Locale.US, "%,.0f YER", item.price));
            if (h.tvStatus != null) {
                if (item.isAvailable) { h.tvStatus.setText(isArabic ? "✅ متاحة" : "✅ Available"); h.tvStatus.setTextColor(Color.parseColor("#4ADE80")); }
                else { h.tvStatus.setText(isArabic ? "❌ غير متاحة" : "❌ Unavailable"); h.tvStatus.setTextColor(Color.parseColor("#EF4444")); }
            }
            if (h.btnRemove != null) h.btnRemove.setOnClickListener(v -> { int p = h.getAdapterPosition(); if (p != RecyclerView.NO_POSITION) removeListener.onRemove(p); });
        }

        @Override public int getItemCount() { return items.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle, tvEpc, tvKaratWeight, tvPrice, tvStatus; View btnRemove;
            ViewHolder(View v) {
                super(v);
                tvTitle = v.findViewById(R.id.tvRowItemTitle); tvEpc = v.findViewById(R.id.tvRowItemEpc);
                tvKaratWeight = v.findViewById(R.id.tvRowItemKaratWeight); tvPrice = v.findViewById(R.id.tvRowItemPrice);
                tvStatus = v.findViewById(R.id.tvRowItemStatus); btnRemove = v.findViewById(R.id.btnRowRemove);
            }
        }
    }
    }
