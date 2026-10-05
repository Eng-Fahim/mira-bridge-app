package com.example.uhf.fragment;

import androidx.fragment.app.Fragment;

import com.example.uhf.activity.UHFMainActivity;

/**
 * ✅ فئة أساسية لكل خطوات الإعداد المحاسبي
 *
 * الوظيفة:
 * - إخفاء BottomNav عند دخول أي خطوة
 * - إخفاء BottomNav عند العودة (onResume)
 * - لا يُظهرها أبدًا — UHFMainActivity.onSetupCompleted() مسؤول عن الإظهار
 *
 * الاستخدام:
 * public class SetupStep1FiscalYearFragment extends BaseSetupStepFragment { ... }
 */
public abstract class BaseSetupStepFragment extends Fragment {

    @Override
    public void onStart() {
        super.onStart();
        hideBottomNav();
    }

    @Override
    public void onResume() {
        super.onResume();
        hideBottomNav();
    }

    /**
     * إخفاء BottomNav — يعمل حتى لو استُدعي عدة مرات
     */
    protected void hideBottomNav() {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setBottomNavVisible(false);
            }
        } catch (Exception ignored) {
            // حماية صامتة
        }
    }
}
