package com.example.uhf.fragment;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;              // ✅ [جديد] مطلوب لـ myOnKeyDwon
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.example.uhf.tools.MiraDialogHelper;
import com.example.uhf.tools.MiraLanguageHelper;

/**
 * 🏗️ MiraBaseFragment - يمتد من KeyDwonFragment
 * لاستقبال الزناد الفيزيائي في جميع الفئات الفرعية
 */
public abstract class MiraBaseFragment extends KeyDwonFragment {

    private static final String TAG = "MiraBaseFragment";

    protected boolean isArabic;
    protected MiraDialogHelper dialogHelper;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            isArabic = MiraLanguageHelper.isArabic(requireContext());
        } catch (Exception e) {
            isArabic = java.util.Locale.getDefault().getLanguage().equals("ar");
        }

        applyLanguageDirection(view);

        try {
            if (getContext() != null) {
                dialogHelper = MiraDialogHelper.getInstance(getContext());
                dialogHelper.setArabic(isArabic);
            }
        } catch (Exception e) {
            Log.e(TAG, "dialogHelper init error: " + e.getMessage());
        }
    }

    // ==================== اتجاه اللغة ====================

    protected void applyLanguageDirection(View view) {
        if (view == null) return;

        view.setLayoutDirection(isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyLanguageDirection(group.getChildAt(i));
            }
        }
    }

    // ==================== دوال Dialog المساعدة ====================

    protected AlertDialog showSuccessDialog(String title, String message) {
        return showSuccessDialog(title, message, null);
    }

    protected AlertDialog showSuccessDialog(String title, String message,
                                             DialogInterface.OnClickListener listener) {
        try {
            if (dialogHelper == null) return null;
            return dialogHelper.builder()
                    .setType(MiraDialogHelper.DialogType.SUCCESS)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(isArabic ? "حسناً" : "OK", listener)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showSuccessDialog error: " + e.getMessage());
            return null;
        }
    }

    protected AlertDialog showErrorDialog(String title, String message) {
        return showErrorDialog(title, message, null);
    }

    protected AlertDialog showErrorDialog(String title, String message,
                                           DialogInterface.OnClickListener listener) {
        try {
            if (dialogHelper == null) return null;
            return dialogHelper.builder()
                    .setType(MiraDialogHelper.DialogType.ERROR)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(isArabic ? "حسناً" : "OK", listener)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showErrorDialog error: " + e.getMessage());
            return null;
        }
    }

    protected AlertDialog showWarningDialog(String title, String message) {
        try {
            if (dialogHelper == null) return null;
            return dialogHelper.showWarning(title, message);
        } catch (Exception e) {
            Log.e(TAG, "showWarningDialog error: " + e.getMessage());
            return null;
        }
    }

    protected AlertDialog showInfoDialog(String title, String message) {
        try {
            if (dialogHelper == null) return null;
            return dialogHelper.showInfo(title, message);
        } catch (Exception e) {
            Log.e(TAG, "showInfoDialog error: " + e.getMessage());
            return null;
        }
    }

    protected AlertDialog showConfirmDialog(String title, String message,
                                             DialogInterface.OnClickListener positiveListener) {
        try {
            if (dialogHelper == null) return null;
            return dialogHelper.showConfirm(title, message, positiveListener);
        } catch (Exception e) {
            Log.e(TAG, "showConfirmDialog error: " + e.getMessage());
            return null;
        }
    }

    protected AlertDialog showConfirmDialog(String title, String message,
                                             String positiveText, String negativeText,
                                             DialogInterface.OnClickListener positiveListener,
                                             DialogInterface.OnClickListener negativeListener) {
        try {
            if (dialogHelper == null) return null;
            return dialogHelper.showConfirm(title, message, positiveText, negativeText,
                    positiveListener, negativeListener);
        } catch (Exception e) {
            Log.e(TAG, "showConfirmDialog error: " + e.getMessage());
            return null;
        }
    }

    protected AlertDialog showLoadingDialog(String message) {
        try {
            if (dialogHelper == null || getContext() == null || !isAdded()) {
                return null;
            }
            return dialogHelper.showLoading(message);
        } catch (Exception e) {
            Log.e(TAG, "showLoadingDialog error: " + e.getMessage());
            return null;
        }
    }

    protected AlertDialog showDetailsDialog(String title, String[][] details) {
        try {
            if (dialogHelper == null || getContext() == null || !isAdded()) {
                return null;
            }
            return dialogHelper.showDetails(title, details);
        } catch (Exception e) {
            Log.e(TAG, "showDetailsDialog error: " + e.getMessage());
            return null;
        }
    }

    protected void dismissDialog(AlertDialog dialog) {
        try {
            if (dialogHelper != null) {
                dialogHelper.dismiss(dialog);
            } else if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
        } catch (Exception e) {
            Log.e(TAG, "dismissDialog error: " + e.getMessage());
        }
    }

    // ==================== إخفاء لوحة المفاتيح ====================

    private void setupAutoHideKeyboard(View view) {
        if (view == null) return;

        if (view.isClickable() && !(view instanceof EditText)) {
            view.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    hideKeyboard();
                }
                return false;
            });
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int childCount = Math.min(group.getChildCount(), 20);
            for (int i = 0; i < childCount; i++) {
                setupAutoHideKeyboard(group.getChildAt(i));
            }
        }
    }

    protected void hideKeyboard() {
        try {
            if (getActivity() == null) return;
            View view = getActivity().getCurrentFocus();
            if (view != null) {
                InputMethodManager imm = (InputMethodManager)
                        getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
                    view.clearFocus();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "hideKeyboard error: " + e.getMessage());
        }
    }

    protected void showKeyboard(EditText editText) {
        try {
            if (editText == null) return;
            editText.requestFocus();
            editText.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager)
                        editText.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 200);
        } catch (Exception e) {
            Log.e(TAG, "showKeyboard error: " + e.getMessage());
        }
    }

    /**
     * ✅ تنفيذ افتراضي — الفئات الفرعية تُOverride عند الحاجة
     */
    @Override
    public boolean myOnKeyDwon(int keyCode, KeyEvent event) {
        return false;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        hideKeyboard();
    }
}
