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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MIRA Accounting — Dashboard (Stage 4.5)
 *
 * إصلاحات أساسية بعد قراءة استجابات API الفعلية:
 *  1. Balance Sheet: السيرفر يُرجع JSON تالف (assets ككائن بمفاتيح مكررة)
 *     → نحلّل النص الخام يدوياً ونستخرج كل حساب على حدة
 *  2. تصنيف الحسابات بحسب account_category و account_code (cash/banks/receivable/payable)
 *  3. Recent Activity: قراءة من data.data.items (مع تراجع احتياطي)
 *  4. لا شيء يبقى في "جاري التحميل" — كل حالة تُحدَّث نهائياً
 *  5. وضع تشخيص مدمج: ضغط مطول على اسم الفرع
 */
public class MiraAccountingDashboardFragment extends Fragment {

    private static final String TAG = "MiraDashboard";

    // ─── Views ───
    private SwipeRefreshLayout swipeRefresh;
    private GridLayout gridKpi, gridQuick;
    private LinearLayout containerAlerts, containerRecent;
    private TextView tvAlertsEmpty, tvRecentEmpty;
    private TextView tvPeriodFrom, tvPeriodTo, tvBranch;

    // ─── State ───
    private final Map<String, AccountingKpi> kpiMap = new HashMap<>();
    private final Map<String, View> kpiViews = new HashMap<>();
    private final Map<String, String> debugResponses = new LinkedHashMap<>();

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

        periodTo = Calendar.getInstance();
        periodFrom = Calendar.getInstance();
        periodFrom.set(Calendar.DAY_OF_MONTH, 1);
        periodFrom.set(Calendar.MONTH, Calendar.JANUARY);
        refreshPeriodLabels();

