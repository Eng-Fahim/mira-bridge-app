package com.example.uhf.tools;

import com.example.uhf.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class UIHelper {

    /**
     * 弹出Toast消息
     */
    public static void ToastMessage(Context cont, String msg) {
        Toast.makeText(cont, msg, Toast.LENGTH_SHORT).show();
    }

    public static void ToastMessage(Context cont, int msg) {
        Toast.makeText(cont, msg, Toast.LENGTH_SHORT).show();
    }

    public static void ToastMessage(Context cont, String msg, int time) {
        Toast.makeText(cont, msg, time).show();
    }

    /**
     * 显示弹出框消息
     */
    public static void alert(Activity act, int titleInt, int messageInt, int iconInt) {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(act);
            builder.setTitle(titleInt);
            builder.setMessage(messageInt);
            builder.setIcon(iconInt);
            builder.setNegativeButton(R.string.close, (dialog, which) -> dialog.dismiss());
            builder.create().show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void alert(Activity act, int titleInt, String message, int iconInt) {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(act);
            builder.setTitle(titleInt);
            builder.setMessage(message);
            builder.setIcon(iconInt);
            builder.setNegativeButton(R.string.close, (dialog, which) -> dialog.dismiss());
            builder.create().show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ===================== ⌨️ Keyboard Management =====================

    /**
     * ⌨️ إخفاء لوحة المفاتيح
     */
    public static void hideKeyboard(Activity activity) {
        if (activity == null) return;
        
        View view = activity.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        }
    }

    /**
     * ⌨️ إخفاء لوحة المفاتيح من أي View
     */
    public static void hideKeyboard(View view) {
        if (view == null) return;
        
        InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    /**
     * ⌨️ إخفاء لوحة المفاتيح عند الضغط خارج حقول النص
     * تستخدم في setupUI() لكل Fragment/Activity
     */
    public static void setupTouchToDismissKeyboard(Activity activity, View rootView) {
        if (activity == null || rootView == null) return;

        // إعداد اللمس لإخفاء لوحة المفاتيح عند الضغط خارج EditText
        if (!(rootView instanceof EditText)) {
            rootView.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    hideKeyboard(activity);
                    v.clearFocus();
                }
                return false;
            });
        }

        // معالجة متكررة لجميع العناصر الفرعية
        if (rootView instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) rootView;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View child = viewGroup.getChildAt(i);
                setupTouchToDismissKeyboard(activity, child);
            }
        }
    }

    /**
     * ⌨️ إخفاء لوحة المفاتيح مع مسح التركيز
     */
    public static void hideKeyboardAndClearFocus(Activity activity) {
        if (activity == null) return;
        
        View currentFocus = activity.getCurrentFocus();
        if (currentFocus != null) {
            currentFocus.clearFocus();
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }
        }
    }

    /**
     * ⌨️ إظهار لوحة المفاتيح
     */
    public static void showKeyboard(Activity activity, EditText editText) {
        if (activity == null || editText == null) return;
        
        editText.requestFocus();
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    /**
     * ⌨️ التحقق مما إذا كانت لوحة المفاتيح مفتوحة
     */
    public static boolean isKeyboardVisible(Activity activity) {
        if (activity == null) return false;
        
        View rootView = activity.getWindow().getDecorView().findViewById(android.R.id.content);
        if (rootView == null) return false;
        
        Rect rect = new Rect();
        rootView.getWindowVisibleDisplayFrame(rect);
        int screenHeight = rootView.getHeight();
        int keypadHeight = screenHeight - rect.bottom;
        
        return keypadHeight > screenHeight * 0.15;
    }
}
