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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MiraPosFragment extends Fragment {

    private static final String TAG = "MiraPosFragment";
    private static final String PENDING_SALES_PREFS = "pos_pending_sales";

    // ==================== متغيرات الأسعار الديناميكية ====================
    private double goldPrice24K = 75.0;
    private double goldPrice22K = 68.75;
    private double goldPrice21K = 65.62;
    private double goldPrice18K = 56.25;
    private double makingChargePerGram = 10.0;
    private double profitMarginPercent = 90.0;

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
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        bindViews(view);
        setupRecyclerView();
        setupListeners();
        initSmartScale();
        loadGoldRates();
        checkPendingSales();
        updateCartSummary();
    }

    @Override
    public void onResume() {
        super.onResume();

        if (getActivity() instanceof UHFMainActivity)
            ((UHFMainActivity) getActivity()).setActivePOSFragment(this);

        if (tvCartStatus != null) {
            tvCartStatus.setText(isArabic ? "جاهز لمسح القطع..." : "Ready to scan items...");
            tvCartStatus.setTextColor(Color.parseColor("#94A3B8"));
        }

        syncPendingSales();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getActivity() instanceof UHFMainActivity)
            ((UHFMainActivity) getActivity()).setActivePOSFragment(null);
    }

    // ==================== دوال مساعدة ====================

    private int getActiveBranchId() {
        try {
            return branchesManager != null ? branchesManager.getActiveBranchId() : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    private double getGoldPriceByKarat(String karat) {
        if (karat == null) return goldPrice21K;
        switch (karat.toUpperCase()) {
            case "24K": return goldPrice24K;
            case "22K": return goldPrice22K;
            case "21K": return goldPrice21K;
            case "18K": return goldPrice18K;
            default: return goldPrice21K;
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

    // ==================== 🟢 جلب أسعار الذهب من السيرفر ====================

    private void loadGoldRates() {
        new Thread(() -> {
            try {
                Context ctx = getContext();
                if (ctx == null) return;
                MiraApiClient api = MiraApiClient.getInstance(ctx);
                MiraApiClient.ApiResponse response = api.getGoldRatesAndSettings();
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject rates = response.data.optJSONObject("live_rates");
                    if (rates != null) {
                        goldPrice24K = rates.optDouble("24K", 75.0);
                        goldPrice22K = rates.optDouble("22K", 68.75);
                        goldPrice21K = rates.optDouble("21K", 65.62);
                        goldPrice18K = rates.optDouble("18K", 56.25);
                    }
                    JSONObject makings = response.data.optJSONObject("default_making_charges");
                    if (makings != null) {
                        makingChargePerGram = makings.optDouble("21K", 10.0);
                    }
                    JSONObject margins = response.data.optJSONObject("default_profit_margins");
                    if (margins != null) {
                        profitMarginPercent = margins.optDouble("21K", 90.0);
                    }
                    Log.i(TAG, "Gold rates loaded successfully");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading gold rates: " + e.getMessage());
            }
        }).start();
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
                if (!epc.isEmpty()) {
                    processRingEpc(epc, "manual");
                    if (etManualEpc != null) etManualEpc.setText("");
                } else {
                    showToast(isArabic ? "أدخل EPC أو باركود" : "Enter EPC or barcode");
                }
            });
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
                        tvScaleStatus.setTextColor(Color.parseColor("#4ADE80"));
                    }
                });
            }

            @Override
            public void onScaleConnected(String n) {
                safeRun(() -> {
                    if (tvScaleStatus != null) {
                        tvScaleStatus.setText("⚖️ " + n);
                        tvScaleStatus.setTextColor(Color.parseColor("#38BDF8"));
                    }
                });
            }

            @Override
            public void onScaleDisconnected() {
                liveScaleWeight = 0.0;
                safeRun(() -> {
                    if (tvScaleStatus != null) {
                        tvScaleStatus.setText(isArabic ? "⚖️ غير متصل" : "⚖️ Offline");
                        tvScaleStatus.setTextColor(Color.parseColor("#94A3B8"));
                    }
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

    private void toggleRFIDMode() {
        isRFIDModeActive = !isRFIDModeActive;
        if (isRFIDModeActive) {
            if (btnPosRFIDMode != null) {
                btnPosRFIDMode.setText(isArabic ? "📡 RFID نشط" : "📡 RFID Active");
                btnPosRFIDMode.setTextColor(Color.WHITE);
            }
            if (tvCartStatus != null) {
                tvCartStatus.setText(isArabic ? "📡 جاهز لمسح RFID..." : "📡 Ready for RFID scan...");
                tvCartStatus.setTextColor(Color.parseColor("#4ADE80"));
            }
            if (getActivity() instanceof UHFMainActivity)
                ((UHFMainActivity) getActivity()).setActivePOSFragment(this);
        } else {
            if (btnPosRFIDMode != null) {
                btnPosRFIDMode.setText(isArabic ? "📡 مسح RFID" : "📡 Scan RFID");
                btnPosRFIDMode.setTextColor(Color.WHITE);
            }
            if (tvCartStatus != null) {
                tvCartStatus.setText(isArabic ? "جاهز لمسح القطع..." : "Ready to scan items...");
                tvCartStatus.setTextColor(Color.parseColor("#94A3B8"));
            }
            if (getActivity() instanceof UHFMainActivity)
                ((UHFMainActivity) getActivity()).setActivePOSFragment(null);
        }
    }

    // ==================== مسح القطع ====================

    public void onRingScanned(String epc) {
        if (scannedEpcs.contains(epc)) {
            playAlertSound(2);
            showToast(isArabic ? "مضافة مسبقاً: " + epc : "Already added: " + epc);
            return;
        }
        processRingEpc(epc, "rfid");
    }

    public void onBarcodeScanned(String data) {
        if (scannedEpcs.contains(data)) {
            playAlertSound(2);
            showToast(isArabic ? "مضافة مسبقاً" : "Already added");
            return;
        }
        processRingEpc(data, "barcode");
    }

    private void processRingEpc(final String code, final String source) {
        playAlertSound(1);

        safeRun(() -> {
            if (tvCartStatus != null) {
                tvCartStatus.setText(isArabic ? "🔍 جاري الاستعلام..." : "🔍 Querying...");
                tvCartStatus.setTextColor(Color.parseColor("#F59E0B"));
            }
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
            }
        }).start();
    }

    private void parseItemResponse(String code, JSONObject data) {
        try {
            JSONObject item = data.optJSONObject("item");
            JSONObject barcode = item != null ? item.optJSONObject("barcode") : null;
            String vaultStatus = item != null ? item.optString("status", "").toLowerCase() : "";

            boolean isAvailable = !vaultStatus.equals("sold") && !vaultStatus.equals("reserved");
            String title = item != null ? item.optString("title", isArabic ? "قطعة" : "Item")
                    : (isArabic ? "قطعة" : "Item");
            String karat = item != null ? item.optString("karat", "21K") : "21K";
            double weight = item != null ? item.optDouble("weight", item.optDouble("gross_weight", 0)) : 0;
            double price = item != null ? item.optDouble("dynamic_retail_price",
                    item.optDouble("retail_price", item.optDouble("price", 0))) : 0;

            if (price <= 0 && weight > 0) {
                double gramPrice = getGoldPriceByKarat(karat);
                double baseGold = weight * gramPrice;
                double making = weight * makingChargePerGram;
                price = (baseGold + making) * (profitMarginPercent / 100.0);
            }

            CartItem cartItem = new CartItem(code, title, karat, weight,
                    liveScaleWeight, price, isAvailable, vaultStatus.toUpperCase());
            cartItem.serialNo = item != null ? item.optString("serial", code) : code;
            cartItem.gtin = (barcode != null) ? barcode.optString("gtin13", "")
                    : (item != null ? item.optString("gtin", "") : "");
            cartItem.location = item != null ? item.optString("location", "") : "";
            cartItem.itemId = item != null ? item.optInt("id", 0) : 0;

            safeRun(() -> {
                if (!isAvailable) {
                    showToast(isArabic ? "❌ غير متاحة: " + vaultStatus
                            : "❌ Unavailable: " + vaultStatus);
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
            if (tvItemCount != null) tvItemCount.setText(String.valueOf(fc));
            if (tvTotalAmount != null)
                tvTotalAmount.setText(String.format(Locale.US, "%,.0f YER", fs));
            if (tvCartStatus != null) {
                if (fc == 0) {
                    tvCartStatus.setText(isArabic ? "جاهز لمسح القطع..." : "Ready to scan...");
                } else if (fb) {
                    tvCartStatus.setText(isArabic ? "⚠️ قطعة غير متاحة!" : "⚠️ Unavailable item!");
                } else {
                    tvCartStatus.setText(isArabic ? "✓ جاهز للبيع" : "✓ Ready for sale");
                }
                tvCartStatus.setTextColor(fc == 0 ? Color.parseColor("#94A3B8")
                        : fb ? Color.parseColor("#EF4444") : Color.parseColor("#4ADE80"));
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
        cartList.clear();
        scannedEpcs.clear();
        if (cartAdapter != null) cartAdapter.notifyDataSetChanged();
        updateCartSummary();
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

        final AlertDialog loadingDialog = showLoadingDialog();

        safeRun(() -> {
            if (btnCheckout != null) {
                btnCheckout.setEnabled(false);
                btnCheckout.setAlpha(0.5f);
            }
        });

        new Thread(() -> {
            try {
                Context ctx = getContext();
                if (ctx == null) return;
                MiraApiClient client = MiraApiClient.getInstance(ctx);
                JSONObject payload = new JSONObject();
                payload.put("action", "COMPLETE_POS_SALE");
                payload.put("gate_id", client.getGateId());
                payload.put("branch_id", getActiveBranchId());

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
                    obj.put("serial_no", item.serialNo != null ? item.serialNo : item.epc);
                    obj.put("title", item.name);
                    obj.put("karat", item.karat);
                    obj.put("weight", item.scaleWeight > 0 ? item.scaleWeight : item.weight);
                    obj.put("sale_price", item.price);
                    obj.put("new_status", "SOLD");
                    if (item.itemId > 0) obj.put("item_id", item.itemId);
                    itemsArr.put(obj);
                }
                payload.put("items", itemsArr);

                // التفاصيل المالية والمحاسبية
                JSONObject payment = new JSONObject();
                payment.put("subtotal", getCartTotal());
                payment.put("discount_percent", data.discount);
                payment.put("final_amount", data.finalAmount);

                String targetAccountCode;
                switch (data.paymentMethod.toUpperCase()) {
                    case "CARD":
                    case "NET":
                        targetAccountCode = "110201";
                        break;
                    case "CREDIT":
                        targetAccountCode = "110301";
                        break;
                    case "CASH":
                    default:
                        targetAccountCode = "110101";
                        break;
                }
                payment.put("account_code", targetAccountCode);
                payload.put("payment", payment);

                MiraApiClient.ApiResponse response = client.checkout(payload);

                safeRun(() -> {
                    if (loadingDialog != null && loadingDialog.isShowing()) {
                        loadingDialog.dismiss();
                    }

                    if (response != null && response.isSuccess) {
                        playAlertSound(1);
                        int soldCount = cartList.size();
                        double finalAmount = data.finalAmount;
                        String paymentMethod = data.paymentMethod;
                        clearCart();
                        showSuccessDialog(finalAmount, soldCount, paymentMethod);
                        String json = response.data != null ? response.data.toString() : "{}";
                        if (getActivity() instanceof UHFMainActivity)
                            ((UHFMainActivity) getActivity()).openReceiptFragment(json);
                    } else {
                        if (tvCartStatus != null) {
                            tvCartStatus.setText("❌ " + (response != null ? response.code : "Error"));
                            tvCartStatus.setTextColor(Color.RED);
                        }
                        if (btnCheckout != null) {
                            btnCheckout.setEnabled(true);
                            btnCheckout.setAlpha(1f);
                        }
                        showToast(isArabic ? "❌ فشل البيع" : "❌ Failed");
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Checkout Error: " + e.getMessage());
                safeRun(() -> {
                    if (loadingDialog != null && loadingDialog.isShowing()) {
                        loadingDialog.dismiss();
                    }
                    saveFullSaleLocally(data);
                    clearCart();
                    if (btnCheckout != null) {
                        btnCheckout.setEnabled(true);
                        btnCheckout.setAlpha(1f);
                    }
                    showToast(isArabic ? "💾 تم الحفظ محلياً للترحيل لاحقاً"
                            : "💾 Saved locally for later posting");
                });
            }
        }).start();
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

            Log.i(TAG, "💾 Sale saved locally: " + sale.optString("id"));
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
                        tvCartStatus.setText("⚠️ " + arr.length() + " "
                                + (isArabic ? "عملية معلقة" : "pending sales"));
                        tvCartStatus.setTextColor(Color.parseColor("#F59E0B"));
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

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

    // ==================== DIALOGS ====================

    private AlertDialog showLoadingDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 30, 40, 30);
        layout.setGravity(android.view.Gravity.CENTER);

        ProgressBar progressBar = new ProgressBar(requireContext());
        progressBar.setIndeterminate(true);
        layout.addView(progressBar);

        TextView tvLoading = new TextView(requireContext());
        tvLoading.setText(isArabic ? "🔄 جاري إتمام البيع والترحيل المحاسبي..."
                : "🔄 Processing sale & journal entry...");
        tvLoading.setTextColor(Color.parseColor("#1E40AF"));
        tvLoading.setTextSize(14);
        tvLoading.setPadding(0, 16, 0, 0);
        tvLoading.setGravity(android.view.Gravity.CENTER);
        layout.addView(tvLoading);

        TextView tvSubtext = new TextView(requireContext());
        tvSubtext.setText(isArabic ? "يرجى الانتظار، يتم تحديث المخزون وتسجيل القيود"
                : "Please wait, updating inventory and ledger");
        tvSubtext.setTextColor(Color.parseColor("#64748B"));
        tvSubtext.setTextSize(11);
        tvSubtext.setPadding(0, 4, 0, 0);
        tvSubtext.setGravity(android.view.Gravity.CENTER);
        layout.addView(tvSubtext);

        builder.setView(layout);
        builder.setCancelable(false);

        AlertDialog dialog = builder.create();
        dialog.show();
        return dialog;
    }

    private void showSuccessDialog(double amount, int itemCount, String paymentMethod) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(30, 25, 30, 10);

        TextView tvIcon = new TextView(requireContext());
        tvIcon.setText("✅");
        tvIcon.setTextSize(40);
        tvIcon.setGravity(android.view.Gravity.CENTER);
        layout.addView(tvIcon);

        TextView tvTitle = new TextView(requireContext());
        tvTitle.setText(isArabic ? "تم البيع بنجاح!" : "Sale Completed!");
        tvTitle.setTextColor(Color.parseColor("#16A34A"));
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setGravity(android.view.Gravity.CENTER);
        tvTitle.setPadding(0, 10, 0, 20);
        layout.addView(tvTitle);

        com.google.android.material.card.MaterialCardView card =
                new com.google.android.material.card.MaterialCardView(requireContext());
        card.setCardBackgroundColor(Color.parseColor("#F0FDF4"));
        card.setRadius(12);
        card.setCardElevation(2);
        card.setPadding(16, 16, 16, 16);

        LinearLayout cardLayout = new LinearLayout(requireContext());
        cardLayout.setOrientation(LinearLayout.VERTICAL);

        addDetailRow(cardLayout, isArabic ? "📦 عدد القطع" : "📦 Items", String.valueOf(itemCount));
        addDetailRow(cardLayout, isArabic ? "💰 المبلغ الإجمالي" : "💰 Total Amount",
                String.format(Locale.US, "%,.0f YER", amount));
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
        tvLabel.setTextColor(Color.parseColor("#475569"));
        tvLabel.setTextSize(12);
        tvLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        tvLabel.setMinWidth(150);
        row.addView(tvLabel);

        TextView tvValue = new TextView(requireContext());
        tvValue.setText(value);
        tvValue.setTextColor(Color.parseColor("#0F172A"));
        tvValue.setTextSize(12);
        tvValue.setPadding(8, 0, 0, 0);
        row.addView(tvValue);

        parent.addView(row);
    }

    // ==================== Cleanup ====================

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (scaleConnector != null) scaleConnector.disconnect();
    }

    // ==================== Adapter ====================

    private static class CartAdapter extends RecyclerView.Adapter<CartAdapter.ViewHolder> {
        interface OnRemoveListener {
            void onRemove(int position);
        }

        private final List<CartItem> items;
        private final OnRemoveListener removeListener;
        private final boolean isArabic;

        CartAdapter(List<CartItem> items, OnRemoveListener listener, boolean isArabic) {
            this.items = items;
            this.removeListener = listener;
            this.isArabic = isArabic;
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
            if (h.tvPrice != null)
                h.tvPrice.setText(String.format(Locale.US, "%,.0f YER", item.price));
            if (h.tvStatus != null) {
                if (item.isAvailable) {
                    h.tvStatus.setText(isArabic ? "✅ متاحة" : "✅ Available");
                    h.tvStatus.setTextColor(Color.parseColor("#4ADE80"));
                } else {
                    h.tvStatus.setText(isArabic ? "❌ غير متاحة" : "❌ Unavailable");
                    h.tvStatus.setTextColor(Color.parseColor("#EF4444"));
                }
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
