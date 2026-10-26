package com.example.uhf.accounting;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.tools.SessionManager;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * MIRA Accounting — Dashboard (Stage 4.1)
 *
 * لوحة التحكم المالية. هذا الإصدار يبني:
 *   • الواجهة الكاملة (KPIs, Alerts, Quick Actions, Recent)
 *   • نموذج البيانات عبر {@link AccountingKpi}
 *   • حالات واضحة: loading / success / empty / error / not-connected
 *
 * ما لا يفعله هذا الإصدار:
 *   • لا يجلب أرقاماً من الخادم (يحدث في 4.2)
 *   • لا يعرض أصفاراً مزيفة على أنها أرصدة حقيقية
 *
 * عند أول تشغيل: كل KPI يعرض "—" مع caption "قيد الربط بالخادم"،
 * ثم تتحول إلى SUCCESS أو ERROR بمجرد إضافة استدعاء API في 4.2.
 */
public class MiraAccountingDashboardFragment extends Fragment {

    private static final String TAG = "MiraDashboard";

    // ─── Views ───
    private SwipeRefreshLayout swipeRefresh;
    private GridLayout gridKpi;
    private GridLayout gridQuick;
    private LinearLayout containerAlerts;
    private LinearLayout containerRecent;
    private TextView tvAlertsEmpty;
    private TextView tvRecentEmpty;
    private TextView tvPeriodFrom;
    private TextView tvPeriodTo;
    private TextView tvBranch;

    // ─── State ───
    private final Map<String, AccountingKpi> kpiMap = new HashMap<>();
    private final Map<String, View> kpiViews = new HashMap<>();
    private Calendar periodFrom;
    private Calendar periodTo;
    private boolean isArabic = true;

    // ═══════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        isArabic = "ar".equals(Locale.getDefault().getLanguage());

        View v = inflater.inflate(R.layout.fragment_mira_accounting_dashboard,
                container, false);

        swipeRefresh   = v.findViewById(R.id.swipeRefresh);
        gridKpi        = v.findViewById(R.id.gridKpi);
        gridQuick      = v.findViewById(R.id.gridQuick);
        containerAlerts = v.findViewById(R.id.containerAlerts);
        containerRecent = v.findViewById(R.id.containerRecent);
        tvAlertsEmpty  = v.findViewById(R.id.tvAlertsEmpty);
        tvRecentEmpty  = v.findViewById(R.id.tvRecentEmpty);
        tvPeriodFrom   = v.findViewById(R.id.tvPeriodFrom);
        tvPeriodTo     = v.findViewById(R.id.tvPeriodTo);
        tvBranch       = v.findViewById(R.id.tvBranch);

        // الفترة الافتراضية: من بداية الشهر حتى اليوم
        periodTo = Calendar.getInstance();
        periodFrom = Calendar.getInstance();
        periodFrom.set(Calendar.DAY_OF_MONTH, 1);
        refreshPeriodLabels();

        // ربط عناصر الفترة
        tvPeriodFrom.setOnClickListener(x -> openDatePicker(periodFrom, true));
        tvPeriodTo.setOnClickListener(x -> openDatePicker(periodTo, false));

        // SwipeRefresh
        swipeRefresh.setColorSchemeResources(
                com.google.android.material.R.color.design_default_color_primary);
        swipeRefresh.setOnRefreshListener(this::loadDashboardData);

        // أعمدة KPIs حسب حجم الشاشة
        int columns = getResources().getInteger(R.integer.dashboard_kpi_columns);
        gridKpi.setColumnCount(columns);
        gridQuick.setColumnCount(columns);

        // بناء البطاقات من الكود
        buildKpiCards();
        buildQuickActions();

        // تحميل مبدئي
        loadDashboardData();

