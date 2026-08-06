package com.example.uhf.fragment;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.tools.SessionManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Users Management Fragment
 * 
 * إدارة المستخدمين والصلاحيات
 * - SYSTEM_ADMIN: يرى جميع المستخدمين في كل الفروع
 * - ORG_ADMIN: يرى جميع المستخدمين في كل الفروع
 * - BRANCH_MANAGER: يرى مستخدمي فرعه فقط ويضيفهم لفرعه تلقائياً
 * - دعم عربي/إنجليزي كامل
 * - توليد كلمات مرور قوية تلقائياً
 * - اقتراح اسم مستخدم ذكي
 * - مؤشر قوة كلمة المرور التفاعلي
 */
public class UsersManagementFragment extends Fragment {

    // ==================== UI ====================
    private RecyclerView rvUsersList;
    private EditText etSearchUser;
    private TextView tvTotalUsers, tvActiveUsers, tvOnlineNow;
    private Button btnAddUser, btnRefreshUsers;

    // ==================== Managers ====================
    private MiraApiClient apiClient;
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ==================== Data ====================
    private UsersAdapter adapter;
    private final List<UserData> userList = new ArrayList<>();
    private final List<UserData> filteredList = new ArrayList<>();
    private boolean isArabic;

    // ==================== Roles ====================
    private static final String[] ROLES = {
        "SYSTEM_ADMIN", "ORG_ADMIN", "BRANCH_MANAGER", "INVENTORY_MGR",
        "POS_OPERATOR", "SECURITY", "GATE_SECURITY", "AUDITOR"
    };

    private static final String[] ROLES_AR = {
        "مهندس النظام", "مدير الجهة", "مدير الفرع", "مدير المخزون",
        "موظف مبيعات", "أمن", "أمن البوابات", "مدقق"
    };

    // ==================== Password Generator ====================
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String SYMBOLS = "!@#$%^&*()_+-=[]{}|;:,.<>?";
    private static final String ALL = UPPER + LOWER + DIGITS + SYMBOLS;
    private final SecureRandom random = new SecureRandom();

    // ==================== Lifecycle ====================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_users_management, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        apiClient = MiraApiClient.getInstance(requireContext());
        sessionManager = new SessionManager(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());

        rvUsersList = view.findViewById(R.id.rvUsersList);
        etSearchUser = view.findViewById(R.id.etSearchUser);
        tvTotalUsers = view.findViewById(R.id.tvTotalUsers);
        tvActiveUsers = view.findViewById(R.id.tvActiveUsers);
        tvOnlineNow = view.findViewById(R.id.tvOnlineNow);
        btnAddUser = view.findViewById(R.id.btnAddUser);
        btnRefreshUsers = view.findViewById(R.id.btnRefreshUsers);

        applyLanguage();

        rvUsersList.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new UsersAdapter(filteredList, isArabic);
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

    private void applyLanguage() {
        if (btnAddUser != null) btnAddUser.setText(isArabic ? "+ إضافة مستخدم" : "+ Add User");
        if (etSearchUser != null) etSearchUser.setHint(isArabic ? "بحث عن مستخدم..." : "Search users...");
    }

    // ==================== Load Users ====================

