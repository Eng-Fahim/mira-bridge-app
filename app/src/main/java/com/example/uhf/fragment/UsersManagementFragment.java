package com.example.uhf.fragment;

import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class UsersManagementFragment extends Fragment {

    private RecyclerView rvUsersList;
    private EditText etSearchUser;
    private TextView tvTotalUsers, tvActiveUsers, tvOnlineNow;
    private Button btnAddUser, btnRefreshUsers;

    private UsersAdapter adapter;
    private final List<UserData> userList = new ArrayList<>();
    private final List<UserData> filteredList = new ArrayList<>();
    private MiraApiClient apiClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final String[] ROLES = {
    "SYSTEM_ADMIN", "ORG_ADMIN", "BRANCH_MANAGER", "INVENTORY_MGR",
    "POS_OPERATOR", "SECURITY", "GATE_SECURITY", "AUDITOR"
};

private static final String[] ROLES_AR = {
    "مهندس النظام", "مدير الجهة", "مدير الفرع", "مدير المخزون",
    "موظف مبيعات", "أمن", "أمن البوابات", "مدقق"
};

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_users_management, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvUsersList = view.findViewById(R.id.rvUsersList);
        etSearchUser = view.findViewById(R.id.etSearchUser);
        tvTotalUsers = view.findViewById(R.id.tvTotalUsers);
        tvActiveUsers = view.findViewById(R.id.tvActiveUsers);
        tvOnlineNow = view.findViewById(R.id.tvOnlineNow);
        btnAddUser = view.findViewById(R.id.btnAddUser);
        btnRefreshUsers = view.findViewById(R.id.btnRefreshUsers);

        apiClient = MiraApiClient.getInstance(requireContext());

        rvUsersList.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new UsersAdapter(filteredList);
        rvUsersList.setAdapter(adapter);

        btnAddUser.setOnClickListener(v -> showUserDialog(null));
        btnRefreshUsers.setOnClickListener(v -> loadUsers());

        etSearchUser.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { filterUsers(s.toString()); }
        });

        loadUsers();
    }

    private void loadUsers() {
        Executors.newSingleThreadExecutor().execute(() -> {
            MiraApiClient.ApiResponse response = apiClient.getUsers();
            mainHandler.post(() -> {
                if (response != null && response.isSuccess) {
                    parseUsers(response.data);
                } else {
                    Toast.makeText(getContext(), "فشل تحميل المستخدمين", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void parseUsers(JSONObject data) {
        userList.clear();
        if (data == null) return;

        JSONArray users = data.optJSONArray("users");
        if (users == null) return;

        for (int i = 0; i < users.length(); i++) {
            JSONObject u = users.optJSONObject(i);
            if (u == null) continue;

            UserData user = new UserData();
            user.id = u.optInt("id");
            user.username = u.optString("username");
            user.displayName = u.optString("display_name");
            user.role = u.optString("role");
            user.department = u.optString("department");
            user.employeeId = u.optString("employee_id");
            user.status = u.optString("status", "active");
            user.lastLogin = u.optString("last_login");
            user.isOnline = u.optBoolean("is_online", false);
            userList.add(user);
        }

        filterUsers(etSearchUser.getText().toString());
        updateStats();
    }

    private void filterUsers(String query) {
        filteredList.clear();
        if (query.isEmpty()) {
            filteredList.addAll(userList);
        } else {
            String q = query.toLowerCase();
            for (UserData u : userList) {
                if (u.displayName.toLowerCase().contains(q)
                    || u.username.toLowerCase().contains(q)
                    || u.role.toLowerCase().contains(q)) {
                    filteredList.add(u);
                }
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void updateStats() {
        int total = userList.size();
        int active = 0, online = 0;
        for (UserData u : userList) {
            if ("active".equals(u.status)) active++;
            if (u.isOnline) online++;
        }
        tvTotalUsers.setText(String.valueOf(total));
        tvActiveUsers.setText(String.valueOf(active));
        tvOnlineNow.setText(String.valueOf(online));
    }

    // ============================================
    // 🪜 حوار إضافة/تعديل مستخدم - مع Switch للتفعيل
    // ============================================
    private void showUserDialog(@Nullable UserData existingUser) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_user_edit, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        boolean isEdit = existingUser != null;

        // ==================== عناصر الواجهة ====================
        TextView tvTitle = dialogView.findViewById(R.id.tvDialogTitle);
        TextView tvSubtitle = dialogView.findViewById(R.id.tvDialogSubtitle);
        EditText etDisplayName = dialogView.findViewById(R.id.etDisplayName);
        EditText etUsername = dialogView.findViewById(R.id.etUsername);
        EditText etPassword = dialogView.findViewById(R.id.etPassword);
        EditText etEmployeeId = dialogView.findViewById(R.id.etEmployeeId);
        com.google.android.material.textfield.MaterialAutoCompleteTextView etDepartment = dialogView.findViewById(R.id.etDepartment);
        RadioGroup rgRole = dialogView.findViewById(R.id.rgRole);
        Button btnCancel = dialogView.findViewById(R.id.btnCancelUser);
        Button btnNextStep = dialogView.findViewById(R.id.btnNextStep);
        Button btnPrevStep = dialogView.findViewById(R.id.btnPrevStep);
        Button btnSaveUser = dialogView.findViewById(R.id.btnSaveUser);
        LinearLayout layoutStep1 = dialogView.findViewById(R.id.layoutStep1);
        LinearLayout layoutStep2 = dialogView.findViewById(R.id.layoutStep2);
        LinearLayout layoutButtons1 = dialogView.findViewById(R.id.layoutButtons1);
        LinearLayout stepsIndicator = dialogView.findViewById(R.id.stepsIndicator);
        TextView tvStep1 = dialogView.findViewById(R.id.tvStep1);
        TextView tvStep2 = dialogView.findViewById(R.id.tvStep2);
        View vStepLine1 = dialogView.findViewById(R.id.vStepLine1);
        View vStrength1 = dialogView.findViewById(R.id.vStrength1);
        View vStrength2 = dialogView.findViewById(R.id.vStrength2);
        View vStrength3 = dialogView.findViewById(R.id.vStrength3);
        View vStrength4 = dialogView.findViewById(R.id.vStrength4);
        TextView tvStrengthLabel = dialogView.findViewById(R.id.tvStrengthLabel);

        // ✅ عناصر Switch لكل صلاحية
        Switch swSystemAdmin = dialogView.findViewById(R.id.swSystemAdmin);
        Switch swOrgAdmin = dialogView.findViewById(R.id.swOrgAdmin);
        Switch swInventoryMgr = dialogView.findViewById(R.id.swInventoryMgr);
        Switch swPosOperator = dialogView.findViewById(R.id.swPosOperator);
        Switch swSecurity = dialogView.findViewById(R.id.swSecurity);
        Switch swGateSecurity = dialogView.findViewById(R.id.swGateSecurity);
        Switch swAuditor = dialogView.findViewById(R.id.swAuditor);

        // ✅ عناصر RadioButton
        RadioButton rbSystemAdmin = dialogView.findViewById(R.id.rbSystemAdmin);
        RadioButton rbOrgAdmin = dialogView.findViewById(R.id.rbOrgAdmin);
        RadioButton rbInventoryMgr = dialogView.findViewById(R.id.rbInventoryMgr);
        RadioButton rbPosOperator = dialogView.findViewById(R.id.rbPosOperator);
        RadioButton rbSecurity = dialogView.findViewById(R.id.rbSecurity);
        RadioButton rbGateSecurity = dialogView.findViewById(R.id.rbGateSecurity);
        RadioButton rbAuditor = dialogView.findViewById(R.id.rbAuditor);

        // ==================== إعداد الأقسام ====================
        String[] departments = {"المبيعات", "المخزون", "الإدارة", "الأمن", "التدقيق", "المشتريات", "خدمة العملاء"};
        ArrayAdapter<String> deptAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, departments);
        etDepartment.setAdapter(deptAdapter);

        // ==================== العنوان ====================
        tvTitle.setText(isEdit ? "تعديل مستخدم" : "إضافة مستخدم جديد");
        tvSubtitle.setText(isEdit ? "تعديل بيانات المستخدم الحالي" : "أدخل بيانات المستخدم والصلاحية المطلوبة");

        // ==================== ربط Switch مع RadioButton ====================
        CompoundButton.OnCheckedChangeListener switchListener = (buttonView, isChecked) -> {
            // عند الضغط على Switch، حدد RadioButton المرتبط به
            if (buttonView == swSystemAdmin && isChecked) rbSystemAdmin.setChecked(true);
            else if (buttonView == swOrgAdmin && isChecked) rbOrgAdmin.setChecked(true);
            else if (buttonView == swInventoryMgr && isChecked) rbInventoryMgr.setChecked(true);
            else if (buttonView == swPosOperator && isChecked) rbPosOperator.setChecked(true);
            else if (buttonView == swSecurity && isChecked) rbSecurity.setChecked(true);
            else if (buttonView == swGateSecurity && isChecked) rbGateSecurity.setChecked(true);
            else if (buttonView == swAuditor && isChecked) rbAuditor.setChecked(true);
        };

        if (swSystemAdmin != null) swSystemAdmin.setOnCheckedChangeListener(switchListener);
        if (swOrgAdmin != null) swOrgAdmin.setOnCheckedChangeListener(switchListener);
        if (swInventoryMgr != null) swInventoryMgr.setOnCheckedChangeListener(switchListener);
        if (swPosOperator != null) swPosOperator.setOnCheckedChangeListener(switchListener);
        if (swSecurity != null) swSecurity.setOnCheckedChangeListener(switchListener);
        if (swGateSecurity != null) swGateSecurity.setOnCheckedChangeListener(switchListener);
        if (swAuditor != null) swAuditor.setOnCheckedChangeListener(switchListener);

        // ==================== ملء البيانات في حالة التعديل ====================
        if (isEdit) {
            etDisplayName.setText(existingUser.displayName);
            etUsername.setText(existingUser.username);
            etUsername.setEnabled(false);
            etPassword.setHint("اتركه فارغاً إذا لم ترغب بالتغيير");
            etEmployeeId.setText(existingUser.employeeId);
            if (existingUser.department != null && !existingUser.department.isEmpty()) {
                etDepartment.setText(existingUser.department, false);
            }

            // إيقاف جميع المفاتيح
            if (swSystemAdmin != null) swSystemAdmin.setChecked(false);
            if (swOrgAdmin != null) swOrgAdmin.setChecked(false);
            if (swInventoryMgr != null) swInventoryMgr.setChecked(false);
            if (swPosOperator != null) swPosOperator.setChecked(false);
            if (swSecurity != null) swSecurity.setChecked(false);
            if (swGateSecurity != null) swGateSecurity.setChecked(false);
            if (swAuditor != null) swAuditor.setChecked(false);

            // تفعيل المفتاح المناسب
            switch (existingUser.role) {
                case "SYSTEM_ADMIN": if (swSystemAdmin != null) swSystemAdmin.setChecked(true); if (rbSystemAdmin != null) rbSystemAdmin.setChecked(true); break;
                case "ORG_ADMIN": if (swOrgAdmin != null) swOrgAdmin.setChecked(true); if (rbOrgAdmin != null) rbOrgAdmin.setChecked(true); break;
                case "INVENTORY_MGR": if (swInventoryMgr != null) swInventoryMgr.setChecked(true); if (rbInventoryMgr != null) rbInventoryMgr.setChecked(true); break;
                case "POS_OPERATOR": if (swPosOperator != null) swPosOperator.setChecked(true); if (rbPosOperator != null) rbPosOperator.setChecked(true); break;
                case "SECURITY": if (swSecurity != null) swSecurity.setChecked(true); if (rbSecurity != null) rbSecurity.setChecked(true); break;
                case "GATE_SECURITY": if (swGateSecurity != null) swGateSecurity.setChecked(true); if (rbGateSecurity != null) rbGateSecurity.setChecked(true); break;
                case "AUDITOR": if (swAuditor != null) swAuditor.setChecked(true); if (rbAuditor != null) rbAuditor.setChecked(true); break;
            }

            // في حالة التعديل: إظهار كل شيء دفعة واحدة
            layoutStep1.setVisibility(View.VISIBLE);
            layoutStep2.setVisibility(View.VISIBLE);
            if (stepsIndicator != null) stepsIndicator.setVisibility(View.GONE);
            if (btnNextStep != null) btnNextStep.setVisibility(View.GONE);
            if (layoutButtons1 != null) layoutButtons1.setVisibility(View.VISIBLE);
            if (btnCancel != null) btnCancel.setVisibility(View.VISIBLE);
            if (btnPrevStep != null) btnPrevStep.setVisibility(View.GONE);
            btnSaveUser.setVisibility(View.VISIBLE);
        } else {
            // إضافة جديد: إظهار الخطوة 1 فقط، تعيين مدير المخزون افتراضياً
            layoutStep1.setVisibility(View.VISIBLE);
            layoutStep2.setVisibility(View.GONE);
            if (stepsIndicator != null) stepsIndicator.setVisibility(View.VISIBLE);
            if (btnNextStep != null) btnNextStep.setVisibility(View.VISIBLE);
            if (layoutButtons1 != null) layoutButtons1.setVisibility(View.GONE);
            if (btnCancel != null) btnCancel.setVisibility(View.GONE);
            if (btnPrevStep != null) btnPrevStep.setVisibility(View.GONE);
            btnSaveUser.setVisibility(View.GONE);
        }

        // ==================== مؤشر قوة كلمة المرور ====================
        etPassword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                int strength = getPasswordStrength(s.toString());
                updateStrengthBars(vStrength1, vStrength2, vStrength3, vStrength4, tvStrengthLabel, strength);
            }
        });

        // ==================== زر التالي ====================
        if (btnNextStep != null) {
            btnNextStep.setOnClickListener(v -> {
                String displayName = etDisplayName.getText().toString().trim();
                String username = etUsername.getText().toString().trim();
                String password = etPassword.getText().toString().trim();

                if (displayName.isEmpty()) { etDisplayName.setError("مطلوب"); etDisplayName.requestFocus(); return; }
                if (username.isEmpty()) { etUsername.setError("مطلوب"); etUsername.requestFocus(); return; }
                if (!isEdit && password.length() < 4) { etPassword.setError("4 أحرف على الأقل"); etPassword.requestFocus(); return; }

                layoutStep1.setVisibility(View.GONE);
                layoutStep2.setVisibility(View.VISIBLE);
                btnNextStep.setVisibility(View.GONE);
                btnSaveUser.setVisibility(View.VISIBLE);
                if (btnPrevStep != null) btnPrevStep.setVisibility(View.VISIBLE);

                if (tvStep1 != null) { tvStep1.setBackgroundResource(R.drawable.circle_outline); tvStep1.setTextColor(0xFF44474E); }
                if (tvStep2 != null) { tvStep2.setBackgroundResource(R.drawable.circle_primary); tvStep2.setTextColor(Color.WHITE); }
                if (vStepLine1 != null) vStepLine1.setBackgroundColor(0xFF1A237E);
            });
        }

        // ==================== زر الرجوع ====================
        if (btnPrevStep != null) {
            btnPrevStep.setOnClickListener(v -> {
                layoutStep1.setVisibility(View.VISIBLE);
                layoutStep2.setVisibility(View.GONE);
                if (btnNextStep != null) btnNextStep.setVisibility(View.VISIBLE);
                btnSaveUser.setVisibility(View.GONE);
                btnPrevStep.setVisibility(View.GONE);

                if (tvStep1 != null) { tvStep1.setBackgroundResource(R.drawable.circle_primary); tvStep1.setTextColor(Color.WHITE); }
                if (tvStep2 != null) { tvStep2.setBackgroundResource(R.drawable.circle_outline); tvStep2.setTextColor(0xFF44474E); }
            });
        }

        // ==================== زر الإلغاء ====================
        if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());

        // ==================== زر الحفظ ====================
        btnSaveUser.setOnClickListener(v -> {
            String displayName = etDisplayName.getText().toString().trim();
            String username = etUsername.getText().toString().trim();
            String password = etPassword.getText().toString().trim();
            String employeeId = etEmployeeId.getText().toString().trim();
            String department = etDepartment.getText().toString().trim();

            if (displayName.isEmpty() || username.isEmpty()) {
                Toast.makeText(getContext(), "الاسم واسم المستخدم مطلوبان", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isEdit && password.isEmpty()) {
                Toast.makeText(getContext(), "كلمة المرور مطلوبة", Toast.LENGTH_SHORT).show();
                return;
            }

            // ✅ استخراج الصلاحية المختارة (من RadioButton المرتبط بـ Switch المفعل)
            String role = getSelectedRole(rgRole, swSystemAdmin, swOrgAdmin, swInventoryMgr,
                swPosOperator, swSecurity, swGateSecurity, swAuditor);

            saveUser(isEdit ? existingUser.id : 0, username, password, displayName, role, employeeId, department, isEdit);
            dialog.dismiss();
        });

        dialog.show();
    }

    /**
     * ✅ استخراج الصلاحية المختارة من خلال Switch المفعل
     */
    private String getSelectedRole(RadioGroup rgRole, Switch... switches) {
        // إذا كان rgRole يحتوي على اختيار، استخدمه
        int selectedId = rgRole.getCheckedRadioButtonId();
        if (selectedId != -1) {
            View selectedView = rgRole.findViewById(selectedId);
            if (selectedView != null && selectedView.getTag() != null) {
                return selectedView.getTag().toString();
            }
        }
        return "INVENTORY_MGR"; // افتراضي
    }

    // ==================== قوة كلمة المرور ====================
    private int getPasswordStrength(String password) {
        int score = 0;
        if (password.length() >= 4) score++;
        if (password.length() >= 6) score++;
        if (password.matches(".*[A-Z].*")) score++;
        if (password.matches(".*[0-9].*")) score++;
        if (password.matches(".*[!@#$%^&*].*")) score++;
        return Math.min(score, 4);
    }

    private void updateStrengthBars(View b1, View b2, View b3, View b4, TextView label, int strength) {
        int red = 0xFFEF4444, orange = 0xFFF59E0B, green = 0xFF22C55E, gray = 0xFFCBD5E1;
        b1.setBackgroundColor(strength >= 1 ? (strength <= 2 ? red : orange) : gray);
        b2.setBackgroundColor(strength >= 2 ? orange : gray);
        b3.setBackgroundColor(strength >= 3 ? green : gray);
        b4.setBackgroundColor(strength >= 4 ? green : gray);
        String[] labels = {"", "ضعيفة", "متوسطة", "جيدة", "قوية"};
        int[] colors = {0, red, orange, green, green};
        label.setText(strength > 0 ? labels[strength] : "");
        label.setTextColor(strength > 0 ? colors[strength] : gray);
    }

    private void saveUser(int userId, String username, String password, String displayName,
                          String role, String employeeId, String department, boolean isEdit) {
        Executors.newSingleThreadExecutor().execute(() -> {
            JSONObject payload = new JSONObject();
            try {
                payload.put("username", username);
                payload.put("display_name", displayName);
                payload.put("role", role);
                payload.put("employee_id", employeeId);
                payload.put("department", department);
                if (!password.isEmpty()) payload.put("password", password);
                if (isEdit) payload.put("user_id", userId);
            } catch (Exception ignored) {}

            MiraApiClient.ApiResponse response = isEdit
                    ? apiClient.updateUser(payload)
                    : apiClient.createUser(payload);

            mainHandler.post(() -> {
                if (response != null && response.isSuccess) {
                    Toast.makeText(getContext(), isEdit ? "تم التعديل بنجاح" : "تمت الإضافة بنجاح", Toast.LENGTH_SHORT).show();
                    loadUsers();
                } else {
                    String msg = response != null ? response.optString("message", "فشل") : "فشل الاتصال";
                    Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void deleteUser(int userId, String name) {
        new AlertDialog.Builder(requireContext())
            .setTitle("حذف مستخدم")
            .setMessage("هل أنت متأكد من حذف \"" + name + "\"؟")
            .setPositiveButton("حذف", (d, w) -> {
                Executors.newSingleThreadExecutor().execute(() -> {
                    MiraApiClient.ApiResponse response = apiClient.deleteUser(userId);
                    mainHandler.post(() -> {
                        if (response != null && response.isSuccess) {
                            Toast.makeText(getContext(), "تم الحذف", Toast.LENGTH_SHORT).show();
                            loadUsers();
                        } else {
                            Toast.makeText(getContext(), "فشل الحذف", Toast.LENGTH_SHORT).show();
                        }
                    });
                });
            })
            .setNegativeButton("إلغاء", null)
            .show();
    }

    // ===================== User Data =====================
    static class UserData {
        int id;
        String username, displayName, role, department, employeeId, status, lastLogin;
        boolean isOnline;
    }

    // ===================== Adapter =====================
    class UsersAdapter extends RecyclerView.Adapter<UsersAdapter.VH> {
        final List<UserData> list;
        UsersAdapter(List<UserData> list) { this.list = list; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_user_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            UserData u = list.get(pos);
            h.tvDisplayName.setText(u.displayName);
            h.tvUsername.setText("@" + u.username);
            h.tvRole.setText(getRoleAr(u.role));
            h.tvDepartment.setText(u.department);

            if ("active".equals(u.status)) {
                h.vStatusDot.setBackgroundResource(R.drawable.circle_green);
                h.tvStatus.setText(u.isOnline ? "متصل" : "نشط");
                h.tvStatus.setTextColor(u.isOnline ? 0xFF3B82F6 : 0xFF22C55E);
            } else {
                h.vStatusDot.setBackgroundResource(R.drawable.circle_red);
                h.tvStatus.setText("موقوف");
                h.tvStatus.setTextColor(0xFFEF4444);
            }

            h.btnEdit.setOnClickListener(v -> showUserDialog(u));
            h.btnDelete.setOnClickListener(v -> deleteUser(u.id, u.displayName));
        }

        @Override public int getItemCount() { return list.size(); }

        private String getRoleAr(String role) {
            for (int i = 0; i < ROLES.length; i++) {
                if (ROLES[i].equals(role)) return ROLES_AR[i];
            }
            return role;
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvDisplayName, tvUsername, tvRole, tvDepartment, tvStatus;
            View vStatusDot;
            ImageButton btnEdit, btnDelete;
            VH(View v) {
                super(v);
                tvDisplayName = v.findViewById(R.id.tvRowDisplayName);
                tvUsername = v.findViewById(R.id.tvRowUsername);
                tvRole = v.findViewById(R.id.tvRowRole);
                tvDepartment = v.findViewById(R.id.tvRowDepartment);
                tvStatus = v.findViewById(R.id.tvRowStatus);
                vStatusDot = v.findViewById(R.id.vStatusDot);
                btnEdit = v.findViewById(R.id.btnRowEdit);
                btnDelete = v.findViewById(R.id.btnRowDelete);
            }
        }
    }
                }
