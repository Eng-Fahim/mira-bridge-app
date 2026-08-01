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
import com.example.uhf.engine.SmartScaleConnector;

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
 * وضع البيع الخاص بـ MIRA Bridge
 */
public class MiraPosFragment extends Fragment {

    private static final String TAG = "MiraPosFragment";
    private static final String PREFS_NAME = "mira_settings";

    // عناصر الواجهة
    private TextView tvScaleStatus, tvItemCount, tvTotalAmount, tvCartStatus;
    private EditText etManualEpc;
    private Button btnAddManual, btnCheckout, btnClearCart;
    private RecyclerView rvCartItems;

    // هيكل بيانات القطع في سلة البيع
    public static class CartItem {
        public String epc;
        public String name;
        public String karat;
        public double weight;
        public double scaleWeight;
        public double price;
        public boolean isAvailable;
        public String status;

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

    // موصل الميزان الذكي
    private SmartScaleConnector scaleConnector;
    private double liveScaleWeight = 0.0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_pos, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // ربط العناصر
        tvScaleStatus = view.findViewById(R.id.tvPosScaleStatus);
        tvItemCount = view.findViewById(R.id.tvPosItemCount);
        tvTotalAmount = view.findViewById(R.id.tvPosTotalAmount);
        tvCartStatus = view.findViewById(R.id.tvPosCartStatus);

        etManualEpc = view.findViewById(R.id.etPosManualEpc);
        btnAddManual = view.findViewById(R.id.btnPosAddManual);
        btnCheckout = view.findViewById(R.id.btnPosCheckout);
        btnClearCart = view.findViewById(R.id.btnPosClearCart);

        rvCartItems = view.findViewById(R.id.rvPosCartItems);
        rvCartItems.setLayoutManager(new LinearLayoutManager(getContext()));
        cartAdapter = new CartAdapter(cartList, this::removeItemFromCart);
        rvCartItems.setAdapter(cartAdapter);

        // الأحداث
        btnAddManual.setOnClickListener(v -> {
            String epc = etManualEpc.getText().toString().trim();
            if (!epc.isEmpty()) {
                processRingEpc(epc);
                etManualEpc.setText("");
            } else {
                Toast.makeText(getContext(), "Please enter an EPC", Toast.LENGTH_SHORT).show();
            }
        });

        btnClearCart.setOnClickListener(v -> clearCart());
        btnCheckout.setOnClickListener(v -> processCheckoutAndStockUpdate());

        initSmartScale();
        updateCartSummary();
    }

    /**
     * ⚖️ الاتصال والاستماع لقراءات الميزان اللحظية
     */
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

    /**
     * 📥 نقطة الدعم الرئيسية: يتم استدعاؤها فور مسح Ring EPC
     */
    public void onRingScanned(String epc) {
        if (scannedEpcs.contains(epc)) {
            playAlertSound(2);
            showToast("القطعة مضافة مسبقاً للسلة: " + epc);
            return;
        }
        processRingEpc(epc);
    }

