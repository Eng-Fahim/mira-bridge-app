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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.adapter.MiraAccountingAdapter;
import com.example.uhf.model.MiraAccountingItem;

import java.util.ArrayList;
import java.util.List;

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

    // 2. الـ Adapter والمجموعات الخاصة بالبيانات
    private MiraAccountingAdapter accountingAdapter;
    private final List<MiraAccountingItem> currentList = new ArrayList<>();
    private MiraAccountingItem.ItemType activeType = MiraAccountingItem.ItemType.JOURNAL_ENTRY;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_accounting, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");

        // ربط العناصر وإعداد القائمة
        initViews(view);

        // إعداد أزرار الأحداث والبحث
        setupAccountingOverview(view);
    }

    private void initViews(View view) {
        // العناوين والإحصائيات
        tvAccountingTitle = view.findViewById(R.id.tvAccountingTitle);
        tvActiveBranch = view.findViewById(R.id.tvActiveBranch);
        tvTodayRevenue = view.findViewById(R.id.tvTodayRevenue);
        tvTotalJournalVolume = view.findViewById(R.id.tvTotalJournalVolume);
        tvBalanceStatus = view.findViewById(R.id.tvBalanceStatus);

        // التبويبات والأزرار
        btnTabJournal = view.findViewById(R.id.btnTabJournal);
        btnTabChartOfAccounts = view.findViewById(R.id.btnTabChartOfAccounts);
        btnTabTrialBalance = view.findViewById(R.id.btnTabTrialBalance);
        btnCreateManualEntry = view.findViewById(R.id.btnCreateManualEntry);

        // ترويسة الجدول والبحث
        thCol1 = view.findViewById(R.id.thCol1);
        thCol2 = view.findViewById(R.id.thCol2);
        thCol3 = view.findViewById(R.id.thCol3);
        thCol4 = view.findViewById(R.id.thCol4);
        etSearchAccounting = view.findViewById(R.id.etSearchAccounting);
        btnRefreshAccounting = view.findViewById(R.id.btnRefreshAccounting);

        // القائمة ومؤشرات التحميل
        rvAccountingData = view.findViewById(R.id.rvAccountingData);
        pbAccountingLoading = view.findViewById(R.id.pbAccountingLoading);
        tvEmptyData = view.findViewById(R.id.tvEmptyData);

        // إعداد ה- RecyclerView والـ Adapter
        if (rvAccountingData != null) {
            rvAccountingData.setLayoutManager(new LinearLayoutManager(getContext()));
            accountingAdapter = new MiraAccountingAdapter();
            rvAccountingData.setAdapter(accountingAdapter);

            accountingAdapter.setOnItemClickListener(item -> {
                String msg = (isArabic ? "تم اختيار: " : "Selected: ") + item.getCol2Text();
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void setupAccountingOverview(View view) {

        // 🟢 تبويب 1: القيود اليومية (Journal Entries)
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

        // 🟢 تبويب 2: دليل الحسابات (Chart of Accounts)
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

        // 🟢 تبويب 3: ميزان المراجعة (Trial Balance)
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

        // زر إنشاء قيد يدوي
        if (btnCreateManualEntry != null) {
            btnCreateManualEntry.setOnClickListener(v -> {
                Toast.makeText(getContext(), isArabic ? "فتح شاشة إضافة قيد محاسبي..." : "Opening manual entry editor...", Toast.LENGTH_SHORT).show();
            });
        }

        // زر تحديث البيانات
        btnRefreshAccounting.setOnClickListener(v -> refreshData());

        // ميزة الفلترة والبحث المباشر عند الكتابة
        etSearchAccounting.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterData(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // تحميل القيود اليومية افتراضياً عند فتح الواجهة
        btnTabJournal.performClick();
    }

    // 🎨 تغيير لون التبويب النشط
    private void highlightTab(Button activeBtn) {
        activeBtn.setBackgroundColor(Color.parseColor("#2980B9"));
        activeBtn.setTextColor(Color.WHITE);
    }

    // 🎨 إعادة تعيين ألوان التبويبات غير النشطة
    private void resetTabStyle(Button... inactiveBtns) {
        for (Button btn : inactiveBtns) {
            btn.setBackgroundColor(Color.parseColor("#BDC3C7"));
            btn.setTextColor(Color.parseColor("#2C3E50"));
        }
    }

    // --- دوال جلب البيانات وإدارتها ---

    private void loadJournalEntries() {
        showLoading(true);
        currentList.clear();

        // بيانات نموذجية - استبدلها بدعوة الـ API في النطاق الفعلي
        currentList.add(new MiraAccountingItem(
                MiraAccountingItem.ItemType.JOURNAL_ENTRY,
                "JV-2026-101", "مبيعات طقم ذهب - فاتورة #4092", "450,000", "0.00"
        ));
        currentList.add(new MiraAccountingItem(
                MiraAccountingItem.ItemType.JOURNAL_ENTRY,
                "JV-2026-102", "قبول توريد صندوق - فرع صنعاء", "0.00", "450,000"
        ));
        currentList.add(new MiraAccountingItem(
                MiraAccountingItem.ItemType.JOURNAL_ENTRY,
                "JV-2026-103", "رسوم صياغة وتلميع قطع مجوهرات", "25,000", "0.00"
        ));

        renderData(currentList);
    }

    private void loadChartOfAccounts() {
        showLoading(true);
        currentList.clear();

        currentList.add(new MiraAccountingItem(
                MiraAccountingItem.ItemType.CHART_OF_ACCOUNT,
                "110101", "الصندوق الرئيسي", "أصول متداولة", "12,450,000 YER"
        ));
        currentList.add(new MiraAccountingItem(
                MiraAccountingItem.ItemType.CHART_OF_ACCOUNT,
                "110201", "مخزون الذهب (عيار 21)", "أصول متداولة", "85,200,000 YER"
        ));
        currentList.add(new MiraAccountingItem(
                MiraAccountingItem.ItemType.CHART_OF_ACCOUNT,
                "410101", "إيرادات مبيعات المجوهرات", "إيرادات", "4,250,000 YER"
        ));

        renderData(currentList);
    }

    private void loadTrialBalance() {
        showLoading(true);
        currentList.clear();

        currentList.add(new MiraAccountingItem(
                MiraAccountingItem.ItemType.TRIAL_BALANCE,
                "1101", "حسابات النقدية وما حكمها", "15,000,000", "2,550,000"
        ));
        currentList.add(new MiraAccountingItem(
                MiraAccountingItem.ItemType.TRIAL_BALANCE,
                "1102", "مخزون البضائع والمجوهرات", "85,200,000", "0.00"
        ));

        renderData(currentList);
    }

    private void filterData(String query) {
        if (query.trim().isEmpty()) {
            renderData(currentList);
            return;
        }

        List<MiraAccountingItem> filtered = new ArrayList<>();
        for (MiraAccountingItem item : currentList) {
            if (item.getCol1Text().toLowerCase().contains(query.toLowerCase()) ||
                item.getCol2Text().toLowerCase().contains(query.toLowerCase())) {
                filtered.add(item);
            }
        }
        renderData(filtered);
    }

    private void renderData(List<MiraAccountingItem> items) {
        showLoading(false);
        if (items.isEmpty()) {
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
}
