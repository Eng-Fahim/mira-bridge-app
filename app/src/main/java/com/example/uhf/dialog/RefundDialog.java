package com.example.uhf.dialog;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.textfield.TextInputLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RefundDialog extends Dialog {

    private static final String TAG = "RefundDialog";
    private static final long SEARCH_DEBOUNCE_MS = 600L;
    private static final int MIN_QUERY_LEN = 3;

    private static final int KEYCODE_RFID_TRIGGER_1 = 139;
    private static final int KEYCODE_RFID_TRIGGER_2 = 280;
    private static final int KEYCODE_RFID_TRIGGER_3 = 293;

    public interface RefundCallback {
        void onRefundSuccess(JSONObject result);
        void onBarcodeScan();
        void onDialogClosed();
    }

    private static class ItemRow {
    int itemId;
    String title;
    String karat;
    double weight;
    double price;
    boolean isRefunded;
    String condition = "in_stock";

    // ✅ معرّفات الكاش — تُستخدم لإبطال MiraLocalItemCache
    String epc = "";
    String serialNo = "";
    String gtin = "";
    String tid = "";
    String rfidEpc = "";
    String itemCode = "";

    CheckBox checkbox;
    Spinner conditionSpinner;
    View rowView;
}

    private final RefundCallback callback;
    private final boolean isArabic;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<ItemRow> itemRows = new ArrayList<>();

    private EditText etQuery;
    private View llEmpty, llLoading, llSale;
    private MaterialButton btnScan, btnClose, btnCancel, btnConfirm;
    private LinearLayout llItemsContainer;
    private TextView tvSaleNumber, tvSaleMeta, tvTotal, tvPaymentsList, tvWarning;
    private MaterialCardView cardCash, cardCredit;

    private JSONObject currentSale;
    private String selectedMethod = "cash";
    private String defaultMethod = "cash";
    private boolean hasCustomer = false;
    private boolean creditRecommended = false;
    private double creditCreated = 0;
    private boolean suppressWatcher = false;
    private Runnable pendingSearch;
    private int searchSeq = 0;

    @Nullable private DialogInterface.OnKeyListener hardwareKeyListener;

    public RefundDialog(@NonNull Context context, RefundCallback callback) {
        super(context);
        this.callback = callback;
        this.isArabic = context.getResources().getConfiguration().locale.getLanguage().equals("ar");
    }
    
    private static String firstNonEmpty(JSONObject obj, String... keys) {
    if (obj == null || keys == null) return "";
    for (String key : keys) {
        try {
            String v = obj.optString(key, "");
            if (v != null && !v.isEmpty() && !"null".equals(v)) return v;
        } catch (Exception ignored) {}
    }
    return "";
}

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_refund);

        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            );
        }

        initViews();
        setupListeners();
        showState("empty");
    }

    private void initViews() {
        etQuery = findViewById(R.id.etRefundQuery);
        llEmpty = findViewById(R.id.llRefundEmpty);
        llLoading = findViewById(R.id.llRefundLoading);
        llSale = findViewById(R.id.llRefundSale);

        btnScan = findViewById(R.id.btnRefundScan);
        btnClose = findViewById(R.id.btnRefundClose);
        btnCancel = findViewById(R.id.btnRefundCancel);
        btnConfirm = findViewById(R.id.btnRefundConfirm);

        llItemsContainer = findViewById(R.id.llRefundItems);
        tvSaleNumber = findViewById(R.id.tvRefundSaleNumber);
        tvSaleMeta = findViewById(R.id.tvRefundSaleMeta);
        tvTotal = findViewById(R.id.tvRefundTotal);
        tvPaymentsList = findViewById(R.id.tvRefundPaymentsList);
        tvWarning = findViewById(R.id.tvRefundWarning);

        cardCash = findViewById(R.id.cardRefundCash);
        cardCredit = findViewById(R.id.cardRefundCredit);
    }

    private void setupListeners() {
        if (btnClose != null) btnClose.setOnClickListener(v -> close());
        if (btnCancel != null) btnCancel.setOnClickListener(v -> close());
        if (btnScan != null) btnScan.setOnClickListener(v -> {
            if (callback != null) callback.onBarcodeScan();
        });

        if (btnConfirm != null) btnConfirm.setOnClickListener(v -> submitRefund());

        if (cardCash != null) cardCash.setOnClickListener(v -> setMethod("cash"));
        if (cardCredit != null) cardCredit.setOnClickListener(v -> setMethod("credit"));

        if (etQuery != null) {
            etQuery.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (suppressWatcher) return;
                    if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
                    String q = s != null ? s.toString().trim() : "";
                    if (q.length() < MIN_QUERY_LEN) {
                        showState("empty");
                        return;
                    }
                    pendingSearch = () -> performSearch();
                    handler.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
                }
            });
        }

        setOnDismissListener(dialog -> {
            if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
            if (callback != null) callback.onDialogClosed();
        });
        setCancelable(true);
        setCanceledOnTouchOutside(true);
    }

    private void performSearch() {
    if (etQuery == null) return;
    String query = etQuery.getText().toString().trim();
    if (query.isEmpty()) return;

    final int mySeq = ++searchSeq;
    showState("loading");

    // ✅ إذا بدأ بـ INV- → بحث برقم الفاتورة
    //    غير ذلك → بحث بكود القطعة (EPC/Serial/GTIN)
    final boolean isSaleNumber = query.toUpperCase().startsWith("INV-")
            || query.toUpperCase().startsWith("REF-");

    new Thread(() -> {
        try {
            MiraApiClient client = MiraApiClient.getInstance(getContext().getApplicationContext());
            MiraApiClient.ApiResponse resp = isSaleNumber
                    ? client.findSaleForRefund(query)
                    : client.findSaleForRefundByItem(query);

            handler.post(() -> {
                if (mySeq != searchSeq) return;
                if (resp != null && resp.isSuccess && resp.data != null) {
                    JSONObject sale = resp.data.optJSONObject("sale");
                    if (sale != null) {
                        renderSale(sale);
                    } else {
                        showState("empty");
                        toast(isArabic ? "❌ لا توجد فاتورة" : "❌ Not found");
                    }
                } else {
                    String msg = (resp != null && resp.error != null) ? resp.error
                            : (isArabic ? "لا توجد فاتورة" : "Not found");
                    showState("empty");
                    toast("❌ " + msg);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "performSearch error", e);
            handler.post(() -> {
                if (mySeq != searchSeq) return;
                showState("empty");
                toast("⚠️ " + e.getMessage());
            });
        }
    }).start();
}

    private void renderSale(JSONObject sale) {
        try {
            currentSale = sale;
            itemRows.clear();
            llItemsContainer.removeAllViews();

            String saleNumber = sale.optString("sale_number", "");
            String createdAt = sale.optString("created_at", "");
            String customerName = sale.optString("customer_name", "");
            String customerPhone = sale.optString("customer_phone", "");

            tvSaleNumber.setText(saleNumber);

            StringBuilder meta = new StringBuilder();
            if (!createdAt.isEmpty()) {
                meta.append(createdAt.length() > 16 ? createdAt.substring(0, 16) : createdAt);
            }
            if (!customerName.isEmpty()) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(customerName);
            }
            if (!customerPhone.isEmpty()) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(customerPhone);
            }
            tvSaleMeta.setText(meta.toString());

            renderPayments(sale);

            JSONArray items = sale.optJSONArray("items");
            if (items != null && items.length() > 0) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    addItemRow(it);
                }
            }

            hasCustomer = sale.optInt("customer_id", 0) > 0;
            creditCreated = sale.optDouble("credit_created_local", 0);
            defaultMethod = sale.optString("default_refund_method", "cash");
            creditRecommended = "credit".equalsIgnoreCase(defaultMethod);

            if (cardCredit != null) {
                cardCredit.setEnabled(hasCustomer);
                cardCredit.setAlpha(hasCustomer ? 1f : 0.5f);
            }

            if (creditRecommended && hasCustomer) {
                setMethod("credit");
            } else {
                setMethod("cash");
            }

            showState("sale");
            updateTotal();

        } catch (Exception e) {
            Log.e(TAG, "renderSale error", e);
            showState("empty");
            toast("⚠️ " + e.getMessage());
        }
    }

    // 1. دالة عرض تفاصيل السداد
