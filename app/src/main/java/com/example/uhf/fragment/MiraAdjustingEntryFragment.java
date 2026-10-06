package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.adapter.AdjustingEntryListAdapter;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.api.dto.JournalEntryDto;
import com.example.uhf.dialog.AddBalanceBottomSheet;
import com.example.uhf.utils.NumberFormatter;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ✅ شاشة قيود التسويات — عرض القيد الافتتاحي + تسويات إضافية
 * 
 * الميزات:
 * - عرض جميع قيود source_type IN ('opening', 'adjusting', 'inventory_add')
 * - FAB لإضافة رصيد جديد (BottomSheet)
 * - زر لإنشاء تسوية مخصصة (متعددة السطور)
 * - النقر على قيد → تفاصيل القيد
 */
public class MiraAdjustingEntryFragment extends Fragment {

    private static final String TAG = "MiraAdjustingEntry";

    // Views
    private RecyclerView rvEntries;
    private LinearLayout emptyState;
    private ProgressBar pbLoading;
    private TextView tvTotalEntries, tvTotalValue;
    private MaterialButton btnCreateCustomEntry;
    private ExtendedFloatingActionButton fabAddBalance;

    // State
    private AdjustingEntryListAdapter adapter;
    private final List<JournalEntryDto> entries = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isArabic = true;
    private MiraApiClient apiClient;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_mira_adjusting,
            container, false);

        isArabic = "ar".equals(Locale.getDefault().getLanguage());
        apiClient = MiraApiClient.getInstance(requireContext().getApplicationContext());

        initViews(v);
        setupRecycler();
        setupListeners();

        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadEntries();
    }

    private void initViews(View v) {
        rvEntries            = v.findViewById(R.id.rvEntries);
        emptyState           = v.findViewById(R.id.emptyState);
        pbLoading            = v.findViewById(R.id.pbLoading);
        tvTotalEntries       = v.findViewById(R.id.tvTotalEntries);
        tvTotalValue         = v.findViewById(R.id.tvTotalValue);
        btnCreateCustomEntry = v.findViewById(R.id.btnCreateCustomEntry);
        fabAddBalance        = v.findViewById(R.id.fabAddBalance);
    }

    private void setupRecycler() {
        adapter = new AdjustingEntryListAdapter(entry -> {
            // فتح تفاصيل القيد
            if (entry.id > 0) {
                MiraJournalEntryDetailsFragment details =
                    MiraJournalEntryDetailsFragment.newInstance(entry.id);

                if (getActivity() instanceof com.example.uhf.activity.UHFMainActivity) {
                    ((com.example.uhf.activity.UHFMainActivity) getActivity())
                        .loadFragment(details, "JournalEntryDetails");
                }
            }
        });

        rvEntries.setLayoutManager(new LinearLayoutManager(getContext()));
        rvEntries.setAdapter(adapter);
    }

    private void setupListeners() {
        // FAB: إضافة رصيد
        fabAddBalance.setOnClickListener(v -> openAddBalanceSheet());

        // زر: تسوية مخصصة
        btnCreateCustomEntry.setOnClickListener(v -> {
    MiraCustomAdjustingFragment fragment = new MiraCustomAdjustingFragment();

    if (getActivity() instanceof com.example.uhf.activity.UHFMainActivity) {
        ((com.example.uhf.activity.UHFMainActivity) getActivity())
            .loadFragment(fragment, "CustomAdjusting");
    } else {
        requireActivity().getSupportFragmentManager()
            .beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack("CustomAdjusting")
            .commit();
    }
});
    }

    private void openAddBalanceSheet() {
        AddBalanceBottomSheet sheet = new AddBalanceBottomSheet();
        sheet.setListener(entryNumber -> {
            // إعادة تحميل القائمة بعد الحفظ
            loadEntries();
        });
        sheet.show(getParentFragmentManager(), "AddBalanceBottomSheet");
    }

    // ═══════════════════════════════════════════════════════════
    //  تحميل البيانات
    // ═══════════════════════════════════════════════════════════

    private void loadEntries() {
        pbLoading.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                // ✅ استدعِ القيود مع فلتر source_type
                // نستخدم /journal بدون فلتر ثم نُصفّي محلياً
                // (يمكن لاحقاً إضافة parameter في الخادم)

                JSONObject params = new JSONObject();
                params.put("per_page", 100);

                MiraApiClient.ApiResponse response =
                    apiClient.getJournalEntries(null);

                mainHandler.post(() -> {
                    pbLoading.setVisibility(View.GONE);

                    if (response != null && response.isSuccess && response.data != null) {
                        parseAndDisplay(response.data);
                    } else {
                        String err = response != null && response.error != null
                            ? response.error : "فشل تحميل القيود";
                        Toast.makeText(requireContext(),
                            "❌ " + err, Toast.LENGTH_SHORT).show();
                        showEmpty();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "loadEntries error: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    pbLoading.setVisibility(View.GONE);
                    showEmpty();
                });
            }
        }).start();
    }

    private void parseAndDisplay(JSONObject responseData) {
        try {
            JSONArray arr = responseData.optJSONArray("data");
            if (arr == null) arr = responseData.optJSONArray("entries");

            if (arr == null || arr.length() == 0) {
                showEmpty();
                return;
            }

            List<JournalEntryDto> allEntries = new ArrayList<>();
            double totalValue = 0;

            Gson gson = new Gson();
            for (int i = 0; i < arr.length(); i++) {
                try {
                    JournalEntryDto entry = gson.fromJson(
                        arr.getJSONObject(i).toString(),
                        JournalEntryDto.class);

                    if (entry == null) continue;

                    // ✅ فلترة: فقط opening + adjusting + inventory_add
                    String st = entry.sourceType != null
                        ? entry.sourceType.toLowerCase() : "";

                    if (st.equals("opening")
                        || st.equals("adjusting")
                        || st.equals("inventory_add")) {

                        allEntries.add(entry);
                        totalValue += entry.totalDebit;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "parse entry error: " + e.getMessage());
                }
            }

            entries.clear();
            entries.addAll(allEntries);

            // تحديث الإحصائيات
            tvTotalEntries.setText(String.valueOf(entries.size()));
            tvTotalValue.setText(NumberFormatter.moneyShort(totalValue));

            if (entries.isEmpty()) {
                showEmpty();
            } else {
                showData();
                adapter.setItems(entries);
            }

        } catch (Exception e) {
            Log.e(TAG, "parseAndDisplay error: " + e.getMessage(), e);
            showEmpty();
        }
    }

    private void showEmpty() {
        emptyState.setVisibility(View.VISIBLE);
        rvEntries.setVisibility(View.GONE);
    }

    private void showData() {
        emptyState.setVisibility(View.GONE);
        rvEntries.setVisibility(View.VISIBLE);
    }
}
