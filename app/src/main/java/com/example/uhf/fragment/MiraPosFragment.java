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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 🛒 MIRA POS Mode Fragment
 * وضع البيع الخاص بـ MIRA Bridge:
 * - 📷 مسح GS1 DATAMATRIX بالكاميرا
 * - 📡 قراءة Ring EPC عبر RFID مباشر
 * - ⌨️ الإدخال اليدوي للكود
 * - ⚖️ الربط مع قراءات الميزان اللحظية
 * - 📋 نموذج تفاصيل العميل قبل إتمام البيع
 * - 🧾 إيصال بيع احترافي بعد البيع
 * - 🔄 تحديث حالة المخزون والهوية الرقمية
 * - 💾 حفظ محلي عند فشل الاتصال
 */
public class MiraPosFragment extends Fragment {

    private static final String TAG = "MiraPosFragment";
    private static final String PREFS_NAME = "mira_settings";
    private static final String PENDING_SALES_PREFS = "pos_pending_sales";

    // ===================== UI Elements =====================
    private TextView tvScaleStatus, tvItemCount, tvTotalAmount, tvCartStatus;
    private EditText etManualEpc;
    private Button btnAddManual, btnCheckout, btnClearCart;
    private Button btnOpenScanner, btnPosRFIDMode;
    private RecyclerView rvCartItems;

    // ===================== RFID Mode =====================
    private boolean isRFIDModeActive = false;

    // ===================== Cart Data =====================
    public static class CartItem {
        public String epc;
        public String name;
        public String karat;
        public double weight;
        public double scaleWeight;
        public double price;
        public boolean isAvailable;
        public String status;
        public String serialNo;
        public String gtin;
        public String location;
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

    // ===================== Smart Scale =====================
    private SmartScaleConnector scaleConnector;
    private double liveScaleWeight = 0.0;

    // ===================== Lifecycle =====================

