package com.example.uhf.fragment;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.engine.SmartScaleConnector;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * UHF Read Tag Fragment - المسح والتحقق من RFID
 * 
 * المميزات:
 * - مسح RFID UHF + كاميرا GS1
 * - تحقق ثلاثي: RFID + GS1 + ميزان
 * - ربط بالفرع النشط
 * - بطاقة نتيجة احترافية
 * - دعم عربي/إنجليزي كامل
 */
public class UHFReadTagFragment extends Fragment {

    private static final String TAG = "UHFReadTagFragment";

    // ==================== UI Elements (مطابقة للـ XML) ====================
    private View cardMiraResult;
    private TextView tvProductName, tvHeaderStatus, tvOnlineStatus;
    private TextView tvPurityVal, tvWeightVal, tvValueVal;
    private TextView tvSerialVal, tvStatusBadge;
    private EditText etGtinInput;
    private Button btnScan;

    // ==================== Managers ====================
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private SmartScaleConnector scaleConnector;
    private double liveScaleWeight = 0.0;
    private boolean isArabic;

    // ==================== Lifecycle ====================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.uhf_readtag_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Context ctx = getContext();
        if (ctx != null) {
            apiClient = MiraApiClient.getInstance(ctx);
            branchesManager = MiraBranchesManager.getInstance(ctx);
            orgProfile = MiraOrgProfileManager.getInstance(ctx);
        }
        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");

        // ربط العناصر - مطابقة للـ XML
        cardMiraResult = view.findViewById(R.id.cardMiraResult);
        tvProductName = view.findViewById(R.id.tvProductName);
        tvHeaderStatus = view.findViewById(R.id.tvHeaderStatus);
        tvOnlineStatus = view.findViewById(R.id.tvOnlineStatus);
        tvPurityVal = view.findViewById(R.id.tvPurityVal);
        tvWeightVal = view.findViewById(R.id.tvWeightVal);
        tvValueVal = view.findViewById(R.id.tvValueVal);
        tvSerialVal = view.findViewById(R.id.tvSerialVal);
        tvStatusBadge = view.findViewById(R.id.tvStatusBadge);
        etGtinInput = view.findViewById(R.id.etGtinInput);
        btnScan = view.findViewById(R.id.btnScan);

        // تطبيق اللغة والهوية
        applyLanguage();
        updateOnlineStatus(true);

        // تهيئة الميزان
        initSmartScale();

        // زر المسح
        if (btnScan != null) {
            btnScan.setOnClickListener(v -> {
                String code = etGtinInput != null ? etGtinInput.getText().toString().trim() : "";
                if (!code.isEmpty()) {
                    handleScannedTag(code);
                } else {
                    Toast.makeText(getContext(),
                        isArabic ? "أدخل رمز EPC أو GTIN" : "Enter EPC or GTIN code",
                        Toast.LENGTH_SHORT).show();
                }
            });
        }

