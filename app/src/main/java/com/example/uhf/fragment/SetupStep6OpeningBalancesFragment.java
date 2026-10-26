package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.utils.AccountingColors;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Step 6 — الأرصدة الافتتاحية (v2.1 — Theme-compliant + Fixed)
 *
 * ✅ v2.1 إصلاحات:
 *  - استبدال lambda ترجع قيمة بـ method reference
 *  - إزالة تكرار الكود
 *  - ألوان theme-aware عبر AccountingColors
 */
public class SetupStep6OpeningBalancesFragment extends BaseSetupStepFragment {

    private static final String TAG = "SetupStep6";
    private static final String[] KARATS = {"24K", "22K", "21K", "18K", "14K"};
    private static final double[] KARAT_FACTORS = {1.0, 0.9166, 0.875, 0.75, 0.5833};

    // ─── Views ───
    private LinearLayout cashContainer, banksContainer, goldContainer,
            trustContainer, scrapContainer, diamondsContainer,
            receivablesContainer, payablesContainer, fixedContainer;
    private TextView tvTotalAssets, tvTotalLiabilities, tvNetCapital, tvBalanceStatus;
    private MaterialButton btnBack, btnNext;
    private View pbLoading;

    // ─── Lists ───
    private final List<GenericRow> cashRows        = new ArrayList<>();
    private final List<GenericRow> banksRows       = new ArrayList<>();
    private final Map<String, GoldRow> goldRows    = new HashMap<>();
    private final List<TrustRow>  trustRows        = new ArrayList<>();
    private final List<ScrapRow>  scrapRows        = new ArrayList<>();
    private final List<GenericRow> diamondRows     = new ArrayList<>();
    private final List<GenericRow> recvRows        = new ArrayList<>();
    private final List<GenericRow> payRows         = new ArrayList<>();
    private final List<GenericRow> fixedRows       = new ArrayList<>();

    // ─── Prices ───
    private final Map<String, Double> buyPrices      = new HashMap<>();
    private final Map<String, Double> scrapDiscounts = new HashMap<>();

    private int scrapAccountId = 0;

    private MiraApiClient apiClient;
    private boolean isArabic;
    private boolean pricesReady = false;

    // ═══════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════

