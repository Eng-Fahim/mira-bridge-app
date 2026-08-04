package com.example.uhf.fragment;

import android.app.AlertDialog;
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
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;

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

    private static final String[] ROLES = {"ADMIN", "INVENTORY_MGR", "POS_OPERATOR", "SECURITY", "GATE_SECURITY", "AUDITOR"};
    private static final String[] ROLES_AR = {"مدير النظام", "مدير المخزون", "موظف مبيعات", "أمن", "أمن البوابات", "مدقق"};

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
        int active = 0;
        int online = 0;
        for (UserData u : userList) {
            if ("active".equals(u.status)) active++;
            if (u.isOnline) online++;
        }
        tvTotalUsers.setText(String.valueOf(total));
        tvActiveUsers.setText(String.valueOf(active));
        tvOnlineNow.setText(String.valueOf(online));
    }

    private void showUserDialog(@Nullable UserData existingUser) {
    AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
    View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_user_edit, null);
    builder.setView(dialogView);

    TextView tvTitle = dialogView.findViewById(R.id.tvDialogTitle);
    EditText etDisplayName = dialogView.findViewById(R.id.etDisplayName);
    EditText etUsername = dialogView.findViewById(R.id.etUsername);
    EditText etPassword = dialogView.findViewById(R.id.etPassword);
    EditText etEmployeeId = dialogView.findViewById(R.id.etEmployeeId);
    EditText etDepartment = dialogView.findViewById(R.id.etDepartment);
    RadioGroup rgRole = dialogView.findViewById(R.id.rgRole);
    Button btnCancel = dialogView.findViewById(R.id.btnCancelUser);
    Button btnSave = dialogView.findViewById(R.id.btnSaveUser);

    boolean isEdit = existingUser != null;
    tvTitle.setText(isEdit ? "تعديل مستخدم" : "إضافة مستخدم جديد");

    if (isEdit) {
        etDisplayName.setText(existingUser.displayName);
        etUsername.setText(existingUser.username);
        etUsername.setEnabled(false);
        etPassword.setHint("اتركه فارغاً إذا لم ترغب بالتغيير");
        etEmployeeId.setText(existingUser.employeeId);
        etDepartment.setText(existingUser.department);

        // تحديد الصلاحية الحالية
        for (int i = 0; i < rgRole.getChildCount(); i++) {
            View child = rgRole.getChildAt(i);
            if (child instanceof RadioButton) {
                RadioButton rb = (RadioButton) child;
                if (rb.getTag().equals(existingUser.role)) {
                    rb.setChecked(true);
                    break;
                }
            }
        }
    } else {
        // افتراضياً: مدير المخزون
        RadioButton defaultRole = dialogView.findViewById(R.id.rbInventoryMgr);
        if (defaultRole != null) defaultRole.setChecked(true);
    }

    AlertDialog dialog = builder.create();

    btnCancel.setOnClickListener(v -> dialog.dismiss());

    btnSave.setOnClickListener(v -> {
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

        // استخراج الصلاحية المختارة
        String role = "INVENTORY_MGR";
        int selectedId = rgRole.getCheckedRadioButtonId();
        if (selectedId != -1) {
            View selectedView = rgRole.findViewById(selectedId);
            if (selectedView != null && selectedView.getTag() != null) {
                role = selectedView.getTag().toString();
            }
        }

        saveUser(isEdit ? existingUser.id : 0, username, password, displayName, role, employeeId, department, isEdit);
        dialog.dismiss();
    });

    dialog.show();
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
                if (!password.isEmpty()) {
                    payload.put("password", password);
                }
                if (isEdit) {
                    payload.put("user_id", userId);
                }
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
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_user_row, parent, false);
            return new VH(v);
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
