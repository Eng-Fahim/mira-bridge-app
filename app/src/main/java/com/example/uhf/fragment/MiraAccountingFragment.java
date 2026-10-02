package com.example.uhf.fragment;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.google.android.material.tabs.TabLayout;

/**
 * نسخة اختبارية — للتحقق من أن الشاشة تعمل
 * بعد التأكد، سنعيد الكود الكامل
 */
public class MiraAccountingFragment extends Fragment {

    private static final String TAG = "MiraAccTest";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        
        Log.e(TAG, "🔵 onCreateView START");

        // ═══ Layout اختبار — بسيط جدًا ═══
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F6FA);

        // عنوان
        TextView tvTitle = new TextView(requireContext());
        tvTitle.setText("✅ شاشة المحاسبة تعمل!");
        tvTitle.setTextSize(22);
        tvTitle.setTextColor(0xFF2C3E50);
        tvTitle.setPadding(40, 80, 40, 40);
        root.addView(tvTitle);

        // رسالة
        TextView tvMessage = new TextView(requireContext());
        tvMessage.setText(
            "إذا رأيت هذه الشاشة، فهذا يعني:\n\n" +
            "✅ MiraAccountingFragment يعمل\n" +
            "✅ FragmentFactory يعرف \"Accounting\"\n" +
            "✅ NavigationManager يعمل\n\n" +
            "المشكلة كانت في:\n" +
            "❌ Layout fragment_mira_accounting_v2.xml\n" +
            "❌ أو FragmentContainer\n" +
            "❌ أو الشاشات الفرعية (7 fragments)\n\n" +
            "الخطوة التالية: سنعيد الميزات واحدة تلو الأخرى"
        );
        tvMessage.setTextSize(15);
        tvMessage.setTextColor(0xFF34495E);
        tvMessage.setPadding(40, 20, 40, 40);
        tvMessage.setLineSpacing(6, 1);
        root.addView(tvMessage);

        // زر اختبار
        com.google.android.material.button.MaterialButton btnTest = 
            new com.google.android.material.button.MaterialButton(requireContext());
        btnTest.setText("🔙 العودة للرئيسية");
        btnTest.setPadding(40, 40, 40, 40);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        btnParams.setMargins(40, 40, 40, 40);
        btnTest.setLayoutParams(btnParams);
        btnTest.setOnClickListener(v -> {
            requireActivity().onBackPressed();
        });
        root.addView(btnTest);

        Log.e(TAG, "✅ Test layout created");

        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.e(TAG, "🟢 onResume");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Log.e(TAG, "🔴 onDestroyView");
    }
}