    @Nullable
@Override
public View onCreateView(@NonNull LayoutInflater inflater,
                         @Nullable ViewGroup viewGroup,
                         @Nullable Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.fragment_setup_step6_opening_balances, viewGroup, false);
    apiClient = MiraApiClient.getInstance(requireContext());
    isArabic = Locale.getDefault().getLanguage().equals("ar");
    initViews(view);
    setupListeners(view);   // ← ✅ التعديل هنا
    loadPricesAndAccounts();
    return view;
}

    private void initViews(View v) {
        cashContainer        = v.findViewById(R.id.cashContainer);
        banksContainer       = v.findViewById(R.id.banksContainer);
        goldContainer        = v.findViewById(R.id.goldContainer);
        trustContainer       = v.findViewById(R.id.trustContainer);
        scrapContainer       = v.findViewById(R.id.scrapContainer);
        diamondsContainer    = v.findViewById(R.id.diamondsContainer);
        receivablesContainer = v.findViewById(R.id.receivablesContainer);
        payablesContainer    = v.findViewById(R.id.payablesContainer);
        fixedContainer       = v.findViewById(R.id.fixedContainer);

        tvTotalAssets      = v.findViewById(R.id.tvTotalAssets);
        tvTotalLiabilities = v.findViewById(R.id.tvTotalLiabilities);
        tvNetCapital       = v.findViewById(R.id.tvNetCapital);
        tvBalanceStatus    = v.findViewById(R.id.tvBalanceStatus);

        btnBack   = v.findViewById(R.id.btnBack);
        btnNext   = v.findViewById(R.id.btnNext);
        pbLoading = v.findViewById(R.id.pbLoading);
    }

    
    private void setupListeners(View v) {
    btnBack.setOnClickListener(x -> requireActivity().onBackPressed());
    btnNext.setOnClickListener(x -> saveAndNext());
    bindAddButtons(v);   // ← نمرر v
}

    private void bindAddButtons(View root) {
    if (root == null) return;
    
    View b;
    if ((b = root.findViewById(R.id.btnAddCash)) != null)
        b.setOnClickListener(x -> addCashRow("", ""));
    if ((b = root.findViewById(R.id.btnAddBank)) != null)
        b.setOnClickListener(x -> addBankRow("", ""));
    if ((b = root.findViewById(R.id.btnAddTrust)) != null)
        b.setOnClickListener(x -> addTrustRow("", "21K", ""));
    if ((b = root.findViewById(R.id.btnAddScrap)) != null)
        b.setOnClickListener(x -> addScrapRow("21K", ""));
    if ((b = root.findViewById(R.id.btnAddDiamond)) != null)
        b.setOnClickListener(x -> addDiamondRow("", ""));
    if ((b = root.findViewById(R.id.btnAddReceivable)) != null)
        b.setOnClickListener(x -> addRecvRow("", ""));
    if ((b = root.findViewById(R.id.btnAddPayable)) != null)
        b.setOnClickListener(x -> addPayRow("", ""));
    if ((b = root.findViewById(R.id.btnAddFixed)) != null)
        b.setOnClickListener(x -> addFixedRow("", ""));
}

    // ═══════════════════════════════════════════════════════
    //  Load
    // ═══════════════════════════════════════════════════════

    private void loadPricesAndAccounts() {
        showLoading(true);
        new Thread(() -> {
            try {
                // 1) أسعار الذهب
                MiraApiClient.ApiResponse pricesResp = apiClient.getAccounting("setup/step/4/current");
                if (pricesResp != null && pricesResp.isSuccess && pricesResp.data != null) {
                    JSONObject payload = pricesResp.data.optJSONObject("data");
                    if (payload == null) payload = pricesResp.data;
                    JSONArray prices = payload.optJSONArray("prices");
                    if (prices != null) {
                        for (int i = 0; i < prices.length(); i++) {
                            JSONObject p = prices.optJSONObject(i);
                            if (p == null) continue;
                            String karat = p.optString("karat", "");
                            buyPrices.put(karat, p.optDouble("buy_price_per_gram", 0));
                            scrapDiscounts.put(karat, p.optDouble("scrap_discount_percent", 5));
                        }
                        pricesReady = !buyPrices.isEmpty();
                    }
                }

                // 2) حساب الكسر
                MiraApiClient.ApiResponse accResp = apiClient.getAccounting("setup/accounts-for-opening");
                if (accResp != null && accResp.isSuccess && accResp.data != null) {
                    JSONObject payload = accResp.data.optJSONObject("data");
                    if (payload == null) payload = accResp.data;
                    JSONArray accounts = payload.optJSONArray("data");
                    if (accounts == null) accounts = payload.optJSONArray("accounts");
                    if (accounts != null) {
                        for (int i = 0; i < accounts.length(); i++) {
                            JSONObject a = accounts.optJSONObject(i);
                            if (a == null) continue;
                            if ("115106".equals(a.optString("account_code", ""))) {
                                scrapAccountId = a.optInt("id", 0);
                                break;
                            }
                        }
                    }
                }

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    buildAllSections();

                    if (!pricesReady) {
                        Toast.makeText(requireContext(),
                                isArabic ? "⚠️ أسعار الذهب غير محددة — ارجع للخطوة 4"
                                        : "⚠️ Gold prices not set — return to step 4",
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "loadPricesAndAccounts", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    buildAllSections();
                });
            }
        }).start();
    }

    private void buildAllSections() {
        cashContainer.removeAllViews();
        banksContainer.removeAllViews();
        goldContainer.removeAllViews();
        trustContainer.removeAllViews();
        scrapContainer.removeAllViews();
        diamondsContainer.removeAllViews();
        receivablesContainer.removeAllViews();
        payablesContainer.removeAllViews();
        fixedContainer.removeAllViews();

        cashRows.clear();
        banksRows.clear();
        goldRows.clear();
        trustRows.clear();
        scrapRows.clear();
        diamondRows.clear();
        recvRows.clear();
        payRows.clear();
        fixedRows.clear();

        addCashRow("الصندوق الرئيسي", "");
        addBankRow("البنك الرئيسي", "");

        for (String k : KARATS) buildGoldRow(k);

        addTrustRow("", "21K", "");
        addScrapRow("21K", "");
        addDiamondRow("ألماس وأحجار كريمة", "");
        addRecvRow("", "");
        addPayRow("", "");
        addFixedRow("", "");

        updateSummary();
    }

    // ═══════════════════════════════════════════════════════
    //  Gold Rows
    // ═══════════════════════════════════════════════════════

    private void buildGoldRow(String karat) {
        View row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_setup_opening_gold_row, goldContainer, false);

        TextView tvKarat = row.findViewById(R.id.tvKarat);
        TextInputEditText etWeight = row.findViewById(R.id.etWeight);
        TextView tvValue = row.findViewById(R.id.tvValue);

        tvKarat.setText(karat);
        goldRows.put(karat, new GoldRow(karat, etWeight, tvValue));

        etWeight.addTextChangedListener(simpleWatcher(() -> {
            updateGoldValue(karat);
            updateSummary();
        }));

        goldContainer.addView(row);
    }

    private void updateGoldValue(String karat) {
        GoldRow gr = goldRows.get(karat);
        if (gr == null) return;
        double w = parseDouble(gr.etWeight);
        if (w <= 0) {
            gr.tvValue.setText("—");
            gr.tvValue.setTextColor(AccountingColors.text(requireContext(),
                    AccountingColors.Status.NEUTRAL));
            return;
        }
        double buy = buyPrices.containsKey(karat) ? buyPrices.get(karat) : 0;
        double value = w * buy;
        gr.tvValue.setText(formatMoney(value) + " YER");
        gr.tvValue.setTextColor(AccountingColors.text(requireContext(),
                value > 0 ? AccountingColors.Status.SUCCESS : AccountingColors.Status.ERROR));
    }

    // ═══════════════════════════════════════════════════════
    //  Generic Rows (Cash / Bank / Diamond / Receivable / Payable / Fixed)
    // ═══════════════════════════════════════════════════════

    private void addCashRow(String desc, String amount) {
        GenericRow row = buildGenericRow(cashContainer, desc, amount, "اسم/وصف الصندوق",
                this::updateSummary,
                () -> cashRows.removeIf(r -> r.view.getParent() == null));
        cashRows.add(row);
    }

    private void addBankRow(String desc, String amount) {
        GenericRow row = buildGenericRow(banksContainer, desc, amount, "اسم البنك",
                this::updateSummary,
                () -> banksRows.removeIf(r -> r.view.getParent() == null));
        banksRows.add(row);
    }

    private void addDiamondRow(String desc, String amount) {
        GenericRow row = buildGenericRow(diamondsContainer, desc, amount, "وصف",
                this::updateSummary,
                () -> diamondRows.removeIf(r -> r.view.getParent() == null));
        diamondRows.add(row);
    }

    private void addRecvRow(String desc, String amount) {
        GenericRow row = buildGenericRow(receivablesContainer, desc, amount, "اسم العميل",
                this::updateSummary,
                () -> recvRows.removeIf(r -> r.view.getParent() == null));
        recvRows.add(row);
    }

    private void addPayRow(String desc, String amount) {
        GenericRow row = buildGenericRow(payablesContainer, desc, amount, "اسم المورد",
                this::updateSummary,
                () -> payRows.removeIf(r -> r.view.getParent() == null));
        payRows.add(row);
    }

    private void addFixedRow(String desc, String amount) {
        GenericRow row = buildGenericRow(fixedContainer, desc, amount, "اسم الأصل",
                this::updateSummary,
                () -> fixedRows.removeIf(r -> r.view.getParent() == null));
        fixedRows.add(row);
    }

    private GenericRow buildGenericRow(LinearLayout parent, String desc, String amount,
                                        String hint, Runnable onChanged, Runnable onRemoved) {
        View row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_setup_opening_generic_row, parent, false);

        TextInputEditText etDesc = row.findViewById(R.id.etDesc);
        TextInputEditText etAmount = row.findViewById(R.id.etAmount);
        ImageButton btnRemove = row.findViewById(R.id.btnRemove);

        etDesc.setHint(hint);
        etDesc.setText(desc);
        etAmount.setText(amount);

        TextWatcher w = simpleWatcher(onChanged);
        etDesc.addTextChangedListener(w);
        etAmount.addTextChangedListener(w);

        btnRemove.setOnClickListener(v -> {
            parent.removeView(row);
            onRemoved.run();
            updateSummary();
        });

        parent.addView(row);
        return new GenericRow(row, etDesc, etAmount);
    }

    // ═══════════════════════════════════════════════════════
    //  Trust Gold Rows
    // ═══════════════════════════════════════════════════════

    private void addTrustRow(String desc, String karat, String weight) {
        View row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_setup_opening_trust_row, trustContainer, false);

        TextInputEditText etDesc = row.findViewById(R.id.etDesc);
        AutoCompleteTextView ddKarat = row.findViewById(R.id.ddKarat);
        TextInputEditText etWeight = row.findViewById(R.id.etWeight);
        TextView tvValue = row.findViewById(R.id.tvValue);
        ImageButton btnRemove = row.findViewById(R.id.btnRemove);

        ArrayAdapter<String> ad = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_dropdown_item_1line, KARATS);
        ddKarat.setAdapter(ad);
        ddKarat.setText(karat != null && !karat.isEmpty() ? karat : "21K", false);

        etDesc.setText(desc);
        etWeight.setText(weight);

        TrustRow tr = new TrustRow(row, etDesc, ddKarat, etWeight, tvValue);
        trustRows.add(tr);

        TextWatcher w = simpleWatcher(() -> { updateTrustValue(tr); updateSummary(); });
        etWeight.addTextChangedListener(w);
        ddKarat.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                updateTrustValue(tr);
                updateSummary();
            }
        });

        btnRemove.setOnClickListener(v -> {
            trustContainer.removeView(row);
            trustRows.remove(tr);
            updateSummary();
        });

        trustContainer.addView(row);
    }

    private void updateTrustValue(TrustRow tr) {
        double w = parseDouble(tr.etWeight);
        String karat = tr.ddKarat.getText().toString();
        double buy = buyPrices.containsKey(karat) ? buyPrices.get(karat) : 0;
        double value = w * buy;
        tr.tvValue.setText(w > 0 ? formatMoney(value) + " YER" : "—");
        tr.tvValue.setTextColor(AccountingColors.text(requireContext(),
                value > 0 ? AccountingColors.Status.WARNING : AccountingColors.Status.NEUTRAL));
    }

    // ═══════════════════════════════════════════════════════
    //  Scrap Rows
    // ═══════════════════════════════════════════════════════

    private void addScrapRow(String karat, String weight) {
        View row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_setup_opening_scrap_row, scrapContainer, false);

        AutoCompleteTextView ddKarat = row.findViewById(R.id.ddKarat);
        TextInputEditText etWeight = row.findViewById(R.id.etWeight);
        TextView tvValue = row.findViewById(R.id.tvValue);
        ImageButton btnRemove = row.findViewById(R.id.btnRemove);

        ArrayAdapter<String> ad = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_dropdown_item_1line, KARATS);
        ddKarat.setAdapter(ad);
        ddKarat.setText(karat != null && !karat.isEmpty() ? karat : "21K", false);
        etWeight.setText(weight);

        ScrapRow sr = new ScrapRow(row, ddKarat, etWeight, tvValue);
        scrapRows.add(sr);

        TextWatcher w = simpleWatcher(() -> { updateScrapValue(sr); updateSummary(); });
        etWeight.addTextChangedListener(w);
        ddKarat.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                updateScrapValue(sr);
                updateSummary();
            }
        });

        btnRemove.setOnClickListener(v -> {
            scrapContainer.removeView(row);
            scrapRows.remove(sr);
            updateSummary();
        });

        scrapContainer.addView(row);
    }

    private void updateScrapValue(ScrapRow sr) {
        double physical = parseDouble(sr.etWeight);
        String karat = sr.ddKarat.getText().toString();
        int idx = karatIndex(karat);
        double factor = idx >= 0 ? KARAT_FACTORS[idx] : 0.875;
        double w24k = physical * factor;
        double buy24k = buyPrices.containsKey("24K") ? buyPrices.get("24K") : 0;
        double disc = scrapDiscounts.containsKey(karat) ? scrapDiscounts.get(karat) : 5.0;
        double value = w24k * buy24k * (1 - disc / 100.0);
        sr.tvValue.setText(physical > 0 ? formatMoney(value) + " YER" : "—");
        sr.tvValue.setTextColor(AccountingColors.text(requireContext(),
                value > 0 ? AccountingColors.Status.INFO : AccountingColors.Status.NEUTRAL));
    }

    // ═══════════════════════════════════════════════════════
    //  Summary
    // ═══════════════════════════════════════════════════════

    private void updateSummary() {
        double totalAssets = 0;
        double totalLiabilities = 0;

        for (GenericRow r : cashRows) totalAssets += parseDouble(r.etAmount);
        for (GenericRow r : banksRows) totalAssets += parseDouble(r.etAmount);
        for (Map.Entry<String, GoldRow> e : goldRows.entrySet()) {
            double w = parseDouble(e.getValue().etWeight);
            double buy = buyPrices.containsKey(e.getKey()) ? buyPrices.get(e.getKey()) : 0;
            totalAssets += w * buy;
        }
        for (GenericRow r : diamondRows) totalAssets += parseDouble(r.etAmount);
        for (GenericRow r : recvRows) totalAssets += parseDouble(r.etAmount);
        for (GenericRow r : fixedRows) totalAssets += parseDouble(r.etAmount);

        for (TrustRow tr : trustRows) {
            double w = parseDouble(tr.etWeight);
            String karat = tr.ddKarat.getText().toString();
            double buy = buyPrices.containsKey(karat) ? buyPrices.get(karat) : 0;
            totalLiabilities += w * buy;
        }
        for (GenericRow r : payRows) totalLiabilities += parseDouble(r.etAmount);

        for (ScrapRow sr : scrapRows) {
            double physical = parseDouble(sr.etWeight);
            String karat = sr.ddKarat.getText().toString();
            int idx = karatIndex(karat);
            double factor = idx >= 0 ? KARAT_FACTORS[idx] : 0.875;
            double w24k = physical * factor;
            double buy24k = buyPrices.containsKey("24K") ? buyPrices.get("24K") : 0;
            double disc = scrapDiscounts.containsKey(karat) ? scrapDiscounts.get(karat) : 5.0;
            totalAssets += w24k * buy24k * (1 - disc / 100.0);
        }

        double net = totalAssets - totalLiabilities;

        tvTotalAssets.setText(formatMoney(totalAssets) + " YER");
        tvTotalLiabilities.setText(formatMoney(totalLiabilities) + " YER");
        tvNetCapital.setText(formatMoney(net) + " YER");

        if (Math.abs(net) < 1) {
            tvBalanceStatus.setText(isArabic ? "⚠️ لا أرصدة مدخلة" : "⚠️ No balances");
            tvBalanceStatus.setTextColor(AccountingColors.text(requireContext(),
                    AccountingColors.Status.NEUTRAL));
        } else if (net > 0) {
            tvBalanceStatus.setText(isArabic ? "✅ جاهز للاعتماد (رأس مال موجب)"
                                            : "✅ Ready (positive capital)");
            tvBalanceStatus.setTextColor(AccountingColors.text(requireContext(),
                    AccountingColors.Status.SUCCESS));
        } else {
            tvBalanceStatus.setText(isArabic ? "⚠️ الالتزامات > الأصول"
                                            : "⚠️ Liabilities > Assets");
            tvBalanceStatus.setTextColor(AccountingColors.text(requireContext(),
                    AccountingColors.Status.ERROR));
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Save
    // ═══════════════════════════════════════════════════════

    private void saveAndNext() {
        try {
            JSONArray balances = new JSONArray();

            for (GenericRow r : cashRows) {
                double amt = parseDouble(r.etAmount);
                if (amt <= 0) continue;
                JSONObject o = new JSONObject();
                o.put("type", "cash");
                o.put("amount_money", amt);
                o.put("currency", "YER");
                o.put("description", safeStr(r.etDesc));
                balances.put(o);
            }

            for (GenericRow r : banksRows) {
                double amt = parseDouble(r.etAmount);
                if (amt <= 0) continue;
                JSONObject o = new JSONObject();
                o.put("type", "bank");
                o.put("amount_money", amt);
                o.put("currency", "YER");
                o.put("description", safeStr(r.etDesc));
                balances.put(o);
            }

            for (Map.Entry<String, GoldRow> e : goldRows.entrySet()) {
                double w = parseDouble(e.getValue().etWeight);
                if (w <= 0) continue;
                String karat = e.getKey();
                double buy = buyPrices.containsKey(karat) ? buyPrices.get(karat) : 0;
                JSONObject o = new JSONObject();
                o.put("type", "gold");
                o.put("karat", karat);
                o.put("weight_grams", w);
                o.put("amount_money", w * buy);
                o.put("currency", "YER");
                o.put("ownership_type", "owned");
                o.put("description", "ذهب " + karat + " مملوك");
                balances.put(o);
            }

            for (TrustRow tr : trustRows) {
                double w = parseDouble(tr.etWeight);
                if (w <= 0) continue;
                String karat = tr.ddKarat.getText().toString();
                double buy = buyPrices.containsKey(karat) ? buyPrices.get(karat) : 0;
                JSONObject o = new JSONObject();
                o.put("type", "gold");
                o.put("karat", karat);
                o.put("weight_grams", w);
                o.put("amount_money", w * buy);
                o.put("currency", "YER");
                o.put("ownership_type", "trust");
                o.put("description", safeStr(tr.etDesc));
                balances.put(o);
            }

            for (ScrapRow sr : scrapRows) {
                double physical = parseDouble(sr.etWeight);
                if (physical <= 0) continue;
                String karat = sr.ddKarat.getText().toString();
                int idx = karatIndex(karat);
                double factor = idx >= 0 ? KARAT_FACTORS[idx] : 0.875;
                double w24k = physical * factor;
                double buy24k = buyPrices.containsKey("24K") ? buyPrices.get("24K") : 0;
                double disc = scrapDiscounts.containsKey(karat) ? scrapDiscounts.get(karat) : 5.0;
                double value = w24k * buy24k * (1 - disc / 100.0);

                JSONObject o = new JSONObject();
                o.put("type", "scrap");
                o.put("karat", karat);
                o.put("weight_grams", physical);
                o.put("amount_money", value);
                o.put("currency", "YER");
                if (scrapAccountId > 0) o.put("account_id", scrapAccountId);
                o.put("description", "كسر " + karat + " للتصفية");
                balances.put(o);
            }

            for (GenericRow r : diamondRows) {
                double amt = parseDouble(r.etAmount);
                if (amt <= 0) continue;
                JSONObject o = new JSONObject();
                o.put("type", "diamond");
                o.put("amount_money", amt);
                o.put("currency", "YER");
                o.put("description", safeStr(r.etDesc));
                balances.put(o);
            }

            for (GenericRow r : recvRows) {
                double amt = parseDouble(r.etAmount);
                if (amt <= 0) continue;
                JSONObject o = new JSONObject();
                o.put("type", "receivable");
                o.put("amount_money", amt);
                o.put("currency", "YER");
                o.put("description", safeStr(r.etDesc));
                balances.put(o);
            }

            for (GenericRow r : payRows) {
                double amt = parseDouble(r.etAmount);
                if (amt <= 0) continue;
                JSONObject o = new JSONObject();
                o.put("type", "payable");
                o.put("amount_money", amt);
                o.put("currency", "YER");
                o.put("description", safeStr(r.etDesc));
                balances.put(o);
            }

            for (GenericRow r : fixedRows) {
                double amt = parseDouble(r.etAmount);
                if (amt <= 0) continue;
                JSONObject o = new JSONObject();
                o.put("type", "fixed_asset");
                o.put("amount_money", amt);
                o.put("currency", "YER");
                o.put("description", safeStr(r.etDesc));
                balances.put(o);
            }

            if (balances.length() == 0) {
                Toast.makeText(requireContext(),
                        isArabic ? "أدخل رصيداً واحداً على الأقل" : "Enter at least one balance",
                        Toast.LENGTH_LONG).show();
                return;
            }

            JSONObject payload = new JSONObject();
            payload.put("balances", balances);

            btnNext.setEnabled(false);
            btnNext.setText(isArabic ? "جاري الحفظ..." : "Saving...");
            showLoading(true);

            new Thread(() -> {
                try {
                    MiraApiClient.ApiResponse resp = apiClient.postAccounting("setup/step/6", payload);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (!isAdded()) return;
                        showLoading(false);
                        btnNext.setEnabled(true);
                        btnNext.setText(isArabic ? "التالي ←" : "Next →");

                        if (resp != null && resp.isSuccess) {
    Toast.makeText(requireContext(),
            isArabic ? "✅ تم حفظ الأرصدة" : "✅ Balances saved",
            Toast.LENGTH_SHORT).show();

    // ✅ الانتقال للخطوة 7 (المستخدمون والصلاحيات)
    SetupNavigator.goToNextStep(SetupStep6OpeningBalancesFragment.this, 6);
} else {
    String err = (resp != null && resp.error != null) ? resp.error : "unknown";
    Toast.makeText(requireContext(), "❌ " + err, Toast.LENGTH_LONG).show();
}
                    });
                } catch (Exception ex) {
                    Log.e(TAG, "save", ex);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (!isAdded()) return;
                        showLoading(false);
                        btnNext.setEnabled(true);
                        btnNext.setText(isArabic ? "التالي ←" : "Next →");
                        Toast.makeText(requireContext(), "❌ " + ex.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "buildPayload", e);
            Toast.makeText(requireContext(), "❌ " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Utils
    // ═══════════════════════════════════════════════════════

    private void showLoading(boolean show) {
        if (pbLoading != null) pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private TextWatcher simpleWatcher(Runnable onChanged) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { onChanged.run(); }
        };
    }

    private double parseDouble(TextInputEditText et) {
        try {
            if (et == null || et.getText() == null) return 0;
            String s = et.getText().toString().trim().replace(",", "");
            return s.isEmpty() ? 0 : Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private String safeStr(TextInputEditText et) {
        if (et == null || et.getText() == null) return "";
        return et.getText().toString().trim();
    }

    private String formatMoney(double v) {
        try {
            NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
            nf.setMinimumFractionDigits(2);
            nf.setMaximumFractionDigits(2);
            return nf.format(v);
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    private int karatIndex(String karat) {
        for (int i = 0; i < KARATS.length; i++) if (KARATS[i].equals(karat)) return i;
        return -1;
    }

    // ═══════════════════════════════════════════════════════
    //  Row holders
    // ═══════════════════════════════════════════════════════

    private static class GenericRow {
        final View view;
        final TextInputEditText etDesc, etAmount;
        GenericRow(View v, TextInputEditText d, TextInputEditText a) {
            view = v; etDesc = d; etAmount = a;
        }
    }

    private static class GoldRow {
        final String karat;
        final TextInputEditText etWeight;
        final TextView tvValue;
        GoldRow(String k, TextInputEditText w, TextView v) {
            karat = k; etWeight = w; tvValue = v;
        }
    }

    private static class TrustRow {
        final View view;
        final TextInputEditText etDesc, etWeight;
        final AutoCompleteTextView ddKarat;
        final TextView tvValue;
        TrustRow(View v, TextInputEditText d, AutoCompleteTextView k,
                 TextInputEditText w, TextView val) {
            view = v; etDesc = d; ddKarat = k; etWeight = w; tvValue = val;
        }
    }

    private static class ScrapRow {
        final View view;
        final TextInputEditText etWeight;
        final AutoCompleteTextView ddKarat;
        final TextView tvValue;
        ScrapRow(View v, AutoCompleteTextView k, TextInputEditText w, TextView val) {
            view = v; ddKarat = k; etWeight = w; tvValue = val;
        }
    }
}
