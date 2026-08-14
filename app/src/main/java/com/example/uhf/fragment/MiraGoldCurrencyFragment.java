package com.example.uhf.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.MiraSettingsManager;

import org.json.JSONObject;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MIRA Gold & Currency Settings Fragment
 * واجهة متكاملة لإدارة أسعار الذهب والبورصة والعملات
 */
public class MiraGoldCurrencyFragment extends Fragment {

    private static final String TAG = "MiraGoldCurrency";
    private static final String PREFS_NAME = "MIRA_GOLD_SETTINGS";

    private UHFMainActivity mContext;
    private MiraSettingsManager settingsManager;
    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final DecimalFormat decimalFormat = new DecimalFormat("#,##0.00");
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    // ============ Gold Views ============
    private TextView tvGoldPriceStatus, tvLastUpdate;
    private EditText etGoldPrice24k, etGoldPrice22k, etGoldPrice21k, etGoldPrice18k, etGoldPrice14k;
    private EditText etMargin24k, etMargin22k, etMargin21k, etMargin18k, etMargin14k, etMargin12k;
    private EditText etMaking24k, etMaking22k, etMaking21k, etMaking18k, etMaking14k, etMaking12k;
    private Spinner spGoldApiProvider, spGoldCurrency;
    private EditText etGoldApiKey;
    private Button btnFetchGoldPrice, btnSaveGoldSettings;
    private ProgressBar pbGoldLoading;

    // ============ Currency Views ============
    private Spinner spDefaultCurrency, spChartCurrency;
    private EditText etExchangeYER, etExchangeEGP, etExchangeSAR, etExchangeAED;
    private EditText etExchangeMarketYER, etExchangeMarketEGP, etExchangeMarketSAR, etExchangeMarketAED;
    private Button btnSaveCurrencySettings, btnRefreshRates;
    private ProgressBar pbCurrencyLoading;

    // ============ Live Prices ============
    private TextView tvLiveGold24k, tvLiveGold22k, tvLiveGold21k, tvLiveGold18k;
    private TextView tvLiveUSD_YER, tvLiveUSD_EGP, tvLiveUSD_SAR, tvLiveUSD_AED;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_gold_currency, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        if (getActivity() instanceof UHFMainActivity) {
            mContext = (UHFMainActivity) getActivity();
            settingsManager = MiraSettingsManager.getInstance(mContext);
            prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }

