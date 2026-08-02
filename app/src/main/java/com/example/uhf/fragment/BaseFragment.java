package com.example.uhf.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * 🎯 BaseFragment - الكلاس الأساسي لجميع Fragments
 * يوفر:
 * - إخفاء لوحة المفاتيح تلقائياً عند اللمس خارج الحقول
 * - دالة hideKeyboard() متاحة لكل الأبناء
 */
public abstract class BaseFragment extends Fragment {

    /**
     * 🟢 دوال مجردة يجب على كل Fragment تنفيذها
     */
    protected abstract int getLayoutResId();
    protected abstract void initViews(View view);
    protected abstract void setupListeners();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(getLayoutResId(), container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // ⭐ إخفاء لوحة المفاتيح تلقائياً
        setupAutoHideKeyboard(view);
        
        // تهيئة الواجهة
        initViews(view);
        
        // ربط الأحداث
        setupListeners();
    }

    // ===================== ⌨️ Keyboard =====================

    private void setupAutoHideKeyboard(View view) {
        if (!(view instanceof EditText)) {
            view.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    hideKeyboard();
                    v.clearFocus();
                }
                return false;
            });
        }

        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                setupAutoHideKeyboard(viewGroup.getChildAt(i));
            }
        }
    }

    public void hideKeyboard() {
        if (getActivity() == null) return;
        View currentFocus = getActivity().getCurrentFocus();
        if (currentFocus != null) {
            InputMethodManager imm = (InputMethodManager) getActivity()
                    .getSystemService(getActivity().INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }
            currentFocus.clearFocus();
        }
    }
}