    @Nullable
    @Override
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

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setActivePOSFragment(this);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setActivePOSFragment(null);
        }
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
            if (!epc.isEmpty()) {
                processRingEpc(epc, "manual");
                etManualEpc.setText("");
            } else {
                showToast("Please enter an EPC, Serial, or GTIN");
            }
        });

        btnClearCart.setOnClickListener(v -> {
            UIHelper.hideKeyboard(getActivity());
            clearCart();
        });

        btnCheckout.setOnClickListener(v -> {
            UIHelper.hideKeyboard(getActivity());
            processCheckoutAndStockUpdate();
        });

        if (btnOpenScanner != null) {
            btnOpenScanner.setOnClickListener(v -> {
                UIHelper.hideKeyboard(getActivity());
                openBarcodeScanner();
            });
        }

        if (btnPosRFIDMode != null) {
            btnPosRFIDMode.setOnClickListener(v -> {
                UIHelper.hideKeyboard(getActivity());
                toggleRFIDMode();
            });
        }
    }

    // ===================== Smart Scale =====================

    private void initSmartScale() {
        scaleConnector = new SmartScaleConnector();
        scaleConnector.setListener(new SmartScaleConnector.ScaleListener() {
            @Override
            public void onWeightReceived(double weightGrams) {
                liveScaleWeight = weightGrams;
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (tvScaleStatus != null) {
                            tvScaleStatus.setText(String.format(Locale.US, "⚖️ Scale: %.2fg", weightGrams));
                            tvScaleStatus.setTextColor(Color.parseColor("#4ADE80"));
                        }
                    });
                }
            }

            @Override
            public void onScaleConnected(String deviceName) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (tvScaleStatus != null) {
                            tvScaleStatus.setText("⚖️ Scale Ready (" + deviceName + ")");
                            tvScaleStatus.setTextColor(Color.parseColor("#38BDF8"));
                        }
                    });
                }
            }

            @Override
            public void onScaleDisconnected() {
                liveScaleWeight = 0.0;
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (tvScaleStatus != null) {
                            tvScaleStatus.setText("⚖️ Scale Offline");
                            tvScaleStatus.setTextColor(Color.parseColor("#94A3B8"));
                        }
                    });
                }
            }

            @Override
            public void onScaleError(String error) {
                Log.e(TAG, "Scale Error: " + error);
            }
        });
    }

    // ===================== 📷 Barcode Scanner =====================

    private void openBarcodeScanner() {
        MiraBarcodeScannerFragment scannerFragment = new MiraBarcodeScannerFragment();
        scannerFragment.setOnBarcodeScannedListener((data, format) -> {
            onBarcodeScanned(data);
            if (getParentFragmentManager().getBackStackEntryCount() > 0) {
                getParentFragmentManager().popBackStack();
            }
        });

        if (getView() != null && getView().getParent() != null) {
            int containerId = ((View) getView().getParent()).getId();
            getParentFragmentManager()
                    .beginTransaction()
                    .replace(containerId, scannerFragment)
                    .addToBackStack("barcode_scanner")
                    .commit();
        } else {
            getParentFragmentManager()
                    .beginTransaction()
                    .replace(android.R.id.content, scannerFragment)
                    .addToBackStack("barcode_scanner")
                    .commit();
        }
    }

    // ===================== 📡 RFID Direct Mode =====================

    private void toggleRFIDMode() {
        isRFIDModeActive = !isRFIDModeActive;

        if (isRFIDModeActive) {
            btnPosRFIDMode.setText("📡 RFID نشط");
            btnPosRFIDMode.setTextColor(Color.parseColor("#FFFFFF"));
            btnPosRFIDMode.setBackgroundResource(R.drawable.bg_rfid_btn_active);
            tvCartStatus.setText("📡 جاهز لاستقبال مسح RFID المباشر...");
            tvCartStatus.setTextColor(Color.parseColor("#4ADE80"));
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setActivePOSFragment(this);
            }
        } else {
            btnPosRFIDMode.setText("📡 مسح RFID");
            btnPosRFIDMode.setTextColor(Color.parseColor("#FFFFFF"));
            btnPosRFIDMode.setBackgroundResource(R.drawable.bg_rfid_btn);
            tvCartStatus.setText("جاهز لمسح القطع والخواتم...");
            tvCartStatus.setTextColor(Color.parseColor("#94A3B8"));
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setActivePOSFragment(null);
            }
        }
    }

    // ===================== Tag Processing =====================

    public void onRingScanned(String epc) {
        if (scannedEpcs.contains(epc)) {
            playAlertSound(2);
            showToast("القطعة مضافة مسبقاً للسلة: " + epc);
            return;
        }
        processRingEpc(epc, "rfid");
    }

    public void onBarcodeScanned(String barcodeData) {
        if (scannedEpcs.contains(barcodeData)) {
            playAlertSound(2);
            showToast("القطعة مضافة مسبقاً للسلة");
            return;
        }
        processRingEpc(barcodeData, "barcode");
    }

    private void processRingEpc(final String code, final String source) {
        playAlertSound(1);

        new Thread(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());

                JSONObject extraParams = new JSONObject();
                extraParams.put("source", source);
                if (liveScaleWeight > 0) {
                    extraParams.put("scale_weight", liveScaleWeight);
                }

                MiraApiClient.ApiResponse response = client.authorize(code, "pos_sale", extraParams);

                if (response.isSuccess && response.data != null) {
                    parseItemResponse(code, response.data);
                } else {
                    throw new Exception(response.error != null ? response.error : "Unknown error");
                }

            } catch (Exception e) {
                Log.e(TAG, "Error fetching item: " + e.getMessage());
                handleItemError(code);
            }
        }).start();
    }

    private void parseItemResponse(String code, JSONObject data) {
        try {
            JSONObject item = data.optJSONObject("item");
            JSONObject decision = data.optJSONObject("decision");
            JSONObject barcode = item != null ? item.optJSONObject("barcode") : null;

            String vaultStatus = item != null ? item.optString("status", "").toLowerCase() : "";
            boolean isAvailable;

            if (vaultStatus.equals("sold")) {
                isAvailable = false;
            } else if (vaultStatus.equals("in_stock") || vaultStatus.equals("available") || vaultStatus.isEmpty()) {
                isAvailable = true;
            } else {
                isAvailable = decision != null && decision.optBoolean("allowed", false);
            }

            String title = item != null ? item.optString("title", "خاتم ذهب") :
                    "Item " + code.substring(Math.max(0, code.length() - 6));
            String karat = item != null ? item.optString("karat", "21K") : "21K";
            double weight = item != null ? item.optDouble("weight", 5.0) : 5.0;
            double price = item != null ? item.optDouble("price", 350.0) : 350.0;
            String serialNo = item != null ? item.optString("serial", code) : code;
            String gtin = (barcode != null) ? barcode.optString("gtin13", "") : "";
            String location = item != null ? item.optString("location", "") : "";
            int itemId = item != null ? item.optInt("id", 0) : 0;

            CartItem cartItem = new CartItem(code, title, karat, weight,
                    liveScaleWeight, price, isAvailable, vaultStatus.toUpperCase());
            cartItem.serialNo = serialNo;
            cartItem.gtin = gtin;
            cartItem.location = location;
            cartItem.itemId = itemId;

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
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
            handleItemError(code);
        }
    }

    private void handleItemError(String code) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                CartItem fallbackItem = new CartItem(code,
                        "قطعة (غير متصلة)", "21K", 5.2,
                        liveScaleWeight, 420.0, true, "OFFLINE");
                fallbackItem.serialNo = code;
                scannedEpcs.add(code);
                cartList.add(0, fallbackItem);
                cartAdapter.notifyItemInserted(0);
                updateCartSummary();
            });
        }
    }

    // ===================== Cart Management =====================

    private void updateCartSummary() {
        int totalItems = cartList.size();
        double totalSum = 0.0;
        boolean hasUnavailable = false;

        for (CartItem item : cartList) {
            totalSum += item.price;
            if (!item.isAvailable) hasUnavailable = true;
        }

        final int count = totalItems;
        final double sum = totalSum;
        final boolean unavailable = hasUnavailable;

        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                tvItemCount.setText(String.valueOf(count));
                tvTotalAmount.setText(String.format(Locale.US, "$%,.2f", sum));

                if (count == 0) {
                    tvCartStatus.setText("جاهز لمسح القطع والخواتم...");
                    tvCartStatus.setTextColor(Color.parseColor("#94A3B8"));
                    btnCheckout.setEnabled(false);
                    btnCheckout.setAlpha(0.5f);
                } else if (unavailable) {
                    tvCartStatus.setText("⚠️ تحذير: توجد قطعة غير متاحة في السلة!");
                    tvCartStatus.setTextColor(Color.parseColor("#EF4444"));
                    btnCheckout.setEnabled(false);
                    btnCheckout.setAlpha(0.5f);
                } else {
                    tvCartStatus.setText("✓ جميع القطع متاحة ومتحقق منها بنجاح");
                    tvCartStatus.setTextColor(Color.parseColor("#4ADE80"));
                    btnCheckout.setEnabled(true);
                    btnCheckout.setAlpha(1.0f);
                }
            });
        }
    }

    private void removeItemFromCart(int position) {
        if (position >= 0 && position < cartList.size()) {
            CartItem item = cartList.get(position);
            scannedEpcs.remove(item.epc);
            cartList.remove(position);
            cartAdapter.notifyItemRemoved(position);
            updateCartSummary();
        }
    }

    private void clearCart() {
        cartList.clear();
        scannedEpcs.clear();
        cartAdapter.notifyDataSetChanged();
        updateCartSummary();
    }

    private double getCartTotal() {
        double total = 0;
        for (CartItem item : cartList) {
            total += item.price;
        }
        return total;
    }

    // ===================== Checkout Process =====================

    private void processCheckoutAndStockUpdate() {
        if (cartList == null || cartList.isEmpty()) {
            Toast.makeText(getContext(), "السلة فارغة!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isAdded() || getActivity() == null) return;

        UIHelper.hideKeyboard(getActivity());

        try {
            double totalAmount = getCartTotal();
            POSCheckoutDialog dialog = POSCheckoutDialog.newInstance(totalAmount, cartList.size());
            dialog.setCheckoutListener(checkoutData -> executeCheckout(checkoutData));

            FragmentManager fm = getActivity().getSupportFragmentManager();
            if (!fm.isStateSaved()) {
                dialog.show(fm, "POSCheckoutDialog");
            }
        } catch (Exception e) {
            Log.e("POS_CHECKOUT_ERROR", "حدث خطأ أثناء فتح نافذة البيع: " + e.getMessage(), e);
            Toast.makeText(getContext(), "خطأ في فتح النافذة: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void executeCheckout(POSCheckoutDialog.POSCheckoutData checkoutData) {
        btnCheckout.setEnabled(false);
        btnCheckout.setAlpha(0.5f);
        tvCartStatus.setText("جاري إتمام البيع وتحديث الهوية الرقمية...");
        tvCartStatus.setTextColor(Color.parseColor("#F59E0B"));

        new Thread(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());

                JSONObject payload = new JSONObject();
                payload.put("action", "COMPLETE_POS_SALE");
                payload.put("gate_id", client.getGateId());
                payload.put("timestamp", System.currentTimeMillis() / 1000);

                JSONObject customer = new JSONObject();
                customer.put("name", checkoutData.customerName);
                customer.put("phone", checkoutData.customerPhone);
                customer.put("payment_method", checkoutData.paymentMethod);
                payload.put("customer", customer);

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

                JSONObject payment = new JSONObject();
                payment.put("subtotal", getCartTotal());
                payment.put("discount_percent", checkoutData.discount);
                payment.put("final_amount", checkoutData.finalAmount);
                payload.put("payment", payment);

                MiraApiClient.ApiResponse response = client.checkout(payload);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (response.isSuccess) {
                            playAlertSound(1);
                            // ⭐ عرض إيصال البيع
                            showReceipt(response.data != null ? response.data.toString() : "{}");
                            clearCart();
                        } else {
                            tvCartStatus.setText("❌ فشل إتمام البيع (" + response.code + ")");
                            tvCartStatus.setTextColor(Color.parseColor("#EF4444"));
                            btnCheckout.setEnabled(true);
                            btnCheckout.setAlpha(1.0f);
                        }
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "Checkout exception: " + e.getMessage());
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        saveSaleLocally(checkoutData);
                        clearCart();
                        btnCheckout.setEnabled(true);
                        btnCheckout.setAlpha(1.0f);
                    });
                }
            }
        }).start();
    }

    // ⭐ عرض إيصال البيع
    private void showReceipt(String responseJson) {
        if (getParentFragmentManager() != null) {
            MiraReceiptFragment receiptFragment = MiraReceiptFragment.newInstance(responseJson);
            getParentFragmentManager()
                    .beginTransaction()
                    .replace(android.R.id.content, receiptFragment)
                    .addToBackStack("mira_receipt")
                    .commit();
        }
    }

    private void saveSaleLocally(POSCheckoutDialog.POSCheckoutData checkoutData) {
        if (getContext() != null) {
            try {
                SharedPreferences prefs = getContext().getSharedPreferences(PENDING_SALES_PREFS, Context.MODE_PRIVATE);
                String pendingJson = prefs.getString("pending", "[]");
                JSONArray pending = new JSONArray(pendingJson);

                JSONObject sale = new JSONObject();
                sale.put("id", System.currentTimeMillis());
                sale.put("timestamp", System.currentTimeMillis() / 1000);
                sale.put("customer_name", checkoutData.customerName);
                sale.put("customer_phone", checkoutData.customerPhone);
                sale.put("payment_method", checkoutData.paymentMethod);
                sale.put("discount_percent", checkoutData.discount);
                sale.put("subtotal", getCartTotal());
                sale.put("final_amount", checkoutData.finalAmount);
                sale.put("item_count", cartList.size());

                JSONArray itemsArr = new JSONArray();
                for (CartItem item : cartList) {
                    JSONObject obj = new JSONObject();
                    obj.put("epc", item.epc);
                    obj.put("name", item.name);
                    obj.put("karat", item.karat);
                    obj.put("weight", item.weight);
                    obj.put("price", item.price);
                    obj.put("serial_no", item.serialNo);
                    itemsArr.put(obj);
                }
                sale.put("items", itemsArr);

                pending.put(sale);
                prefs.edit().putString("pending", pending.toString()).apply();
                Log.d(TAG, "Sale saved locally. Pending count: " + pending.length());
            } catch (Exception e) {
                Log.e(TAG, "Local save error: " + e.getMessage());
            }
        }
    }

    private void checkPendingSales() {
        if (getContext() != null) {
            SharedPreferences prefs = getContext().getSharedPreferences(PENDING_SALES_PREFS, Context.MODE_PRIVATE);
            String pendingJson = prefs.getString("pending", "[]");
            try {
                JSONArray pending = new JSONArray(pendingJson);
                if (pending.length() > 0) {
                    final int count = pending.length();
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            tvCartStatus.setText("⚠️ يوجد " + count + " عملية بيع معلقة بانتظار المزامنة");
                            tvCartStatus.setTextColor(Color.parseColor("#F59E0B"));
                        });
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Pending check error: " + e.getMessage());
            }
        }
    }

    // ===================== Audio & Toast =====================

    private void playAlertSound(int id) {
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).playSound(id);
        }
    }

    private void showToast(String msg) {
        if (getContext() != null) {
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (scaleConnector != null) {
            scaleConnector.disconnect();
        }
    }

    // ===================== RecyclerView Adapter =====================

    private static class CartAdapter extends RecyclerView.Adapter<CartAdapter.ViewHolder> {

        interface OnRemoveListener { void onRemove(int position); }

        private final List<CartItem> items;
        private final OnRemoveListener removeListener;

        CartAdapter(List<CartItem> items, OnRemoveListener listener) {
            this.items = items;
            this.removeListener = listener;
        }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_pos_cart_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            CartItem item = items.get(position);
            holder.tvTitle.setText(item.name);
            holder.tvEpc.setText("EPC: " + item.epc);
            holder.tvKaratWeight.setText(String.format(Locale.US, "%s | %.2fg",
                    item.karat, item.scaleWeight > 0 ? item.scaleWeight : item.weight));
            holder.tvPrice.setText(String.format(Locale.US, "$%,.2f", item.price));

            if (item.isAvailable) {
                holder.tvStatus.setText("✅ متاحة للبيع");
                holder.tvStatus.setTextColor(Color.parseColor("#4ADE80"));
            } else {
                String statusText;
                int color;
                switch (item.status.toUpperCase()) {
                    case "SOLD":
                        statusText = "❌ مباعة مسبقاً";
                        color = Color.parseColor("#EF4444");
                        break;
                    case "RESERVED":
                        statusText = "🔒 محجوزة";
                        color = Color.parseColor("#F59E0B");
                        break;
                    default:
                        statusText = "⚠️ " + item.status;
                        color = Color.parseColor("#EF4444");
                }
                holder.tvStatus.setText(statusText);
                holder.tvStatus.setTextColor(color);
            }

            holder.btnRemove.setOnClickListener(v -> {
                if (holder.getAdapterPosition() != RecyclerView.NO_POSITION) {
                    removeListener.onRemove(holder.getAdapterPosition());
                }
            });
        }

        @Override
        public int getItemCount() { return items.size(); }

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