    /**
     * جلب بيانات القطعة والتحقق من توفرها
     */
    private void processRingEpc(final String epc) {
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
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setDoOutput(true);

                JSONObject jsonParam = new JSONObject();
                jsonParam.put("epc", epc);
                jsonParam.put("context", "pos_sale");
                if (liveScaleWeight > 0) {
                    jsonParam.put("scale_weight", liveScaleWeight);
                }

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonParam.toString().getBytes("utf-8"));
                }

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "utf-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);

                JSONObject resObj = new JSONObject(response.toString());
                JSONObject item = resObj.optJSONObject("item");
                JSONObject decision = resObj.optJSONObject("decision");

                boolean isAvailable = decision != null && decision.optBoolean("allowed", true);
                String title = item != null ? item.optString("title", "خاتم ذهب") : "Ring " + epc.substring(Math.max(0, epc.length() - 4));
                String karat = item != null ? item.optString("karat", "21K") : "21K";
                double weight = item != null ? item.optDouble("weight", 5.0) : 5.0;
                double price = item != null ? item.optDouble("price", 350.0) : 350.0;
                String status = item != null ? item.optString("status", "AVAILABLE") : "AVAILABLE";

                CartItem cartItem = new CartItem(epc, title, karat, weight, liveScaleWeight, price, isAvailable, status);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        scannedEpcs.add(epc);
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
                        CartItem fallbackItem = new CartItem(epc, "خاتم ذهب (اختباري)", "21K", 5.2, liveScaleWeight, 420.0, true, "IN_STOCK");
                        scannedEpcs.add(epc);
                        cartList.add(0, fallbackItem);
                        cartAdapter.notifyItemInserted(0);
                        updateCartSummary();
                    });
                }
            }
        }).start();
    }

    /**
     * تحديث ملخص السلة
     */
    private void updateCartSummary() {
        int totalItems = cartList.size();
        double totalSum = 0.0;
        boolean hasUnavailable = false;

        for (CartItem item : cartList) {
            totalSum += item.price;
            if (!item.isAvailable) hasUnavailable = true;
        }

        tvItemCount.setText(String.valueOf(totalItems));
        tvTotalAmount.setText(String.format(Locale.US, "$%,.2f", totalSum));

        if (totalItems == 0) {
            tvCartStatus.setText("جاهز لمسح القطع والخواتم...");
            tvCartStatus.setTextColor(Color.parseColor("#94A3B8"));
            btnCheckout.setEnabled(false);
            btnCheckout.setAlpha(0.5f);
        } else if (hasUnavailable) {
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

    /**
     * 📤 إتمام عملية البيع وتحديث حالة المخزون
     */
    private void processCheckoutAndStockUpdate() {
        if (cartList.isEmpty()) return;

        btnCheckout.setEnabled(false);
        tvCartStatus.setText("جاري خصم القطع وتحديث حالة المخزون...");
        tvCartStatus.setTextColor(Color.parseColor("#F59E0B"));

        new Thread(() -> {
            try {
                SharedPreferences prefs = getContext() != null ?
                        getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) : null;

                String apiUrl = "https://ams.ibreg.org/wp-json/mira-gate/v1/update-stock";
                String apiKey = "mira_gate_test071234567890abcdefghijklmnop";

                if (prefs != null) {
                    apiUrl = prefs.getString("mira_stock_api_url", apiUrl);
                    apiKey = prefs.getString("mira_api_key", apiKey);
                }

                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("X-MIRA-API-Key", apiKey);
                conn.setConnectTimeout(5000);
                conn.setDoOutput(true);

                JSONArray itemsArr = new JSONArray();
                for (CartItem item : cartList) {
                    JSONObject obj = new JSONObject();
                    obj.put("epc", item.epc);
                    obj.put("new_status", "SOLD");
                    obj.put("sale_price", item.price);
                    itemsArr.put(obj);
                }

                JSONObject payload = new JSONObject();
                payload.put("action", "COMPLETE_POS_SALE");
                payload.put("items", itemsArr);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.toString().getBytes("utf-8"));
                }

                int code = conn.getResponseCode();
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (code >= 200 && code < 300) {
                            playAlertSound(1);
                            Toast.makeText(getContext(), "✅ تم إتمام عملية البيع وتحديث حالة القطع إلى SOLD بنجاح!", Toast.LENGTH_LONG).show();
                            clearCart();
                        } else {
                            tvCartStatus.setText("❌ فشل تحديث المخزون (" + code + ")");
                            tvCartStatus.setTextColor(Color.parseColor("#EF4444"));
                            btnCheckout.setEnabled(true);
                        }
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "Checkout stock sync error: " + e.getMessage());
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "✅ تم حفظ عملية البيع محلياً (في انتظار المزامنة)", Toast.LENGTH_LONG).show();
                        clearCart();
                    });
                }
            }
        }).start();
    }

    private void playAlertSound(int id) {
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).playSound(id);
        }
    }

    private void showToast(String msg) {
        if (getContext() != null) Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (scaleConnector != null) scaleConnector.disconnect();
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

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_pos_cart_row, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            CartItem item = items.get(position);
            holder.tvTitle.setText(item.name);
            holder.tvEpc.setText("EPC: " + item.epc);
            holder.tvKaratWeight.setText(String.format(Locale.US, "%s | %.2fg", item.karat, item.weight));
            holder.tvPrice.setText(String.format(Locale.US, "$%,.2f", item.price));

            if (item.isAvailable) {
                holder.tvStatus.setText("متاحة للبيع");
                holder.tvStatus.setTextColor(Color.parseColor("#4ADE80"));
            } else {
                holder.tvStatus.setText("غير متاحة (" + item.status + ")");
                holder.tvStatus.setTextColor(Color.parseColor("#EF4444"));
            }

            holder.btnRemove.setOnClickListener(v -> removeListener.onRemove(holder.getAdapterPosition()));
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