        // عرض أولي
        showInitialState();
    }

    // ==================== Language ====================

    private void applyLanguage() {
        if (btnScan != null) btnScan.setText(isArabic ? "مسح" : "SCAN");
        if (etGtinInput != null) {
            etGtinInput.setHint(isArabic ? "أدخل EPC / GTIN..." : "Enter EPC / GTIN...");
        }
    }

    private void updateOnlineStatus(boolean online) {
        if (tvOnlineStatus != null) {
            tvOnlineStatus.setText(online ? "● ONLINE" : "● OFFLINE");
            tvOnlineStatus.setTextColor(online ? Color.parseColor("#22C55E") : Color.parseColor("#EF4444"));
        }
    }

    private void showInitialState() {
        if (cardMiraResult != null) cardMiraResult.setVisibility(View.VISIBLE);
        if (tvProductName != null) tvProductName.setText(isArabic ? "جاهز للمسح..." : "Ready to scan...");
        if (tvHeaderStatus != null) {
            tvHeaderStatus.setText(isArabic ? "📡 في انتظار المسح" : "📡 Waiting for scan");
            tvHeaderStatus.setTextColor(Color.parseColor("#38BDF8"));
        }
        if (tvPurityVal != null) tvPurityVal.setText("—");
        if (tvWeightVal != null) tvWeightVal.setText("—");
        if (tvValueVal != null) tvValueVal.setText("—");
        if (tvSerialVal != null) tvSerialVal.setText("—");
        if (tvStatusBadge != null) {
            tvStatusBadge.setText(isArabic ? "جاهز" : "READY");
            tvStatusBadge.setTextColor(Color.parseColor("#38BDF8"));
        }
    }

    // ==================== Smart Scale ====================

    private void initSmartScale() {
        scaleConnector = new SmartScaleConnector();
        scaleConnector.setListener(new SmartScaleConnector.ScaleListener() {
            @Override public void onWeightReceived(double w) {
                liveScaleWeight = w;
                Log.d(TAG, "Scale: " + w + "g");
            }
            @Override public void onScaleConnected(String n) {
                safeRun(() -> Toast.makeText(getContext(),
                    (isArabic ? "⚖️ تم توصيل الميزان: " : "⚖️ Scale connected: ") + n,
                    Toast.LENGTH_SHORT).show());
            }
            @Override public void onScaleDisconnected() { liveScaleWeight = 0.0; }
            @Override public void onScaleError(String e) { Log.e(TAG, "Scale error: " + e); }
        });
    }

    // ==================== Tag Processing ====================

    /**
     * نقطة المعالجة الموحدة للوسم
     */
    private void handleScannedTag(String epc) {
        // تمرير إلى POS Mode إذا كان نشطاً
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).onTagRead(epc);
        }
        // استعلام MIRA API
        queryMiraItem(epc);
    }

    private void queryMiraItem(String epc) {
        if (cardMiraResult != null) cardMiraResult.setVisibility(View.VISIBLE);
        if (tvHeaderStatus != null) {
            tvHeaderStatus.setText(isArabic ? "🔍 جاري الاستعلام..." : "🔍 Querying...");
            tvHeaderStatus.setTextColor(Color.parseColor("#F59E0B"));
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject extraParams = new JSONObject();
                extraParams.put("branch_id", branchesManager.getActiveBranchId());
                if (liveScaleWeight > 0) extraParams.put("scale_weight", liveScaleWeight);

                MiraApiClient.ApiResponse response = apiClient.authorize(epc, "scan", extraParams);

                if (response != null && response.isSuccess && response.data != null) {
                    safeRun(() -> updateUIWithMiraResponse(response.data, epc));
                } else {
                    safeRun(() -> showErrorResult(isArabic ? "قطعة غير مسجلة" : "Item not found"));
                }
            } catch (Exception e) {
                Log.e(TAG, "Query error: " + e.getMessage());
                safeRun(() -> showErrorResult(isArabic ? "خطأ في الاتصال" : "Connection error"));
            }
        });
    }

    // ==================== UI Updates ====================

    private void updateUIWithMiraResponse(JSONObject data, String fallbackEpc) {
        try {
            JSONObject decision = data.optJSONObject("decision");
            JSONObject item = data.optJSONObject("item");
            JSONObject barcode = data.optJSONObject("barcode");

            boolean allowed = decision != null && decision.optBoolean("allowed", false);
            String title = item != null ? item.optString("title", isArabic ? "قطعة" : "Item") : (isArabic ? "قطعة" : "Item");
            String karat = item != null ? item.optString("karat", "—") : "—";
            double weight = item != null ? item.optDouble("weight", 0) : 0;
            double price = item != null ? item.optDouble("price", item.optDouble("retail_price", 0)) : 0;
            String status = item != null ? item.optString("status", "") : "";
            String serial = fallbackEpc;
            if (barcode != null && !barcode.optString("serial_no", "").isEmpty()) {
                serial = barcode.optString("serial_no");
            }

            // تحديث العناصر
            if (tvProductName != null) tvProductName.setText(title);
            if (tvPurityVal != null) tvPurityVal.setText(karat);
            if (tvWeightVal != null) {
                if (liveScaleWeight > 0 && weight > 0) {
                    tvWeightVal.setText(String.format(Locale.US, "%.2f / %.2fg", weight, liveScaleWeight));
                } else {
                    tvWeightVal.setText(weight > 0 ? String.format(Locale.US, "%.2fg", weight) : "—");
                }
            }
            if (tvValueVal != null) {
                tvValueVal.setText(price > 0 ? String.format(Locale.US, "%,.0f YER", price) : "—");
            }
            if (tvSerialVal != null) tvSerialVal.setText(serial);

            // الحالة
            boolean weightMatch = liveScaleWeight > 0 && weight > 0 &&
                Math.abs(liveScaleWeight - weight) <= 0.05;

            if (tvHeaderStatus != null && tvStatusBadge != null) {
                if (allowed && item != null) {
                    if (weightMatch) {
                        tvHeaderStatus.setText(isArabic ? "✅✅✅ مطابقة تامة" : "✅✅✅ Tri-Verified");
                        tvHeaderStatus.setTextColor(Color.parseColor("#4ADE80"));
                        tvStatusBadge.setText(isArabic ? "مطابق" : "VERIFIED");
                        tvStatusBadge.setTextColor(Color.parseColor("#4ADE80"));
                    } else {
                        tvHeaderStatus.setText(isArabic ? "✅✅ تم التحقق" : "✅✅ Verified");
                        tvHeaderStatus.setTextColor(Color.parseColor("#38BDF8"));
                        tvStatusBadge.setText(isArabic ? "تم التحقق" : "VERIFIED");
                        tvStatusBadge.setTextColor(Color.parseColor("#38BDF8"));
                    }
                } else if (!weightMatch) {
                    tvHeaderStatus.setText(isArabic ? "🚨 عدم تطابق الوزن!" : "🚨 Weight Mismatch!");
                    tvHeaderStatus.setTextColor(Color.parseColor("#EF4444"));
                    tvStatusBadge.setText(isArabic ? "تنبيه وزن" : "WEIGHT ALERT");
                    tvStatusBadge.setTextColor(Color.parseColor("#EF4444"));
                } else {
                    tvHeaderStatus.setText(isArabic ? "🚨 غير مصرح" : "🚨 Unauthorized");
                    tvHeaderStatus.setTextColor(Color.parseColor("#EF4444"));
                    tvStatusBadge.setText(status.toUpperCase());
                    tvStatusBadge.setTextColor(Color.parseColor("#EF4444"));
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "UI update error: " + e.getMessage());
            showErrorResult(isArabic ? "خطأ في البيانات" : "Data error");
        }
    }

    private void showErrorResult(String message) {
        if (tvHeaderStatus != null) {
            tvHeaderStatus.setText(message);
            tvHeaderStatus.setTextColor(Color.parseColor("#EF4444"));
        }
        if (tvStatusBadge != null) {
            tvStatusBadge.setText(isArabic ? "خطأ" : "ERROR");
            tvStatusBadge.setTextColor(Color.parseColor("#EF4444"));
        }
        updateOnlineStatus(false);
    }

    // ==================== Helpers ====================

    private void safeRun(Runnable r) {
        if (getActivity() != null) getActivity().runOnUiThread(r);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (scaleConnector != null) scaleConnector.disconnect();
    }
}
