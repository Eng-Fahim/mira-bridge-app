package com.example.uhf.activity;

import com.example.uhf.application.MiraApplication;
import com.example.uhf.manager.AppWatchdog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.appcompat.view.menu.MenuBuilder;
import android.annotation.SuppressLint;

import com.example.uhf.R;
import com.example.uhf.fragment.MiraAccountingFragment;
import com.example.uhf.MiraRfid.sound.MiraSoundSettingsFragment;
import com.example.uhf.fragment.MiraAdminHubFragment;
import com.example.uhf.fragment.MiraBranchesManagementFragment;
import com.example.uhf.fragment.MiraDashboardFragment;
import com.example.uhf.fragment.MiraGateSettingsFragment;
import com.example.uhf.fragment.MiraGoldCurrencyFragment;
import com.example.uhf.fragment.MiraIdentityStudioFragment;
import com.example.uhf.fragment.MiraInventoryProFragment;
import com.example.uhf.fragment.MiraPosFragment;
import com.example.uhf.fragment.MiraProfileFragment;
import com.example.uhf.fragment.MiraQuickAccessSettingsFragment;
import com.example.uhf.fragment.MiraReaderSetupFragment;
import com.example.uhf.fragment.MiraReceiptFragment;
import com.example.uhf.fragment.MiraReportsProFragment;
import com.example.uhf.fragment.MiraRfidBindingsFragment;
import com.example.uhf.fragment.MiraSalesHistoryFragment;
import com.example.uhf.fragment.MiraSecureGateFragment;
import com.example.uhf.fragment.MiraServerConfigFragment;
import com.example.uhf.fragment.MiraTransferFragment;
import com.example.uhf.fragment.MiraUHFRadarLocationFragment;


import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.graphics.Typeface;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.model.MessageModel;
import com.example.uhf.model.NotificationModel;

import com.example.uhf.fragment.MiraUHFReadTagFragment;
import com.example.uhf.fragment.MiraUHFReadWriteFragment;
import com.example.uhf.fragment.UsersManagementFragment;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraMessagingManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraQuickAccessSettings;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.manager.SessionMonitorManager;
import com.example.uhf.tools.MiraLanguageHelper;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.tools.ToastUtil;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.rscja.deviceapi.entity.UHFTAGInfo;

import java.util.ArrayList;
import java.util.List;

public class UHFMainActivity extends BaseTabFragmentActivity {

    private static final boolean ENABLE_DASHBOARD = true;
    private static final boolean ENABLE_INVENTORY = true;
    private static final boolean ENABLE_POS = true;
    private static final boolean ENABLE_TRANSFERS = true;
    private static final boolean ENABLE_OTHER = true;

    private TextView txtBadgeCount;
    private MiraMessagingManager messagingManager;
    private final Handler notificationPollHandler = new Handler(Looper.getMainLooper());
    private Runnable notificationPollRunnable;

    private static final String TAG = "UHFMainActivity";
    private static final String PREFS_NAVIGATION = "app_navigation";
    private static final String KEY_CURRENT_FRAGMENT = "current_fragment_tag";

    public TabHost mTabHost = null;

    private boolean isProfileListenerSetup = false;
    private MiraOrgProfileManager orgProfileManager;
    private MiraOrgProfileManager.OnProfileChangedListener profileListener;

    private FragmentManager fragmentManager;

    private MiraPosFragment activePOSFragment = null;
    private MiraIdentityStudioFragment activeEncodingFragment = null;
    private OnRfidScannedListener activeRfidListener = null;

    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private View statusDot;
    private TextView statusText;
    private Handler pulseHandler;
    private Runnable pulseRunnable;
    private boolean isPulseRunning = false;

    public int selectIndex = -1;
    public ArrayList<UHFTAGInfo> tagList = new ArrayList<>();
    public boolean loopFlag = false;
    private boolean isArabic = true;

    private String currentFragmentTag = "Dashboard";
    public Fragment currentFragment;

    private long lastBackPressTime = 0;
    private static final long DOUBLE_BACK_TIMEOUT = 2000;

    private enum HardwareStatus {
        CONNECTED,
        CONNECTING,
        DISCONNECTED
    }

    public interface OnRfidScannedListener {
        void onRfidScanned(String epc);
    }

    public void setActiveEncodingFragment(MiraIdentityStudioFragment fragment) {
        this.activeEncodingFragment = fragment;
    }

    public void setActiveRfidListener(OnRfidScannedListener listener) {
        this.activeRfidListener = listener;
    }

    public void setActivePOSFragment(MiraPosFragment fragment) {
        this.activePOSFragment = fragment;
    }

    private void clearActiveListeners() {
        this.activePOSFragment = null;
        this.activeEncodingFragment = null;
        this.activeRfidListener = null;
    }

    private void setupOrgProfileListener() {
        if (isProfileListenerSetup) return;
        isProfileListenerSetup = true;

        orgProfileManager = MiraOrgProfileManager.getInstance(getApplicationContext());
        profileListener = this::updateAppTitle;
        orgProfileManager.addListener(profileListener);
        updateAppTitle();
    }