private void renderPayments(JSONObject sale) {
    try {
        JSONArray pay = sale.optJSONArray("payments_summary");
        if (pay == null || pay.length() == 0) {
            tvPaymentsList.setText("—");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pay.length(); i++) {
            JSONObject p = pay.getJSONObject(i);
            String name = p.optString("method_name_ar", p.optString("method", "CASH"));
            double yer = p.optDouble("amount_in_yer", 0);
            sb.append("• ").append(name).append(": ")
              .append(String.format(Locale.US, "%,.0f", yer))
              .append(" YER\n");
        }
        tvPaymentsList.setText(sb.toString().trim());

        // ✅ لا سلسلة append — كل استدعاء على سطر منفصل
        if (creditCreated > 0.01) {
            tvPaymentsList.append("\n💰 أنشأت رصيداً: ");
            tvPaymentsList.append(String.format(Locale.US, "%,.0f", creditCreated));
            tvPaymentsList.append(" YER");
        }
    } catch (Exception e) {
        Log.e(TAG, "renderPayments error", e);
    }
}

// 2. دالة إضافة سطر القطعة
private void addItemRow(JSONObject it) {
    try {
        View row = getLayoutInflater().inflate(R.layout.item_refund_row, llItemsContainer, false);

        ItemRow ir = new ItemRow();
        ir.itemId = it.optInt("item_id", 0);
ir.title = it.optString("item_title", "قطعة");
ir.karat = it.optString("gold_karat", "21K");
ir.weight = it.optDouble("net_weight_grams", 0);
ir.price = it.optDouble("line_total_local", 0);
ir.isRefunded = it.optBoolean("is_refunded", false);

// ✅ استخراج كل المعرّفات الممكنة لإبطال الكاش
ir.epc      = firstNonEmpty(it, "epc", "rfid_epc", "epc_final");
ir.serialNo = firstNonEmpty(it, "serial", "serial_number", "serialNo", "sn");
ir.gtin     = firstNonEmpty(it, "gtin", "gtin13", "barcode", "code");
ir.tid      = firstNonEmpty(it, "tid", "tag_tid", "rfid_tid");
ir.rfidEpc  = firstNonEmpty(it, "rfid_epc", "epc_final");
ir.itemCode = firstNonEmpty(it, "item_code");
        ir.rowView = row;

        TextView tvTitle = row.findViewById(R.id.tvRefundRowTitle);
        TextView tvMeta = row.findViewById(R.id.tvRefundRowMeta);
        TextView tvPrice = row.findViewById(R.id.tvRefundRowPrice);
        TextView tvBadge = row.findViewById(R.id.tvRefundRowBadge);
        CheckBox cb = row.findViewById(R.id.cbRefundItem);
        TextInputLayout tilCond = row.findViewById(R.id.tilRefundCondition);
        com.google.android.material.textfield.MaterialAutoCompleteTextView actCond = row.findViewById(R.id.actRefundCondition);

        ir.checkbox = cb;
        ir.conditionSpinner = null; // Not using Spinner anymore

        tvTitle.setText(ir.title);
        tvMeta.setText(String.format(Locale.US, "%s · %.3fg", ir.karat, ir.weight));
        tvPrice.setText(String.format(Locale.US, "%,.0f YER", ir.price));
        
        boolean isScanned = it.optBoolean("is_scanned", false);
        if (isScanned && !ir.isRefunded) {
        // تمييز بصري
        row.setBackgroundColor(Color.parseColor("#DBEAFE"));  // أزرق فاتح
        cb.setChecked(true);
        // شارة صغيرة
        tvTitle.setText(ir.title + "  📷");
        }
        if (ir.isRefunded) {
            cb.setEnabled(false);
            cb.setChecked(false);
            cb.setAlpha(0.4f);
            tvBadge.setVisibility(View.VISIBLE);
            tvTitle.setPaintFlags(tvTitle.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            tvPrice.setPaintFlags(tvPrice.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            tvPrice.setTextColor(Color.parseColor("#9CA3AF"));
            tilCond.setVisibility(View.GONE);
        } else {
            cb.setChecked(true);
            cb.setOnCheckedChangeListener((b, checked) -> {
                tilCond.setVisibility(checked ? View.VISIBLE : View.GONE);
                updateTotal();
            });

            String[] conditions = {
                isArabic ? "حالة ممتازة (مخزون)" : "Excellent (in stock)",
                isArabic ? "كسر (تالف)" : "Scrapped",
                isArabic ? "تالف (فحص)" : "Damaged"
            };
            ArrayAdapter<String> adp = new ArrayAdapter<>(getContext(), android.R.layout.simple_dropdown_item_1line, conditions);
            actCond.setAdapter(adp);
            actCond.setText(conditions[0], false);
            ir.condition = "in_stock"; // default
            
            actCond.setOnItemClickListener((p, v, pos, id) -> {
                ir.condition = pos == 1 ? "scrapped" : pos == 2 ? "damaged" : "in_stock";
            });
            tilCond.setVisibility(View.VISIBLE);
        }

        itemRows.add(ir);
        llItemsContainer.addView(row);

    } catch (Exception e) {
        Log.e(TAG, "addItemRow error", e);
    }
}

// 3. تحديث ألوان أزرار الإرجاع (Dark Mode Support)
private void setMethod(String method) {
        selectedMethod = method;
        try {
            if ("cash".equals(method)) {
                cardCash.setStrokeColor(android.content.res.ColorStateList.valueOf(Color.parseColor("#3B82F6")));
                cardCash.setCardBackgroundColor(Color.parseColor("#EFF6FF"));
                cardCredit.setStrokeColor(android.content.res.ColorStateList.valueOf(Color.parseColor("#D1D5DB")));
                cardCredit.setCardBackgroundColor(Color.parseColor("#F9FAFB"));
            } else {
                cardCredit.setStrokeColor(android.content.res.ColorStateList.valueOf(Color.parseColor("#16A34A")));
                cardCredit.setCardBackgroundColor(Color.parseColor("#F0FDF4"));
                cardCash.setStrokeColor(android.content.res.ColorStateList.valueOf(Color.parseColor("#D1D5DB")));
                cardCash.setCardBackgroundColor(Color.parseColor("#F9FAFB"));
            }
        } catch (Exception e) {
            Log.w(TAG, "setMethod color error: " + e.getMessage());
        }
        updateWarning();
    }

    private void updateTotal() {
        double total = 0;
        int selectedCount = 0;
        for (ItemRow ir : itemRows) {
            if (!ir.isRefunded && ir.checkbox != null && ir.checkbox.isChecked()) {
                total += ir.price;
                selectedCount++;
            }
        }
        if (tvTotal != null) {
            tvTotal.setText(String.format(Locale.US, "%,.0f YER", total));
        }
        if (btnConfirm != null) {
            boolean enabled = selectedCount > 0 && currentSale != null;
            btnConfirm.setEnabled(enabled);
            btnConfirm.setAlpha(enabled ? 1f : 0.5f);
        }
        updateWarning();
    }

    private void updateWarning() {
        if (tvWarning == null) return;

        if (creditRecommended && "cash".equals(selectedMethod) && creditCreated > 0.01) {
            tvWarning.setText(isArabic
                ? "⚠️ هذه الفاتورة أنشأت رصيداً دائناً بقيمة " + String.format(Locale.US, "%,.0f", creditCreated) + " YER. الإرجاع كرصيد يحفظ التوازن."
                : "⚠️ This sale created " + String.format(Locale.US, "%,.0f", creditCreated) + " YER credit. Refunding as credit keeps balance.");
            tvWarning.setVisibility(View.VISIBLE);
        } else {
            tvWarning.setVisibility(View.GONE);
        }
    }

    private void submitRefund() {
        if (currentSale == null) return;

        List<Integer> selected = new ArrayList<>();
        for (ItemRow ir : itemRows) {
            if (!ir.isRefunded && ir.checkbox != null && ir.checkbox.isChecked()) {
                selected.add(ir.itemId);
            }
        }
        if (selected.isEmpty()) {
            toast(isArabic ? "⚠️ اختر قطعة واحدة على الأقل" : "⚠️ Select at least one item");
            return;
        }
        if ("credit".equals(selectedMethod) && !hasCustomer) {
            toast(isArabic ? "⚠️ الإرجاع كرصيد يتطلب عميلاً مسجلاً" : "⚠️ Credit refund requires registered customer");
            return;
        }

        btnConfirm.setEnabled(false);
        btnConfirm.setText(isArabic ? "جاري التنفيذ..." : "Processing...");

        try {
            JSONArray itemsArr = new JSONArray();
            for (ItemRow ir : itemRows) {
                if (ir.isRefunded || !ir.checkbox.isChecked()) continue;
                JSONObject o = new JSONObject();
                o.put("item_id", ir.itemId);
                o.put("condition", ir.condition);
                itemsArr.put(o);
            }

            final JSONObject payload = new JSONObject();
            payload.put("sale_number", currentSale.optString("sale_number", ""));
            payload.put("refund_method", selectedMethod);
            payload.put("items", itemsArr);

            new Thread(() -> {
                try {
                    MiraApiClient client = MiraApiClient.getInstance(getContext().getApplicationContext());
                    MiraApiClient.ApiResponse resp = client.processRefundV2(payload);

                    handler.post(() -> {
                        if (resp != null && resp.isSuccess) {
    JSONObject result = resp.data != null ? resp.data : new JSONObject();
    if (result.has("data")) result = result.optJSONObject("data");

    // ✅ إبطال الكاش المحلي للقطع المرتجعة — قبل استدعاء callback
    invalidateCacheForRefundedItems();

    if (callback != null) callback.onRefundSuccess(result);
    dismiss();
} else {
                            String err = (resp != null && resp.error != null) ? resp.error
                                : (isArabic ? "فشل الإرجاع" : "Refund failed");
                            toast("❌ " + err);
                            btnConfirm.setEnabled(true);
                            btnConfirm.setText(isArabic ? "تأكيد الإرجاع" : "Confirm Refund");
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "submitRefund error", e);
                    handler.post(() -> {
                        toast("⚠️ " + e.getMessage());
                        btnConfirm.setEnabled(true);
                        btnConfirm.setText(isArabic ? "تأكيد الإرجاع" : "Confirm Refund");
                    });
                }
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "submitRefund payload error", e);
            btnConfirm.setEnabled(true);
            btnConfirm.setText(isArabic ? "تأكيد الإرجاع" : "Confirm Refund");
        }
    }

    private void showState(String state) {
        if (llEmpty != null) llEmpty.setVisibility("empty".equals(state) ? View.VISIBLE : View.GONE);
        if (llLoading != null) llLoading.setVisibility("loading".equals(state) ? View.VISIBLE : View.GONE);
        if (llSale != null) llSale.setVisibility("sale".equals(state) ? View.VISIBLE : View.GONE);
    }

    private void toast(String msg) {
        if (getContext() != null) {
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
        }
    }

    private void close() {
        try {
            if (callback != null) callback.onDialogClosed();
        } catch (Exception ignored) {}
        dismiss();
    }


    // ═══════════════════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════════════════
/**
 * ✅ إبطال MiraLocalItemCache لجميع القطع المرتجعة
 * تستخدم كل المعرّفات الممكنة (epc, serial, gtin, tid, rfid_epc, item_code)
 */
private void invalidateCacheForRefundedItems() {
    try {
        Context appCtx = getContext() != null
                ? getContext().getApplicationContext() : null;
        if (appCtx == null) {
            Log.w(TAG, "invalidateCacheForRefundedItems: no context");
            return;
        }

        com.example.uhf.manager.MiraLocalItemCache cache =
                com.example.uhf.manager.MiraLocalItemCache.getInstance(appCtx);
        if (cache == null) {
            Log.w(TAG, "invalidateCacheForRefundedItems: cache is null");
            return;
        }

        int invalidated = 0;
        int itemsProcessed = 0;

        for (ItemRow ir : itemRows) {
            if (ir.isRefunded) continue;
            if (ir.checkbox == null || !ir.checkbox.isChecked()) continue;

            itemsProcessed++;

            if (!ir.epc.isEmpty()) {
                cache.markItemAsAvailable(ir.epc);
                invalidated++;
            }
            if (!ir.serialNo.isEmpty() && !ir.serialNo.equals(ir.epc)) {
                cache.markItemAsAvailable(ir.serialNo);
                invalidated++;
            }
            if (!ir.gtin.isEmpty()) {
                cache.markItemAsAvailable(ir.gtin);
                invalidated++;
            }
            if (!ir.tid.isEmpty()) {
                cache.markItemAsAvailable(ir.tid);
                invalidated++;
            }
            if (!ir.rfidEpc.isEmpty() && !ir.rfidEpc.equals(ir.epc)) {
                cache.markItemAsAvailable(ir.rfidEpc);
                invalidated++;
            }
            if (!ir.itemCode.isEmpty()) {
                cache.markItemAsAvailable(ir.itemCode);
                invalidated++;
            }
        }

        Log.i(TAG, "✅ Cache invalidated: " + invalidated
                + " identifiers for " + itemsProcessed + " refunded item(s)");

    } catch (Exception e) {
        Log.e(TAG, "invalidateCacheForRefundedItems error", e);
    }
}
    
    
    public void setQueryFromScan(String code) {
        if (etQuery == null || code == null) return;
        suppressWatcher = true;
        try {
            etQuery.setText(code);
            etQuery.setSelection(code.length());
        } finally {
            suppressWatcher = false;
        }
        handler.postDelayed(this::performSearch, 150);
    }

    /** @deprecated استخدم setQueryFromScan بدلاً منها */
    @Deprecated
    public void setRefundCode(@Nullable String code) {
        setQueryFromScan(code);
    }

    public void setOnHardwareKeyListener(@Nullable DialogInterface.OnKeyListener listener) {
        this.hardwareKeyListener = listener;
    }

    // ✅ [FIX] الدالتان المفقودتان — تستخدمهما MiraPosFragment في showRefundItemInfoInDialog / showRefundItemErrorInDialog
    public void showRefundItemInfo(@Nullable String title, @Nullable String details) {
        if (tvWarning == null) return;
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isEmpty()) sb.append("📦 ").append(title);
        if (details != null && !details.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(details);
        }
        if (sb.length() > 0) {
            tvWarning.setText(sb.toString());
            tvWarning.setTextColor(Color.parseColor("#92400E"));
            tvWarning.setVisibility(View.VISIBLE);
        }
    }

    public void showRefundItemError(@Nullable String message) {
        if (message == null) return;
        if (tvWarning != null) {
            tvWarning.setText("⚠️ " + message);
            tvWarning.setTextColor(Color.parseColor("#DC2626"));
            tvWarning.setVisibility(View.VISIBLE);
        }
        toast("⚠️ " + message);
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        try {
            int code = event.getKeyCode();
            boolean isTrigger = (code == KEYCODE_RFID_TRIGGER_1
                || code == KEYCODE_RFID_TRIGGER_2
                || code == KEYCODE_RFID_TRIGGER_3);
            if (isTrigger && hardwareKeyListener != null) {
                return hardwareKeyListener.onKey(this, code, event);
            }
        } catch (Exception e) {
            Log.e(TAG, "dispatchKeyEvent error", e);
        }
        return super.dispatchKeyEvent(event);
    }
}
