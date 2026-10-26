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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Step 7 — المستخدمون والصلاحيات (اختياري)
 *
 * - يعرض المستخدمين المسجلين
 * - يسمح بتعديل صلاحياتهم المحاسبية:
 *   • ترحيل القيود
 *   • اعتماد القيود
 *   • تعديل الأرصدة الافتتاحية
 *   • الحد الأقصى للقيد
 * - يحفظ عبر POST /setup/step/7
 */
public class SetupStep7UsersPermissionsFragment extends BaseSetupStepFragment {

    private static final String TAG = "SetupStep7";

    private LinearLayout container;
    private TextView tvSummary, tvEmpty;
    private MaterialButton btnBack, btnNext;
    private View pbLoading;

    private final List<UserRow> rows = new ArrayList<>();
    private MiraApiClient apiClient;
    private boolean isArabic;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup viewGroup,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_setup_step7_users_permissions, viewGroup, false);
        apiClient = MiraApiClient.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        initViews(view);
        setupListeners();
        loadUsers();
        return view;
    }

    private void initViews(View v) {
        container = v.findViewById(R.id.usersContainer);
        tvSummary = v.findViewById(R.id.tvSummary);
        tvEmpty   = v.findViewById(R.id.tvEmpty);
        btnBack   = v.findViewById(R.id.btnBack);
        btnNext   = v.findViewById(R.id.btnNext);
        pbLoading = v.findViewById(R.id.pbLoading);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> requireActivity().onBackPressed());
        btnNext.setOnClickListener(v -> saveAndNext());
    }

    private void loadUsers() {
        showLoading(true);
        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse resp = apiClient.getAccounting("setup/step/7/current");
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);

                    JSONArray users = null;
                    if (resp != null && resp.isSuccess && resp.data != null) {
                        JSONObject payload = resp.data.optJSONObject("data");
                        if (payload == null) payload = resp.data;
                        users = payload.optJSONArray("users");
                    }

                    container.removeAllViews();
                    rows.clear();

                    if (users == null || users.length() == 0) {
                        tvEmpty.setVisibility(View.VISIBLE);
                        container.setVisibility(View.GONE);
                    } else {
                        tvEmpty.setVisibility(View.GONE);
                        container.setVisibility(View.VISIBLE);
                        for (int i = 0; i < users.length(); i++) {
                            JSONObject u = users.optJSONObject(i);
                            if (u == null) continue;
                            addUserRow(u);
                        }
                    }
                    updateSummary();
                });
            } catch (Exception e) {
                Log.e(TAG, "loadUsers", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    tvEmpty.setVisibility(View.VISIBLE);
                    container.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    private void addUserRow(JSONObject user) {
        View card = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_setup_user_permission, container, false);

        int userId       = user.optInt("id", 0);
        String username  = user.optString("username", "");
        String display   = user.optString("display_name", username);
        if (display == null || display.isEmpty()) display = username;

        boolean canPost     = user.optInt("can_post_journal", 0) == 1;
        boolean canApprove  = user.optInt("can_approve_journal", 0) == 1;
        boolean canEditOpen = user.optInt("can_edit_opening", 0) == 1;
        double maxAmount    = user.optDouble("max_entry_amount", 0);

        TextView tvName  = card.findViewById(R.id.tvName);
        TextView tvUser  = card.findViewById(R.id.tvUsername);
        MaterialSwitch swPost     = card.findViewById(R.id.swCanPost);
        MaterialSwitch swApprove  = card.findViewById(R.id.swCanApprove);
        MaterialSwitch swEditOpen = card.findViewById(R.id.swCanEditOpening);
        TextInputEditText etMax   = card.findViewById(R.id.etMaxAmount);

        tvName.setText(display);
        tvUser.setText("@" + username);

        swPost.setChecked(canPost);
        swApprove.setChecked(canApprove);
        swEditOpen.setChecked(canEditOpen);
        if (maxAmount > 0) etMax.setText(formatInput(maxAmount));

        UserRow row = new UserRow(userId, username, swPost, swApprove, swEditOpen, etMax);
        rows.add(row);

        TextWatcher w = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { updateSummary(); }
        };
        etMax.addTextChangedListener(w);
        swPost.setOnCheckedChangeListener((btn, checked) -> updateSummary());
        swApprove.setOnCheckedChangeListener((btn, checked) -> updateSummary());
        swEditOpen.setOnCheckedChangeListener((btn, checked) -> updateSummary());

        container.addView(card);
    }

    private void updateSummary() {
        int withPost = 0;
        int withApprove = 0;
        int withEditOpen = 0;

        for (UserRow r : rows) {
            if (r.swPost.isChecked()) withPost++;
            if (r.swApprove.isChecked()) withApprove++;
            if (r.swEditOpen.isChecked()) withEditOpen++;
        }

        tvSummary.setText(String.format(Locale.US,
                isArabic
                        ? "%d مستخدم — ترحيل:%d | اعتماد:%d | افتتاحي:%d"
                        : "%d users — post:%d | approve:%d | opening:%d",
                rows.size(), withPost, withApprove, withEditOpen));
    }

    private void saveAndNext() {
        try {
            JSONArray usersArray = new JSONArray();
            for (UserRow r : rows) {
                JSONObject u = new JSONObject();
                u.put("user_id", r.userId);
                u.put("can_post", r.swPost.isChecked());
                u.put("can_approve", r.swApprove.isChecked());
                u.put("can_edit_opening", r.swEditOpen.isChecked());
                u.put("max_amount", parseDouble(r.etMax));
                usersArray.put(u);
            }

            JSONObject payload = new JSONObject();
            payload.put("users", usersArray);

            btnNext.setEnabled(false);
            btnNext.setText(isArabic ? "جاري الحفظ..." : "Saving...");
            showLoading(true);

            new Thread(() -> {
                try {
                    MiraApiClient.ApiResponse resp = apiClient.postAccounting("setup/step/7", payload);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (!isAdded()) return;
                        showLoading(false);
                        btnNext.setEnabled(true);
                        btnNext.setText(isArabic ? "التالي ←" : "Next →");

                        if (resp != null && resp.isSuccess) {
                            Toast.makeText(requireContext(),
                                    isArabic ? "✅ تم حفظ الصلاحيات" : "✅ Permissions saved",
                                    Toast.LENGTH_SHORT).show();
                            requireActivity().getSupportFragmentManager().popBackStack();
                        } else {
                            String err = (resp != null && resp.error != null) ? resp.error : "unknown";
                            Toast.makeText(requireContext(), "❌ " + err, Toast.LENGTH_LONG).show();
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

    private double parseDouble(TextInputEditText et) {
        try {
            if (et == null || et.getText() == null) return 0;
            String s = et.getText().toString().trim().replace(",", "");
            return s.isEmpty() ? 0 : Double.parseDouble(s);
        } catch (Exception e) { return 0; }
    }

    private String formatInput(double v) {
        if (v == Math.floor(v)) return String.format(Locale.US, "%.0f", v);
        return String.format(Locale.US, "%.2f", v);
    }

    private static class UserRow {
        final int userId;
        final String username;
        final MaterialSwitch swPost, swApprove, swEditOpen;
        final TextInputEditText etMax;

        UserRow(int id, String username, MaterialSwitch p, MaterialSwitch a,
                MaterialSwitch o, TextInputEditText m) {
            this.userId = id;
            this.username = username;
            this.swPost = p;
            this.swApprove = a;
            this.swEditOpen = o;
            this.etMax = m;
        }
    }
}
