package com.example.uhf.fragment;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;

import java.util.ArrayList;
import java.util.List;

public class InventoryFragment extends Fragment {

    private RecyclerView rvRecentTags;
    private TextView tvScannedCount, tvScannedWeight, tvLastTagEpc, tvLastTagDetails;
    private Button btnToggleScan, btnManualScan, btnClearScan, btnCommitSession;

    private ScannedTagsAdapter adapter;
    private final List<ScannedItemModel> scannedList = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_inventory, container, false);

        initViews(view);
        setupRecyclerView();
        setupClickListeners();

        return view;
    }

    private void initViews(View view) {
        rvRecentTags = view.findViewById(R.id.rvRecentTags);
        tvScannedCount = view.findViewById(R.id.tvScannedCount);
        tvScannedWeight = view.findViewById(R.id.tvScannedWeight);
        tvLastTagEpc = view.findViewById(R.id.tvLastTagEpc);
        tvLastTagDetails = view.findViewById(R.id.tvLastTagDetails);

        btnToggleScan = view.findViewById(R.id.btnToggleScan);
        btnManualScan = view.findViewById(R.id.btnManualScan);
        btnClearScan = view.findViewById(R.id.btnClearScan);
        btnCommitSession = view.findViewById(R.id.btnCommitSession);
    }

    private void setupRecyclerView() {
        if (getContext() != null) {
            adapter = new ScannedTagsAdapter();
            // استخدام مدير التخطيط الآمن لمنع الانهيار نهائياً
            rvRecentTags.setLayoutManager(new ScannedTagsAdapter.SafeLinearLayoutManager(getContext()));
            rvRecentTags.setAdapter(adapter);
        }
    }

    private void setupClickListeners() {
        // زر المسح اليدوي
        btnManualScan.setOnClickListener(v -> showManualInputDialog());

        // زر مسح الجلسة
        btnClearScan.setOnClickListener(v -> clearSession());

        // زر ترحيل الجرد
        btnCommitSession.setOnClickListener(v -> {
            if (getContext() != null) {
                Toast.makeText(getContext(), "تم ترحيل بيانات الجرد بنجاح", Toast.LENGTH_SHORT).show();
            }
        });

        // زر بدء قراءة RFID
        btnToggleScan.setOnClickListener(v -> {
            if (getContext() != null) {
                Toast.makeText(getContext(), "جاري تشغيل جهاز RFID...", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * دالة استلام القراءات (يمكن استدعاؤها مباشرة من كود الـ RFID لديك)
     */
    public synchronized void addOrUpdateTag(String epcInput, double weight) {
        if (epcInput == null || epcInput.trim().isEmpty()) return;

        final String cleanEpc = epcInput.trim().toUpperCase();

        try {
            int existingIndex = -1;
            for (int i = 0; i < scannedList.size(); i++) {
                if (scannedList.get(i).getEpc().equalsIgnoreCase(cleanEpc)) {
                    existingIndex = i;
                    break;
                }
            }

            if (existingIndex != -1) {
                // تحديث العداد للقطعة الموجودة
                ScannedItemModel existingItem = scannedList.get(existingIndex);
                existingItem.setReadCount(existingItem.getReadCount() + 1);
            } else {
                // إضافة قطعة جديدة في أول القائمة
                ScannedItemModel newItem = new ScannedItemModel(cleanEpc, weight, "مجوهرات", "مطابق", 1);
                scannedList.add(0, newItem);
            }

            // تحديث الواجهة بشكل آمن
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    adapter.updateData(scannedList);
                    tvLastTagEpc.setText(cleanEpc);
                    tvLastTagDetails.setText("الوزن: " + weight + " جم | الفئة: مجوهرات");
                    updateMetrics();
                });
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateMetrics() {
        tvScannedCount.setText(String.valueOf(scannedList.size()));
        double totalWeight = 0;
        for (ScannedItemModel item : scannedList) {
            totalWeight += item.getWeight();
        }
        tvScannedWeight.setText(String.format("%.2f جم", totalWeight));
    }

    private void clearSession() {
        scannedList.clear();
        adapter.updateData(scannedList);
        tvLastTagEpc.setText("---");
        tvLastTagDetails.setText("الوزن: - | العيار: - | الفئة: -");
        updateMetrics();
    }

    private void showManualInputDialog() {
        if (getContext() == null) return;

        final EditText input = new EditText(getContext());
        input.setHint("أدخل الـ EPC يدوياً");

        new AlertDialog.Builder(getContext())
                .setTitle("مسح يدوي")
                .setView(input)
                .setPositiveButton("إضافة", (dialog, which) -> {
                    String text = input.getText().toString();
                    if (!text.trim().isEmpty()) {
                        addOrUpdateTag(text, 4.5); // وزن تجريبي
                    }
                })
                .setNegativeButton("إلغاء", (dialog, which) -> dialog.dismiss())
                .show();
    }
}
