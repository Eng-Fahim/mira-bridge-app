package com.example.uhf.accounting;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.tools.SessionManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MIRA Accounting — Dashboard (Stage 4.3)
 *
 * تحسينات 4.3:
 *  • إصلاح: الذمم لا تعلق في التحميل عند فشل API
 *  • إصلاح: النقدية تُستخرج من مصفوفة assets إن لم تكن مباشرة
 *  • إصلاح: الذمم تُستخرج بكلمات مفتاحية (cash/نقد/صندوق/ذمم...)
 *  • إصلاح: آخر الأنشطة تقرأ مفاتيح متعددة
 *  • تحسين: تسجيل كامل للـ JSON لضبطه مع WP Admin
 */
public class MiraAccountingDashboardFragment extends Fragment {

    private static final String TAG = "MiraDashboard";

    private SwipeRefreshLayout swipeRefresh;
    private GridLayout gridKpi;
    private GridLayout gridQuick;
    private LinearLayout containerAlerts, containerRecent;
    private TextView tvAlertsEmpty, tvRecentEmpty;
    private TextView tvPeriodFrom, tvPeriodTo, tvBranch;

    private final Map<String, AccountingKpi> kpiMap = new HashMap<>();
    private final Map<String, View> kpiViews = new HashMap<>();
    private Calendar periodFrom, periodTo;
    private boolean isArabic = true;
    private ExecutorService io;

    private MiraApiClient api;
    private SessionManager session;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        isArabic = "ar".equals(Locale.getDefault().getLanguage());
        io = Executors.newFixedThreadPool(4);

        if (getContext() != null) {
            api = MiraApiClient.getInstance(requireContext());
            session = new SessionManager(requireContext());
        }

        View v = inflater.inflate(R.layout.fragment_mira_accounting_dashboard, container, false);
        swipeRefresh    = v.findViewById(R.id.swipeRefresh);
        gridKpi         = v.findViewById(R.id.gridKpi);
        gridQuick       = v.findViewById(R.id.gridQuick);
        containerAlerts = v.findViewById(R.id.containerAlerts);
        containerRecent = v.findViewById(R.id.containerRecent);
        tvAlertsEmpty   = v.findViewById(R.id.tvAlertsEmpty);
        tvRecentEmpty   = v.findViewById(R.id.tvRecentEmpty);
        tvPeriodFrom    = v.findViewById(R.id.tvPeriodFrom);
        tvPeriodTo      = v.findViewById(R.id.tvPeriodTo);
        tvBranch        = v.findViewById(R.id.tvBranch);

        periodTo = Calendar.getInstance();
        periodFrom = Calendar.getInstance();
        periodFrom.set(Calendar.DAY_OF_MONTH, 1);
        periodFrom.set(Calendar.MONTH, Calendar.JANUARY);
        refreshPeriodLabels();

        tvPeriodFrom.setOnClickListener(x -> openDatePicker(periodFrom, true));
        tvPeriodTo.setOnClickListener(x -> openDatePicker(periodTo, false));

        swipeRefresh.setOnRefreshListener(this::loadDashboardData);

        int columns = getResources().getInteger(R.integer.dashboard_kpi_columns);
        gridKpi.setColumnCount(columns);
        gridQuick.setColumnCount(columns);

        buildKpiCards();
        buildQuickActions();

        loadDashboardData();

