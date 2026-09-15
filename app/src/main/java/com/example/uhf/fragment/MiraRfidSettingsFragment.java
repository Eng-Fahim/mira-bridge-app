package com.example.uhf.fragment;

import android.os.Bundle;
import android.text.InputFilter;
import android.text.Spanned;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.MiraRfid.MiraRfidService;
import com.example.uhf.R;
import com.example.uhf.manager.RfidPasswordManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * MiraRfidSettingsFragment - واجهة إعدادات أمان شرائح RFID
 * 
 * ✅ الميزات:
 * - اختيار وضع كلمة المرور (STATIC / DERIVED / SERVER)
 * - تعيين كلمة مرور ثابتة (مع إخفاء القيمة)
 * - اختبار فعلي على شريحة قريبة
 * - تصدير/استيراد Salt (للنسخ الاحتياطي)
 * - عرض معلومات تشخيصية
 * 
 * ✅ التسمية: MiraRfidSettingsFragment متسقة مع باقي ملفات المشروع
 */
public class MiraRfidSettingsFragment extends Fragment {

    private static final String TAG = "MiraRfidSettingsFragment";

    private RfidPasswordManager pwdManager;
    private MiraRfidService rfidService;

    private RadioButton rbModeStatic, rbModeDerived, rbModeServer;
    private TextInputLayout tilPassword;
    private TextInputEditText etPassword;
    private MaterialButton btnSave, btnTest, btnExportSalt, btnImportSalt, btnReset;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_rfid_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            pwdManager = RfidPasswordManager.getInstance(requireContext());
            rfidService = MiraRfidService.getInstance(requireContext());
        } catch (Exception e) {
            Log.e(TAG, "Failed to init managers", e);
        }

        // ربط العناصر
        rbModeStatic = view.findViewById(R.id.rbModeStatic);
        rbModeDerived = view.findViewById(R.id.rbModeDerived);
        rbModeServer = view.findViewById(R.id.rbModeServer);
        tilPassword = view.findViewById(R.id.tilPassword);
        etPassword = view.findViewById(R.id.etPassword);
        btnSave = view.findViewById(R.id.btnSavePassword);
        btnTest = view.findViewById(R.id.btnTestPassword);
        btnExportSalt = view.findViewById(R.id.btnExportSalt);
        btnImportSalt = view.findViewById(R.id.btnImportSalt);
        btnReset = view.findViewById(R.id.btnResetAll);

        // ✅ تقييد الإدخال: 8 حروف Hex فقط
        etPassword.setFilters(new InputFilter[]{
            new InputFilter.LengthFilter(8),
            new InputFilter() {
                @Override
                public CharSequence filter(CharSequence source, int start, int end,
                                           Spanned dest, int dstart, int dend) {
                    for (int i = start; i < end; i++) {
                        char c = source.charAt(i);
                        if (!isHexChar(c)) {
                            return "";
                        }
                    }
                    return null;
                }
                private boolean isHexChar(char c) {
                    return (c >= '0' && c <= '9') ||
                           (c >= 'A' && c <= 'F') ||
                           (c >= 'a' && c <= 'f');
                }
            }
        });

        loadCurrentSettings();

        // ==================== المستمعون ====================
        btnSave.setOnClickListener(v -> saveSettings());
        btnTest.setOnClickListener(v -> testOnTag());
        btnExportSalt.setOnClickListener(v -> exportSalt());
        btnImportSalt.setOnClickListener(v -> importSalt());
        btnReset.setOnClickListener(v -> confirmReset());

        rbModeStatic.setOnCheckedChangeListener((v, checked) -> {
            if (checked) updateUIMode(RfidPasswordManager.PasswordMode.STATIC);
        });
        rbModeDerived.setOnCheckedChangeListener((v, checked) -> {
            if (checked) updateUIMode(RfidPasswordManager.PasswordMode.DERIVED_FROM_TID);
        });
        rbModeServer.setOnCheckedChangeListener((v, checked) -> {
            if (checked) updateUIMode(RfidPasswordManager.PasswordMode.FROM_SERVER);
        });
    }

    // =========================================================================
    // 📋 تحميل وعرض الإعدادات
    // =========================================================================

    private void loadCurrentSettings() {
        if (pwdManager == null) return;

        RfidPasswordManager.PasswordMode mode = pwdManager.getPasswordMode();
        updateUIMode(mode);

        if (pwdManager.hasDefaultPassword()) {
            etPassword.setText("••••••••");
            etPassword.setEnabled(false);
            tilPassword.setHelperText(getString(R.string.pwd_saved_encrypted));
        } else {
            tilPassword.setHelperText(getString(R.string.pwd_hint_8_hex));
        }
    }

    private void updateUIMode(RfidPasswordManager.PasswordMode mode) {
        switch (mode) {
            case STATIC:
                rbModeStatic.setChecked(true);
                tilPassword.setVisibility(View.VISIBLE);
                break;
            case DERIVED_FROM_TID:
                rbModeDerived.setChecked(true);
                tilPassword.setVisibility(View.GONE);
                break;
            case FROM_SERVER:
                rbModeServer.setChecked(true);
                tilPassword.setVisibility(View.GONE);
                break;
        }
    }

    // =========================================================================
    // 💾 حفظ الإعدادات
    // =========================================================================

    private void saveSettings() {
        if (pwdManager == null) return;

        // 1) تحديد الوضع
        RfidPasswordManager.PasswordMode mode;
        if (rbModeStatic.isChecked()) {
            mode = RfidPasswordManager.PasswordMode.STATIC;
        } else if (rbModeDerived.isChecked()) {
            mode = RfidPasswordManager.PasswordMode.DERIVED_FROM_TID;
        } else {
            mode = RfidPasswordManager.PasswordMode.FROM_SERVER;
        }

        // 2) حفظ كلمة المرور (فقط في وضع STATIC)
        if (mode == RfidPasswordManager.PasswordMode.STATIC) {
            String pwd = etPassword.getText() != null ?
                    etPassword.getText().toString().trim() : "";

            if (pwd.equals("••••••••")) {
                if (!pwdManager.hasDefaultPassword()) {
                    showToast(getString(R.string.pwd_enter_password));
                    return;
                }
            } else {
                if (pwd.isEmpty()) {
                    showToast(getString(R.string.pwd_enter_password));
                    return;
                }
                if (pwd.length() != 8) {
                    showToast(getString(R.string.pwd_must_be_8_hex));
                    return;
                }
                if (!pwd.matches("^[0-9A-Fa-f]{8}$")) {
                    showToast(getString(R.string.pwd_must_be_hex));
                    return;
                }

                try {
                    pwdManager.setDefaultPassword(pwd);
                } catch (IllegalArgumentException e) {
                    showToast("❌ " + e.getMessage());
                    return;
                }
            }
        }

        // 3) حفظ الوضع
        pwdManager.setPasswordMode(mode);

        // 4) إعادة تحميل الواجهة
        etPassword.setText("••••••••");
        etPassword.setEnabled(false);
        tilPassword.setHelperText(getString(R.string.pwd_saved_encrypted));

        showToast(getString(R.string.pwd_saved_securely));
        Log.i(TAG, "✅ Settings saved: mode=" + mode);
    }

    // =========================================================================
    // 🧪 اختبار على شريحة
    // =========================================================================

    private void testOnTag() {
        if (pwdManager == null || rfidService == null) return;

        if (!pwdManager.hasDefaultPassword() &&
            pwdManager.getPasswordMode() == RfidPasswordManager.PasswordMode.STATIC) {
            showToast(getString(R.string.pwd_save_first));
            return;
        }

        showToast(getString(R.string.pwd_reading_tag));

        new Thread(() -> {
            try {
                String tid = rfidService.readTid(null);

                if (getActivity() == null) return;

                getActivity().runOnUiThread(() -> {
                    if (tid != null && !tid.isEmpty()) {
                        String pwd = pwdManager.getPasswordForTag(tid);
                        showPasswordDialog(tid, pwd);
                    } else {
                        showToast(getString(R.string.pwd_no_tag_found));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "testOnTag error", e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                        showToast("❌ " + e.getMessage()));
                }
            }
        }, "Pwd-Test-Thread").start();
    }

    private void showPasswordDialog(String tid, String pwd) {
        if (getContext() == null) return;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.pwd_test_result))
                .setMessage(
                    getString(R.string.pwd_tid_label) + " " + maskTid(tid) + "\n\n" +
                    getString(R.string.pwd_password_label) + " " + pwd + "\n\n" +
                    getString(R.string.pwd_mode_label_result) + " " + pwdManager.getPasswordMode()
                )
                .setPositiveButton(getString(R.string.pwd_copy), (d, w) -> {
                    android.content.ClipboardManager cm = 
                        (android.content.ClipboardManager) requireContext()
                            .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Password", pwd));
                        showToast(getString(R.string.pwd_copied));
                    }
                })
                .setNegativeButton(getString(R.string.pwd_close), null)
                .show();
    }

    private String maskTid(String tid) {
        if (tid == null || tid.length() < 8) return "***";
        return tid.substring(0, 4) + "****" + tid.substring(tid.length() - 4);
    }

    // =========================================================================
    // 📤 تصدير / استيراد Salt
    // =========================================================================

    private void exportSalt() {
        if (pwdManager == null || getContext() == null) return;

        String salt = pwdManager.exportSalt();
        if (salt == null || salt.isEmpty()) {
            showToast(getString(R.string.pwd_no_salt));
            return;
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.pwd_export_salt_title))
                .setMessage(
                    getString(R.string.pwd_export_salt_warning) + "\n\n" + salt
                )
                .setPositiveButton(getString(R.string.pwd_copy), (d, w) -> {
                    android.content.ClipboardManager cm = 
                        (android.content.ClipboardManager) requireContext()
                            .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Salt", salt));
                        showToast(getString(R.string.pwd_salt_copied));
                    }
                })
                .setNegativeButton(getString(R.string.pwd_close), null)
                .show();
    }

    private void importSalt() {
        if (pwdManager == null || getContext() == null) return;

        android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setHint(getString(R.string.pwd_paste_salt_hint));
        input.setTextSize(12f);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.pwd_import_salt_title))
                .setMessage(getString(R.string.pwd_import_salt_warning))
                .setView(input)
                .setPositiveButton(getString(R.string.pwd_import), (d, w) -> {
                    String salt = input.getText().toString().trim();
                    if (salt.isEmpty()) {
                        showToast(getString(R.string.pwd_empty_salt));
                        return;
                    }
                    pwdManager.importSalt(salt);
                    showToast(getString(R.string.pwd_salt_imported));
                })
                .setNegativeButton(getString(R.string.pwd_cancel), null)
                .show();
    }

    // =========================================================================
    // 🔄 إعادة تعيين
    // =========================================================================

    private void confirmReset() {
        if (getContext() == null) return;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.pwd_reset_title))
                .setMessage(getString(R.string.pwd_reset_warning))
                .setPositiveButton(getString(R.string.pwd_reset_confirm), (d, w) -> {
                    if (pwdManager != null) {
                        pwdManager.resetAll();
                    }
                    loadCurrentSettings();
                    etPassword.setText("");
                    etPassword.setEnabled(true);
                    showToast(getString(R.string.pwd_reset_done));
                })
                .setNegativeButton(getString(R.string.pwd_cancel), null)
                .show();
    }

    // =========================================================================
    // 🛠️ أدوات مساعدة
    // =========================================================================

    private void showToast(String msg) {
        try {
            if (getContext() != null && isAdded()) {
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "showToast error", e);
        }
    }
}