        tvPeriodFrom.setOnClickListener(x -> openDatePicker(periodFrom, true));
        tvPeriodTo.setOnClickListener(x -> openDatePicker(periodTo, false));
        tvBranch.setOnLongClickListener(x -> { showDebugDialog(); return true; });

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
        StringBuilder sb = new StringBuilder("🔍 استجابات API\n\n");
        if (debugResponses.isEmpty()) {
            sb.append("(لا توجد استجابات — اسحب لأسفل)");
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
        tv.setMaxHeight((int) (350 * getResources().getDisplayMetrics().density));

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

    // ═══════════════════════════════════════════════════════════
    //  Load
    // ═══════════════════════════════════════════════════════════
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
        final int branch   = getBranchId();

        io.execute(() -> loadIncomeStatement(from, to, branch));
        io.execute(() -> loadBalanceSheet(to, branch));
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

            double revenue   = pick(resp.data, "net_revenue", "revenue", "total_revenue", "sales_revenue");
            double expenses  = pick(resp.data, "operating_expenses", "expenses", "total_expenses");
            double netProfit = pick(resp.data, "net_profit", "netProfit", "profit", "net_income");

            final double rf = revenue, ef = expenses, nf = netProfit;
            postUi(() -> {
                String suffix = from + " → " + to;
                if (rf != 0) updateKpi(AccountingKpi.KEY_REVENUE, AccountingKpi.success(AccountingKpi.KEY_REVENUE, "الإيرادات", "📈", rf, fmt(rf), "YER", suffix));
                else updateKpi(AccountingKpi.KEY_REVENUE, AccountingKpi.empty(AccountingKpi.KEY_REVENUE, "الإيرادات", "📈", "لا قيود مرحّلة في الفترة"));

                if (ef != 0) updateKpi(AccountingKpi.KEY_EXPENSES, AccountingKpi.success(AccountingKpi.KEY_EXPENSES, "المصروفات", "📉", ef, fmt(ef), "YER", suffix));
                else updateKpi(AccountingKpi.KEY_EXPENSES, AccountingKpi.empty(AccountingKpi.KEY_EXPENSES, "المصروفات", "📉", "لا قيود مرحّلة في الفترة"));

                if (nf != 0) updateKpi(AccountingKpi.KEY_NET_PROFIT, AccountingKpi.success(AccountingKpi.KEY_NET_PROFIT, "صافي الربح", "💎", nf, fmt(nf), "YER", suffix));
                else updateKpi(AccountingKpi.KEY_NET_PROFIT, AccountingKpi.empty(AccountingKpi.KEY_NET_PROFIT, "صافي الربح", "💎", "لا قيود مرحّلة في الفترة"));
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
    //  ✅ Balance Sheet — يعالج JSON التالف
    // ═══════════════════════════════════════════════════════════
    private void loadBalanceSheet(String asOf, int branch) {
        try {
            MiraApiClient.ApiResponse resp = api.getBalanceSheet(
                    branch > 0 ? String.valueOf(branch) : null, asOf);
            captureResponse("getBalanceSheet(as_of=" + asOf + ")", resp);

            if (!resp.isSuccess || resp.data == null) {
                String err = resp.error != null ? resp.error : "تعذر تحميل الميزانية";
                postUi(() -> {
                    updateKpi(AccountingKpi.KEY_CASH, AccountingKpi.error(AccountingKpi.KEY_CASH, "النقدية والخزائن", "💰", err));
                    updateKpi(AccountingKpi.KEY_RECEIVABLES, AccountingKpi.error(AccountingKpi.KEY_RECEIVABLES, "ذمم مدينة", "👤", err));
                    updateKpi(AccountingKpi.KEY_PAYABLES, AccountingKpi.error(AccountingKpi.KEY_PAYABLES, "ذمم دائنة", "🏢", err));
                });
                return;
            }

            String rawBody = resp.getBody();
            boolean malformed = isJsonMalformed(rawBody);

            double cash = 0, receivables = 0, payables = 0;

            if (malformed) {
                Log.w(TAG, "⚠️ getBalanceSheet returns MALFORMED JSON — parsing raw text");
                List<JSONObject> assets = extractAccountsFromMalformedJson(rawBody, "assets");
                List<JSONObject> liabilities = extractAccountsFromMalformedJson(rawBody, "liabilities");
                Log.d(TAG, "Parsed " + assets.size() + " assets, " + liabilities.size() + " liabilities");

                for (JSONObject acc : assets) {
                    String cat  = acc.optString("account_category", "").toLowerCase(Locale.US);
                    String code = acc.optString("account_code", "");
                    double bal  = acc.optDouble("balance", 0);
                    if (isCashAccount(cat, code))            cash += bal;
                    else if (isReceivableAccount(cat, code)) receivables += bal;
                }
                for (JSONObject acc : liabilities) {
                    String cat  = acc.optString("account_category", "").toLowerCase(Locale.US);
                    String code = acc.optString("account_code", "");
                    double bal  = acc.optDouble("balance", 0);
                    if (isPayableAccount(cat, code)) payables += bal;
                }
            } else {
                JSONObject assets = resp.data.optJSONObject("assets");
                JSONObject liabilities = resp.data.optJSONObject("liabilities");
                if (assets != null) {
                    cash += sumArrayBalances(assets.optJSONArray("cash"));
                    cash += sumArrayBalances(assets.optJSONArray("banks"));
                    receivables = sumArrayBalances(assets.optJSONArray("receivables"));
                }
                if (liabilities != null) {
                    payables = sumArrayBalances(liabilities.optJSONArray("payables"));
                }
            }

            Log.d(TAG, "BalanceSheet → cash=" + cash + " recv=" + receivables + " pay=" + payables);

            final double cf = cash, rcf = receivables, pf = payables;
            postUi(() -> {
                String suffix = isArabic ? ("كما في " + asOf) : ("as of " + asOf);

                if (cf != 0) updateKpi(AccountingKpi.KEY_CASH, AccountingKpi.success(AccountingKpi.KEY_CASH, "النقدية والخزائن", "💰", cf, fmt(cf), "YER", suffix));
                else updateKpi(AccountingKpi.KEY_CASH, AccountingKpi.empty(AccountingKpi.KEY_CASH, "النقدية والخزائن", "💰", "لا توجد أرصدة نقدية"));

                if (rcf != 0) updateKpi(AccountingKpi.KEY_RECEIVABLES, AccountingKpi.success(AccountingKpi.KEY_RECEIVABLES, "ذمم مدينة", "👤", rcf, fmt(rcf), "YER", suffix));
                else updateKpi(AccountingKpi.KEY_RECEIVABLES, AccountingKpi.empty(AccountingKpi.KEY_RECEIVABLES, "ذمم مدينة", "👤", "لا توجد ذمم مدينة"));

                if (pf != 0) updateKpi(AccountingKpi.KEY_PAYABLES, AccountingKpi.success(AccountingKpi.KEY_PAYABLES, "ذمم دائنة", "🏢", pf, fmt(pf), "YER", suffix));
                else updateKpi(AccountingKpi.KEY_PAYABLES, AccountingKpi.empty(AccountingKpi.KEY_PAYABLES, "ذمم دائنة", "🏢", "لا توجد ذمم دائنة"));
            });
        } catch (Throwable t) {
            Log.e(TAG, "loadBalanceSheet error", t);
            postUi(() -> {
                updateKpi(AccountingKpi.KEY_CASH, AccountingKpi.error(AccountingKpi.KEY_CASH, "النقدية والخزائن", "💰", "خطأ"));
                updateKpi(AccountingKpi.KEY_RECEIVABLES, AccountingKpi.error(AccountingKpi.KEY_RECEIVABLES, "ذمم مدينة", "👤", "خطأ"));
                updateKpi(AccountingKpi.KEY_PAYABLES, AccountingKpi.error(AccountingKpi.KEY_PAYABLES, "ذمم دائنة", "🏢", "خطأ"));
            });
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
                    a.put("icon", "📑");
                    a.put("title", "شيكات معلّقة");
                    a.put("subtitle", "بحاجة إلى إيداع");
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
    //  Recent Activity — data.data.items
    // ═══════════════════════════════════════════════════════════
    private void loadRecentActivity(int branch) {
        try {
            MiraApiClient.ApiResponse resp = api.getUnifiedActivity(branch, 5);
            captureResponse("getUnifiedActivity(branch=" + branch + ")", resp);
            if (!resp.isSuccess || resp.data == null) { postUi(this::showRecentEmpty); return; }

            JSONArray items = null;
            JSONObject inner = resp.data.optJSONObject("data");
            if (inner != null) items = inner.optJSONArray("items");
            if (items == null) items = resp.data.optJSONArray("items");
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

            String subtitle = firstNonEmpty(it, "details", "description", "message", "reference", "item_name", "username");
            ((TextView) row.findViewById(R.id.tvRecentSubtitle)).setText(subtitle);

            ((TextView) row.findViewById(R.id.tvRecentDate))
                    .setText(shortDate(firstNonEmpty(it, "timestamp", "created_at", "date", "time")));

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
    //  JSON Helpers
    // ═══════════════════════════════════════════════════════════
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

    private double sumArrayBalances(JSONArray arr) {
        if (arr == null) return 0;
        double sum = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject acc = arr.optJSONObject(i);
            if (acc == null) continue;
            sum += acc.optDouble("balance", 0);
        }
        return sum;
    }

    /**
     * فحص: هل الـ JSON التالف يعيد "id" أكثر من مرة داخل "assets":{...}؟
     * هذا يكشف أن السيرفر يعيد objects بدل arrays.
     */
    private boolean isJsonMalformed(String raw) {
        if (raw == null) return false;
        int aStart = raw.indexOf("\"assets\":{");
        if (aStart < 0) return false;
        int aEnd = raw.indexOf("}", aStart);
        if (aEnd < 0) return false;
        String section = raw.substring(aStart, aEnd);
        int idCount = 0;
        int idx = 0;
        while ((idx = section.indexOf("\"id\":", idx)) >= 0) {
            idCount++;
            idx += 5;
            if (idCount > 1) return true;
        }
        return false;
    }

    /**
     * استخراج حسابات من JSON تالف: {"id":"41",... ,"id":"42",...}
     * يقسم على الفاصلة التي تسبق "id" — لأن هذا بداية حساب جديد.
     */
    private List<JSONObject> extractAccountsFromMalformedJson(String raw, String sectionKey) {
        List<JSONObject> result = new ArrayList<>();
        if (raw == null) return result;

        String searchKey = "\"" + sectionKey + "\":";
        int start = raw.indexOf(searchKey);
        if (start < 0) return result;

        int braceStart = start + searchKey.length();
        while (braceStart < raw.length() && raw.charAt(braceStart) != '{') braceStart++;
        if (braceStart >= raw.length()) return result;

        int depth = 0;
        int braceEnd = -1;
        for (int i = braceStart; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { braceEnd = i; break; }
            }
        }
        if (braceEnd < 0) return result;

        String inner = raw.substring(braceStart + 1, braceEnd);
        String[] parts = inner.split(",(?=\"id\":\")");
        for (String part : parts) {
            if (part == null || part.isEmpty()) continue;
            try {
                String jsonStr = "{" + part + "}";
                JSONObject obj = new JSONObject(jsonStr);
                result.add(obj);
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse account part: "
                        + (part.length() > 80 ? part.substring(0, 80) + "..." : part));
            }
        }
        return result;
    }

    /** تصنيف: هل هذا الحساب نقدي (cash/bank)؟ */
    private boolean isCashAccount(String category, String code) {
        if ("cash".equals(category) || "bank".equals(category)) return true;
        return code.startsWith("1101") || code.startsWith("1102")
                || code.startsWith("1110") || code.startsWith("1111")
                || code.startsWith("1112") || code.startsWith("1113")
                || code.startsWith("1114") || code.startsWith("1115");
    }

    /** تصنيف: هل هذا الحساب ذمم مدينة (receivable)؟ */
    private boolean isReceivableAccount(String category, String code) {
        if ("receivable".equals(category)) return true;
        return code.startsWith("113") || code.startsWith("114")
                || code.startsWith("1125") || code.startsWith("1201");
    }

    /** تصنيف: هل هذا الحساب ذمم دائنة (payable)؟ */
    private boolean isPayableAccount(String category, String code) {
        if ("payable".equals(category)) return true;
        return code.startsWith("21") || code.startsWith("22");
    }

    // ═══════════════════════════════════════════════════════════
    //  Utility
    // ═══════════════════════════════════════════════════════════
    private void postUi(Runnable r) {
        if (!isAdded() || getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (isAdded()) {
                try { r.run(); } catch (Exception ignored) {}
            }
        });
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
