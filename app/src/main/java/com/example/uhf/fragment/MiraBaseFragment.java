package com.example.uhf.fragment;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.example.uhf.tools.MiraDialogHelper;

import java.util.Locale;

/**
 * 🏗️ BaseFragment - الفрагмент الأساسي لجميع صفحات التطبيق
 * يوفر:
 * - إخفاء لوحة المفاتيح تلقائياً
 * - دوال Dialog موحدة
 * - دعم اللغة العربية
 */
public abstract class BaseFragment extends Fragment {

    protected boolean isArabic;
    protected MiraDialogHelper dialogHelper;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        dialogHelper = MiraDialogHelper.getInstance(requireContext());
        dialogHelper.setArabic(isArabic);
        
        setupAutoHideKeyboard(view);
    }

    // ==================== دوال Dialog المساعدة ====================

    protected AlertDialog showSuccessDialog(String title, String message) {
        return dialogHelper.showSuccess(title, message);
    }

    protected AlertDialog showSuccessDialog(String title, String message,
                                             DialogInterface.OnClickListener listener) {
        return dialogHelper.builder()
                .setType(MiraDialogHelper.DialogType.SUCCESS)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(isArabic ? "حسناً" : "OK", listener)
                .show();
    }

    protected AlertDialog showErrorDialog(String title, String message) {
        return dialogHelper.showError(title, message);
    }

    protected AlertDialog showErrorDialog(String title, String message,
                                           DialogInterface.OnClickListener listener) {
        return dialogHelper.builder()
                .setType(MiraDialogHelper.DialogType.ERROR)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(isArabic ? "حسناً" : "OK", listener)
                .show();
    }

    protected AlertDialog showWarningDialog(String title, String message) {
        return dialogHelper.showWarning(title, message);
    }

    protected AlertDialog showInfoDialog(String title, String message) {
        return dialogHelper.showInfo(title, message);
    }

    protected AlertDialog showConfirmDialog(String title, String message,
                                             DialogInterface.OnClickListener positiveListener) {
        return dialogHelper.showConfirm(title, message, positiveListener);
    }

    protected AlertDialog showConfirmDialog(String title, String message,
                                             String positiveText, String negativeText,
                                             DialogInterface.OnClickListener positiveListener,
                                             DialogInterface.OnClickListener negativeListener) {
        return dialogHelper.showConfirm(title, message, positiveText, negativeText,
                positiveListener, negativeListener);
    }

    protected AlertDialog showLoadingDialog(String message) {
        return dialogHelper.showLoading(message);
    }

    protected AlertDialog showDetailsDialog(String title, String[][] details) {
        return dialogHelper.showDetails(title, details);
    }

    protected void dismissDialog(AlertDialog dialog) {
        dialogHelper.dismiss(dialog);
    }

    // ==================== إخفاء لوحة المفاتيح تلقائياً ====================

    /**
     * إعداد إخفاء لوحة المفاتيح عند النقر خارج EditText
     * يعمل بشكل متكرر على جميع العناصر الفرعية
     */
    private void setupAutoHideKeyboard(View view) {
        if (view == null) return;

        if (!(view instanceof EditText)) {
            view.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    hideKeyboard();
                    v.performClick();
                }
                return false;
            });
        }

        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setupAutoHideKeyboard(group.getChildAt(i));
            }
        }
    }

    /**
     * إخفاء لوحة المفاتيح
     */
    protected void hideKeyboard() {
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
    }

    /**
     * إظهار لوحة المفاتيح لحقل معين
     */
    protected void showKeyboard(EditText editText) {
        if (editText == null) return;
        editText.requestFocus();
        editText.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) 
                editText.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 200);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        hideKeyboard();
    }
}
