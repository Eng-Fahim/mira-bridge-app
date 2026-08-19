package com.example.uhf.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.manager.MiraQuickAccessSettings;

import java.util.List;
import java.util.Locale;

public class MiraQuickAccessSettingsFragment extends Fragment {

    private MiraQuickAccessSettings quickAccess;
    private boolean isArabic;
    private LinearLayout containerActions;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_quick_access_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        if (getContext() == null) return;
        
        quickAccess = MiraQuickAccessSettings.getInstance(getContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        
        containerActions = view.findViewById(R.id.containerActions);
        Button btnSave = view.findViewById(R.id.btnSaveQuickAccess);
        Button btnReset = view.findViewById(R.id.btnResetQuickAccess);
        
        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                Toast.makeText(getContext(), 
                    isArabic ? "✅ تم الحفظ" : "✅ Saved", 
                    Toast.LENGTH_SHORT).show();
            });
        }
        
        if (btnReset != null) {
            btnReset.setOnClickListener(v -> resetToDefaults());
        }
        
        renderActionToggles();
    }

    /**
     * ✅ عرض جميع الإجراءات مع مفاتيح التفعيل
     */
    private void renderActionToggles() {
        if (containerActions == null) return;
        
        containerActions.removeAllViews();
        
        List<MiraQuickAccessSettings.QuickAction> allActions = quickAccess.getAllActions();
        
        for (MiraQuickAccessSettings.QuickAction action : allActions) {
            View actionView = createActionToggle(action);
            if (actionView != null) {
                containerActions.addView(actionView);
            }
        }
    }

    /**
     * ✅ إنشاء صف تبديل للإجراء
     */
    private View createActionToggle(MiraQuickAccessSettings.QuickAction action) {
        if (getContext() == null) return null;
        
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(16, 12, 16, 12);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        
        // الأيقونة
        TextView tvIcon = new TextView(getContext());
        tvIcon.setText(getActionIcon(action.id));
        tvIcon.setTextSize(24);
        tvIcon.setPadding(0, 0, 12, 0);
        row.addView(tvIcon);
        
        // العنوان
        LinearLayout textContainer = new LinearLayout(getContext());
        textContainer.setOrientation(LinearLayout.VERTICAL);
        textContainer.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        
        TextView tvTitle = new TextView(getContext());
        tvTitle.setText(isArabic ? action.titleAr : action.titleEn);
        tvTitle.setTextSize(14);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(0xFF1F2937);
        textContainer.addView(tvTitle);
        
        TextView tvId = new TextView(getContext());
        tvId.setText(action.id);
        tvId.setTextSize(10);
        tvId.setTextColor(0xFF9CA3AF);
        textContainer.addView(tvId);
        
        row.addView(textContainer);
        
        // المفتاح
        SwitchCompat switchCompat = new SwitchCompat(getContext());
        switchCompat.setChecked(action.enabled);
        switchCompat.setOnCheckedChangeListener((buttonView, isChecked) -> {
            quickAccess.setActionEnabled(action.id, isChecked);
            Toast.makeText(getContext(), 
                (isArabic ? "✅ تم تفعيل: " : "✅ Enabled: ") + (isArabic ? action.titleAr : action.titleEn), 
                Toast.LENGTH_SHORT).show();
        });
        row.addView(switchCompat);
        
        return row;
    }

    /**
     * ✅ إعادة التعيين للوضع الافتراضي
     */
    private void resetToDefaults() {
        List<MiraQuickAccessSettings.QuickAction> allActions = quickAccess.getAllActions();
        
        for (MiraQuickAccessSettings.QuickAction action : allActions) {
            boolean defaultEnabled = !action.id.equals("gold_rates") && 
                                     !action.id.equals("users") && 
                                     !action.id.equals("branches");
            quickAccess.setActionEnabled(action.id, defaultEnabled);
        }
        
        renderActionToggles();
        
        Toast.makeText(getContext(), 
            isArabic ? "✅ تمت إعادة التعيين" : "✅ Reset to defaults", 
            Toast.LENGTH_SHORT).show();
    }

    /**
     * ✅ الحصول على أيقونة الإجراء
     */
    private String getActionIcon(String actionId) {
        switch (actionId) {
            case "identity": return "🏷️";
            case "radar": return "📡";
            case "gate": return "🚪";
            case "reports": return "📊";
            case "accounting": return "💰";
            case "transfers": return "🔄";
            case "admin_hub": return "⚙️";
            case "sales_history": return "📋";
            case "server_config": return "🌐";
            case "gold_rates": return "🥇";
            case "users": return "👥";
            case "branches": return "🏢";
            default: return "📱";
        }
    }
}