        return v;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (io != null) io.shutdownNow();
    }

    private void refreshPeriodLabels() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        tvPeriodFrom.setText(fmt.format(periodFrom.getTime()));
        tvPeriodTo.setText(fmt.format(periodTo.getTime()));

        int bid = getBranchId();
        String branch = isArabic ? "الفرع: " : "Branch: ";
        branch += bid > 0 ? String.valueOf(bid) : (isArabic ? "كل الفروع" : "All");
        tvBranch.setText(branch);
    }

    private void openDatePicker(Calendar target, boolean isFrom) {
        new DatePickerDialog(requireContext(),
                (view, y, m, d) -> {
                    target.set(y, m, d);
                    if (periodFrom.after(periodTo)) {
                        if (isFrom) periodTo.setTime(periodFrom.getTime());
                        else        periodFrom.setTime(periodTo.getTime());
                    }
                    refreshPeriodLabels();
                    loadDashboardData();
                },
                target.get(Calendar.YEAR), target.get(Calendar.MONTH), target.get(Calendar.DAY_OF_MONTH)).show();
    }

    private int getBranchId() {
        try { return session != null ? session.getBranchId() : 0; }
        catch (Exception e) { return 0; }
    }

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

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        int m = dp(4);
        lp.setMargins(m, m, m, m);
        card.setLayoutParams(lp);

        kpiViews.put(key, card);
        gridKpi.addView(card);

        kpiMap.put(key, AccountingKpi.loading(key, label, icon));
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
                caption.setText("قيد الربط");
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

    private void updateKpi(String key, AccountingKpi kpi) {
        kpiMap.put(key, kpi);
        View card = kpiViews.get(key);
        if (card != null) bindKpi(card, kpi);
    }

    private void buildQuickActions() {
        gridQuick.removeAllViews();
        addQuickAction("➕", "قيد جديد",       MiraAccountingScreens.ID_JOURNAL);
        addQuickAction("📖", "دفتر الأستاذ",   MiraAccountingScreens.ID_GENERAL_LEDGER);
        addQuickAction("⚖️", "ميزان المراجعة", MiraAccountingScreens.ID_TRIAL_BALANCE);
        addQuickAction("📊", "الحسابات",       MiraAccountingScreens.ID_ACCOUNTS_TREE);
    }

    private void addQuickAction(String icon, String label, String screenId) {
        LayoutInflater inf = LayoutInflater.from(requireContext());
        View card = inf.inflate(R.layout.item_dashboard_quick_action, gridQuick, false);

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = dp(80);
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        int m = dp(4);
        lp.setMargins(m, m, m, m);
        card.setLayoutParams(lp);

        ((TextView) card.findViewById(R.id.tvQuickIcon)).setText(icon);
        ((TextView) card.findViewById(R.id.tvQuickLabel)).setText(label);
        card.setOnClickListener(v -> navigateTo(screenId));
        gridQuick.addView(card);
    }

    private void loadDashboardData() {
        if (api == null) return;
        swipeRefresh.setRefreshing(true);

        for (String k : kpiViews.keySet()) {
            AccountingKpi template = kpiMap.get(k);
            if (template != null) {
                updateKpi(k, AccountingKpi.loading(k, template.label, template.icon));
            }
        }

        final String from  = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(periodFrom.getTime());
        final String to    = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(periodTo.getTime());
        final String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().getTime());
        final int branch   = getBranchId();

        io.execute(() -> loadIncomeStatement(from, to, branch));
        io.execute(() -> loadBalanceSheet(to, branch, today));
        io.execute(() -> loadAlerts(branch));
        io.execute(() -> loadRecentActivity(branch));

        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> {
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                }, 4000);
    }

    // ═══════════════════════════════════════════════════════════
    //  Income Statement — Revenue, Expenses, Net Profit
    // ═══════════════════════════════════════════════════════════
    private void loadIncomeStatement(String from, String to, int branch) {
        try {
            MiraApiClient.ApiResponse resp = api.getIncomeStatement(
                    branch > 0 ? String.valueOf(branch) : null, from, to);

            Log.d(TAG, "getIncomeStatement [" + from + "→" + to + "] code="
                    + resp.code + " body=" + preview(resp.getBody()));

            if (!resp.isSuccess || resp.data == null) {
                String err = resp.error != null ? resp.error : "تعذر تحميل قائمة الدخل";
                postUi(() -> {
                    updateKpi(AccountingKpi.KEY_REVENUE,
                            AccountingKpi.error(AccountingKpi.KEY_REVENUE, "الإيرادات", "📈", err));
                    updateKpi(AccountingKpi.KEY_EXPENSES,
                            AccountingKpi.error(AccountingKpi.KEY_EXPENSES, "المصروفات", "📉", err));
                    updateKpi(AccountingKpi.KEY_NET_PROFIT,
                            AccountingKpi.error(AccountingKpi.KEY_NET_PROFIT, "صافي الربح", "💎", err));
                });
                return;
            }

            double revenue   = pick(resp.data, "net_revenue", "revenue", "total_revenue", "total_revenues");
            double expenses  = pick(resp.data, "operating_expenses", "expenses", "total_expenses", "total_expenses_amount");
            double netProfit = pick(resp.data, "net_profit", "netProfit", "profit", "net_income");

            boolean hasAny = revenue != 0 || expenses != 0 || netProfit != 0;

            postUi(() -> {
                String suffix = from + " → " + to;
                if (hasAny) {
                    updateKpi(AccountingKpi.KEY_REVENUE, AccountingKpi.success(
                            AccountingKpi.KEY_REVENUE, "الإيرادات", "📈",
                            revenue, fmt(revenue), "YER", suffix));
                    updateKpi(AccountingKpi.KEY_EXPENSES, AccountingKpi.success(
                            AccountingKpi.KEY_EXPENSES, "المصروفات", "📉",
                            expenses, fmt(expenses), "YER", suffix));
                    updateKpi(AccountingKpi.KEY_NET_PROFIT, AccountingKpi.success(
                            AccountingKpi.KEY_NET_PROFIT, "صافي الربح", "💎",
                            netProfit, fmt(netProfit), "YER", suffix));
                } else {
                    updateKpi(AccountingKpi.KEY_REVENUE, AccountingKpi.empty(
                            AccountingKpi.KEY_REVENUE, "الإيرادات", "📈",
                            "لا حركات مرحّلة في الفترة"));
                    updateKpi(AccountingKpi.KEY_EXPENSES, AccountingKpi.empty(
                            AccountingKpi.KEY_EXPENSES, "المصروفات", "📉",
                            "لا حركات مرحّلة في الفترة"));
                    updateKpi(AccountingKpi.KEY_NET_PROFIT, AccountingKpi.empty(
                            AccountingKpi.KEY_NET_PROFIT, "صافي الربح", "💎",
                            "لا حركات مرحّلة في الفترة"));
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "loadIncomeStatement error", t);
            postUi(() -> {
                updateKpi(AccountingKpi.KEY_REVENUE,
                        AccountingKpi.error(AccountingKpi.KEY_REVENUE, "الإيرادات", "📈", "خطأ غير متوقع"));
                updateKpi(AccountingKpi.KEY_EXPENSES,
                        AccountingKpi.error(AccountingKpi.KEY_EXPENSES, "المصروفات", "📉", "خطأ غير متوقع"));
                updateKpi(AccountingKpi.KEY_NET_PROFIT,
                        AccountingKpi.error(AccountingKpi.KEY_NET_PROFIT, "صافي الربح", "💎", "خطأ غير متوقع"));
            });
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Balance Sheet — Cash, Receivables, Payables
    // ═══════════════════════════════════════════════════════════
    private void loadBalanceSheet(String asOf, int branch, String today) {
        try {
            MiraApiClient.ApiResponse resp = api.getBalanceSheet(
                    branch > 0 ? String.valueOf(branch) : null, asOf);

            Log.d(TAG, "getBalanceSheet as_of=" + asOf + " code="
                    + resp.code + " body=" + preview(resp.getBody()));

            if (!resp.isSuccess || resp.data == null) {
                String err = resp.error != null ? resp.error : "تعذر تحميل الميزانية";
                postUi(() -> {
                    updateKpi(AccountingKpi.KEY_CASH,
                            AccountingKpi.error(AccountingKpi.KEY_CASH, "النقدية والخزائن", "💰", err));
                });
                loadAgingFallback(today, branch);
                return;
            }

            // ✅ استخراج النقدية بعدة طرق
            double cash = extractFromAccountsArray(resp.data.optJSONArray("assets"),
                    "cash", "نقد", "صندوق", "cash_on_hand");
            if (cash == 0) cash = extractNested(resp.data, "cash");
            if (cash == 0) cash = pick(resp.data, "total_cash", "cash_and_equivalents", "cash_balance");

            double receivables = extractFromAccountsArray(resp.data.optJSONArray("assets"),
                    "receivable", "ذمم", "مدينة", "عملاء");
            if (receivables == 0) receivables = extractNested(resp.data, "receivables");
            if (receivables == 0) receivables = pick(resp.data, "total_receivables");

            double payables = extractFromAccountsArray(resp.data.optJSONArray("liabilities"),
                    "payable", "دائنة", "مورد");
            if (payables == 0) payables = extractNested(resp.data, "payables");
            if (payables == 0) payables = pick(resp.data, "total_payables");

            final double cashF = cash;
            final double receivablesF = receivables;
            final double payablesF = payables;

            postUi(() -> {
                String suffix = isArabic ? ("كما في " + asOf) : ("as of " + asOf);
                if (cashF != 0) {
                    updateKpi(AccountingKpi.KEY_CASH, AccountingKpi.success(
                            AccountingKpi.KEY_CASH, "النقدية والخزائن", "💰",
                            cashF, fmt(cashF), "YER", suffix));
                } else {
                    updateKpi(AccountingKpi.KEY_CASH, AccountingKpi.empty(
                            AccountingKpi.KEY_CASH, "النقدية والخزائن", "💰",
                            "لا توجد أرصدة نقدية"));
                }

                if (receivablesF != 0) {
                    updateKpi(AccountingKpi.KEY_RECEIVABLES, AccountingKpi.success(
                            AccountingKpi.KEY_RECEIVABLES, "ذمم مدينة", "👤",
                            receivablesF, fmt(receivablesF), "YER", suffix));
                }
                if (payablesF != 0) {
                    updateKpi(AccountingKpi.KEY_PAYABLES, AccountingKpi.success(
                            AccountingKpi.KEY_PAYABLES, "ذمم دائنة", "🏢",
                            payablesF, fmt(payablesF), "YER", suffix));
                }
            });

            // احتياطي فقط إن فشلت القيم أعلاه
            if (cash == 0 || receivables == 0 || payables == 0) {
                loadAgingFallback(today, branch);
            }
        } catch (Throwable t) {
            Log.e(TAG, "loadBalanceSheet error", t);
            postUi(() -> updateKpi(AccountingKpi.KEY_CASH,
                    AccountingKpi.error(AccountingKpi.KEY_CASH, "النقدية والخزائن", "💰", "خطأ غير متوقع")));
            loadAgingFallback(today, branch);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Aging Fallback — FIXED (always updates UI)
    // ═══════════════════════════════════════════════════════════
    private void loadAgingFallback(String today, int branch) {
        // Receivables
        try {
            MiraApiClient.ApiResponse recv = api.getAgingReport(today, "receivable");
            Log.d(TAG, "getAgingReport receivable code=" + recv.code
                    + " body=" + preview(recv.getBody()));

            double totalRecv = 0;
            if (recv.isSuccess && recv.data != null) {
                totalRecv = pick(recv.data, "total", "grand_total", "total_outstanding", "total_receivable");
                JSONObject inner = recv.data.optJSONObject("data");
                if (totalRecv == 0 && inner != null) {
                    totalRecv = pick(inner, "total", "grand_total", "total_outstanding");
                }
            }
            final double finalRecv = totalRecv;
            postUi(() -> {
                if (finalRecv > 0) {
                    updateKpi(AccountingKpi.KEY_RECEIVABLES, AccountingKpi.success(
                            AccountingKpi.KEY_RECEIVABLES, "ذمم مدينة", "👤",
                            finalRecv, fmt(finalRecv), "YER", "كما في " + today));
                } else {
                    // ✅ دائماً نُحدّث، لا نُبقي في التحميل
                    updateKpi(AccountingKpi.KEY_RECEIVABLES, AccountingKpi.empty(
                            AccountingKpi.KEY_RECEIVABLES, "ذمم مدينة", "👤",
                            "لا توجد ذمم مدينة"));
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "loadAgingFallback receivable error", t);
            postUi(() -> updateKpi(AccountingKpi.KEY_RECEIVABLES,
                    AccountingKpi.error(AccountingKpi.KEY_RECEIVABLES, "ذمم مدينة", "👤", "تعذر التحميل")));
        }

        // Payables
        try {
            MiraApiClient.ApiResponse pay = api.getAgingReport(today, "payable");
            Log.d(TAG, "getAgingReport payable code=" + pay.code
                    + " body=" + preview(pay.getBody()));

            double totalPay = 0;
            if (pay.isSuccess && pay.data != null) {
                totalPay = pick(pay.data, "total", "grand_total", "total_outstanding", "total_payable");
                JSONObject inner = pay.data.optJSONObject("data");
                if (totalPay == 0 && inner != null) {
                    totalPay = pick(inner, "total", "grand_total", "total_outstanding");
                }
            }
            final double finalPay = totalPay;
            postUi(() -> {
                if (finalPay > 0) {
                    updateKpi(AccountingKpi.KEY_PAYABLES, AccountingKpi.success(
                            AccountingKpi.KEY_PAYABLES, "ذمم دائنة", "🏢",
                            finalPay, fmt(finalPay), "YER", "كما في " + today));
                } else {
                    updateKpi(AccountingKpi.KEY_PAYABLES, AccountingKpi.empty(
                            AccountingKpi.KEY_PAYABLES, "ذمم دائنة", "🏢",
                            "لا توجد ذمم دائنة"));
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "loadAgingFallback payable error", t);
            postUi(() -> updateKpi(AccountingKpi.KEY_PAYABLES,
                    AccountingKpi.error(AccountingKpi.KEY_PAYABLES, "ذمم دائنة", "🏢", "تعذر التحميل")));
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Alerts
    // ═══════════════════════════════════════════════════════════
    private void loadAlerts(int branch) {
        try {
            JSONArray alertsArr = new JSONArray();
            MiraApiClient.ApiResponse cheques = api.getCheques(null, "pending");
            if (cheques.isSuccess && cheques.data != null) {
                JSONArray arr = cheques.data.optJSONArray("cheques");
                if (arr == null) arr = cheques.data.optJSONArray("data");
                if (arr != null && arr.length() > 0) {
                    JSONObject a = new JSONObject();
                    a.put("icon", "📑");
                    a.put("title", "شيكات معلّقة");
                    a.put("subtitle", "بحاجة إلى إيداع أو إرجاع");
                    a.put("count", arr.length());
                    a.put("color", "#F59E0B");
                    a.put("target", MiraAccountingScreens.ID_CHEQUES);
                    alertsArr.put(a);
                }
            }
            postUi(() -> renderAlerts(alertsArr));
        } catch (Throwable t) {
            Log.e(TAG, "loadAlerts error", t);
            postUi(this::showAlertsEmpty);
        }
    }

    private void renderAlerts(JSONArray alerts) {
        containerAlerts.removeAllViews();
        if (alerts.length() == 0) { showAlertsEmpty(); return; }
        containerAlerts.setVisibility(View.VISIBLE);
        tvAlertsEmpty.setVisibility(View.GONE);

        LayoutInflater inf = LayoutInflater.from(requireContext());
        for (int i = 0; i < alerts.length(); i++) {
            JSONObject a = alerts.optJSONObject(i);
            if (a == null) continue;
            View row = inf.inflate(R.layout.item_dashboard_alert, containerAlerts, false);
            ((TextView) row.findViewById(R.id.tvAlertIcon)).setText(a.optString("icon", "⚠️"));
            ((TextView) row.findViewById(R.id.tvAlertTitle)).setText(a.optString("title", ""));
            ((TextView) row.findViewById(R.id.tvAlertSubtitle)).setText(a.optString("subtitle", ""));
            ((TextView) row.findViewById(R.id.tvAlertCount)).setText(a.optString("count", "0"));
            try {
                ((View) row.findViewById(R.id.vAlertColor))
                        .setBackgroundColor(android.graphics.Color.parseColor(a.optString("color", "#F59E0B")));
            } catch (Exception ignored) {}
            final String target = a.optString("target", "");
            if (!target.isEmpty()) row.setOnClickListener(v -> navigateTo(target));
            containerAlerts.addView(row);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Recent Activity — improved key fallback
    // ═══════════════════════════════════════════════════════════
    private void loadRecentActivity(int branch) {
        try {
            MiraApiClient.ApiResponse resp = api.getUnifiedActivity(branch, 5);
            Log.d(TAG, "getUnifiedActivity code=" + resp.code
                    + " body=" + preview(resp.getBody()));

            if (!resp.isSuccess || resp.data == null) {
                postUi(this::showRecentEmpty);
                return;
            }
            JSONArray items = resp.data.optJSONArray("items");
            if (items == null) items = resp.data.optJSONArray("data");
            if (items == null) items = resp.data.optJSONArray("activities");
            if (items == null) items = resp.data.optJSONArray("logs");

            if (items == null || items.length() == 0) {
                postUi(this::showRecentEmpty);
                return;
            }
            final JSONArray finalItems = items;
            postUi(() -> renderRecent(finalItems));
        } catch (Throwable t) {
            Log.e(TAG, "loadRecentActivity error", t);
            postUi(this::showRecentEmpty);
        }
    }

    private void renderRecent(JSONArray items) {
        containerRecent.removeAllViews();
        containerRecent.setVisibility(View.VISIBLE);
        tvRecentEmpty.setVisibility(View.GONE);

        LayoutInflater inf = LayoutInflater.from(requireContext());
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;

            View row = inf.inflate(R.layout.item_dashboard_recent, containerRecent, false);

            String icon = it.optString("icon", "📝");
            if (icon.isEmpty()) icon = "📝";
            ((TextView) row.findViewById(R.id.tvRecentIcon)).setText(icon);

            // ✅ مفاتيح متعددة للعنوان
            String title = it.optString("title", "");
            if (title.isEmpty()) title = it.optString("action", "");
            if (title.isEmpty()) title = it.optString("type", "");
            if (title.isEmpty()) title = it.optString("event_type", "");
            if (title.isEmpty()) title = it.optString("name", "");
            if (title.isEmpty()) title = "نشاط";
            ((TextView) row.findViewById(R.id.tvRecentTitle)).setText(title);

            // ✅ مفاتيح متعددة للوصف
            String subtitle = it.optString("description", "");
            if (subtitle.isEmpty()) subtitle = it.optString("details", "");
            if (subtitle.isEmpty()) subtitle = it.optString("message", "");
            if (subtitle.isEmpty()) subtitle = it.optString("item_name", "");
            if (subtitle.isEmpty()) subtitle = it.optString("user_name", "");
            ((TextView) row.findViewById(R.id.tvRecentSubtitle)).setText(subtitle);

            ((TextView) row.findViewById(R.id.tvRecentDate))
                    .setText(shortDate(it.optString("created_at",
                            it.optString("date", it.optString("time", "")))));

            containerRecent.addView(row);
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
    //  Helpers
    // ═══════════════════════════════════════════════════════════
    private void postUi(Runnable r) {
        if (!isAdded() || getActivity() == null) return;
        getActivity().runOnUiThread(() -> { if (isAdded()) { try { r.run(); } catch (Exception ignored) {} } });
    }

    private double pick(JSONObject o, String... keys) {
        if (o == null) return 0;
        for (String k : keys) {
            if (o.has(k)) {
                try {
                    Object v = o.get(k);
                    if (v instanceof Number) return ((Number) v).doubleValue();
                    if (v instanceof String) return Double.parseDouble((String) v);
                } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    /**
     * ✅ استخراج مبلغ من مصفوفة حسابات بالبحث عن كلمة مفتاحية
     */
    private double extractFromAccountsArray(JSONArray arr, String... matchWords) {
        if (arr == null) return 0;
        double sum = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject acc = arr.optJSONObject(i);
            if (acc == null) continue;
            String name = acc.optString("account_name_ar", "") + " "
                    + acc.optString("account_name", "") + " "
                    + acc.optString("account_category", "") + " "
                    + acc.optString("category", "");
            boolean matched = false;
            for (String w : matchWords) {
                if (name.toLowerCase().contains(w.toLowerCase())) { matched = true; break; }
            }
            if (matched) {
                double bal = acc.optDouble("balance",
                        acc.optDouble("total_debit", 0) - acc.optDouble("total_credit", 0));
                sum += bal;
            }
        }
        return sum;
    }

    private double extractNested(JSONObject data, String field) {
        if (data == null) return 0;
        double direct = pick(data, field, "total_" + field);
        if (direct != 0) return direct;
        for (String container : new String[]{"assets", "liabilities", "equity", "data", "report", "summary"}) {
            JSONObject obj = data.optJSONObject(container);
            if (obj != null) {
                double v = pick(obj, field, "total_" + field);
                if (v != 0) return v;
            }
        }
        return 0;
    }

    private String fmt(double v) {
        try {
            NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
            nf.setMinimumFractionDigits(2);
            nf.setMaximumFractionDigits(2);
            return nf.format(v);
        } catch (Exception e) { return String.valueOf(v); }
    }

    private String shortDate(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        return iso.length() >= 10 ? iso.substring(0, 10) : iso;
    }

    private String preview(String body) {
        if (body == null) return "null";
        return body.length() > 400 ? body.substring(0, 400) + "..." : body;
    }

    private void navigateTo(@NonNull String screenId) {
        Fragment parent = getParentFragment();
        if (parent instanceof MiraAccountingFragmentNew) {
            ((MiraAccountingFragmentNew) parent).navigateToScreen(screenId);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
