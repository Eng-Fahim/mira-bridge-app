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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Step 4 — أسعار الذهب لكل عيار (v2.1 — Theme-compliant + Navigation Fix)
 *
 * ✅ v2.1:
 *  - التنقل عبر SetupNavigator (لا popBackStack)
 *  - ينتقل مباشرة للخطوة 5 (أجور الصياغة)
 *  - null-safety للأزرار
 */
public class SetupStep4GoldPricesFragment extends BaseSetupStepFragment {

    private static final String TAG = "SetupStep4";
    private static final String[] KARATS = {"24K", "22K", "21K", "18K", "14K"};

    private final Map<String, KaratRow> rows = new LinkedHashMap<>();

    private LinearLayout container;
    private MaterialButton btnBack, btnNext;
    private View pbLoading;
    private TextView tvSummary;

    private MiraApiClient apiClient;
    private boolean isArabic;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup viewGroup,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_setup_step4_gold_prices, viewGroup, false);
        apiClient = MiraApiClient.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        initViews(view);
        setupListeners();
        buildKaratCards();
        loadCurrentPrices();
        return view;
    }

    private void initViews(View v) {
        container   = v.findViewById(R.id.karatContainer);
        btnBack     = v.findViewById(R.id.btnBack);
        btnNext     = v.findViewById(R.id.btnNext);
        pbLoading   = v.findViewById(R.id.pbLoading);
        tvSummary   = v.findViewById(R.id.tvSummary);
    }

    private void setupListeners() {
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> requireActivity().onBackPressed());
        }
        if (btnNext != null) {
            btnNext.setOnClickListener(v -> saveAndNext());
        }
    }

    private void buildKaratCards() {
        if (container == null) return;
        container.removeAllViews();
        rows.clear();

        LayoutInflater inf = LayoutInflater.from(requireContext());
        for (String karat : KARATS) {
            View card = inf.inflate(R.layout.item_setup_karat_price, container, false);

            TextView tvKarat            = card.findViewById(R.id.tvKarat);
            TextInputEditText etBuy     = card.findViewById(R.id.etBuyPrice);
            TextInputEditText etSell    = card.findViewById(R.id.etSellPrice);
            TextInputEditText etDisc    = card.findViewById(R.id.etScrapDiscount);
            TextView tvSpread           = card.findViewById(R.id.tvSpread);

            tvKarat.setText(karat);

            KaratRow row = new KaratRow(karat, etBuy, etSell, etDisc, tvSpread);
            rows.put(karat, row);

            etDisc.setText("5");

            TextWatcher watcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    row.updateSpread();
                    updateSummary();
                }
            };
            etBuy.addTextChangedListener(watcher);
            etSell.addTextChangedListener(watcher);
            etDisc.addTextChangedListener(watcher);

            container.addView(card);
        }
    }

    private void loadCurrentPrices() {
        showLoading(true);
        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse resp = apiClient.getAccounting("setup/step/4/current");
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);

                    if (resp != null && resp.isSuccess && resp.data != null) {
                        JSONObject payload = resp.data.optJSONObject("data");
                        if (payload == null) payload = resp.data;

                        JSONArray prices = payload.optJSONArray("prices");
                        if (prices != null && prices.length() > 0) {
                            populateRows(prices);
                        }
                    }
                    updateSummary();
                });
            } catch (Exception e) {
                Log.e(TAG, "loadCurrentPrices", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    updateSummary();
                });
            }
        }).start();
    }

    private void populateRows(JSONArray prices) {
        for (int i = 0; i < prices.length(); i++) {
            JSONObject p = prices.optJSONObject(i);
            if (p == null) continue;

            String karat = p.optString("karat", "");
            KaratRow row = rows.get(karat);
            if (row == null) continue;

            row.etBuy.setText(fmtInput(p.optDouble("buy_price_per_gram", 0)));
            row.etSell.setText(fmtInput(p.optDouble("sell_price_per_gram", 0)));
            row.etDisc.setText(fmtInput(p.optDouble("scrap_discount_percent", 5)));
            row.updateSpread();
        }
    }

    private void saveAndNext() {
        Map<String, double[]> collected = new LinkedHashMap<>();

        for (KaratRow row : rows.values()) {
            double buy  = parseDouble(row.etBuy);
            double sell = parseDouble(row.etSell);
            double disc = parseDouble(row.etDisc);

            if (buy <= 0 && sell <= 0) continue;

            if (buy <= 0 || sell <= 0) {
                Toast.makeText(requireContext(),
                        isArabic ? "أدخل السعرين معاً لعيار " + row.karat
                                 : "Enter both prices for " + row.karat,
                        Toast.LENGTH_LONG).show();
                return;
            }

            if (sell < buy) {
                Toast.makeText(requireContext(),
                        isArabic ? "سعر البيع أقل من الشراء لعيار " + row.karat
                                 : "Sell < Buy for " + row.karat,
                        Toast.LENGTH_LONG).show();
                return;
            }

            if (disc < 0 || disc > 50) {
                Toast.makeText(requireContext(),
                        isArabic ? "خصم الكسر يجب أن يكون 0-50% لعيار " + row.karat
                                 : "Discount must be 0-50% for " + row.karat,
                        Toast.LENGTH_LONG).show();
                return;
            }

            collected.put(row.karat, new double[]{buy, sell, disc});
        }

        if (collected.isEmpty()) {
            Toast.makeText(requireContext(),
                    isArabic ? "أدخل سعراً واحداً على الأقل" : "Enter at least one price",
                    Toast.LENGTH_LONG).show();
            return;
        }

        try {
            JSONArray pricesArray = new JSONArray();
            for (Map.Entry<String, double[]> e : collected.entrySet()) {
                JSONObject p = new JSONObject();
                p.put("karat", e.getKey());
                p.put("buy_price_per_gram", e.getValue()[0]);
                p.put("sell_price_per_gram", e.getValue()[1]);
                p.put("scrap_discount_percent", e.getValue()[2]);
                p.put("currency", "YER");
                pricesArray.put(p);
            }

            JSONObject payload = new JSONObject();
            payload.put("prices", pricesArray);
            payload.put("branch_id", 1);
            payload.put("source", "manual");

            if (btnNext != null) {
                btnNext.setEnabled(false);
                btnNext.setText(isArabic ? "جاري الحفظ..." : "Saving...");
            }
            showLoading(true);

            new Thread(() -> {
                try {
                    MiraApiClient.ApiResponse resp = apiClient.postAccounting("setup/step/4", payload);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (!isAdded()) return;
                        showLoading(false);

                        if (btnNext != null) {
                            btnNext.setEnabled(true);
                            btnNext.setText(isArabic ? "التالي ←" : "Next →");
                        }

                        if (resp != null && resp.isSuccess) {
                            Toast.makeText(requireContext(),
                                    isArabic ? "✅ تم حفظ أسعار الذهب" : "✅ Gold prices saved",
                                    Toast.LENGTH_SHORT).show();

                            // ═══════════════════════════════════════════════
                            // ✅ الانتقال للخطوة 5 (أجور الصياغة)
                            // ═══════════════════════════════════════════════
                            SetupNavigator.goToNextStep(SetupStep4GoldPricesFragment.this, 4);
                        } else {
                            String err = (resp != null && resp.error != null) ? resp.error : "unknown";
                            Toast.makeText(requireContext(), "❌ " + err, Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception ex) {
                    Log.e(TAG, "saveAndNext", ex);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (!isAdded()) return;
                        showLoading(false);
                        if (btnNext != null) {
                            btnNext.setEnabled(true);
                            btnNext.setText(isArabic ? "التالي ←" : "Next →");
                        }
                        Toast.makeText(requireContext(), "❌ " + ex.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "buildPayload", e);
            Toast.makeText(requireContext(), "❌ " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateSummary() {
        if (tvSummary == null) return;

        int filled = 0;
        double sumSpreadPct = 0;

        for (KaratRow row : rows.values()) {
            double buy  = parseDouble(row.etBuy);
            double sell = parseDouble(row.etSell);
            if (buy > 0 && sell > 0) {
                filled++;
                sumSpreadPct += ((sell - buy) / buy) * 100;
            }
        }

        double avg = filled > 0 ? sumSpreadPct / filled : 0;
        tvSummary.setText(String.format(Locale.US,
                isArabic ? "%d من 5 عيارات مُعبأة — متوسط الهامش %.1f%%"
                        : "%d of 5 karats filled — avg margin %.1f%%",
                filled, avg));
    }

    private void showLoading(boolean show) {
        if (pbLoading != null) pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        if (container != null) container.setAlpha(show ? 0.5f : 1.0f);
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

    private String fmtInput(double v) {
        if (v == 0) return "";
        if (v == Math.floor(v)) return String.format(Locale.US, "%.0f", v);
        return String.format(Locale.US, "%.2f", v);
    }

    private class KaratRow {
        final String karat;
        final TextInputEditText etBuy, etSell, etDisc;
        final TextView tvSpread;

        KaratRow(String karat, TextInputEditText etBuy, TextInputEditText etSell,
                 TextInputEditText etDisc, TextView tvSpread) {
            this.karat    = karat;
            this.etBuy    = etBuy;
            this.etSell   = etSell;
            this.etDisc   = etDisc;
            this.tvSpread = tvSpread;
        }

        void updateSpread() {
            double buy  = parseDouble(etBuy);
            double sell = parseDouble(etSell);
            if (buy <= 0 || sell <= 0) {
                tvSpread.setText("—");
                tvSpread.setTextColor(AccountingColors.text(requireContext(),
                        AccountingColors.Status.NEUTRAL));
                return;
            }
            double spread = sell - buy;
            double pct = (spread / buy) * 100;
            tvSpread.setText(String.format(Locale.US, "+%,.0f (%.1f%%)", spread, pct));
            tvSpread.setTextColor(AccountingColors.text(requireContext(),
                    spread >= 0 ? AccountingColors.Status.SUCCESS
                                : AccountingColors.Status.ERROR));
        }
    }
}
