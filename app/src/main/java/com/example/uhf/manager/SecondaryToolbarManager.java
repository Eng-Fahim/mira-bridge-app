package com.example.uhf.manager;

import android.util.Log;
import android.view.Menu;

import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;

import com.example.uhf.R;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.HashMap;
import java.util.Map;

/**
 * ✅ SecondaryToolbarManager — إدارة ديناميكية للتولبار الثانوي
 * 
 * كل واجهة أساسية لها قائمتها الخاصة التي تُحمَّل تلقائياً عند التنقل.
 */
public class SecondaryToolbarManager {

    private static final String TAG = "SecondaryToolbar";

    // ✅ ربط كل tag بقائمته الخاصة
    private static final Map<String, Integer> TAG_TO_MENU = new HashMap<>();

    static {
        TAG_TO_MENU.put("Dashboard", R.menu.secondary_menu_dashboard);
        TAG_TO_MENU.put("Scan",      R.menu.secondary_menu_inventory);
        TAG_TO_MENU.put("POS",       R.menu.secondary_menu_pos);
        TAG_TO_MENU.put("Transfers", R.menu.secondary_menu_transfers);
    }

    @MenuRes
    private static final int DEFAULT_MENU = R.menu.secondary_menu_dashboard;

    private final MaterialToolbar toolbar;
    private OnMenuItemClickListener listener;
    private String currentTag;

    public interface OnMenuItemClickListener {
        boolean onMenuItemClick(@NonNull android.view.MenuItem item, @NonNull String currentTag);
    }

    public SecondaryToolbarManager(MaterialToolbar toolbar) {
        this.toolbar = toolbar;
    }

    public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {
        this.listener = listener;
        if (toolbar != null) {
            toolbar.setOnMenuItemClickListener(item -> {
                if (this.listener != null && currentTag != null) {
                    return this.listener.onMenuItemClick(item, currentTag);
                }
                return false;
            });
        }
    }

    /**
     * ✅ تبديل القائمة بناءً على tag الـ Fragment
     */
    public void setMenuForTag(@NonNull String tag) {
        if (toolbar == null) {
            Log.w(TAG, "Toolbar is null");
            return;
        }

        // ✅ تجاهل إذا نفس الـ tag
        if (tag.equals(currentTag)) {
            Log.d(TAG, "Same tag, skipping menu switch: " + tag);
            return;
        }

        int menuRes = TAG_TO_MENU.getOrDefault(tag, DEFAULT_MENU);

        try {
            toolbar.getMenu().clear();
            toolbar.inflateMenu(menuRes);
            currentTag = tag;
            Log.d(TAG, "✅ Menu switched to: " + tag);
        } catch (Exception e) {
            Log.e(TAG, "Failed to switch menu for " + tag, e);
        }
    }

    public Menu getCurrentMenu() {
        return toolbar != null ? toolbar.getMenu() : null;
    }

    public String getCurrentTag() {
        return currentTag;
    }

    public void reset() {
        currentTag = null;
    }
}