        initViews(view);
        setupSpinners();
        setupListeners();
        loadLocalSettings();
        loadServerSettings();
    }

    private void initViews(View view) {
        // Gold Views
        tvGoldPriceStatus = view.findViewById(R.id.tvGoldPriceStatus);
        tvLastUpdate = view.findViewById(R.id.tvLastUpdate);
        
        etGoldPrice24k = view.findViewById(R.id.etGoldPrice24k);
        etGoldPrice22k = view.findViewById(R.id.etGoldPrice22k);
        etGoldPrice21k = view.findViewById(R.id.etGoldPrice21k);
        etGoldPrice18k = view.findViewById(R.id.etGoldPrice18k);
        etGoldPrice14k = view.findViewById(R.id.etGoldPrice14k);
        
        etMargin24k = view.findViewById(R.id.etMargin24k);
        etMargin22k = view.findViewById(R.id.etMargin22k);
        etMargin21k = view.findViewById(R.id.etMargin21k);
        etMargin18k = view.findViewById(R.id.etMargin18k);
        etMargin14k = view.findViewById(R.id.etMargin14k);
        etMargin12k = view.findViewById(R.id.etMargin12k);
        
        etMaking24k = view.findViewById(R.id.etMaking24k);
        etMaking22k = view.findViewById(R.id.etMaking22k);
        etMaking21k = view.findViewById(R.id.etMaking21k);
        etMaking18k = view.findViewById(R.id.etMaking18k);
        etMaking14k = view.findViewById(R.id.etMaking14k);
        etMaking12k = view.findViewById(R.id.etMaking12k);
        
        spGoldApiProvider = view.findViewById(R.id.spGoldApiProvider);
        spGoldCurrency = view.findViewById(R.id.spGoldCurrency);
        etGoldApiKey = view.findViewById(R.id.etGoldApiKey);
        btnFetchGoldPrice = view.findViewById(R.id.btnFetchGoldPrice);
        btnSaveGoldSettings = view.findViewById(R.id.btnSaveGoldSettings);
        pbGoldLoading = view.findViewById(R.id.pbGoldLoading);

        // Currency Views
        spDefaultCurrency = view.findViewById(R.id.spDefaultCurrency);
        spChartCurrency = view.findViewById(R.id.spChartCurrency);
        
        etExchangeYER = view.findViewById(R.id.etExchangeYER);
        etExchangeEGP = view.findViewById(R.id.etExchangeEGP);
        etExchangeSAR = view.findViewById(R.id.etExchangeSAR);
        etExchangeAED = view.findViewById(R.id.etExchangeAED);
        
        etExchangeMarketYER = view.findViewById(R.id.etExchangeMarketYER);
        etExchangeMarketEGP = view.findViewById(R.id.etExchangeMarketEGP);
        etExchangeMarketSAR = view.findViewById(R.id.etExchangeMarketSAR);
        etExchangeMarketAED = view.findViewById(R.id.etExchangeMarketAED);
        
        btnSaveCurrencySettings = view.findViewById(R.id.btnSaveCurrencySettings);
        btnRefreshRates = view.findViewById(R.id.btnRefreshRates);
        pbCurrencyLoading = view.findViewById(R.id.pbCurrencyLoading);

        // Live Prices
        tvLiveGold24k = view.findViewById(R.id.tvLiveGold24k);
        tvLiveGold22k = view.findViewById(R.id.tvLiveGold22k);
        tvLiveGold21k = view.findViewById(R.id.tvLiveGold21k);
        tvLiveGold18k = view.findViewById(R.id.tvLiveGold18k);
        
        tvLiveUSD_YER = view.findViewById(R.id.tvLiveUSD_YER);
        tvLiveUSD_EGP = view.findViewById(R.id.tvLiveUSD_EGP);
        tvLiveUSD_SAR = view.findViewById(R.id.tvLiveUSD_SAR);
        tvLiveUSD_AED = view.findViewById(R.id.tvLiveUSD_AED);
    }

    private void setupSpinners() {
        String[] providers = {"metalprice", "goldapi", "metalsdev"};
        ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(mContext, 
            android.R.layout.simple_spinner_item, providers);
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spGoldApiProvider.setAdapter(providerAdapter);

        String[] currencies = {"USD", "YER", "EGP", "SAR", "AED"};
        ArrayAdapter<String> currencyAdapter = new ArrayAdapter<>(mContext, 
            android.R.layout.simple_spinner_item, currencies);
        currencyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spGoldCurrency.setAdapter(currencyAdapter);
        spDefaultCurrency.setAdapter(currencyAdapter);
        spChartCurrency.setAdapter(currencyAdapter);
    }

    private void setupListeners() {
        btnFetchGoldPrice.setOnClickListener(v -> fetchGoldPricesFromApi());
        btnSaveGoldSettings.setOnClickListener(v -> saveGoldSettings());
        btnSaveCurrencySettings.setOnClickListener(v -> saveCurrencySettings());
        btnRefreshRates.setOnClickListener(v -> updateLivePrices());
    }

    // ============================================
    // 💰 جلب أسعار الذهب من API
    // ============================================
    private void fetchGoldPricesFromApi() {
        String provider = spGoldApiProvider.getSelectedItem().toString();
        String apiKey = getText(etGoldApiKey);
        
        if (apiKey.isEmpty()) {
            Toast.makeText(mContext, "⚠️ أدخل مفتاح API أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        pbGoldLoading.setVisibility(View.VISIBLE);
        btnFetchGoldPrice.setEnabled(false);
        tvGoldPriceStatus.setText("⏳ جاري جلب الأسعار...");
        
        executor.execute(() -> {
            try {
                com.example.uhf.api.MiraApiClient client = com.example.uhf.api.MiraApiClient.getInstance(mContext);
                com.example.uhf.api.MiraApiClient.ApiResponse response = client.fetchGoldPrice(provider, apiKey);
                
                if (response.isSuccess && response.data != null) {
                    JSONObject data = response.data.optJSONObject("data");
                    if (data == null) data = response.data;
                    
                    JSONObject prices = data.optJSONObject("prices");
                    if (prices == null) prices = data;
                    
                    mainHandler.post(() -> {
                        if (prices.has("24k")) etGoldPrice24k.setText(decimalFormat.format(prices.optDouble("24k")));
                        if (prices.has("22k")) etGoldPrice22k.setText(decimalFormat.format(prices.optDouble("22k")));
                        if (prices.has("21k")) etGoldPrice21k.setText(decimalFormat.format(prices.optDouble("21k")));
                        if (prices.has("18k")) etGoldPrice18k.setText(decimalFormat.format(prices.optDouble("18k")));
                        if (prices.has("14k")) etGoldPrice14k.setText(decimalFormat.format(prices.optDouble("14k")));
                        
                        pbGoldLoading.setVisibility(View.GONE);
                        btnFetchGoldPrice.setEnabled(true);
                        tvGoldPriceStatus.setText("✅ تم تحديث الأسعار");
                        tvLastUpdate.setText("آخر تحديث: " + dateFormat.format(new Date()));
                        updateLivePrices();
                        
                        Toast.makeText(mContext, "✅ تم جلب أسعار الذهب بنجاح", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    mainHandler.post(() -> {
                        pbGoldLoading.setVisibility(View.GONE);
                        btnFetchGoldPrice.setEnabled(true);
                        tvGoldPriceStatus.setText("❌ فشل جلب الأسعار");
                        Toast.makeText(mContext, "❌ فشل جلب الأسعار", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching gold prices: " + e.getMessage());
                mainHandler.post(() -> {
                    pbGoldLoading.setVisibility(View.GONE);
                    btnFetchGoldPrice.setEnabled(true);
                    tvGoldPriceStatus.setText("❌ خطأ في الاتصال");
                    Toast.makeText(mContext, "❌ " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // ============================================
    // 💾 حفظ إعدادات الذهب
    // ============================================
    private void saveGoldSettings() {
        pbGoldLoading.setVisibility(View.VISIBLE);
        btnSaveGoldSettings.setEnabled(false);
        
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                
                payload.put("gold_price_24k", getDouble(etGoldPrice24k));
                payload.put("gold_price_22k", getDouble(etGoldPrice22k));
                payload.put("gold_price_21k", getDouble(etGoldPrice21k));
                payload.put("gold_price_18k", getDouble(etGoldPrice18k));
                payload.put("gold_price_14k", getDouble(etGoldPrice14k));
                
                payload.put("margin_24k", getDouble(etMargin24k));
                payload.put("margin_22k", getDouble(etMargin22k));
                payload.put("margin_21k", getDouble(etMargin21k));
                payload.put("margin_18k", getDouble(etMargin18k));
                payload.put("margin_14k", getDouble(etMargin14k));
                payload.put("margin_12k", getDouble(etMargin12k));
                
                payload.put("making_24k", getDouble(etMaking24k));
                payload.put("making_22k", getDouble(etMaking22k));
                payload.put("making_21k", getDouble(etMaking21k));
                payload.put("making_18k", getDouble(etMaking18k));
                payload.put("making_14k", getDouble(etMaking14k));
                payload.put("making_12k", getDouble(etMaking12k));
                
                payload.put("gold_api_provider", spGoldApiProvider.getSelectedItem().toString());
                payload.put("gold_api_key", getText(etGoldApiKey));
                payload.put("gold_default_currency", spGoldCurrency.getSelectedItem().toString());
                
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("gold_settings", payload.toString());
                editor.putLong("gold_last_update", System.currentTimeMillis());
                editor.apply();
                
                com.example.uhf.api.MiraApiClient client = com.example.uhf.api.MiraApiClient.getInstance(mContext);
                com.example.uhf.api.MiraApiClient.ApiResponse response = client.saveGoldSettings(payload);
                
                final boolean serverSaved = response.isSuccess;
                mainHandler.post(() -> {
                    pbGoldLoading.setVisibility(View.GONE);
                    btnSaveGoldSettings.setEnabled(true);
                    if (serverSaved) {
                        Toast.makeText(mContext, "✅ تم حفظ إعدادات الذهب", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(mContext, "⚠️ تم الحفظ محلياً فقط", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error saving gold settings: " + e.getMessage());
                mainHandler.post(() -> {
                    pbGoldLoading.setVisibility(View.GONE);
                    btnSaveGoldSettings.setEnabled(true);
                    Toast.makeText(mContext, "❌ فشل حفظ الإعدادات", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // ============================================
    // 💾 حفظ إعدادات العملات
    // ============================================
    private void saveCurrencySettings() {
        pbCurrencyLoading.setVisibility(View.VISIBLE);
        btnSaveCurrencySettings.setEnabled(false);
        
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                
                payload.put("global_default_currency", spDefaultCurrency.getSelectedItem().toString());
                payload.put("chart_currency", spChartCurrency.getSelectedItem().toString());
                
                payload.put("exchange_official_YER", getDouble(etExchangeYER));
                payload.put("exchange_official_EGP", getDouble(etExchangeEGP));
                payload.put("exchange_official_SAR", getDouble(etExchangeSAR));
                payload.put("exchange_official_AED", getDouble(etExchangeAED));
                
                payload.put("exchange_market_YER", getDouble(etExchangeMarketYER));
                payload.put("exchange_market_EGP", getDouble(etExchangeMarketEGP));
                payload.put("exchange_market_SAR", getDouble(etExchangeMarketSAR));
                payload.put("exchange_market_AED", getDouble(etExchangeMarketAED));
                
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("currency_settings", payload.toString());
                editor.apply();
                
                com.example.uhf.api.MiraApiClient client = com.example.uhf.api.MiraApiClient.getInstance(mContext);
                com.example.uhf.api.MiraApiClient.ApiResponse response = client.saveCurrencySettings(payload);
                
                final boolean serverSaved = response.isSuccess;
                mainHandler.post(() -> {
                    pbCurrencyLoading.setVisibility(View.GONE);
                    btnSaveCurrencySettings.setEnabled(true);
                    if (serverSaved) {
                        Toast.makeText(mContext, "✅ تم حفظ إعدادات العملات", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(mContext, "⚠️ تم الحفظ محلياً فقط", Toast.LENGTH_SHORT).show();
                    }
                    updateLivePrices();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error saving currency settings: " + e.getMessage());
                mainHandler.post(() -> {
                    pbCurrencyLoading.setVisibility(View.GONE);
                    btnSaveCurrencySettings.setEnabled(true);
                    Toast.makeText(mContext, "❌ فشل حفظ الإعدادات", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // ============================================
    // 🔄 تحديث الأسعار المباشرة
    // ============================================
    private void updateLivePrices() {
        updateLiveGoldPrices();
        updateLiveCurrencyRates();
    }

    private void updateLiveGoldPrices() {
        double price24k = getDouble(etGoldPrice24k);
        double margin24k = getDouble(etMargin24k);
        double making24k = getDouble(etMaking24k);
        
        if (price24k > 0) {
            double final24k = (price24k * margin24k / 100) + making24k;
            tvLiveGold24k.setText(String.format("24k: $%s/g", decimalFormat.format(final24k)));
            
            double price22k = price24k * 22 / 24;
            double margin22k = getDouble(etMargin22k);
            double making22k = getDouble(etMaking22k);
            double final22k = (price22k * margin22k / 100) + making22k;
            tvLiveGold22k.setText(String.format("22k: $%s/g", decimalFormat.format(final22k)));
            
            double price21k = price24k * 21 / 24;
            double margin21k = getDouble(etMargin21k);
            double making21k = getDouble(etMaking21k);
            double final21k = (price21k * margin21k / 100) + making21k;
            tvLiveGold21k.setText(String.format("21k: $%s/g", decimalFormat.format(final21k)));
            
            double price18k = price24k * 18 / 24;
            double margin18k = getDouble(etMargin18k);
            double making18k = getDouble(etMaking18k);
            double final18k = (price18k * margin18k / 100) + making18k;
            tvLiveGold18k.setText(String.format("18k: $%s/g", decimalFormat.format(final18k)));
        }
    }

    private void updateLiveCurrencyRates() {
        double yer = getDouble(etExchangeYER);
        double egp = getDouble(etExchangeEGP);
        double sar = getDouble(etExchangeSAR);
        double aed = getDouble(etExchangeAED);
        
        tvLiveUSD_YER.setText(String.format("1 USD = %s YER", decimalFormat.format(yer)));
        tvLiveUSD_EGP.setText(String.format("1 USD = %s EGP", decimalFormat.format(egp)));
        tvLiveUSD_SAR.setText(String.format("1 USD = %s SAR", decimalFormat.format(sar)));
        tvLiveUSD_AED.setText(String.format("1 USD = %s AED", decimalFormat.format(aed)));
    }

    // ============================================
    // 📥 تحميل الإعدادات
    // ============================================
    private void loadLocalSettings() {
        String goldSettingsJson = prefs.getString("gold_settings", "");
        if (!goldSettingsJson.isEmpty()) {
            try {
                JSONObject json = new JSONObject(goldSettingsJson);
                applyGoldSettings(json);
            } catch (Exception e) {
                Log.e(TAG, "Error loading local gold settings: " + e.getMessage());
            }
        }
        
        String currencySettingsJson = prefs.getString("currency_settings", "");
        if (!currencySettingsJson.isEmpty()) {
            try {
                JSONObject json = new JSONObject(currencySettingsJson);
                applyCurrencySettings(json);
            } catch (Exception e) {
                Log.e(TAG, "Error loading local currency settings: " + e.getMessage());
            }
        }
        
        updateLivePrices();
    }

    private void loadServerSettings() {
        executor.execute(() -> {
            try {
                com.example.uhf.api.MiraApiClient client = com.example.uhf.api.MiraApiClient.getInstance(mContext);
                com.example.uhf.api.MiraApiClient.ApiResponse response = client.getGoldCurrencySettings();
                
                if (response.isSuccess && response.data != null) {
                    JSONObject data = response.data.optJSONObject("data");
                    if (data == null) data = response.data;
                    
                    JSONObject goldData = data.optJSONObject("gold");
                    JSONObject currencyData = data.optJSONObject("currency");
                    
                    mainHandler.post(() -> {
                        if (goldData != null) applyGoldSettings(goldData);
                        if (currencyData != null) applyCurrencySettings(currencyData);
                        updateLivePrices();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading server settings: " + e.getMessage());
            }
        });
    }

    private void applyGoldSettings(JSONObject json) {
        try {
            if (json == null) return;
            
            if (json.has("gold_price_24k")) etGoldPrice24k.setText(decimalFormat.format(json.optDouble("gold_price_24k")));
            if (json.has("gold_price_22k")) etGoldPrice22k.setText(decimalFormat.format(json.optDouble("gold_price_22k")));
            if (json.has("gold_price_21k")) etGoldPrice21k.setText(decimalFormat.format(json.optDouble("gold_price_21k")));
            if (json.has("gold_price_18k")) etGoldPrice18k.setText(decimalFormat.format(json.optDouble("gold_price_18k")));
            if (json.has("gold_price_14k")) etGoldPrice14k.setText(decimalFormat.format(json.optDouble("gold_price_14k")));
            
            if (json.has("margin_24k")) etMargin24k.setText(decimalFormat.format(json.optDouble("margin_24k")));
            if (json.has("margin_22k")) etMargin22k.setText(decimalFormat.format(json.optDouble("margin_22k")));
            if (json.has("margin_21k")) etMargin21k.setText(decimalFormat.format(json.optDouble("margin_21k")));
            if (json.has("margin_18k")) etMargin18k.setText(decimalFormat.format(json.optDouble("margin_18k")));
            if (json.has("margin_14k")) etMargin14k.setText(decimalFormat.format(json.optDouble("margin_14k")));
            if (json.has("margin_12k")) etMargin12k.setText(decimalFormat.format(json.optDouble("margin_12k")));
            
            if (json.has("making_24k")) etMaking24k.setText(decimalFormat.format(json.optDouble("making_24k")));
            if (json.has("making_22k")) etMaking22k.setText(decimalFormat.format(json.optDouble("making_22k")));
            if (json.has("making_21k")) etMaking21k.setText(decimalFormat.format(json.optDouble("making_21k")));
            if (json.has("making_18k")) etMaking18k.setText(decimalFormat.format(json.optDouble("making_18k")));
            if (json.has("making_14k")) etMaking14k.setText(decimalFormat.format(json.optDouble("making_14k")));
            if (json.has("making_12k")) etMaking12k.setText(decimalFormat.format(json.optDouble("making_12k")));
            
            if (json.has("gold_api_key")) etGoldApiKey.setText(json.optString("gold_api_key"));
        } catch (Exception e) {
            Log.e(TAG, "Error applying gold settings: " + e.getMessage());
        }
    }

    private void applyCurrencySettings(JSONObject json) {
        try {
            if (json == null) return;
            
            if (json.has("exchange_official_YER")) etExchangeYER.setText(decimalFormat.format(json.optDouble("exchange_official_YER")));
            if (json.has("exchange_official_EGP")) etExchangeEGP.setText(decimalFormat.format(json.optDouble("exchange_official_EGP")));
            if (json.has("exchange_official_SAR")) etExchangeSAR.setText(decimalFormat.format(json.optDouble("exchange_official_SAR")));
            if (json.has("exchange_official_AED")) etExchangeAED.setText(decimalFormat.format(json.optDouble("exchange_official_AED")));
            
            if (json.has("exchange_market_YER")) etExchangeMarketYER.setText(decimalFormat.format(json.optDouble("exchange_market_YER")));
            if (json.has("exchange_market_EGP")) etExchangeMarketEGP.setText(decimalFormat.format(json.optDouble("exchange_market_EGP")));
            if (json.has("exchange_market_SAR")) etExchangeMarketSAR.setText(decimalFormat.format(json.optDouble("exchange_market_SAR")));
            if (json.has("exchange_market_AED")) etExchangeMarketAED.setText(decimalFormat.format(json.optDouble("exchange_market_AED")));
        } catch (Exception e) {
            Log.e(TAG, "Error applying currency settings: " + e.getMessage());
        }
    }

    // ============================================
    // Helper Methods
    // ============================================
    private double getDouble(EditText et) {
        try {
            return et != null ? Double.parseDouble(et.getText().toString().trim()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String getText(EditText et) {
        return et != null ? et.getText().toString().trim() : "";
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
