package com.example.uhf.fragment;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MiraPeriodLockFragment extends Fragment {

    private static final String[] MONTHS_AR = {
        "يناير","فبراير","مارس","أبريل","مايو","يونيو",
        "يوليو","أغسطس","سبتمبر","أكتوبر","نوفمبر","ديسمبر"
    };
    private static final String[] MONTHS_EN = {
        "Jan","Feb","Mar","Apr","May","Jun",
        "Jul","Aug","Sep","Oct","Nov","Dec"
    };

    private LinearLayout yearBar;
    private GridLayout monthsGrid;
    private ProgressBar pbLoading;

    private int currentYear;
    private boolean isArabic = true;
    private final Map<String, String> statusMap = new HashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isArabic    = "ar".equals(Locale.getDefault().getLanguage());
        currentYear = Calendar.getInstance().get(Calendar.YEAR);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_mira_period_lock, container, false);

        yearBar    = v.findViewById(R.id.yearBar);
        monthsGrid = v.findViewById(R.id.monthsGrid);
        pbLoading  = v.findViewById(R.id.pbLoading);

        buildYearSelector();
        fetchPeriods();

        return v;
    }

    // ═════════════════════════════════════════════════════════
    //  منتقي السنة
    // ═════════════════════════════════════════════════════════
    private void buildYearSelector() {
        yearBar.removeAllViews();
        int cy = Calendar.getInstance().get(Calendar.YEAR);
        int[] years = { cy, cy - 1, cy - 2 };

        for (int y : years) {
            MaterialButton btn = new MaterialButton(
                requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btn.setText(String.valueOf(y));
            btn.setInsetTop(0);
            btn.setInsetBottom(0);
            btn.setMinWidth(0);
            btn.setMinHeight(0);
            btn.setMinimumWidth(0);
            btn.setMinimumHeight(0);
            btn.setPadding(dp(22), 0, dp(22), 0);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
            lp.setMargins(dp(4), 0, dp(4), 0);
            btn.setLayoutParams(lp);

            if (y == currentYear) {
                btn.setBackgroundTintList(
                    ColorStateList.valueOf(Color.parseColor("#1E3A8A")));
                btn.setTextColor(Color.WHITE);
                btn.setStrokeWidth(0);
            }

            final int yy = y;
            btn.setOnClickListener(view -> {
                if (currentYear == yy) return;
                currentYear = yy;
                buildYearSelector();
                renderMonths();
            });

            yearBar.addView(btn);
        }
    }

    // ═════════════════════════════════════════════════════════
    //  جلب الحالة
    // ═════════════════════════════════════════════════════════
    private void fetchPeriods() {
        pbLoading.setVisibility(View.VISIBLE);
        monthsGrid.removeAllViews();

        new Thread(() -> {
            MiraApiClient.ApiResponse response = null;
            try {
                MiraApiClient client = MiraApiClient.getInstance(requireContext());
                response = client.getLockedPeriods();
            } catch (Exception e) {
                e.printStackTrace();
            }
            final MiraApiClient.ApiResponse r = response;

            mainHandler.post(() -> {
                if (!isAdded()) return;
                pbLoading.setVisibility(View.GONE);
                statusMap.clear();

                if (r != null && r.isSuccess && r.data != null) {
                    JSONArray arr = r.data.optJSONArray("data");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            try {
                                JSONObject o = arr.getJSONObject(i);
                                statusMap.put(o.optInt("year") + "-" + o.optInt("month"),
                                              o.optString("status", "unlocked"));
                            } catch (Exception ignored) {}
                        }
                    }
                    renderMonths();
                } else {
                    renderMonths();
                    String err = (r != null && r.error != null)
                            ? r.error : "فشل جلب الفترات";
                    Toast.makeText(requireContext(), "❌ " + err, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    // ═════════════════════════════════════════════════════════
    //  رسم شبكة الشهور (MaterialCardView بنمط الإقفال السنوي)
    // ═════════════════════════════════════════════════════════
    private void renderMonths() {
        monthsGrid.removeAllViews();

        int onSurface = resolveAttr(com.google.android.material.R.attr.colorOnSurface);
        int onSurfaceVariant = resolveAttr(
            com.google.android.material.R.attr.colorOnSurfaceVariant);

        for (int m = 1; m <= 12; m++) {
            final int month = m;
            String status = statusMap.get(currentYear + "-" + m);
            boolean locked = "locked".equals(status);

            // === البطاقة ===
            MaterialCardView card = new MaterialCardView(requireContext());
            card.setRadius(dp(12));
            card.setCardElevation(dp(2));
            card.setStrokeWidth(dp(2));
            card.setStrokeColor(Color.parseColor(locked ? "#EF4444" : "#22C55E"));
            card.setCardBackgroundColor(
                Color.parseColor(locked ? "#FEE2E2" : "#DCFCE7"));
            card.setClickable(true);
            card.setFocusable(true);

            // === المحتوى ===
            LinearLayout inner = new LinearLayout(requireContext());
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            inner.setPadding(dp(16), dp(20), dp(16), dp(20));

            TextView nameTv = new TextView(requireContext());
            nameTv.setText(isArabic ? MONTHS_AR[m - 1] : MONTHS_EN[m - 1]);
            nameTv.setTextSize(16f);
            nameTv.setTypeface(null, Typeface.BOLD);
            nameTv.setTextColor(onSurface);
            nameTv.setGravity(Gravity.CENTER);

            TextView statusTv = new TextView(requireContext());
            statusTv.setTextSize(12f);
            statusTv.setGravity(Gravity.CENTER);
            statusTv.setPadding(0, dp(6), 0, 0);
            statusTv.setTextColor(locked
                ? Color.parseColor("#B91C1C")
                : Color.parseColor("#15803D"));
            statusTv.setText(locked
                ? "🔒 " + (isArabic ? "مقفل" : "Locked")
                : "🔓 " + (isArabic ? "مفتوح" : "Open"));

            inner.addView(nameTv);
            inner.addView(statusTv);
            card.addView(inner);

            // === قيود الشبكة ===
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width  = 0;
            lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            card.setLayoutParams(lp);

            final boolean lockedFinal = locked;
            card.setOnClickListener(view -> confirmToggle(month, lockedFinal));

            monthsGrid.addView(card);
        }
    }

    // ═════════════════════════════════════════════════════════
    //  تأكيد
    // ═════════════════════════════════════════════════════════
    private void confirmToggle(int month, boolean currentlyLocked) {
        String action  = currentlyLocked ? "unlock" : "lock";
        String verb    = currentlyLocked
                ? (isArabic ? "فتح" : "Unlock")
                : (isArabic ? "قفل" : "Lock");
        String monthNm = isArabic ? MONTHS_AR[month - 1] : MONTHS_EN[month - 1];

        String msg = currentlyLocked
            ? "⚠️ سيسمح هذا لجميع المستخدمين بالتعديل على قيود شهر "
                + monthNm + " " + currentYear + "."
            : "🔒 لن يتمكن أي مستخدم (حتى المدير) من إضافة أو تعديل قيود شهر "
                + monthNm + " " + currentYear + ".";

        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("⚠️ تأكيد " + verb + " الفترة")
            .setMessage(msg)
            .setPositiveButton(verb, (dialog, which) -> doToggle(month, action))
            .setNegativeButton("إلغاء", null)
            .show();
    }

    // ═════════════════════════════════════════════════════════
    //  التنفيذ
    // ═════════════════════════════════════════════════════════
    private void doToggle(int month, String action) {
        pbLoading.setVisibility(View.VISIBLE);
        monthsGrid.removeAllViews();

        new Thread(() -> {
            MiraApiClient.ApiResponse response = null;
            try {
                MiraApiClient client = MiraApiClient.getInstance(requireContext());
                JSONObject payload = new JSONObject();
                payload.put("year",   currentYear);
                payload.put("month",  month);
                payload.put("action", action);
                response = client.togglePeriodLock(payload);
            } catch (Exception e) {
                e.printStackTrace();
            }
            final MiraApiClient.ApiResponse r = response;

            mainHandler.post(() -> {
                if (!isAdded()) return;
                pbLoading.setVisibility(View.GONE);

                if (r != null && r.isSuccess) {
                    String msg = (r.data != null)
                            ? r.data.optString("message", "") : "";
                    if (msg.isEmpty()) {
                        msg = "lock".equals(action)
                                ? "تم قفل الفترة" : "تم فتح الفترة";
                    }
                    Toast.makeText(requireContext(), "✅ " + msg, Toast.LENGTH_SHORT).show();
                    fetchPeriods();
                } else {
                    String err = (r != null && r.error != null)
                            ? r.error : "فشل العملية";
                    Toast.makeText(requireContext(), "❌ " + err, Toast.LENGTH_LONG).show();
                    renderMonths();
                }
            });
        }).start();
    }

    // ═════════════════════════════════════════════════════════
    //  أدوات
    // ═════════════════════════════════════════════════════════
    private int dp(int v) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v,
            getResources().getDisplayMetrics());
    }

    private int resolveAttr(int attr) {
        TypedValue tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }
}
