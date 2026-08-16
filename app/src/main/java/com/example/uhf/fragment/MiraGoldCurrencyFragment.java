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
    
    private boolean isArabic; // ✅ جديد

    // ... جميع المتغيرات كما هي ...

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        if (getActivity() instanceof UHFMainActivity) {
            mContext = (UHFMainActivity) getActivity();
            settingsManager = MiraSettingsManager.getInstance(mContext);
            prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
        
        isArabic = Locale.getDefault().getLanguage().equals("ar"); // ✅ جديد

        initViews(view);
        setupSpinners();
        setupListeners();
        loadLocalSettings();
        loadServerSettings();
    }

    private void setupSpinners() {
        // ✅ استخدام أسماء عربية للمزودين
        String[] providers = isArabic ? 
            new String[]{"ميتال برايس", "جولد API", "ميتالز ديف"} : 
            new String[]{"metalprice", "goldapi", "metalsdev"};
        
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

    private void fetchGoldPricesFromApi() {
        String providerDisplay = spGoldApiProvider.getSelectedItem().toString();
        // ✅ تحويل الاسم المعروض إلى القيمة الفعلية
        String provider = getProviderValue(providerDisplay);
        String apiKey = getText(etGoldApiKey);
        
        if (apiKey.isEmpty()) {
            Toast.makeText(mContext, 
                isArabic ? "⚠️ أدخل مفتاح API أولاً" : "⚠️ Enter API key first", 
                Toast.LENGTH_SHORT).show();
            return;
        }

        pbGoldLoading.setVisibility(View.VISIBLE);
        btnFetchGoldPrice.setEnabled(false);
        tvGoldPriceStatus.setText(isArabic ? "⏳ جاري جلب الأسعار..." : "⏳ Fetching prices...");
        
        executor.execute(() -> {
            try {
                com.example.uhf.api.MiraApiClient client = com.example.uhf.api.MiraApiClient.getInstance(mContext);
                com.example.uhf.api.MiraApiClient.ApiResponse response = client.fetchGoldPrice(provider, apiKey);
                
                if (response.isSuccess && response.data != null) {
                    JSONObject data = response.data.optJSONObject("data");
                    if (data == null) data = response.data;
                    
                    JSONObject prices = data.optJSONObject("prices");
                    if (prices == null) prices = data;
                    
                    final JSONObject finalPrices = prices;
                    final double price24k = finalPrices.optDouble("24k", 0);
                    final double price22k = finalPrices.optDouble("22k", 0);
                    final double price21k = finalPrices.optDouble("21k", 0);
                    final double price18k = finalPrices.optDouble("18k", 0);
                    final double price14k = finalPrices.optDouble("14k", 0);
                    
                    mainHandler.post(() -> {
                        if (price24k > 0) etGoldPrice24k.setText(decimalFormat.format(price24k));
                        if (price22k > 0) etGoldPrice22k.setText(decimalFormat.format(price22k));
                        if (price21k > 0) etGoldPrice21k.setText(decimalFormat.format(price21k));
                        if (price18k > 0) etGoldPrice18k.setText(decimalFormat.format(price18k));
                        if (price14k > 0) etGoldPrice14k.setText(decimalFormat.format(price14k));
                        
                        pbGoldLoading.setVisibility(View.GONE);
                        btnFetchGoldPrice.setEnabled(true);
                        tvGoldPriceStatus.setText(isArabic ? "✅ تم تحديث الأسعار" : "✅ Prices updated");
                        tvLastUpdate.setText((isArabic ? "آخر تحديث: " : "Last update: ") + dateFormat.format(new Date()));
                        updateLivePrices();
                        
                        Toast.makeText(mContext, 
                            isArabic ? "✅ تم جلب أسعار الذهب بنجاح" : "✅ Gold prices fetched successfully", 
                            Toast.LENGTH_SHORT).show();
                    });
                } else {
                    mainHandler.post(() -> {
                        pbGoldLoading.setVisibility(View.GONE);
                        btnFetchGoldPrice.setEnabled(true);
                        tvGoldPriceStatus.setText(isArabic ? "❌ فشل جلب الأسعار" : "❌ Failed to fetch prices");
                        Toast.makeText(mContext, 
                            isArabic ? "❌ فشل جلب الأسعار من السيرفر" : "❌ Failed to fetch from server", 
                            Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching gold prices: " + e.getMessage());
                mainHandler.post(() -> {
                    pbGoldLoading.setVisibility(View.GONE);
                    btnFetchGoldPrice.setEnabled(true);
                    tvGoldPriceStatus.setText(isArabic ? "❌ خطأ في الاتصال" : "❌ Connection error");
                    Toast.makeText(mContext, "❌ " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * ✅ تحويل اسم المزود المعروض إلى القيمة الفعلية
     */
    private String getProviderValue(String displayName) {
        if (displayName == null) return "metalprice";
        
        if (displayName.equals("ميتال برايس") || displayName.equals("metalprice")) return "metalprice";
        if (displayName.equals("جولد API") || displayName.equals("goldapi")) return "goldapi";
        if (displayName.equals("ميتالز ديف") || displayName.equals("metalsdev")) return "metalsdev";
        
        return "metalprice";
    }

    private void saveGoldSettings() {
        // ... نفس الكود مع تعديل الرسائل ...
        
        mainHandler.post(() -> {
            pbGoldLoading.setVisibility(View.GONE);
            btnSaveGoldSettings.setEnabled(true);
            if (serverSaved) {
                Toast.makeText(mContext, 
                    isArabic ? "✅ تم حفظ إعدادات الذهب" : "✅ Gold settings saved", 
                    Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(mContext, 
                    isArabic ? "⚠️ تم الحفظ محلياً فقط" : "⚠️ Saved locally only", 
                    Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void saveCurrencySettings() {
        // ... نفس الكود مع تعديل الرسائل ...
        
        mainHandler.post(() -> {
            pbCurrencyLoading.setVisibility(View.GONE);
            btnSaveCurrencySettings.setEnabled(true);
            if (serverSaved) {
                Toast.makeText(mContext, 
                    isArabic ? "✅ تم حفظ إعدادات العملات" : "✅ Currency settings saved", 
                    Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(mContext, 
                    isArabic ? "⚠️ تم الحفظ محلياً فقط" : "⚠️ Saved locally only", 
                    Toast.LENGTH_SHORT).show();
            }
            updateLivePrices();
        });
    }

    private void updateLiveGoldPrices() {
        double price24k = getDouble(etGoldPrice24k);
        double margin24k = getDouble(etMargin24k);
        double making24k = getDouble(etMaking24k);
        
        String karatLabel = isArabic ? "قيراط" : "K";
        
        if (price24k > 0) {
            double final24k = (price24k * margin24k / 100) + making24k;
            tvLiveGold24k.setText(String.format(Locale.getDefault(), 
                "24%s: $%s/g", karatLabel, decimalFormat.format(final24k)));
            
            double price22k = price24k * 22 / 24;
            double final22k = (price22k * getDouble(etMargin22k) / 100) + getDouble(etMaking22k);
            tvLiveGold22k.setText(String.format(Locale.getDefault(), 
                "22%s: $%s/g", karatLabel, decimalFormat.format(final22k)));
            
            double price21k = price24k * 21 / 24;
            double final21k = (price21k * getDouble(etMargin21k) / 100) + getDouble(etMaking21k);
            tvLiveGold21k.setText(String.format(Locale.getDefault(), 
                "21%s: $%s/g", karatLabel, decimalFormat.format(final21k)));
            
            double price18k = price24k * 18 / 24;
            double final18k = (price18k * getDouble(etMargin18k) / 100) + getDouble(etMaking18k);
            tvLiveGold18k.setText(String.format(Locale.getDefault(), 
                "18%s: $%s/g", karatLabel, decimalFormat.format(final18k)));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