    private void loadUsers() {
        Executors.newSingleThreadExecutor().execute(() -> {
            MiraApiClient.ApiResponse response = apiClient.getUsers();
            mainHandler.post(() -> {
                if (response != null && response.isSuccess) parseUsers(response.data);
                else Toast.makeText(getContext(), isArabic ? "فشل تحميل المستخدمين" : "Failed to load users", Toast.LENGTH_SHORT).show();
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
            user.id = u.optInt("id"); user.username = u.optString("username"); user.displayName = u.optString("display_name");
            user.role = u.optString("role"); user.department = u.optString("department"); user.employeeId = u.optString("employee_id");
            user.status = u.optString("status", "active"); user.lastLogin = u.optString("last_login");
            user.isOnline = u.optBoolean("is_online", false); user.branchName = u.optString("branch_name", "");
            userList.add(user);
        }
        filterUsers(etSearchUser.getText().toString()); updateStats();
    }

    private void filterUsers(String query) {
        filteredList.clear();
        if (query.isEmpty()) filteredList.addAll(userList);
        else {
            String q = query.toLowerCase();
            for (UserData u : userList) {
                if (u.displayName.toLowerCase().contains(q) || u.username.toLowerCase().contains(q) || u.role.toLowerCase().contains(q))
                    filteredList.add(u);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void updateStats() {
        int total = userList.size(), active = 0, online = 0;
        for (UserData u : userList) { if ("active".equals(u.status)) active++; if (u.isOnline) online++; }
        if (tvTotalUsers != null) tvTotalUsers.setText(String.valueOf(total));
        if (tvActiveUsers != null) tvActiveUsers.setText(String.valueOf(active));
        if (tvOnlineNow != null) tvOnlineNow.setText(String.valueOf(online));
    }

    // ==================== 🧠 Smart Password Generator ====================

    private String generateStrongPassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        // ضمان وجود حرف كبير، حرف صغير، رقم، ورمز على الأقل
        sb.append(UPPER.charAt(random.nextInt(UPPER.length())));
        sb.append(LOWER.charAt(random.nextInt(LOWER.length())));
        sb.append(DIGITS.charAt(random.nextInt(DIGITS.length())));
        sb.append(SYMBOLS.charAt(random.nextInt(SYMBOLS.length())));
        // باقي الأحرف عشوائية
        for (int i = 4; i < length; i++) {
            sb.append(ALL.charAt(random.nextInt(ALL.length())));
        }
        // خلط الأحرف
        char[] chars = sb.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char temp = chars[i]; chars[i] = chars[j]; chars[j] = temp;
        }
        return new String(chars);
    }

    private String suggestUsername(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) return "";
        // إزالة المسافات والحركات
        String cleaned = displayName.trim().replaceAll("\\s+", ".").toLowerCase();
        // إزالة الأحرف غير الإنجليزية
        cleaned = cleaned.replaceAll("[^a-z0-9.]", "");
        if (cleaned.isEmpty()) return "user" + (System.currentTimeMillis() % 10000);
        return cleaned;
    }

    private void copyToClipboard(String text) {
        if (getContext() == null) return;
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("password", text));
        Toast.makeText(getContext(), isArabic ? "📋 تم النسخ!" : "📋 Copied!", Toast.LENGTH_SHORT).show();
    }

    // ==================== User Dialog ====================

    private void showUserDialog(@Nullable UserData existingUser) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_user_edit, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        boolean isEdit = existingUser != null;

        // عناصر الواجهة
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
        TextView tvPasswordRequirements = dialogView.findViewById(R.id.tvPasswordRequirements);
        ImageButton btnGeneratePassword = dialogView.findViewById(R.id.btnGeneratePassword);
        ImageButton btnCopyPassword = dialogView.findViewById(R.id.btnCopyPassword);
        ImageButton btnSuggestUsername = dialogView.findViewById(R.id.btnSuggestUsername);

        // مفاتيح الصلاحيات
        Switch swSystemAdmin = dialogView.findViewById(R.id.swSystemAdmin);
        Switch swOrgAdmin = dialogView.findViewById(R.id.swOrgAdmin);
        Switch swBranchManager = dialogView.findViewById(R.id.swBranchManager);
        Switch swInventoryMgr = dialogView.findViewById(R.id.swInventoryMgr);
        Switch swPosOperator = dialogView.findViewById(R.id.swPosOperator);
        Switch swSecurity = dialogView.findViewById(R.id.swSecurity);
        Switch swGateSecurity = dialogView.findViewById(R.id.swGateSecurity);
        Switch swAuditor = dialogView.findViewById(R.id.swAuditor);

        RadioButton rbSystemAdmin = dialogView.findViewById(R.id.rbSystemAdmin);
        RadioButton rbOrgAdmin = dialogView.findViewById(R.id.rbOrgAdmin);
        RadioButton rbBranchManager = dialogView.findViewById(R.id.rbBranchManager);
        RadioButton rbInventoryMgr = dialogView.findViewById(R.id.rbInventoryMgr);
        RadioButton rbPosOperator = dialogView.findViewById(R.id.rbPosOperator);
        RadioButton rbSecurity = dialogView.findViewById(R.id.rbSecurity);
        RadioButton rbGateSecurity = dialogView.findViewById(R.id.rbGateSecurity);
        RadioButton rbAuditor = dialogView.findViewById(R.id.rbAuditor);

        String[] departments = isArabic
            ? new String[]{"المبيعات", "المخزون", "الإدارة", "الأمن", "التدقيق", "المشتريات", "خدمة العملاء"}
            : new String[]{"Sales", "Inventory", "Management", "Security", "Audit", "Purchasing", "Customer Service"};
        ArrayAdapter<String> deptAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, departments);
        etDepartment.setAdapter(deptAdapter);

        tvTitle.setText(isEdit ? (isArabic ? "تعديل مستخدم" : "Edit User") : (isArabic ? "إضافة مستخدم جديد" : "Add New User"));
        if (tvSubtitle != null) tvSubtitle.setText(isEdit ? (isArabic ? "تعديل بيانات المستخدم الحالي" : "Edit current user data") : (isArabic ? "أدخل بيانات المستخدم والصلاحية" : "Enter user data and role"));

        // 🧠 زر توليد كلمة مرور قوية
        if (btnGeneratePassword != null) {
            btnGeneratePassword.setOnClickListener(v -> {
                String pwd = generateStrongPassword(12);
                if (etPassword != null) etPassword.setText(pwd);
                Toast.makeText(getContext(), isArabic ? "🔐 تم توليد كلمة مرور قوية!" : "🔐 Strong password generated!", Toast.LENGTH_SHORT).show();
            });
        }

        // 🧠 زر نسخ كلمة المرور
        if (btnCopyPassword != null) {
            btnCopyPassword.setOnClickListener(v -> {
                String pwd = etPassword != null ? etPassword.getText().toString() : "";
                if (!pwd.isEmpty()) copyToClipboard(pwd);
                else Toast.makeText(getContext(), isArabic ? "لا توجد كلمة مرور لنسخها" : "No password to copy", Toast.LENGTH_SHORT).show();
            });
        }

        // 🧠 زر اقتراح اسم مستخدم
        if (btnSuggestUsername != null && etDisplayName != null && etUsername != null) {
            btnSuggestUsername.setOnClickListener(v -> {
                String name = etDisplayName.getText().toString().trim();
                if (!name.isEmpty()) {
                    etUsername.setText(suggestUsername(name));
                } else {
                    Toast.makeText(getContext(), isArabic ? "أدخل الاسم الظاهر أولاً" : "Enter display name first", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // 🧠 مراقبة الاسم الظاهر لاقتراح اسم مستخدم تلقائياً (للإضافة الجديدة فقط)
        if (!isEdit && etDisplayName != null && etUsername != null) {
            etDisplayName.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    // فقط إذا كان اسم المستخدم فارغاً أو مطابقاً للاقتراح السابق
                    String currentUser = etUsername.getText().toString().trim();
                    if (currentUser.isEmpty() || currentUser.equals(suggestUsername(s.toString().trim()))) {
                        // لا تغير إذا كان المستخدم قد عدل يدوياً
                    }
                }
            });
        }

        // 🧠 متطلبات كلمة المرور
        if (tvPasswordRequirements != null) {
            tvPasswordRequirements.setText(isArabic
                ? "🔒 كلمة المرور يجب أن تحتوي على:\n• 8 أحرف على الأقل\n• حرف كبير (A-Z)\n• رقم (0-9)\n• رمز خاص (!@#$%^&*)"
                : "🔒 Password must contain:\n• Min 8 characters\n• Uppercase (A-Z)\n• Digit (0-9)\n• Special char (!@#$%^&*)");
        }

        // ربط Switch مع RadioButton
        CompoundButton.OnCheckedChangeListener switchListener = (buttonView, isChecked) -> {
            if (buttonView == swSystemAdmin && isChecked && rbSystemAdmin != null) rbSystemAdmin.setChecked(true);
            else if (buttonView == swOrgAdmin && isChecked && rbOrgAdmin != null) rbOrgAdmin.setChecked(true);
            else if (buttonView == swBranchManager && isChecked && rbBranchManager != null) rbBranchManager.setChecked(true);
            else if (buttonView == swInventoryMgr && isChecked && rbInventoryMgr != null) rbInventoryMgr.setChecked(true);
            else if (buttonView == swPosOperator && isChecked && rbPosOperator != null) rbPosOperator.setChecked(true);
            else if (buttonView == swSecurity && isChecked && rbSecurity != null) rbSecurity.setChecked(true);
            else if (buttonView == swGateSecurity && isChecked && rbGateSecurity != null) rbGateSecurity.setChecked(true);
            else if (buttonView == swAuditor && isChecked && rbAuditor != null) rbAuditor.setChecked(true);
        };

        if (swSystemAdmin != null) swSystemAdmin.setOnCheckedChangeListener(switchListener);
        if (swOrgAdmin != null) swOrgAdmin.setOnCheckedChangeListener(switchListener);
        if (swBranchManager != null) swBranchManager.setOnCheckedChangeListener(switchListener);
        if (swInventoryMgr != null) swInventoryMgr.setOnCheckedChangeListener(switchListener);
        if (swPosOperator != null) swPosOperator.setOnCheckedChangeListener(switchListener);
        if (swSecurity != null) swSecurity.setOnCheckedChangeListener(switchListener);
        if (swGateSecurity != null) swGateSecurity.setOnCheckedChangeListener(switchListener);
        if (swAuditor != null) swAuditor.setOnCheckedChangeListener(switchListener);

        // ملء بيانات التعديل
        if (isEdit) {
            etDisplayName.setText(existingUser.displayName);
            etUsername.setText(existingUser.username);
            etUsername.setEnabled(false);
            etPassword.setHint(isArabic ? "اتركه فارغاً إذا لم ترغب بالتغيير" : "Leave empty to keep unchanged");
            etEmployeeId.setText(existingUser.employeeId);
            if (existingUser.department != null && !existingUser.department.isEmpty()) etDepartment.setText(existingUser.department, false);

            resetAllSwitches(swSystemAdmin, swOrgAdmin, swBranchManager, swInventoryMgr, swPosOperator, swSecurity, swGateSecurity, swAuditor);
            selectRoleSwitch(existingUser.role, swSystemAdmin, swOrgAdmin, swBranchManager, swInventoryMgr, swPosOperator, swSecurity, swGateSecurity, swAuditor);

            layoutStep1.setVisibility(View.VISIBLE); layoutStep2.setVisibility(View.VISIBLE);
            if (stepsIndicator != null) stepsIndicator.setVisibility(View.GONE);
            if (btnNextStep != null) btnNextStep.setVisibility(View.GONE);
            if (layoutButtons1 != null) layoutButtons1.setVisibility(View.VISIBLE);
            if (btnCancel != null) btnCancel.setVisibility(View.VISIBLE);
            if (btnPrevStep != null) btnPrevStep.setVisibility(View.GONE);
            if (btnSaveUser != null) btnSaveUser.setVisibility(View.VISIBLE);
        } else {
            layoutStep1.setVisibility(View.VISIBLE); layoutStep2.setVisibility(View.GONE);
            if (stepsIndicator != null) stepsIndicator.setVisibility(View.VISIBLE);
            if (btnNextStep != null) btnNextStep.setVisibility(View.VISIBLE);
            if (layoutButtons1 != null) layoutButtons1.setVisibility(View.GONE);
            if (btnCancel != null) btnCancel.setVisibility(View.GONE);
            if (btnPrevStep != null) btnPrevStep.setVisibility(View.GONE);
            if (btnSaveUser != null) btnSaveUser.setVisibility(View.GONE);
        }

        // قوة كلمة المرور مع التحقق من المتطلبات
        if (etPassword != null) {
            etPassword.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    String pwd = s.toString();
                    int strength = getPasswordStrength(pwd);
                    updateStrengthBars(vStrength1, vStrength2, vStrength3, vStrength4, tvStrengthLabel, strength);

                    // 🧠 التحقق من المتطلبات
                    if (tvPasswordRequirements != null && !pwd.isEmpty()) {
                        StringBuilder req = new StringBuilder();
                        req.append(pwd.length() >= 8 ? "✅ " : "❌ ").append(isArabic ? "8 أحرف على الأقل" : "Min 8 characters").append("\n");
                        req.append(pwd.matches(".*[A-Z].*") ? "✅ " : "❌ ").append(isArabic ? "حرف كبير" : "Uppercase letter").append("\n");
                        req.append(pwd.matches(".*[0-9].*") ? "✅ " : "❌ ").append(isArabic ? "رقم" : "Digit").append("\n");
                        req.append(pwd.matches(".*[!@#$%^&*()_+\\-=\\[\\]{}|;:,.<>?].*") ? "✅ " : "❌ ").append(isArabic ? "رمز خاص" : "Special char");
                        tvPasswordRequirements.setText(req.toString());
                    } else if (tvPasswordRequirements != null) {
                        tvPasswordRequirements.setText(isArabic
                            ? "🔒 كلمة المرور يجب أن تحتوي على:\n• 8 أحرف على الأقل\n• حرف كبير (A-Z)\n• رقم (0-9)\n• رمز خاص (!@#$%^&*)"
                            : "🔒 Password must contain:\n• Min 8 characters\n• Uppercase (A-Z)\n• Digit (0-9)\n• Special char (!@#$%^&*)");
                    }
                }
            });
        }

