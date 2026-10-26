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
 * MIRA Accounting — Dashboard (Stage 4.2)
 *
 * يجلب الأرقام الفعلية من MiraApiClient وليس من بيانات ثابتة.
 * كل KPI يُحمّل مستقلّاً — فشل واحد لا يؤثر على الآخرين.
 *
 * مصادر البيانات:
 *   • getIncomeStatement  → revenue, expenses, net_profit
 *   • getBalanceSheet     → cash, receivables, payables
 *   • getAgingReport      → fallback للذمم
 *   • getCheques          → تنبيهات الشيكات المستحقة
 *   • getLockedPeriods    → تنبيهات الفترات غير المقفلة
 *   • getUnifiedActivity  → آخر الأنشطة
 *
 * كل استجابة تُسجَّل في logcat تحت tag "MiraDashboard" للتحقق اليدوي.
 */
public class MiraAccountingDashboardFragment extends Fragment {

    private static final String TAG = "MiraDashboard";

    // ─── Views ───
    private SwipeRefreshLayout swipeRefresh;
    private GridLayout gridKpi;
    private GridLayout gridQuick;
    private LinearLayout containerAlerts;
    private LinearLayout containerRecent;
    private TextView tvAlertsEmpty, tvRecentEmpty;
    private TextView tvPeriodFrom, tvPeriodTo, tvBranch;

    // ─── State ───
    private final Map<String, AccountingKpi> kpiMap = new HashMap<>();
    private final Map<String, View> kpiViews = new HashMap<>();
    private Calendar periodFrom, periodTo;
    private boolean isArabic = true;
    private ExecutorService io;

    private MiraApiClient api;
    private SessionManager session;

    // ═══════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════
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

        // الفترة الافتراضية: بداية السنة المالية حتى اليوم
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

