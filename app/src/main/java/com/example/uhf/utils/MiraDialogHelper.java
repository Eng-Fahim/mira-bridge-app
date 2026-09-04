package com.example.uhf.utils;

import android.content.Context;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.example.uhf.R;

public class MiraDialogHelper {
    
    private final Context context;
    private boolean isArabic;
    
    public MiraDialogHelper(Context context) {
        this.context = context;
        this.isArabic = context.getResources().getConfiguration().locale.getLanguage().equals("ar");
    }
    
    // ✅ نافذة تأكيد موحدة
    public AlertDialog showConfirmation(
            String title, 
            String message, 
            String positiveText, 
            String negativeText,
            Runnable onPositive) {
        
        return new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positiveText, (dialog, which) -> {
                    if (onPositive != null) onPositive.run();
                })
                .setNegativeButton(negativeText, null)
                .show();
    }
    
    // ✅ نافذة معلومات موحدة
    public AlertDialog showInfo(String title, String message) {
        return new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(isArabic ? "موافق" : "OK", null)
                .show();
    }
    
    // ✅ نافذة خطأ موحدة
    public AlertDialog showError(String title, String message) {
        return new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setIcon(R.drawable.ic_error_outline)
                .setPositiveButton(isArabic ? "موافق" : "OK", null)
                .show();
    }
    
    // ✅ نافذة تحذير موحدة
    public AlertDialog showWarning(String title, String message) {
        return new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setIcon(R.drawable.ic_warning)
                .setPositiveButton(isArabic ? "حسناً" : "OK", null)
                .show();
    }
    
    // ✅ نافذة اختيار موحدة
    public AlertDialog showOptions(
            String title, 
            String[] options,
            android.content.DialogInterface.OnClickListener listener) {
        
        return new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setItems(options, listener)
                .show();
    }
    
    // ✅ نافذة تحميل موحدة
    public AlertDialog showLoading(String title, String message) {
        return new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .show();
    }
    
    // ✅ نافذة مخصصة كاملة
    public AlertDialog showCustom(
            String title,
            String message,
            String positiveText,
            String negativeText,
            String neutralText,
            Runnable onPositive,
            Runnable onNegative,
            Runnable onNeutral) {
        
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message);
        
        if (positiveText != null) {
            builder.setPositiveButton(positiveText, (d, w) -> {
                if (onPositive != null) onPositive.run();
            });
        }
        
        if (negativeText != null) {
            builder.setNegativeButton(negativeText, (d, w) -> {
                if (onNegative != null) onNegative.run();
            });
        }
        
        if (neutralText != null) {
            builder.setNeutralButton(neutralText, (d, w) -> {
                if (onNeutral != null) onNeutral.run();
            });
        }
        
        return builder.show();
    }
}