        // زر التالي
        if (btnNextStep != null) {
            btnNextStep.setOnClickListener(v -> {
                String displayName = etDisplayName.getText().toString().trim();
                String username = etUsername.getText().toString().trim();
                String password = etPassword != null ? etPassword.getText().toString().trim() : "";

                if (displayName.isEmpty()) { etDisplayName.setError(isArabic ? "مطلوب" : "Required"); return; }
                if (username.isEmpty()) { etUsername.setError(isArabic ? "مطلوب" : "Required"); return; }
                if (!isEdit && !isPasswordStrong(password)) {
                    Toast.makeText(getContext(), isArabic ? "كلمة المرور لا تستوفي المتطلبات" : "Password does not meet requirements", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!isEdit && password.length() < 4) {
                    Toast.makeText(getContext(), isArabic ? "كلمة المرور قصيرة جداً" : "Password too short", Toast.LENGTH_SHORT).show();
                    return;
                }

                layoutStep1.setVisibility(View.GONE); layoutStep2.setVisibility(View.VISIBLE);
                if (btnNextStep != null) btnNextStep.setVisibility(View.GONE);
                if (btnSaveUser != null) btnSaveUser.setVisibility(View.VISIBLE);
                if (btnPrevStep != null) btnPrevStep.setVisibility(View.VISIBLE);
                if (tvStep1 != null) { tvStep1.setBackgroundResource(R.drawable.circle_outline); tvStep1.setTextColor(0xFF44474E); }
                if (tvStep2 != null) { tvStep2.setBackgroundResource(R.drawable.circle_primary); tvStep2.setTextColor(Color.WHITE); }
                if (vStepLine1 != null) vStepLine1.setBackgroundColor(0xFF1A237E);
            });
        }

        // زر الرجوع
        if (btnPrevStep != null) {
            btnPrevStep.setOnClickListener(v -> {
                layoutStep1.setVisibility(View.VISIBLE); layoutStep2.setVisibility(View.GONE);
                if (btnNextStep != null) btnNextStep.setVisibility(View.VISIBLE);
                if (btnSaveUser != null) btnSaveUser.setVisibility(View.GONE);
                btnPrevStep.setVisibility(View.GONE);
                if (tvStep1 != null) { tvStep1.setBackgroundResource(R.drawable.circle_primary); tvStep1.setTextColor(Color.WHITE); }
                if (tvStep2 != null) { tvStep2.setBackgroundResource(R.drawable.circle_outline); tvStep2.setTextColor(0xFF44474E); }
            });
        }

        if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());