    // ═══════════════════════════════════════════════════════════
    //  Period Picker
    // ═══════════════════════════════════════════════════════════
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
                target.get(Calendar.YEAR),
                target.get(Calendar.MONTH),
                target.get(Calendar.DAY_OF_MONTH)).show();
    }

    private int getBranchId() {
        try {
            return session != null ? session.getBranchId() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  KPI Cards — بنية ثابتة
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

    // ═══════════════════════════════════════════════════════════
    //  Quick Actions
    // ═══════════════════════════════════════════════════════════
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

    // ═══════════════════════════════════════════════════════════
    //  Data Loading — كل KPI في thread منفصل
    // ═══════════════════════════════════════════════════════════
    private void loadDashboardData() {
        if (api == null) return;
        swipeRefresh.setRefreshing(true);

        // reset إلى LOADING
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

        // إيقاف مؤشر التحديث بعد فترة قصيرة بغض النظر
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> {
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                }, 4000);
    }

    // ═══════════════════════════════════════════════════════════
    //  Income Statement → Revenue, Expenses, Net Profit
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
                            AccountingKpi.error(KEY(AccountingKpi.KEY_REVENUE), "الإيرادات", "📈", err));
                    updateKpi(AccountingKpi.KEY_EXPENSES,
                            AccountingKpi.error(KEY(AccountingKpi.KEY_EXPENSES), "المصروفات", "📉", err));
                    updateKpi(AccountingKpi.KEY_NET_PROFIT,
                            AccountingKpi.error(KEY(AccountingKpi.KEY_NET_PROFIT), "صافي الربح", "💎", err));
                });
                return;
            }

            // جرّب عدة مفاتيح محتملة (API قد يستخدم أي منها)
            double revenue   = pick(resp.data, "net_revenue", "revenue", "total_revenue");
            double expenses  = pick(resp.data, "operating_expenses", "expenses", "total_expenses");
            double netProfit = pick(resp.data, "net_profit", "netProfit", "profit");

            // إن لم يوجد أي مصدر → EMPTY
            boolean hasAny = revenue != 0 || expenses != 0 || netProfit != 0;

            postUi(() -> {
                String suffix = from + " → " + to;
                if (hasAny) {
                    updateKpi(AccountingKpi.KEY_REVENUE,
                            AccountingKpi.success(KEY(AccountingKpi.KEY_REVENUE), "الإيرادات", "📈",
                                    revenue, fmt(revenue), "YER", suffix));
                    updateKpi(AccountingKpi.KEY_EXPENSES,
                            AccountingKpi.success(KEY(AccountingKpi.KEY_EXPENSES), "المصروفات", "📉",
                                    expenses, fmt(expenses), "YER", suffix));
                    updateKpi(AccountingKpi.KEY_NET_PROFIT,
                            AccountingKpi.success(KEY(AccountingKpi.KEY_NET_PROFIT), "صافي الربح", "💎",
                                    netProfit, fmt(netProfit), "YER", suffix));
                } else {
                    updateKpi(AccountingKpi.KEY_REVENUE,
                            AccountingKpi.empty(KEY(AccountingKpi.KEY_REVENUE), "الإيرادات", "📈", "لا حركات في الفترة"));
                    updateKpi(AccountingKpi.KEY_EXPENSES,
                            AccountingKpi.empty(KEY(AccountingKpi.KEY_EXPENSES), "المصروفات", "📉", "لا حركات في الفترة"));
                    updateKpi(AccountingKpi.KEY_NET_PROFIT,
                            AccountingKpi.empty(KEY(AccountingKpi.KEY_NET_PROFIT), "صافي الربح", "💎", "لا حركات في الفترة"));
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "loadIncomeStatement error", t);
            postUi(() -> {
                updateKpi(AccountingKpi.KEY_REVENUE,
                        AccountingKpi.error(KEY(AccountingKpi.KEY_REVENUE), "الإيرادات", "📈", "خطأ غير متوقع"));
            });
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Balance Sheet → Cash, Receivables, Payables
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
                    updateKpi(KEY(AccountingKpi.KEY_CASH),
                            AccountingKpi.error(AccountingKpi.KEY_CASH, "النقدية والخزائن", "💰", err));
                    // الذمم ستُجلب من aging كاحتياطي
                });
                loadAgingFallback(today, branch);
                return;
            }

            // محاولة قراءة cash / receivables / payables
            double cash        = extractNested(resp.data, "cash");
            double receivables = extractNested(resp.data, "receivables");
            double payables    = extractNested(resp.data, "payables");

            // Cash — يعتمد على total_assets في بعض التطبيقات إذا لم يجد cash محدداً
            if (cash == 0) {
                cash = pick(resp.data, "total_cash", "cash_and_equivalents", "cash_balance");
            }

            postUi(() -> {
                String suffix = isArabic ? ("كما في " + asOf) : ("as of " + asOf);
                if (cash != 0) {
                    updateKpi(AccountingKpi.KEY_CASH,
                            AccountingKpi.success(AccountingKpi.KEY_CASH, "النقدية والخزائن", "💰",
                                    cash, fmt(cash), "YER", suffix));
                } else {
                    updateKpi(AccountingKpi.KEY_CASH,
                            AccountingKpi.empty(AccountingKpi.KEY_CASH, "النقدية والخزائن", "💰",
                                    "لم تُعثر على أرصدة نقدية في الاستجابة"));
                }

                if (receivables != 0) {
                    updateKpi(AccountingKpi.KEY_RECEIVABLES,
                            AccountingKpi.success(AccountingKpi.KEY_RECEIVABLES, "ذمم مدينة", "👤",
                                    receivables, fmt(receivables), "YER", suffix));
                }
                if (payables != 0) {
                    updateKpi(AccountingKpi.KEY_PAYABLES,
                            AccountingKpi.success(AccountingKpi.KEY_PAYABLES, "ذمم دائنة", "🏢",
                                    payables, fmt(payables), "YER", suffix));
                }
            });

            // إذا كانت الذمم لم تُستخرج، استخدم aging كاحتياطي
            if (receivables == 0 || payables == 0) {
                loadAgingFallback(today, branch);
            }
        } catch (Throwable t) {
            Log.e(TAG, "loadBalanceSheet error", t);
            postUi(() -> {
                updateKpi(AccountingKpi.KEY_CASH,
                        AccountingKpi.error(AccountingKpi.KEY_CASH, "النقدية والخزائن", "💰", "خطأ غير متوقع"));
            });
            loadAgingFallback(today, branch);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Aging Fallback → Receivables / Payables
    // ═══════════════════════════════════════════════════════════
    private void loadAgingFallback(String today, int branch) {
        try {
            MiraApiClient.ApiResponse recv = api.getAgingReport(today, "receivable");
            Log.d(TAG, "getAgingReport receivable code=" + recv.code
                    + " body=" + preview(recv.getBody()));

            if (recv.isSuccess && recv.data != null) {
                double totalRecv = pick(recv.data, "total", "grand_total", "total_outstanding");
                postUi(() -> {
                    if (totalRecv > 0) {
                        updateKpi(AccountingKpi.KEY_RECEIVABLES,
                                AccountingKpi.success(AccountingKpi.KEY_RECEIVABLES, "ذمم مدينة", "👤",
                                        totalRecv, fmt(totalRecv), "YER", "كما في " + today));
                    }
                });
            }

            MiraApiClient.ApiResponse pay = api.getAgingReport(today, "payable");
            Log.d(TAG, "getAgingReport payable code=" + pay.code
                    + " body=" + preview(pay.getBody()));

            if (pay.isSuccess && pay.data != null) {
                double totalPay = pick(pay.data, "total", "grand_total", "total_outstanding");
                postUi(() -> {
                    if (totalPay > 0) {
                        updateKpi(AccountingKpi.KEY_PAYABLES,
                                AccountingKpi.success(AccountingKpi.KEY_PAYABLES, "ذمم دائنة", "🏢",
                                        totalPay, fmt(totalPay), "YER", "كما في " + today));
                    }
                });
            }
        } catch (Throwable t) {
            Log.e(TAG, "loadAgingFallback error", t);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Alerts — Cheques + Period Locks
    // ═══════════════════════════════════════════════════════════
    private void loadAlerts(int branch) {
        try {
            int alertsCount = 0;
            JSONArray alertsArr = new JSONArray();

            // 1) الشيكات المعلّقة
            MiraApiClient.ApiResponse cheques = api.getCheques(null, "pending");
            Log.d(TAG, "getCheques pending code=" + cheques.code
                    + " body=" + preview(cheques.getBody()));

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
                    alertsCount++;
                }
            }

            // 2) الفترات المحاسبية
            MiraApiClient.ApiResponse periods = api.getLockedPeriods();
            Log.d(TAG, "getLockedPeriods code=" + periods.code
                    + " body=" + preview(periods.getBody()));

            // ملاحظة: نعرض التنبيه فقط إذا كانت الاستجابة تحتوي على فترات غير مقفلة
            // (تُحدَّد في السيرفر — قد تحتاج تعديلاً في endpoint مستقبلاً)

            postUi(() -> renderAlerts(alertsArr));
        } catch (Throwable t) {
            Log.e(TAG, "loadAlerts error", t);
            postUi(this::showAlertsEmpty);
        }
    }

    private void renderAlerts(JSONArray alerts) {
        containerAlerts.removeAllViews();
        if (alerts.length() == 0) {
            showAlertsEmpty();
            return;
        }
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

            View colorBar = row.findViewById(R.id.vAlertColor);
            try {
                colorBar.setBackgroundColor(android.graphics.Color.parseColor(a.optString("color", "#F59E0B")));
            } catch (Exception ignored) {}

            final String target = a.optString("target", "");
            if (!target.isEmpty()) {
                row.setOnClickListener(v -> navigateTo(target));
            }
            containerAlerts.addView(row);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Recent Activity
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
            ((TextView) row.findViewById(R.id.tvRecentIcon))
                    .setText(it.optString("icon", "📝"));
            ((TextView) row.findViewById(R.id.tvRecentTitle))
                    .setText(it.optString("title", it.optString("action", "نشاط")));
            ((TextView) row.findViewById(R.id.tvRecentSubtitle))
                    .setText(it.optString("description", it.optString("details", "")));
            ((TextView) row.findViewById(R.id.tvRecentDate))
                    .setText(shortDate(it.optString("created_at", it.optString("date", ""))));

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
        getActivity().runOnUiThread(() -> {
            if (isAdded()) {
                try { r.run(); } catch (Exception ignored) {}
            }
        });
    }

    /** جرّب عدة مفاتيح في JSON وأعد أول قيمة موجودة */
    private double pick(JSONObject o, String... keys) {
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

    /** يبحث داخل assets/liabilities عن حقل مالي */
    private double extractNested(JSONObject data, String field) {
        // جرّب مباشرة
        double direct = pick(data, field, "total_" + field);
        if (direct != 0) return direct;

        // ابحث داخل assets أو liabilities
        for (String container : new String[]{"assets", "liabilities", "equity"}) {
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
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    private String shortDate(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            String d = iso.length() >= 10 ? iso.substring(0, 10) : iso;
            return d;
        } catch (Exception e) {
            return iso;
        }
    }

    private String preview(String body) {
        if (body == null) return "null";
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }

    private String KEY(String key) {
        return key;
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
