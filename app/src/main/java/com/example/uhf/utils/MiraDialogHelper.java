// MiraDialogHelper.java
package com.example.uhf.utils;

import android.content.Context;
import android.graphics.drawable.Drawable;
import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
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
    public AlertDialog showConfirmationDialog(
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
    public AlertDialog showInfoDialog(String title, String message) {
        return new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(isArabic ? "موافق" : "OK", null)
                .show();
    }
    
    // ✅ نافذة خطأ موحدة
    public AlertDialog showErrorDialog(String title, String message) {
        return new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setIcon(R.drawable.ic_error_outline)
                .setPositiveButton(isArabic ? "موافق" : "OK", null)
                .show();
    }
    
    // ✅ نافذة تحذير موحدة
    public AlertDialog showWarningDialog(String title, String message) {
        return new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setIcon(R.drawable.ic_warning)
                .setPositiveButton(isArabic ? "حسناً" : "OK", null)
                .show();
    }
    
    // ✅ نافذة اختيار موحدة
    public AlertDialog showOptionsDialog(
            String title, 
            String[] options,
            android.content.DialogInterface.OnClickListener listener) {
        
        return new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setItems(options, listener)
                .show();
    }
    
    // ✅ نافذة تحميل موحدة
    public AlertDialog showLoadingDialog(String title, String message) {
        return new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .show();
    }
}
