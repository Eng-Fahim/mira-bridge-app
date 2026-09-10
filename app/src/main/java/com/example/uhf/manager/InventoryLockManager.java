package com.example.uhf.manager;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.R;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

/**
 * 🆕 InventoryLockManager - مدير وضع الجرد المحصّن
 * 
 * المسؤوليات:
 * - إخفاء/إظهار Bottom Navigation أثناء الجرد
 * - إخفاء/إظهار FAB أثناء الجرد
 * - تتبع حالة القفل (Locked / Unlocked)
 * - إدارة الحركات (Animations)
 * 
 * المزايا:
 * - فصل المسؤوليات عن Activity
 * - قابل للاستخدام من أي Fragment
 * - Thread-Safe
 * 
 * @author Mira Team
 * @version 1.0
 */
public class InventoryLockManager {

    private static final String TAG = "InventoryLockManager";
    
    // مدد الحركات
    private static final long ANIM_DURATION = 300L;
    private static final float ANIM_TRANSLATION_Y = 120f;
    
    private final Activity activity;
    
    // حالة القفل
    private volatile boolean isLocked = false;
    
    // المراجع للمكونات
    private View bottomNavContainer;
    private BottomNavigationView bottomNavigationView;
    private FloatingActionButton fabActionCenter;
    
    // ==================== Constructor ====================
    
    public InventoryLockManager(@NonNull Activity activity) {
        this.activity = activity;
    }
    
    /**
     * تهيئة المراجع للمكونات (تُستدعى من onCreate)
     */
    public void initialize() {
        try {
            bottomNavContainer = activity.findViewById(R.id.bottomNavContainer);
            bottomNavigationView = activity.findViewById(R.id.bottomNavigationView);
            fabActionCenter = activity.findViewById(R.id.fabActionCenter);
        } catch (Exception e) {
            Log.e(TAG, "Error initializing references", e);
        }
    }
    
    // ==================== Public API ====================
    
    /**
     * 🔒 تفعيل وضع الجرد المحصّن
     * يُخفي Bottom Navigation و FAB بحركة انسيابية
     */
    public void lock() {
        if (isLocked) {
            Log.d(TAG, "Already locked, skipping");
            return;
        }
        
        try {
            isLocked = true;
            
            // 1. إخفاء Bottom Navigation
            hideBottomNavigation();
            
            // 2. إخفاء FAB
            hideFab();
            
            Log.i(TAG, "🔒 Inventory Lock Mode ACTIVATED");
        } catch (Exception e) {
            Log.e(TAG, "Error locking inventory mode", e);
        }
    }
    
    /**
     * 🔓 إلغاء وضع الجرد المحصّن
     * يُظهر Bottom Navigation و FAB بحركة انسيابية
     */
    public void unlock() {
        if (!isLocked) {
            Log.d(TAG, "Already unlocked, skipping");
            return;
        }
        
        try {
            isLocked = false;
            
            // 1. إظهار Bottom Navigation
            showBottomNavigation();
            
            // 2. إظهار FAB
            showFab();
            
            Log.i(TAG, "🔓 Inventory Lock Mode DEACTIVATED");
        } catch (Exception e) {
            Log.e(TAG, "Error unlocking inventory mode", e);
        }
    }
    
    /**
     * هل وضع القفل نشط؟
     */
    public boolean isLocked() {
        return isLocked;
    }
    
    /**
     * إعادة تهيئة الحالة (تُستدعى من onDestroy)
     */
    public void cleanup() {
        try {
            isLocked = false;
            bottomNavContainer = null;
            bottomNavigationView = null;
            fabActionCenter = null;
        } catch (Exception e) {
            Log.e(TAG, "Error cleaning up", e);
        }
    }
    
    // ==================== Private Helpers ====================
    
    /**
     * إخفاء Bottom Navigation بحركة انسيابية
     */
    private void hideBottomNavigation() {
        if (bottomNavContainer == null) {
            bottomNavContainer = activity.findViewById(R.id.bottomNavContainer);
        }
        
        if (bottomNavContainer == null) {
            Log.w(TAG, "bottomNavContainer not found");
            return;
        }
        
        bottomNavContainer.animate()
                .alpha(0f)
                .translationY(ANIM_TRANSLATION_Y)
                .setDuration(ANIM_DURATION)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (bottomNavContainer != null) {
                        bottomNavContainer.setVisibility(View.GONE);
                    }
                })
                .start();
    }
    
    /**
     * إظهار Bottom Navigation بحركة انسيابية
     */
    private void showBottomNavigation() {
        if (bottomNavContainer == null) {
            bottomNavContainer = activity.findViewById(R.id.bottomNavContainer);
        }
        
        if (bottomNavContainer == null) {
            Log.w(TAG, "bottomNavContainer not found");
            return;
        }
        
        bottomNavContainer.setVisibility(View.VISIBLE);
        bottomNavContainer.setAlpha(0f);
        bottomNavContainer.setTranslationY(ANIM_TRANSLATION_Y);
        bottomNavContainer.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(ANIM_DURATION)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }
    
    /**
     * إخفاء FAB
     */
    private void hideFab() {
        if (fabActionCenter == null) {
            fabActionCenter = activity.findViewById(R.id.fabActionCenter);
        }
        
        if (fabActionCenter == null) {
            Log.w(TAG, "fabActionCenter not found");
            return;
        }
        
        fabActionCenter.hide();
    }
    
    /**
     * إظهار FAB
     */
    private void showFab() {
        if (fabActionCenter == null) {
            fabActionCenter = activity.findViewById(R.id.fabActionCenter);
        }
        
        if (fabActionCenter == null) {
            Log.w(TAG, "fabActionCenter not found");
            return;
        }
        
        // إظهار FAB فقط إذا كان هناك إجراءات سريعة مسموحة
        boolean hasQuickActions = checkQuickActionsPermission();
        if (hasQuickActions) {
            fabActionCenter.show();
        }
    }
    
    /**
     * فحص صلاحية الإجراءات السريعة
     */
    private boolean checkQuickActionsPermission() {
        try {
            PermissionManager pm = PermissionManager.getInstance(activity);
            return pm != null && pm.getAllowedQuickActions().size() > 0;
        } catch (Exception e) {
            Log.w(TAG, "Error checking quick actions permission", e);
            return false;
        }
    }
}
