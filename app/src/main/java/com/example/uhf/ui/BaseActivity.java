package com.example.uhf.ui;

import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.application.MiraApplication;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.manager.PermissionSyncManager;
import com.example.uhf.tools.SessionManager;

import java.util.ArrayList;
import java.util.List;

public abstract class BaseActivity extends AppCompatActivity
        implements PermissionManager.OnPermissionsChangedListener {

    private static final String TAG = "BaseActivity";

    protected PermissionManager permissionManager;
    protected PermissionSyncManager PermissionSyncManager;
    protected SessionManager sessionManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        permissionManager = PermissionManager.getInstance(this);
        PermissionSyncManager = MiraApplication.getInstance().getPermissionSyncManager();
        sessionManager = new SessionManager(getApplicationContext());
    }

    private boolean isLoginScreenOrLoggedOut() {
        String className = getClass().getSimpleName();
        if ("LoginActivity".equals(className)
                || "ActivationActivity".equals(className)
                || "SplashActivity".equals(className)) {
            return true;
        }
        if (sessionManager == null) {
            sessionManager = new SessionManager(getApplicationContext());
        }
        return !sessionManager.isLoggedIn();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (isLoginScreenOrLoggedOut()) {
            if (PermissionSyncManager != null) {
                PermissionSyncManager.stopPolling();
            }
            return;
        }

        permissionManager.addListener(this);
        enforceFragmentAccess();
        if (PermissionSyncManager != null) {
            PermissionSyncManager.startPolling();
            PermissionSyncManager.onResumeCheck();
        }
    }

    @Override
    protected void onPause() {
        if (permissionManager != null) {
            permissionManager.removeListener(this);
        }
        if (PermissionSyncManager != null) {
            PermissionSyncManager.stopPolling();
        }
        super.onPause();
    }

    @Override
    public void onPermissionsChanged() {
        if (isLoginScreenOrLoggedOut()) {
            return;
        }
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            enforceFragmentAccess();
            handlePermissionsChanged();
        });
    }

    /** إعادة الرسم الافتراضية */
    protected void handlePermissionsChanged() {
        if (this instanceof UHFMainActivity) {
            ((UHFMainActivity) this).refreshAllPermissionsUI();
            return;
        }
        recreate();
    }

    /** إغلاق أي Fragment فقد صلاحيته مع الرجوع للرئيسية بدلاً من ترك الشاشة بيضاء */
    protected void enforceFragmentAccess() {
        if (isLoginScreenOrLoggedOut()) {
            return;
        }

        FragmentManager fm = getSupportFragmentManager();
        List<Fragment> toRemove = new ArrayList<>();

        for (Fragment f : fm.getFragments()) {
            if (f == null || !f.isAdded() || f.isRemoving()) continue;
            if (f instanceof DialogFragment) continue;

            String tag = f.getTag();
            if (tag == null || tag.trim().isEmpty()) continue;
            if (tag.startsWith("androidx.") || tag.startsWith("android.")
                    || "QuickActions".equals(tag) || "Receipt".equals(tag)) {
                continue;
            }

            if (!permissionManager.canAccessFragment(tag)) {
                Log.w(TAG, "Closing unauthorized fragment on resume: tag=" + tag
                        + ", class=" + f.getClass().getSimpleName());
                toRemove.add(f);
            }
        }

        if (toRemove.isEmpty()) {
            return;
        }

        boolean removedMainContent = false;
        for (Fragment f : toRemove) {
            try {
                if (f.getId() == com.example.uhf.R.id.fragment_container) {
                    removedMainContent = true;
                }
                fm.beginTransaction().remove(f).commitNowAllowingStateLoss();
                Toast.makeText(this,
                        "تم إغلاق شاشة فقدت صلاحيتها",
                        Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                Log.e(TAG, "remove unauthorized fragment", t);
            }
        }

        if (removedMainContent && this instanceof UHFMainActivity) {
            UHFMainActivity main = (UHFMainActivity) this;
            if (main.getNavigationManager() != null) {
                main.getNavigationManager().goToDashboard();
            }
        }
    }
}