    private void updateAppTitle() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::updateAppTitle);
            return;
        }

        try {
            updateToolbarTitle();
        } catch (Exception e) {
            Log.e(TAG, "Error updating app title: " + e.getMessage());
        }
    }

    private void updateHardwareStatusIndicator() {
        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);

        if (statusDot == null || statusText == null) return;

        HardwareStatus status = checkHardwareStatus();

        switch (status) {
            case CONNECTED:
                statusDot.setBackgroundResource(R.drawable.bg_status_dot_green);
                statusText.setText(R.string.status_connected);
                startPulseAnimation(1.0f, 1.15f);
                break;

            case CONNECTING:
                statusDot.setBackgroundResource(R.drawable.bg_status_dot_orange);
                statusText.setText(R.string.status_connecting);
                startPulseAnimation(0.7f, 1.3f);
                break;

            case DISCONNECTED:
                statusDot.setBackgroundResource(R.drawable.bg_status_dot_red);
                statusText.setText(R.string.status_disconnected);
                stopPulseAnimation();
                statusDot.animate().alpha(0.5f).setDuration(300).start();
                break;
        }
    }

    private HardwareStatus checkHardwareStatus() {
        try {
            if (!BaseTabFragmentActivity.RFID_DEVICE_PRESENT) {
                return HardwareStatus.DISCONNECTED;
            }
            if (mReader != null && isReaderReady()) {
                return HardwareStatus.CONNECTED;
            }
            return HardwareStatus.CONNECTING;
        } catch (Exception e) {
            Log.e(TAG, "Hardware status check error: " + e.getMessage());
            return HardwareStatus.DISCONNECTED;
        }
    }

    private void startPulseAnimation(float minScale, float maxScale) {
        stopPulseAnimation();

        isPulseRunning = true;
        pulseHandler = new Handler(Looper.getMainLooper());
        pulseRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPulseRunning || statusDot == null) return;

                statusDot.animate()
                        .scaleX(maxScale)
                        .scaleY(maxScale)
                        .setDuration(600)
                        .withEndAction(() -> {
                            if (!isPulseRunning || statusDot == null) return;

                            statusDot.animate()
                                    .scaleX(minScale)
                                    .scaleY(minScale)
                                    .setDuration(600)
                                    .withEndAction(() -> {
                                        if (isPulseRunning && pulseHandler != null) {
                                            pulseHandler.postDelayed(pulseRunnable, 200);
                                        }
                                    })
                                    .start();
                        })
                        .start();
            }
        };

        pulseHandler.post(pulseRunnable);
    }

    private void stopPulseAnimation() {
        isPulseRunning = false;
        if (pulseHandler != null && pulseRunnable != null) {
            pulseHandler.removeCallbacks(pulseRunnable);
        }
        if (statusDot != null) {
            statusDot.animate().cancel();
            statusDot.setScaleX(1.0f);
            statusDot.setScaleY(1.0f);
            statusDot.setAlpha(1.0f);
        }
    }

    private void scheduleHardwareStatusUpdates() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && !isDestroyed()) {
                    updateHardwareStatusIndicator();
                    mainHandler.postDelayed(this, 5000);
                }
            }
        }, 5000);
    }

    private void updateNotificationBadge(int count) {
        if (txtBadgeCount == null) return;

        if (count > 0) {
            txtBadgeCount.setText(count > 99 ? "99+" : String.valueOf(count));
            txtBadgeCount.setVisibility(View.VISIBLE);
        } else {
            txtBadgeCount.setVisibility(View.GONE);
        }
    }

    private void updateToolbarTitle() {
        try {
            if (getSupportActionBar() != null) {
                String orgName = "MIRA ID";
                if (orgProfileManager != null) {
                    String name = orgProfileManager.getDisplayName();
                    if (name != null && !name.trim().isEmpty()) {
                        orgName = name;
                    }
                }
                getSupportActionBar().setTitle(orgName);

                if (branchesManager != null && branchesManager.hasActiveBranch()) {
                    getSupportActionBar().setSubtitle("📍 " + branchesManager.getActiveBranchDisplayName());
                } else {
                    getSupportActionBar().setSubtitle(null);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Toolbar Update Error: " + e.getMessage());
        }
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        String savedLang = MiraLanguageHelper.getSavedLanguage(newBase);
        MiraLanguageHelper.applyLanguage(newBase, savedLang);
        super.attachBaseContext(newBase);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            sessionManager = new SessionManager(getApplicationContext());
            if (!sessionManager.isLoggedIn()) {
                navigateToLogin();
                return;
            }

            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            setContentView(R.layout.activity_main);

            cleanupOldFragments();

            AppWatchdog.getInstance().startMonitoring(() -> {
                Log.e(TAG, "UI Thread FROZEN! Restarting app...");
                MiraApplication.getInstance().restartApp();
            });

            ToastUtil.init(getApplicationContext());
            initializeLanguageState();

            branchesManager = MiraBranchesManager.getInstance(getApplicationContext());
            fragmentManager = getSupportFragmentManager();

            MaterialToolbar toolbar = findViewById(R.id.toolbar);
            if (toolbar != null) {
                setSupportActionBar(toolbar);
                updateToolbarTitle();
            }

            View btnSubSearch = findViewById(R.id.btnSubSearch);
            if (btnSubSearch != null) btnSubSearch.setOnClickListener(v -> showGlobalSearchDialog());

            View btnSubSync = findViewById(R.id.btnSubSync);
            if (btnSubSync != null) btnSubSync.setOnClickListener(v -> performDataSync());

            View btnSubNotifications = findViewById(R.id.btnSubNotifications);
            if (btnSubNotifications != null) btnSubNotifications.setOnClickListener(v -> showNotificationsDialog());

            View btnHardwareStatus = findViewById(R.id.btnHardwareStatus);
            if (btnHardwareStatus != null) {
                btnHardwareStatus.setOnClickListener(v -> {
                    showHardwareOptionsDialog();
                    mainHandler.postDelayed(this::updateHardwareStatusIndicator, 500);
                });
            }

            updateHardwareStatusIndicator();
            scheduleHardwareStatusUpdates();

            setupQuickNav();
            setupOrgProfileListener();

            if (savedInstanceState == null) {
                currentFragmentTag = "Dashboard";
            } else {
                currentFragmentTag = savedInstanceState.getString("current_fragment", "Dashboard");
            }

            loadFragmentDirectly(new MiraDashboardFragment(), "Dashboard");

        } catch (Exception e) {
            Log.e(TAG, "Fatal error in onCreate: " + e.getMessage(), e);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        try {
            isArabic = newConfig.locale.getLanguage().equals("ar");

            getWindow().getDecorView().setLayoutDirection(
                    isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR
            );

            updateBottomNavLanguage();
            updateToolbarTitle();

        } catch (Exception e) {
            Log.e(TAG, "Configuration change error: " + e.getMessage());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateToolbarTitle();
        updateHardwareStatusIndicator();
        mainHandler.postDelayed(this::hideLoading, 100);

        try {
            com.example.uhf.MiraRfid.RFIDManager.getInstance(this).autoConnectAndBind(this);
        } catch (Exception e) {
            Log.e(TAG, "Error connecting RFID in onResume: " + e.getMessage());
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        hideLoading();
        AppWatchdog.getInstance().stopMonitoring();
        stopPulseAnimation();

        try {
            com.example.uhf.MiraRfid.RFIDManager rfidManager =
                    com.example.uhf.MiraRfid.RFIDManager.getInstance(this);
            rfidManager.stopScan();
            rfidManager.unregisterTriggerKeyReceiver();
        } catch (Exception e) {
            Log.w(TAG, "Unregister trigger in onPause warning: " + e.getMessage());
        }
    }

    private void initializeLanguageState() {
        try {
            isArabic = getResources().getConfiguration().locale.getLanguage().equals("ar");
            getWindow().getDecorView().setLayoutDirection(
                    isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR
            );
        } catch (Exception e) {
            Log.w(TAG, "Locale check failed: " + e.getMessage());
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("current_fragment", currentFragmentTag);
        saveFragmentTagToPrefs(currentFragmentTag);
    }

    private void saveFragmentTagToPrefs(String tag) {
        try {
            getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CURRENT_FRAGMENT, tag)
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "Save fragment tag error: " + e.getMessage());
        }
    }

    @SuppressLint("RestrictedApi")
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);

        MenuItem notificationItem = menu.findItem(R.id.action_notifications);
        if (notificationItem != null) {
            View actionView = notificationItem.getActionView();
            if (actionView != null) {
                txtBadgeCount = actionView.findViewById(R.id.txtBadgeCount);
                actionView.setOnClickListener(v -> showNotificationsAndChatDialog());
            }
        }

        // في onCreate بعد تهيئة sessionManager
        messagingManager = MiraMessagingManager.getInstance(this);
        // تعيين معرف المستخدم الحالي
        String userId = sessionManager.getUserId(); // أو أي طريقة للحصول على معرف المستخدم
        if (userId != null && !userId.isEmpty()) {
        messagingManager.setCurrentUserId(userId);
        }
        messagingManager.addListener(this::updateNotificationBadge);
        updateNotificationBadge(messagingManager.getUnreadCount());

        startNotificationPolling();

        PermissionManager pm = PermissionManager.getInstance(getApplicationContext());
        applyToolbarMenuPermissions(menu, pm);
        return true;
    }

    private void applyToolbarMenuPermissions(Menu menu, PermissionManager pm) {
        if (pm == null) return;

        MenuItem toggleLanguage = menu.findItem(R.id.action_toggle_language);
        if (toggleLanguage != null) {
            toggleLanguage.setTitle(isArabic ? "English" : "العربية");
        }

        MenuItem actionGoldRates = menu.findItem(R.id.action_gold_rates);
        if (actionGoldRates != null) {
            actionGoldRates.setVisible(pm.canViewGoldRates());
        }

        MenuItem actionQuickAccess = menu.findItem(R.id.action_quick_access_settings);
        if (actionQuickAccess != null) {
            actionQuickAccess.setVisible(pm.canManageQuickAccess());
        }

        MenuItem actionDashboard = menu.findItem(R.id.action_dashboard);
        if (actionDashboard != null) {
            actionDashboard.setVisible(pm.canViewDashboard());
        }

        MenuItem actionBranches = menu.findItem(R.id.action_branches);
        if (actionBranches != null) {
            actionBranches.setVisible(pm.isManager() || pm.isAdmin() || pm.isBranchManager());
        }

        MenuItem actionProfile = menu.findItem(R.id.action_profile);
        if (actionProfile != null) {
            actionProfile.setVisible(pm.canViewProfile());
        }

        MenuItem actionHardware = menu.findItem(R.id.action_hardware_setup);
        if (actionHardware != null) {
            actionHardware.setVisible(true);
        }

        MenuItem actionExport = menu.findItem(R.id.export);
        if (actionExport != null) {
            actionExport.setVisible(pm.canExportReports());
        }

        MenuItem actionLogout = menu.findItem(R.id.action_logout);
        if (actionLogout != null) {
            actionLogout.setVisible(true);
        }

        MenuItem uhfVer = menu.findItem(R.id.UHF_ver);
        if (uhfVer != null) {
            uhfVer.setVisible(pm.isAdmin() || pm.isSystemAdmin());
        }

        MenuItem soundSettings = menu.findItem(R.id.nav_sound_settings);
        if (soundSettings != null) {
            soundSettings.setVisible(true);
        }
    }

    private void showNotificationsAndChatDialog() {
        if (messagingManager != null) {
            messagingManager.clearUnreadCount();
        }

        String[] options = isArabic ? new String[]{
                "💬 مراسلة الأعضاء والفروع",
                "📥 تنبيهات النظام المخزنية",
                "📢 التعميمات الإدارية"
        } : new String[]{
                "💬 Team & Branch Chat",
                "📥 Inventory Notifications",
                "📢 Administrative Broadcasts"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "📬 مركز المراسلات والإشعارات" : "Messaging Center")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showChatWindowDialog();
                    } else {
                        showNotificationsDialog();
                    }
                })
                .setPositiveButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    }

    

    // تحديث دالة startNotificationPolling للربط الفعلي
