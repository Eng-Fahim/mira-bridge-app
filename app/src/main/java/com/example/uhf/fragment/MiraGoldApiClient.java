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
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MIRA Gold & Currency Settings
 * إدارة أسعار الذهب والبورصة والعملات
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

    // ============ Gold Settings Views ============
    private TextView tvGoldPriceStatus, tvLastUpdate;
    private EditText etGoldPrice24k, etGoldPrice22k, etGoldPrice21k, etGoldPrice18k, etGoldPrice14k;
    private EditText etMargin24k, etMargin22k, etMargin21k, etMargin18k, etMargin14k, etMargin12k;
    private EditText etMaking24k, etMaking22k, etMaking21k, etMaking18k, etMaking14k, etMaking12k;
    private Spinner spGoldApiProvider, spGoldCurrency;
    private EditText etGoldApiKey;
    private Button btnFetchGoldPrice, btnSaveGoldSettings;
    private ProgressBar pbGoldLoading;

    // ============ Currency Settings Views ============
    private Spinner spDefaultCurrency, spChartCurrency;
    private RadioGroup rgExchangeSource;
    private RadioButton rbOfficial, rbMarket;
    private EditText etExchangeYER, etExchangeEGP, etExchangeSAR, etExchangeAED;
    private EditText etExchangeMarketYER, etExchangeMarketEGP, etExchangeMarketSAR, etExchangeMarketAED;
    private Button btnSaveCurrencySettings, btnRefreshRates;
    private ProgressBar pbCurrencyLoading;

    // ============ Live Prices Display ============
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
        setupListeners();
        loadSettings();
        refreshLivePrices();
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
        rgExchangeSource = view.findViewById(R.id.rgExchangeSource);
        rbOfficial = view.findViewById(R.id.rbOfficial);
        rbMarket = view.findViewById(R.id.rbMarket);
        
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

    private void setupListeners() {
        btnFetchGoldPrice.setOnClickListener(v -> fetchGoldPricesFromApi());
        btnSaveGoldSettings.setOnClickListener(v -> saveGoldSettings());
        btnSaveCurrencySettings.setOnClickListener(v -> saveCurrencySettings());
        btnRefreshRates.setOnClickListener(v -> refreshLivePrices());
    }

    // ============================================
    // 💰 جلب أسعار الذهب من API
    // ============================================
    private void fetchGoldPricesFromApi() {
        String provider = spGoldApiProvider != null ? spGoldApiProvider.getSelectedItem().toString() : "metalprice";
        String apiKey = getText(etGoldApiKey);
        
        if (apiKey.isEmpty()) {
            Toast.makeText(mContext, "⚠️ أدخل مفتاح API أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        pbGoldLoading.setVisibility(View.VISIBLE);
        btnFetchGoldPrice.setEnabled(false);
        
        executor.execute(() -> {
            try {
                String apiUrl = "";
                if (provider.contains("metalprice")) {
                    apiUrl = "https://api.metalpriceapi.com/v1/latest?api_key=" + apiKey + "&base=USD&currencies=XAU";
                } else if (provider.contains("goldapi")) {
                    apiUrl = "https://www.goldapi.io/api/XAU/USD";
                }
                
                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Content-Type", "application/json");
                if (provider.contains("goldapi")) {
                    conn.setRequestProperty("x-access-token", apiKey);
                }
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                
                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                    
                    JSONObject json = new JSONObject(response.toString());
                    parseAndUpdateGoldPrices(json, provider);
                    
                    mainHandler.post(() -> {
                        pbGoldLoading.setVisibility(View.GONE);
                        btnFetchGoldPrice.setEnabled(true);
                        tvGoldPriceStatus.setText("✅ تم تحديث الأسعار");
                        tvLastUpdate.setText("آخر تحديث: " + System.currentTimeMillis());
                        Toast.makeText(mContext, "✅ تم جلب أسعار الذهب بنجاح", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    mainHandler.post(() -> {
                        pbGoldLoading.setVisibility(View.GONE);
                        btnFetchGoldPrice.setEnabled(true);
                        tvGoldPriceStatus.setText("❌ فشل جلب الأسعار");
                        Toast.makeText(mContext, "❌ فشل الاتصال بمزود الأسعار", Toast.LENGTH_SHORT).show();
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

    private void parseAndUpdateGoldPrices(JSONObject json, String provider) {
        try {
            double goldPricePerOunce = 0;
            
            if (provider.contains("metalprice")) {
                JSONObject rates = json.optJSONObject("rates");
                if (rates != null) {
                    goldPricePerOunce = rates.optDouble("XAU", 0);
                }
            } else if (provider.contains("goldapi")) {
                goldPricePerOunce = json.optDouble("price", 0);
            }
            
            // تحويل من أونصة إلى جرام (1 أونصة = 31.1035 جرام)
            double goldPricePerGram = goldPricePerOunce / 31.1035;
            
            // حساب أسعار العيارات
            double price24k = goldPricePerGram;
            double price22k = goldPricePerGram * 22 / 24;
            double price21k = goldPricePerGram * 21 / 24;
            double price18k = goldPricePerGram * 18 / 24;
            double price14k = goldPricePerGram * 14 / 24;
            
            // تحديث الواجهة
            final double final24k = price24k;
            final double final22k = price22k;
            final double final21k = price21k;
            final double final18k = price18k;
            final double final14k = price14k;
            
            mainHandler.post(() -> {
                etGoldPrice24k.setText(decimalFormat.format(final24k));
                etGoldPrice22k.setText(decimalFormat.format(final22k));
                etGoldPrice21k.setText(decimalFormat.format(final21k));
                etGoldPrice18k.setText(decimalFormat.format(final18k));
                etGoldPrice14k.setText(decimalFormat.format(final14k));
                
                // تحديث الأسعار المباشرة
                updateLiveGoldPrices();
            });
        } catch (Exception e) {
            Log.e(TAG, "Error parsing gold prices: " + e.getMessage());
        }
    }

    // ============================================
    // 💾 حفظ إعدادات الذهب
    // ============================================
    private void saveGoldSettings() {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                
                // الأسعار
                payload.put("gold_price_24k", getDouble(etGoldPrice24k));
                payload.put("gold_price_22k", getDouble(etGoldPrice22k));
                payload.put("gold_price_21k", getDouble(etGoldPrice21k));
                payload.put("gold_price_18k", getDouble(etGoldPrice18k));
                payload.put("gold_price_14k", getDouble(etGoldPrice14k));
                
                // الهوامش
                payload.put("margin_24k", getDouble(etMargin24k));
                payload.put("margin_22k", getDouble(etMargin22k));
                payload.put("margin_21k", getDouble(etMargin21k));
                payload.put("margin_18k", getDouble(etMargin18k));
                payload.put("margin_14k", getDouble(etMargin14k));
                payload.put("margin_12k", getDouble(etMargin12k));
                
                // المصنعية
                payload.put("making_24k", getDouble(etMaking24k));
                payload.put("making_22k", getDouble(etMaking22k));
                payload.put("making_21k", getDouble(etMaking21k));
                payload.put("making_18k", getDouble(etMaking18k));
                payload.put("making_14k", getDouble(etMaking14k));
                payload.put("making_12k", getDouble(etMaking12k));
                
                // الإعدادات
                payload.put("gold_api_provider", spGoldApiProvider.getSelectedItem().toString());
                payload.put("gold_api_key", getText(etGoldApiKey));
                payload.put("gold_default_currency", spGoldCurrency.getSelectedItem().toString());
                
                // حفظ محلياً
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("gold_settings", payload.toString());
                editor.apply();
                
                // إرسال للسيرفر
                sendToServer("mira_save_gold_settings", payload);
                
                mainHandler.post(() -> {
                    Toast.makeText(mContext, "✅ تم حفظ إعدادات الذهب", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error saving gold settings: " + e.getMessage());
                mainHandler.post(() -> {
                    Toast.makeText(mContext, "❌ فشل حفظ الإعدادات", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // ============================================
    // 💾 حفظ إعدادات العملات
    // ============================================
    private void saveCurrencySettings() {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                
                payload.put("global_default_currency", spDefaultCurrency.getSelectedItem().toString());
                payload.put("chart_currency", spChartCurrency.getSelectedItem().toString());
                
                // أسعار الصرف الرسمية
                payload.put("exchange_official_YER", getDouble(etExchangeYER));
                payload.put("exchange_official_EGP", getDouble(etExchangeEGP));
                payload.put("exchange_official_SAR", getDouble(etExchangeSAR));
                payload.put("exchange_official_AED", getDouble(etExchangeAED));
                
                // أسعار الصرف السوقية
                payload.put("exchange_market_YER", getDouble(etExchangeMarketYER));
                payload.put("exchange_market_EGP", getDouble(etExchangeMarketEGP));
                payload.put("exchange_market_SAR", getDouble(etExchangeMarketSAR));
                payload.put("exchange_market_AED", getDouble(etExchangeMarketAED));
                
                // حفظ محلياً
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("currency_settings", payload.toString());
                editor.apply();
                
                // إرسال للسيرفر
                sendToServer("mira_save_currency_settings", payload);
                
                mainHandler.post(() -> {
                    Toast.makeText(mContext, "✅ تم حفظ إعدادات العملات", Toast.LENGTH_SHORT).show();
                    updateLiveCurrencyRates();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error saving currency settings: " + e.getMessage());
                mainHandler.post(() -> {
                    Toast.makeText(mContext, "❌ فشل حفظ الإعدادات", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // ============================================
    // 📡 إرسال البيانات للسيرفر
    // ============================================
    private void sendToServer(String action, JSONObject payload) {
        try {
            String apiUrl = settingsManager.getString("mira_api_url", "https://ams.ibreg.org/wp-json/mira/v1/settings");
            String apiKey = settingsManager.getString("mira_api_key", "");
            
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-MIRA-API-Key", apiKey);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            
            JSONObject requestData = new JSONObject();
            requestData.put("action", action);
            requestData.put("data", payload);
            
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestData.toString().getBytes("utf-8"));
            }
            
            conn.getResponseCode();
        } catch (Exception e) {
            Log.e(TAG, "Error sending to server: " + e.getMessage());
        }
    }

    // ============================================
    // 🔄 تحديث الأسعار المباشرة
    // ============================================
    private void refreshLivePrices() {
        updateLiveGoldPrices();
        updateLiveCurrencyRates();
    }

    private void updateLiveGoldPrices() {
        double price24k = getDouble(etGoldPrice24k);
        double margin24k = getDouble(etMargin24k);
        double making24k = getDouble(etMaking24k);
        
        if (price24k > 0) {
            double final24k = (price24k * margin24k / 100) + making24k;
            tvLiveGold24k.setText("24k: $" + decimalFormat.format(final24k) + "/g");
            
            double price22k = price24k * 22 / 24;
            double margin22k = getDouble(etMargin22k);
            double making22k = getDouble(etMaking22k);
            double final22k = (price22k * margin22k / 100) + making22k;
            tvLiveGold22k.setText("22k: $" + decimalFormat.format(final22k) + "/g");
            
            double price21k = price24k * 21 / 24;
            double margin21k = getDouble(etMargin21k);
            double making21k = getDouble(etMaking21k);
            double final21k = (price21k * margin21k / 100) + making21k;
            tvLiveGold21k.setText("21k: $" + decimalFormat.format(final21k) + "/g");
            
            double price18k = price24k * 18 / 24;
            double margin18k = getDouble(etMargin18k);
            double making18k = getDouble(etMaking18k);
            double final18k = (price18k * margin18k / 100) + making18k;
            tvLiveGold18k.setText("18k: $" + decimalFormat.format(final18k) + "/g");
        }
    }

    private void updateLiveCurrencyRates() {
        double yer = getDouble(etExchangeYER);
        double egp = getDouble(etExchangeEGP);
        double sar = getDouble(etExchangeSAR);
        double aed = getDouble(etExchangeAED);
        
        tvLiveUSD_YER.setText("1 USD = " + decimalFormat.format(yer) + " YER");
        tvLiveUSD_EGP.setText("1 USD = " + decimalFormat.format(egp) + " EGP");
        tvLiveUSD_SAR.setText("1 USD = " + decimalFormat.format(sar) + " SAR");
        tvLiveUSD_AED.setText("1 USD = " + decimalFormat.format(aed) + " AED");
    }

    // ============================================
    // 📥 تحميل الإعدادات المحفوظة
    // ============================================
    private void loadSettings() {
        String goldSettingsJson = prefs.getString("gold_settings", "");
        if (!goldSettingsJson.isEmpty()) {
            try {
                JSONObject json = new JSONObject(goldSettingsJson);
                // تحميل القيم في الواجهة
                if (json.has("gold_price_24k")) etGoldPrice24k.setText(decimalFormat.format(json.optDouble("gold_price_24k")));
                if (json.has("margin_24k")) etMargin24k.setText(decimalFormat.format(json.optDouble("margin_24k")));
                if (json.has("making_24k")) etMaking24k.setText(decimalFormat.format(json.optDouble("making_24k")));
                // ... تحميل باقي القيم
            } catch (Exception e) {
                Log.e(TAG, "Error loading gold settings: " + e.getMessage());
            }
        }
        
        String currencySettingsJson = prefs.getString("currency_settings", "");
        if (!currencySettingsJson.isEmpty()) {
            try {
                JSONObject json = new JSONObject(currencySettingsJson);
                // تحميل القيم في الواجهة
                if (json.has("exchange_official_YER")) etExchangeYER.setText(decimalFormat.format(json.optDouble("exchange_official_YER")));
                if (json.has("exchange_official_EGP")) etExchangeEGP.setText(decimalFormat.format(json.optDouble("exchange_official_EGP")));
                // ... تحميل باقي القيم
            } catch (Exception e) {
                Log.e(TAG, "Error loading currency settings: " + e.getMessage());
            }
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
