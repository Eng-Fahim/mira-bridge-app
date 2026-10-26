package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Step 2 — الفروع والخزائن (v3.0)
 *
 * هيكل جديد:
 *  1. المرحلة 2/10
 *  2. عرض الفروع الحالية (بـ id، code صحيح)
 *  3. زر "إدارة الفروع" (لإضافة/تعديل)
 *  4. إضافة الخزائن الفعلية
 *  5. إرسال payload صحيح: branches + vaults
 */
public class SetupStep2BranchesFragment extends BaseSetupStepFragment {

    private static final String TAG = "SetupStep2";

    // Views — الفروع
    private LinearLayout branchesContainer;
    private TextView tvBranchCount, tvBranchesEmpty;
    private MaterialButton btnManageBranches;

    // Views — الخزائن
    private LinearLayout vaultsContainer;
    private TextView tvVaultCount, tvVaultsEmpty;
    private MaterialButton btnAddVault;

    // Views — أزرار
    private MaterialButton btnBack, btnNext;
    private View pbLoading;

    // State
    private MiraApiClient apiClient;
    private boolean isArabic;
    private final List<JSONObject> branchesList = new ArrayList<>();
    private final List<VaultRow> vaultRows = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_setup_step2_branches_v3, container, false);
        apiClient = MiraApiClient.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        initViews(view);
        setupListeners();
        loadData();
        return view;
    }

    private void initViews(View v) {
        branchesContainer = v.findViewById(R.id.branchesContainer);
        tvBranchCount     = v.findViewById(R.id.tvBranchCount);
        tvBranchesEmpty   = v.findViewById(R.id.tvBranchesEmpty);
        btnManageBranches = v.findViewById(R.id.btnManageBranches);

        vaultsContainer   = v.findViewById(R.id.vaultsContainer);
        tvVaultCount      = v.findViewById(R.id.tvVaultCount);
        tvVaultsEmpty     = v.findViewById(R.id.tvVaultsEmpty);
        btnAddVault       = v.findViewById(R.id.btnAddVault);

        btnBack   = v.findViewById(R.id.btnBack);
        btnNext   = v.findViewById(R.id.btnNext);
        pbLoading = v.findViewById(R.id.pbLoading);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(x -> requireActivity().onBackPressed());
        btnNext.setOnClickListener(x -> saveAndNext());
        btnManageBranches.setOnClickListener(x -> openBranchManager());
        btnAddVault.setOnClickListener(x -> addVaultRow("", "main", "medium", ""));
    }

    private void openBranchManager() {
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity())
                    .loadFragment(new MiraBranchesManagementFragment(), "Branches");
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Load Data
    // ═══════════════════════════════════════════════════════

    private void loadData() {
        showLoading(true);
        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse resp = apiClient.getAccounting("setup/step/2/current");
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);

                    if (resp != null && resp.isSuccess && resp.data != null) {
                        JSONObject payload = resp.data.optJSONObject("data");
                        if (payload == null) payload = resp.data;

                        JSONArray branches = payload.optJSONArray("branches");
                        JSONArray vaults = payload.optJSONArray("vaults");

                        branchesList.clear();
                        if (branches != null) {
                            for (int i = 0; i < branches.length(); i++) {
                                JSONObject b = branches.optJSONObject(i);
                                if (b != null) branchesList.add(b);
                            }
                        }
                        renderBranches();

                        vaultsContainer.removeAllViews();
                        vaultRows.clear();
                        if (vaults != null && vaults.length() > 0) {
                            for (int i = 0; i < vaults.length(); i++) {
                                JSONObject vault = vaults.optJSONObject(i);
                                if (vault == null) continue;
                                addVaultRow(
                                        vault.optString("vault_code", ""),
                                        vault.optString("vault_type", "main"),
                                        vault.optString("security_level", "medium"),
                                        String.valueOf(vault.optInt("capacity_grams", 0))
                                );
                            }
                        } else {
                            // إضافة افتراضية
                            addVaultRow("VAULT-MAIN-01", "main", "maximum", "5000");
                        }
                    } else {
                        tvBranchesEmpty.setVisibility(View.VISIBLE);
                        tvVaultsEmpty.setVisibility(View.VISIBLE);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "loadData", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                });
            }
        }).start();
    }

    // ═══════════════════════════════════════════════════════
    //  Branches Render
    // ═══════════════════════════════════════════════════════

    private void renderBranches() {
        branchesContainer.removeAllViews();

        if (branchesList.isEmpty()) {
            tvBranchesEmpty.setVisibility(View.VISIBLE);
            branchesContainer.setVisibility(View.GONE);
            tvBranchCount.setText("0");
            return;
        }

        tvBranchesEmpty.setVisibility(View.GONE);
        branchesContainer.setVisibility(View.VISIBLE);
        tvBranchCount.setText(String.valueOf(branchesList.size()));

        LayoutInflater inf = LayoutInflater.from(requireContext());
        for (JSONObject b : branchesList) {
            View card = inf.inflate(R.layout.item_setup_branch_v3, branchesContainer, false);

            TextView tvName = card.findViewById(R.id.tvBranchName);
            TextView tvCode = card.findViewById(R.id.tvBranchCode);
            TextView tvCity = card.findViewById(R.id.tvBranchCity);
            TextView tvType = card.findViewById(R.id.tvBranchType);

            String nameAr = b.optString("branch_name_ar", "");
            String nameEn = b.optString("branch_name", "");
            String display = nameAr.isEmpty() ? nameEn : nameAr;
            if (display.isEmpty()) display = isArabic ? "فرع" : "Branch";

            tvName.setText(display);
            tvCode.setText(b.optString("branch_code", "—"));
            tvCity.setText(b.optString("city", "—"));
            tvType.setText(b.optString("status", "active").equals("active")
                    ? (isArabic ? "نشط" : "Active")
                    : (isArabic ? "غير نشط" : "Inactive"));

            branchesContainer.addView(card);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Vaults
    // ═══════════════════════════════════════════════════════

    private void addVaultRow(String code, String type, String level, String capacity) {
        View row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_setup_vault_row, vaultsContainer, false);

        TextInputEditText etCode     = row.findViewById(R.id.etCode);
        TextInputEditText etCapacity = row.findViewById(R.id.etCapacity);
        AutoCompleteTextView ddType  = row.findViewById(R.id.ddType);
        AutoCompleteTextView ddLevel = row.findViewById(R.id.ddLevel);
        ImageButton btnRemove        = row.findViewById(R.id.btnRemove);

        // Types
        String[] typeValuesAr = {"رئيسية", "يومية", "آمنة", "عرض", "عبور"};
        String[] typeValuesEn = {"Main", "Daily", "Safe", "Display", "Transit"};
        String[] typeCodes = {"main", "daily", "safe", "display", "transit"};
        String[] typeLabels = isArabic ? typeValuesAr : typeValuesEn;

        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_dropdown_item_1line, typeLabels);
        ddType.setAdapter(typeAdapter);
        ddType.setText(typeLabel(type, typeCodes, typeLabels), false);

        // Levels
        String[] levelValuesAr = {"منخفض", "متوسط", "عالي", "أقصى"};
        String[] levelValuesEn = {"Low", "Medium", "High", "Maximum"};
        String[] levelCodes = {"low", "medium", "high", "maximum"};
        String[] levelLabels = isArabic ? levelValuesAr : levelValuesEn;

        ArrayAdapter<String> levelAdapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_dropdown_item_1line, levelLabels);
        ddLevel.setAdapter(levelAdapter);
        ddLevel.setText(levelLabel(level, levelCodes, levelLabels), false);

        etCode.setText(code);
        etCapacity.setText(capacity);

        VaultRow vr = new VaultRow(row, etCode, etCapacity, ddType, ddLevel);
        vaultRows.add(vr);

        btnRemove.setOnClickListener(v -> {
            if (vaultRows.size() <= 1) {
                Toast.makeText(requireContext(),
                        isArabic ? "يجب أن تبقى خزنة واحدة على الأقل"
                                : "At least one vault is required",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            vaultsContainer.removeView(row);
            vaultRows.remove(vr);
            updateVaultCount();
        });

        vaultsContainer.addView(row);
        tvVaultsEmpty.setVisibility(View.GONE);
        updateVaultCount();
    }

    private String typeLabel(String code, String[] codes, String[] labels) {
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(code)) return labels[i];
        }
        return labels[0];
    }

    private String typeValue(String label, String[] codes, String[] labels) {
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equals(label)) return codes[i];
        }
        return codes[0];
    }

    private void updateVaultCount() {
        tvVaultCount.setText(String.valueOf(vaultRows.size()));
    }

    // ═══════════════════════════════════════════════════════
    //  Save
    // ═══════════════════════════════════════════════════════

    private void saveAndNext() {
        // 1) التحقق من الفروع
        if (branchesList.isEmpty()) {
            Toast.makeText(requireContext(),
                    isArabic ? "لا توجد فروع — أضف فرعاً أولاً"
                            : "No branches — add one first",
                    Toast.LENGTH_LONG).show();
            return;
        }

        // 2) التحقق من الخزائن
        if (vaultRows.isEmpty()) {
            Toast.makeText(requireContext(),
                    isArabic ? "أضف خزنة واحدة على الأقل"
                            : "Add at least one vault",
                    Toast.LENGTH_LONG).show();
            return;
        }

        try {
            // ═══ بناء payload الفروع ═══
            JSONArray branchesArray = new JSONArray();
            for (JSONObject b : branchesList) {
                JSONObject bo = new JSONObject();
                bo.put("id", b.optInt("id", 0));
                bo.put("name_ar", b.optString("branch_name_ar",
                        b.optString("branch_name", "")));
                bo.put("name_en", b.optString("branch_name", ""));
                bo.put("code", b.optString("branch_code", ""));
                bo.put("city", b.optString("city", ""));
                bo.put("type", "branch");
                bo.put("country", "Yemen");
                bo.put("currency", "YER");
                bo.put("status", "active");
                branchesArray.put(bo);
            }

            // ═══ بناء payload الخزائن ═══
            JSONArray vaultsArray = new JSONArray();
            for (VaultRow vr : vaultRows) {
                String code = safeText(vr.etCode);
                if (code.isEmpty()) {
                    Toast.makeText(requireContext(),
                            isArabic ? "أدخل كود كل خزنة" : "Enter code for each vault",
                            Toast.LENGTH_LONG).show();
                    return;
                }

                String[] typeCodes = {"main", "daily", "safe", "display", "transit"};
                String[] typeLabels = isArabic
                        ? new String[]{"رئيسية", "يومية", "آمنة", "عرض", "عبور"}
                        : new String[]{"Main", "Daily", "Safe", "Display", "Transit"};

                String[] levelCodes = {"low", "medium", "high", "maximum"};
                String[] levelLabels = isArabic
                        ? new String[]{"منخفض", "متوسط", "عالي", "أقصى"}
                        : new String[]{"Low", "Medium", "High", "Maximum"};

                JSONObject vo = new JSONObject();
                vo.put("vault_code", code);
                vo.put("vault_name", code);
                vo.put("vault_type", typeValue(vr.ddType.getText().toString(), typeCodes, typeLabels));
                vo.put("security_level", typeValue(vr.ddLevel.getText().toString(), levelCodes, levelLabels));
                double cap = parseDouble(vr.etCapacity);
                if (cap > 0) vo.put("capacity_grams", cap);
                vaultsArray.put(vo);
            }

            // ═══ إرسال ═══
            btnNext.setEnabled(false);
            btnNext.setText(isArabic ? "جاري الحفظ..." : "Saving...");
            showLoading(true);

            // أولاً: حفظ الفروع
            new Thread(() -> {
                try {
                    JSONObject branchesPayload = new JSONObject();
                    branchesPayload.put("branches", branchesArray);
                    MiraApiClient.ApiResponse brResp = apiClient.postAccounting("setup/step/2", branchesPayload);

                    if (brResp == null || !brResp.isSuccess) {
                        String err = (brResp != null && brResp.error != null) ? brResp.error : "unknown";
                        new Handler(Looper.getMainLooper()).post(() -> {
                            if (!isAdded()) return;
                            showLoading(false);
                            btnNext.setEnabled(true);
                            btnNext.setText(isArabic ? "التالي ←" : "Next →");
                            Toast.makeText(requireContext(), "❌ فروع: " + err, Toast.LENGTH_LONG).show();
                        });
                        return;
                    }

                    // ثانياً: حفظ الخزائن
                    JSONObject vaultsPayload = new JSONObject();
                    vaultsPayload.put("vaults", vaultsArray);
                    vaultsPayload.put("branch_id", branchesList.get(0).optInt("id", 1));
                    MiraApiClient.ApiResponse vResp = apiClient.postAccounting("setup/step/2/vaults", vaultsPayload);

                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (!isAdded()) return;
                        showLoading(false);
                        btnNext.setEnabled(true);
                        btnNext.setText(isArabic ? "التالي ←" : "Next →");

                        if (vResp != null && vResp.isSuccess) {
                            Toast.makeText(requireContext(),
                                    isArabic ? "✅ تم حفظ الفروع والخزائن"
                                            : "✅ Branches & Vaults saved",
                                    Toast.LENGTH_SHORT).show();
                            requireActivity().getSupportFragmentManager().popBackStack();
                        } else {
                            String err = (vResp != null && vResp.error != null) ? vResp.error : "unknown";
                            Toast.makeText(requireContext(), "❌ خزائن: " + err, Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception ex) {
                    Log.e(TAG, "save", ex);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (!isAdded()) return;
                        showLoading(false);
                        btnNext.setEnabled(true);
                        btnNext.setText(isArabic ? "التالي ←" : "Next →");
                        Toast.makeText(requireContext(), "❌ " + ex.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "buildPayload", e);
            Toast.makeText(requireContext(), "❌ " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showLoading(boolean show) {
        if (pbLoading != null) pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private String safeText(TextInputEditText et) {
        if (et == null || et.getText() == null) return "";
        return et.getText().toString().trim();
    }

    private double parseDouble(TextInputEditText et) {
        try {
            String s = safeText(et).replace(",", "");
            return s.isEmpty() ? 0 : Double.parseDouble(s);
        } catch (Exception e) { return 0; }
    }

    private static class VaultRow {
        final View view;
        final TextInputEditText etCode, etCapacity;
        final AutoCompleteTextView ddType, ddLevel;
        VaultRow(View v, TextInputEditText c, TextInputEditText cap,
                 AutoCompleteTextView t, AutoCompleteTextView l) {
            view = v; etCode = c; etCapacity = cap; ddType = t; ddLevel = l;
        }
    }
}
