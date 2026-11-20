package com.example.uhf.ui;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.uhf.application.MiraApplication;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.manager.PermissionsSync;

public abstract class BaseActivity extends AppCompatActivity
        implements PermissionManager.OnPermissionsChangedListener {

    protected PermissionManager permissionManager;
    protected PermissionsSync permissionsSync;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        permissionManager = PermissionManager.getInstance(this);
        permissionsSync   = MiraApplication.getInstance().getPermissionsSync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        permissionManager.addListener(this);
        enforceFragmentAccess();
        permissionsSync.startPolling();
        permissionsSync.onResumeCheck();
    }

    @Override
    protected void onPause() {
        permissionManager.removeListener(this);
        permissionsSync.stopPolling();
        super.onPause();
    }

    @Override
    public void onPermissionsChanged() {
        runOnUiThread(() -> {
            enforceFragmentAccess();
            handlePermissionsChanged();
        });
    }

    /** إعادة الرسم الافتراضية */
    protected void handlePermissionsChanged() {
        recreate();
    }

    /** إغلاق أي Fragment فقد صلاحيته */
    protected void enforceFragmentAccess() {
        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        java.util.List<androidx.fragment.app.Fragment> toRemove = new java.util.ArrayList<>();

        for (androidx.fragment.app.Fragment f : fm.getFragments()) {
            if (f == null || !f.isAdded()) continue;
            String tag = f.getTag();
            if (tag == null) continue;
            if (!permissionManager.canAccessFragment(tag)) {
                toRemove.add(f);
            }
        }

        for (androidx.fragment.app.Fragment f : toRemove) {
            try {
                fm.beginTransaction().remove(f).commitNowAllowingStateLoss();
                android.widget.Toast.makeText(this,
                        "تم إغلاق شاشة فقدت صلاحيتها",
                        android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                android.util.Log.e("BaseActivity", "remove unauthorized fragment", t);
            }
        }
    }
}
