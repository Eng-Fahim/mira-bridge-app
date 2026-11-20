package com.example.uhf.ui;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.uhf.manager.PermissionManager;
import com.example.uhf.manager.PermissionsSync;

public abstract class BaseActivity extends AppCompatActivity
        implements PermissionManager.OnPermissionsChangedListener {

    private PermissionsSync permissionsSync;
    private PermissionManager permissionManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        permissionManager = PermissionManager.getInstance(this);
        permissionsSync = PermissionsSync.getInstance(this, /* NetworkClient */ buildNetworkClient());
    }

    /** يجب على كل Activity أن تُعيد نفس العميل المستخدم في التطبيق */
    protected abstract com.example.uhf.network.NetworkClient buildNetworkClient();

    @Override
    protected void onResume() {
        super.onResume();
        // 1) سجّل الاستماع للتغيّرات
        permissionManager.addListener(this);
        // 2) ابدأ الفحص الدوري أثناء الاستخدام
        permissionsSync.startPolling();
        // 3) افحص فوراً (onResume قد يكون بعد بقاء التطبيق في الخلفية)
        permissionsSync.onResumeCheck();
    }

    @Override
    protected void onPause() {
        permissionManager.removeListener(this);
        permissionsSync.stopPolling();
        super.onPause();
    }

    /**
     * الافتراضي: إعادة إنشاء النشاط ليعيد كل شيء التقييم.
     * يمكنك تجاوزه في النشاطات التي تدعم التحديث الجزئي (مثلاً عبر ViewModel)
     */
    @Override
    public void onPermissionsChanged() {
        runOnUiThread(this::handlePermissionsChanged);
    }

    protected void handlePermissionsChanged() {
        // إعادة إنشاء الشاشة — الحل الأبسط والأكثر أماناً
        recreate();
    }
}
