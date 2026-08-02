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
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.dialog.POSCheckoutDialog;
import com.example.uhf.engine.SmartScaleConnector;
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
    private static final String PREFS_NAME = "mira_settings";
    private static final String PENDING_SALES_PREFS = "pos_pending_sales";

    // ⭐ أسعار الذهب الافتراضية
    private static final Map<String, Double> GOLD_RATES = new HashMap<>();
    static {
        GOLD_RATES.put("24K", 75.0);
        GOLD_RATES.put("22K", 68.75);
        GOLD_RATES.put("21K", 65.62);
        GOLD_RATES.put("18K", 56.25);
    }

    // ===================== UI Elements =====================
    private TextView tvScaleStatus, tvItemCount, tvTotalAmount, tvCartStatus;
    private EditText etManualEpc;
    private Button btnAddManual, btnCheckout, btnClearCart;
    private Button btnOpenScanner, btnPosRFIDMode;
    private RecyclerView rvCartItems;

    private boolean isRFIDModeActive = false;

    // ===================== Cart Data =====================
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

    // ===================== Lifecycle =====================

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_pos, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindViews(view);
        setupRecyclerView();
        setupListeners();
        initSmartScale();
        checkPendingSales();
        updateCartSummary();
    }

    @Override public void onResume() {
        super.onResume();
        if (getActivity() instanceof UHFMainActivity)
            ((UHFMainActivity) getActivity()).setActivePOSFragment(this);
    }

    @Override public void onPause() {
        super.onPause();
        if (getActivity() instanceof UHFMainActivity)
            ((UHFMainActivity) getActivity()).setActivePOSFragment(null);
    }

    // ===================== Initialization =====================

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
    }

    private void setupRecyclerView() {
        rvCartItems.setLayoutManager(new LinearLayoutManager(getContext()));
        cartAdapter = new CartAdapter(cartList, this::removeItemFromCart);
        rvCartItems.setAdapter(cartAdapter);
    }

    private void setupListeners() {
        btnAddManual.setOnClickListener(v -> {
            UIHelper.hideKeyboard(getActivity());
            String epc = etManualEpc.getText().toString().trim();
            if (!epc.isEmpty()) { processRingEpc(epc, "manual"); etManualEpc.setText(""); }
            else showToast("Please enter an EPC, Serial, or GTIN");
        });
        btnClearCart.setOnClickListener(v -> { UIHelper.hideKeyboard(getActivity()); clearCart(); });
        btnCheckout.setOnClickListener(v -> { UIHelper.hideKeyboard(getActivity()); processCheckoutAndStockUpdate(); });
        if (btnOpenScanner != null) btnOpenScanner.setOnClickListener(v -> { UIHelper.hideKeyboard(getActivity()); openBarcodeScanner(); });
        if (btnPosRFIDMode != null) btnPosRFIDMode.setOnClickListener(v -> { UIHelper.hideKeyboard(getActivity()); toggleRFIDMode(); });
    }

    // ===================== Smart Scale =====================

    private void initSmartScale() {
        scaleConnector = new SmartScaleConnector();
        scaleConnector.setListener(new SmartScaleConnector.ScaleListener() {
            @Override public void onWeightReceived(double w) {
                liveScaleWeight = w;
                if (getActivity() != null) getActivity().runOnUiThread(() -> {
                    if (tvScaleStatus != null) {
                        tvScaleStatus.setText(String.format(Locale.US, "⚖️ Scale: %.2fg", w));
                        tvScaleStatus.setTextColor(Color.parseColor("#4ADE80"));
                    }
                });
            }
            @Override public void onScaleConnected(String n) {
                if (getActivity() != null) getActivity().runOnUiThread(() -> {
                    if (tvScaleStatus != null) { tvScaleStatus.setText("⚖️ Scale Ready (" + n + ")"); tvScaleStatus.setTextColor(Color.parseColor("#38BDF8")); }
                });
            }
            @Override public void onScaleDisconnected() { liveScaleWeight = 0.0;
                if (getActivity() != null) getActivity().runOnUiThread(() -> {
                    if (tvScaleStatus != null) { tvScaleStatus.setText("⚖️ Scale Offline"); tvScaleStatus.setTextColor(Color.parseColor("#94A3B8")); }
                });
            }
            @Override public void onScaleError(String e) { Log.e(TAG, "Scale Error: " + e); }
        });
    }

    // ===================== Barcode Scanner =====================

    private void openBarcodeScanner() {
        MiraBarcodeScannerFragment sf = new MiraBarcodeScannerFragment();
        sf.setOnBarcodeScannedListener((data, format) -> { onBarcodeScanned(data); if (getParentFragmentManager().getBackStackEntryCount() > 0) getParentFragmentManager().popBackStack(); });
        if (getView() != null && getView().getParent() != null) {
            int cid = ((View) getView().getParent()).getId();
            getParentFragmentManager().beginTransaction().replace(cid, sf).addToBackStack("barcode_scanner").commit();
        } else {
            getParentFragmentManager().beginTransaction().replace(android.R.id.content, sf).addToBackStack("barcode_scanner").commit();
        }
    }

    // ===================== RFID Direct Mode =====================

    private void toggleRFIDMode() {
        isRFIDModeActive = !isRFIDModeActive;
        if (isRFIDModeActive) {
            btnPosRFIDMode.setText("📡 RFID نشط"); btnPosRFIDMode.setTextColor(Color.parseColor("#FFFFFF"));
            btnPosRFIDMode.setBackgroundResource(R.drawable.bg_rfid_btn_active);
            tvCartStatus.setText("📡 جاهز لاستقبال مسح RFID المباشر..."); tvCartStatus.setTextColor(Color.parseColor("#4ADE80"));
            if (getActivity() instanceof UHFMainActivity) ((UHFMainActivity) getActivity()).setActivePOSFragment(this);
        } else {
            btnPosRFIDMode.setText("📡 مسح RFID"); btnPosRFIDMode.setTextColor(Color.parseColor("#FFFFFF"));
            btnPosRFIDMode.setBackgroundResource(R.drawable.bg_rfid_btn);
            tvCartStatus.setText("جاهز لمسح القطع والخواتم..."); tvCartStatus.setTextColor(Color.parseColor("#94A3B8"));
            if (getActivity() instanceof UHFMainActivity) ((UHFMainActivity) getActivity()).setActivePOSFragment(null);
        }
    }

    // ===================== Tag Processing =====================

    public void onRingScanned(String epc) {
        if (scannedEpcs.contains(epc)) { playAlertSound(2); showToast("القطعة مضافة مسبقاً: " + epc); return; }
        processRingEpc(epc, "rfid");
    }

    public void onBarcodeScanned(String data) {
        if (scannedEpcs.contains(data)) { playAlertSound(2); showToast("القطعة مضافة مسبقاً"); return; }
        processRingEpc(data, "barcode");
    }

    private void processRingEpc(final String code, final String source) {
        playAlertSound(1);
        new Thread(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());
                JSONObject extraParams = new JSONObject();
                extraParams.put("source", source);
                if (liveScaleWeight > 0) extraParams.put("scale_weight", liveScaleWeight);

                MiraApiClient.ApiResponse response = client.authorize(code, "pos_sale", extraParams);

                if (response.isSuccess && response.data != null) {
                    JSONObject item = response.data.optJSONObject("item");
                    if (item != null) {
                        parseItemResponse(code, response.data);
                        return;
                    }
                }
                // ⭐ قطعة غير موجودة
                handleItemNotFound(code);

            } catch (Exception e) {
                Log.e(TAG, "Error: " + e.getMessage());
                handleItemNotFound(code);
            }
        }).start();
    }

    private void parseItemResponse(String code, JSONObject data) {
        try {
            JSONObject item = data.optJSONObject("item");
            JSONObject decision = data.optJSONObject("decision");
            JSONObject barcode = item != null ? item.optJSONObject("barcode") : null;

            String vaultStatus = item != null ? item.optString("status", "").toLowerCase() : "";

            // ⭐ تحديد التوفر الصحيح
            boolean isAvailable;
            if (vaultStatus.equals("sold")) isAvailable = false;
            else if (vaultStatus.equals("reserved")) isAvailable = false;
            else if (vaultStatus.equals("in_stock") || vaultStatus.equals("available") || vaultStatus.equals("transferred") || vaultStatus.isEmpty()) isAvailable = true;
            else isAvailable = (decision != null && decision.optBoolean("allowed", false));

            String title = item.optString("title", "قطعة");
            String karat = item.optString("karat", "21K");
            double weight = item.optDouble("weight", item.optDouble("gross_weight", 0));

            // ⭐ السعر الحقيقي من API
            double price = item.optDouble("dynamic_retail_price", 0);
            if (price <= 0) price = item.optDouble("retail_price", 0);
            if (price <= 0) price = item.optDouble("price", 0);
            if (price <= 0 && weight > 0) {
                double rate = GOLD_RATES.getOrDefault(karat.toUpperCase(), 65.0);
                price = weight * rate;
            }

            String serialNo = item.optString("serial", item.optString("serial_number", code));
            String gtin = (barcode != null) ? barcode.optString("gtin13", "") : item.optString("gtin", "");
            String location = item.optString("location", "");
            int itemId = item.optInt("id", 0);

            CartItem cartItem = new CartItem(code, title, karat, weight, liveScaleWeight, price, isAvailable, vaultStatus.toUpperCase());
            cartItem.serialNo = serialNo; cartItem.gtin = gtin; cartItem.location = location; cartItem.itemId = itemId;

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    if (!isAvailable) {
                        showToast("❌ لا يمكن إضافة هذه القطعة: " + vaultStatus.toUpperCase());
                        return;
                    }
                    scannedEpcs.add(code);
                    cartList.add(0, cartItem);
                    cartAdapter.notifyItemInserted(0);
                    rvCartItems.scrollToPosition(0);
                    updateCartSummary();
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
            handleItemNotFound(code);
        }
    }

    // ⭐ قطعة غير موجودة - لا تضفها للسلة
    private void handleItemNotFound(String code) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (isAdded()) showToast("❌ لم يتم العثور على القطعة: " + code);
            });
        }
    }

    // ===================== Cart Management =====================

    private void updateCartSummary() {
        int totalItems = cartList.size();
        double totalSum = 0.0;
        boolean hasUnavailable = false;
        for (CartItem item : cartList) { totalSum += item.price; if (!item.isAvailable) hasUnavailable = true; }
        final int count = totalItems; final double sum = totalSum; final boolean unavailable = hasUnavailable;
        if (getActivity() != null) getActivity().runOnUiThread(() -> {
            if (!isAdded()) return;
            tvItemCount.setText(String.valueOf(count));
            tvTotalAmount.setText(String.format(Locale.US, "$%,.2f", sum));
            if (count == 0) { tvCartStatus.setText("جاهز لمسح القطع والخواتم..."); tvCartStatus.setTextColor(Color.parseColor("#94A3B8")); btnCheckout.setEnabled(false); btnCheckout.setAlpha(0.5f); }
            else if (unavailable) { tvCartStatus.setText("⚠️ تحذير: توجد قطعة غير متاحة!"); tvCartStatus.setTextColor(Color.parseColor("#EF4444")); btnCheckout.setEnabled(false); btnCheckout.setAlpha(0.5f); }
            else { tvCartStatus.setText("✓ جميع القطع متاحة"); tvCartStatus.setTextColor(Color.parseColor("#4ADE80")); btnCheckout.setEnabled(true); btnCheckout.setAlpha(1.0f); }
        });
    }

    private void removeItemFromCart(int pos) {
        if (pos >= 0 && pos < cartList.size()) { CartItem item = cartList.get(pos); scannedEpcs.remove(item.epc); cartList.remove(pos); cartAdapter.notifyItemRemoved(pos); updateCartSummary(); }
    }

    private void clearCart() { cartList.clear(); scannedEpcs.clear(); cartAdapter.notifyDataSetChanged(); updateCartSummary(); }

    private double getCartTotal() { double t = 0; for (CartItem i : cartList) t += i.price; return t; }

    // ===================== Checkout =====================

    private void processCheckoutAndStockUpdate() {
        if (cartList.isEmpty()) { showToast("السلة فارغة!"); return; }
        if (!isAdded() || getActivity() == null) return;
        UIHelper.hideKeyboard(getActivity());
        try {
            POSCheckoutDialog dialog = POSCheckoutDialog.newInstance(getCartTotal(), cartList.size());
            dialog.setCheckoutListener(this::executeCheckout);
            FragmentManager fm = getActivity().getSupportFragmentManager();
            if (!fm.isStateSaved()) dialog.show(fm, "POSCheckoutDialog");
        } catch (Exception e) { showToast("خطأ: " + e.getMessage()); }
    }

    private void executeCheckout(POSCheckoutDialog.POSCheckoutData data) {
        btnCheckout.setEnabled(false); btnCheckout.setAlpha(0.5f);
        tvCartStatus.setText("جاري إتمام البيع..."); tvCartStatus.setTextColor(Color.parseColor("#F59E0B"));
        new Thread(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());
                JSONObject payload = new JSONObject();
                payload.put("action", "COMPLETE_POS_SALE"); payload.put("gate_id", client.getGateId());
                JSONObject customer = new JSONObject();
                customer.put("name", data.customerName); customer.put("phone", data.customerPhone); customer.put("payment_method", data.paymentMethod);
                payload.put("customer", customer);
                JSONArray itemsArr = new JSONArray();
                for (CartItem item : cartList) {
                    JSONObject obj = new JSONObject();
                    obj.put("epc", item.epc); obj.put("serial_no", item.serialNo); obj.put("title", item.name);
                    obj.put("karat", item.karat); obj.put("weight", item.weight); obj.put("sale_price", item.price); obj.put("new_status", "SOLD");
                    itemsArr.put(obj);
                }
                payload.put("items", itemsArr);
                JSONObject payment = new JSONObject();
                payment.put("subtotal", getCartTotal()); payment.put("discount_percent", data.discount); payment.put("final_amount", data.finalAmount);
                payload.put("payment", payment);

                MiraApiClient.ApiResponse response = client.checkout(payload);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;

                        if (response.isSuccess) {
                            playAlertSound(1);
                            String jsonResponse = response.data != null ? response.data.toString() : "{}";
                            clearCart();
                            
                            // 🟢 استدعاء آمن ومحمي لعرض الإيصال بدون Crash
                            showReceiptSafe(jsonResponse);
                        } else {
                            tvCartStatus.setText("❌ فشل (" + response.code + ")"); tvCartStatus.setTextColor(Color.parseColor("#EF4444"));
                            btnCheckout.setEnabled(true); btnCheckout.setAlpha(1.0f);
                            showToast("❌ فشل البيع: " + response.code);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Checkout error: " + e.getMessage());
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        saveSaleLocally(data); clearCart(); btnCheckout.setEnabled(true); btnCheckout.setAlpha(1.0f);
                        showToast("💾 تم الحفظ محلياً");
                    });
                }
            }
        }).start();
    }

    // ⭐ عرض الإيصال بأمان تام ومنع انهيار التطبيق
    private void showReceiptSafe(String responseJson) {
        View rootView = getView();
        if (rootView == null || !isAdded()) {
            showToast("✅ تم البيع بنجاح!");
            return;
        }

        // 🛡️ جدولة العملية على الـ Main Thread لضمان إغلاق الحوارات واستقرار الـ FragmentManager
        rootView.post(() -> {
            try {
                if (!isAdded() || isDetached() || getActivity() == null) return;

                FragmentManager fm = getParentFragmentManager();
                if (fm != null && !fm.isStateSaved() && !fm.isDestroyed()) {
                    MiraReceiptFragment receipt = MiraReceiptFragment.newInstance(responseJson);
                    
                    int containerId = (getView() != null && getView().getParent() != null) 
                            ? ((View) getView().getParent()).getId() 
                            : android.R.id.content;

                    fm.beginTransaction()
                      .replace(containerId, receipt)
                      .addToBackStack("mira_receipt")
                      .commitAllowingStateLoss(); // 🛡️ منع خطأ حفظ حالة الـ Fragment (IllegalStateException)
                } else {
                    showToast("✅ تم البيع بنجاح!");
                }
            } catch (Exception e) {
                Log.e(TAG, "Receipt error prevented crash: " + e.getMessage());
                showToast("✅ تم البيع بنجاح!");
            }
        });
    }

    private void saveSaleLocally(POSCheckoutDialog.POSCheckoutData data) {
        if (getContext() == null) return;
        try {
            SharedPreferences prefs = getContext().getSharedPreferences(PENDING_SALES_PREFS, Context.MODE_PRIVATE);
            JSONArray pending = new JSONArray(prefs.getString("pending", "[]"));
            JSONObject sale = new JSONObject();
            sale.put("id", System.currentTimeMillis()); sale.put("customer_name", data.customerName);
            sale.put("final_amount", data.finalAmount); sale.put("item_count", cartList.size());
            pending.put(sale);
            prefs.edit().putString("pending", pending.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void checkPendingSales() {
        if (getContext() == null) return;
        try {
            JSONArray pending = new JSONArray(getContext().getSharedPreferences(PENDING_SALES_PREFS, Context.MODE_PRIVATE).getString("pending", "[]"));
            if (pending.length() > 0 && getActivity() != null) getActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                tvCartStatus.setText("⚠️ " + pending.length() + " عملية معلقة"); tvCartStatus.setTextColor(Color.parseColor("#F59E0B"));
            });
        } catch (Exception ignored) {}
    }

    private void playAlertSound(int id) { if (getActivity() instanceof UHFMainActivity) ((UHFMainActivity) getActivity()).playSound(id); }
    private void showToast(String m) { if (getContext() != null) Toast.makeText(getContext(), m, Toast.LENGTH_SHORT).show(); }

    @Override public void onDestroy() { super.onDestroy(); if (scaleConnector != null) scaleConnector.disconnect(); }

    // ===================== Adapter =====================

    private static class CartAdapter extends RecyclerView.Adapter<CartAdapter.ViewHolder> {
        interface OnRemoveListener { void onRemove(int position); }
        private final List<CartItem> items; private final OnRemoveListener removeListener;
        CartAdapter(List<CartItem> items, OnRemoveListener listener) { this.items = items; this.removeListener = listener; }

        @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_pos_cart_row, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            CartItem item = items.get(position);
            holder.tvTitle.setText(item.name);
            holder.tvEpc.setText("EPC: " + item.epc);
            holder.tvKaratWeight.setText(String.format(Locale.US, "%s | %.2fg", item.karat, item.scaleWeight > 0 ? item.scaleWeight : item.weight));
            holder.tvPrice.setText(String.format(Locale.US, "$%,.2f", item.price));
            if (item.isAvailable) { holder.tvStatus.setText("✅ متاحة للبيع"); holder.tvStatus.setTextColor(Color.parseColor("#4ADE80")); }
            else {
                String st; int c;
                switch (item.status.toUpperCase()) {
                    case "SOLD": st = "❌ مباعة مسبقاً"; c = Color.parseColor("#EF4444"); break;
                    case "RESERVED": st = "🔒 محجوزة"; c = Color.parseColor("#F59E0B"); break;
                    default: st = "⚠️ " + item.status; c = Color.parseColor("#EF4444");
                }
                holder.tvStatus.setText(st); holder.tvStatus.setTextColor(c);
            }
            holder.btnRemove.setOnClickListener(v -> { if (holder.getAdapterPosition() != RecyclerView.NO_POSITION) removeListener.onRemove(holder.getAdapterPosition()); });
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
