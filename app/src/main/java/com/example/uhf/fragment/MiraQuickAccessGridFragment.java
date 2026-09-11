package com.example.uhf.fragment;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.MiraQuickAccessSettings;
import com.example.uhf.fragment.MiraIdentityStudioFragment;
import java.util.List;
import java.util.Locale;

public class MiraQuickAccessGridFragment extends Fragment {

    private static final String TAG = "MiraQuickAccessGrid";
    
    private MiraQuickAccessSettings quickAccess;
    private boolean isArabic;
    private OnQuickActionClickListener listener;

    public interface OnQuickActionClickListener {
        void onQuickActionClick(String actionId);
    }

    public void setOnQuickActionClickListener(OnQuickActionClickListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_quick_access_grid, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        if (getContext() == null) return;
        
        quickAccess = MiraQuickAccessSettings.getInstance(getContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        
        renderQuickActions(view);
    }

    /**
     * ✅ عرض الأزرار المخصصة
     */
    private void renderQuickActions(View view) {
        GridLayout gridLayout = view.findViewById(R.id.gridQuickActions);
        if (gridLayout == null) return;
        
        gridLayout.removeAllViews();
        
        // ✅ الحصول على الإجراءات المفعّلة فقط
        List<MiraQuickAccessSettings.QuickAction> enabledActions = quickAccess.getEnabledActions();
        
        if (enabledActions.isEmpty()) {
            showEmptyState(view);
            return;
        }
        
        // ✅ حساب عدد الأعمدة (3 أعمدة للشاشات العادية)
        int columnCount = 3;
        gridLayout.setColumnCount(columnCount);
        
        for (MiraQuickAccessSettings.QuickAction action : enabledActions) {
            CardView card = createActionCard(action);
            if (card != null) {
                gridLayout.addView(card);
            }
        }
    }

    /**
     * ✅ إنشاء بطاقة زر
     */
    private CardView createActionCard(MiraQuickAccessSettings.QuickAction action) {
        Context context = getContext();
        if (context == null) return null;
        
        // إنشاء CardView
        CardView card = new CardView(context);
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = GridLayout.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(8, 8, 8, 8);
        card.setLayoutParams(params);
        card.setRadius(16);
        card.setCardElevation(4);
        card.setUseCompatPadding(true);
        card.setClickable(true);
        card.setFocusable(true);
        
        // إنشاء المحتوى
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(16, 20, 16, 20);
        layout.setGravity(android.view.Gravity.CENTER);
        
        // الأيقونة
        TextView tvIcon = new TextView(context);
        tvIcon.setText(getActionIcon(action.id));
        tvIcon.setTextSize(32);
        tvIcon.setGravity(android.view.Gravity.CENTER);
        layout.addView(tvIcon);
        
        // العنوان
        TextView tvTitle = new TextView(context);
        tvTitle.setText(isArabic ? action.titleAr : action.titleEn);
        tvTitle.setTextSize(12);
        tvTitle.setGravity(android.view.Gravity.CENTER);
        tvTitle.setPadding(0, 8, 0, 0);
        tvTitle.setTextColor(0xFF1F2937);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(tvTitle);
        
        card.addView(layout);
        
        // ✅ النقر على البطاقة
        card.setOnClickListener(v -> {
            if (listener != null) {
                listener.onQuickActionClick(action.id);
            } else {
                handleActionClick(action.id);
            }
        });
        
        return card;
    }

    /**
     * ✅ معالجة النقر على الإجراء
     */
    private void handleActionClick(String actionId) {
        if (getActivity() instanceof UHFMainActivity) {
            UHFMainActivity activity = (UHFMainActivity) getActivity();
            
            switch (actionId) {
                case "identity":
                    activity.loadFragment(new MiraIdentityStudioFragment(), "IdentityStudio");
                    break;
                case "radar":
                    activity.loadFragment(new MiraUHFRadarLocationFragment(), "Radar");
                    break;
                case "gate":
                    activity.loadFragment(new MiraSecureGateFragment(), "SecureGate");
                    break;
                case "reports":
                    activity.loadFragment(new MiraReportsProFragment(), "Reports");
                    break;
                case "accounting":
                    activity.loadFragment(new MiraAccountingFragment(), "Accounting");
                    break;
                case "transfers":
                    activity.loadFragment(new MiraTransferFragment(), "Transfers");
                    break;
                case "admin_hub":
                    activity.loadFragment(new MiraAdminHubFragment(), "AdminHub");
                    break;
                case "sales_history":
                    activity.loadFragment(new MiraSalesHistoryFragment(), "SalesHistory");
                    break;
                case "server_config":
                    activity.loadFragment(new MiraServerConfigFragment(), "ServerConfig");
                    break;
                case "gold_rates":
                    activity.loadFragment(new MiraGoldCurrencyFragment(), "GoldRates");
                    break;
                case "users":
                    activity.loadFragment(new UsersManagementFragment(), "UsersManagement");
                    break;
                case "branches":
                    // فتح إدارة الفروع
                    Toast.makeText(getContext(), 
                        isArabic ? "إدارة الفروع" : "Branches Management", 
                        Toast.LENGTH_SHORT).show();
                    break;
                default:
                    Toast.makeText(getContext(), 
                        isArabic ? "غير معروف: " + actionId : "Unknown: " + actionId, 
                        Toast.LENGTH_SHORT).show();
                    break;
            }
        }
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

    /**
     * ✅ عرض حالة فارغة
     */
    private void showEmptyState(View view) {
        GridLayout gridLayout = view.findViewById(R.id.gridQuickActions);
        if (gridLayout == null) return;
        
        Context context = getContext();
        if (context == null) return;
        
        TextView tvEmpty = new TextView(context);
        tvEmpty.setText(isArabic ? 
            "لا توجد أزرار مفعّلة\nقم بتخصيص الأزرار من الإعدادات" : 
            "No enabled actions\nCustomize buttons from settings");
        tvEmpty.setTextSize(14);
        tvEmpty.setGravity(android.view.Gravity.CENTER);
        tvEmpty.setPadding(32, 32, 32, 32);
        tvEmpty.setTextColor(0xFF6B7280);
        
        gridLayout.addView(tvEmpty);
    }
}