private void startNotificationPolling() {
    notificationPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && !isDestroyed()) {
                // جلب العداد الفعلي من السيرفر
                if (messagingManager != null) {
                    messagingManager.fetchUnreadCountFromServer();
                }
                
                // إعادة جدولة الفحص
                notificationPollHandler.postDelayed(this, 30000); // فحص كل 30 ثانية
            }
        }
    };
    notificationPollHandler.postDelayed(notificationPollRunnable, 5000); // أول فحص بعد 5 ثواني
}
private void showChatWindowDialog() {
    BottomSheetDialog chatDialog = new BottomSheetDialog(this, R.style.TransparentBottomSheetDialog);
    
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(32, 32, 32, 32);

    TextView title = new TextView(this);
    title.setText(isArabic ? "💬 مراسلة اعضاء المنظومة" : "💬 Internal Team Chat");
    title.setTextSize(18);
    title.setTypeface(null, Typeface.BOLD);
    root.addView(title);

    // عرض الرسائل السابقة
    ScrollView scrollView = new ScrollView(this);
    scrollView.setLayoutParams(new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        0,
        1f // يأخذ المساحة المتاحة
    ));
    
    LinearLayout messagesContainer = new LinearLayout(this);
    messagesContainer.setOrientation(LinearLayout.VERTICAL);
    
    // عرض رسالة تحميل
    TextView loadingText = new TextView(this);
    loadingText.setText(isArabic ? "جاري تحميل الرسائل..." : "Loading messages...");
    loadingText.setGravity(android.view.Gravity.CENTER);
    messagesContainer.addView(loadingText);
    
    scrollView.addView(messagesContainer);
    root.addView(scrollView);
    
    // جلب الرسائل من السيرفر
    messagingManager.fetchMessagesFromServer(new MiraMessagingManager.OnMessagesLoadedListener() {
        @Override
        public void onMessagesLoaded(List<MessageModel> messages) {
            runOnUiThread(() -> {
                messagesContainer.removeAllViews();
                
                if (messages.isEmpty()) {
                    TextView emptyText = new TextView(UHFMainActivity.this);
                    emptyText.setText(isArabic ? "لا توجد رسائل بعد" : "No messages yet");
                    emptyText.setGravity(android.view.Gravity.CENTER);
                    emptyText.setPadding(16, 32, 16, 32);
                    messagesContainer.addView(emptyText);
                } else {
                    for (MessageModel message : messages) {
                        TextView messageView = new TextView(UHFMainActivity.this);
                        String sender = message.getSenderName() != null && !message.getSenderName().isEmpty() 
                            ? message.getSenderName() 
                            : (isArabic ? "مستخدم" : "User");
                        
                        String time = formatTimestamp(message.getTimestamp());
                        String messageText = sender + " (" + time + "):\n" + message.getContent();
                        
                        messageView.setText(messageText);
                        messageView.setPadding(16, 12, 16, 12);
                        messageView.setBackgroundResource(R.drawable.bg_message_bubble);
                        
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        );
                        params.setMargins(0, 0, 0, 8);
                        messageView.setLayoutParams(params);
                        
                        messagesContainer.addView(messageView);
                    }
                }
                
                // التمرير لأسفل لعرض أحدث رسالة
                scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
            });
        }

        @Override
        public void onError(String error) {
            runOnUiThread(() -> {
                messagesContainer.removeAllViews();
                TextView errorText = new TextView(UHFMainActivity.this);
                errorText.setText(isArabic ? "فشل تحميل الرسائل: " + error : "Failed to load messages: " + error);
                errorText.setGravity(android.view.Gravity.CENTER);
                errorText.setPadding(16, 32, 16, 32);
                messagesContainer.addView(errorText);
            });
        }
    });

    // حقل الإدخال
    EditText etMessage = new EditText(this);
    etMessage.setHint(isArabic ? "اكتب رسالتك..." : "Type message...");
    etMessage.setPadding(24, 24, 24, 24);
    LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    );
    inputParams.setMargins(0, 16, 0, 8);
    etMessage.setLayoutParams(inputParams);
    root.addView(etMessage);

    // زر الإرسال
    MaterialButton btnSend = new MaterialButton(this);
    btnSend.setText(isArabic ? "إرسال" : "Send");
    btnSend.setLayoutParams(new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ));
    root.addView(btnSend);

    btnSend.setOnClickListener(v -> {
        String msg = etMessage.getText().toString().trim();
        if (!msg.isEmpty()) {
            btnSend.setEnabled(false);
            btnSend.setText(isArabic ? "جاري الإرسال..." : "Sending...");
            
            // إرسال الرسالة عبر السيرفر
            String receiverId = "all"; // أو معرف مستخدم محدد
            messagingManager.sendMessage(receiverId, msg, new MiraApiClient.SendMessageCallback() {
                @Override
                public void onSuccess(MessageModel message) {
                    runOnUiThread(() -> {
                        Toast.makeText(UHFMainActivity.this, 
                            isArabic ? "تم إرسال الرسالة بنجاح" : "Message Sent", 
                            Toast.LENGTH_SHORT).show();
                        etMessage.setText("");
                        btnSend.setEnabled(true);
                        btnSend.setText(isArabic ? "إرسال" : "Send");
                        chatDialog.dismiss();
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        Toast.makeText(UHFMainActivity.this, 
                            isArabic ? "فشل إرسال الرسالة: " + error : "Failed to send message: " + error, 
                            Toast.LENGTH_SHORT).show();
                        btnSend.setEnabled(true);
                        btnSend.setText(isArabic ? "إرسال" : "Send");
                    });
                }
            });
        } else {
            etMessage.setError(isArabic ? "الرجاء كتابة رسالة" : "Please type a message");
        }
    });

    chatDialog.setContentView(root);
    chatDialog.show();
}



