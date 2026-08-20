package com.example.uhf.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
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
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.model.ApiProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraGoldCurrencyFragment extends Fragment {

    private static final String TAG = "MiraGoldCurrency";
    private static final String PREFS_NAME = "MIRA_GOLD_SETTINGS";
    private static final double OUNCE_TO_GRAM = 31.1035;

    private UHFMainActivity mContext;
    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final DecimalFormat decimalFormat = new DecimalFormat("#,##0.00", new DecimalFormatSymbols(Locale.US));
    private final DecimalFormat largeFormat = new DecimalFormat("#,##0", new DecimalFormatSymbols(Locale.US));
    
    private boolean isArabic;
    private String selectedCurrency = "USD";
    private boolean useMarketRate = false;
    private boolean isUpdatingFields = false;
    private double currentGoldPrice24kUsd = 75.00;
    
    // Provider Management
    private List<ApiProvider> providersList = new ArrayList<>();
    private ApiProvider selectedProvider;
    private TextView tvSelectedProvider;
    private TextView tvActiveProviderStatus;

    // Gold Views
    private TextView tvGoldPriceStatus, tvLastUpdate, tvOuncePrice, tvOuncePriceConverted;
    private EditText etGoldPrice24k, etGoldPrice22k, etGoldPrice21k, etGoldPrice18k, etGoldPrice14k;
    private EditText etMargin24k, etMargin22k, etMargin21k, etMargin18k, etMargin14k, etMargin12k;
    private EditText etMaking24k, etMaking22k, etMaking21k, etMaking18k, etMaking14k, etMaking12k;
    private Spinner spGoldApiProvider, spGoldCurrency;
    private EditText etGoldApiKey;
    private Button btnFetchGoldPrice, btnSaveGoldSettings;
    private ProgressBar pbGoldLoading;

    // Currency Views
    private Spinner spDefaultCurrency, spChartCurrency;
    private EditText etExchangeYER, etExchangeEGP, etExchangeSAR, etExchangeAED;
    private EditText etExchangeMarketYER, etExchangeMarketEGP, etExchangeMarketSAR, etExchangeMarketAED;
    private Button btnSaveCurrencySettings, btnRefreshRates;
    private ProgressBar pbCurrencyLoading;
    private RadioGroup rgExchangeRateType;
    private RadioButton rbOfficialRate, rbMarketRate;

    // Live Prices
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
            prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
        
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        selectedCurrency = prefs.getString("preferred_currency", "USD");
        useMarketRate = prefs.getBoolean("use_market_rate", false);

        initViews(view);
        setupSpinners();
        setupListeners();
        loadActiveProviders();
        loadLocalSettings();
        loadServerSettings();
        updateAllPrices();
    }

    private String normalizeArabicNumbers(String input) {
        if (input == null) return "";
        return input
            .replace('٠', '0').replace('١', '1').replace('٢', '2').replace('٣', '3')
            .replace('٤', '4').replace('٥', '5').replace('٦', '6').replace('٧', '7')
            .replace('٨', '8').replace('٩', '9').replace('٫', '.').replace('٬', ',');
    }

    private double getDouble(EditText et) {
        try {
            if (et == null) return 0;
            return Double.parseDouble(normalizeArabicNumbers(et.getText().toString().trim()));
        } catch (Exception e) {
            return 0;
        }
    }

    private String getText(EditText et) {
        if (et == null) return "";
        return normalizeArabicNumbers(et.getText().toString().trim());
    }

    private void initViews(View view) {
        tvGoldPriceStatus = view.findViewById(R.id.tvGoldPriceStatus);
        tvLastUpdate = view.findViewById(R.id.tvLastUpdate);
        tvOuncePrice = view.findViewById(R.id.tvOuncePrice);
        tvOuncePriceConverted = view.findViewById(R.id.tvOuncePriceConverted);
        tvSelectedProvider = view.findViewById(R.id.tvSelectedProvider);
        tvActiveProviderStatus = view.findViewById(R.id.tvActiveProviderStatus);
        
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
        
        rgExchangeRateType = view.findViewById(R.id.rgExchangeRateType);
        rbOfficialRate = view.findViewById(R.id.rbOfficialRate);
        rbMarketRate = view.findViewById(R.id.rbMarketRate);

        tvLiveGold24k = view.findViewById(R.id.tvLiveGold24k);
        tvLiveGold22k = view.findViewById(R.id.tvLiveGold22k);
        tvLiveGold21k = view.findViewById(R.id.tvLiveGold21k);
        tvLiveGold18k = view.findViewById(R.id.tvLiveGold18k);
        
        tvLiveUSD_YER = view.findViewById(R.id.tvLiveUSD_YER);
        tvLiveUSD_EGP = view.findViewById(R.id.tvLiveUSD_EGP);
        tvLiveUSD_SAR = view.findViewById(R.id.tvLiveUSD_SAR);
        tvLiveUSD_AED = view.findViewById(R.id.tvLiveUSD_AED);
        
        // إخفاء حقل API Key - لا يحتاج المستخدم لرؤيته
        if (etGoldApiKey != null) {
            etGoldApiKey.setVisibility(View.GONE);
            etGoldApiKey.setEnabled(false);
        }
        
        if (rgExchangeRateType != null) {
            rgExchangeRateType.check(useMarketRate ? R.id.rbMarketRate : R.id.rbOfficialRate);
        }
    }

    private void setupSpinners() {
        // إعداد Spinner للعملات
        String[] currencies = {"USD", "YER", "EGP", "SAR", "AED"};
        ArrayAdapter<String> currencyAdapter = new ArrayAdapter<>(mContext, android.R.layout.simple_spinner_item, currencies);
        currencyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        spGoldCurrency.setAdapter(currencyAdapter);
        spDefaultCurrency.setAdapter(currencyAdapter);
        spChartCurrency.setAdapter(currencyAdapter);
        
        int pos = getCurrencyPosition(selectedCurrency);
        spGoldCurrency.setSelection(pos);
        spDefaultCurrency.setSelection(pos);
        spChartCurrency.setSelection(pos);
        
        // إعداد Spinner للمزودين - سيتم تحديثه لاحقاً بعد تحميل البيانات
        List<String> emptyList = new ArrayList<>();
        emptyList.add(isArabic ? "جاري التحميل..." : "Loading...");
        ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(mContext, android.R.layout.simple_spinner_item, emptyList);
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spGoldApiProvider.setAdapter(providerAdapter);
        spGoldApiProvider.setEnabled(false);
    }

    private void setupListeners() {
        // مستمع اختيار المزود
        spGoldApiProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < providersList.size()) {
                    selectedProvider = providersList.get(position);
                    
                    // عرض اسم المزود
                    if (tvSelectedProvider != null) {
                        tvSelectedProvider.setText(
                            isArabic ? 
                            "المزود المحدد: " + selectedProvider.getProviderName() : 
                            "Selected Provider: " + selectedProvider.getProviderName()
                        );
                        tvSelectedProvider.setVisibility(View.VISIBLE);
                    }
                    
                    // عرض حالة المزود
                    if (tvActiveProviderStatus != null) {
                        if (selectedProvider.isActive()) {
                            tvActiveProviderStatus.setText(isArabic ? "✅ نشط" : "✅ Active");
                            tvActiveProviderStatus.setTextColor(0xFF16A34A);
                        } else {
                            tvActiveProviderStatus.setText(isArabic ? "⚠️ سيتم تفعيله عند الحفظ" : "⚠️ Will be activated on save");
                            tvActiveProviderStatus.setTextColor(0xFFF59E0B);
                        }
                    }
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        btnFetchGoldPrice.setOnClickListener(v -> fetchGoldPricesAutomatically());
        btnSaveGoldSettings.setOnClickListener(v -> saveGoldSettings());
        btnSaveCurrencySettings.setOnClickListener(v -> saveCurrencySettings());
        btnRefreshRates.setOnClickListener(v -> { 
            updateAllPrices(); 
            Toast.makeText(mContext, "✅", Toast.LENGTH_SHORT).show(); 
        });
        
        // تغيير العملة → تحديث كل الأسعار
        spGoldCurrency.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedCurrency = parent.getItemAtPosition(position).toString();
                prefs.edit().putString("preferred_currency", selectedCurrency).apply();
                updateAllPrices();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        // تغيير نوع السعر → تحديث
        if (rgExchangeRateType != null) {
            rgExchangeRateType.setOnCheckedChangeListener((group, checkedId) -> {
                useMarketRate = (checkedId == R.id.rbMarketRate);
                prefs.edit().putBoolean("use_market_rate", useMarketRate).apply();
                updateAllPrices();
            });
        }
        
        // عند تغيير سعر 24K يدوياً → تحديث بقية العيارات تلقائياً
        etGoldPrice24k.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (isUpdatingFields) return;
                double price24k = getDouble(etGoldPrice24k);
                if (price24k > 0) {
                    currentGoldPrice24kUsd = price24k;
                    autoFillKaratPrices(price24k);
                }
            }
        });
        
        // تحديث أسعار الصرف عند تغييرها
        TextWatcher rateWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateLiveCurrencyRates(); }
        };
        etExchangeYER.addTextChangedListener(rateWatcher);
        etExchangeEGP.addTextChangedListener(rateWatcher);
        etExchangeSAR.addTextChangedListener(rateWatcher);
        etExchangeAED.addTextChangedListener(rateWatcher);
        etExchangeMarketYER.addTextChangedListener(rateWatcher);
        etExchangeMarketEGP.addTextChangedListener(rateWatcher);
        etExchangeMarketSAR.addTextChangedListener(rateWatcher);
        etExchangeMarketAED.addTextChangedListener(rateWatcher);
    }

    private void loadActiveProviders() {
        pbGoldLoading.setVisibility(View.VISIBLE);
        
        executor.execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(mContext);
                providersList = client.getActiveProviders();
                
                mainHandler.post(() -> {
                    pbGoldLoading.setVisibility(View.GONE);
                    
                    if (providersList.isEmpty()) {
                        // إضافة مزودين افتراضيين
                        addDefaultProviders();
                    }
                    
                    // تحديث Spinner بأسماء المزودين
                    List<String> providerNames = new ArrayList<>();
                    for (ApiProvider provider : providersList) {
                        String name = provider.getProviderName();
                        if (provider.isActive()) {
                            name += isArabic ? " ✓" : " ✓";
                        }
                        providerNames.add(name);
                    }
                    
                    ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(
                        mContext, 
                        android.R.layout.simple_spinner_item, 
                        providerNames
                    );
                    providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spGoldApiProvider.setAdapter(providerAdapter);
                    spGoldApiProvider.setEnabled(true);
                    
                    // استعادة المزود المحفوظ أو النشط
                    int savedProviderId = prefs.getInt("selected_provider_id", -1);
                    int activeIndex = 0;
                    
                    for (int i = 0; i < providersList.size(); i++) {
                        if (providersList.get(i).isActive()) {
                            activeIndex = i;
                            break;
                        }
                    }
                    
                    if (savedProviderId != -1) {
                        for (int i = 0; i < providersList.size(); i++) {
                            if (providersList.get(i).getId() == savedProviderId) {
                                spGoldApiProvider.setSelection(i);
                                return;
                            }
                        }
                    }
                    
                    // إذا لم نجد المحفوظ، نختار النشط
                    spGoldApiProvider.setSelection(activeIndex);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading providers: " + e.getMessage());
                mainHandler.post(() -> {
                    pbGoldLoading.setVisibility(View.GONE);
                    addDefaultProviders();
                    
                    List<String> providerNames = new ArrayList<>();
                    for (ApiProvider provider : providersList) {
                        providerNames.add(provider.getProviderName());
                    }
                    
                    ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(
                        mContext, 
                        android.R.layout.simple_spinner_item, 
                        providerNames
                    );
                    providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spGoldApiProvider.setAdapter(providerAdapter);
                    spGoldApiProvider.setEnabled(true);
                    spGoldApiProvider.setSelection(0);
                });
            }
        });
    }
    
    private void addDefaultProviders() {
        String[] names = {"Metal Price", "Gold API", "Metals Dev"};
        String[] types = {"metalprice", "goldapi", "metalsdev"};
        
        for (int i = 0; i < names.length; i++) {
            ApiProvider provider = new ApiProvider();
            provider.setId(i + 1);
            provider.setProviderName(names[i]);
            provider.setProviderType(types[i]);
            provider.setApiKey("");
            provider.setActive(i == 0); // الأول نشط افتراضياً
            providersList.add(provider);
        }
    }

    private void fetchGoldPricesAutomatically() {
        if (selectedProvider == null) {
            Toast.makeText(mContext, 
                isArabic ? "يرجى اختيار مزود أولاً" : "Please select a provider first", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        pbGoldLoading.setVisibility(View.VISIBLE);
        btnFetchGoldPrice.setEnabled(false);
        tvGoldPriceStatus.setText(isArabic ? "⏳ جاري جلب الأسعار..." : "⏳ Fetching...");
        
        executor.execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(mContext);
                MiraApiClient.ApiResponse response = client.fetchGoldPriceWithProvider(selectedProvider.getId());
                
                if (response.isSuccess && response.data != null) {
                    JSONObject data = response.data.optJSONObject("data");
                    if (data == null) data = response.data;
                    JSONObject prices = data.optJSONObject("prices");
                    if (prices == null) prices = data;
                    
                    final double price24k = prices.optDouble("24k", 0);
                    
                    mainHandler.post(() -> {
                        if (price24k > 0) {
                            currentGoldPrice24kUsd = price24k;
                            etGoldPrice24k.setText(decimalFormat.format(price24k));
                            autoFillKaratPrices(price24k);
                            tvGoldPriceStatus.setText(isArabic ? "✅ تم تحديث الأسعار" : "✅ Updated");
                            tvLastUpdate.setText((isArabic ? "آخر تحديث: " : "Last: ") + 
                                new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()));
                            
                            // حفظ آخر سعر تم جلبه
                            prefs.edit().putFloat("last_gold_price_24k", (float) price24k).apply();
                            prefs.edit().putLong("last_update_time", System.currentTimeMillis()).apply();
                        } else {
                            tvGoldPriceStatus.setText(isArabic ? "❌ فشل في جلب البيانات" : "❌ Failed to fetch data");
                        }
                        pbGoldLoading.setVisibility(View.GONE);
                        btnFetchGoldPrice.setEnabled(true);
                    });
                } else {
                    mainHandler.post(() -> {
                        pbGoldLoading.setVisibility(View.GONE);
                        btnFetchGoldPrice.setEnabled(true);
                        tvGoldPriceStatus.setText(isArabic ? "❌ فشل في الاتصال" : "❌ Connection failed");
                        Toast.makeText(mContext, 
                            isArabic ? "فشل في جلب الأسعار من " + selectedProvider.getProviderName() : 
                            "Failed to fetch prices from " + selectedProvider.getProviderName(), 
                            Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching prices: " + e.getMessage());
                mainHandler.post(() -> {
                    pbGoldLoading.setVisibility(View.GONE);
                    btnFetchGoldPrice.setEnabled(true);
                    tvGoldPriceStatus.setText(isArabic ? "❌ خطأ" : "❌ Error");
                });
            }
        });
    }

    private void autoFillKaratPrices(double price24k) {
        isUpdatingFields = true;
        etGoldPrice22k.setText(decimalFormat.format(price24k * 22 / 24));
        etGoldPrice21k.setText(decimalFormat.format(price24k * 21 / 24));
        etGoldPrice18k.setText(decimalFormat.format(price24k * 18 / 24));
        etGoldPrice14k.setText(decimalFormat.format(price24k * 14 / 24));
        isUpdatingFields = false;
        updateAllPrices();
    }

    private void updateAllPrices() {
        double price24k = getDouble(etGoldPrice24k);
        if (price24k <= 0) {
            price24k = 75.00;
            etGoldPrice24k.setText(decimalFormat.format(price24k));
            autoFillKaratPrices(price24k);
        }
        
        currentGoldPrice24kUsd = price24k;
        
        // تحديث سعر الأوقية
        double ouncePriceUsd = price24k * OUNCE_TO_GRAM;
        double exchangeRate = getExchangeRate(selectedCurrency);
        double ouncePriceConverted = ouncePriceUsd * exchangeRate;
        
        String currencySymbol = getCurrencySymbol(selectedCurrency);
        if (tvOuncePrice != null) {
            tvOuncePrice.setText(String.format(Locale.US, "أوقية (31.10g): $%s", decimalFormat.format(ouncePriceUsd)));
        }
        if (tvOuncePriceConverted != null) {
            tvOuncePriceConverted.setText(String.format(Locale.US, "= %s %s", 
                largeFormat.format(ouncePriceConverted), currencySymbol));
        }
        
        updateLiveGoldPrices();
        updateLiveCurrencyRates();
    }

    private int getCurrencyPosition(String currency) {
        switch (currency) {
            case "YER": return 1;
            case "EGP": return 2;
            case "SAR": return 3;
            case "AED": return 4;
            default: return 0;
        }
    }
    
    private String getCurrencySymbol(String currency) {
        switch (currency) {
            case "YER": return "ريال";
            case "EGP": return "ج.م";
            case "SAR": return "ر.س";
            case "AED": return "د.إ";
            default: return "$";
        }
    }
    
    private double getExchangeRate(String currency) {
        switch (currency) {
            case "YER": return useMarketRate ? getDouble(etExchangeMarketYER) : getDouble(etExchangeYER);
            case "EGP": return useMarketRate ? getDouble(etExchangeMarketEGP) : getDouble(etExchangeEGP);
            case "SAR": return useMarketRate ? getDouble(etExchangeMarketSAR) : getDouble(etExchangeSAR);
            case "AED": return useMarketRate ? getDouble(etExchangeMarketAED) : getDouble(etExchangeAED);
            default: return 1.0;
        }
    }
    
    private double convertToCurrency(double usdAmount, String targetCurrency) {
        if (targetCurrency.equals("USD")) return usdAmount;
        double rate = getExchangeRate(targetCurrency);
        return rate > 0 ? usdAmount * rate : usdAmount;
    }
    
    private String formatCurrency(double amount, String currency) {
        String symbol = getCurrencySymbol(currency);
        if (currency.equals("YER") || currency.equals("EGP")) {
            return symbol + " " + largeFormat.format(amount);
        }
        return symbol + " " + decimalFormat.format(amount);
    }

    private void saveGoldSettings() {
        if (selectedProvider == null) {
            Toast.makeText(mContext, 
                isArabic ? "يرجى اختيار مزود أولاً" : "Please select a provider first", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        pbGoldLoading.setVisibility(View.VISIBLE);
        btnSaveGoldSettings.setEnabled(false);
        
        executor.execute(() -> {
            try {
                // 1️⃣ حفظ إعدادات الذهب
                JSONObject goldData = new JSONObject();
                goldData.put("gold_price_24k", getDouble(etGoldPrice24k));
                goldData.put("gold_price_22k", getDouble(etGoldPrice22k));
                goldData.put("gold_price_21k", getDouble(etGoldPrice21k));
                goldData.put("gold_price_18k", getDouble(etGoldPrice18k));
                goldData.put("gold_price_14k", getDouble(etGoldPrice14k));
                goldData.put("margin_24k", getDouble(etMargin24k));
                goldData.put("margin_22k", getDouble(etMargin22k));
                goldData.put("margin_21k", getDouble(etMargin21k));
                goldData.put("margin_18k", getDouble(etMargin18k));
                goldData.put("margin_14k", getDouble(etMargin14k));
                goldData.put("margin_12k", getDouble(etMargin12k));
                goldData.put("making_24k", getDouble(etMaking24k));
                goldData.put("making_22k", getDouble(etMaking22k));
                goldData.put("making_21k", getDouble(etMaking21k));
                goldData.put("making_18k", getDouble(etMaking18k));
                goldData.put("making_14k", getDouble(etMaking14k));
                goldData.put("making_12k", getDouble(etMaking12k));
                goldData.put("provider_id", selectedProvider.getId());
                goldData.put("gold_default_currency", spGoldCurrency.getSelectedItem().toString());
                
                // حفظ محلي
                prefs.edit().putString("gold_settings", goldData.toString()).apply();
                prefs.edit().putInt("selected_provider_id", selectedProvider.getId()).apply();
                
                MiraApiClient client = MiraApiClient.getInstance(mContext);
                
                // 2️⃣ حفظ إعدادات الذهب في السيرفر
                MiraApiClient.ApiResponse goldResponse = client.saveGoldSettings(goldData);
                
                // 3️⃣ تفعيل المزود المختار في السيرفر
                MiraApiClient.ApiResponse activateResponse = client.activateProvider(selectedProvider.getId());
                
                final boolean serverSaved = goldResponse != null && goldResponse.isSuccess;
                final boolean providerActivated = activateResponse != null && activateResponse.isSuccess;
                
                // 4️⃣ تحديث حالة المزودين محلياً
                for (ApiProvider provider : providersList) {
                    provider.setActive(provider.getId() == selectedProvider.getId());
                }
                
                mainHandler.post(() -> {
                    pbGoldLoading.setVisibility(View.GONE);
                    btnSaveGoldSettings.setEnabled(true);
                    
                    String message;
                    if (serverSaved && providerActivated) {
                        message = isArabic ? "✅ تم الحفظ وتفعيل المزود" : "✅ Saved & provider activated";
                    } else if (serverSaved) {
                        message = isArabic ? "⚠️ تم الحفظ ولكن فشل تفعيل المزود" : "⚠️ Saved but provider activation failed";
                    } else {
                        message = isArabic ? "⚠️ تم الحفظ محلياً" : "⚠️ Saved locally";
                    }
                    
                    Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show();
                    
                    // تحديث واجهة المزودين
                    updateProvidersUI();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error saving gold settings: " + e.getMessage());
                mainHandler.post(() -> {
                    pbGoldLoading.setVisibility(View.GONE);
                    btnSaveGoldSettings.setEnabled(true);
                });
            }
        });
    }
    
    private void updateProvidersUI() {
        if (providersList.isEmpty()) return;
        
        List<String> providerNames = new ArrayList<>();
        for (ApiProvider provider : providersList) {
            String name = provider.getProviderName();
            if (provider.isActive()) {
                name += isArabic ? " ✓" : " ✓";
            }
            providerNames.add(name);
        }
        
        ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(
            mContext, 
            android.R.layout.simple_spinner_item, 
            providerNames
        );
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spGoldApiProvider.setAdapter(providerAdapter);
        
        // الحفاظ على اختيار المزود
        for (int i = 0; i < providersList.size(); i++) {
            if (providersList.get(i).getId() == selectedProvider.getId()) {
                spGoldApiProvider.setSelection(i);
                break;
            }
        }
        
        // تحديث حالة العرض
        if (tvActiveProviderStatus != null && selectedProvider != null) {
            if (selectedProvider.isActive()) {
                tvActiveProviderStatus.setText(isArabic ? "✅ نشط" : "✅ Active");
                tvActiveProviderStatus.setTextColor(0xFF16A34A);
            } else {
                tvActiveProviderStatus.setText(isArabic ? "⚠️ غير نشط" : "⚠️ Inactive");
                tvActiveProviderStatus.setTextColor(0xFFF59E0B);
            }
        }
    }

    private void saveCurrencySettings() {
        pbCurrencyLoading.setVisibility(View.VISIBLE);
        btnSaveCurrencySettings.setEnabled(false);
        
        executor.execute(() -> {
            try {
                JSONObject currencyData = new JSONObject();
                currencyData.put("global_default_currency", spDefaultCurrency.getSelectedItem().toString());
                currencyData.put("chart_currency", spChartCurrency.getSelectedItem().toString());
                currencyData.put("use_market_rate", useMarketRate);
                currencyData.put("exchange_official_YER", getDouble(etExchangeYER));
                currencyData.put("exchange_official_EGP", getDouble(etExchangeEGP));
                currencyData.put("exchange_official_SAR", getDouble(etExchangeSAR));
                currencyData.put("exchange_official_AED", getDouble(etExchangeAED));
                currencyData.put("exchange_market_YER", getDouble(etExchangeMarketYER));
                currencyData.put("exchange_market_EGP", getDouble(etExchangeMarketEGP));
                currencyData.put("exchange_market_SAR", getDouble(etExchangeMarketSAR));
                currencyData.put("exchange_market_AED", getDouble(etExchangeMarketAED));
                
                prefs.edit().putString("currency_settings", currencyData.toString()).apply();
                prefs.edit().putBoolean("use_market_rate", useMarketRate).apply();
                
                MiraApiClient client = MiraApiClient.getInstance(mContext);
                MiraApiClient.ApiResponse response = client.saveCurrencySettings(currencyData);
                
                final boolean serverSaved = response != null && response.isSuccess;
                mainHandler.post(() -> {
                    pbCurrencyLoading.setVisibility(View.GONE);
                    btnSaveCurrencySettings.setEnabled(true);
                    Toast.makeText(mContext, 
                        serverSaved ? (isArabic ? "✅ تم الحفظ" : "✅ Saved") : (isArabic ? "⚠️ تم الحفظ محلياً" : "⚠️ Saved locally"), 
                        Toast.LENGTH_SHORT).show();
                    updateAllPrices();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error saving currency settings: " + e.getMessage());
                mainHandler.post(() -> {
                    pbCurrencyLoading.setVisibility(View.GONE);
                    btnSaveCurrencySettings.setEnabled(true);
                });
            }
        });
    }

    private void updateLiveGoldPrices() {
        String currencySymbol = getCurrencySymbol(selectedCurrency);
        String karatLabel = isArabic ? "قيراط" : "K";
        String perGram = isArabic ? "جرام" : "g";
        
        double price24kUsd = getDouble(etGoldPrice24k);
        double margin24k = getDouble(etMargin24k);
        double making24k = getDouble(etMaking24k);
        
        if (price24kUsd > 0) {
            double final24kUsd = price24kUsd + (price24kUsd * margin24k / 100) + making24k;
            double final24kConverted = convertToCurrency(final24kUsd, selectedCurrency);
            tvLiveGold24k.setText(String.format(Locale.US, "24%s: %s/%s\n($%s/%s)", karatLabel, 
                formatCurrency(final24kConverted, selectedCurrency), perGram, decimalFormat.format(final24kUsd), perGram));
            
            double price22kUsd = price24kUsd * 22 / 24;
            double final22kUsd = price22kUsd + (price22kUsd * getDouble(etMargin22k) / 100) + getDouble(etMaking22k);
            tvLiveGold22k.setText(String.format(Locale.US, "22%s: %s/%s\n($%s/%s)", karatLabel, 
                formatCurrency(convertToCurrency(final22kUsd, selectedCurrency), selectedCurrency), perGram, decimalFormat.format(final22kUsd), perGram));
            
            double price21kUsd = price24kUsd * 21 / 24;
            double final21kUsd = price21kUsd + (price21kUsd * getDouble(etMargin21k) / 100) + getDouble(etMaking21k);
            tvLiveGold21k.setText(String.format(Locale.US, "21%s: %s/%s\n($%s/%s)", karatLabel, 
                formatCurrency(convertToCurrency(final21kUsd, selectedCurrency), selectedCurrency), perGram, decimalFormat.format(final21kUsd), perGram));
            
            double price18kUsd = price24kUsd * 18 / 24;
            double final18kUsd = price18kUsd + (price18kUsd * getDouble(etMargin18k) / 100) + getDouble(etMaking18k);
            tvLiveGold18k.setText(String.format(Locale.US, "18%s: %s/%s\n($%s/%s)", karatLabel, 
                formatCurrency(convertToCurrency(final18kUsd, selectedCurrency), selectedCurrency), perGram, decimalFormat.format(final18kUsd), perGram));
        }
    }

    private void updateLiveCurrencyRates() {
        String rateType = useMarketRate ? (isArabic ? "سعر السوق" : "Market") : (isArabic ? "السعر الرسمي" : "Official");
        
        tvLiveUSD_YER.setText(String.format(Locale.US, "1 USD = %s ريال (%s)", largeFormat.format(getExchangeRate("YER")), rateType));
        tvLiveUSD_EGP.setText(String.format(Locale.US, "1 USD = %s ج.م (%s)", decimalFormat.format(getExchangeRate("EGP")), rateType));
        tvLiveUSD_SAR.setText(String.format(Locale.US, "1 USD = %s ر.س (%s)", decimalFormat.format(getExchangeRate("SAR")), rateType));
        tvLiveUSD_AED.setText(String.format(Locale.US, "1 USD = %s د.إ (%s)", decimalFormat.format(getExchangeRate("AED")), rateType));
    }

    private void loadLocalSettings() {
        String goldSettingsJson = prefs.getString("gold_settings", "");
        if (!goldSettingsJson.isEmpty()) {
            try { applyGoldSettings(new JSONObject(goldSettingsJson)); } catch (Exception ignored) {}
        }
        String currencySettingsJson = prefs.getString("currency_settings", "");
        if (!currencySettingsJson.isEmpty()) {
            try { applyCurrencySettings(new JSONObject(currencySettingsJson)); } catch (Exception ignored) {}
        }
        
        // استعادة آخر سعر تم جلبه إذا كان حديثاً
        long lastUpdateTime = prefs.getLong("last_update_time", 0);
        float lastGoldPrice = prefs.getFloat("last_gold_price_24k", 0);
        if (lastGoldPrice > 0 && (System.currentTimeMillis() - lastUpdateTime) < 3600000) {
            etGoldPrice24k.setText(decimalFormat.format(lastGoldPrice));
            autoFillKaratPrices(lastGoldPrice);
        }
        
        updateAllPrices();
    }

    private void loadServerSettings() {
        executor.execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(mContext);
                MiraApiClient.ApiResponse response = client.getGoldCurrencySettings();
                if (response.isSuccess && response.data != null) {
                    JSONObject data = response.data.optJSONObject("data");
                    if (data == null) data = response.data;
                    JSONObject goldData = data.optJSONObject("gold");
                    JSONObject currencyData = data.optJSONObject("currency");
                    mainHandler.post(() -> {
                        if (goldData != null) applyGoldSettings(goldData);
                        if (currencyData != null) applyCurrencySettings(currencyData);
                        updateAllPrices();
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
            
            if (json.has("gold_default_currency")) {
                spGoldCurrency.setSelection(getCurrencyPosition(json.optString("gold_default_currency", "USD")));
            }
            
            // استعادة معرف المزود المحفوظ
            if (json.has("provider_id")) {
                int providerId = json.optInt("provider_id");
                prefs.edit().putInt("selected_provider_id", providerId).apply();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error applying gold settings: " + e.getMessage());
        }
    }

    private void applyCurrencySettings(JSONObject json) {
        try {
            if (json == null) return;
            if (json.has("global_default_currency")) {
                spDefaultCurrency.setSelection(getCurrencyPosition(json.optString("global_default_currency", "USD")));
            }
            if (json.has("chart_currency")) {
                spChartCurrency.setSelection(getCurrencyPosition(json.optString("chart_currency", "USD")));
            }
            if (json.has("use_market_rate")) {
                useMarketRate = json.optBoolean("use_market_rate", false);
                if (rgExchangeRateType != null) {
                    rgExchangeRateType.check(useMarketRate ? R.id.rbMarketRate : R.id.rbOfficialRate);
                }
            }
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

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
            }
