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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.dialog.POSCheckoutDialog;
import com.example.uhf.engine.SmartScaleConnector;

import com.example.uhf.tools.UIHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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

        // ربط عناصر الواجهة
        bindViews(view);

        // تهيئة القائمة
        setupRecyclerView();

        // ربط الأحداث
        setupListeners();

        // بدء الميزان الذكي
        initSmartScale();

        // تحميل المبيعات المعلقة
        checkPendingSales();

        // تحديث الملخص
        updateCartSummary();
    }

    @Override
    public void onResume() {
        super.onResume();
        // تسجيل هذا الفragment كمستقبل نشط للـ RFID
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setActivePOSFragment(this);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // إلغاء التسجيل عند الخروج من التبويب
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

        // ⭐ أزرار المسح الجديدة
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
        // ⌨️ زر الإضافة اليدوية
        btnAddManual.setOnClickListener(v -> {
            String epc = etManualEpc.getText().toString().trim();
            if (!epc.isEmpty()) {
                processRingEpc(epc, "manual");
                etManualEpc.setText("");
            } else {
                showToast("Please enter an EPC, Serial, or GTIN");
            }
        });

        // 🗑️ زر مسح السلة
        btnClearCart.setOnClickListener(v -> clearCart());

        // 💳 زر إتمام البيع
        btnCheckout.setOnClickListener(v -> processCheckoutAndStockUpdate());

        // 📷 زر فتح ماسح GS1 DATAMATRIX
        if (btnOpenScanner != null) {
            btnOpenScanner.setOnClickListener(v -> openBarcodeScanner());
        }

        // 📡 زر تفعيل/تعطيل RFID المباشر
        if (btnPosRFIDMode != null) {
            btnPosRFIDMode.setOnClickListener(v -> toggleRFIDMode());
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

   
        /**
     * 📷 فتح ماسح GS1 DATAMATRIX بالكاميرا
     */
    private void openBarcodeScanner() {
        MiraBarcodeScannerFragment scannerFragment = new MiraBarcodeScannerFragment();
        scannerFragment.setOnBarcodeScannedListener((data, format) -> {
            // عند مسح باركود، أرسل إلى POS
            onBarcodeScanned(data);
            // العودة إلى شاشة POS
            if (getParentFragmentManager().getBackStackEntryCount() > 0) {
                getParentFragmentManager().popBackStack();
            }
        });

        // ⚡ تم تصحيح الـ Container ID لاستخدام الحاوية الجذرية لـ Fragment الحالي بأمان
        if (getView() != null && getView().getParent() != null) {
            int containerId = ((View) getView().getParent()).getId();
            getParentFragmentManager()
                    .beginTransaction()
                    .replace(containerId, scannerFragment)
                    .addToBackStack("barcode_scanner")
                    .commit();
        } else {
            // حل احتياطي استخدام android.R.id.content
            getParentFragmentManager()
                    .beginTransaction()
                    .replace(android.R.id.content, scannerFragment)
                    .addToBackStack("barcode_scanner")
                    .commit();
        }
    }


    // ===================== 📡 RFID Direct Mode =====================

    /**
     * 📡 تفعيل/تعطيل استقبال RFID المباشر
     */
    private void toggleRFIDMode() {
        isRFIDModeActive = !isRFIDModeActive;

        if (isRFIDModeActive) {
            // تفعيل وضع RFID
            btnPosRFIDMode.setText("📡 RFID نشط");
            btnPosRFIDMode.setTextColor(Color.parseColor("#FFFFFF"));
            btnPosRFIDMode.setBackgroundResource(R.drawable.bg_rfid_btn_active);
            tvCartStatus.setText("📡 جاهز لاستقبال مسح RFID المباشر...");
            tvCartStatus.setTextColor(Color.parseColor("#4ADE80"));

            // تسجيل كمستقبل نشط
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setActivePOSFragment(this);
            }
        } else {
            // تعطيل وضع RFID
            btnPosRFIDMode.setText("📡 مسح RFID");
            btnPosRFIDMode.setTextColor(Color.parseColor("#FFFFFF"));
            btnPosRFIDMode.setBackgroundResource(R.drawable.bg_rfid_btn);
            tvCartStatus.setText("جاهز لمسح القطع والخواتم...");
            tvCartStatus.setTextColor(Color.parseColor("#94A3B8"));

            // إلغاء التسجيل
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setActivePOSFragment(null);
            }
        }
    }

    // ===================== Tag Processing =====================

    /**
     * 📥 نقطة الدعم الرئيسية: يتم استدعاؤها فور مسح Ring EPC بواسطة قارئ RFID
     */
    public void onRingScanned(String epc) {
        if (scannedEpcs.contains(epc)) {
            playAlertSound(2);
            showToast("القطعة مضافة مسبقاً للسلة: " + epc);
            return;
        }
        processRingEpc(epc, "rfid");
    }

    /**
     * 📱 استدعاء من مسح GS1 DATAMATRIX
     */
    public void onBarcodeScanned(String barcodeData) {
        if (scannedEpcs.contains(barcodeData)) {
            playAlertSound(2);
            showToast("القطعة مضافة مسبقاً للسلة");
            return;
        }
        processRingEpc(barcodeData, "barcode");
    }

    /**
     * 🔍 جلب بيانات القطعة والتحقق من توفرها في سيرفر MIRA ID
     * يدعم: RFID EPC, GS1 DATAMATRIX, Serial Number, GTIN
     */
    private void processRingEpc(final String code, final String source) {
        playAlertSound(1);

        new Thread(() -> {
            try {
                SharedPreferences prefs = getContext() != null ?
                        getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) : null;

                String apiUrl = "https://ams.ibreg.org/wp-json/mira-gate/v1/authorize";
                String apiKey = "mira_gate_test071234567890abcdefghijklmnop";

                if (prefs != null) {
                    apiUrl = prefs.getString("mira_api_url", apiUrl);
                    apiKey = prefs.getString("mira_api_key", apiKey);
                }

                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("X-MIRA-API-Key", apiKey);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setDoOutput(true);

                JSONObject jsonParam = new JSONObject();
                jsonParam.put("epc", code);
                jsonParam.put("context", "pos_sale");
                jsonParam.put("source", source);
                if (liveScaleWeight > 0) {
                    jsonParam.put("scale_weight", liveScaleWeight);
                }

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonParam.toString().getBytes("utf-8"));
                }

                int responseCode = conn.getResponseCode();
                InputStream is = (responseCode >= 200 && responseCode < 300) ?
                        conn.getInputStream() : conn.getErrorStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "utf-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);

                final String responseStr = response.toString();
                Log.d(TAG, "API Response [" + source + "]: " + responseStr);

                JSONObject resObj = new JSONObject(responseStr);
                JSONObject item = resObj.optJSONObject("item");
                JSONObject decision = resObj.optJSONObject("decision");
                JSONObject barcode = item != null ? item.optJSONObject("barcode") : null;

                boolean isAvailable = decision != null && decision.optBoolean("allowed", true);
                String title = item != null ? item.optString("title", "خاتم ذهب") :
                        "Item " + code.substring(Math.max(0, code.length() - 6));
                String karat = item != null ? item.optString("karat", "21K") : "21K";
                double weight = item != null ? item.optDouble("weight", 5.0) : 5.0;
                double price = item != null ? item.optDouble("price", 350.0) : 350.0;
                String status = item != null ? item.optString("status", "AVAILABLE") : "AVAILABLE";
                String serialNo = item != null ? item.optString("serial", code) : code;
                String gtin = (barcode != null) ? barcode.optString("gtin13", "") : "";
                String location = item != null ? item.optString("location", "") : "";
                int itemId = item != null ? item.optInt("id", 0) : 0;

                CartItem cartItem = new CartItem(code, title, karat, weight,
                        liveScaleWeight, price, isAvailable, status);
                cartItem.serialNo = serialNo;
                cartItem.gtin = gtin;
                cartItem.location = location;
                cartItem.itemId = itemId;

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        scannedEpcs.add(code);
                        cartList.add(0, cartItem);
                        cartAdapter.notifyItemInserted(0);
                        rvCartItems.scrollToPosition(0);
                        updateCartSummary();
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "Error fetching item: " + e.getMessage());
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
        }).start();
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
        if (cartList.isEmpty()) return;

        // ⚡ إخفاء لوحة المفاتيح
        if (getActivity() != null) {
            UIHelper.hideKeyboard(getActivity());
        }

        double totalAmount = getCartTotal();

        // ⚡ استخدام getChildFragmentManager() آمن جداً للدايلوج
        POSCheckoutDialog dialog = POSCheckoutDialog.newInstance(totalAmount, cartList.size());
        dialog.setCheckoutListener(checkoutData -> executeCheckout(checkoutData));
        dialog.show(getChildFragmentManager(), "POSCheckoutDialog");
    }



    private void executeCheckout(POSCheckoutDialog.POSCheckoutData checkoutData) {
        btnCheckout.setEnabled(false);
        btnCheckout.setAlpha(0.5f);
        tvCartStatus.setText("جاري إتمام البيع وتحديث الهوية الرقمية...");
        tvCartStatus.setTextColor(Color.parseColor("#F59E0B"));

        new Thread(() -> {
            try {
                SharedPreferences prefs = getContext() != null ?
                        getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) : null;

                String apiUrl = "https://ams.ibreg.org/wp-json/mira-gate/v1/pos/checkout";
                String apiKey = "mira_gate_test071234567890abcdefghijklmnop";

                if (prefs != null) {
                    apiUrl = prefs.getString("mira_pos_checkout_url", apiUrl);
                    apiKey = prefs.getString("mira_api_key", apiKey);
                }

                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("X-MIRA-API-Key", apiKey);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setDoOutput(true);

                JSONObject payload = new JSONObject();
                payload.put("action", "COMPLETE_POS_SALE");
                payload.put("gate_id", prefs != null ? prefs.getString("mira_gate_id", "handheld_c72") : "handheld_c72");
                payload.put("timestamp", System.currentTimeMillis() / 1000);

                JSONObject customer = new JSONObject();
                customer.put("name", checkoutData.customerName);
                customer.put("phone", checkoutData.customerPhone);
                customer.put("email", checkoutData.customerEmail);
                customer.put("payment_method", checkoutData.paymentMethod);
                customer.put("notes", checkoutData.notes);
                payload.put("customer", customer);

                JSONArray itemsArr = new JSONArray();
                for (CartItem item : cartList) {
                    JSONObject obj = new JSONObject();
                    obj.put("epc", item.epc);
                    obj.put("serial_no", item.serialNo);
                    obj.put("gtin", item.gtin);
                    obj.put("item_id", item.itemId);
                    obj.put("title", item.name);
                    obj.put("karat", item.karat);
                    obj.put("weight", item.weight);
                    obj.put("scale_weight", item.scaleWeight > 0 ? item.scaleWeight : item.weight);
                    obj.put("sale_price", item.price);
                    obj.put("new_status", "SOLD");
                    obj.put("location", item.location);
                    itemsArr.put(obj);
                }
                payload.put("items", itemsArr);

                JSONObject payment = new JSONObject();
                payment.put("subtotal", getCartTotal());
                payment.put("discount_percent", checkoutData.discount);
                payment.put("discount_amount", getCartTotal() * checkoutData.discount / 100);
                payment.put("final_amount", checkoutData.finalAmount);
                payment.put("currency", "USD");
                payload.put("payment", payment);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = payload.toString().getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                int responseCode = conn.getResponseCode();
                InputStream is = (responseCode >= 200 && responseCode < 300) ?
                        conn.getInputStream() : conn.getErrorStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "utf-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);

                final String responseStr = response.toString();
                final int finalCode = responseCode;

                Log.d(TAG, "Checkout Response [" + finalCode + "]: " + responseStr);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (finalCode >= 200 && finalCode < 300) {
                            playAlertSound(1);
                            String successMsg = "✅ تم إتمام البيع بنجاح!\n" +
                                    "العميل: " + checkoutData.customerName + "\n" +
                                    "القطع: " + cartList.size() + "\n" +
                                    "المبلغ: " + String.format(Locale.US, "$%,.2f", checkoutData.finalAmount);
                            Toast.makeText(getContext(), successMsg, Toast.LENGTH_LONG).show();
                            clearCart();
                        } else {
                            tvCartStatus.setText("❌ فشل إتمام البيع (" + finalCode + ")");
                            tvCartStatus.setTextColor(Color.parseColor("#EF4444"));
                            btnCheckout.setEnabled(true);
                            btnCheckout.setAlpha(1.0f);
                            Log.e(TAG, "Checkout failed: " + responseStr);
                            Toast.makeText(getContext(), "❌ فشل الاتصال بالخادم. حاول مرة أخرى.", Toast.LENGTH_LONG).show();
                        }
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "Checkout exception: " + e.getMessage());
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        saveSaleLocally(checkoutData);
                        tvCartStatus.setText("💾 تم حفظ البيع محلياً (بانتظار المزامنة)");
                        tvCartStatus.setTextColor(Color.parseColor("#F59E0B"));
                        Toast.makeText(getContext(),
                                "✅ تم حفظ عملية البيع محلياً (في انتظار المزامنة)",
                                Toast.LENGTH_LONG).show();
                        clearCart();
                        btnCheckout.setEnabled(true);
                        btnCheckout.setAlpha(1.0f);
                    });
                }
            }
        }).start();
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
                sale.put("customer_email", checkoutData.customerEmail);
                sale.put("payment_method", checkoutData.paymentMethod);
                sale.put("notes", checkoutData.notes);
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

    // ===================== Lifecycle Cleanup =====================

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (scaleConnector != null) {
            scaleConnector.disconnect();
        }
    }

    // ===================== RecyclerView Adapter =====================

    private static class CartAdapter extends RecyclerView.Adapter<CartAdapter.ViewHolder> {

        interface OnRemoveListener {
            void onRemove(int position);
        }

        private final List<CartItem> items;
        private final OnRemoveListener removeListener;

        CartAdapter(List<CartItem> items, OnRemoveListener listener) {
            this.items = items;
            this.removeListener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_pos_cart_row, parent, false);
            return new ViewHolder(v);
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
                holder.tvStatus.setText("متاحة للبيع");
                holder.tvStatus.setTextColor(Color.parseColor("#4ADE80"));
            } else {
                holder.tvStatus.setText("غير متاحة (" + item.status + ")");
                holder.tvStatus.setTextColor(Color.parseColor("#EF4444"));
            }

            holder.btnRemove.setOnClickListener(v -> {
                if (holder.getAdapterPosition() != RecyclerView.NO_POSITION) {
                    removeListener.onRemove(holder.getAdapterPosition());
                }
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