        return v;
    }

    // ═══════════════════════════════════════════════════════════
    //  Period
    // ═══════════════════════════════════════════════════════════
    private void refreshPeriodLabels() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        tvPeriodFrom.setText(fmt.format(periodFrom.getTime()));
        tvPeriodTo.setText(fmt.format(periodTo.getTime()));

        String branch = "الفرع: ";
        try {
            SessionManager sm = new SessionManager(requireContext());
            int bid = sm.getBranchId();
            branch += bid > 0 ? String.valueOf(bid) : "كل الفروع";
        } catch (Exception e) {
            branch += "—";
        }
        tvBranch.setText(branch);
    }

    private void openDatePicker(Calendar target, boolean isFrom) {
        new DatePickerDialog(requireContext(),
                (view, y, m, d) -> {
                    target.set(y, m, d);
                    // ضمان from <= to
                    if (periodFrom.after(periodTo)) {
                        if (isFrom) periodTo.setTime(periodFrom.getTime());
                        else        periodFrom.setTime(periodTo.getTime());
                    }
                    refreshPeriodLabels();
                    loadDashboardData();
                },
                target.get(Calendar.YEAR),
                target.get(Calendar.MONTH),
                target.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ═══════════════════════════════════════════════════════════
    //  KPI Cards
    // ═══════════════════════════════════════════════════════════
    private void buildKpiCards() {
        gridKpi.removeAllViews();
        kpiViews.clear();

        addKpiCard(AccountingKpi.KEY_CASH,        "النقدية والخزائن", "💰");
        addKpiCard(AccountingKpi.KEY_REVENUE,     "الإيرادات",        "📈");
        addKpiCard(AccountingKpi.KEY_EXPENSES,    "المصروفات",        "📉");
        addKpiCard(AccountingKpi.KEY_NET_PROFIT,  "صافي الربح",       "💎");
        addKpiCard(AccountingKpi.KEY_RECEIVABLES, "ذمم مدينة",        "👤");
        addKpiCard(AccountingKpi.KEY_PAYABLES,    "ذمم دائنة",        "🏢");
    }

    private void addKpiCard(String key, String label, String icon) {
        LayoutInflater inf = LayoutInflater.from(requireContext());
        View card = inf.inflate(R.layout.item_dashboard_kpi, gridKpi, false);

        // اضبط layout_params للـ GridLayout
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        int margin = dp(4);
        lp.setMargins(margin, margin, margin, margin);
        card.setLayoutParams(lp);

        kpiViews.put(key, card);
        gridKpi.addView(card);

        // حالة أولية
        kpiMap.put(key, AccountingKpi.notConnected(key, label, icon));
        bindKpi(card, kpiMap.get(key));
    }

    private void bindKpi(View card, AccountingKpi kpi) {
        TextView icon    = card.findViewById(R.id.tvKpiIcon);
        TextView label   = card.findViewById(R.id.tvKpiLabel);
        TextView value   = card.findViewById(R.id.tvKpiValue);
        TextView caption = card.findViewById(R.id.tvKpiCaption);
        TextView error   = card.findViewById(R.id.tvKpiError);
        ProgressBar pb   = card.findViewById(R.id.pbKpiLoading);

        icon.setText(kpi.icon);
        label.setText(kpi.label);

        pb.setVisibility(View.GONE);
        error.setVisibility(View.GONE);
        value.setVisibility(View.VISIBLE);
        caption.setVisibility(View.VISIBLE);

        switch (kpi.state) {
            case AccountingKpi.STATE_LOADING:
                value.setText("—");
                value.setAlpha(0.4f);
                caption.setText("جاري التحميل...");
                pb.setVisibility(View.VISIBLE);
                break;

            case AccountingKpi.STATE_SUCCESS:
                value.setText(kpi.displayValue != null ? kpi.displayValue : "—");
                value.setAlpha(1.0f);
                caption.setText(kpi.caption != null ? kpi.caption : "");
                break;

            case AccountingKpi.STATE_NOT_CONNECTED:
                value.setText("—");
                value.setAlpha(0.5f);
                caption.setText("قيد الربط بالخادم");
                break;

            case AccountingKpi.STATE_EMPTY:
                value.setText("—");
                value.setAlpha(0.5f);
                caption.setText(kpi.caption != null ? kpi.caption : "لا بيانات");
                break;

            case AccountingKpi.STATE_ERROR:
                value.setText("—");
                value.setAlpha(0.5f);
                caption.setText("");
                error.setText(kpi.errorMessage != null ? kpi.errorMessage : "تعذر التحميل");
                error.setVisibility(View.VISIBLE);
                break;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Quick Actions
    // ═══════════════════════════════════════════════════════════
    private void buildQuickActions() {
        gridQuick.removeAllViews();

        addQuickAction("➕", "قيد جديد",     MiraAccountingScreens.ID_JOURNAL);
        addQuickAction("📖", "دفتر الأستاذ", MiraAccountingScreens.ID_GENERAL_LEDGER);
        addQuickAction("⚖️", "ميزان المراجعة", MiraAccountingScreens.ID_TRIAL_BALANCE);
        addQuickAction("📊", "الحسابات",     MiraAccountingScreens.ID_ACCOUNTS_TREE);
    }

    private void addQuickAction(String icon, String label, String targetScreenId) {
        LayoutInflater inf = LayoutInflater.from(requireContext());
        View card = inf.inflate(R.layout.item_dashboard_quick_action, gridQuick, false);

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = dp(80);
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        int margin = dp(4);
        lp.setMargins(margin, margin, margin, margin);
        card.setLayoutParams(lp);

        ((TextView) card.findViewById(R.id.tvQuickIcon)).setText(icon);
        ((TextView) card.findViewById(R.id.tvQuickLabel)).setText(label);

        card.setOnClickListener(v -> navigateTo(targetScreenId));

        gridQuick.addView(card);
    }

    // ═══════════════════════════════════════════════════════════
    //  Data Loading (Stage 4.2 will fill this)
    // ═══════════════════════════════════════════════════════════
    private void loadDashboardData() {
        // في 4.2: يُستبدل هذا باستدعاءات API فعلية لكل KPI
        //          + التحقق اليدوي من كل رقم مقابل WP Admin
        Log.d(TAG, "loadDashboardData() — API integration pending (stage 4.2)");

        swipeRefresh.setRefreshing(false);

        // حالة افتراضية معبّرة (لا أرقام مزيفة):
        updateAllKpisToNotConnected();
        showAlertsEmpty();
        showRecentEmpty();
    }

    private void updateAllKpisToNotConnected() {
        for (String key : kpiViews.keySet()) {
            View card = kpiViews.get(key);
            AccountingKpi kpi = kpiMap.get(key);
            if (card != null && kpi != null) bindKpi(card, kpi);
        }
    }

    private void showAlertsEmpty() {
        containerAlerts.removeAllViews();
        containerAlerts.setVisibility(View.GONE);
        tvAlertsEmpty.setVisibility(View.VISIBLE);
    }

    private void showRecentEmpty() {
        containerRecent.removeAllViews();
        containerRecent.setVisibility(View.GONE);
        tvRecentEmpty.setVisibility(View.VISIBLE);
    }

    // ═══════════════════════════════════════════════════════════
    //  Navigation
    // ═══════════════════════════════════════════════════════════
    private void navigateTo(@NonNull String screenId) {
        Fragment parent = getParentFragment();
        if (parent instanceof MiraAccountingFragmentNew) {
            ((MiraAccountingFragmentNew) parent).navigateToScreen(screenId);
        } else {
            Log.w(TAG, "parent not MiraAccountingFragmentNew: " + parent);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void safeToast(String message) {
        if (!isAdded() || getContext() == null) return;
        Toast.makeText(requireContext().getApplicationContext(),
                message, Toast.LENGTH_SHORT).show();
    }
}
