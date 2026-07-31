package com.mira.hardware.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.mira.hardware.R;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraDashboardFragment extends Fragment {

    // عناصر الواجهة
    private TextView tvGreeting, tvDate, tvTime;
    private TextView tvTotalItems, tvSoldToday, tvActiveGates;
    private TextView tvSystemStatus, tvLastSync;
    private View indicatorApi, indicatorDb, indicatorGate;
    private TextView tvRecent1, tvRecent2, tvRecent3;
    private View cardScan, cardGate, cardRadar, cardSettings;

    // الخيوط الموازية وإدارة الوقت
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler timeHandler = new Handler(Looper.getMainLooper());

    // إعدادات الـ API (يمكن جلبها من SharedPreferences)
    private static final String BASE_URL = "https://your-domain.com/wp-json/mira-gate/v1/";
    private static final String API_KEY = "YOUR_MIRA_API_KEY_HERE"; 

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_mira_dashboard, container, false);

        initViews(v);
        setupNavigationListeners();
        startTimeClock();
        fetchDashboardDataFromApi();

        return v;
    }

    private void initViews(View v) {
        tvGreeting = v.findViewById(R.id.tvGreeting);
        tvDate = v.findViewById(R.id.tvDate);
        tvTime = v.findViewById(R.id.tvTime);

        tvTotalItems = v.findViewById(R.id.tvTotalItems);
        tvSoldToday = v.findViewById(R.id.tvSoldToday);
        tvActiveGates = v.findViewById(R.id.tvActiveGates);

        tvSystemStatus = v.findViewById(R.id.tvSystemStatus);
        tvLastSync = v.findViewById(R.id.tvLastSync);

        indicatorApi = v.findViewById(R.id.indicatorApi);
        indicatorDb = v.findViewById(R.id.indicatorDb);
        indicatorGate = v.findViewById(R.id.indicatorGate);

        tvRecent1 = v.findViewById(R.id.tvRecent1);
        tvRecent2 = v.findViewById(R.id.tvRecent2);
        tvRecent3 = v.findViewById(R.id.tvRecent3);

        cardScan = v.findViewById(R.id.cardScan);
        cardGate = v.findViewById(R.id.cardGate);
        cardRadar = v.findViewById(R.id.cardRadar);
        cardSettings = v.findViewById(R.id.cardSettings);
    }

    /**
     * تفعيل التنقل المباشر عند النقر على بطاقات الوصول السريع
     */
    private void setupNavigationListeners() {
        if (cardScan != null) {
            cardScan.setOnClickListener(v -> navigateToFragment(new MiraScanFragment()));
        }
        if (cardGate != null) {
            cardGate.setOnClickListener(v -> navigateToFragment(new MiraGateFragment()));
        }
        if (cardRadar != null) {
            cardRadar.setOnClickListener(v -> navigateToFragment(new MiraRadarFragment()));
        }
        if (cardSettings != null) {
            cardSettings.setOnClickListener(v -> navigateToFragment(new MiraSettingsFragment()));
        }
    }

    private void navigateToFragment(Fragment targetFragment) {
        if (getActivity() != null) {
            getActivity().getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, targetFragment) // احرص على مطابقة المسمى مع Activity الرئيسي
                    .addToBackStack(null)
                    .commit();
        }
    }

    /**
     * تحديث الساعة والتاريخ بشكل حي
     */
    private void startTimeClock() {
        timeHandler.post(new Runnable() {
            @Override
            public void run() {
                SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE, d MMMM yyyy", new Locale("ar"));
                Date now = new Date();

                if (tvTime != null) tvTime.setText(timeFormat.format(now));
                if (tvDate != null) tvDate.setText(dateFormat.format(now));

                timeHandler.postDelayed(this, 1000);
            }
        });
    }

    /**
     * جلب البيانات من الـ REST API في الخلفية
     */
    private void fetchDashboardDataFromApi() {
        executor.execute(() -> {
            try {
                // طلب الإحصائيات من الـ API
                URL url = new URL(BASE_URL + "gates/stats");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("X-MIRA-API-Key", API_KEY);
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(5000);

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String inputLine;
                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }
                    in.close();

                    JSONObject jsonResponse = new JSONObject(response.toString());
                    if (jsonResponse.optBoolean("success")) {
                        JSONObject data = jsonResponse.getJSONObject("data");
                        int totalGates = data.optInt("total", 0);
                        int onlineGates = data.optInt("online", 0);

                        // تحديث الواجهة على الخيط الرئيسي
                        mainHandler.post(() -> {
                            tvActiveGates.setText(String.valueOf(onlineGates));
                            indicatorApi.setBackgroundColor(0xFF16A34A); // أخضر
                            indicatorDb.setBackgroundColor(0xFF16A34A);  // أخضر
                            tvLastSync.setText("تم التحديث: " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
                        });
                    }
                } else {
                    mainHandler.post(() -> {
                        indicatorApi.setBackgroundColor(0xFFDC2626); // أحمر
                        tvLastSync.setText("خطأ في الاتصال");
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> {
                    if (indicatorApi != null) indicatorApi.setBackgroundColor(0xFFDC2626);
                    if (tvLastSync != null) tvLastSync.setText("غير متصل");
                });
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        timeHandler.removeCallbacksAndMessages(null);
        executor.shutdown();
    }
}
