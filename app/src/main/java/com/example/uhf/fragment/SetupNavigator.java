package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.example.uhf.R;

/**
 * SetupNavigator — التنقل المركزي في خطوات الإعداد
 *
 * - ينتقل للخطوة التالية تلقائياً بعد الحفظ
 * - يزيل الخطوة الحالية من backstack (لتجنب التكرار)
 * - Back من أي خطوة → Setup الرئيسية
 */
public final class SetupNavigator {

    private SetupNavigator() {}

    /**
     * جلب fragment للخطوة المطلوبة
     */
    public static Fragment getFragmentForStep(int step) {
        switch (step) {
            case 1:  return new SetupStep1FiscalYearFragment();
            case 2:  return new SetupStep2BranchesFragment();
            case 3:  return new SetupStep3CurrenciesFragment();
            case 4:  return new SetupStep4GoldPricesFragment();
            case 5:  return new SetupStep5MakingChargesFragment();
            case 6:  return new SetupStep6OpeningBalancesFragment();
            case 7:  return new SetupStep7UsersPermissionsFragment();
            case 8:  return new SetupStep8ZakatFragment();
            case 9:  return new SetupStep9RfidFragment();
            case 10: return new SetupStep10ReviewFragment();
            default: return null;
        }
    }

    /**
     * الانتقال للخطوة التالية
     *
     * @param current الخطوة الحالية
     * @param currentStepNumber رقم الخطوة الحالية (1-9)
     */
    public static void goToNextStep(Fragment current, int currentStepNumber) {
        if (current == null || !current.isAdded() || current.getActivity() == null) return;

        int nextStep = currentStepNumber + 1;
        Fragment nextFragment = getFragmentForStep(nextStep);

        if (nextFragment == null) {
            // لا توجد خطوة تالية (فقط لو استُدعيت من Step 10)
            current.getParentFragmentManager().popBackStack();
            return;
        }

        Bundle args = new Bundle();
        args.putInt("setup_step", nextStep);
        nextFragment.setArguments(args);

        final FragmentManager fm = current.getParentFragmentManager();
        final Fragment finalNext = nextFragment;
        final int finalNextStep = nextStep;

        // نستخدم Handler لتنفيذ التنقل بعد اكتمال العملية الحالية
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                // احذف الخطوة الحالية
                fm.popBackStackImmediate();
                // أضف الخطوة التالية
                fm.beginTransaction()
                        .replace(R.id.fragment_container, finalNext, "SetupStep" + finalNextStep)
                        .addToBackStack("SetupStep" + finalNextStep)
                        .commit();
            } catch (Exception e) {
                android.util.Log.e("SetupNavigator",
                        "Navigation error: " + e.getMessage());
            }
        });
    }
}