        if (btnSaveUser != null) {
            btnSaveUser.setOnClickListener(v -> {
                String displayName = etDisplayName.getText().toString().trim();
                String username = etUsername.getText().toString().trim();
                String password = etPassword != null ? etPassword.getText().toString().trim() : "";
                String employeeId = etEmployeeId != null ? etEmployeeId.getText().toString().trim() : "";
                String department = etDepartment != null ? etDepartment.getText().toString().trim() : "";

                if (displayName.isEmpty() || username.isEmpty()) {
                    Toast.makeText(getContext(), isArabic ? "الاسم واسم المستخدم مطلوبان" : "Name and username required", Toast.LENGTH_SHORT).show();
                    return;
                }

                String role = getSelectedRole(rgRole);
                saveUser(isEdit ? existingUser.id : 0, username, password, displayName, role, employeeId, department, isEdit);
                dialog.dismiss();
            });
        }

        dialog.show();
    }

    // ==================== Password Validation ====================

    private boolean isPasswordStrong(String password) {
        if (password.length() < 8) return false;
        if (!password.matches(".*[A-Z].*")) return false;
        if (!password.matches(".*[0-9].*")) return false;
        if (!password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{}|;:,.<>?].*")) return false;
        return true;
    }

    // ==================== Helpers ====================

    private void resetAllSwitches(Switch... switches) {
        for (Switch sw : switches) { if (sw != null) sw.setChecked(false); }
    }

    private void selectRoleSwitch(String role, Switch... switches) {
        switch (role) {
            case "SYSTEM_ADMIN": if (switches.length > 0 && switches[0] != null) switches[0].setChecked(true); break;
            case "ORG_ADMIN": if (switches.length > 1 && switches[1] != null) switches[1].setChecked(true); break;
            case "BRANCH_MANAGER": if (switches.length > 2 && switches[2] != null) switches[2].setChecked(true); break;
            case "INVENTORY_MGR": if (switches.length > 3 && switches[3] != null) switches[3].setChecked(true); break;
            case "POS_OPERATOR": if (switches.length > 4 && switches[4] != null) switches[4].setChecked(true); break;
            case "SECURITY": if (switches.length > 5 && switches[5] != null) switches[5].setChecked(true); break;
            case "GATE_SECURITY": if (switches.length > 6 && switches[6] != null) switches[6].setChecked(true); break;
            case "AUDITOR": if (switches.length > 7 && switches[7] != null) switches[7].setChecked(true); break;
        }
    }

    private String getSelectedRole(RadioGroup rgRole) {
        if (rgRole == null) return "INVENTORY_MGR";
        int selectedId = rgRole.getCheckedRadioButtonId();
        if (selectedId != -1) {
            View v = rgRole.findViewById(selectedId);
            if (v != null && v.getTag() != null) return v.getTag().toString();
        }
        return "INVENTORY_MGR";
    }

    private int getPasswordStrength(String password) {
        int score = 0;
        if (password.length() >= 4) score++; if (password.length() >= 6) score++; if (password.length() >= 8) score++;
        if (password.matches(".*[A-Z].*")) score++;
        if (password.matches(".*[0-9].*")) score++;
        if (password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{}|;:,.<>?].*")) score++;
        return Math.min(score, 4);
    }

    private void updateStrengthBars(View b1, View b2, View b3, View b4, TextView label, int strength) {
        int red = 0xFFEF4444, orange = 0xFFF59E0B, green = 0xFF22C55E, gray = 0xFFCBD5E1;
        if (b1 != null) b1.setBackgroundColor(strength >= 1 ? (strength <= 2 ? red : orange) : gray);
        if (b2 != null) b2.setBackgroundColor(strength >= 2 ? orange : gray);
        if (b3 != null) b3.setBackgroundColor(strength >= 3 ? green : gray);
        if (b4 != null) b4.setBackgroundColor(strength >= 4 ? green : gray);
        if (label != null) {
            String[] labels = isArabic ? new String[]{"", "ضعيفة", "متوسطة", "جيدة", "قوية"} : new String[]{"", "Weak", "Medium", "Good", "Strong"};
            int[] colors = {0, red, orange, green, green};
            label.setText(strength > 0 ? labels[strength] : "");
            label.setTextColor(strength > 0 ? colors[strength] : gray);
        }
    }

    // ==================== Save / Delete ====================

    private void saveUser(int userId, String username, String password, String displayName,
                          String role, String employeeId, String department, boolean isEdit) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("username", username); payload.put("display_name", displayName);
                payload.put("role", role); payload.put("employee_id", employeeId);
                payload.put("department", department);
                if (!password.isEmpty()) payload.put("password", password);
                if (isEdit) payload.put("user_id", userId);
                if ("BRANCH_MANAGER".equals(sessionManager.getRoleString())) payload.put("branch_id", branchesManager.getActiveBranchId());

                MiraApiClient.ApiResponse response = isEdit ? apiClient.updateUser(payload) : apiClient.createUser(payload);

                mainHandler.post(() -> {
                    if (response != null && response.isSuccess) {
                        Toast.makeText(getContext(), isEdit ? (isArabic ? "تم التعديل بنجاح" : "Updated") : (isArabic ? "تمت الإضافة بنجاح" : "Added"), Toast.LENGTH_SHORT).show();
                        loadUsers();
                    } else {
                        String msg = response != null ? response.optString("message", isArabic ? "فشل" : "Failed") : (isArabic ? "فشل الاتصال" : "Connection failed");
                        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(getContext(), isArabic ? "خطأ: " + e.getMessage() : "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void deleteUser(int userId, String name) {
        new AlertDialog.Builder(requireContext())
            .setTitle(isArabic ? "حذف مستخدم" : "Delete User")
            .setMessage((isArabic ? "هل أنت متأكد من حذف \"" : "Are you sure you want to delete \"") + name + "\"؟")
            .setPositiveButton(isArabic ? "حذف" : "Delete", (d, w) -> {
                Executors.newSingleThreadExecutor().execute(() -> {
                    MiraApiClient.ApiResponse response = apiClient.deleteUser(userId);
                    mainHandler.post(() -> {
                        Toast.makeText(getContext(), response != null && response.isSuccess ? (isArabic ? "تم الحذف" : "Deleted") : (isArabic ? "فشل الحذف" : "Failed"), Toast.LENGTH_SHORT).show();
                        loadUsers();
                    });
                });
            }).setNegativeButton(isArabic ? "إلغاء" : "Cancel", null).show();
    }

    // ==================== Data Classes ====================

    static class UserData {
        int id;
        String username, displayName, role, department, employeeId, status, lastLogin, branchName;
        boolean isOnline;
    }

    class UsersAdapter extends RecyclerView.Adapter<UsersAdapter.VH> {
        final List<UserData> list; final boolean isArabic;
        UsersAdapter(List<UserData> list, boolean isArabic) { this.list = list; this.isArabic = isArabic; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) { return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_user_row, p, false)); }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            UserData u = list.get(pos);
            if (h.tvDisplayName != null) h.tvDisplayName.setText(u.displayName);
            if (h.tvUsername != null) h.tvUsername.setText("@" + u.username);
            if (h.tvRole != null) h.tvRole.setText(getRoleAr(u.role));
            if (h.tvDepartment != null) h.tvDepartment.setText(u.department);
            if ("active".equals(u.status)) {
                if (h.vStatusDot != null) h.vStatusDot.setBackgroundResource(R.drawable.circle_green);
                if (h.tvStatus != null) { h.tvStatus.setText(u.isOnline ? (isArabic ? "متصل" : "Online") : (isArabic ? "نشط" : "Active")); h.tvStatus.setTextColor(u.isOnline ? 0xFF3B82F6 : 0xFF22C55E); }
            } else { if (h.vStatusDot != null) h.vStatusDot.setBackgroundResource(R.drawable.circle_red); if (h.tvStatus != null) { h.tvStatus.setText(isArabic ? "موقوف" : "Suspended"); h.tvStatus.setTextColor(0xFFEF4444); } }
            if (h.btnEdit != null) h.btnEdit.setOnClickListener(v -> showUserDialog(u));
            if (h.btnDelete != null) h.btnDelete.setOnClickListener(v -> deleteUser(u.id, u.displayName));
        }
        @Override public int getItemCount() { return list.size(); }
        private String getRoleAr(String role) { for (int i = 0; i < ROLES.length; i++) { if (ROLES[i].equals(role)) return ROLES_AR[i]; } return role; }
        class VH extends RecyclerView.ViewHolder {
            TextView tvDisplayName, tvUsername, tvRole, tvDepartment, tvStatus; View vStatusDot; ImageButton btnEdit, btnDelete;
            VH(View v) { super(v); tvDisplayName = v.findViewById(R.id.tvRowDisplayName); tvUsername = v.findViewById(R.id.tvRowUsername); tvRole = v.findViewById(R.id.tvRowRole); tvDepartment = v.findViewById(R.id.tvRowDepartment); tvStatus = v.findViewById(R.id.tvRowStatus); vStatusDot = v.findViewById(R.id.vStatusDot); btnEdit = v.findViewById(R.id.btnRowEdit); btnDelete = v.findViewById(R.id.btnRowDelete); }
        }
    }
            }
