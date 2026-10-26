package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MiraFixedAssetsFragment extends Fragment {

    private RecyclerView rvAssets;
    private ExtendedFloatingActionButton fabAdd;
    private MaterialButton btnRunDepreciation;
    private ProgressBar pbLoading;

    private AssetAdapter adapter;
    private List<JSONObject> assetList = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // فئات الأصول والحسابات المرتبطة بها (من شجرة الحسابات)
    private static final String[] CATEGORIES = {"أثاث وتجهيزات", "سيارات"};
    private static final int[] ASSET_ACC = {82, 90}; // فترينات العرض، سيارات التوزيع
    private static final int[] DEPR_ACC = {98, 99};  // مجمع إهلاك الأثاث، مجمع إهلاك السيارات
    private static final int[] EXP_ACC = {251, 252}; // إهلاك الأثاث، إهلاك السيارات

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_mira_fixed_assets, container, false);

        rvAssets = v.findViewById(R.id.rvAssets);
        fabAdd = v.findViewById(R.id.fabAddAsset);
        btnRunDepreciation = v.findViewById(R.id.btnRunDepreciation);
        pbLoading = v.findViewById(R.id.pbLoading);

        rvAssets.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new AssetAdapter();
        rvAssets.setAdapter(adapter);

        fabAdd.setOnClickListener(view -> showAddAssetDialog());
        btnRunDepreciation.setOnClickListener(view -> runDepreciation());

        fetchAssets();
        return v;
    }

    private void fetchAssets() {
        pbLoading.setVisibility(View.VISIBLE);
        new Thread(() -> {
            MiraApiClient.ApiResponse res = MiraApiClient.getInstance(requireContext()).getFixedAssets();
            mainHandler.post(() -> {
                pbLoading.setVisibility(View.GONE);
                if (res != null && res.isSuccess && res.data != null) {
                    JSONArray arr = res.data.optJSONArray("data");
                    assetList.clear();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            try { assetList.add(arr.getJSONObject(i)); } catch (Exception ignored) {}
                        }
                    }
                    adapter.notifyDataSetChanged();
                }
            });
        }).start();
    }

    private void showAddAssetDialog() {
        View v = LayoutInflater.from(getContext()).inflate(R.layout.dialog_add_asset, null);
        TextInputEditText etName = v.findViewById(R.id.etAssetName);
        TextInputEditText etCode = v.findViewById(R.id.etAssetCode);
        TextInputEditText etCost = v.findViewById(R.id.etCost);
        TextInputEditText etLife = v.findViewById(R.id.etLife);
        MaterialAutoCompleteTextView spinnerCat = v.findViewById(R.id.spinnerCategory);

        spinnerCat.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, CATEGORIES));
        // أضف هذا السطر بعد إعداد الـ Adapter
        spinnerCat.setOnClickListener(v -> spinnerCat.showDropDown());
        spinnerCat.setText(CATEGORIES[0], false);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("إضافة أصل جديد")
                .setView(v)
                .setPositiveButton("حفظ", (dialog, which) -> {
                    try {
                        int catIdx = 0;
                        for (int i = 0; i < CATEGORIES.length; i++) {
                            if (CATEGORIES[i].equals(spinnerCat.getText().toString())) catIdx = i;
                        }

                        JSONObject payload = new JSONObject();
                        payload.put("asset_name", etName.getText().toString());
                        payload.put("asset_code", etCode.getText().toString());
                        payload.put("purchase_cost", Double.parseDouble(etCost.getText().toString()));
                        payload.put("useful_life_years", Integer.parseInt(etLife.getText().toString()));
                        payload.put("purchase_date", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().getTime()));
                        payload.put("asset_category", CATEGORIES[catIdx]);
                        
                        payload.put("asset_account_id", ASSET_ACC[catIdx]);
                        payload.put("depreciation_account_id", DEPR_ACC[catIdx]);
                        payload.put("expense_account_id", EXP_ACC[catIdx]);

                        saveAsset(payload);
                    } catch (Exception e) {
                        Toast.makeText(getContext(), "تأكد من إدخال الأرقام بشكل صحيح", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void saveAsset(JSONObject payload) {
        pbLoading.setVisibility(View.VISIBLE);
        new Thread(() -> {
            MiraApiClient.ApiResponse res = MiraApiClient.getInstance(requireContext()).createFixedAsset(payload);
            mainHandler.post(() -> {
                pbLoading.setVisibility(View.GONE);
                if (res != null && res.isSuccess) {
                    Toast.makeText(getContext(), "✅ تم إضافة الأصل", Toast.LENGTH_SHORT).show();
                    fetchAssets();
                } else {
                    Toast.makeText(getContext(), "❌ فشل الحفظ", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void runDepreciation() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("تأكيد الإهلاك")
                .setMessage("سيقوم النظام بحساب إهلاك الشهر الحالي لجميع الأصول النشطة وترحيل القيد المحاسبي. هل أنت متأكد؟")
                .setPositiveButton("نعم، مرحّل", (dialog, which) -> {
                    pbLoading.setVisibility(View.VISIBLE);
                    new Thread(() -> {
                        try {
                            JSONObject payload = new JSONObject();
                            payload.put("date", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().getTime()));
                            MiraApiClient.ApiResponse res = MiraApiClient.getInstance(requireContext()).runDepreciation(payload);
                            mainHandler.post(() -> {
                                pbLoading.setVisibility(View.GONE);
                                if (res != null && res.isSuccess) {
                                    Toast.makeText(getContext(), res.data.optString("message", "تم"), Toast.LENGTH_LONG).show();
                                    fetchAssets();
                                } else {
                                    Toast.makeText(getContext(), "❌ فشل الإهلاك", Toast.LENGTH_SHORT).show();
                                }
                            });
                        } catch (Exception ignored) {}
                    }).start();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private class AssetAdapter extends RecyclerView.Adapter<AssetAdapter.VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_fixed_asset, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int position) {
            JSONObject a = assetList.get(position);
            h.tvName.setText(a.optString("asset_name"));
            h.tvCode.setText("كود: " + a.optString("asset_code"));
            h.tvCost.setText(String.format(Locale.US, "التكلفة: %,.0f", a.optDouble("purchase_cost")));
            h.tvBookValue.setText(String.format(Locale.US, "الدفترية: %,.0f", a.optDouble("current_book_value")));
        }
        @Override public int getItemCount() { return assetList.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvCode, tvCost, tvBookValue;
            public VH(@NonNull View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvAssetName);
                tvCode = itemView.findViewById(R.id.tvAssetCode);
                tvCost = itemView.findViewById(R.id.tvCost);
                tvBookValue = itemView.findViewById(R.id.tvBookValue);
            }
        }
    }
}
