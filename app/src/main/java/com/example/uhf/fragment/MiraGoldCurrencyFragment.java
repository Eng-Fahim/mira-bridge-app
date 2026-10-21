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
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.model.ApiProvider;
import com.example.uhf.utils.MiraExecutorService;

import org.json.JSONObject;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

public class MiraGoldCurrencyFragment extends Fragment {

    private static final String TAG = "MiraGoldCurrency";
    private static final String PREFS_NAME = "MIRA_GOLD_SETTINGS";
    private static final double OUNCE_TO_GRAM = 31.1035;

    private PermissionManager permissionManager;
    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    
    private final DecimalFormat decimalFormat = new DecimalFormat("#,##0.00", new DecimalFormatSymbols(Locale.US));
    private final DecimalFormat largeFormat = new DecimalFormat("#,##0", new DecimalFormatSymbols(Locale.US));
    
    private boolean isArabic;
    private String selectedCurrency = "USD";
    private boolean useMarketRate = false;
    private boolean isUpdatingFields = false;
    private double currentGoldPrice24kUsd = 0; // لا نضع سعر افتراضي
    
    private List<ApiProvider> providersList = new ArrayList<>();
    private ApiProvider selectedProvider;
    private TextView tvSelectedProvider;
    private TextView tvActiveProviderStatus;

    private TextView tvGoldPriceStatus, tvLastUpdate, tvOuncePrice, tvOuncePriceConverted;
    private EditText etGoldPrice24k, etGoldPrice22k, etGoldPrice21k, etGoldPrice18k, etGoldPrice14k;
    private EditText etMargin24k, etMargin22k, etMargin21k, etMargin18k, etMargin14k, etMargin12k;
    private EditText etMaking24k, etMaking22k, etMaking21k, etMaking18k, etMaking14k, etMaking12k;
    private Spinner spGoldApiProvider, spGoldCurrency;
    private EditText etGoldApiKey;
    private Button btnFetchGoldPrice, btnSaveGoldSettings;
    private ProgressBar pbGoldLoading;

    private Spinner spDefaultCurrency, spChartCurrency;
    private EditText etExchangeYER, etExchangeEGP, etExchangeSAR, etExchangeAED;
    private EditText etExchangeMarketYER, etExchangeMarketEGP, etExchangeMarketSAR, etExchangeMarketAED;
    private Button btnSaveCurrencySettings, btnRefreshRates;
    private ProgressBar pbCurrencyLoading;
    private RadioGroup rgExchangeRateType;
    private RadioButton rbOfficialRate, rbMarketRate;

