package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.adapter.MiraAccountingAdapter;
import com.example.uhf.model.MiraAccountingItem;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.GET;

public class MiraAccountingFragment extends Fragment {

    private boolean isArabic;

    // 1. عناصر الواجهة (UI Elements)
    private TextView tvAccountingTitle, tvActiveBranch, tvTodayRevenue, tvTotalJournalVolume, tvBalanceStatus;
    private TextView thCol1, thCol2, thCol3, thCol4, tvEmptyData;
    private Button btnTabJournal, btnTabChartOfAccounts, btnTabTrialBalance, btnCreateManualEntry;
    private EditText etSearchAccounting;
    private ImageButton btnRefreshAccounting;
    private RecyclerView rvAccountingData;
    private ProgressBar pbAccountingLoading;

    // 2. المحولات والبيانات والـ ViewModel
    private MiraAccountingAdapter accountingAdapter;
    private MiraAccountingViewModel viewModel;
    private MiraAccountingItem.ItemType activeType = MiraAccountingItem.ItemType.JOURNAL_ENTRY;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_accounting, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // التحقق من اتجاه اللغة لتخصيص النصوص تلقائياً
        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");

        // تهيئة العناصر البرمجية
        initViews(view);

        // تهيئة الـ ViewModel ومراقبة تغيرات البيانات (LiveData Observer)
        initViewModel();

