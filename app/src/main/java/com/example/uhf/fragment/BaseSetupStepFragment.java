package com.example.uhf.fragment;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.example.uhf.activity.UHFMainActivity;

/**
 * ✅ فئة أساسية لكل خطوات الإعداد المحاسبي
 *  - إخفاء BottomNav
 *  - التنقل للخلف بأمان (popBackStack)
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

    protected void hideBottomNav() {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setBottomNavVisible(false);
            }
        } catch (Exception ignored) {}
    }

    /**
     * ✅ الانتقال للخطوة السابقة بأمان:
     *  - إذا في back stack → popBackStack()
     *  - لا → نستخدم onBackPressed() كاحتياط
     */
    protected void goToPreviousStep() {
        try {
            FragmentManager fm = getParentFragmentManager();
            if (fm.getBackStackEntryCount() > 0) {
                fm.popBackStack();
                return;
            }
        } catch (Exception ignored) {}

        requireActivity().onBackPressed();
    }
}
