package com.example.uhf.accounting;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
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
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraAccountingDashboardFragment extends Fragment {

    private static final String TAG = "MiraDashboard";

    private SwipeRefreshLayout swipeRefresh;
    private GridLayout gridKpi, gridQuick;
    private LinearLayout containerAlerts, containerRecent;
    private TextView tvAlertsEmpty, tvRecentEmpty;
    private TextView tvPeriodFrom, tvPeriodTo, tvBranch;

    private final Map<String, AccountingKpi> kpiMap = new HashMap<>();
    private final Map<String, View> kpiViews = new HashMap<>();

    // ✅ سجل استجابات الـ API — لعرضها عبر الضغط المطول
    private final Map<String, String> debugResponses = new LinkedHashMap<>();

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

        // ✅ وضع التشخيص: ضغط مطول على اسم الفرع
        tvBranch.setOnLongClickListener(x -> {
            showDebugDialog();
            return true;
        });

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
    //  Debug Dialog
    // ═══════════════════════════════════════════════════════════
    private void showDebugDialog() {
        if (!isAdded()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 استجابات API — للتشخيص\n\n");
        if (debugResponses.isEmpty()) {
            sb.append("(لا توجد استجابات بعد — اسحب لأسفل لإعادة التحميل)");
        } else {
            for (Map.Entry<String, String> e : debugResponses.entrySet()) {
                sb.append("━━━━━━━━━━━━━━━━━━━\n");
                sb.append("📡 ").append(e.getKey()).append("\n");
                sb.append(e.getValue()).append("\n\n");
            }
        }

        TextView tv = new TextView(requireContext());
        tv.setText(sb.toString());
        tv.setTextSize(11);
        tv.setPadding(24, 24, 24, 24);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setMovementMethod(new ScrollingMovementMethod());
        int px = (int) (350 * getResources().getDisplayMetrics().density);
        tv.setMaxHeight(px);

        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        scroll.addView(tv);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("🔧 التشخيص")
                .setView(scroll)
                .setPositiveButton("إغلاق", null)
                .setNeutralButton("إعادة التحميل", (d, w) -> {
                    debugResponses.clear();
                    loadDashboardData();
                })
                .show();
    }

    private void captureResponse(String key, MiraApiClient.ApiResponse resp) {
        if (resp == null) return;
        String body = resp.getBody();
        if (body == null || body.isEmpty()) body = "(فارغ)";
        debugResponses.put(key + " [code=" + resp.code + "]", body);
    }

    // ═══════════════════════════════════════════════════════════
    //  Period
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
                target.get(Calendar.YEAR), target.get(Calendar.MONTH),
                target.get(Calendar.DAY_OF_MONTH)).show();
    }

    private int getBranchId() {
        try { return session != null ? session.getBranchId() : 0; }
        catch (Exception e) { return 0; }
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
        View card = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_dashboard_kpi, gridKpi, false);
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
                value.setText("—"); value.setAlpha(0.4f);
                caption.setText("جاري التحميل...");
                pb.setVisibility(View.VISIBLE); break;
            case AccountingKpi.STATE_SUCCESS:
                value.setText(kpi.displayValue != null ? kpi.displayValue : "—");
                value.setAlpha(1.0f);
                caption.setText(kpi.caption != null ? kpi.caption : ""); break;
            case AccountingKpi.STATE_NOT_CONNECTED:
                value.setText("—"); value.setAlpha(0.5f);
                caption.setText("قيد الربط"); break;
            case AccountingKpi.STATE_EMPTY:
                value.setText("—"); value.setAlpha(0.5f);
                caption.setText(kpi.caption != null ? kpi.caption : "لا بيانات"); break;
            case AccountingKpi.STATE_ERROR:
                value.setText("—"); value.setAlpha(0.5f);
                caption.setText("");
                error.setText(kpi.errorMessage != null ? kpi.errorMessage : "تعذر التحميل");
                error.setVisibility(View.VISIBLE); break;
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
        View card = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_dashboard_quick_action, gridQuick, false);
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
        debugResponses.clear();
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
                .postDelayed(() -> { if (swipeRefresh != null) swipeRefresh.setRefreshing(false); }, 5000);
    }

    // ═══════════════════════════════════════════════════════════
    //  Income Statement
    // ═══════════════════════════════════════════════════════════
    private void loadIncomeStatement(String from, String to, int branch) {
        try {
            MiraApiClient.ApiResponse resp = api.getIncomeStatement(
                    branch > 0 ? String.valueOf(branch) : null, from, to);
            captureResponse("getIncomeStatement(" + from + "→" + to + ")", resp);

            if (!resp.isSuccess || resp.data == null) {
                String err = resp.error != null ? resp.error : "تعذر تحميل قائمة الدخل";
                postUi(() -> {
                    updateKpi(AccountingKpi.KEY_REVENUE, AccountingKpi.error(AccountingKpi.KEY_REVENUE, "الإيرادات", "📈", err));
                    updateKpi(AccountingKpi.KEY_EXPENSES, AccountingKpi.error(AccountingKpi.KEY_EXPENSES, "المصروفات", "📉", err));
                    updateKpi(AccountingKpi.KEY_NET_PROFIT, AccountingKpi.error(AccountingKpi.KEY_NET_PROFIT, "صافي الربح", "💎", err));
                });
                return;
            }

            double revenue   = pick(resp.data, "net_revenue", "revenue", "total_revenue", "total_revenues");
            double expenses  = pick(resp.data, "operating_expenses", "expenses", "total_expenses");
            double netProfit = pick(resp.data, "net_profit", "netProfit", "profit", "net_income");

            // ابحث داخل data.inner إن لم توجد مباشرة
            JSONObject inner = resp.data.optJSONObject("data");
            if (inner != null) {
                if (revenue == 0) revenue = pick(inner, "net_revenue", "revenue", "total_revenue");
                if (expenses == 0) expenses = pick(inner, "operating_expenses", "expenses", "total_expenses");
                if (netProfit == 0) netProfit = pick(inner, "net_profit", "profit");
            }

            final double rf = revenue, ef = expenses, nf = netProfit;
            postUi(() -> {
                String suffix = from + " → " + to;
                if (rf != 0) updateKpi(AccountingKpi.KEY_REVENUE, AccountingKpi.success(AccountingKpi.KEY_REVENUE, "الإيرادات", "📈", rf, fmt(rf), "YER", suffix));
                else updateKpi(AccountingKpi.KEY_REVENUE, AccountingKpi.empty(AccountingKpi.KEY_REVENUE, "الإيرادات", "📈", "لا حركات مرحّلة"));

                if (ef != 0) updateKpi(AccountingKpi.KEY_EXPENSES, AccountingKpi.success(AccountingKpi.KEY_EXPENSES, "المصروفات", "📉", ef, fmt(ef), "YER", suffix));
                else updateKpi(AccountingKpi.KEY_EXPENSES, AccountingKpi.empty(AccountingKpi.KEY_EXPENSES, "المصروفات", "📉", "لا حركات مرحّلة"));

                if (nf != 0) updateKpi(AccountingKpi.KEY_NET_PROFIT, AccountingKpi.success(AccountingKpi.KEY_NET_PROFIT, "صافي الربح", "💎", nf, fmt(nf), "YER", suffix));
                else updateKpi(AccountingKpi.KEY_NET_PROFIT, AccountingKpi.empty(AccountingKpi.KEY_NET_PROFIT, "صافي الربح", "💎", "لا حركات مرحّلة"));
            });
        } catch (Throwable t) {
            Log.e(TAG, "loadIncomeStatement error", t);
            postUi(() -> {
                updateKpi(AccountingKpi.KEY_REVENUE, AccountingKpi.error(AccountingKpi.KEY_REVENUE, "الإيرادات", "📈", "خطأ"));
                updateKpi(AccountingKpi.KEY_EXPENSES, AccountingKpi.error(AccountingKpi.KEY_EXPENSES, "المصروفات", "📉", "خطأ"));
                updateKpi(AccountingKpi.KEY_NET_PROFIT, AccountingKpi.error(AccountingKpi.KEY_NET_PROFIT, "صافي الربح", "💎", "خطأ"));
            });
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Balance Sheet
    // ═══════════════════════════════════════════════════════════
    private void loadBalanceSheet(String asOf, int branch, String today) {
        try {
            MiraApiClient.ApiResponse resp = api.getBalanceSheet(
                    branch > 0 ? String.valueOf(branch) : null, asOf);
            captureResponse("getBalanceSheet(as_of=" + asOf + ")", resp);

            if (!resp.isSuccess || resp.data == null) {
                String err = resp.error != null ? resp.error : "تعذر تحميل الميزانية";
                postUi(() -> updateKpi(AccountingKpi.KEY_CASH, AccountingKpi.error(AccountingKpi.KEY_CASH, "النقدية والخزائن", "💰", err)));
                loadAgingFallback(today, branch);
                return;
            }

            // ✅ استخراج النقدية: مباشرة → assets array → assets json → مفاتيح متعددة
            double cash = pick(resp.data, "total_cash", "cash_and_equivalents", "cash_balance");

            if (cash == 0) {
                JSONArray assetsArr = resp.data.optJSONArray("assets");
                cash = sumAccountsMatching(assetsArr, "cash", "نقد", "صندوق", "cash_on_hand", "cash_account");
            }
            if (cash == 0) {
                JSONObject assetsObj = resp.data.optJSONObject("assets");
                if (assetsObj != null) {
                    cash = pick(assetsObj, "cash", "total_cash", "cash_balance");
                }
            }

            // ذمم مدينة
            double receivables = pick(resp.data, "total_receivables");
            if (receivables == 0) {
                JSONArray arr = resp.data.optJSONArray("assets");
                receivables = sumAccountsMatching(arr, "receivable", "ذمم", "مدينة", "عملاء");
            }
            if (receivables == 0) {
                JSONObject obj = resp.data.optJSONObject("assets");
                if (obj != null) receivables = pick(obj, "receivables", "total_receivables");
            }

            // ذمم دائنة
            double payables = pick(resp.data, "total_payables");
            if (payables == 0) {
                JSONArray arr = resp.data.optJSONArray("liabilities");
                payables = sumAccountsMatching(arr, "payable", "دائنة", "مورد", "supplier");
            }
            if (payables == 0) {
                JSONObject obj = resp.data.optJSONObject("liabilities");
                if (obj != null) payables = pick(obj, "payables", "total_payables");
            }

            final double cf = cash, rcf = receivables, pf = payables;
            postUi(() -> {
                String suffix = isArabic ? ("كما في " + asOf) : ("as of " + asOf);
                if (cf != 0) updateKpi(AccountingKpi.KEY_CASH, AccountingKpi.success(AccountingKpi.KEY_CASH, "النقدية والخزائن", "💰", cf, fmt(cf), "YER", suffix));
                else updateKpi(AccountingKpi.KEY_CASH, AccountingKpi.empty(AccountingKpi.KEY_CASH, "النقدية والخزائن", "💰", "لا توجد أرصدة نقدية"));

                if (rcf != 0) updateKpi(AccountingKpi.KEY_RECEIVABLES, AccountingKpi.success(AccountingKpi.KEY_RECEIVABLES, "ذمم مدينة", "👤", rcf, fmt(rcf), "YER", suffix));
                if (pf != 0) updateKpi(AccountingKpi.KEY_PAYABLES, AccountingKpi.success(AccountingKpi.KEY_PAYABLES, "ذمم دائنة", "🏢", pf, fmt(pf), "YER", suffix));
            });

            if (cash == 0 || receivables == 0 || payables == 0) loadAgingFallback(today, branch);
        } catch (Throwable t) {
            Log.e(TAG, "loadBalanceSheet error", t);
            postUi(() -> updateKpi(AccountingKpi.KEY_CASH, AccountingKpi.error(AccountingKpi.KEY_CASH, "النقدية والخزائن", "💰", "خطأ")));
            loadAgingFallback(today, branch);
        }
    }

    private void loadAgingFallback(String today, int branch) {
        try {
            MiraApiClient.ApiResponse recv = api.getAgingReport(today, "receivable");
            captureResponse("getAgingReport(receivable)", recv);
            double totalRecv = 0;
            if (recv.isSuccess && recv.data != null) {
                totalRecv = pick(recv.data, "total", "grand_total", "total_outstanding");
                JSONObject inner = recv.data.optJSONObject("data");
                if (totalRecv == 0 && inner != null) totalRecv = pick(inner, "total", "grand_total");
            }
            final double tr = totalRecv;
            postUi(() -> {
                if (tr > 0) updateKpi(AccountingKpi.KEY_RECEIVABLES, AccountingKpi.success(AccountingKpi.KEY_RECEIVABLES, "ذمم مدينة", "👤", tr, fmt(tr), "YER", "كما في " + today));
                else updateKpi(AccountingKpi.KEY_RECEIVABLES, AccountingKpi.empty(AccountingKpi.KEY_RECEIVABLES, "ذمم مدينة", "👤", "لا توجد ذمم مدينة"));
            });
        } catch (Throwable t) {
            Log.e(TAG, "aging receivable error", t);
            postUi(() -> updateKpi(AccountingKpi.KEY_RECEIVABLES, AccountingKpi.error(AccountingKpi.KEY_RECEIVABLES, "ذمم مدينة", "👤", "تعذر التحميل")));
        }

        try {
            MiraApiClient.ApiResponse pay = api.getAgingReport(today, "payable");
            captureResponse("getAgingReport(payable)", pay);
            double totalPay = 0;
            if (pay.isSuccess && pay.data != null) {
                totalPay = pick(pay.data, "total", "grand_total", "total_outstanding");
                JSONObject inner = pay.data.optJSONObject("data");
                if (totalPay == 0 && inner != null) totalPay = pick(inner, "total", "grand_total");
            }
            final double tp = totalPay;
            postUi(() -> {
                if (tp > 0) updateKpi(AccountingKpi.KEY_PAYABLES, AccountingKpi.success(AccountingKpi.KEY_PAYABLES, "ذمم دائنة", "🏢", tp, fmt(tp), "YER", "كما في " + today));
                else updateKpi(AccountingKpi.KEY_PAYABLES, AccountingKpi.empty(AccountingKpi.KEY_PAYABLES, "ذمم دائنة", "🏢", "لا توجد ذمم دائنة"));
            });
        } catch (Throwable t) {
            Log.e(TAG, "aging payable error", t);
            postUi(() -> updateKpi(AccountingKpi.KEY_PAYABLES, AccountingKpi.error(AccountingKpi.KEY_PAYABLES, "ذمم دائنة", "🏢", "تعذر التحميل")));
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Alerts
    // ═══════════════════════════════════════════════════════════
    private void loadAlerts(int branch) {
        try {
            JSONArray alertsArr = new JSONArray();
            MiraApiClient.ApiResponse cheques = api.getCheques(null, "pending");
            captureResponse("getCheques(pending)", cheques);
            if (cheques.isSuccess && cheques.data != null) {
                JSONArray arr = cheques.data.optJSONArray("cheques");
                if (arr == null) arr = cheques.data.optJSONArray("data");
                if (arr != null && arr.length() > 0) {
                    JSONObject a = new JSONObject();
                    a.put("icon", "📑"); a.put("title", "شيكات معلّقة");
                    a.put("subtitle", "بحاجة إلى إيداع"); a.put("count", arr.length());
                    a.put("color", "#F59E0B"); a.put("target", MiraAccountingScreens.ID_CHEQUES);
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
            try { ((View) row.findViewById(R.id.vAlertColor)).setBackgroundColor(android.graphics.Color.parseColor(a.optString("color", "#F59E0B"))); } catch (Exception ignored) {}
            final String target = a.optString("target", "");
            if (!target.isEmpty()) row.setOnClickListener(v -> navigateTo(target));
            containerAlerts.addView(row);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Recent
    // ═══════════════════════════════════════════════════════════
    private void loadRecentActivity(int branch) {
        try {
            MiraApiClient.ApiResponse resp = api.getUnifiedActivity(branch, 5);
            captureResponse("getUnifiedActivity(branch=" + branch + ")", resp);
            if (!resp.isSuccess || resp.data == null) { postUi(this::showRecentEmpty); return; }
            JSONArray items = resp.data.optJSONArray("items");
            if (items == null) items = resp.data.optJSONArray("data");
            if (items == null) items = resp.data.optJSONArray("activities");
            if (items == null || items.length() == 0) { postUi(this::showRecentEmpty); return; }
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

            String title = firstNonEmpty(it, "title", "action", "type", "event_type", "name");
            if (title.isEmpty()) title = "نشاط";
            ((TextView) row.findViewById(R.id.tvRecentTitle)).setText(title);

            String subtitle = firstNonEmpty(it, "description", "details", "message", "item_name", "user_name", "reference");
            ((TextView) row.findViewById(R.id.tvRecentSubtitle)).setText(subtitle);

            ((TextView) row.findViewById(R.id.tvRecentDate))
                    .setText(shortDate(firstNonEmpty(it, "created_at", "date", "time", "timestamp")));

            containerRecent.addView(row);
        }
    }

    private String firstNonEmpty(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.isEmpty() && !"null".equals(v)) return v;
        }
        return "";
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

    /** جمع balances لحسابات تطابق كلمة مفتاحية */
    private double sumAccountsMatching(JSONArray arr, String... words) {
        if (arr == null) return 0;
        double sum = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject acc = arr.optJSONObject(i);
            if (acc == null) continue;
            String hay = (acc.optString("account_name_ar", "") + " "
                    + acc.optString("account_name", "") + " "
                    + acc.optString("account_category", "") + " "
                    + acc.optString("category", "") + " "
                    + acc.optString("account_code", "")).toLowerCase();
            boolean matched = false;
            for (String w : words) if (hay.contains(w.toLowerCase())) { matched = true; break; }
            if (matched) {
                double bal = acc.optDouble("balance",
                        acc.optDouble("total_debit", 0) - acc.optDouble("total_credit", 0));
                sum += bal;
            }
        }
        return sum;
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
