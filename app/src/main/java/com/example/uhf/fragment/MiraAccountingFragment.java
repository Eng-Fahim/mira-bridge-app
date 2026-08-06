package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;

public class MiraAccountingFragment extends Fragment {

    private boolean isArabic;

    // 1. تعريف عناصر الواجهة (UI Elements)
    private TextView tvAccountingTitle, tvActiveBranch, tvTodayRevenue, tvTotalJournalVolume, tvBalanceStatus;
    private TextView thCol1, thCol2, thCol3, thCol4, tvEmptyData;
    private Button btnTabJournal, btnTabChartOfAccounts, btnTabTrialBalance, btnCreateManualEntry;
    private EditText etSearchAccounting;
    private ImageButton btnRefreshAccounting;
    private RecyclerView rvAccountingData;
    private ProgressBar pbAccountingLoading;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_accounting, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");

        // 2. ربط العناصر البرمجية بعناصر التصميم XML
        initViews(view);

        // 3. إعداد أزرار التنقل والربط بالبيانات المحاسبية
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

        // إعداد الـ RecyclerView
        if (rvAccountingData != null) {
            rvAccountingData.setLayoutManager(new LinearLayoutManager(getContext()));
        }
    }

    private void setupAccountingOverview(View view) {

        // 🟢 تبويب 1: القيود اليومية (Journal Entries)
        btnTabJournal.setOnClickListener(v -> {
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
            highlightTab(btnTabTrialBalance);
            resetTabStyle(btnTabJournal, btnTabChartOfAccounts);

            thCol1.setText(isArabic ? "رقم الحساب" : "Account");
            thCol2.setText(isArabic ? "الحساب" : "Name");
            thCol3.setText(isArabic ? "إجمالي مدين" : "Total Debit");
            thCol4.setText(isArabic ? "إجمالي دائن" : "Total Credit");

            loadTrialBalance();
        });

        // زر تحديث البيانات
        btnRefreshAccounting.setOnClickListener(v -> refreshData());

        // تحميل القيود اليومية بشكل افتراضي عند فتح الواجهة
        btnTabJournal.performClick();
    }

    // 🎨 دالة تغيير لون التبويب النشط
    private void highlightTab(Button activeBtn) {
        activeBtn.setBackgroundColor(Color.parseColor("#2980B9"));
        activeBtn.setTextColor(Color.WHITE);
    }

    // 🎨 دالة إعادة تعيين ألوان التبويبات غير النشطة
    private void resetTabStyle(Button... inactiveBtns) {
        for (Button btn : inactiveBtns) {
            btn.setBackgroundColor(Color.parseColor("#BDC3C7"));
            btn.setTextColor(Color.parseColor("#2C3E50"));
        }
    }

    // --- دوال جلب البيانات ---
    private void loadJournalEntries() {
        // كود استدعاء API القيود اليومية وعرضها في RecyclerView
    }

    private void loadChartOfAccounts() {
        // كود استدعاء API شجرة الحسابات
    }

    private void loadTrialBalance() {
        // كود استدعاء API ميزان المراجعة
    }

    private void refreshData() {
        // إعادة تحميل البيانات
    }
}