    private TextView tvLiveGold24k, tvLiveGold22k, tvLiveGold21k, tvLiveGold18k;
    private TextView tvLiveUSD_YER, tvLiveUSD_EGP, tvLiveUSD_SAR, tvLiveUSD_AED;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_gold_currency, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        try {
            prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            permissionManager = PermissionManager.getInstance(requireContext());
            isArabic = Locale.getDefault().getLanguage().equals("ar");
            selectedCurrency = prefs.getString("preferred_currency", "USD");
            useMarketRate = prefs.getBoolean("use_market_rate", false);

            // ✅ استخدام المحرك الموحد لتفادي مشاكل الإغلاق
            executor = MiraExecutorService.getInstance().getNetworkExecutor();
            if (executor == null || executor.isShutdown()) {
                executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            }

            initViews(view);
            setupSpinners();
            setupListeners();
            applyPermissions();
            loadActiveProviders();
            loadLocalSettings();
            loadServerSettings();
            updateAllPrices();
            
        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error", e);
            Toast.makeText(getContext(), "Error initializing screen", Toast.LENGTH_SHORT).show();
        }
    }

    private void applyPermissions() {
        if (!isAdded()) return;
        boolean canEdit = permissionManager.canEdit("gold_rates") || permissionManager.isAdmin() || permissionManager.isBranchManager();
        
        if (btnFetchGoldPrice != null) btnFetchGoldPrice.setEnabled(canEdit);
        if (btnSaveGoldSettings != null) btnSaveGoldSettings.setEnabled(canEdit);
        if (btnSaveCurrencySettings != null) btnSaveCurrencySettings.setEnabled(canEdit);
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
            String text = normalizeArabicNumbers(et.getText().toString().trim());
            if (text.isEmpty()) return 0;
            return Double.parseDouble(text);
        } catch (Exception e) {
            return 0;
        }
    }

    // ✅ إصلاح: السماح بالصفر كقيمة صحيحة (للهوامش والمصنعية)
    private double getDoubleOrDefault(EditText et, double defaultValue) {
        if (et == null) return defaultValue;
        String text = et.getText().toString().trim();
        if (text.isEmpty() || text.equals(".")) return defaultValue;
        try {
            double value = Double.parseDouble(normalizeArabicNumbers(text));
            return Math.max(value, 0); // يسمح بالصفر، يمنع السالب
        } catch (Exception e) {
            return defaultValue;
        }
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
        
        if (etGoldApiKey != null) {
            etGoldApiKey.setVisibility(View.GONE);
            etGoldApiKey.setEnabled(false);
        }
        
        if (rgExchangeRateType != null) {
            rgExchangeRateType.check(useMarketRate ? R.id.rbMarketRate : R.id.rbOfficialRate);
        }
    }

    private void setupSpinners() {
        if (!isAdded()) return;
        String[] currencies = {"USD", "YER", "EGP", "SAR", "AED"};
        ArrayAdapter<String> currencyAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, currencies);
        currencyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        
        spGoldCurrency.setAdapter(currencyAdapter);
        spDefaultCurrency.setAdapter(currencyAdapter);
        spChartCurrency.setAdapter(currencyAdapter);
        
        int pos = getCurrencyPosition(selectedCurrency);
        spGoldCurrency.setSelection(pos);
        spDefaultCurrency.setSelection(pos);
        spChartCurrency.setSelection(pos);
        
        List<String> emptyList = new ArrayList<>();
        emptyList.add(isArabic ? "جاري التحميل..." : "Loading...");
        ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, emptyList);
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spGoldApiProvider.setAdapter(providerAdapter);
        spGoldApiProvider.setEnabled(false);
    }

    private void setupListeners() {
        spGoldApiProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < providersList.size()) {
                    selectedProvider = providersList.get(position);
                    
                    if (tvSelectedProvider != null) {
                        tvSelectedProvider.setText(
                            isArabic ? 
                            "المزود المحدد: " + selectedProvider.getProviderName() : 
                            "Selected Provider: " + selectedProvider.getProviderName()
                        );
                        tvSelectedProvider.setVisibility(View.VISIBLE);
                    }
                    
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
        
        btnFetchGoldPrice.setOnClickListener(v -> {
            if (permissionManager.canEdit("gold_rates") || permissionManager.isAdmin() || permissionManager.isBranchManager()) {
                fetchGoldPricesAutomatically();
            } else {
                Toast.makeText(getContext(), "⛔ غير مصرح", Toast.LENGTH_SHORT).show();
            }
        });
        
        btnSaveGoldSettings.setOnClickListener(v -> {
            if (permissionManager.canEdit("gold_rates") || permissionManager.isAdmin() || permissionManager.isBranchManager()) {
                saveGoldSettings();
            } else {
                Toast.makeText(getContext(), "⛔ غير مصرح", Toast.LENGTH_SHORT).show();
            }
        });
        
        btnSaveCurrencySettings.setOnClickListener(v -> {
            if (permissionManager.canEdit("gold_rates") || permissionManager.isAdmin() || permissionManager.isBranchManager()) {
                saveCurrencySettings();
            } else {
                Toast.makeText(getContext(), "⛔ غير مصرح", Toast.LENGTH_SHORT).show();
            }
        });
        
        btnRefreshRates.setOnClickListener(v -> { 
            updateAllPrices(); 
            Toast.makeText(getContext(), "✅", Toast.LENGTH_SHORT).show(); 
        });
        
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
        
        if (rgExchangeRateType != null) {
            rgExchangeRateType.setOnCheckedChangeListener((group, checkedId) -> {
                useMarketRate = (checkedId == R.id.rbMarketRate);
                prefs.edit().putBoolean("use_market_rate", useMarketRate).apply();
                updateAllPrices();
            });
        }
        
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
        if (!isAdded()) return;
        pbGoldLoading.setVisibility(View.VISIBLE);
        
        executor.execute(() -> {
            try {
                Context appCtx = requireContext().getApplicationContext();
                MiraApiClient client = MiraApiClient.getInstance(appCtx);
                providersList = client.getActiveProviders();
                
                mainHandler.post(() -> {
                    if (!isAdded() || getContext() == null) return; // ✅ حماية
                    pbGoldLoading.setVisibility(View.GONE);
                    
                    if (providersList.isEmpty()) {
                        addDefaultProviders();
                    }
                    
                    List<String> providerNames = new ArrayList<>();
                    for (ApiProvider provider : providersList) {
                        String name = provider.getProviderName();
                        if (provider.isActive()) {
                            name += isArabic ? " ✓" : " ✓";
                        }
                        providerNames.add(name);
                    }
                    
                    ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(
                        requireContext(), 
                        android.R.layout.simple_spinner_item, 
                        providerNames
                    );
                    providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spGoldApiProvider.setAdapter(providerAdapter);
                    spGoldApiProvider.setEnabled(true);
                    
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
                    
                    spGoldApiProvider.setSelection(activeIndex);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading providers: " + e.getMessage());
                mainHandler.post(() -> {
                    if (!isAdded() || getContext() == null) return; // ✅ حماية
                    pbGoldLoading.setVisibility(View.GONE);
                    addDefaultProviders();
                    
                    List<String> providerNames = new ArrayList<>();
                    for (ApiProvider provider : providersList) {
                        providerNames.add(provider.getProviderName());
                    }
                    
                    ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(
                        requireContext(), 
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
            provider.setActive(i == 0);
            providersList.add(provider);
        }
    }

    private void fetchGoldPricesAutomatically() {
        if (!isAdded() || selectedProvider == null) {
            Toast.makeText(getContext(), isArabic ? "يرجى اختيار مزود أولاً" : "Please select a provider first", Toast.LENGTH_SHORT).show();
            return;
        }
        
        pbGoldLoading.setVisibility(View.VISIBLE);
        btnFetchGoldPrice.setEnabled(false);
        tvGoldPriceStatus.setText(isArabic ? "⏳ جاري جلب الأسعار..." : "⏳ Fetching...");
        
        executor.execute(() -> {
            try {
                Context appCtx = requireContext().getApplicationContext();
                MiraApiClient client = MiraApiClient.getInstance(appCtx);
                MiraApiClient.ApiResponse response = client.fetchGoldPriceWithProvider(selectedProvider.getId());
                
                if (response.isSuccess && response.data != null) {
                    JSONObject data = response.data.optJSONObject("data");
                    if (data == null) data = response.data;
                    JSONObject prices = data.optJSONObject("prices");
                    if (prices == null) prices = data;
                    
                    final double price24k = prices.optDouble("24k", 0);
                    
                    mainHandler.post(() -> {
                        if (!isAdded() || getContext() == null) return; // ✅ حماية
                        if (price24k > 0) {
                            currentGoldPrice24kUsd = price24k;
                            etGoldPrice24k.setText(decimalFormat.format(price24k));
                            autoFillKaratPrices(price24k);
                            tvGoldPriceStatus.setText(isArabic ? "✅ تم تحديث الأسعار" : "✅ Updated");
                            tvLastUpdate.setText((isArabic ? "آخر تحديث: " : "Last: ") + 
                                new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()));
                            
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
                        if (!isAdded() || getContext() == null) return; // ✅ حماية
                        pbGoldLoading.setVisibility(View.GONE);
                        btnFetchGoldPrice.setEnabled(true);
                        tvGoldPriceStatus.setText(isArabic ? "❌ فشل في الاتصال" : "❌ Connection failed");
                        Toast.makeText(getContext(), isArabic ? "فشل في جلب الأسعار من " + selectedProvider.getProviderName() : "Failed to fetch prices from " + selectedProvider.getProviderName(), Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching prices: " + e.getMessage());
                mainHandler.post(() -> {
                    if (!isAdded() || getContext() == null) return; // ✅ حماية
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
        if (!isAdded()) return;
        double price24k = getDouble(etGoldPrice24k);
        
        // ✅ إصلاح خطير: منع استخدام سعر وهمي 75.00
        if (price24k <= 0) {
            if (tvGoldPriceStatus != null) {
                tvGoldPriceStatus.setText(isArabic ? "⚠️ يرجى جلب سعر الذهب أولاً" : "⚠️ Please fetch gold price first");
                tvGoldPriceStatus.setTextColor(0xFFDC2626);
            }
            return; // إيقاف الحسابات
        }
        
        currentGoldPrice24kUsd = price24k;
        
        double ouncePriceUsd = price24k * OUNCE_TO_GRAM;
        double exchangeRate = getExchangeRate(selectedCurrency);
        double ouncePriceConverted = ouncePriceUsd * exchangeRate;
        
        String currencySymbol = getCurrencySymbol(selectedCurrency);
        if (tvOuncePrice != null) {
            tvOuncePrice.setText(String.format(Locale.US, "أوقية (31.10g): $%s", decimalFormat.format(ouncePriceUsd)));
        }
        if (tvOuncePriceConverted != null) {
            tvOuncePriceConverted.setText(String.format(Locale.US, "= %s %s", largeFormat.format(ouncePriceConverted), currencySymbol));
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
        if (!isAdded() || selectedProvider == null) {
            Toast.makeText(getContext(), isArabic ? "يرجى اختيار مزود أولاً" : "Please select a provider first", Toast.LENGTH_SHORT).show();
            return;
        }
        
        pbGoldLoading.setVisibility(View.VISIBLE);
        btnSaveGoldSettings.setEnabled(false);
        
        executor.execute(() -> {
            try {
                JSONObject goldData = new JSONObject();
                goldData.put("gold_price_24k", getDouble(etGoldPrice24k));
                goldData.put("gold_price_22k", getDouble(etGoldPrice22k));
                goldData.put("gold_price_21k", getDouble(etGoldPrice21k));
                goldData.put("gold_price_18k", getDouble(etGoldPrice18k));
                goldData.put("gold_price_14k", getDouble(etGoldPrice14k));
                
                double margin24k = getDouble(etMargin24k);
                double making24k = getDouble(etMaking24k);
                
                goldData.put("margin_24k", margin24k > 0 ? margin24k : 95.00);
                goldData.put("margin_22k", getDoubleOrDefault(etMargin22k, 88.00));
                goldData.put("margin_21k", getDoubleOrDefault(etMargin21k, 90.00));
                goldData.put("margin_18k", getDoubleOrDefault(etMargin18k, 100.80));
                goldData.put("margin_14k", getDoubleOrDefault(etMargin14k, 105.80));
                goldData.put("margin_12k", getDoubleOrDefault(etMargin12k, 110.00));
                
                goldData.put("making_24k", making24k > 0 ? making24k : 5.00);
                goldData.put("making_22k", getDoubleOrDefault(etMaking22k, 8.00));
                goldData.put("making_21k", getDoubleOrDefault(etMaking21k, 10.00));
                goldData.put("making_18k", getDoubleOrDefault(etMaking18k, 12.00));
                goldData.put("making_14k", getDoubleOrDefault(etMaking14k, 15.00));
                goldData.put("making_12k", getDoubleOrDefault(etMaking12k, 18.00));
                
                goldData.put("provider_id", selectedProvider.getId());
                goldData.put("gold_default_currency", spGoldCurrency.getSelectedItem().toString());
                
                Context appCtx = requireContext().getApplicationContext();
                MiraApiClient client = MiraApiClient.getInstance(appCtx);
                
                MiraApiClient.ApiResponse goldResponse = client.saveGoldSettings(goldData);
                MiraApiClient.ApiResponse activateResponse = client.activateProvider(selectedProvider.getId());
                
                final boolean serverSaved = goldResponse != null && goldResponse.isSuccess;
                final boolean providerActivated = activateResponse != null && activateResponse.isSuccess;
                
                // ✅ إصلاح: لا نحفظ محلياً إلا بعد نجاح السيرفر
                if (serverSaved) {
                    prefs.edit().putString("gold_settings", goldData.toString()).apply();
                    prefs.edit().putInt("selected_provider_id", selectedProvider.getId()).apply();
                    for (ApiProvider provider : providersList) {
                        provider.setActive(provider.getId() == selectedProvider.getId());
                    }
                }
                
                final String resultMessage;
                if (serverSaved) {
                    resultMessage = isArabic ? "✅ تم الحفظ في السيرفر" : "✅ Saved to server";
                } else {
                    String error = goldResponse != null ? goldResponse.error : "null response";
                    resultMessage = (isArabic ? "❌ فشل الحفظ: " : "❌ Save failed: ") + error;
                }
                
                mainHandler.post(() -> {
                    if (!isAdded() || getContext() == null) return; // ✅ حماية
                    pbGoldLoading.setVisibility(View.GONE);
                    btnSaveGoldSettings.setEnabled(true);
                    Toast.makeText(getContext(), resultMessage, Toast.LENGTH_LONG).show();
                    if (serverSaved) updateProvidersUI();
                });
                
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isAdded() || getContext() == null) return; // ✅ حماية
                    pbGoldLoading.setVisibility(View.GONE);
                    btnSaveGoldSettings.setEnabled(true);
                    Toast.makeText(getContext(), (isArabic ? "❌ خطأ: " : "❌ Error: ") + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void updateProvidersUI() {
        if (!isAdded() || providersList.isEmpty()) return;
        
        List<String> providerNames = new ArrayList<>();
        for (ApiProvider provider : providersList) {
            String name = provider.getProviderName();
            if (provider.isActive()) {
                name += isArabic ? " ✓" : " ✓";
            }
            providerNames.add(name);
        }
        
        ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(
            requireContext(), 
            android.R.layout.simple_spinner_item, 
            providerNames
        );
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spGoldApiProvider.setAdapter(providerAdapter);
        
        for (int i = 0; i < providersList.size(); i++) {
            if (providersList.get(i).getId() == selectedProvider.getId()) {
                spGoldApiProvider.setSelection(i);
                break;
            }
        }
        
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
        if (!isAdded()) return;
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
                
                Context appCtx = requireContext().getApplicationContext();
                MiraApiClient client = MiraApiClient.getInstance(appCtx);
                MiraApiClient.ApiResponse response = client.saveCurrencySettings(currencyData);
                
                final boolean serverSaved = response != null && response.isSuccess;
                
                if (serverSaved) {
                    prefs.edit().putString("currency_settings", currencyData.toString()).apply();
                    prefs.edit().putBoolean("use_market_rate", useMarketRate).apply();
                }
                
                mainHandler.post(() -> {
                    if (!isAdded() || getContext() == null) return; // ✅ حماية
                    pbCurrencyLoading.setVisibility(View.GONE);
                    btnSaveCurrencySettings.setEnabled(true);
                    Toast.makeText(getContext(), 
                        serverSaved ? (isArabic ? "✅ تم الحفظ" : "✅ Saved") : (isArabic ? "⚠️ فشل الحفظ في السيرفر" : "⚠️ Failed to save on server"), 
                        Toast.LENGTH_SHORT).show();
                    if (serverSaved) updateAllPrices();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error saving currency settings: " + e.getMessage());
                mainHandler.post(() -> {
                    if (!isAdded() || getContext() == null) return; // ✅ حماية
                    pbCurrencyLoading.setVisibility(View.GONE);
                    btnSaveCurrencySettings.setEnabled(true);
                });
            }
        });
    }

    private void updateLiveGoldPrices() {
        if (!isAdded()) return;
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
        if (!isAdded()) return;
        String rateType = useMarketRate ? (isArabic ? "سعر السوق" : "Market") : (isArabic ? "السعر الرسمي" : "Official");
        
        tvLiveUSD_YER.setText(String.format(Locale.US, "1 USD = %s ريال (%s)", largeFormat.format(getExchangeRate("YER")), rateType));
        tvLiveUSD_EGP.setText(String.format(Locale.US, "1 USD = %s ج.م (%s)", decimalFormat.format(getExchangeRate("EGP")), rateType));
        tvLiveUSD_SAR.setText(String.format(Locale.US, "1 USD = %s ر.س (%s)", decimalFormat.format(getExchangeRate("SAR")), rateType));
        tvLiveUSD_AED.setText(String.format(Locale.US, "1 USD = %s د.إ (%s)", decimalFormat.format(getExchangeRate("AED")), rateType));
    }

    private void applyGoldSettings(JSONObject json) {
        try {
            if (json == null || !isAdded()) return;
            
            Log.d(TAG, "Applying gold settings: " + json.toString());
            
            if (json.has("gold_price_24k")) {
                double val = json.optDouble("gold_price_24k", 0);
                if (val > 0) etGoldPrice24k.setText(decimalFormat.format(val));
            }
            if (json.has("gold_price_22k")) {
                double val = json.optDouble("gold_price_22k", 0);
                if (val > 0) etGoldPrice22k.setText(decimalFormat.format(val));
            }
            if (json.has("gold_price_21k")) {
                double val = json.optDouble("gold_price_21k", 0);
                if (val > 0) etGoldPrice21k.setText(decimalFormat.format(val));
            }
            if (json.has("gold_price_18k")) {
                double val = json.optDouble("gold_price_18k", 0);
                if (val > 0) etGoldPrice18k.setText(decimalFormat.format(val));
            }
            if (json.has("gold_price_14k")) {
                double val = json.optDouble("gold_price_14k", 0);
                if (val > 0) etGoldPrice14k.setText(decimalFormat.format(val));
            }
            
            // تحميل الهوامش (يسمح بالصفر)
            if (json.has("margin_24k")) etMargin24k.setText(decimalFormat.format(json.optDouble("margin_24k", 0)));
            if (json.has("margin_22k")) etMargin22k.setText(decimalFormat.format(json.optDouble("margin_22k", 0)));
            if (json.has("margin_21k")) etMargin21k.setText(decimalFormat.format(json.optDouble("margin_21k", 0)));
            if (json.has("margin_18k")) etMargin18k.setText(decimalFormat.format(json.optDouble("margin_18k", 0)));
            if (json.has("margin_14k")) etMargin14k.setText(decimalFormat.format(json.optDouble("margin_14k", 0)));
            if (json.has("margin_12k")) etMargin12k.setText(decimalFormat.format(json.optDouble("margin_12k", 0)));
            
            // تحميل المصنعية (يسمح بالصفر)
            if (json.has("making_24k")) etMaking24k.setText(decimalFormat.format(json.optDouble("making_24k", 0)));
            if (json.has("making_22k")) etMaking22k.setText(decimalFormat.format(json.optDouble("making_22k", 0)));
            if (json.has("making_21k")) etMaking21k.setText(decimalFormat.format(json.optDouble("making_21k", 0)));
            if (json.has("making_18k")) etMaking18k.setText(decimalFormat.format(json.optDouble("making_18k", 0)));
            if (json.has("making_14k")) etMaking14k.setText(decimalFormat.format(json.optDouble("making_14k", 0)));
            if (json.has("making_12k")) etMaking12k.setText(decimalFormat.format(json.optDouble("making_12k", 0)));
            
            if (json.has("gold_default_currency")) {
                spGoldCurrency.setSelection(getCurrencyPosition(json.optString("gold_default_currency", "USD")));
            }
            if (json.has("provider_id")) {
                int providerId = json.optInt("provider_id");
                prefs.edit().putInt("selected_provider_id", providerId).apply();
            }
            
            updateAllPrices();
        } catch (Exception e) {
            Log.e(TAG, "Error applying gold settings: " + e.getMessage(), e);
        }
    }

    private void loadLocalSettings() {
        if (!isAdded()) return;
        String goldSettingsJson = prefs.getString("gold_settings", "");
        if (!goldSettingsJson.isEmpty()) {
            try { applyGoldSettings(new JSONObject(goldSettingsJson)); } catch (Exception ignored) {}
        }
        String currencySettingsJson = prefs.getString("currency_settings", "");
        if (!currencySettingsJson.isEmpty()) {
            try { applyCurrencySettings(new JSONObject(currencySettingsJson)); } catch (Exception ignored) {}
        }
        
        long lastUpdateTime = prefs.getLong("last_update_time", 0);
        float lastGoldPrice = prefs.getFloat("last_gold_price_24k", 0);
        if (lastGoldPrice > 0 && (System.currentTimeMillis() - lastUpdateTime) < 3600000) {
            etGoldPrice24k.setText(decimalFormat.format(lastGoldPrice));
            autoFillKaratPrices(lastGoldPrice);
        }
        
        updateAllPrices();
    }

    private void loadServerSettings() {
        if (!isAdded()) return;
        executor.execute(() -> {
            try {
                Context appCtx = requireContext().getApplicationContext();
                MiraApiClient client = MiraApiClient.getInstance(appCtx);
                MiraApiClient.ApiResponse response = client.getGoldCurrencySettings();
                
                if (response != null && response.isSuccess && response.data != null) {
                    Log.d(TAG, "Server settings response: " + response.data.toString());
                    
                    JSONObject data = response.data.optJSONObject("data");
                    if (data == null) data = response.data;
                    
                    JSONObject goldData = data.optJSONObject("gold");
                    if (goldData == null) goldData = data;
                    
                    JSONObject currencyData = data.optJSONObject("currency");
                    
                    final JSONObject finalGoldData = goldData;
                    final JSONObject finalCurrencyData = currencyData;
                    
                    mainHandler.post(() -> {
                        if (!isAdded() || getContext() == null) return; // ✅ حماية
                        if (finalGoldData != null) applyGoldSettings(finalGoldData);
                        if (finalCurrencyData != null) applyCurrencySettings(finalCurrencyData);
                        updateAllPrices();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading server settings: " + e.getMessage());
            }
        });
    }

    private void applyCurrencySettings(JSONObject json) {
        try {
            if (json == null || !isAdded()) return;
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
    public void onDestroyView() {
        super.onDestroyView();
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
    }
}