        // ضبط أحداث التبويبات وأزرار التحكم والبحث
        setupAccountingOverview(view);
    }

    /**
     * ربط العناصر البرمجية بمكونات واجهة المستخدم XML
     */
    private void initViews(View view) {
        // العناوين الإحصائية والأقسام الرئيسية
        tvAccountingTitle = view.findViewById(R.id.tvAccountingTitle);
        tvActiveBranch = view.findViewById(R.id.tvActiveBranch);
        tvTodayRevenue = view.findViewById(R.id.tvTodayRevenue);
        tvTotalJournalVolume = view.findViewById(R.id.tvTotalJournalVolume);
        tvBalanceStatus = view.findViewById(R.id.tvBalanceStatus);

        // أزرار التبويبات والإجراءات
        btnTabJournal = view.findViewById(R.id.btnTabJournal);
        btnTabChartOfAccounts = view.findViewById(R.id.btnTabChartOfAccounts);
        btnTabTrialBalance = view.findViewById(R.id.btnTabTrialBalance);
        btnCreateManualEntry = view.findViewById(R.id.btnCreateManualEntry);

        // عناوين الأعمدة والبحث
        thCol1 = view.findViewById(R.id.thCol1);
        thCol2 = view.findViewById(R.id.thCol2);
        thCol3 = view.findViewById(R.id.thCol3);
        thCol4 = view.findViewById(R.id.thCol4);
        etSearchAccounting = view.findViewById(R.id.etSearchAccounting);
        btnRefreshAccounting = view.findViewById(R.id.btnRefreshAccounting);

        // القائمة ومؤشرات الحالة
        rvAccountingData = view.findViewById(R.id.rvAccountingData);
        pbAccountingLoading = view.findViewById(R.id.pbAccountingLoading);
        tvEmptyData = view.findViewById(R.id.tvEmptyData);

        // إعداد RecyclerView والمجيب (Adapter)
        if (rvAccountingData != null) {
            rvAccountingData.setLayoutManager(new LinearLayoutManager(getContext()));
            accountingAdapter = new MiraAccountingAdapter();
            rvAccountingData.setAdapter(accountingAdapter);

            // معالجة النقر على العناصر داخل القائمة
            accountingAdapter.setOnItemClickListener(item -> {
                String msg = (isArabic ? "تم اختيار: " : "Selected: ") + item.getCol2Text();
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            });
        }
    }

    /**
     * ربط الـ ViewModel ومراقبة التغييرات القادمة من API المباشر
     */
    private void initViewModel() {
        viewModel = new ViewModelProvider(this).get(MiraAccountingViewModel.class);

        // مراقبة البيانات المحدثة
        viewModel.getItemsLiveData().observe(getViewLifecycleOwner(), items -> {
            renderData(items);
        });

        // مراقبة مؤشر التحميل
        viewModel.getIsLoading().observe(getViewLifecycleOwner(), isLoading -> {
            showLoading(isLoading);
        });

        // مراقبة الأخطاء
        viewModel.getErrorMessage().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                Toast.makeText(getContext(), error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * إعداد المستمعات للأزرار وحقول النماذج والتبويبات
     */
    private void setupAccountingOverview(View view) {

        // 🟢 التبويب الأول: القيود اليومية (Journal Entries)
        btnTabJournal.setOnClickListener(v -> {
            activeType = MiraAccountingItem.ItemType.JOURNAL_ENTRY;
            highlightTab(btnTabJournal);
            resetTabStyle(btnTabChartOfAccounts, btnTabTrialBalance);

            thCol1.setText(isArabic ? "رقم القيد" : "Entry No");
            thCol2.setText(isArabic ? "البيان والطلب" : "Description");
            thCol3.setText(isArabic ? "مدين (YER)" : "Debit");
            thCol4.setText(isArabic ? "دائن (YER)" : "Credit");

            loadJournalEntries();
        });

        // 🟢 التبويب الثاني: دليل الحسابات (Chart of Accounts)
        btnTabChartOfAccounts.setOnClickListener(v -> {
            activeType = MiraAccountingItem.ItemType.CHART_OF_ACCOUNT;
            highlightTab(btnTabChartOfAccounts);
            resetTabStyle(btnTabJournal, btnTabTrialBalance);

            thCol1.setText(isArabic ? "رمز الحساب" : "Code");
            thCol2.setText(isArabic ? "اسم الحساب" : "Account Name");
            thCol3.setText(isArabic ? "النوع" : "Type");
            thCol4.setText(isArabic ? "الرصيد" : "Balance");

            loadChartOfAccounts();
        });

        // 🟢 التبويب الثالث: ميزان المراجعة (Trial Balance)
        btnTabTrialBalance.setOnClickListener(v -> {
            activeType = MiraAccountingItem.ItemType.TRIAL_BALANCE;
            highlightTab(btnTabTrialBalance);
            resetTabStyle(btnTabJournal, btnTabChartOfAccounts);

            thCol1.setText(isArabic ? "رقم الحساب" : "Account");
            thCol2.setText(isArabic ? "الحساب" : "Name");
            thCol3.setText(isArabic ? "إجمالي مدين" : "Total Debit");
            thCol4.setText(isArabic ? "إجمالي دائن" : "Total Credit");

            loadTrialBalance();
        });

        // زر إنشاء قيد يدوي جديد
        if (btnCreateManualEntry != null) {
            btnCreateManualEntry.setOnClickListener(v -> {
                Toast.makeText(getContext(), isArabic ? "فتح شاشة إضافة قيد محاسبي..." : "Opening manual entry editor...", Toast.LENGTH_SHORT).show();
            });
        }

        // زر التحديث المباشر
        if (btnRefreshAccounting != null) {
            btnRefreshAccounting.setOnClickListener(v -> refreshData());
        }

        // تفعيل الفلترة المباشرة عبر الـ ViewModel
        if (etSearchAccounting != null) {
            etSearchAccounting.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    viewModel.filterData(s.toString());
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        // تحميل القيود افتراضياً عند فتح الشاشة
        btnTabJournal.performClick();
    }

    // 🎨 تظليل التبويب النشط
    private void highlightTab(Button activeBtn) {
        if (activeBtn == null) return;
        activeBtn.setBackgroundColor(Color.parseColor("#2980B9"));
        activeBtn.setTextColor(Color.WHITE);
    }

    // 🎨 إعادة تعيين ألوان التبويبات غير النشطة
    private void resetTabStyle(Button... inactiveBtns) {
        for (Button btn : inactiveBtns) {
            if (btn != null) {
                btn.setBackgroundColor(Color.parseColor("#BDC3C7"));
                btn.setTextColor(Color.parseColor("#2C3E50"));
            }
        }
    }

    // --- دوال طلب البيانات المحاسبية عبر الـ ViewModel ---

    private void loadJournalEntries() {
        viewModel.fetchJournalEntries();
    }

    private void loadChartOfAccounts() {
        viewModel.fetchChartOfAccounts();
    }

    private void loadTrialBalance() {
        viewModel.fetchTrialBalance();
    }

    private void renderData(List<MiraAccountingItem> items) {
        if (items == null || items.isEmpty()) {
            if (tvEmptyData != null) tvEmptyData.setVisibility(View.VISIBLE);
            if (rvAccountingData != null) rvAccountingData.setVisibility(View.GONE);
        } else {
            if (tvEmptyData != null) tvEmptyData.setVisibility(View.GONE);
            if (rvAccountingData != null) rvAccountingData.setVisibility(View.VISIBLE);
            if (accountingAdapter != null) accountingAdapter.setItems(items);
        }
    }

    private void showLoading(boolean isLoading) {
        if (pbAccountingLoading != null) {
            pbAccountingLoading.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        }
        if (isLoading && rvAccountingData != null) {
            rvAccountingData.setVisibility(View.GONE);
        }
    }

    private void refreshData() {
        if (etSearchAccounting != null) etSearchAccounting.setText("");
        if (activeType == MiraAccountingItem.ItemType.JOURNAL_ENTRY) {
            loadJournalEntries();
        } else if (activeType == MiraAccountingItem.ItemType.CHART_OF_ACCOUNT) {
            loadChartOfAccounts();
        } else {
            loadTrialBalance();
        }
        Toast.makeText(getContext(), isArabic ? "تم تحديث البيانات" : "Data refreshed", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        rvAccountingData = null;
        accountingAdapter = null;
    }

    // =========================================================================
    // 3. الـ ViewModel الداخلي وإعدادات الاتصال بالسيرفر (Retrofit REST API)
    // =========================================================================

    public static class MiraAccountingViewModel extends ViewModel {

        private final MutableLiveData<List<MiraAccountingItem>> itemsLiveData = new MutableLiveData<>();
        private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>();
        private final MutableLiveData<String> errorMessage = new MutableLiveData<>();

        private List<MiraAccountingItem> rawList = new ArrayList<>();
        private final AccountingApiService apiService;

        public MiraAccountingViewModel() {
            // تهيئة عميل Retrofit مدمج للاستعلام المباشر من API
            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl("https://api.mira-technology.com/")
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();

            apiService = retrofit.create(AccountingApiService.class);
        }

        public LiveData<List<MiraAccountingItem>> getItemsLiveData() { return itemsLiveData; }
        public LiveData<Boolean> getIsLoading() { return isLoading; }
        public LiveData<String> getErrorMessage() { return errorMessage; }

        public void fetchJournalEntries() {
            isLoading.setValue(true);
            
            // طلب بيانات قيود اليومية من السيرفر
            apiService.getJournalEntries().enqueue(new retrofit2.Callback<List<MiraAccountingItem>>() {
                @Override
                public void onResponse(retrofit2.Call<List<MiraAccountingItem>> call, retrofit2.Response<List<MiraAccountingItem>> response) {
                    isLoading.setValue(false);
                    if (response.isSuccessful() && response.body() != null) {
                        rawList = response.body();
                        itemsLiveData.setValue(rawList);
                    } else {
                        loadMockJournalEntries();
                    }
                }

                @Override
                public void onFailure(retrofit2.Call<List<MiraAccountingItem>> call, Throwable t) {
                    isLoading.setValue(false);
                    loadMockJournalEntries();
                }
            });
        }

        public void fetchChartOfAccounts() {
            isLoading.setValue(true);
            apiService.getChartOfAccounts().enqueue(new retrofit2.Callback<List<MiraAccountingItem>>() {
                @Override
                public void onResponse(retrofit2.Call<List<MiraAccountingItem>> call, retrofit2.Response<List<MiraAccountingItem>> response) {
                    isLoading.setValue(false);
                    if (response.isSuccessful() && response.body() != null) {
                        rawList = response.body();
                        itemsLiveData.setValue(rawList);
                    } else {
                        loadMockChartOfAccounts();
                    }
                }

                @Override
                public void onFailure(retrofit2.Call<List<MiraAccountingItem>> call, Throwable t) {
                    isLoading.setValue(false);
                    loadMockChartOfAccounts();
                }
            });
        }

        public void fetchTrialBalance() {
            isLoading.setValue(true);
            apiService.getTrialBalance().enqueue(new retrofit2.Callback<List<MiraAccountingItem>>() {
                @Override
                public void onResponse(retrofit2.Call<List<MiraAccountingItem>> call, retrofit2.Response<List<MiraAccountingItem>> response) {
                    isLoading.setValue(false);
                    if (response.isSuccessful() && response.body() != null) {
                        rawList = response.body();
                        itemsLiveData.setValue(rawList);
                    } else {
                        loadMockTrialBalance();
                    }
                }

                @Override
                public void onFailure(retrofit2.Call<List<MiraAccountingItem>> call, Throwable t) {
                    isLoading.setValue(false);
                    loadMockTrialBalance();
                }
            });
        }

        public void filterData(String query) {
            if (rawList == null) return;
            if (query == null || query.trim().isEmpty()) {
                itemsLiveData.setValue(rawList);
                return;
            }

            List<MiraAccountingItem> filtered = new ArrayList<>();
            String lowerQuery = query.toLowerCase().trim();

            for (MiraAccountingItem item : rawList) {
                if ((item.getCol1Text() != null && item.getCol1Text().toLowerCase().contains(lowerQuery)) ||
                    (item.getCol2Text() != null && item.getCol2Text().toLowerCase().contains(lowerQuery))) {
                    filtered.add(item);
                }
            }
            itemsLiveData.setValue(filtered);
        }

        // --- دوال تزويد البيانات الاحتياطية عند تعثر السيرفر ---

        private void loadMockJournalEntries() {
            rawList.clear();
            rawList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.JOURNAL_ENTRY, "JV-2026-101", "مبيعات طقم ذهب - فاتورة #4092", "450,000", "0.00"));
            rawList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.JOURNAL_ENTRY, "JV-2026-102", "قبول توريد صندوق - فرع صنعاء", "0.00", "450,000"));
            rawList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.JOURNAL_ENTRY, "JV-2026-103", "رسوم صياغة وتلميع قطع مجوهرات", "25,000", "0.00"));
            itemsLiveData.setValue(rawList);
        }

        private void loadMockChartOfAccounts() {
            rawList.clear();
            rawList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.CHART_OF_ACCOUNT, "110101", "الصندوق الرئيسي", "أصول متداولة", "12,450,000 YER"));
            rawList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.CHART_OF_ACCOUNT, "110201", "مخزون الذهب (عيار 21)", "أصول متداولة", "85,200,000 YER"));
            rawList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.CHART_OF_ACCOUNT, "410101", "إيرادات مبيعات المجوهرات", "إيرادات", "4,250,000 YER"));
            itemsLiveData.setValue(rawList);
        }

        private void loadMockTrialBalance() {
            rawList.clear();
            rawList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.TRIAL_BALANCE, "1101", "حسابات النقدية وما حكمها", "15,000,000", "2,550,000"));
            rawList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.TRIAL_BALANCE, "1102", "مخزون البضائع والمجوهرات", "85,200,000", "0.00"));
            itemsLiveData.setValue(rawList);
        }
    }

    // =========================================================================
    // 4. واجهة Retrofit API للطلبات المباشرة من الشبكة
    // =========================================================================

    public interface AccountingApiService {
        @GET("accounting/journal-entries")
        retrofit2.Call<List<MiraAccountingItem>> getJournalEntries();

        @GET("accounting/chart-of-accounts")
        retrofit2.Call<List<MiraAccountingItem>> getChartOfAccounts();

        @GET("accounting/trial-balance")
        retrofit2.Call<List<MiraAccountingItem>> getTrialBalance();
    }
}