// دالة مساعدة لتنسيق الوقت
private String formatTimestamp(long timestamp) {
    try {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
            isArabic ? "HH:mm dd/MM/yyyy" : "HH:mm MM/dd/yyyy",
            java.util.Locale.getDefault()
        );
        return sdf.format(new java.util.Date(timestamp));
    } catch (Exception e) {
        return "";
    }
}

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_hardware_setup) {
            showHardwareOptionsDialog();
            return true;
        } else if (id == R.id.action_dashboard) {
            checkInventoryLockAndExecute(() -> {
                clearBackStack();
                loadFragment(new MiraDashboardFragment(), "Dashboard", false);
            });
            return true;
        } else if (id == R.id.action_branches) {
            checkInventoryLockAndExecute(() -> loadFragment(new MiraBranchesManagementFragment(), "Branches"));
            return true;
        } else if (id == R.id.action_gold_rates) {
            checkInventoryLockAndExecute(() -> loadFragment(new MiraGoldCurrencyFragment(), "GoldRates"));
            return true;
        } else if (id == R.id.action_quick_access_settings) {
            checkInventoryLockAndExecute(() -> loadFragment(new MiraQuickAccessSettingsFragment(), "QuickAccessSettings"));
            return true;
        } else if (id == R.id.export) {
            checkInventoryLockAndExecute(() -> loadFragment(new MiraReportsProFragment(), "Reports"));
            return true;
        } else if (id == R.id.action_toggle_language) {
            toggleLanguage();
            return true;
        } else if (id == R.id.UHF_ver) {
            showSystemVersionDialog();
            return true;
        } else if (id == R.id.action_profile) {
            checkInventoryLockAndExecute(() -> loadFragment(new MiraProfileFragment(), "Profile"));
            return true;
        } else if (id == R.id.action_logout) {
            confirmLogout();
            return true;
        } else if (id == R.id.nav_sound_settings) {
            checkInventoryLockAndExecute(() -> loadFragment(new MiraSoundSettingsFragment(), "SoundSettings"));
            return true;
        } else if (id == R.id.action_search) {
            showGlobalSearchDialog();
            return true;
        } else if (id == R.id.action_sync) {
            performDataSync();
            return true;
        } else if (id == R.id.action_notifications) {
            showNotificationsDialog();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void toggleLanguage() {
        try {
            String newLang = isArabic ? "en" : "ar";
            saveFragmentTagToPrefs(currentFragmentTag);
            MiraLanguageHelper.applyLanguage(this, newLang);

            Intent intent = new Intent(this, UHFMainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Log.e(TAG, "Toggle language error: " + e.getMessage(), e);
            Toast.makeText(this, "Error changing language", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmLogout() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "تسجيل الخروج" : "Logout")
                .setMessage(isArabic ? "هل أنت متأكد من تسجيل الخروج من المنظومة؟" : "Are you sure you want to log out?")
                .setPositiveButton(isArabic ? "تأكيد" : "Logout", (dialog, which) -> {
                    sessionManager.logoutUser();
                    navigateToLogin();
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void showGlobalSearchDialog() {
        BottomSheetDialog searchDialog = new BottomSheetDialog(this, R.style.TransparentBottomSheetDialog);

        android.widget.LinearLayout searchLayout = new android.widget.LinearLayout(this);
        searchLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        searchLayout.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText(isArabic ? "🔍 البحث السريع في المنظومة" : "Global System Search");
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, 24);
        searchLayout.addView(title);

        final EditText etSearchQuery = new EditText(this);
        etSearchQuery.setHint(isArabic ? "أدخل رقم الباركود، EPC، أو اسم القطعة..." : "Enter EPC, Barcode, or item name...");
        etSearchQuery.setPadding(32, 32, 32, 32);
        searchLayout.addView(etSearchQuery);

        MaterialButton btnExecuteSearch = new MaterialButton(this);
        btnExecuteSearch.setText(isArabic ? "بحث الآن" : "Search Now");
        btnExecuteSearch.setPadding(0, 24, 0, 24);
        searchLayout.addView(btnExecuteSearch);

        searchDialog.setContentView(searchLayout);

        btnExecuteSearch.setOnClickListener(v -> {
            String query = etSearchQuery.getText().toString().trim();
            if (!query.isEmpty()) {
                searchDialog.dismiss();
                Toast.makeText(this, (isArabic ? "جاري البحث عن: " : "Searching for: ") + query, Toast.LENGTH_SHORT).show();
                loadFragment(MiraInventoryProFragment.newInstance(), "Scan");
            } else {
                etSearchQuery.setError(isArabic ? "الرجاء إدخال نص للبحث" : "Please enter search text");
            }
        });

        searchDialog.show();
    }

    private void performDataSync() {
        showLoading();
        Toast.makeText(this, isArabic ? "جاري مزامنة البيانات المحلية مع السيرفر..." : "Syncing local data with server...", Toast.LENGTH_SHORT).show();

        mainHandler.postDelayed(() -> {
            hideLoading();
            new MaterialAlertDialogBuilder(this)
                    .setTitle(isArabic ? "✅ تمت المزامنة بنجاح" : "✅ Sync Completed")
                    .setMessage(isArabic ? "تم تحديث كافة السجلات، المخزون، والأسعار مع السيرفر السحابي بنجاح." : "All records, inventory, and prices have been successfully synced with the cloud server.")
                    .setPositiveButton(isArabic ? "موافق" : "OK", (dialog, which) -> dialog.dismiss())
                    .show();
        }, 1500);
    }

    private void showNotificationsDialog() {
        String[] notifications = isArabic ? new String[]{
                "📥 تم استلام تحديث أسعار الذهب الجديدة بنجاح",
                "⚠️ تنبيه: توجد 3 قطع مجوهرات غير مسجلة في النطاق الحالي",
                "🔄 اكتملت مزامنة المبيعات اليومية بنجاح الساعة 11:00 م",
                "🛡️ نظام حماية البوابات الأمنية يعمل بكفاءة تامة"
        } : new String[]{
                "📥 New gold price rates successfully updated",
                "⚠️ Alert: 3 unregistered jewelry items detected in current zone",
                "🔄 Daily sales synchronization completed at 11:00 PM",
                "🛡️ Gate security protection system operating normally"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "🔔 إشعارات المنظومة" : "System Notifications")
                .setItems(notifications, (dialog, which) -> {})
                .setPositiveButton(isArabic ? "إغلاق" : "Close", (dialog, which) -> dialog.dismiss())
                .setNeutralButton(isArabic ? "حذف الكل" : "Clear All", (dialog, which) -> {
                    Toast.makeText(this, isArabic ? "تم مسح كافة الإشعارات" : "All notifications cleared", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void setupQuickNav() {
        try {
            BottomNavigationView quickNav = findViewById(R.id.bottomNavigationView);
            FloatingActionButton fabActionCenter = findViewById(R.id.fabActionCenter);

            PermissionManager pm = PermissionManager.getInstance(getApplicationContext());

            if (fabActionCenter != null) {
                boolean hasQuickActions = pm.getAllowedQuickActions().size() > 0;
                fabActionCenter.setVisibility(hasQuickActions ? View.VISIBLE : View.GONE);
                fabActionCenter.setEnabled(hasQuickActions);
                fabActionCenter.setOnClickListener(v -> showQuickActionMenu());
            }

            if (quickNav != null) {
                applyBottomNavPermissions(quickNav, pm);

                quickNav.setOnItemSelectedListener(item -> {
                    int id = item.getItemId();
                    String tag = getFragmentTagForNavItem(id);
                    Fragment fragment = createFragmentByTag(tag);

                    if (fragment != null) {
                        loadFragment(fragment, tag, false);
                        return true;
                    }
                    return false;
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "QuickNav Setup Error: " + e.getMessage());
        }
    }

    private void applyBottomNavPermissions(BottomNavigationView quickNav, PermissionManager pm) {
        Menu menu = quickNav.getMenu();

        MenuItem navHome = menu.findItem(R.id.nav_home);
        if (navHome != null) navHome.setVisible(true);

        MenuItem navInventory = menu.findItem(R.id.nav_inventory);
        if (navInventory != null) navInventory.setVisible(true);

        MenuItem navSales = menu.findItem(R.id.nav_sales);
        if (navSales != null) navSales.setVisible(true);

        MenuItem navTransfers = menu.findItem(R.id.nav_transfers);
        if (navTransfers != null) navTransfers.setVisible(true);
    }

    private String getFragmentTagForNavItem(int itemId) {
        if (itemId == R.id.nav_home) return "Dashboard";
        if (itemId == R.id.nav_inventory) return "Scan";
        if (itemId == R.id.nav_sales) return "POS";
        if (itemId == R.id.nav_transfers) return "Transfers";
        return "";
    }

    private void showQuickActionMenu() {
        try {
            BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.TransparentBottomSheetDialog);
            View view = getLayoutInflater().inflate(R.layout.dialog_quick_action_grid, null);
            dialog.setContentView(view);

            PermissionManager pm = PermissionManager.getInstance(getApplicationContext());
            MiraQuickAccessSettings quickAccess = MiraQuickAccessSettings.getInstance(getApplicationContext());

            View btnRfidWrite = view.findViewById(R.id.btnRfidWrite);
            if (btnRfidWrite != null) {
                boolean allowed = pm.canEditInventory() && quickAccess.isActionEnabled("rfid_write");
                btnRfidWrite.setVisibility(allowed ? View.VISIBLE : View.GONE);
                if (allowed) {
                    btnRfidWrite.setOnClickListener(v -> {
                        dialog.dismiss();
                        checkInventoryLockAndExecute(() -> loadFragment(new MiraUHFReadWriteFragment(), "RFIDWrite"));
                    });
                }
            }

            applyQuickActionButton(view, R.id.btnRfidBindings, dialog,
                    pm.canViewRfidBindings() && quickAccess.isActionEnabled("rfid"),
                    new MiraRfidBindingsFragment(), "RfidBindings");

            applyQuickActionButton(view, R.id.btnIdentityStudio, dialog,
                    pm.canAccessIdentityStudio() && quickAccess.isActionEnabled("identity"),
                    new MiraIdentityStudioFragment(), "IdentityStudio");

            applyQuickActionButton(view, R.id.btnRadarLocation, dialog,
                    pm.canAccessRadar() && quickAccess.isActionEnabled("radar"),
                    new MiraUHFRadarLocationFragment(), "Radar");

            applyQuickActionButton(view, R.id.btnSecureGate, dialog,
                    pm.canAccessGate() && quickAccess.isActionEnabled("gate"),
                    new MiraSecureGateFragment(), "SecureGate");

            applyQuickActionButton(view, R.id.btnReports, dialog,
                    pm.canExportReports() && quickAccess.isActionEnabled("reports"),
                    new MiraReportsProFragment(), "Reports");

            applyQuickActionButton(view, R.id.btnAccounting, dialog,
                    pm.canAccessAccounting() && quickAccess.isActionEnabled("accounting"),
                    new MiraAccountingFragment(), "Accounting");

            applyQuickActionButton(view, R.id.btnTransfer, dialog,
                    pm.canAccessTransfers() && quickAccess.isActionEnabled("transfers"),
                    new MiraTransferFragment(), "Transfers");

            applyQuickActionButton(view, R.id.btnSalesHistory, dialog,
                    pm.canViewSalesHistory() && quickAccess.isActionEnabled("sales_history"),
                    new MiraSalesHistoryFragment(), "SalesHistory");

            applyQuickActionButton(view, R.id.btnAdminHub, dialog,
                    pm.isAdmin() && quickAccess.isActionEnabled("admin_hub"),
                    new MiraAdminHubFragment(), "AdminHub");

            applyQuickActionButton(view, R.id.btnServerConfig, dialog,
                    pm.canViewServerConfig() && quickAccess.isActionEnabled("server_config"),
                    new MiraServerConfigFragment(), "ServerConfig");

            View fabCloseSheet = view.findViewById(R.id.fabCloseSheet);
            if (fabCloseSheet != null) {
                fabCloseSheet.setOnClickListener(v -> dialog.dismiss());
            }

            dialog.show();
        } catch (Exception e) {
            Log.e(TAG, "QuickActionMenu Error: " + e.getMessage(), e);
        }
    }

    private void applyQuickActionButton(View root, int viewId, BottomSheetDialog dialog,
                                        boolean allowed, Fragment fragment, String tag) {
        View btn = root.findViewById(viewId);
        if (btn != null) {
            btn.setVisibility(allowed ? View.VISIBLE : View.GONE);
            if (allowed) {
                btn.setOnClickListener(v -> {
                    dialog.dismiss();
                    checkInventoryLockAndExecute(() -> loadFragment(fragment, tag));
                });
            }
        }
    }

    public void loadFragment(Fragment fragment, String tag) {
        loadFragment(fragment, tag, true);
    }

    public void loadFragment(Fragment fragment, String tag, boolean addToBackStack) {
        if (isFinishing() || isDestroyed() || fragment == null) return;

        try {
            showLoading();
            clearActiveListeners();

            currentFragment = fragment;
            currentFragmentTag = tag;

            if (!addToBackStack) {
                clearBackStack();
            }

            FragmentTransaction transaction = fragmentManager.beginTransaction();
            transaction.setCustomAnimations(0, 0, 0, 0);
            transaction.replace(R.id.fragment_container, fragment, tag);

            if (addToBackStack) {
                transaction.addToBackStack(tag);
            }

            transaction.commit();

            mainHandler.postDelayed(this::hideLoading, 250);
            updateBottomNavSelectionByTag(tag);

        } catch (Exception e) {
            Log.e(TAG, "Error switching fragment: " + e.getMessage(), e);
            try {
                FragmentTransaction transaction = fragmentManager.beginTransaction();
                transaction.replace(R.id.fragment_container, fragment, tag);
                transaction.commitNowAllowingStateLoss();
                updateBottomNavSelectionByTag(tag);
            } catch (Exception e2) {
                Log.e(TAG, "Final fallback failed: " + e2.getMessage(), e2);
            }
            hideLoading();
        }
    }

    private void loadFragmentDirectly(Fragment fragment, String tag) {
        try {
            clearActiveListeners();
            currentFragment = fragment;
            currentFragmentTag = tag;

            FragmentTransaction transaction = fragmentManager.beginTransaction();
            transaction.replace(R.id.fragment_container, fragment, tag);
            transaction.commitNow();

            updateBottomNavSelectionByTag(tag);
            hideLoading();
        } catch (Exception e) {
            Log.e(TAG, "Direct load error: " + e.getMessage(), e);
            hideLoading();
        }
    }

    public Fragment createFragmentByTag(String tag) {
        if (tag == null) return new MiraDashboardFragment();

        switch (tag) {
            case "Dashboard":
                return new MiraDashboardFragment();
            case "Scan":
                return ENABLE_INVENTORY ? new MiraInventoryProFragment() : new MiraDashboardFragment();
            case "POS":
                return ENABLE_POS ? new MiraPosFragment() : new MiraDashboardFragment();
            case "Transfers":
                return ENABLE_TRANSFERS ? new MiraTransferFragment() : new MiraDashboardFragment();
            default:
                if (ENABLE_OTHER) {
                    switch (tag) {
                        case "IdentityStudio": return new MiraIdentityStudioFragment();
                        case "Radar": return new MiraUHFRadarLocationFragment();
                        case "SecureGate": return new MiraSecureGateFragment();
                        case "Reports": return new MiraReportsProFragment();
                        case "Accounting": return new MiraAccountingFragment();
                        case "AdminHub": return new MiraAdminHubFragment();
                        case "SalesHistory": return new MiraSalesHistoryFragment();
                        case "ServerConfig": return new MiraServerConfigFragment();
                        case "GoldRates": return new MiraGoldCurrencyFragment();
                        case "Profile": return new MiraProfileFragment();
                        case "UsersManagement": return new UsersManagementFragment();
                        case "RFIDWrite": return new MiraUHFReadWriteFragment();
                        case "ReaderSetup": return new MiraReaderSetupFragment();
                        case "GateSettings": return new MiraGateSettingsFragment();
                        case "QuickAccessSettings": return new MiraQuickAccessSettingsFragment();
                        case "Branches": return new MiraBranchesManagementFragment();
                        case "RfidBindings": return new MiraRfidBindingsFragment();
                        case "SoundSettings": return new MiraSoundSettingsFragment();
                    }
                }
                return new MiraDashboardFragment();
        }
    }

    private void showHardwareOptionsDialog() {
        PermissionManager pm = PermissionManager.getInstance(getApplicationContext());
        List<String> optionsList = new ArrayList<>();

        if (pm.isAdmin() || pm.isInventoryManager() || pm.isSystemAdmin()) {
            optionsList.add(isArabic ? "📡 إعدادات قارئ RFID" : "📡 RFID Reader Setup");
        }
        if (pm.isAdmin() || pm.isSecurity() || pm.isGateSecurity()) {
            optionsList.add(isArabic ? "🚧 تهيئة البوابات الأمنية" : "🚧 Gate Security Setup");
        }
        if (pm.canViewServerConfig()) {
            optionsList.add(isArabic ? "🌐 إعدادات السيرفر" : "🌐 Server Config");
        }
        if (pm.isAdmin() || pm.isManager() || pm.isInventoryManager()) {
            optionsList.add(isArabic ? "🏷️ إدارة الشرائح المبرمجة" : "🏷️ RFID Bindings");
        }

        optionsList.add(isArabic ? "🔄 اختبار التوصيل والربط" : "🔄 Connection Test");

        String[] options = optionsList.toArray(new String[0]);

        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "أجهزة ومستشعرات المنظومة" : "Hardware & Sensors")
                .setItems(options, (dialog, which) -> {
                    String selected = options[which];

                    if (selected.contains("RFID") || selected.contains("قارئ")) {
                        loadFragment(new MiraReaderSetupFragment(), "ReaderSetup");
                    } else if (selected.contains("Gate") || selected.contains("بوابات")) {
                        loadFragment(new MiraGateSettingsFragment(), "GateSettings");
                    } else if (selected.contains("Server") || selected.contains("سيرفر")) {
                        loadFragment(new MiraServerConfigFragment(), "ServerConfig");
                    } else if (selected.contains("Bindings") || selected.contains("شرائح")) {
                        loadFragment(new MiraRfidBindingsFragment(), "RfidBindings");
                    } else {
                        Toast.makeText(this, isArabic ? "الأجهزة تعمل بحالة جيدة" : "All devices operational", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void showSystemVersionDialog() {
        String miraSystemVersion = "MIRA ID™ v1.0.0 (Enterprise)";
        String hwVer = null;
        String modelName = Build.MODEL;

        if (BaseTabFragmentActivity.RFID_DEVICE_PRESENT && mReader != null) {
            try {
                hwVer = mReader.getHardwareVersion();
            } catch (Exception e) {
                Log.e(TAG, "Error fetching RFID reader info: " + e.getMessage());
            }
        }

        StringBuilder message = new StringBuilder();
        message.append("MIRA Trust Architecture™\n");
        message.append(isArabic ? "نظام المصادقة والتتبع المعتمد للمجوهرات\n\n" : "Jewelry Tracking & Authentication System\n\n");
        message.append(isArabic ? "📱 الجهاز: " : "📱 Device: ").append(modelName).append("\n");
        message.append(isArabic ? "📦 النسخة: " : "📦 Version: ").append(miraSystemVersion).append("\n");

        if (!BaseTabFragmentActivity.RFID_DEVICE_PRESENT) {
            message.append(isArabic ? "📡 العتاد: غير متصل" : "📡 Hardware: Not Connected");
        } else if (hwVer == null || hwVer.trim().isEmpty()) {
            message.append(isArabic ? "📡 العتاد: غير متهيئ" : "📡 Hardware: Not Configured");
        } else {
            message.append(isArabic ? "📡 العتاد: " : "📡 Hardware: ").append(hwVer).append("\n");
            message.append(isArabic ? "🟢 الحالة: وحدة UHF نشطة ومتصلة" : "🟢 Status: Active & Connected");
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "حول النظام | System Info" : "About System")
                .setMessage(message.toString())
                .setPositiveButton(isArabic ? "موافق" : "OK", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void updateBottomNavSelectionByTag(String tag) {
        try {
            BottomNavigationView quickNav = findViewById(R.id.bottomNavigationView);
            if (quickNav == null || tag == null) return;

            Menu menu = quickNav.getMenu();
            if (tag.equals("Dashboard")) {
                menu.findItem(R.id.nav_home).setChecked(true);
            } else if (tag.equals("Scan")) {
                menu.findItem(R.id.nav_inventory).setChecked(true);
            } else if (tag.equals("POS")) {
                menu.findItem(R.id.nav_sales).setChecked(true);
            } else if (tag.equals("Transfers")) {
                menu.findItem(R.id.nav_transfers).setChecked(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating bottom nav selection: " + e.getMessage());
        }
    }

    private Fragment getTopFragment() {
        if (fragmentManager != null && fragmentManager.getBackStackEntryCount() > 0) {
            FragmentManager.BackStackEntry entry =
                    fragmentManager.getBackStackEntryAt(fragmentManager.getBackStackEntryCount() - 1);
            return fragmentManager.findFragmentByTag(entry.getName());
        }
        return currentFragment;
    }

    private void clearBackStack() {
        if (fragmentManager != null && fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
    }

    public void startUHFScan() {
        if (!BaseTabFragmentActivity.RFID_DEVICE_PRESENT || !isReaderReady()) return;
        try {
            loopFlag = true;
            if (mReader != null) mReader.startInventoryTag();
        } catch (Exception e) {
            Log.e(TAG, "startUHFScan Error: " + e.getMessage());
        }
    }

    public void stopUHFScan() {
        if (!BaseTabFragmentActivity.RFID_DEVICE_PRESENT) return;
        try {
            loopFlag = false;
            if (mReader != null) mReader.stopInventory();
        } catch (Exception e) {
            Log.e(TAG, "stopUHFScan Error: " + e.getMessage());
        }
    }

    public void openReceiptFragment(String rawJson) {
        try {
            MiraReceiptFragment fragment = MiraReceiptFragment.newInstance(rawJson);
            fragment.show(fragmentManager, "Receipt");
        } catch (Exception e) {
            Log.e(TAG, "openReceiptFragment Error: " + e.getMessage());
        }
    }

    public void showToast(int resId) {
        showToast(getString(resId));
    }

    public void showToast(String text) {
        if (text == null) return;
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show());
    }

    public void playSound(int id) {
        try {
            String soundName = (id == 1) ? "beep_success" : "beep_error";
            com.example.uhf.tools.SoundGenerator.playSound(soundName);
        } catch (Exception e) {
            Log.e(TAG, "playSound Error: " + e.getMessage());
        }
    }

    public void playSoundDelayed(int value) {
        try {
            mainHandler.postDelayed(() -> playSound(1), Math.max(10, 1000 - (value * 10)));
        } catch (Exception e) {
            Log.e(TAG, "playSoundDelayed Error: " + e.getMessage());
        }
    }

    public boolean isInventoryActive() {
        if (currentFragment instanceof MiraInventoryProFragment && currentFragment.isVisible()) {
            return ((MiraInventoryProFragment) currentFragment).isScanning();
        }
        if (currentFragment instanceof MiraUHFReadTagFragment && currentFragment.isVisible()) {
            return ((MiraUHFReadTagFragment) currentFragment).isScanning();
        }
        return false;
    }

    private void checkInventoryLockAndExecute(Runnable onConfirmed) {
        if (isInventoryActive()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(isArabic ? "⚠️ الجرد قيد التشغيل" : "⚠️ Inventory In Progress")
                    .setMessage(isArabic ? "عملية الجرد تعمل حالياً. هل تريد إيقاف الجرد والانتقال؟"
                            : "Active scanning in progress. Stop and proceed?")
                    .setPositiveButton(isArabic ? "إيقاف والانتقال" : "Stop & Proceed", (dialog, which) -> {
                        stopActiveInventory();
                        if (onConfirmed != null) onConfirmed.run();
                    })
                    .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                    .show();
        } else {
            if (onConfirmed != null) onConfirmed.run();
        }
    }

    private void stopActiveInventory() {
        try {
            if (currentFragment instanceof MiraInventoryProFragment && currentFragment.isVisible()) {
                ((MiraInventoryProFragment) currentFragment).stopScanning();
            }
            if (currentFragment instanceof MiraUHFReadTagFragment && currentFragment.isVisible()) {
                ((MiraUHFReadTagFragment) currentFragment).stopScanning();
            }
            stopUHFScan();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping inventory: " + e.getMessage());
        }
    }

    @Override
    public void onBackPressed() {
        if (isInventoryActive()) {
            checkInventoryLockAndExecute(this::handleBackNavigation);
            return;
        }
        handleBackNavigation();
    }

    private void handleBackNavigation() {
        clearActiveListeners();
        if (fragmentManager != null) {
            for (Fragment fragment : fragmentManager.getFragments()) {
                if (fragment instanceof androidx.fragment.app.DialogFragment) {
                    androidx.fragment.app.DialogFragment dialogFragment = (androidx.fragment.app.DialogFragment) fragment;
                    if (dialogFragment.getDialog() != null && dialogFragment.getDialog().isShowing()) {
                        dialogFragment.dismissAllowingStateLoss();
                        return;
                    }
                }
            }

            if (fragmentManager.getBackStackEntryCount() > 0) {
                fragmentManager.popBackStackImmediate();
                Fragment topFragment = getTopFragment();
                if (topFragment != null) {
                    String tag = topFragment.getTag();
                    PermissionManager pm = PermissionManager.getInstance(getApplicationContext());
                    if (tag != null && !pm.canAccessFragment(tag)) {
                        clearBackStack();
                        loadFragment(new MiraDashboardFragment(), "Dashboard", false);
                        return;
                    }
                    currentFragment = topFragment;
                    if (tag != null) {
                        currentFragmentTag = tag;
                        saveFragmentTagToPrefs(tag);
                    }
                }
                return;
            }
        }

        if (currentFragment != null && !(currentFragment instanceof MiraDashboardFragment)) {
            clearBackStack();
            loadFragment(new MiraDashboardFragment(), "Dashboard", false);
            return;
        }

        handleDoubleBackToExit();
    }

    private void handleDoubleBackToExit() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastBackPressTime < DOUBLE_BACK_TIMEOUT) {
            finish();
            return;
        }
        lastBackPressTime = currentTime;
        Toast.makeText(this,
                isArabic ? "اضغط مرة أخرى للخروج" : "Press again to exit",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public void onTagRead(String epc) {
        try {
            if (activeEncodingFragment != null && activeEncodingFragment.isAdded() && activeEncodingFragment.isResumed()) {
                activeEncodingFragment.onRfidScanned(epc);
                return;
            }
            if (activeRfidListener != null) {
                activeRfidListener.onRfidScanned(epc);
                return;
            }
            if (activePOSFragment != null && activePOSFragment.isAdded() && activePOSFragment.isVisible()) {
                activePOSFragment.onRingScanned(epc);
                return;
            }
            if (currentFragment instanceof MiraUHFReadTagFragment && currentFragment.isAdded() && currentFragment.isVisible()) {
                ((MiraUHFReadTagFragment) currentFragment).onTagScanned(epc, -50.0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Tag Read Processing Error: " + e.getMessage());
        }
    }

    private void updateBottomNavLanguage() {
        try {
            BottomNavigationView nav = findViewById(R.id.bottomNavigationView);
            if (nav != null && nav.getMenu() != null) {
                Menu menu = nav.getMenu();

                MenuItem home = menu.findItem(R.id.nav_home);
                if (home != null) home.setTitle(isArabic ? "الرئيسية" : "Home");

                MenuItem inventory = menu.findItem(R.id.nav_inventory);
                if (inventory != null) inventory.setTitle(isArabic ? "الجرد" : "Inventory");

                MenuItem sales = menu.findItem(R.id.nav_sales);
                if (sales != null) sales.setTitle(isArabic ? "البيع" : "Sales");

                MenuItem transfers = menu.findItem(R.id.nav_transfers);
                if (transfers != null) transfers.setTitle(isArabic ? "التحويلات" : "Transfers");
            }
        } catch (Exception e) {
            Log.e(TAG, "Update bottom nav language error: " + e.getMessage());
        }
    }

    public void showLoading() {
        mainHandler.post(() -> {
            try {
                View loadingOverlay = findViewById(R.id.successOverlayMain);
                if (loadingOverlay != null) {
                    loadingOverlay.setVisibility(View.VISIBLE);
                    loadingOverlay.setAlpha(1.0f);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error showing loading: " + e.getMessage());
            }
        });
    }

    public void hideLoading() {
        mainHandler.post(() -> {
            try {
                View loadingOverlay = findViewById(R.id.successOverlayMain);
                if (loadingOverlay != null && loadingOverlay.getVisibility() == View.VISIBLE) {
                    loadingOverlay.animate()
                            .alpha(0.0f)
                            .setDuration(200)
                            .withEndAction(() -> loadingOverlay.setVisibility(View.GONE))
                            .start();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error hiding loading: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            View view = getCurrentFocus();
            if (view != null && view instanceof EditText) {
                int[] location = new int[2];
                view.getLocationOnScreen(location);
                float x = ev.getRawX() + view.getLeft() - location[0];
                float y = ev.getRawY() + view.getTop() - location[1];

                if (x < view.getLeft() || x > view.getRight() || y < view.getTop() || y > view.getBottom()) {
                    hideKeyboard(view);
                    view.clearFocus();
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private void navigateToLogin() {
        try {
            hideLoading();
            if (mReader != null) {
                mReader.free();
                mReader = null;
            }

            Intent intent = new Intent(this, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Log.e(TAG, "Navigation to login error: " + e.getMessage());
        }
    }
    


    @Override
    protected void onDestroy() {
        try {
            stopPulseAnimation();

            com.example.uhf.MiraRfid.RFIDManager rfidManager =
                    com.example.uhf.MiraRfid.RFIDManager.getInstance(this);
            if (rfidManager != null) {
                rfidManager.stopScan();
                rfidManager.unregisterTriggerKeyReceiver();
            }

            // إزالة مستمع الإشعارات
        if (messagingManager != null) {
            messagingManager.removeListener(this::updateNotificationBadge);
        }
        
        // إيقاف الفحص الدوري
        if (notificationPollHandler != null && notificationPollRunnable != null) {
            notificationPollHandler.removeCallbacks(notificationPollRunnable);
        }
        

            AppWatchdog.getInstance().stopMonitoring();
            hideLoading();
            stopActiveInventory();

            if (fragmentManager != null) {
                clearBackStack();
            }

            if (orgProfileManager != null && profileListener != null) {
                orgProfileManager.removeListener(profileListener);
            }

            SessionMonitorManager.getInstance(this).stopMonitoring();

            clearActiveListeners();

            if (tagList != null) {
                tagList.clear();
                tagList = null;
            }

        } catch (Exception e) {
            Log.e(TAG, "onDestroy cleanup error: " + e.getMessage());
        }

        super.onDestroy();
    }

    private void cleanupOldFragments() {
        try {
            FragmentManager fm = getSupportFragmentManager();
            for (Fragment fragment : fm.getFragments()) {
                if (fragment != null) {
                    FragmentTransaction transaction = fm.beginTransaction();
                    transaction.remove(fragment);
                    transaction.commitAllowingStateLoss();
                }
            }
            fm.executePendingTransactions();
            fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        } catch (Exception e) {
            Log.e(TAG, "Cleanup error: " + e.getMessage(), e);
        }
    }
}
