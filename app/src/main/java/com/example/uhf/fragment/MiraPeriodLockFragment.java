package com.example.uhf.fragment;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
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

import com.example.uhf.R;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.SessionManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraPeriodLockFragment extends Fragment {

    private static final String TAG = "MiraPeriodLockFragment";

    // ⚠️ عدّل هذا ليطابق عنوان موقعك (بدون / في النهاية)
    private static final String BASE_URL = "https://your-domain.com/wp-json/mira/v2";

    private static final String[] MONTHS_AR = {
        "يناير","فبراير","مارس","أبريل","مايو","يونيو",
        "يوليو","أغسطس","سبتمبر","أكتوبر","نوفمبر","ديسمبر"
    };

    private LinearLayout yearBar;
    private GridLayout monthsGrid;
    private ProgressBar progressBar;

    private int currentYear;
    private boolean isArabic = true;
    private boolean canToggle = false;

    /** key = "YYYY-M"  →  status  ("locked" / "unlocked") */
    private final Map<String, String> statusMap = new HashMap<>();
    private int busyMonth = -1;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private PermissionManager permissionManager;
    private SessionManager sessionManager;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isArabic    = "ar".equals(Locale.getDefault().getLanguage());
        currentYear = Calendar.getInstance().get(Calendar.YEAR);

        if (getContext() != null) {
            try {
                permissionManager = PermissionManager.getInstance(requireContext());
                sessionManager    = new SessionManager(requireContext());
                canToggle         = permissionManager.isAdmin()
                                 || permissionManager.isBranchManager();
            } catch (Exception e) {
                Log.e(TAG, "init error: " + e.getMessage());
            }
        }
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_mira_period_lock, container, false);

        yearBar     = v.findViewById(R.id.yearBar);
        monthsGrid  = v.findViewById(R.id.monthsGrid);
        progressBar = v.findViewById(R.id.progressBar);

        buildYearSelector();
        renderMonths();
        fetchPeriods();

        return v;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ═════════════════════════════════════════════════════════
    //  بناء السنة
    // ═════════════════════════════════════════════════════════
    private void buildYearSelector() {
        yearBar.removeAllViews();
        int cy = Calendar.getInstance().get(Calendar.YEAR);
        int[] years = { cy, cy - 1, cy - 2 };

        for (int y : years) {
            TextView tv = new TextView(requireContext());
            tv.setText(String.valueOf(y));
            tv.setPadding(dp(22), dp(10), dp(22), dp(10));
            tv.setTypeface(null, Typeface.BOLD);
            tv.setTextSize(15f);
            tv.setGravity(Gravity.CENTER);

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(22));
            bg.setColor(y == currentYear ? Color.parseColor("#1E3A8A")
                                          : Color.parseColor("#E5E7EB"));
            tv.setBackground(bg);
            tv.setTextColor(y == currentYear ? Color.WHITE
                                              : Color.parseColor("#374151"));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(6), 0, dp(6), 0);
            tv.setLayoutParams(lp);

            final int yy = y;
            tv.setOnClickListener(view -> {
                if (currentYear == yy) return;
                currentYear = yy;
                buildYearSelector();
                renderMonths();
            });

            yearBar.addView(tv);
        }
    }

    // ═════════════════════════════════════════════════════════
    //  رسم شبكة الشهور
    // ═════════════════════════════════════════════════════════
    private void renderMonths() {
        monthsGrid.removeAllViews();
        int padding = dp(6);

        for (int m = 1; m <= 12; m++) {
            final int month = m;
            String key    = currentYear + "-" + m;
            String status = statusMap.containsKey(key) ? statusMap.get(key) : "unlocked";
            boolean locked = "locked".equals(status);
            boolean busy   = (busyMonth == m);

            LinearLayout cell = new LinearLayout(requireContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(10), dp(22), dp(10), dp(22));

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(locked ? Color.parseColor("#FEE2E2")
                                : Color.parseColor("#DCFCE7"));
            bg.setStroke(dp(2), Color.parseColor(locked ? "#EF4444" : "#22C55E"));
            cell.setBackground(bg);

            TextView nameTv = new TextView(requireContext());
            nameTv.setText(MONTHS_AR[m - 1]);
            nameTv.setTextSize(16f);
            nameTv.setTypeface(null, Typeface.BOLD);
            nameTv.setTextColor(Color.parseColor("#111827"));
            nameTv.setGravity(Gravity.CENTER);

            TextView statusTv = new TextView(requireContext());
            statusTv.setTextSize(13f);
            statusTv.setGravity(Gravity.CENTER);
            statusTv.setPadding(0, dp(8), 0, 0);

            if (busy) {
                statusTv.setText("⏳...");
                statusTv.setTextColor(Color.parseColor("#6B7280"));
            } else if (locked) {
                statusTv.setText("🔒 " + (isArabic ? "مقفل" : "Locked"));
                statusTv.setTextColor(Color.parseColor("#B91C1C"));
            } else {
                statusTv.setText("🔓 " + (isArabic ? "مفتوح" : "Open"));
                statusTv.setTextColor(Color.parseColor("#15803D"));
            }

            cell.addView(nameTv);
            cell.addView(statusTv);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width  = 0;
            lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            lp.setMargins(padding, padding, padding, padding);
            cell.setLayoutParams(lp);

            final boolean lockedFinal = locked;
            cell.setOnClickListener(view -> {
                if (!canToggle) {
                    Toast.makeText(requireContext(),
                        "🔐 هذه الصلاحية للمدير فقط", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (busyMonth != -1) return;
                confirmToggle(month, lockedFinal);
            });

            monthsGrid.addView(cell);
        }
    }

    // ═════════════════════════════════════════════════════════
    //  تأكيد العملية
    // ═════════════════════════════════════════════════════════
    private void confirmToggle(int month, boolean currentlyLocked) {
        String action  = currentlyLocked ? "unlock" : "lock";
        String verbAr  = currentlyLocked ? "فتح" : "قفل";
        String monthAr = MONTHS_AR[month - 1];

        String msg = currentlyLocked
            ? "⚠️ سيسمح هذا لجميع المستخدمين بالتعديل على قيود شهر "
              + monthAr + " " + currentYear + "."
            : "🔒 لن يتمكن أي مستخدم (حتى المدير) من إضافة أو تعديل قيود "
              + "شهر " + monthAr + " " + currentYear + ".";

        new AlertDialog.Builder(requireContext())
            .setTitle("تأكيد " + verbAr + " الفترة")
            .setMessage(msg)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton(verbAr, (d, w) -> doToggle(month, action))
            .show();
    }

    // ═════════════════════════════════════════════════════════
    //  تنفيذ طلب القفل/الفتح
    // ═════════════════════════════════════════════════════════
    private void doToggle(int month, String action) {
        busyMonth = month;
        renderMonths();
        progressBar.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("year",   currentYear);
                payload.put("month",  month);
                payload.put("action", action);

                HttpResult r = httpPost(BASE_URL + "/accounting/lock-period",
                                        payload.toString());

                mainHandler.post(() -> {
                    busyMonth = -1;
                    progressBar.setVisibility(View.GONE);
                    renderMonths();

                    if (r.code == 200 && r.body != null) {
                        try {
                            JSONObject j = new JSONObject(r.body);
                            if (j.optBoolean("success", false)) {
                                statusMap.put(currentYear + "-" + month,
                                    "lock".equals(action) ? "locked" : "unlocked");
                                renderMonths();
                                Toast.makeText(requireContext(),
                                    j.optString("message",
                                        "تم بنجاح"), Toast.LENGTH_SHORT).show();
                                return;
                            }
                            Toast.makeText(requireContext(),
                                j.optString("message", "فشل"), Toast.LENGTH_LONG).show();
                            return;
                        } catch (Exception ignored) { }
                    }
                    Toast.makeText(requireContext(),
                        "فشل الاتصال بالسيرفر (code " + r.code + ")",
                        Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "doToggle error", e);
                mainHandler.post(() -> {
                    busyMonth = -1;
                    progressBar.setVisibility(View.GONE);
                    renderMonths();
                    Toast.makeText(requireContext(),
                        "خطأ: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ═════════════════════════════════════════════════════════
    //  جلب الحالة
    // ═════════════════════════════════════════════════════════
    private void fetchPeriods() {
        progressBar.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            try {
                HttpResult r = httpGet(BASE_URL + "/accounting/locked-periods");
                final Map<String, String> newMap = new HashMap<>();

                if (r.code == 200 && r.body != null) {
                    JSONObject j = new JSONObject(r.body);
                    JSONArray arr = j.optJSONArray("data");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            String key = o.getInt("year") + "-" + o.getInt("month");
                            newMap.put(key, o.optString("status", "unlocked"));
                        }
                    }
                }

                mainHandler.post(() -> {
                    statusMap.clear();
                    statusMap.putAll(newMap);
                    progressBar.setVisibility(View.GONE);
                    renderMonths();
                });
            } catch (Exception e) {
                Log.e(TAG, "fetchPeriods error", e);
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(requireContext(),
                        "تعذر جلب الحالة: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ═════════════════════════════════════════════════════════
    //  HTTP helpers
    // ═════════════════════════════════════════════════════════
    private static class HttpResult {
        int code;
        String body;
        HttpResult(int c, String b) { code = c; body = b; }
    }

    private String authToken() {
        if (sessionManager == null) return null;
        try {
            // ⚠️ عدّل اسم الدالة حسب SessionManager عندك
            return sessionManager.getToken();
        } catch (Exception ignored) { return null; }
    }

    private HttpResult httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json");
        String token = authToken();
        if (!TextUtils.isEmpty(token)) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        int code = c.getResponseCode();
        BufferedReader br = new BufferedReader(new InputStreamReader(
            code >= 400 ? c.getErrorStream() : c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();
        return new HttpResult(code, sb.toString());
    }

    private HttpResult httpPost(String urlStr, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("Accept", "application/json");
        String token = authToken();
        if (!TextUtils.isEmpty(token)) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        OutputStream os = c.getOutputStream();
        os.write(json.getBytes("UTF-8"));
        os.close();

        int code = c.getResponseCode();
        BufferedReader br = new BufferedReader(new InputStreamReader(
            code >= 400 ? c.getErrorStream() : c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();
        return new HttpResult(code, sb.toString());
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v,
            getResources().getDisplayMetrics());
    }
}
