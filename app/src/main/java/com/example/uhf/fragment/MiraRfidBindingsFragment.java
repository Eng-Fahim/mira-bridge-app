package com.example.uhf.fragment;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * ⚠️ SHIM v1 — هذا الملف أُفرغ مؤقتاً.
 *
 * السبب: إدارة الشرائح المبرمجة نُقلت إلى لوحة التحكم على الويب.
 * احتفظنا بالـ class لتجنب كسر المراجع:
 *   - UHFMainActivity (import)
 *   - ActivityDetailsBottomSheet (navigateTo)
 *   - NavigationManager (case)
 *   - FragmentFactory (case)
 *
 * بعد أسبوعين من التأكد:
 *   - احذف هذا الملف
 *   - احذف المراجع المعلّقة
 *   - احذف layout/fragment_mira_rfid_bindings.xml
 */
public class MiraRfidBindingsFragment extends MiraBaseFragment {

    private static final String TAG = "MiraRfidBindings";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        TextView tv = new TextView(requireContext());
        tv.setText("📡 إدارة الشرائح المبرمجة\n\n"
                 + "هذه الشاشة متاحة الآن عبر لوحة التحكم على الويب.\n"
                 + "يرجى تسجيل الدخول من المتصفح.");
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(64, 64, 64, 64);
        tv.setTextSize(16);
        return tv;
    }
}
