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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Users Management Fragment
 * 
 * إدارة المستخدمين والصلاحيات مع دعم الفروع
 * - SYSTEM_ADMIN: يرى جميع المستخدمين في كل الفروع - يمكنه إنشاء جميع الأدوار
 * - ORG_ADMIN: يرى جميع المستخدمين في كل الفروع - يمكنه إنشاء جميع الأدوار ما عدا SYSTEM_ADMIN
 * - BRANCH_MANAGER: يرى مستخدمي فرعه فقط - يمكنه إنشاء أدوار محدودة لفرعه
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
    private SessionManager.Role currentUserRole;

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
        currentUserRole = sessionManager.getUserRole();

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

        btnAddUser.setOnClickListener(v -> {
            if (canManageUsers()) {
                showUserDialog(null);
            } else {
                Toast.makeText(getContext(), 
                    isArabic ? "❌ ليس لديك صلاحية لإضافة مستخدمين" : "❌ You don't have permission to add users", 
                    Toast.LENGTH_SHORT).show();
            }
        });
        
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
        if (btnRefreshUsers != null) btnRefreshUsers.setText(isArabic ? "تحديث" : "Refresh");
    }

    // ==================== Branch & Permission Helpers ====================

    /**
     * ✅ الحصول على branch_id المناسب للمستخدم الجديد
     */
    private int getBranchIdForNewUser() {
        // دائماً نستخدم الفرع النشط حالياً
        return branchesManager.getActiveBranchId();
    }

    /**
     * ✅ التحقق من صلاحية إدارة المستخدمين
     */
    private boolean canManageUsers() {
        SessionManager.Role role = currentUserRole;
        return role == SessionManager.Role.SYSTEM_ADMIN 
            || role == SessionManager.Role.ORG_ADMIN 
            || role == SessionManager.Role.BRANCH_MANAGER;
    }

    /**
     * ✅ التحقق من إمكانية حذف مستخدم
     */
    private boolean canDeleteUser(String targetRole) {
        SessionManager.Role role = currentUserRole;
        
        // SYSTEM_ADMIN: يمكنه حذف أي مستخدم
        if (role == SessionManager.Role.SYSTEM_ADMIN) return true;
        
        // ORG_ADMIN: يمكنه حذف الجميع ما عدا SYSTEM_ADMIN
        if (role == SessionManager.Role.ORG_ADMIN && !"SYSTEM_ADMIN".equals(targetRole)) return true;
        
        // BRANCH_MANAGER: يمكنه حذف مستخدمي فرعه فقط من الأدوار المحدودة
        if (role == SessionManager.Role.BRANCH_MANAGER) {
            String[] allowed = {"INVENTORY_MGR", "POS_OPERATOR", "SECURITY", "GATE_SECURITY"};
            for (String a : allowed) {
                if (a.equals(targetRole)) return true;
            }
        }
        
        return false;
    }

    /**
     * ✅ الحصول على الأدوار المسموح بإنشائها
     */
    private String[] getAllowedRoles() {
        SessionManager.Role role = currentUserRole;
        
        if (role == SessionManager.Role.SYSTEM_ADMIN) {
            return ROLES;
        }
        
        if (role == SessionManager.Role.ORG_ADMIN) {
            return new String[]{"ORG_ADMIN", "BRANCH_MANAGER", "INVENTORY_MGR", 
                               "POS_OPERATOR", "SECURITY", "GATE_SECURITY", "AUDITOR"};
        }
        
        if (role == SessionManager.Role.BRANCH_MANAGER) {
            return new String[]{"INVENTORY_MGR", "POS_OPERATOR", "SECURITY", "GATE_SECURITY"};
        }
        
        return new String[]{};
    }

    /**
     * ✅ التحقق من صلاحية تعديل مستخدم
     */
    private boolean canEditUser(String targetRole) {
        return canDeleteUser(targetRole); // نفس منطق الصلاحيات
    }

    // ==================== Load Users ====================

    private void loadUsers() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getUsers();
                mainHandler.post(() -> {
                    if (response != null && response.isSuccess) {
                        parseUsers(response.data);
                    } else {
                        Toast.makeText(getContext(), 
                            isArabic ? "فشل تحميل المستخدمين" : "Failed to load users", 
                            Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    Toast.makeText(getContext(), 
                        isArabic ? "خطأ في الاتصال" : "Connection error", 
                        Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void parseUsers(JSONObject data) {
        userList.clear();
        if (data == null) return;
        JSONArray users = data.optJSONArray("users");
        if (users == null) return;
        
        int currentBranchId = branchesManager.getActiveBranchId();
        SessionManager.Role role = currentUserRole;
        
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
            user.branchName = u.optString("branch_name", "");
            user.branchId = u.optInt("branch_id", 0);
            
            // ✅ تصفية المستخدمين حسب صلاحية المستخدم الحالي
            if (role == SessionManager.Role.SYSTEM_ADMIN || role == SessionManager.Role.ORG_ADMIN) {
                // يرى جميع المستخدمين
                userList.add(user);
            } else if (role == SessionManager.Role.BRANCH_MANAGER) {
                // يرى مستخدمي فرعه فقط
                if (user.branchId == currentBranchId) {
                    userList.add(user);
                }
            } else {
                // الأدوار الأخرى: لا يرون مستخدمين آخرين
                if (user.id == getCurrentUserId()) {
                    userList.add(user);
                }
            }
        }
        
        filterUsers(etSearchUser.getText().toString());
        updateStats();
    }

    private int getCurrentUserId() {
        // يمكنك تخزين userId في SessionManager
        return 0;
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
        int active = 0;
        int online = 0;
        for (UserData u : userList) {
            if ("active".equals(u.status)) active++;
            if (u.isOnline) online++;
        }
        if (tvTotalUsers != null) tvTotalUsers.setText(String.valueOf(total));
        if (tvActiveUsers != null) tvActiveUsers.setText(String.valueOf(active));
        if (tvOnlineNow != null) tvOnlineNow.setText(String.valueOf(online));
    }

    // ==================== 🧠 Smart Password Generator ====================

    private String generateStrongPassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        sb.append(UPPER.charAt(random.nextInt(UPPER.length())));
        sb.append(LOWER.charAt(random.nextInt(LOWER.length())));
        sb.append(DIGITS.charAt(random.nextInt(DIGITS.length())));
        sb.append(SYMBOLS.charAt(random.nextInt(SYMBOLS.length())));
        for (int i = 4; i < length; i++) {
            sb.append(ALL.charAt(random.nextInt(ALL.length())));
        }
        char[] chars = sb.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char temp = chars[i];
            chars[i] = chars[j];
            chars[j] = temp;
        }
        return new String(chars);
    }

    private String suggestUsername(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) return "";
        String cleaned = displayName.trim().replaceAll("\\s+", ".").toLowerCase();
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
        if (!isAdded() || getContext() == null) return;
        
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_user_edit, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        boolean isEdit = existingUser != null;

        // عناصر الواجهة
        TextView tvTitle = dialogView.findViewById(R.id.tvDialogTitle);
        TextView tvSubtitle = dialogView.findViewById(R.id.tvDialogSubtitle);
        EditText etDisplayName = dialogView.findViewById(R.id.etDisplayName);
        EditText etUsername = dialogView.findViewById(R.id.etUsername);
        EditText etPassword = dialogView.findViewById(R.id.etPassword);
        EditText etEmployeeId = dialogView.findViewById(R.id.etEmployeeId);
        com.google.android.material.textfield.MaterialAutoCompleteTextView etDepartment = 
            dialogView.findViewById(R.id.etDepartment);
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

        // تجميع المفاتيح في مصفوفات
        Switch[] allSwitches = {swSystemAdmin, swOrgAdmin, swBranchManager, swInventoryMgr, 
                               swPosOperator, swSecurity, swGateSecurity, swAuditor};
        RadioButton[] allRadioButtons = {rbSystemAdmin, rbOrgAdmin, rbBranchManager, rbInventoryMgr, 
                                        rbPosOperator, rbSecurity, rbGateSecurity, rbAuditor};

        // ✅ تحديث واجهة الصلاحيات حسب صلاحية المستخدم الحالي
        updateAvailableRolesUI(allSwitches, allRadioButtons);

        // القسم
        String[] departments = isArabic
            ? new String[]{"المبيعات", "المخزون", "الإدارة", "الأمن", "التدقيق", "المشتريات", "خدمة العملاء"}
            : new String[]{"Sales", "Inventory", "Management", "Security", "Audit", "Purchasing", "Customer Service"};
        ArrayAdapter<String> deptAdapter = new ArrayAdapter<>(requireContext(), 
            android.R.layout.simple_dropdown_item_1line, departments);
        if (etDepartment != null) etDepartment.setAdapter(deptAdapter);

        // العناوين
        if (tvTitle != null) {
            tvTitle.setText(isEdit 
                ? (isArabic ? "تعديل مستخدم" : "Edit User") 
                : (isArabic ? "إضافة مستخدم جديد" : "Add New User"));
        }
        if (tvSubtitle != null) {
            tvSubtitle.setText(isEdit 
                ? (isArabic ? "تعديل بيانات المستخدم الحالي" : "Edit current user data") 
                : (isArabic ? "أدخل بيانات المستخدم والصلاحية" : "Enter user data and role"));
        }

        // 🧠 زر توليد كلمة مرور قوية
        if (btnGeneratePassword != null) {
            btnGeneratePassword.setOnClickListener(v -> {
                String pwd = generateStrongPassword(12);
                if (etPassword != null) etPassword.setText(pwd);
                Toast.makeText(getContext(), 
                    isArabic ? "🔐 تم توليد كلمة مرور قوية!" : "🔐 Strong password generated!", 
                    Toast.LENGTH_SHORT).show();
            });
        }

        // 🧠 زر نسخ كلمة المرور
        if (btnCopyPassword != null) {
            btnCopyPassword.setOnClickListener(v -> {
                String pwd = etPassword != null ? etPassword.getText().toString() : "";
                if (!pwd.isEmpty()) {
                    copyToClipboard(pwd);
                } else {
                    Toast.makeText(getContext(), 
                        isArabic ? "لا توجد كلمة مرور لنسخها" : "No password to copy", 
                        Toast.LENGTH_SHORT).show();
                }
            });
        }

        // 🧠 زر اقتراح اسم مستخدم
        if (btnSuggestUsername != null && etDisplayName != null && etUsername != null) {
            btnSuggestUsername.setOnClickListener(v -> {
                String name = etDisplayName.getText().toString().trim();
                if (!name.isEmpty()) {
                    etUsername.setText(suggestUsername(name));
                } else {
                    Toast.makeText(getContext(), 
                        isArabic ? "أدخل الاسم الظاهر أولاً" : "Enter display name first", 
                        Toast.LENGTH_SHORT).show();
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
            if (isChecked) {
                // إلغاء تفعيل جميع المفاتيح الأخرى
                for (Switch sw : allSwitches) {
                    if (sw != null && sw != buttonView) sw.setChecked(false);
                }
                // تفعيل RadioButton المناسب
                if (buttonView == swSystemAdmin && rbSystemAdmin != null) rbSystemAdmin.setChecked(true);
                else if (buttonView == swOrgAdmin && rbOrgAdmin != null) rbOrgAdmin.setChecked(true);
                else if (buttonView == swBranchManager && rbBranchManager != null) rbBranchManager.setChecked(true);
                else if (buttonView == swInventoryMgr && rbInventoryMgr != null) rbInventoryMgr.setChecked(true);
                else if (buttonView == swPosOperator && rbPosOperator != null) rbPosOperator.setChecked(true);
                else if (buttonView == swSecurity && rbSecurity != null) rbSecurity.setChecked(true);
                else if (buttonView == swGateSecurity && rbGateSecurity != null) rbGateSecurity.setChecked(true);
                else if (buttonView == swAuditor && rbAuditor != null) rbAuditor.setChecked(true);
            }
        };

        for (Switch sw : allSwitches) {
            if (sw != null) sw.setOnCheckedChangeListener(switchListener);
        }

        // ملء بيانات التعديل
        if (isEdit) {
            if (etDisplayName != null) etDisplayName.setText(existingUser.displayName);
            if (etUsername != null) {
                etUsername.setText(existingUser.username);
                etUsername.setEnabled(false);
            }
            if (etPassword != null) {
                etPassword.setHint(isArabic ? "اتركه فارغاً إذا لم ترغب بالتغيير" : "Leave empty to keep unchanged");
            }
            if (etEmployeeId != null) etEmployeeId.setText(existingUser.employeeId);
            if (etDepartment != null && existingUser.department != null && !existingUser.department.isEmpty()) {
                etDepartment.setText(existingUser.department, false);
            }

            resetAllSwitches(allSwitches);
            selectRoleSwitch(existingUser.role, allSwitches);

            if (layoutStep1 != null) layoutStep1.setVisibility(View.VISIBLE);
            if (layoutStep2 != null) layoutStep2.setVisibility(View.VISIBLE);
            if (stepsIndicator != null) stepsIndicator.setVisibility(View.GONE);
            if (btnNextStep != null) btnNextStep.setVisibility(View.GONE);
            if (layoutButtons1 != null) layoutButtons1.setVisibility(View.VISIBLE);
            if (btnCancel != null) btnCancel.setVisibility(View.VISIBLE);
            if (btnPrevStep != null) btnPrevStep.setVisibility(View.GONE);
            if (btnSaveUser != null) btnSaveUser.setVisibility(View.VISIBLE);
        } else {
            if (layoutStep1 != null) layoutStep1.setVisibility(View.VISIBLE);
            if (layoutStep2 != null) layoutStep2.setVisibility(View.GONE);
            if (stepsIndicator != null) stepsIndicator.setVisibility(View.VISIBLE);
            if (btnNextStep != null) btnNextStep.setVisibility(View.VISIBLE);
            if (layoutButtons1 != null) layoutButtons1.setVisibility(View.GONE);
            if (btnCancel != null) btnCancel.setVisibility(View.GONE);
            if (btnPrevStep != null) btnPrevStep.setVisibility(View.GONE);
            if (btnSaveUser != null) btnSaveUser.setVisibility(View.GONE);
        }

        // قوة كلمة المرور
        if (etPassword != null) {
            etPassword.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    String pwd = s.toString();
                    int strength = getPasswordStrength(pwd);
                    updateStrengthBars(vStrength1, vStrength2, vStrength3, vStrength4, tvStrengthLabel, strength);

                    if (tvPasswordRequirements != null && !pwd.isEmpty()) {
                        StringBuilder req = new StringBuilder();
                        req.append(pwd.length() >= 8 ? "✅ " : "❌ ")
                           .append(isArabic ? "8 أحرف على الأقل" : "Min 8 characters").append("\n");
                        req.append(pwd.matches(".*[A-Z].*") ? "✅ " : "❌ ")
                           .append(isArabic ? "حرف كبير" : "Uppercase letter").append("\n");
                        req.append(pwd.matches(".*[0-9].*") ? "✅ " : "❌ ")
                           .append(isArabic ? "رقم" : "Digit").append("\n");
                        req.append(pwd.matches(".*[!@#$%^&*()_+\\-=\\[\\]{}|;:,.<>?].*") ? "✅ " : "❌ ")
                           .append(isArabic ? "رمز خاص" : "Special char");
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
                String displayName = etDisplayName != null ? etDisplayName.getText().toString().trim() : "";
                String username = etUsername != null ? etUsername.getText().toString().trim() : "";
                String password = etPassword != null ? etPassword.getText().toString().trim() : "";

                if (displayName.isEmpty()) {
                    if (etDisplayName != null) etDisplayName.setError(isArabic ? "مطلوب" : "Required");
                    return;
                }
                if (username.isEmpty()) {
                    if (etUsername != null) etUsername.setError(isArabic ? "مطلوب" : "Required");
                    return;
                }
                if (!isEdit && !isPasswordStrong(password)) {
                    Toast.makeText(getContext(), 
                        isArabic ? "كلمة المرور لا تستوفي المتطلبات" : "Password does not meet requirements", 
                        Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!isEdit && password.length() < 4) {
                    Toast.makeText(getContext(), 
                        isArabic ? "كلمة المرور قصيرة جداً" : "Password too short", 
                        Toast.LENGTH_SHORT).show();
                    return;
                }

                if (layoutStep1 != null) layoutStep1.setVisibility(View.GONE);
                if (layoutStep2 != null) layoutStep2.setVisibility(View.VISIBLE);
                if (btnNextStep != null) btnNextStep.setVisibility(View.GONE);
                if (btnSaveUser != null) btnSaveUser.setVisibility(View.VISIBLE);
                if (btnPrevStep != null) btnPrevStep.setVisibility(View.VISIBLE);
                if (tvStep1 != null) { 
                    tvStep1.setBackgroundResource(R.drawable.circle_outline); 
                    tvStep1.setTextColor(0xFF44474E); 
                }
                if (tvStep2 != null) { 
                    tvStep2.setBackgroundResource(R.drawable.circle_primary); 
                    tvStep2.setTextColor(Color.WHITE); 
                }
                if (vStepLine1 != null) vStepLine1.setBackgroundColor(0xFF1A237E);
            });
        }

        // زر الرجوع
        if (btnPrevStep != null) {
            btnPrevStep.setOnClickListener(v -> {
                if (layoutStep1 != null) layoutStep1.setVisibility(View.VISIBLE);
                if (layoutStep2 != null) layoutStep2.setVisibility(View.GONE);
                if (btnNextStep != null) btnNextStep.setVisibility(View.VISIBLE);
                if (btnSaveUser != null) btnSaveUser.setVisibility(View.GONE);
                if (btnPrevStep != null) btnPrevStep.setVisibility(View.GONE);
                if (tvStep1 != null) { 
                    tvStep1.setBackgroundResource(R.drawable.circle_primary); 
                    tvStep1.setTextColor(Color.WHITE); 
                }
                if (tvStep2 != null) { 
                    tvStep2.setBackgroundResource(R.drawable.circle_outline); 
                    tvStep2.setTextColor(0xFF44474E); 
                }
            });
        }

        // زر الإلغاء
        if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());

        // زر الحفظ
        if (btnSaveUser != null) {
            btnSaveUser.setOnClickListener(v -> {
                String displayName = etDisplayName != null ? etDisplayName.getText().toString().trim() : "";
                String username = etUsername != null ? etUsername.getText().toString().trim() : "";
                String password = etPassword != null ? etPassword.getText().toString().trim() : "";
                String employeeId = etEmployeeId != null ? etEmployeeId.getText().toString().trim() : "";
                String department = etDepartment != null ? etDepartment.getText().toString().trim() : "";

                if (displayName.isEmpty() || username.isEmpty()) {
                    Toast.makeText(getContext(), 
                        isArabic ? "الاسم واسم المستخدم مطلوبان" : "Name and username required", 
                        Toast.LENGTH_SHORT).show();
                    return;
                }

                String role = getSelectedRole(allRadioButtons);
                
                // ✅ التحقق من صلاحية إنشاء هذا الدور
                if (!isEdit && !isRoleAllowed(role)) {
                    Toast.makeText(getContext(), 
                        isArabic ? "❌ ليس لديك صلاحية لإنشاء هذا الدور" : "❌ You don't have permission to create this role", 
                        Toast.LENGTH_SHORT).show();
                    return;
                }

                saveUser(isEdit ? existingUser.id : 0, username, password, displayName, 
                        role, employeeId, department, isEdit);
                dialog.dismiss();
            });
        }

        dialog.show();
    }

    // ==================== Role Permission UI ====================

    /**
     * ✅ تحديث واجهة الصلاحيات - إخفاء الأدوار غير المسموحة
     */
    private void updateAvailableRolesUI(Switch[] switches, RadioButton[] radioButtons) {
        String[] allowedRoles = getAllowedRoles();
        Set<String> allowedSet = new HashSet<>(Arrays.asList(allowedRoles));

        String[] roleTags = {"SYSTEM_ADMIN", "ORG_ADMIN", "BRANCH_MANAGER", "INVENTORY_MGR",
                            "POS_OPERATOR", "SECURITY", "GATE_SECURITY", "AUDITOR"};

        for (int i = 0; i < radioButtons.length && i < roleTags.length; i++) {
            if (!allowedSet.contains(roleTags[i])) {
                // إخفاء RadioButton
                if (radioButtons[i] != null) {
                    View parent = (View) radioButtons[i].getParent();
                    if (parent != null) {
                        View grandParent = (View) parent.getParent();
                        if (grandParent != null) {
                            grandParent.setVisibility(View.GONE);
                        }
                    }
                }
                // إخفاء Switch
                if (switches[i] != null) {
                    switches[i].setVisibility(View.GONE);
                }
            }
        }
    }

    /**
     * ✅ التحقق من صلاحية الدور
     */
    private boolean isRoleAllowed(String role) {
        String[] allowedRoles = getAllowedRoles();
        for (String allowed : allowedRoles) {
            if (allowed.equals(role)) return true;
        }
        return false;
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
        for (Switch sw : switches) {
            if (sw != null) sw.setChecked(false);
        }
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

    private String getSelectedRole(RadioButton[] radioButtons) {
        String[] roleTags = {"SYSTEM_ADMIN", "ORG_ADMIN", "BRANCH_MANAGER", "INVENTORY_MGR",
                            "POS_OPERATOR", "SECURITY", "GATE_SECURITY", "AUDITOR"};
        
        for (int i = 0; i < radioButtons.length; i++) {
            if (radioButtons[i] != null && radioButtons[i].isChecked()) {
                return roleTags[i];
            }
        }
        return "INVENTORY_MGR";
    }

    private int getPasswordStrength(String password) {
        int score = 0;
        if (password.length() >= 4) score++;
        if (password.length() >= 6) score++;
        if (password.length() >= 8) score++;
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
            String[] labels = isArabic 
                ? new String[]{"", "ضعيفة", "متوسطة", "جيدة", "قوية"} 
                : new String[]{"", "Weak", "Medium", "Good", "Strong"};
            int[] colors = {0, red, orange, green, green};
            label.setText(strength > 0 ? labels[strength] : "");
            label.setTextColor(strength > 0 ? colors[strength] : gray);
        }
    }

    // ==================== Save / Delete ====================

    private void saveUser(int userId, String username, String password, String displayName,
                          String role, String employeeId, String department, boolean isEdit) {
        
        // ✅ التحقق من صلاحية إنشاء هذا الدور
        if (!isEdit && !isRoleAllowed(role)) {
            Toast.makeText(getContext(), 
                isArabic ? "❌ ليس لديك صلاحية لإنشاء هذا الدور" : "❌ You don't have permission to create this role", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("username", username);
                payload.put("display_name", displayName);
                payload.put("role", role);
                payload.put("employee_id", employeeId);
                payload.put("department", department);
                
                if (!password.isEmpty()) {
                    payload.put("password", password);
                }
                
                if (isEdit) {
                    payload.put("user_id", userId);
                }
                
                // ✅ إضافة branch_id للمستخدم الجديد
                int branchId = getBranchIdForNewUser();
                payload.put("branch_id", branchId);
                
                // ✅ إضافة branch_name للعرض
                String branchName = branchesManager.getActiveBranchName();
                payload.put("branch_name", branchName);

                MiraApiClient.ApiResponse response = isEdit 
                    ? apiClient.updateUser(payload) 
                    : apiClient.createUser(payload);

                mainHandler.post(() -> {
                    if (response != null && response.isSuccess) {
                        String successMsg = isEdit 
                            ? (isArabic ? "✅ تم التعديل بنجاح" : "✅ Updated successfully") 
                            : (isArabic ? "✅ تمت الإضافة بنجاح - الفرع: " + branchName 
                                       : "✅ Added successfully - Branch: " + branchName);
                        Toast.makeText(getContext(), successMsg, Toast.LENGTH_SHORT).show();
                        loadUsers();
                    } else {
                        String msg = response != null 
                            ? response.optString("message", isArabic ? "فشل" : "Failed") 
                            : (isArabic ? "فشل الاتصال" : "Connection failed");
                        Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(getContext(), 
                    isArabic ? "خطأ: " + e.getMessage() : "Error: " + e.getMessage(), 
                    Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void deleteUser(int userId, String name, String role) {
        if (!isAdded() || getContext() == null) return;
        
        // ✅ التحقق من صلاحية الحذف
        if (!canDeleteUser(role)) {
            Toast.makeText(getContext(), 
                isArabic ? "❌ ليس لديك صلاحية لحذف هذا المستخدم" : "❌ You don't have permission to delete this user", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        new AlertDialog.Builder(requireContext())
            .setTitle(isArabic ? "حذف مستخدم" : "Delete User")
            .setMessage((isArabic ? "هل أنت متأكد من حذف \"" : "Are you sure you want to delete \"") + name + "\"؟")
            .setPositiveButton(isArabic ? "حذف" : "Delete", (d, w) -> {
                Executors.newSingleThreadExecutor().execute(() -> {
                    MiraApiClient.ApiResponse response = apiClient.deleteUser(userId);
                    mainHandler.post(() -> {
                        Toast.makeText(getContext(), 
                            response != null && response.isSuccess 
                                ? (isArabic ? "✅ تم الحذف" : "✅ Deleted") 
                                : (isArabic ? "❌ فشل الحذف" : "❌ Failed"), 
                            Toast.LENGTH_SHORT).show();
                        loadUsers();
                    });
                });
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
    }

    // ==================== Data Classes ====================

    static class UserData {
        int id;
        int branchId;
        String username, displayName, role, department, employeeId, status, lastLogin, branchName;
        boolean isOnline;
    }

    // ==================== Adapter ====================

    class UsersAdapter extends RecyclerView.Adapter<UsersAdapter.VH> {
        final List<UserData> list;
        final boolean isArabic;

        UsersAdapter(List<UserData> list, boolean isArabic) {
            this.list = list;
            this.isArabic = isArabic;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_user_row, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            UserData u = list.get(pos);
            if (h.tvDisplayName != null) h.tvDisplayName.setText(u.displayName);
            if (h.tvUsername != null) h.tvUsername.setText("@" + u.username);
            if (h.tvRole != null) h.tvRole.setText(getRoleAr(u.role));
            if (h.tvDepartment != null) h.tvDepartment.setText(u.department);
            if (h.tvBranchName != null && u.branchName != null && !u.branchName.isEmpty()) {
                h.tvBranchName.setText("🏢 " + u.branchName);
                h.tvBranchName.setVisibility(View.VISIBLE);
            } else if (h.tvBranchName != null) {
                h.tvBranchName.setVisibility(View.GONE);
            }
            
            if ("active".equals(u.status)) {
                if (h.vStatusDot != null) h.vStatusDot.setBackgroundResource(R.drawable.circle_green);
                if (h.tvStatus != null) {
                    h.tvStatus.setText(u.isOnline 
                        ? (isArabic ? "متصل" : "Online") 
                        : (isArabic ? "نشط" : "Active"));
                    h.tvStatus.setTextColor(u.isOnline ? 0xFF3B82F6 : 0xFF22C55E);
                }
            } else {
                if (h.vStatusDot != null) h.vStatusDot.setBackgroundResource(R.drawable.circle_red);
                if (h.tvStatus != null) {
                    h.tvStatus.setText(isArabic ? "موقوف" : "Suspended");
                    h.tvStatus.setTextColor(0xFFEF4444);
                }
            }
            
            // ✅ أزرار التعديل والحذف - مع التحقق من الصلاحيات
            if (h.btnEdit != null) {
                if (canEditUser(u.role)) {
                    h.btnEdit.setVisibility(View.VISIBLE);
                    h.btnEdit.setOnClickListener(v -> showUserDialog(u));
                } else {
                    h.btnEdit.setVisibility(View.GONE);
                }
            }
            
            if (h.btnDelete != null) {
                if (canDeleteUser(u.role)) {
                    h.btnDelete.setVisibility(View.VISIBLE);
                    h.btnDelete.setOnClickListener(v -> deleteUser(u.id, u.displayName, u.role));
                } else {
                    h.btnDelete.setVisibility(View.GONE);
                }
            }
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        private String getRoleAr(String role) {
            for (int i = 0; i < ROLES.length; i++) {
                if (ROLES[i].equals(role)) return ROLES_AR[i];
            }
            return role;
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvDisplayName, tvUsername, tvRole, tvDepartment, tvStatus, tvBranchName;
            View vStatusDot;
            ImageButton btnEdit, btnDelete;

            VH(View v) {
                super(v);
                tvDisplayName = v.findViewById(R.id.tvRowDisplayName);
                tvUsername = v.findViewById(R.id.tvRowUsername);
                tvRole = v.findViewById(R.id.tvRowRole);
                tvDepartment = v.findViewById(R.id.tvRowDepartment);
                tvStatus = v.findViewById(R.id.tvRowStatus);
                tvBranchName = v.findViewById(R.id.tvRowBranchName);
                vStatusDot = v.findViewById(R.id.vStatusDot);
                btnEdit = v.findViewById(R.id.btnRowEdit);
                btnDelete = v.findViewById(R.id.btnRowDelete);
            }
        }
    }
    }
