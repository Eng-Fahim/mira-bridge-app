package com.example.uhf.fragment;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * ⚠️ SHIM v1 — هذا الملف أُفرغ مؤقتاً.
 *
 * السبب: إدارة المستخدمين نُقلت إلى لوحة التحكم على الويب.
 * احتفظنا بالـ class لتجنب كسر المراجع:
 *   - MiraQuickAccessGridFragment.java:186
 *   - NavigationManager.java:438
 *
 * بعد أسبوعين من التأكد (لا أخطاء، لا شكاوى):
 *   - احذف هذا الملف
 *   - احذف المراجع
 *   - احذف fragment_users_management.xml
 */
public class UsersManagementFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        TextView tv = new TextView(requireContext());
        tv.setText("👥 إدارة المستخدمين\n\n"
                 + "هذه الشاشة متاحة الآن عبر لوحة التحكم على الويب.\n"
                 + "يرجى تسجيل الدخول من المتصفح.");
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(64, 64, 64, 64);
        tv.setTextSize(16);
        return tv;
    }
}
