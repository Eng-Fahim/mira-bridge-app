package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.graphics.Typeface;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.textfield.TextInputLayout;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.adapter.CartAdapter;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.dialog.POSCheckoutDialog;
import com.example.uhf.dialog.RefundDialog;
import com.example.uhf.manager.GateSaleBridge;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.utils.MiraExecutorService;
import com.example.uhf.manager.MiraItemSyncManager;
import com.example.uhf.manager.MiraLocalItemCache;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.POSManager;
import com.example.uhf.manager.PendingSalesManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.manager.PosCartManager;
import com.example.uhf.manager.PosHeroController;
import com.example.uhf.manager.PosPriceResolver;
import com.example.uhf.manager.PosUIManager;
import com.example.uhf.manager.SmartScaleManager;
import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.model.CartItem;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.tools.UIHelper;
import com.example.uhf.utils.ScaleSimulationHelper;
import com.example.uhf.navigation.NavigationManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  MiraPosFragment v6.0 — Unified Edition
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * إعادة هيكلة جذرية تعالج 13 سبباً بنيوياً:
 *
 *   ✅ مصدر واحد لتحديثات السلة: PosCartManager listener → renderCart().
 *   ✅ إلغاء forceShowCart (كانت تُعيد بناء adapter في كل إضافة).
 *   ✅ adapter واحد للجلسة، replaceItems() + notifyItem*.
 *   ✅ علم واحد للمسح: currentScanMode (لا isRFIDScanActive / isRFIDModeActive).
 *   ✅ RFIDManager.addListener / removeListener (بدل setListener المفرد).
 *   ✅ PosCartManager.addListener / removeListener (بدل setListener المفرد).
 *   ✅ thread-safety كاملة عبر PosCartManager v2.0 و PosHeroController v2.0.
 *   ✅ حفظ قيمة Power الأصلية ديناميكياً (لا 27 المفترضة).
 *   ✅ ترتيب shutdown صحيح في onDestroyView.
 *   ✅ renderCart() موحّد لكل الحالات.
 *   ✅ updateFooterVisibility() single source of truth.
 *   ✅ منع تسريب الـ listeners عبر removeListener.
 *   ✅ حماية ضد الاستدعاء بعد onDestroyView.
 */
public class MiraPosFragment extends MiraBaseFragment {

    private static final String TAG = "MiraPosFragment";

    // ═══════════════════════════════════════════════════════════════════
    //  ثوابت
    // ═══════════════════════════════════════════════════════════════════

    private static final int  KEYCODE_RFID_TRIGGER_1 = 139;
    private static final int  KEYCODE_RFID_TRIGGER_2 = 280;
    private static final int  KEYCODE_RFID_TRIGGER_3 = 293;

    private static final long SCAN_COOLDOWN_MS          = 1500;
    private static final long REFUND_MAX_DAYS           = 30;
    private static final long LAST_SALE_VALIDITY_MS     = 5 * 60 * 1000;
    private static final long ITEM_ADDED_DISPLAY_MS     = 1000;
    private static final long AUTO_ADD_DELAY_MS         = 350;
    private static final int  AUTO_ADD_MIN_LEN          = 6;
   // private static final int  POS_LOW_POWER_DBM         = 15;

    // ═══════════════════════════════════════════════════════════════════
    //  وضع المسح الموحّد (علم واحد فقط)
    // ═══════════════════════════════════════════════════════════════════
    private enum ScanMode { NONE, RFID, MANUAL, GS1 }
    private volatile ScanMode currentScanMode = ScanMode.NONE;

    // ═══════════════════════════════════════════════════════════════════
    //  One-Shot Control
    // ═══════════════════════════════════════════════════════════════════
    private volatile boolean stopAfterFirstRead = false;
    private final AtomicBoolean oneShotConsumed = new AtomicBoolean(false);

    // ═══════════════════════════════════════════════════════════════════
    //  SDK Executor — عمليات RFID المتسلسلة
    // ═══════════════════════════════════════════════════════════════════
    private final ExecutorService sdkExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "POS-SDK");
                t.setDaemon(true);
                return t;
            });

    // ═══════════════════════════════════════════════════════════════════
    //  Managers
    // ═══════════════════════════════════════════════════════════════════
    private SessionManager sessionManager;
    private SmartScaleManager scaleManager;
    private ScaleSimulationHelper simulationHelper;
    private POSManager posManager;
    private PendingSalesManager pendingSalesManager;
    private RefundDialog refundDialog;
    private MiraExecutorService miraExecutor;
    private MiraApiClient apiClient;
    private MiraPriceManager priceManager;
    private MiraBranchesManager branchesManager;
    private PermissionManager permissionManager;

    private PosCartManager cartManager;
    private PosPriceResolver priceResolver;
    private PosHeroController heroController;

    private RFIDManager rfidManager;

    // ═══════════════════════════════════════════════════════════════════
    //  Listener References (لإزالتهم بأمان)
    // ═══════════════════════════════════════════════════════════════════
    @Nullable private PosCartManager.OnCartChangedListener cartListener;
    @Nullable private MiraIRFIDReader.OnTagReadListener rfidListener;

    // ═══════════════════════════════════════════════════════════════════
    //  Handler
    // ═══════════════════════════════════════════════════════════════════
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ═══════════════════════════════════════════════════════════════════
    //  State
    // ═══════════════════════════════════════════════════════════════════
    private volatile boolean isCheckoutDialogShowing = false;
    private volatile double  liveScaleWeight = 0.0;
    private volatile int     savedPowerBeforePos = -1;   // -1 = لم يُحفظ بعد

    private final Set<String> pendingEpcs       = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastScannedTimes = new ConcurrentHashMap<>();

    private volatile MiraApiClient.ApiResponse lastSaleResponse = null;
    private volatile long lastSaleResponseTime = 0;

    private final AtomicReference<RefundItem> selectedRefundItem =
            new AtomicReference<>(null);

    // ═══════════════════════════════════════════════════════════════════
    //  View References
    // ═══════════════════════════════════════════════════════════════════
    private ImageButton btnPosRefund;
    private ImageButton btnPosLastReceipt;
    private ImageButton btnPosClose;
    private OnBackPressedCallback backPressedCallback;

    private TextView tvScaleStatus, tvItemCount, tvTotalAmount, tvCartStatus;
    private TextInputLayout tilManualEpc;
    private EditText etManualEpc;
    private TextView btnAddManual;
    private TextView btnCheckout;
    private TextView btnClearCart;

    private RecyclerView rvCartItems;
    private View layoutCartEmptyState;
    private View posHeroContainer;
    private View posManualInputContainer;
    private View posSummaryStrip;
    private View posFooterSticky;

    private View btnPosScanGs1;
    private View btnPosScanRfid;
    private View btnPosScanManual;

    private CartAdapter cartAdapter;
    
// ═══════════════════════════════════════════════════════════════════
//  Scan Feedback Card — بطاقة تأكيد مؤقتة
// ═══════════════════════════════════════════════════════════════════
@Nullable private View scanFeedbackOverlay;
@Nullable private MaterialCardView scanFeedbackCard;
@Nullable private ImageView ivFeedbackIcon;
@Nullable private TextView tvFeedbackTitle;
@Nullable private TextView tvFeedbackDetails;
@Nullable private TextView tvFeedbackPrice;
@Nullable private View vFeedbackAccent;
@Nullable private Runnable pendingHideFeedback;

    // ═══════════════════════════════════════════════════════════════════
    //  Helper Classes
    // ═══════════════════════════════════════════════════════════════════

    private static final class RefundItem {
        final String epc, serialNo, title;
        final int    itemId;
        final long   saleId;
        final double originalPrice;

        RefundItem(String epc, String serialNo, int itemId, long saleId,
                   double originalPrice, String title) {
            this.epc = epc;
            this.serialNo = serialNo;
            this.itemId = itemId;
            this.saleId = saleId;
            this.originalPrice = originalPrice;
            this.title = title;
        }
    }

    private static final class RefundEligibility {
        final boolean isEligible;
        final String  reason;
        final long    saleId;
        final int     itemId;
        final double  originalPrice;
        final String  originalSaleDate;

        private RefundEligibility(boolean isEligible, String reason, long saleId,
                                  int itemId, double originalPrice,
                                  String originalSaleDate) {
            this.isEligible = isEligible;
            this.reason = reason;
            this.saleId = saleId;
            this.itemId = itemId;
            this.originalPrice = originalPrice;
            this.originalSaleDate = originalSaleDate;
        }

        static RefundEligibility eligible(long saleId, int itemId,
                                          double price, String date) {
            return new RefundEligibility(true, null, saleId, itemId, price, date);
        }

        static RefundEligibility notEligible(String reason) {
            return new RefundEligibility(false, reason, 0, 0, 0, "");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        try {
            return inflater.inflate(R.layout.fragment_mira_pos, container, false);
        } catch (Exception e) {
            Log.e(TAG, "onCreateView error", e);
            return null;
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            Context ctx = requireContext().getApplicationContext();

            // ═══════════════════════════════════════════════════════
            //  1) Managers
            // ═══════════════════════════════════════════════════════
            branchesManager    = MiraBranchesManager.getInstance(ctx);
            priceManager       = MiraPriceManager.getInstance(ctx);
            permissionManager  = PermissionManager.getInstance(ctx);
            sessionManager     = new SessionManager(ctx);
            posManager         = new POSManager(ctx);
            pendingSalesManager = new PendingSalesManager(ctx);

            miraExecutor = MiraExecutorService.getInstance();
            if (miraExecutor == null || !miraExecutor.isHealthy()) {
                Log.e(TAG, "MiraExecutorService unhealthy");
                showToast(getString(R.string.pos_msg_engine_error));
                return;
            }

            priceResolver = new PosPriceResolver(priceManager);
            cartManager   = PosCartManager.getInstance();
            apiClient     = MiraApiClient.getInstance(ctx);

            // ═══════════════════════════════════════════════════════
            //  2) RFID — addListener بدلاً من setListener
            // ═══════════════════════════════════════════════════════
            rfidManager = RFIDManager.getInstance(ctx);
            if (rfidManager != null) {
                rfidListener = new MiraIRFIDReader.OnTagReadListener() {
                    @Override
                    public void onTagRead(String epc, int rssi) {
                        // ✅ علم واحد فقط
                        if (currentScanMode == ScanMode.RFID) {
                            onRingScanned(epc);
                        }
                    }
                    @Override public void onScanStarted() {}
                    @Override public void onScanStopped() {}
                    @Override
                    public void onConnectionChanged(boolean connected) {
                        safeRun(() -> {
                            if (btnPosScanRfid != null
                                    && currentScanMode == ScanMode.RFID) {
                                btnPosScanRfid.setAlpha(connected ? 1.0f : 0.5f);
                            }
                        });
                    }
                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "RFID Error: " + error);
                    }
                };
                rfidManager.addListener(rfidListener);
            }

            // ═══════════════════════════════════════════════════════
            //  3) Views + Setup
            // ═══════════════════════════════════════════════════════
            bindViews(view);
            setupHeroController(view);
            initScanFeedbackCard(view);   // ✅ [v6.2]
            setupRecyclerView();
            setupCartListener();       // ← يربط PosCartManager listener
            setupListeners();
            applyPermissions();
            hideRfidButtonIfUnsupported();

            initSmartScale();

            // ✅ ربط أولي بحالة السلة
            renderCart(cartManager.snapshot(), null, -1);

            heroController.setState(PosHeroController.State.IDLE);
            setupBackPressedHandler();

        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error", e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setActivePOSFragment(this);
                View fab = getActivity().findViewById(R.id.fabActionCenter);
                if (fab != null) fab.setVisibility(View.GONE);
            }

            if (scaleManager != null) scaleManager.connect();

            // ✅ تطبيق Low Power عند فتح POS
            applyLowPowerForPos();

            // ✅ ربط أولي بحالة السلة
            if (cartManager != null) {
                renderCart(cartManager.snapshot(), null, -1);
            }

        } catch (Exception e) {
            Log.e(TAG, "onResume error", e);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setBottomNavVisible(false);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            // ✅ إغلاق كل الأوضاع
            if (currentScanMode != ScanMode.NONE) {
                switchScanMode(ScanMode.NONE);
            }

            isCheckoutDialogShowing = false;

            if (heroController != null) {
                heroController.setState(PosHeroController.State.IDLE);
            }

            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setActivePOSFragment(null);
                View fab = getActivity().findViewById(R.id.fabActionCenter);
                if (fab != null) fab.setVisibility(View.VISIBLE);
            }
        } catch (Exception e) {
            Log.e(TAG, "onPause error", e);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setBottomNavVisible(true);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        try {
            // ═══════════════════════════════════════════════════════
            //  1) أوقف RFID أولاً (لا حجب)
            // ═══════════════════════════════════════════════════════
            stopRFIDScan();

            // ═══════════════════════════════════════════════════════
            //  2) إزالة مستمع الـ Back
            // ═══════════════════════════════════════════════════════
            if (backPressedCallback != null) {
                backPressedCallback.setEnabled(false);
                backPressedCallback.remove();
                backPressedCallback = null;
            }

            // ═══════════════════════════════════════════════════════
            //  3) إزالة مستمعي RFID و Cart (لا نُصفّرهم للآخرين)
            // ═══════════════════════════════════════════════════════
            if (rfidManager != null && rfidListener != null) {
                rfidManager.removeListener(rfidListener);
                rfidListener = null;
            }
            if (cartManager != null && cartListener != null) {
                cartManager.removeListener(cartListener);
                cartListener = null;
            }

            // ═══════════════════════════════════════════════════════
            //  4) استعادة الطاقة (قبل shutdown)
            // ═══════════════════════════════════════════════════════
            restoreOriginalPower();

            // ═══════════════════════════════════════════════════════
            //  5) إغلاق Scale و Hero
            // ═══════════════════════════════════════════════════════
            if (scaleManager != null) scaleManager.cleanup();
            if (simulationHelper != null) simulationHelper.cleanup();
            if (heroController != null) {
                heroController.cleanup();
                heroController = null;
            }

            // ═══════════════════════════════════════════════════════
            //  6) تفريغ RecyclerView
            // ═══════════════════════════════════════════════════════
            if (rvCartItems != null) {
                rvCartItems.setAdapter(null);
                rvCartItems.setLayoutManager(null);
            }
            cartAdapter = null;

            // ═══════════════════════════════════════════════════════
           // ═══════════════════════════════════════════════════════
//  6.5) تنظيف بطاقة التأكيد [v6.2]
// ═══════════════════════════════════════════════════════
if (pendingHideFeedback != null && mainHandler != null) {
    mainHandler.removeCallbacks(pendingHideFeedback);
    pendingHideFeedback = null;
}
scanFeedbackOverlay = null;
scanFeedbackCard = null;
ivFeedbackIcon = null;
tvFeedbackTitle = null;
tvFeedbackDetails = null;
tvFeedbackPrice = null;
vFeedbackAccent = null;

// ═══════════════════════════════════════════════════════
//  7) تصفير الحالة
// ═══════════════════════════════════════════════════════
pendingEpcs.clear();
            lastScannedTimes.clear();
            selectedRefundItem.set(null);
            currentScanMode = ScanMode.NONE;
            stopAfterFirstRead = false;
            oneShotConsumed.set(false);

            if (mainHandler != null) {
                mainHandler.removeCallbacksAndMessages(null);
            }

            // ═══════════════════════════════════════════════════════
            //  8) ✅ أخيراً: shutdown الـ SDK executor
            //     (بعد أن أُرسلت كل المهام)
            // ═══════════════════════════════════════════════════════
            if (sdkExecutor != null && !sdkExecutor.isShutdown()) {
                sdkExecutor.shutdown();
            }

        } catch (Exception e) {
            Log.e(TAG, "onDestroyView error", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            stopRFIDScan();
            if (scaleManager != null) scaleManager.cleanup();
        } catch (Exception e) {
            Log.e(TAG, "onDestroy error", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Power Management — Dynamic Save/Restore
    // ═══════════════════════════════════════════════════════════════════

  /**
 * ✅ [v6.1] يقلل قوة الإرسال في POS — يقرأ القيمة من MiraReaderSettings.
 *    المستخدم يتحكم بها من واجهة الإعداد (Long-press على زر RFID).
 */
private void applyLowPowerForPos() {
    if (sdkExecutor == null || sdkExecutor.isShutdown()) return;

    sdkExecutor.execute(() -> {
        try {
            Context appCtx = getSafeAppContext();
            if (appCtx == null) return;

            com.example.uhf.MiraRfid.MiraReaderSettings settings =
                    com.example.uhf.MiraRfid.MiraReaderSettings.getInstance(appCtx);

            // ✅ احفظ القيمة الفعلية قبل التغيير (للاستعادة لاحقاً)
            savedPowerBeforePos = settings.getPowerDbm();

            // ✅ اقرأ قوة POS من الإعدادات
            int posPower = settings.getPosPowerDbm();

            if (savedPowerBeforePos == posPower) {
                Log.d(TAG, "⚡ POS power already at " + posPower);
                return;
            }

            settings.setPowerDbm(posPower);

            com.rscja.deviceapi.RFIDWithUHFUART reader =
                    com.example.uhf.activity.BaseTabFragmentActivity.getSharedReader();
            if (reader != null) {
                try {
                    reader.setPower(posPower);
                    Log.i(TAG, "⚡ POS Low-Power applied: "
                            + posPower
                            + " dBm (saved=" + savedPowerBeforePos + ")");
                } catch (Exception e) {
                    Log.w(TAG, "setPower failed", e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "applyLowPowerForPos error", e);
        }
    });
}


/**
 * ✅ [v6.1] يُستدعى من Dialog عند تغيير المستخدم لقوة POS.
 *    يُطبّق القيمة الجديدة على القارئ فوراً (بدون إعادة فتح POS).
 */
private void applyPosPowerImmediate(int newPowerDbm) {
    if (sdkExecutor == null || sdkExecutor.isShutdown()) return;

    sdkExecutor.execute(() -> {
        try {
            com.rscja.deviceapi.RFIDWithUHFUART reader =
                    com.example.uhf.activity.BaseTabFragmentActivity.getSharedReader();
            if (reader != null) {
                try {
                    reader.setPower(newPowerDbm);
                    Log.i(TAG, "⚡ POS power updated immediately: " + newPowerDbm);
                } catch (Exception e) {
                    Log.w(TAG, "setPower failed", e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "applyPosPowerImmediate error", e);
        }
    });
}
    /**
     * ✅ استعادة القيمة الأصلية — ديناميكياً.
     *    إذا لم تُحفظ → لا تُغيَّر (منع الكتابة فوق إعداد المستخدم).
     */
    private void restoreOriginalPower() {
        if (savedPowerBeforePos <= 0) {
            Log.d(TAG, "⏭️ restoreOriginalPower: no saved value — skip");
            return;
        }
        if (sdkExecutor == null || sdkExecutor.isShutdown()) return;

        final int target = savedPowerBeforePos;
        savedPowerBeforePos = -1;   // ✅ مرة واحدة فقط

        sdkExecutor.execute(() -> {
            try {
                Context appCtx = getSafeAppContext();
                if (appCtx == null) return;

                com.example.uhf.MiraRfid.MiraReaderSettings settings =
                        com.example.uhf.MiraRfid.MiraReaderSettings.getInstance(appCtx);

                settings.setPowerDbm(target);

                com.rscja.deviceapi.RFIDWithUHFUART reader =
                        com.example.uhf.activity.BaseTabFragmentActivity.getSharedReader();
                if (reader != null) {
                    try {
                        reader.setPower(target);
                        Log.i(TAG, "⚡ Original power restored: " + target + " dBm");
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.e(TAG, "restoreOriginalPower error", e);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Lifecycle Helpers
    // ═══════════════════════════════════════════════════════════════════

    /** ✅ هل الـ Fragment ما زال صالحاً للتفاعل؟ */
    private boolean isFragmentAlive() {
        return isAdded() && !isRemoving() && !isDetached()
                && getActivity() != null && !getActivity().isFinishing()
                && !getActivity().isDestroyed();
    }

    /** ✅ application context آمن للاستخدام من أي thread */
    @Nullable
    private Context getSafeAppContext() {
        try {
            if (getContext() != null) return getContext().getApplicationContext();
        } catch (Exception ignored) {}
        return null;
    }


// ═══════════════════════════════════════════════════════════════════
//  Views Binding
// ═══════════════════════════════════════════════════════════════════

private void bindViews(View view) {
    tvScaleStatus   = view.findViewById(R.id.tvPosScaleStatus);
    tvItemCount     = view.findViewById(R.id.tvPosItemCount);
    tvTotalAmount   = view.findViewById(R.id.tvPosTotalAmount);
    tvCartStatus    = view.findViewById(R.id.tvPosCartStatus);

    tilManualEpc    = view.findViewById(R.id.tilPosManualEpc);
    etManualEpc     = view.findViewById(R.id.etPosManualEpc);
    btnAddManual    = view.findViewById(R.id.btnPosAddManual);
    if (btnAddManual != null) btnAddManual.setVisibility(View.GONE);

    btnCheckout     = view.findViewById(R.id.btnPosCheckout);
    btnClearCart    = view.findViewById(R.id.btnPosClearCart);

    btnPosRefund    = view.findViewById(R.id.btnPosRefund);
    btnPosLastReceipt = view.findViewById(R.id.btnPosLastReceipt);
    btnPosClose     = view.findViewById(R.id.btnPosClose);

    rvCartItems             = view.findViewById(R.id.rvPosCartItems);
    layoutCartEmptyState    = view.findViewById(R.id.layoutCartEmptyState);
    posHeroContainer        = view.findViewById(R.id.posHeroContainer);
    posManualInputContainer = view.findViewById(R.id.posManualInputContainer);
    posSummaryStrip         = view.findViewById(R.id.posSummaryStrip);
    posFooterSticky         = view.findViewById(R.id.posFooterSticky);

    btnPosScanGs1   = view.findViewById(R.id.btnPosScanGs1);
    btnPosScanRfid  = view.findViewById(R.id.btnPosScanRfid);
    btnPosScanManual = view.findViewById(R.id.btnPosScanManual);

    // Scale status click → settings dialog
    if (tvScaleStatus != null) {
        tvScaleStatus.setOnClickListener(v -> {
            if (scaleManager != null) scaleManager.showSettingsDialog();
        });
    }
    if (etManualEpc != null) etManualEpc.clearFocus();
}

/**
 * ✅ إخفاء زر RFID على الأجهزة التي لا تحتوي على قارئ مدمج
 */
private void hideRfidButtonIfUnsupported() {
    try {
        boolean hasRfid = com.example.uhf.tools.DeviceDetector
                .isRfidHardwareSupported();

        if (btnPosScanRfid != null) {
            btnPosScanRfid.setVisibility(hasRfid ? View.VISIBLE : View.GONE);
            Log.i(TAG, hasRfid
                    ? "📡 RFID button visible"
                    : "📱 RFID button hidden (phone mode)");
        }
    } catch (Exception e) {
        Log.w(TAG, "hideRfidButtonIfUnsupported error", e);
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Hero Controller
// ═══════════════════════════════════════════════════════════════════

private void setupHeroController(View view) {
    try {
        heroController = new PosHeroController(
                view.findViewById(R.id.posHeroGlow),
                view.findViewById(R.id.posHeroRing),
                view.findViewById(R.id.ivPosHeroIcon),
                view.findViewById(R.id.ivPosHeroSparkleTop),
                view.findViewById(R.id.ivPosHeroSparkleBottom),
                view.findViewById(R.id.tvPosHeroTitleAr),
                view.findViewById(R.id.tvPosHeroTitleEn),
                view.findViewById(R.id.posHeroDivider),
                view.findViewById(R.id.tvPosHeroMessage),
                view.findViewById(R.id.tvPosHeroHint),
                view.findViewById(R.id.tvPosRfidCount),
                view.findViewById(R.id.btnPosScanGs1),
                view.findViewById(R.id.btnPosScanRfid),
                view.findViewById(R.id.btnPosScanManual),
                view.findViewById(R.id.ivPosGs1Icon),
                view.findViewById(R.id.ivPosRfidIcon),
                view.findViewById(R.id.ivPosManualIcon),
                view.findViewById(R.id.tvPosGs1Label),
                view.findViewById(R.id.tvPosRfidLabel),
                view.findViewById(R.id.tvPosManualLabel)
        );
    } catch (Exception e) {
        Log.e(TAG, "setupHeroController error", e);
    }
}

// ═══════════════════════════════════════════════════════════════════
//  RecyclerView — adapter واحد للجلسة
// ═══════════════════════════════════════════════════════════════════

private void setupRecyclerView() {
    try {
        if (rvCartItems == null || cartManager == null) return;

        rvCartItems.setLayoutManager(new LinearLayoutManager(getContext()));
        rvCartItems.setHasFixedSize(false);

        // ✅ adapter واحد — لا يحمل قائمة حيّة
        cartAdapter = new CartAdapter(
                position -> {
                    // ✅ الحذف عبر cartManager — listener سيُحدّث UI
                    if (cartManager != null) {
                        cartManager.removeItemAt(position);
                    }
                },
                isArabic,
                priceManager
        );

        rvCartItems.setAdapter(cartAdapter);

        // ✅ ربط أولي بحالة السلة الحالية
        cartAdapter.replaceItems(cartManager.getItems());
        cartAdapter.notifyDataSetChanged();

        Log.d(TAG, "✅ CartAdapter initialized (single instance)");

    } catch (Exception e) {
        Log.e(TAG, "setupRecyclerView error", e);
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Cart Listener — إشعار موحّد من PosCartManager
// ═══════════════════════════════════════════════════════════════════

private void setupCartListener() {
    if (cartManager == null) return;

    // ✅ listener واحد — يستقبل كل التغييرات
    cartListener = (snapshot, type, position) -> {
        safeRun(() -> renderCart(snapshot, type, position));
    };

    cartManager.addListener(cartListener);
    Log.d(TAG, "✅ Cart listener registered (unified)");
}

// ═══════════════════════════════════════════════════════════════════
//  renderCart — الدالة الموحّدة لتحديث UI
// ═══════════════════════════════════════════════════════════════════

/**
 * ✅ [v6.0] الدالة الوحيدة لعرض السلة.
 *
 * تُستدعى من:
 *   - PosCartManager listener
 *   - onViewCreated (ربط أولي)
 *   - onResume (ربط أولي)
 *
 * @param snapshot صورة ثابتة للحالة الجديدة
 * @param type     نوع التغيير (null = ربط أولي)
 * @param position فهرس التغيير (أو -1)
 */
private void renderCart(@NonNull PosCartManager.CartSnapshot snapshot,
                        @Nullable PosCartManager.ChangeType type,
                        int position) {
    if (!isFragmentAlive()) return;

    try {
        final int count = snapshot.count;

        // ═══════════════════════════════════════════════════
        //  1) تحديث الـ Adapter
        // ═══════════════════════════════════════════════════
        if (cartAdapter != null) {
            // أولاً: استبدل البيانات
            cartAdapter.replaceItems(snapshot.items);

            // ثانياً: notify حسب نوع التغيير
            if (type != null) {
                switch (type) {
                    case ITEM_ADDED:
                        cartAdapter.notifyItemInserted(0);
                        if (rvCartItems != null) {
                            rvCartItems.scrollToPosition(0);
                        }
                        break;

                    case ITEM_REMOVED:
                        if (position >= 0) {
                            cartAdapter.notifyItemRemoved(position);
                        } else {
                            cartAdapter.notifyDataSetChanged();
                        }
                        break;

                    case ITEM_UPDATED:
                        if (position >= 0) {
                            cartAdapter.notifyItemChanged(position);
                        }
                        break;

                    case CART_CLEARED:
                        cartAdapter.notifyDataSetChanged();
                        break;
                }
            } else {
                // ربط أولي — إعادة رسم كامل
                cartAdapter.notifyDataSetChanged();
            }
        }

        // ═══════════════════════════════════════════════════
        //  2) تبديل Hero / Cart Visibility
        // ═══════════════════════════════════════════════════
        if (posHeroContainer != null) {
            posHeroContainer.setVisibility(count == 0
                    ? View.VISIBLE : View.GONE);
        }
        if (rvCartItems != null) {
            rvCartItems.setVisibility(count > 0
                    ? View.VISIBLE : View.GONE);
        }
        if (layoutCartEmptyState != null) {
            layoutCartEmptyState.setVisibility(count == 0
                    ? View.VISIBLE : View.GONE);
        }

        // ═══════════════════════════════════════════════════
        //  3) Footer Sticky — source of truth واحدة
        // ═══════════════════════════════════════════════════
        updateFooterVisibility(count > 0);

        // ═══════════════════════════════════════════════════
        //  4) النصوص والملخص
        // ═══════════════════════════════════════════════════
        if (tvItemCount != null) {
            tvItemCount.setText(String.valueOf(count));
        }

        if (tvTotalAmount != null) {
            tvTotalAmount.setText(buildTotalText(count, snapshot.totalUsd));
            tvTotalAmount.setVisibility(View.VISIBLE);
        }

        if (tvCartStatus != null) {
            if (count == 0) {
                tvCartStatus.setText(getString(R.string.pos_cart_empty));
            } else if (snapshot.hasUnavailable) {
                tvCartStatus.setText(getString(R.string.pos_cart_unavailable));
            } else {
                tvCartStatus.setText(getString(R.string.pos_cart_ready));
            }
        }

        if (posSummaryStrip != null) {
            posSummaryStrip.setSelected(count > 0);
        }

        // ═══════════════════════════════════════════════════
        //  5) CTA buttons
        // ═══════════════════════════════════════════════════
        if (btnCheckout != null) {
            boolean enabled = count > 0
                    && !snapshot.hasUnavailable
                    && hasPosPermission();
            btnCheckout.setEnabled(enabled);
            btnCheckout.setAlpha(enabled ? 1f : 0.5f);
        }
        if (btnClearCart != null) {
            btnClearCart.setEnabled(count > 0);
            btnClearCart.setAlpha(count > 0 ? 1f : 0.5f);
        }

    } catch (Exception e) {
        Log.e(TAG, "renderCart error", e);
    }
}

/**
 * ✅ Footer visibility — single source of truth
 * لا يتداخل مع animations أخرى.
 */
private void updateFooterVisibility(boolean shouldBeVisible) {
    if (posFooterSticky == null) return;

    try {
        int currentVisibility = posFooterSticky.getVisibility();

        if (shouldBeVisible) {
            if (currentVisibility == View.VISIBLE) {
                // ✅ ظاهر بالفعل — لا تفعل شيئاً (منع تراكم animations)
                return;
            }
            // أظهره بـ animation
            posFooterSticky.setVisibility(View.VISIBLE);
            posFooterSticky.setAlpha(0f);
            posFooterSticky.setTranslationY(
                    posFooterSticky.getHeight() > 0
                            ? posFooterSticky.getHeight()
                            : 300f);
            posFooterSticky.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(250)
                    .setInterpolator(
                            new android.view.animation.DecelerateInterpolator())
                    .start();
        } else {
            if (currentVisibility != View.VISIBLE) {
                // ✅ مخفي بالفعل — لا تفعل شيئاً
                return;
            }
            // أخفه بـ animation
            posFooterSticky.animate()
                    .alpha(0f)
                    .translationY(posFooterSticky.getHeight())
                    .setDuration(200)
                    .setInterpolator(
                            new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        if (posFooterSticky != null) {
                            posFooterSticky.setVisibility(View.GONE);
                            posFooterSticky.setAlpha(1f);
                            posFooterSticky.setTranslationY(0f);
                        }
                    })
                    .start();
        }
    } catch (Exception e) {
        Log.e(TAG, "updateFooterVisibility error", e);
    }
}

/**
 * ✅ بناء نص الإجمالي — مضمون 100%
 */
private String buildTotalText(int count, double totalUsd) {
    if (count <= 0) {
        String def = getString(R.string.pos_default_amount);
        return (def == null || def.trim().isEmpty()) ? "0" : def;
    }

    if (priceManager != null) {
        try {
            String formatted = priceManager.formatPriceDefault(totalUsd);
            if (formatted != null
                    && !formatted.trim().isEmpty()
                    && !"—".equals(formatted.trim())
                    && hasDigit(formatted)) {
                return formatted;
            }
        } catch (Exception e) {
            Log.w(TAG, "formatPriceDefault error", e);
        }

        // Fallback عبر priceManager
        try {
            String currency = priceManager.getDefaultCurrency();
            double rate = priceManager.getExchangeRate(currency);
            double local = totalUsd * rate;
            return String.format(Locale.US, "%,.2f %s", local, currency);
        } catch (Exception e) {
            Log.w(TAG, "fallback calc error", e);
        }
    }

    // Fallback نهائي — USD
    return String.format(Locale.US, "$%,.2f", totalUsd);
}

private static boolean hasDigit(String s) {
    if (s == null) return false;
    for (int i = 0; i < s.length(); i++) {
        if (Character.isDigit(s.charAt(i))) return true;
    }
    return false;
}

// ═══════════════════════════════════════════════════════════════════
//  Listeners Setup
// ═══════════════════════════════════════════════════════════════════

private void setupListeners() {
    try {
        setupManualInputListeners();
        setupScanButtonListeners();
        setupActionButtonListeners();
    } catch (Exception e) {
        Log.e(TAG, "setupListeners error", e);
    }
}

private void setupManualInputListeners() {
    if (etManualEpc == null) return;

    // ✅ Enter / Search → submit
    etManualEpc.setOnEditorActionListener((v, actionId, event) -> {
        if (actionId == EditorInfo.IME_ACTION_DONE
                || actionId == EditorInfo.IME_ACTION_SEARCH
                || (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
            submitManualEpc();
            return true;
        }
        return false;
    });

    // ✅ Auto-submit بعد 350ms من آخر حرف (≥ 6 أحرف)
    final Runnable[] pendingAuto = new Runnable[1];
    etManualEpc.addTextChangedListener(new android.text.TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
        @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}

        @Override
        public void afterTextChanged(android.text.Editable s) {
            if (pendingAuto[0] != null) {
                mainHandler.removeCallbacks(pendingAuto[0]);
            }
            final String code = s.toString().trim();
            if (code.length() < AUTO_ADD_MIN_LEN) return;

            pendingAuto[0] = () -> {
                if (etManualEpc != null
                        && code.equals(etManualEpc.getText().toString().trim())
                        && isFragmentAlive()) {
                    submitManualEpc();
                }
            };
            mainHandler.postDelayed(pendingAuto[0], AUTO_ADD_DELAY_MS);
        }
    });
}

private void setupScanButtonListeners() {
    // ⭐ GS1 — ماسح الباركود
    if (btnPosScanGs1 != null) {
        btnPosScanGs1.setOnClickListener(v -> {
            UIHelper.hideKeyboard(getActivity());
            switchScanMode(ScanMode.GS1);
        });
    }

    // ⭐ RFID — إعلامي فقط + Long-press للإعدادات
    if (btnPosScanRfid != null) {
        // ✅ ضغطة عادية → رسالة توجيه
        btnPosScanRfid.setOnClickListener(v -> {
            UIHelper.hideKeyboard(getActivity());
            showToast(isArabic
                    ? "🎯 استخدم زر الزناد في القارئ للمسح"
                    : "🎯 Use the physical trigger to scan");
        });

        // ✅ ضغطة مطوّلة → إعدادات قوة POS
        btnPosScanRfid.setOnLongClickListener(v -> {
            UIHelper.hideKeyboard(getActivity());
            openPosPowerSettings();
            return true;
        });
    }

    // ⭐ Manual — Toggle
    if (btnPosScanManual != null) {
        btnPosScanManual.setOnClickListener(v -> {
            UIHelper.hideKeyboard(getActivity());
            switchScanMode(currentScanMode == ScanMode.MANUAL
                    ? ScanMode.NONE : ScanMode.MANUAL);
        });
    }
}


/**
 * ✅ [v6.1] فتح نافذة إعداد قوة قراءة POS.
 *    عند الحفظ، تُطبّق القيمة الجديدة فوراً على القارئ.
 */
private void openPosPowerSettings() {
    if (!isFragmentAlive()) return;

    try {
        Context ctx = getSafeAppContext();
        if (ctx == null) return;

        com.example.uhf.dialog.PosPowerSettingsDialog.show(
                ctx,
                isArabic,
                newPowerDbm -> {
                    Log.i(TAG, "⚡ User set POS power → " + newPowerDbm + " dBm");

                    // ✅ طبّق القيمة الجديدة فوراً
                    applyPosPowerImmediate(newPowerDbm);

                    // ✅ أبلغ المستخدم
                    showToast(isArabic
                            ? "✅ تم تحديث قوة القراءة إلى " + newPowerDbm + " dBm"
                            : "✅ Power updated to " + newPowerDbm + " dBm");
                }
        );
    } catch (Exception e) {
        Log.e(TAG, "openPosPowerSettings error", e);
        showToast(isArabic
                ? "⚠️ تعذر فتح الإعدادات"
                : "⚠️ Could not open settings");
    }
}


private void setupActionButtonListeners() {
    if (btnClearCart != null) {
        btnClearCart.setOnClickListener(v -> {
            UIHelper.hideKeyboard(getActivity());
            if (cartManager != null) cartManager.clear();
        });
    }

    if (btnCheckout != null) {
        btnCheckout.setOnClickListener(v -> {
            UIHelper.hideKeyboard(getActivity());
            processCheckoutAndStockUpdate();
        });
    }

    if (btnPosRefund != null) {
        btnPosRefund.setOnClickListener(v -> {
            UIHelper.hideKeyboard(getActivity());
            showRefundDialog();
        });
    }

    if (btnPosLastReceipt != null) {
        btnPosLastReceipt.setOnClickListener(v -> {
            UIHelper.hideKeyboard(getActivity());
            showLastReceiptDialog();
        });
    }

    if (btnPosClose != null) {
        btnPosClose.setOnClickListener(v -> {
            UIHelper.hideKeyboard(getActivity());
            handleCustomBack();
        });
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Manual Input
// ═══════════════════════════════════════════════════════════════════

private void showManualInput() {
    try {
        if (heroController != null) {
            heroController.setState(PosHeroController.State.MANUAL_ENTRY);
        }
        if (posManualInputContainer != null) {
            posManualInputContainer.setVisibility(View.VISIBLE);
            posManualInputContainer.startAnimation(
                    android.view.animation.AnimationUtils.loadAnimation(
                            requireContext(), R.anim.pos_slide_up));
        }
        if (etManualEpc != null) {
            etManualEpc.requestFocus();
            showKeyboard(etManualEpc);
        }
    } catch (Exception e) {
        Log.e(TAG, "showManualInput error", e);
    }
}

private void hideManualInput() {
    try {
        if (posManualInputContainer != null) {
            posManualInputContainer.setVisibility(View.GONE);
        }
        if (etManualEpc != null) {
            etManualEpc.setText("");
            etManualEpc.clearFocus();
        }
    } catch (Exception e) {
        Log.e(TAG, "hideManualInput error", e);
    }
}

private void submitManualEpc() {
    try {
        if (!hasPosPermission()) {
            showToast(getString(R.string.pos_msg_no_permission));
            return;
        }

        UIHelper.hideKeyboard(getActivity());

        String epc = etManualEpc != null
                ? etManualEpc.getText().toString().trim() : "";

        if (epc.isEmpty()) {
            showToast(getString(R.string.pos_msg_not_found));
            return;
        }

        if (cartManager != null && cartManager.isIdScanned(epc)) {
            showToast(getString(R.string.pos_msg_already_in_cart));
            playAlertSound(2);
            return;
        }

        processRingEpc(epc, "manual");

        if (etManualEpc != null) etManualEpc.setText("");
        hideManualInput();

        if (heroController != null) {
            heroController.setState(PosHeroController.State.IDLE);
        }
    } catch (Exception e) {
        Log.e(TAG, "submitManualEpc error", e);
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Process Item — Lookup + Parse
// ═══════════════════════════════════════════════════════════════════

private void processRingEpc(final String code, final String source) {
    if (code == null || code.trim().isEmpty() || !isFragmentAlive()) return;
    final String cleanCode = code.trim();

    if (!pendingEpcs.add(cleanCode)) return;

    playAlertSound(1);

    safeRun(() -> {
        if (tvCartStatus != null) {
            tvCartStatus.setText(getString(R.string.pos_msg_searching));
        }
    });

    try {
        miraExecutor.getNetworkExecutor().execute(() -> {
            try {
                Context context = getSafeAppContext();
                if (context == null) return;

                MiraItemSyncManager syncManager =
                        MiraItemSyncManager.getInstance(context);
                final int branchId = getActiveBranchId();

                syncManager.findItem(cleanCode, branchId,
                        new MiraItemSyncManager.ItemLookupCallback() {
                    @Override
                    public void onItemFound(JSONObject item, boolean fromServer) {
                        safeRun(() -> {
                            if (!isFragmentAlive()) return;

                            int itemBranchId = item.optInt("branch_id", -1);
                            if (itemBranchId > 0 && itemBranchId != branchId) {
                                showToast(getString(R.string.pos_msg_other_branch));
                                playAlertSound(2);
                                resetHeroAfterDelay();
                                return;
                            }
                            parseItemResponse(cleanCode, item, source);
                        });
                    }

                    @Override
public void onItemNotFound() {
    safeRun(() -> {
        if (!isFragmentAlive()) return;
        showToast(getString(R.string.pos_msg_not_found));
        playAlertSound(2);
        setHeroError();
        resetHeroAfterDelay();

        // ✅ [v6.2] بطاقة "غير موجود"
        showScanFeedback(
                isArabic ? "❌ لم يتم العثور" : "❌ Not Found",
                cleanCode,
                null,
                android.R.drawable.ic_dialog_alert,
                0xFFDC2626   // أحمر
        );
    });
}
                });
            } catch (Exception e) {
                Log.e(TAG, "processRingEpc network error", e);
                safeRun(() -> {
                    if (!isFragmentAlive()) return;
                    setHeroError();
                    resetHeroAfterDelay();
                });
            } finally {
                pendingEpcs.remove(cleanCode);
            }
        });
    } catch (Exception e) {
        Log.e(TAG, "processRingEpc submit error", e);
        pendingEpcs.remove(cleanCode);
    }
}

/**
 * ✅ تحليل استجابة السيرفر — يُنفَّذ على main thread عبر safeRun
 *    من processRingEpc. لا forceShowCart. الإضافة عبر cartManager
 *    تُطلق الإشعار الموحّد → renderCart.
 */
private void parseItemResponse(String code, JSONObject data, String source) {
    try {
        JSONObject item = data.has("item") ? data.optJSONObject("item") : data;
        if (item == null) {
            showToast(getString(R.string.pos_msg_not_found));
            playAlertSound(2);
            setHeroError();
            resetHeroAfterDelay();
            return;
        }

        String vaultStatus = item.optString("status",
                item.optString("vault_status", "")).toLowerCase();

        if ("sold".equals(vaultStatus) || "exited".equals(vaultStatus)) {
    showToast(getString(R.string.pos_msg_already_sold));
    playAlertSound(2);
    setHeroError();
    resetHeroAfterDelay();

    // ✅ [v6.2] بطاقة رفض
    showScanFeedback(
            isArabic ? "❌ القطعة مباعة" : "❌ Already Sold",
            extractString(item, "title", "item_title"),
            null,
            android.R.drawable.ic_delete,
            0xFFDC2626   // أحمر
    );
    return;
}

        boolean isAvailable = !vaultStatus.equals("sold")
                && !vaultStatus.equals("reserved");

        // ═══════════════════════════════════════════════════
        //  استخراج بيانات القطعة
        // ═══════════════════════════════════════════════════
        String title = item.optString("title",
                item.optString("item_title",
                        isArabic ? "قطعة" : "Item"));
        String karat = item.optString("karat",
                item.optString("gold_karat", "21K"));
        double weight = item.optDouble("weight",
                item.optDouble("gross_weight",
                        item.optDouble("gross_weight_grams", 0)));

        int karatNum = getKaratNumber(karat);

        PosPriceResolver.PriceResult priceResult =
                priceResolver.resolve(item, karatNum, weight);
        double price = priceResult.priceUsd;

        Log.d(TAG, "💰 Price resolved: " + price
                + " (source=" + priceResult.source + ")");

        // ═══════════════════════════════════════════════════
        //  بناء CartItem
        // ═══════════════════════════════════════════════════
        CartItem cartItem = new CartItem(code, title, karat, weight,
                liveScaleWeight, price, isAvailable,
                vaultStatus.toUpperCase());

        cartItem.serialNo = extractString(item,
                "serial", "serial_number", "serialNo", "sn");
        cartItem.gtin = extractString(item,
                "gtin", "gtin13", "barcode", "code");
        cartItem.location = extractString(item,
                "location", "current_location", "zone");
        cartItem.itemId = item.optInt("id",
                item.optInt("item_id", item.optInt("product_id", 0)));

        String tid = extractString(item, "tid", "tag_tid", "rfid_tid");
        if (!tid.isEmpty() && cartItem.serialNo.isEmpty()) {
            cartItem.serialNo = tid;
        }
        cartItem.tid = tid;

        String rfidEpcFromServer = extractString(item, "rfid_epc", "epc_final");
        if (!rfidEpcFromServer.isEmpty()) {
            cartItem.rfidEpc = rfidEpcFromServer;
            cartItem.epc = rfidEpcFromServer;
        } else {
            cartItem.rfidEpc = code;
        }

        // ═══════════════════════════════════════════════════
        //  فحوصات ما قبل الإضافة
        // ═══════════════════════════════════════════════════
        boolean alreadyInCart = cartManager != null
                && cartManager.containsItem(cartItem.epc,
                cartItem.serialNo, cartItem.gtin);

        if (alreadyInCart) {
    showToast(getString(R.string.pos_msg_already_in_cart));
    playAlertSound(2);
    resetHeroAfterDelay();

    // ✅ [v6.2] بطاقة تحذير
    showScanFeedback(
            isArabic ? "⚠️ مكررة في السلة" : "⚠️ Already in Cart",
            title,
            null,
            android.R.drawable.ic_dialog_alert,
            0xFF3B82F6   // أزرق
    );
    return;
}

        if (!isAvailable) {
    showToast(getString(R.string.pos_msg_unavailable));
    resetHeroAfterDelay();

    // ✅ [v6.2] بطاقة تحذير
    showScanFeedback(
            isArabic ? "⚠️ القطعة غير متاحة" : "⚠️ Item Unavailable",
            title,
            null,
            android.R.drawable.ic_dialog_alert,
            0xFFF59E0B   // كهرماني
    );
    return;
}

        // ═══════════════════════════════════════════════════
        //  ✅ الإضافة — كل UI يتحدّث تلقائياً عبر renderCart
        // ═══════════════════════════════════════════════════
        int pos = cartManager.addItem(cartItem);
        if (pos < 0) {
            showToast(getString(R.string.pos_msg_already_in_cart));
            resetHeroAfterDelay();
            return;
        }

        // ✅ الأصوات والـ toast
playAlertSound(1);
showToast(getString(R.string.pos_msg_added, title));

// ✅ [v6.2] بطاقة تأكيد احترافية
showScanFeedback(
        title,
        karat + " • " + String.format(Locale.US, "%.2fg", weight),
        buildTotalText(1, price),
        R.drawable.ic_pos_cart_gold,
        0xFF22C55E   // أخضر
);

        // ✅ Hero state
        if (heroController != null) {
            heroController.setState(PosHeroController.State.ITEM_ADDED);
            if ("rfid".equals(source)) {
                heroController.incrementRfidCount();
            }
        }
        resetHeroAfterDelay();

    } catch (Exception e) {
        Log.e(TAG, "parseItemResponse error", e);
        safeRun(() -> {
            if (!isFragmentAlive()) return;
            showToast(getString(R.string.pos_msg_not_found));
            resetHeroAfterDelay();
        });
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Hero Helpers
// ═══════════════════════════════════════════════════════════════════

private void setHeroError() {
    if (heroController != null) {
        heroController.setState(PosHeroController.State.ERROR);
    }
}

private void resetHeroAfterDelay() {
    mainHandler.postDelayed(() -> {
        if (!isFragmentAlive() || heroController == null) return;
        PosHeroController.State s = heroController.getState();
        if (s == PosHeroController.State.ITEM_ADDED
                || s == PosHeroController.State.ERROR) {
            heroController.setState(PosHeroController.State.IDLE);
        }
    }, ITEM_ADDED_DISPLAY_MS);
}

// ═══════════════════════════════════════════════════════════════════
//  Scale
// ═══════════════════════════════════════════════════════════════════

private void initSmartScale() {
    try {
        scaleManager = new SmartScaleManager(requireContext(),
                new SmartScaleManager.ScaleCallback() {
            @Override
            public void onWeightReceived(double weight) {
                liveScaleWeight = weight;
                safeRun(() -> {
                    if (tvScaleStatus != null) {
                        tvScaleStatus.setText(String.format(Locale.US,
                                "⚖️ %.2fg", weight));
                    }
                    updateTopCartItemScaleWeight(weight);
                });
            }

            @Override
            public void onScaleConnected(String name) {
                safeRun(() -> {
                    if (tvScaleStatus != null) {
                        tvScaleStatus.setText("⚖️ " + name);
                    }
                    showToast(getString(R.string.pos_msg_scale_connected, name));
                });
            }

            @Override
            public void onScaleDisconnected() {
                liveScaleWeight = 0.0;
                safeRun(() -> {
                    if (tvScaleStatus != null) {
                        tvScaleStatus.setText(R.string.pos_scale_offline);
                    }
                });
            }

            @Override
            public void onScaleError(String error) {
                Log.e(TAG, "Scale Error: " + error);
            }
        });

        simulationHelper = new ScaleSimulationHelper(requireContext(),
                new ScaleSimulationHelper.SimulationCallback() {
            @Override
            public void onWeightReceived(double weight, String label) {
                safeRun(() -> {
                    liveScaleWeight = weight;
                    if (tvScaleStatus != null) {
                        tvScaleStatus.setText(String.format(Locale.US,
                                "⚖️ %.2fg", weight));
                    }
                    updateTopCartItemScaleWeight(weight);
                });
            }
        });
    } catch (Exception e) {
        Log.e(TAG, "initSmartScale error", e);
    }
}

/**
 * ✅ تحديث وزن أعلى قطعة في السلة — عبر cartManager
 *    حتى يمرّ عبر renderCart (تحديث موحّد).
 */
private void updateTopCartItemScaleWeight(double currentScaleWeight) {
    if (cartManager == null || cartManager.isEmpty()
            || currentScaleWeight <= 0) {
        return;
    }
    try {
        List<CartItem> items = cartManager.getItems();
        if (items.isEmpty()) return;

        CartItem topItem = items.get(0);
        if (topItem == null) return;
        if (topItem.scaleWeight == currentScaleWeight) return;

        // ✅ عدّل مباشرة
        topItem.scaleWeight = currentScaleWeight;

        // ✅ أبلغ cartManager (يطلق ITEM_UPDATED → renderCart)
        cartManager.updateItemAt(0, topItem);

    } catch (Exception e) {
        Log.e(TAG, "updateTopCartItemScaleWeight error", e);
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Scan Feedback Card — التنفيذ [v6.2]
// ═══════════════════════════════════════════════════════════════════

/** تحويل dp → pixels */
private int dp(int value) {
    try {
        return Math.round(value * getResources()
                .getDisplayMetrics().density);
    } catch (Exception e) {
        return value;
    }
}

/**
 * ✅ [v6.2] إنشاء بطاقة التأكيد برمجياً.
 *    تُضاف إلى الجزء العلوي من الشاشة وتظهر عند كل مسح.
 */
private void initScanFeedbackCard(@NonNull View fragmentRoot) {
    try {
        if (!(fragmentRoot instanceof ViewGroup)) return;
        ViewGroup root = (ViewGroup) fragmentRoot;

        // ═══════════════════════════════════════════════════
        //  الحاوية (تملأ الشاشة، للتمركز)
        // ═══════════════════════════════════════════════════
        FrameLayout overlay = new FrameLayout(requireContext());
        overlay.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setClickable(false);
        overlay.setFocusable(false);
        overlay.setVisibility(View.GONE);

        // ═══════════════════════════════════════════════════
        //  البطاقة
        // ═══════════════════════════════════════════════════
        MaterialCardView card = new MaterialCardView(requireContext());
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(dp(16), dp(72), dp(16), 0);
        cardParams.gravity = android.view.Gravity.TOP;
        card.setLayoutParams(cardParams);
        card.setRadius(dp(16));
        card.setCardElevation(dp(8));
        card.setCardBackgroundColor(0xFFFFFFFF);
        card.setClickable(false);

        // ═══════════════════════════════════════════════════
        //  صف المحتوى
        // ═══════════════════════════════════════════════════
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // أيقونة
        ImageView icon = new ImageView(requireContext());
        LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(dp(44), dp(44));
        icon.setLayoutParams(iconParams);
        icon.setImageResource(R.drawable.ic_pos_cart_gold);
        row.addView(icon);

        // عمود النصوص
        LinearLayout textCol = new LinearLayout(requireContext());
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMargins(dp(14), 0, dp(14), 0);
        textCol.setLayoutParams(textParams);

        TextView title = new TextView(requireContext());
        title.setTextSize(15);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF1A1A1A);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(title);

        TextView details = new TextView(requireContext());
        details.setTextSize(12);
        details.setTextColor(0xFF666666);
        details.setMaxLines(1);
        details.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams detParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        detParams.topMargin = dp(3);
        details.setLayoutParams(detParams);
        textCol.addView(details);

        TextView price = new TextView(requireContext());
        price.setTextSize(17);
        price.setTypeface(null, Typeface.BOLD);
        price.setTextColor(0xFFB89456);
        LinearLayout.LayoutParams priceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        priceParams.topMargin = dp(4);
        price.setLayoutParams(priceParams);
        textCol.addView(price);

        row.addView(textCol);

        // شريط جانبي ملوّن
        View accent = new View(requireContext());
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(
                dp(4), ViewGroup.LayoutParams.MATCH_PARENT);
        accent.setLayoutParams(accentParams);
        accent.setBackgroundColor(0xFF22C55E);
        row.addView(accent);

        card.addView(row);
        overlay.addView(card);
        root.addView(overlay);

        // ✅ حفظ المراجع
        this.scanFeedbackOverlay = overlay;
        this.scanFeedbackCard = card;
        this.ivFeedbackIcon = icon;
        this.tvFeedbackTitle = title;
        this.tvFeedbackDetails = details;
        this.tvFeedbackPrice = price;
        this.vFeedbackAccent = accent;

        Log.d(TAG, "✅ Scan feedback card initialized");

    } catch (Exception e) {
        Log.e(TAG, "initScanFeedbackCard error", e);
    }
}

/**
 * ✅ [v6.2] عرض بطاقة تأكيد مؤقتة.
 *
 * @param title       العنوان (اسم القطعة أو الرسالة)
 * @param details     التفاصيل (العيار • الوزن)
 * @param price       السعر (null = لا يُعرض)
 * @param iconRes     أيقونة
 * @param accentColor لون الشريط الجانبي والأيقونة
 */
private void showScanFeedback(@NonNull String title,
                              @Nullable String details,
                              @Nullable String price,
                              int iconRes,
                              int accentColor) {
    if (!isFragmentAlive() || scanFeedbackOverlay == null) return;

    try {
        // إلغاء الإخفاء السابق
        if (pendingHideFeedback != null) {
            mainHandler.removeCallbacks(pendingHideFeedback);
            pendingHideFeedback = null;
        }

        // تحديث المحتوى
        if (tvFeedbackTitle != null) {
            tvFeedbackTitle.setText(title);
        }

        if (tvFeedbackDetails != null) {
            if (details == null || details.isEmpty()) {
                tvFeedbackDetails.setVisibility(View.GONE);
            } else {
                tvFeedbackDetails.setVisibility(View.VISIBLE);
                tvFeedbackDetails.setText(details);
            }
        }

        if (tvFeedbackPrice != null) {
            if (price == null || price.isEmpty()) {
                tvFeedbackPrice.setVisibility(View.GONE);
            } else {
                tvFeedbackPrice.setVisibility(View.VISIBLE);
                tvFeedbackPrice.setText(price);
                tvFeedbackPrice.setTextColor(accentColor);
            }
        }

        if (ivFeedbackIcon != null) {
            ivFeedbackIcon.setImageResource(iconRes);
            ivFeedbackIcon.setColorFilter(accentColor);
        }

        if (vFeedbackAccent != null) {
            vFeedbackAccent.setBackgroundColor(accentColor);
        }

        // إظهار بأنيميشن
        final View card = scanFeedbackCard;
        final View overlay = scanFeedbackOverlay;

        overlay.setVisibility(View.VISIBLE);
        if (card != null) {
            card.setAlpha(0f);
            card.setTranslationY(-dp(20));
            card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(250)
                    .setInterpolator(
                            new android.view.animation.DecelerateInterpolator())
                    .start();
        }

        // إخفاء تلقائي بعد 2.5 ثانية
        pendingHideFeedback = () -> {
            if (card == null || overlay == null) return;
            card.animate()
                    .alpha(0f)
                    .translationY(-dp(20))
                    .setDuration(200)
                    .withEndAction(() -> {
                        if (overlay != null) {
                            overlay.setVisibility(View.GONE);
                        }
                    })
                    .start();
            pendingHideFeedback = null;
        };
        mainHandler.postDelayed(pendingHideFeedback, 2500);

    } catch (Exception e) {
        Log.e(TAG, "showScanFeedback error", e);
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Utilities (تُستخدم في كل الأجزاء)
// ═══════════════════════════════════════════════════════════════════

private void showToast(String msg) {
    PosUIManager.toast(getContext(), msg);
}

/**
 * ✅ تنفيذ آمن على main thread — مع فحص lifecycle.
 */
private void safeRun(Runnable r) {
    if (r == null) return;
    try {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (isFragmentAlive()) {
                    try { r.run(); }
                    catch (Exception e) {
                        Log.e(TAG, "safeRun inner error", e);
                    }
                }
            });
        }
    } catch (Exception e) {
        Log.e(TAG, "safeRun error", e);
    }
}

private void playAlertSound(int id) {
    try {
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).playSound(id);
        }
    } catch (Exception e) {
        Log.e(TAG, "playAlertSound error", e);
    }
}

private int getActiveBranchId() {
    try {
        return branchesManager != null
                ? branchesManager.getActiveBranchId() : 1;
    } catch (Exception e) {
        return 1;
    }
}

private boolean hasPosPermission() {
    try {
        return permissionManager != null && (
                permissionManager.canView("pos")
                        || permissionManager.isPosOperator()
                        || permissionManager.isManager());
    } catch (Exception e) {
        return false;
    }
}

private int getKaratNumber(String karat) {
    if (karat == null) return 21;
    String clean = karat.trim().toUpperCase(Locale.US).replace("K", "");
    try {
        int num = Integer.parseInt(clean);
        if (num >= 10 && num <= 24) return num;
    } catch (NumberFormatException ignored) {}
    return 21;
}

private String extractString(JSONObject obj, String... keys) {
    if (obj == null || keys == null) return "";
    for (String key : keys) {
        try {
            String v = obj.optString(key, "");
            if (v != null && !v.isEmpty() && !"null".equals(v)) return v;
        } catch (Exception ignored) {}
    }
    return "";
}

/**
 * ✅ إخفاء لوحة المفاتيح بأمان — يستخدمها navigateAwayFromPos.
 */
private void UIKeyboardHide() {
    try {
        if (getActivity() != null) {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                    getActivity().getSystemService(
                            Context.INPUT_METHOD_SERVICE);
            if (imm != null && getView() != null) {
                imm.hideSoftInputFromWindow(getView().getWindowToken(), 0);
            }
        }
    } catch (Exception ignored) {}
}

// ═══════════════════════════════════════════════════════════════════
//  Checkout — الدفع
// ═══════════════════════════════════════════════════════════════════

/**
 * ✅ فتح نافذة الدفع — الحماية الكاملة ضد النقر المزدوج.
 */
private void processCheckoutAndStockUpdate() {
    try {
        if (!hasPosPermission()) {
            showToast(getString(R.string.pos_msg_no_permission));
            return;
        }
        if (cartManager == null || cartManager.isEmpty()) {
            showToast(getString(R.string.pos_msg_cart_empty_toast));
            return;
        }
        if (isCheckoutDialogShowing) return;
        if (!isFragmentAlive()) return;

        UIHelper.hideKeyboard(getActivity());
        isCheckoutDialogShowing = true;

        PosCartManager.CartSnapshot snap = cartManager.snapshot();

        POSCheckoutDialog dialog = POSCheckoutDialog.newInstance(
                snap.totalUsd, snap.count);

        dialog.setCheckoutListener(data -> {
            isCheckoutDialogShowing = false;
            if (data != null) executeCheckoutDirectly(data);
        });

        dialog.setOnDismissListener(() -> {
            isCheckoutDialogShowing = false;
        });

        FragmentManager fm = getParentFragmentManager();
        if (fm != null && !fm.isStateSaved()) {
            dialog.show(fm, "POSCheckoutDialog");
        } else {
            isCheckoutDialogShowing = false;
            Log.w(TAG, "Dialog not shown: FragmentManager state saved");
        }
    } catch (Exception e) {
        isCheckoutDialogShowing = false;
        Log.e(TAG, "processCheckoutAndStockUpdate error", e);
    }
}

/**
 * ✅ تنفيذ الدفع على الخيط الشبكي — مع retry عند الفشل.
 */
private void executeCheckoutDirectly(final POSCheckoutDialog.POSCheckoutData data) {
    if (data == null || !isFragmentAlive()) return;

    try {
        setButtonsEnabled(false);

        final AlertDialog loadingDialog =
                PosUIManager.showLoadingDialog(requireContext(), isArabic);

        miraExecutor.getNetworkExecutor().execute(() -> {
            try {
                Context ctx = getSafeAppContext();
                if (ctx == null) return;

                MiraApiClient client = MiraApiClient.getInstance(ctx);
                JSONObject payload = buildCheckoutPayload(client, data);

                MiraApiClient.ApiResponse response = client.checkout(payload);

                safeRun(() -> {
                    PosUIManager.dismissDialog(loadingDialog);
                    setButtonsEnabled(true);

                    if (response != null && response.isSuccess) {
                        handleSaleSuccess(response, data, false);
                    } else if (response != null && response.isNetworkError) {
                        handleSaleSuccess(response, data, true);
                    } else {
                        String errorMsg = (response != null
                                && response.error != null)
                                ? response.error : "Unknown error";
                        PosUIManager.showRetryDialog(
                                requireContext(),
                                getString(R.string.pos_dialog_retry_title),
                                errorMsg,
                                () -> executeCheckoutDirectly(data),
                                isArabic
                        );
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "executeCheckoutDirectly error", e);
                safeRun(() -> {
                    PosUIManager.dismissDialog(loadingDialog);
                    setButtonsEnabled(true);
                    PosUIManager.showRetryDialog(
                            requireContext(),
                            getString(R.string.pos_dialog_error_title),
                            e.getMessage(),
                            () -> executeCheckoutDirectly(data),
                            isArabic
                    );
                });
            }
        });

    } catch (Exception e) {
        Log.e(TAG, "executeCheckoutDirectly outer error", e);
        setButtonsEnabled(true);
    }
}

/**
 * ✅ معالجة نجاح البيع — بسيط، مختصر، لا يتلاعب بـ UI السلة.
 *    cartManager.clear() سيُطلق renderCart تلقائياً.
 */
private void handleSaleSuccess(final MiraApiClient.ApiResponse response,
                               final POSCheckoutDialog.POSCheckoutData data,
                               final boolean isOffline) {
    if (!isFragmentAlive() || data == null) return;

    try {
        playAlertSound(1);
        lastSaleResponse = response;
        lastSaleResponseTime = System.currentTimeMillis();

        // ✅ لقطة قبل التفريغ — للـ GateSaleBridge
        final List<CartItem> soldItems = new ArrayList<>(cartManager.getItems());
        final long saleId = isOffline
                ? 0L : extractSaleIdFromResponse(response);

        // ✅ إشعار Gate
        Context appCtx = getSafeAppContext();
        if (appCtx != null) {
            GateSaleBridge.onSaleCompleted(
                    appCtx,
                    soldItems,
                    saleId,
                    data.finalAmount,
                    getActiveBranchId(),
                    sessionManager != null ? sessionManager.getUserId() : 0,
                    isOffline
            );
        }

        // ✅ تفريغ السلة — renderCart سيُستدعى تلقائياً
        cartManager.clear();

        // ✅ عرض الإيصال + إشعارات
        if (!isOffline) {
            showReceipt(response);
            PosUIManager.notifyDataChanged(requireContext(),
                    "sale", getActiveBranchId());
            showToast(getString(R.string.pos_msg_sale_success));
        } else {
            showToast(getString(R.string.pos_msg_sale_offline));
            PosUIManager.notifyDataChanged(requireContext(),
                    "sale_offline", getActiveBranchId());
        }

        // ✅ إعادة تعيين Hero
        if (heroController != null) {
            heroController.resetRfidCount();
            heroController.setState(PosHeroController.State.IDLE);
        }
    } catch (Exception e) {
        Log.e(TAG, "handleSaleSuccess error", e);
    }
}

/**
 * ✅ بناء Payload الدفع — كل التحويلات والحقول.
 */
private JSONObject buildCheckoutPayload(MiraApiClient client,
                                        POSCheckoutDialog.POSCheckoutData data)
        throws Exception {

    JSONObject payload = new JSONObject();

    // ═══════════════════════════════════════════════════════
    //  المعلومات الأساسية
    // ═══════════════════════════════════════════════════════
    payload.put("action", "COMPLETE_POS_SALE");
    payload.put("gate_id", client != null ? client.getGateId() : "");
    payload.put("branch_id", getActiveBranchId());
    payload.put("user_id", sessionManager != null
            ? sessionManager.getUserId() : 0);
    payload.put("username", sessionManager != null
            ? sessionManager.getUsername() : "");

    // ═══════════════════════════════════════════════════════
    //  معلومات العميل
    // ═══════════════════════════════════════════════════════
    String customerName = (data.customerName != null
            && !data.customerName.trim().isEmpty())
            ? data.customerName.trim()
            : (isArabic ? "عميل نقدي" : "Cash Customer");

    String customerPhone = (data.customerPhone != null
            && !data.customerPhone.trim().isEmpty()
            && !"—".equals(data.customerPhone.trim()))
            ? data.customerPhone.trim() : "";

    payload.put("customer_name", customerName);
    payload.put("customer_phone", customerPhone);

    JSONObject customerObj = new JSONObject();
    customerObj.put("name", customerName);
    customerObj.put("phone", customerPhone);
    customerObj.put("email", data.customerEmail != null
            ? data.customerEmail : "");
    customerObj.put("address", data.customerAddress != null
            ? data.customerAddress : "");
    customerObj.put("notes", data.customerNotes != null
            ? data.customerNotes : "");
    customerObj.put("payment_method", data.paymentMethod != null
            ? data.paymentMethod : "CASH");
    payload.put("customer", customerObj);

    // ═══════════════════════════════════════════════════════
    //  العناصر
    // ═══════════════════════════════════════════════════════
    JSONArray itemsArr = new JSONArray();
    for (CartItem item : cartManager.getItems()) {
        if (item == null) continue;

        JSONObject obj = item.toJson();
        if (obj.optString("serial_no", "").isEmpty()) {
            String fallback = !item.tid.isEmpty() ? item.tid
                    : !item.rfidEpc.isEmpty() ? item.rfidEpc
                    : item.epc;
            obj.put("serial_no", fallback != null ? fallback : "");
        }
        itemsArr.put(obj);
    }
    payload.put("items", itemsArr);
    payload.put("item_count", cartManager.size());

    // ═══════════════════════════════════════════════════════
    //  الأسعار والعمولة
    // ═══════════════════════════════════════════════════════
    String currencyCode = priceManager.getDefaultCurrency();
    double exchangeRate = priceManager.getExchangeRate(currencyCode);

    double subtotalUsd = data.originalTotal;
    double discountUsd = data.discountAmount;
    double finalUsd    = data.finalAmount;

    double subtotalLocal = priceManager.convertFromUSD(subtotalUsd, currencyCode);
    double discountLocal = priceManager.convertFromUSD(discountUsd, currencyCode);
    double finalLocal    = priceManager.convertFromUSD(finalUsd, currencyCode);

    if (exchangeRate <= 0) {
        exchangeRate = 530.0;
        subtotalLocal = subtotalUsd * exchangeRate;
        discountLocal = discountUsd * exchangeRate;
        finalLocal    = finalUsd * exchangeRate;
    }

    payload.put("subtotal_usd", subtotalUsd);
    payload.put("discount_usd", discountUsd);
    payload.put("total_usd", finalUsd);
    payload.put("total_amount_usd", finalUsd);
    payload.put("subtotal", subtotalLocal);
    payload.put("discount", discountLocal);
    payload.put("total", finalLocal);
    payload.put("total_amount", finalLocal);

    JSONObject payment = new JSONObject();
    payment.put("subtotal_usd", subtotalUsd);
    payment.put("discount_amount_usd", discountUsd);
    payment.put("final_amount_usd", finalUsd);
    payment.put("subtotal", subtotalLocal);
    payment.put("discount_amount", discountLocal);
    payment.put("final_amount", finalLocal);
    payment.put("exchange_rate", exchangeRate);
    payment.put("currency_code", currencyCode);
    payment.put("payment_method", data.paymentMethod != null
            ? data.paymentMethod : "CASH");
    payload.put("payment", payment);

    payload.put("notes", isArabic ? "بيع POS" : "POS Sale");
    return payload;
}

// ═══════════════════════════════════════════════════════════════════
//  Receipt
// ═══════════════════════════════════════════════════════════════════

private void showReceipt(MiraApiClient.ApiResponse response) {
    try {
        if (!isFragmentAlive()) return;

        String receiptJson = "{}";
        if (response != null) {
            if (response.data != null) {
                receiptJson = response.data.toString();
            } else if (response.getBody() != null
                    && !response.getBody().isEmpty()) {
                receiptJson = response.getBody();
            }
        }

        MiraReceiptFragment receiptFragment =
                MiraReceiptFragment.newInstance(receiptJson);

        FragmentManager fm = getParentFragmentManager();
        if (fm != null && !fm.isStateSaved()) {
            receiptFragment.show(fm, "MiraReceiptFragment");
        }
    } catch (Exception e) {
        Log.e(TAG, "showReceipt error", e);
    }
}

private long extractSaleIdFromResponse(MiraApiClient.ApiResponse response) {
    if (response == null || response.data == null) return 0L;
    try {
        long id = response.data.optLong("sale_id",
                response.data.optLong("saleId",
                        response.data.optLong("id", 0L)));
        if (id > 0) return id;

        JSONObject sale = response.data.optJSONObject("sale");
        if (sale != null) {
            id = sale.optLong("id", sale.optLong("sale_id", 0L));
            if (id > 0) return id;
        }

        JSONObject receipt = response.data.optJSONObject("receipt");
        if (receipt != null) {
            id = receipt.optLong("sale_id", receipt.optLong("id", 0L));
            if (id > 0) return id;
        }
    } catch (Exception e) {
        Log.w(TAG, "extractSaleId error", e);
    }
    return 0L;
}

// ═══════════════════════════════════════════════════════════════════
//  Refund — الاسترجاع
// ═══════════════════════════════════════════════════════════════════

private void showRefundDialog() {
    if (!isFragmentAlive()) return;

    try {
        selectedRefundItem.set(null);

        refundDialog = new RefundDialog(requireContext(),
                new RefundDialog.RefundCallback() {
            @Override
            public void onRefundConfirm(String code, String reason) {
                processRefund(code, reason);
            }

            @Override
            public void onBarcodeScan() {
                try {
                    ScanOptions options = new ScanOptions();
                    options.setPrompt(getString(R.string.pos_refund_scan_prompt));
                    options.setBeepEnabled(false);
                    options.setOrientationLocked(true);
                    refundBarcodeLauncher.launch(options);
                } catch (Exception e) {
                    Log.e(TAG, "onBarcodeScan error", e);
                }
            }

            @Override
            public void onCodeChanged(String code) {
                searchItemForRefund(code);
            }
        });
        refundDialog.show();
    } catch (Exception e) {
        Log.e(TAG, "showRefundDialog error", e);
    }
}

private void searchItemForRefund(String code) {
    if (code == null || code.trim().isEmpty()) return;
    final String cleanCode = code.trim();

    selectedRefundItem.set(null);

    try {
        miraExecutor.getNetworkExecutor().execute(() -> {
            try {
                Context ctx = getSafeAppContext();
                if (ctx == null) return;

                JSONObject payload = new JSONObject();
                payload.put("code", cleanCode);
                payload.put("search_type", "auto");
                payload.put("branch_id", getActiveBranchId());
                payload.put("context", "refund_check");
                payload.put("gate_id", apiClient != null
                        ? apiClient.getGateId() : "");
                payload.put("user_id", sessionManager != null
                        ? sessionManager.getUserId() : 0);
                payload.put("username", sessionManager != null
                        ? sessionManager.getUsername() : "");

                MiraApiClient client = MiraApiClient.getInstance(ctx);
                MiraApiClient.ApiResponse response = client.searchItem(payload);

                safeRun(() -> {
                    if (!isFragmentAlive()) return;
                    handleRefundSearchResponse(response, cleanCode);
                });
            } catch (Exception e) {
                Log.e(TAG, "searchItemForRefund error", e);
            }
        });
    } catch (Exception e) {
        Log.e(TAG, "searchItemForRefund submit error", e);
    }
}

private void handleRefundSearchResponse(MiraApiClient.ApiResponse response,
                                        String code) {
    if (!isFragmentAlive()) return;

    if (response != null && response.isSuccess && response.data != null) {
        JSONObject itemData = response.data.optJSONObject("item");
        if (itemData == null) itemData = response.data;

        RefundEligibility eligibility = checkRefundEligibility(itemData);

        if (eligibility.isEligible) {
            selectedRefundItem.set(new RefundItem(
                    itemData.optString("epc", code),
                    itemData.optString("serial",
                            itemData.optString("serial_number", "")),
                    eligibility.itemId,
                    eligibility.saleId,
                    eligibility.originalPrice,
                    itemData.optString("title",
                            itemData.optString("item_title", ""))
            ));

            showRefundItemInfoInDialog(itemData, eligibility);
        } else {
            showRefundItemErrorInDialog(eligibility.reason);
        }
    } else {
        String errorMsg = (response != null && response.error != null)
                ? response.error
                : getString(R.string.pos_msg_not_found);
        showRefundItemErrorInDialog(errorMsg);
    }
}

private RefundEligibility checkRefundEligibility(JSONObject itemData) {
    if (itemData == null) {
        return RefundEligibility.notEligible(isArabic
                ? "⚠️ لا توجد بيانات" : "⚠️ No data");
    }

    try {
        String status = itemData.optString("status",
                itemData.optString("vault_status", "")).toLowerCase();

        if (!"sold".equals(status)) {
            return RefundEligibility.notEligible(isArabic
                    ? "⚠️ القطعة غير مباعة" : "⚠️ Item not sold");
        }

        String lastAction = itemData.optString("last_action", "")
                .toLowerCase();
        if (!lastAction.isEmpty()
                && !"sale".equals(lastAction)
                && !"pos_sale".equals(lastAction)) {
            return RefundEligibility.notEligible(isArabic
                    ? "⚠️ آخر عملية ليست بيعاً"
                    : "⚠️ Last action not a sale");
        }

        String saleDateStr = itemData.optString("sale_date",
                itemData.optString("sold_at", ""));
        if (!saleDateStr.isEmpty()) {
            Long daysDiff = parseDaysDifference(saleDateStr);
            if (daysDiff != null && daysDiff > REFUND_MAX_DAYS) {
                return RefundEligibility.notEligible(isArabic
                        ? "⚠️ انتهت فترة الإرجاع"
                        : "⚠️ Refund period expired");
            }
        }

        if (itemData.optBoolean("is_refunded", false)) {
            return RefundEligibility.notEligible(isArabic
                    ? "⚠️ تم الإرجاع مسبقاً" : "⚠️ Already refunded");
        }

        return RefundEligibility.eligible(
                itemData.optLong("sale_id", 0),
                itemData.optInt("id", itemData.optInt("item_id", 0)),
                itemData.optDouble("retail_price_usd",
                        itemData.optDouble("retail_price", 0)),
                saleDateStr);
    } catch (Exception e) {
        Log.e(TAG, "checkRefundEligibility error", e);
        return RefundEligibility.notEligible(isArabic
                ? "⚠️ خطأ في التحقق" : "⚠️ Validation error");
    }
}

private Long parseDaysDifference(String dateStr) {
    if (dateStr == null || dateStr.isEmpty()) return null;

    String[] patterns = {
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd"
    };
    for (String pattern : patterns) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.US);
            Date d = sdf.parse(dateStr);
            if (d != null) {
                return (System.currentTimeMillis() - d.getTime())
                        / (1000L * 60 * 60 * 24);
            }
        } catch (ParseException ignored) {}
    }
    return null;
}

private void showRefundItemInfoInDialog(JSONObject itemData,
                                        RefundEligibility eligibility) {
    try {
        if (refundDialog == null || !refundDialog.isShowing()) return;

        String title = itemData.optString("title",
                itemData.optString("item_title",
                        isArabic ? "قطعة ذهبية" : "Gold Item"));
        String epc = itemData.optString("epc", "");
        String serialNo = itemData.optString("serial",
                itemData.optString("serial_number", ""));
        String karat = itemData.optString("karat", "21K");
        double weight = itemData.optDouble("weight", 0);

        double originalPrice = eligibility.originalPrice > 0
                ? eligibility.originalPrice
                : itemData.optDouble("retail_price_usd", 0);

        String priceStr = priceManager != null
                ? priceManager.formatPriceDefault(originalPrice)
                : String.format(Locale.US, "$%.2f", originalPrice);

        String details = String.format(Locale.US,
                "EPC: %s\nSerial: %s\nKarat: %s | Weight: %.2fg\nPrice: %s",
                epc.isEmpty() ? "—" : epc,
                serialNo.isEmpty() ? "—" : serialNo,
                karat, weight, priceStr);

        refundDialog.showRefundItemInfo(title, details);
    } catch (Exception e) {
        Log.e(TAG, "showRefundItemInfoInDialog error", e);
    }
}

private void showRefundItemErrorInDialog(String message) {
    try {
        if (refundDialog != null) refundDialog.showRefundItemError(message);
    } catch (Exception e) {
        Log.e(TAG, "showRefundItemErrorInDialog error", e);
    }
}

private void processRefund(final String code, final String reason) {
    if (!isFragmentAlive()) return;

    final AlertDialog loadingDialog =
            PosUIManager.showLoadingDialog(requireContext(), isArabic);
    final RefundItem itemToRefund = selectedRefundItem.get();

    try {
        miraExecutor.getNetworkExecutor().execute(() -> {
            try {
                Context ctx = getSafeAppContext();
                if (ctx == null) return;

                JSONObject payload = new JSONObject();
                payload.put("code", code != null ? code.trim() : "");
                payload.put("epc", itemToRefund != null
                        && itemToRefund.epc != null
                        ? itemToRefund.epc : code);
                payload.put("serial_no", itemToRefund != null
                        && itemToRefund.serialNo != null
                        ? itemToRefund.serialNo : "");
                payload.put("item_id", itemToRefund != null
                        ? itemToRefund.itemId : 0);
                payload.put("sale_id", itemToRefund != null
                        ? itemToRefund.saleId : 0);
                payload.put("original_price_usd",
                        itemToRefund != null ? itemToRefund.originalPrice : 0);
                payload.put("reason", reason != null
                        ? reason : "customer_request");
                payload.put("refund_type", "full");
                payload.put("branch_id", getActiveBranchId());
                payload.put("user_id", sessionManager != null
                        ? sessionManager.getUserId() : 0);
                payload.put("gate_id", apiClient != null
                        ? apiClient.getGateId() : "");
                payload.put("username", sessionManager != null
                        ? sessionManager.getUsername() : "");
                payload.put("timestamp", System.currentTimeMillis());

                MiraApiClient client = MiraApiClient.getInstance(ctx);
                MiraApiClient.ApiResponse response = client.processRefund(payload);

                safeRun(() -> {
                    if (!isFragmentAlive()) {
                        PosUIManager.dismissDialog(loadingDialog);
                        return;
                    }

                    PosUIManager.dismissDialog(loadingDialog);
                    if (refundDialog != null && refundDialog.isShowing()) {
                        refundDialog.dismiss();
                    }

                    if (response != null && response.isSuccess) {
                        restoreItemToCache(code, itemToRefund);

                        final RefundItem refunded = itemToRefund;
                        Context appCtx = getSafeAppContext();
                        if (appCtx != null) {
                            GateSaleBridge.onRefundCompleted(
                                    appCtx,
                                    refunded != null ? refunded.epc : code,
                                    refunded != null ? refunded.serialNo : null,
                                    refunded != null ? refunded.saleId : 0L,
                                    getActiveBranchId(),
                                    sessionManager != null
                                            ? sessionManager.getUserId() : 0,
                                    reason
                            );
                        }

                        PosUIManager.showResultDialog(
                                requireContext(),
                                getString(R.string.pos_dialog_refund_success_title),
                                getString(R.string.pos_dialog_refund_success_msg),
                                true, isArabic);

                        PosUIManager.notifyDataChanged(requireContext(),
                                "refund", getActiveBranchId());
                        selectedRefundItem.set(null);
                    } else {
                        String err = (response != null
                                && response.error != null)
                                ? response.error
                                : getString(R.string.pos_dialog_refund_failed_msg);
                        PosUIManager.showResultDialog(
                                requireContext(),
                                getString(R.string.pos_dialog_refund_failed_title),
                                err, false, isArabic);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "processRefund error", e);
                safeRun(() -> {
                    PosUIManager.dismissDialog(loadingDialog);
                    if (isFragmentAlive()) {
                        PosUIManager.showResultDialog(
                                requireContext(),
                                getString(R.string.pos_dialog_error_title),
                                e.getMessage(), false, isArabic);
                    }
                });
            }
        });
    } catch (Exception e) {
        Log.e(TAG, "processRefund submit error", e);
    }
}

private void restoreItemToCache(String code, RefundItem item) {
    try {
        Context ctx = getSafeAppContext();
        if (ctx == null) return;

        MiraLocalItemCache cache = MiraLocalItemCache.getInstance(ctx);

        java.util.Set<String> ids = new java.util.HashSet<>();
        if (item != null) {
            if (item.epc != null && !item.epc.isEmpty()) ids.add(item.epc);
            if (item.serialNo != null && !item.serialNo.isEmpty()) {
                ids.add(item.serialNo);
            }
        }
        if (code != null && !code.isEmpty()) ids.add(code);

        for (String id : ids) {
            cache.markItemAsAvailable(id);
        }
    } catch (Exception e) {
        Log.e(TAG, "restoreItemToCache error", e);
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Public APIs
// ═══════════════════════════════════════════════════════════════════

public void showLastReceiptDialog() {
    try {
        if (lastSaleResponse == null
                || (System.currentTimeMillis() - lastSaleResponseTime)
                    > LAST_SALE_VALIDITY_MS) {
            showToast(isArabic ? "لا يوجد إيصال حديث"
                               : "No recent receipt");
            return;
        }
        showReceipt(lastSaleResponse);
    } catch (Exception e) {
        Log.e(TAG, "showLastReceiptDialog error", e);
    }
}

public void focusSearchField() {
    try {
        showManualInput();
    } catch (Exception e) {
        Log.e(TAG, "focusSearchField error", e);
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Launchers
// ═══════════════════════════════════════════════════════════════════

private final ActivityResultLauncher<ScanOptions> refundBarcodeLauncher =
        registerForActivityResult(new ScanContract(), result -> {
    try {
        if (result != null && result.getContents() != null
                && isFragmentAlive()) {
            if (refundDialog != null && refundDialog.isShowing()) {
                selectedRefundItem.set(null);
                searchItemForRefund(result.getContents());
            }
        }
    } catch (Exception e) {
        Log.e(TAG, "refundBarcodeLauncher error", e);
    }
});

// ═══════════════════════════════════════════════════════════════════
//  Buttons State
// ═══════════════════════════════════════════════════════════════════

private void setButtonsEnabled(boolean enabled) {
    safeRun(() -> {
        try {
            boolean canUse = hasPosPermission();
            if (btnCheckout != null) {
                boolean canCheckout = enabled && canUse
                        && cartManager != null
                        && !cartManager.isEmpty();
                btnCheckout.setEnabled(canCheckout);
                btnCheckout.setAlpha(canCheckout ? 1f : 0.5f);
            }
            if (btnClearCart != null) {
                btnClearCart.setEnabled(enabled && cartManager != null
                        && !cartManager.isEmpty());
            }
            if (btnAddManual != null) {
                btnAddManual.setEnabled(enabled && canUse);
            }
            if (btnPosScanGs1 != null) {
                btnPosScanGs1.setEnabled(enabled && canUse);
            }
            if (btnPosScanRfid != null) {
                btnPosScanRfid.setEnabled(enabled && canUse);
            }
            if (btnPosScanManual != null) {
                btnPosScanManual.setEnabled(enabled && canUse);
            }
            if (etManualEpc != null) {
                etManualEpc.setEnabled(enabled && canUse);
            }
        } catch (Exception e) {
            Log.e(TAG, "setButtonsEnabled error", e);
        }
    });
}

private void applyPermissions() {
    try {
        boolean canUse = hasPosPermission();

        if (btnAddManual != null) btnAddManual.setEnabled(canUse);
        if (btnPosScanGs1 != null) btnPosScanGs1.setEnabled(canUse);
        if (btnPosScanRfid != null) btnPosScanRfid.setEnabled(canUse);
        if (btnPosScanManual != null) btnPosScanManual.setEnabled(canUse);
        if (etManualEpc != null) etManualEpc.setEnabled(canUse);

        if (!canUse && tvCartStatus != null) {
            tvCartStatus.setText(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
        }
    } catch (Exception e) {
        Log.e(TAG, "applyPermissions error", e);
    }
}

    // ═══════════════════════════════════════════════════════════════════
    //  Scan Mode Switching — Unified
    // ═══════════════════════════════════════════════════════════════════

    /**
     * ✅ تبديل ذكي بين أوضاع المسح — علم واحد فقط.
     *    idempotent: إذا كان الوضع المطلوب = الحالي → لا يفعل شيئاً.
     */
    private void switchScanMode(ScanMode target) {
        if (!isFragmentAlive() || heroController == null) return;
        if (currentScanMode == target) return;

        Log.d(TAG, "🔄 Scan mode: " + currentScanMode + " → " + target);

        // ═══════════════════════════════════════════════════════════
        //  1) تنظيف الوضع الحالي
        // ═══════════════════════════════════════════════════════════
        switch (currentScanMode) {
            case RFID:
                stopRFIDScan();
                break;
            case MANUAL:
                hideManualInput();
                break;
            case GS1:
                // لا شيء — الماسح Fragment مؤقت
                break;
            case NONE:
            default:
                break;
        }

        // ═══════════════════════════════════════════════════════════
        //  2) تفعيل الوضع الجديد
        // ═══════════════════════════════════════════════════════════
        currentScanMode = target;

        switch (target) {
            case GS1:
                heroController.setState(PosHeroController.State.GS1_SCANNING);
                openBarcodeScanner();
                // ملاحظة: بعد الإغلاق، onBarcodeScannerResult سيعيد NONE
                break;

            case RFID:
                heroController.setState(PosHeroController.State.RFID_LISTENING);
                heroController.resetRfidCount();
                startRFIDScan();
                break;

            case MANUAL:
                heroController.setState(PosHeroController.State.MANUAL_ENTRY);
                showManualInput();
                break;

            case NONE:
            default:
                heroController.setState(PosHeroController.State.IDLE);
                break;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  RFID Scan — Start/Stop
    // ═══════════════════════════════════════════════════════════════════

    private void startRFIDScan() {
        try {
            if (rfidManager == null) return;

            oneShotConsumed.set(false);
            stopAfterFirstRead = true;

            sdkExecutor.execute(() -> {
                try {
                    rfidManager.startScan(new RFIDManager.ReadyCallback() {
                        @Override
                        public void onReady(MiraIRFIDReader reader) {
                            Log.d(TAG, "✅ RFID started (async)");
                        }

                        @Override
                        public void onError(String reason) {
                            Log.w(TAG, "RFID start failed: " + reason);
                            safeRun(() -> {
                                if (!isFragmentAlive()) return;
                                currentScanMode = ScanMode.NONE;
                                if (heroController != null) {
                                    heroController.setState(
                                            PosHeroController.State.IDLE);
                                }
                            });
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "startScan bg error", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "startRFIDScan error", e);
        }
    }

    private void stopRFIDScan() {
        try {
            if (rfidManager == null) return;
            if (sdkExecutor == null || sdkExecutor.isShutdown()) {
                // ✅ نفّذ مباشرة إذا كان المنفّذ مغلق
                try { rfidManager.stopScan(); }
                catch (Exception ignored) {}
                return;
            }
            sdkExecutor.execute(() -> {
                try {
                    rfidManager.stopScan();
                } catch (Exception e) {
                    Log.e(TAG, "stopScan bg error", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "stopRFIDScan error", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Tag Handlers — One-Shot + Continuous
    // ═══════════════════════════════════════════════════════════════════

    /**
 * ✅ معالجة وسم RFID — علم واحد للتحقق.
 * [Demo v6.3] يقبل EPC مشوّه (3008...) ويقرأ TID كبديل.
 */
public void onRingScanned(String epc) {
    try {
        if (epc == null || epc.trim().isEmpty()) return;

        // ✅ العلم الوحيد
        if (currentScanMode != ScanMode.RFID) return;

        // ═══════════════════════════════════════════════════════
        //  Debounce — تجاهل نفس الوسم خلال فترة قصيرة
        // ═══════════════════════════════════════════════════════
        final String cleanEpc = epc.trim().toUpperCase(Locale.US);

        long now = System.currentTimeMillis();
        Long lastTime = lastScannedTimes.get(cleanEpc);
        if (lastTime != null && (now - lastTime < SCAN_COOLDOWN_MS)) return;
        lastScannedTimes.put(cleanEpc, now);

        // ═══════════════════════════════════════════════════════
        //  One Shot — حماية ذرّية كاملة
        // ═══════════════════════════════════════════════════════
        if (stopAfterFirstRead) {
            // فحص ذرّي: أول خيط فقط سينجح
            if (!oneShotConsumed.compareAndSet(false, true)) {
                Log.i(TAG, "🎯 One-shot: ignoring extra tag " + cleanEpc);
                return;
            }

            stopAfterFirstRead = false;
            Log.i(TAG, "🎯 One-shot: accepted first tag " + cleanEpc);

            // ✅ أغلق الوضع — switchScanMode سيتولّى stopRFIDScan
            switchScanMode(ScanMode.NONE);
        }

        // ═══════════════════════════════════════════════════════
        //  ✅ [Demo] فحص صحة EPC
        //  EPC الصالح في هذا النظام: 24 hex يبدأ بـ E2
        //  EPC المشوّه (3008...): غير صالح → اقرأ TID
        // ═══════════════════════════════════════════════════════
        boolean isValidEpc = cleanEpc.matches("^E2[0-9A-F]{22}$");

        if (!isValidEpc) {
            Log.w(TAG, "⚠️ POS: Invalid EPC '" + cleanEpc
                    + "' → reading TID as fallback");
            readTidAndProcess(cleanEpc, "rfid");
            return;
        }

        // ═══════════════════════════════════════════════════════
        //  ✅ EPC صالح — مسار عادي
        // ═══════════════════════════════════════════════════════
        if (cartManager != null && cartManager.isIdScanned(cleanEpc)) {
            showToast(getString(R.string.pos_msg_already_in_cart));
            playAlertSound(2);
            return;
        }

        processRingEpc(cleanEpc, "rfid");

    } catch (Exception e) {
        Log.e(TAG, "onRingScanned error", e);
    }
}


/**
 * ✅ [Demo v6.3] قراءة TID عندما يكون EPC مشوّهاً.
 *
 *  السياق:
 *   - EPC على الشريحة = "3008..." (لم يُكتب بعد)
 *   - التطبيق (التكويد) خزّن TID في حقل rfid_epc
 *   - لذلك نقرأ TID من الشريحة ونسأل به
 *
 *  التسلسل:
 *   - switchScanMode(NONE) → sdkExecutor.execute(stopScan)
 *   - readTidAndProcess     → sdkExecutor.execute(readTid)
 *   - FIFO: stopScan ينفّذ قبل readTid (القارئ جاهز)
 */
private void readTidAndProcess(final String originalEpc, final String source) {
    if (sdkExecutor == null || sdkExecutor.isShutdown()) {
        Log.w(TAG, "readTidAndProcess: sdkExecutor unavailable → fallback to EPC");
        processRingEpc(originalEpc, source);
        return;
    }

    // ✅ مؤشر البحث للمستخدم
    safeRun(() -> {
        if (tvCartStatus != null) {
            tvCartStatus.setText(getString(R.string.pos_msg_searching));
        }
    });

    // ✅ استخدم sdkExecutor — FIFO مع stopScan
    sdkExecutor.execute(() -> {
        String tidRead = null;
        try {
            Context ctx = getSafeAppContext();
            if (ctx != null) {
                com.example.uhf.MiraRfid.MiraRfidService rfidService =
                        com.example.uhf.MiraRfid.MiraRfidService.getInstance(ctx);
                tidRead = rfidService.readTid(null);
            }
        } catch (Exception e) {
            Log.e(TAG, "readTidAndProcess: readTid error", e);
        }

        final String cleanTid = (tidRead != null && !tidRead.trim().isEmpty())
                ? tidRead.trim().toUpperCase(Locale.US)
                : null;

        safeRun(() -> {
            if (!isFragmentAlive()) return;

            // ═══════════════════════════════════════════════════
            //  فشل قراءة TID → fallback إلى EPC الأصلي
            // ═══════════════════════════════════════════════════
            if (cleanTid == null) {
                Log.w(TAG, "⚠️ POS: TID read failed → fallback to EPC");
                processRingEpc(originalEpc, source);
                return;
            }

            Log.i(TAG, "✅ POS: Using TID identifier: " + cleanTid);

            // ═══════════════════════════════════════════════════
            //  فحص السلة بالـ TID
            // ═══════════════════════════════════════════════════
            if (cartManager != null && cartManager.isIdScanned(cleanTid)) {
                showToast(getString(R.string.pos_msg_already_in_cart));
                playAlertSound(2);
                return;
            }

            // ═══════════════════════════════════════════════════
            //  المعالجة بالـ TID
            // ═══════════════════════════════════════════════════
            processRingEpc(cleanTid, source);
        });
    });
}
    /**
     * ✅ معالجة باركود — مسار مستقل عن RFID.
     */
    public void onBarcodeScanned(String data) {
        try {
            if (data == null || data.trim().isEmpty()) return;

            long now = System.currentTimeMillis();
            Long lastTime = lastScannedTimes.get(data);
            if (lastTime != null && (now - lastTime < SCAN_COOLDOWN_MS)) return;
            lastScannedTimes.put(data, now);

            if (cartManager != null && cartManager.isIdScanned(data)) {
                showToast(getString(R.string.pos_msg_already_in_cart));
                playAlertSound(2);
                return;
            }

            processRingEpc(data, "barcode");
        } catch (Exception e) {
            Log.e(TAG, "onBarcodeScanned error", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Barcode Scanner Fragment
    // ═══════════════════════════════════════════════════════════════════

    private void openBarcodeScanner() {
        try {
            if (!isFragmentAlive()) return;

            FragmentManager fm = getParentFragmentManager();
            if (fm == null || fm.isStateSaved()) {
                // ✅ إذا كان state saved — أعِد الحالة
                currentScanMode = ScanMode.NONE;
                if (heroController != null) {
                    heroController.setState(PosHeroController.State.IDLE);
                }
                return;
            }

            MiraBarcodeScannerFragment scannerFragment =
                    new MiraBarcodeScannerFragment();

            scannerFragment.setOnBarcodeScannedListener((data, format) -> {
                try {
                    // ✅ معالجة الباركود
                    if (isFragmentAlive() && data != null && !data.isEmpty()) {
                        onBarcodeScanned(data);
                    }

                    // ✅ إعادة الوضع إلى NONE
                    currentScanMode = ScanMode.NONE;
                    if (heroController != null) {
                        heroController.setState(PosHeroController.State.IDLE);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Barcode callback error", e);
                }
            });

            // احسب containerId
            int containerId = android.R.id.content;
            try {
                View view = getView();
                if (view != null && view.getParent() instanceof View) {
                    int parentId = ((View) view.getParent()).getId();
                    if (parentId != View.NO_ID) containerId = parentId;
                }
            } catch (Exception ignored) {}

            fm.beginTransaction()
                    .replace(containerId, scannerFragment)
                    .addToBackStack("barcode_scanner")
                    .commit();

        } catch (Exception e) {
            Log.e(TAG, "openBarcodeScanner error", e);
            showToast(isArabic
                    ? "⚠️ تعذر فتح الماسح" : "⚠️ Unable to open scanner");
            if (heroController != null) {
                heroController.setState(PosHeroController.State.IDLE);
            }
            currentScanMode = ScanMode.NONE;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Back Press Handler
    // ═══════════════════════════════════════════════════════════════════

    private void setupBackPressedHandler() {
        if (getActivity() == null) return;

        backPressedCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleCustomBack();
            }
        };

        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), backPressedCallback);

        Log.d(TAG, "✅ Back press handler registered");
    }

    /**
     * ✅ منطق زر الرجوع — متدرّج:
     *   1. Dialog الدفع مفتوح → تجاهل
     *   2. RFID نشط → أوقفه
     *   3. Manual نشط → أغلقه
     *   4. السلة غير فارغة → تأكيد
     *   5. خلاف ذلك → انتقل للـ Dashboard
     */
    private void handleCustomBack() {
        try {
            // 1. حوار الدفع
            if (isCheckoutDialogShowing) return;

            // 2. RFID نشط → أوقفه فقط
            if (currentScanMode == ScanMode.RFID) {
                switchScanMode(ScanMode.NONE);
                return;
            }

            // 3. Manual نشط → أغلقه
            if (currentScanMode == ScanMode.MANUAL) {
                switchScanMode(ScanMode.NONE);
                return;
            }

            // 4. GS1 نشط → أغلقه
            if (currentScanMode == ScanMode.GS1) {
                switchScanMode(ScanMode.NONE);
                return;
            }

            // 5. السلة فيها عناصر → تأكيد
            if (cartManager != null && !cartManager.isEmpty()) {
                showExitConfirmation();
                return;
            }

            // 6. اخرج
            navigateAwayFromPos();

        } catch (Exception e) {
            Log.e(TAG, "handleCustomBack error", e);
        }
    }

    private void showExitConfirmation() {
        if (!isFragmentAlive()) return;

        new AlertDialog.Builder(requireContext())
                .setTitle(isArabic ? "🛒 مغادرة شاشة البيع؟"
                                   : "🛒 Leave POS?")
                .setMessage(isArabic
                        ? "لديك " + cartManager.size() + " عنصر في السلة.\n\n"
                        + "هل تريد المغادرة؟ (سيتم الاحتفاظ بالسلة)"
                        : "You have " + cartManager.size()
                        + " items in cart.\n\n"
                        + "Leave anyway? (Cart will be preserved)")
                .setPositiveButton(isArabic ? "مغادرة" : "Leave",
                        (d, w) -> navigateAwayFromPos())
                .setNegativeButton(isArabic ? "البقاء" : "Stay", null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Navigation — Back to Dashboard
    // ═══════════════════════════════════════════════════════════════════

    /**
     * ✅ الخروج من شاشة POS — استبدال مباشر بـ FragmentManager.
     *
     * سبب عدم استخدام NavigationManager:
     *   - NavigationManager قد يرفض التنقل في ظروف خاصة.
     *   - الاستبدال المباشر مضمون — نتحكم في كل الخطوات.
     */
    private void navigateAwayFromPos() {
    try {
        try { stopRFIDScan(); } catch (Exception ignored) {}
        try { UIKeyboardHide(); } catch (Exception ignored) {}

        currentScanMode = ScanMode.NONE;
        stopAfterFirstRead = false;
        oneShotConsumed.set(false);

        if (!isAdded() || isRemoving() || isDetached()) return;
        if (getActivity() == null || getActivity().isFinishing()
                || getActivity().isDestroyed()) return;

        UHFMainActivity activity = (UHFMainActivity) getActivity();

        activity.setBottomNavVisible(true);
        activity.setNavigationLocked(false);
        activity.clearActiveListeners();

        // ✅ [v6.1] فوّض التنقل لـ NavigationManager
        NavigationManager nav = activity.getNavigationManager();

        if (nav != null) {
            nav.goToDashboard();
            activity.syncCurrentFragment();   // ← نُضيفها public في UHFMainActivity
            Log.i(TAG, "✅ Navigated to Dashboard via NavigationManager");
        } else {
            // fallback (نادر)
            Log.w(TAG, "⚠️ NavigationManager null — direct replace");
            forceRestartActivity(activity);
        }
    } catch (Exception e) {
        Log.e(TAG, "navigateAwayFromPos error", e);
        try {
            if (getActivity() instanceof UHFMainActivity) {
                forceRestartActivity((UHFMainActivity) getActivity());
            }
        } catch (Exception ignored) {}
    }
}

    private void updateBottomNavToHome(UHFMainActivity activity) {
        try {
            View navView = activity.findViewById(R.id.bottomNavigationView);
            if (navView instanceof BottomNavigationView) {
                BottomNavigationView nav = (BottomNavigationView) navView;
                if (nav.getMenu() != null) {
                    android.view.MenuItem homeItem =
                            nav.getMenu().findItem(R.id.nav_home);
                    if (homeItem != null) homeItem.setChecked(true);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "updateBottomNavToHome error", e);
        }
    }

    private void forceRestartActivity(UHFMainActivity activity) {
        try {
            Log.w(TAG, "🛡️ Force restart activity");
            Intent intent = new Intent(activity, UHFMainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent);
            activity.finish();
        } catch (Exception e) {
            Log.e(TAG, "forceRestartActivity error", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Keyboard Helper
    // ═══════════════════════════════════════════════════════════════════

    private void showKeyboard(View view) {
        try {
            if (view == null || getActivity() == null) return;
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                    getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        } catch (Exception e) {
            Log.w(TAG, "showKeyboard error", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Physical RFID Trigger
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public boolean myOnKeyDwon(int keyCode, KeyEvent event) {
        boolean isTrigger = (keyCode == KEYCODE_RFID_TRIGGER_1
                || keyCode == KEYCODE_RFID_TRIGGER_2
                || keyCode == KEYCODE_RFID_TRIGGER_3);

        if (!isTrigger) return false;

        // ═══════════════════════════════════════════════════════════
        //  ACTION_DOWN — ابدأ المسح
        // ═══════════════════════════════════════════════════════════
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {

            if (!hasPosPermission()) {
                showToast(getString(R.string.pos_msg_no_permission));
                return true;
            }

            try {
                if (!com.example.uhf.tools.DeviceDetector
                        .isRfidHardwareSupported()) {
                    return true;
                }
            } catch (Exception ignored) {}

            if (currentScanMode != ScanMode.RFID) {
                Log.i(TAG, "🎯 Trigger DOWN → starting RFID One-Shot");
                switchScanMode(ScanMode.RFID);
            }
            return true;
        }

        // ═══════════════════════════════════════════════════════════
        //  ACTION_UP — أوقف المسح
        // ═══════════════════════════════════════════════════════════
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (currentScanMode == ScanMode.RFID) {
                Log.i(TAG, "🎯 Trigger UP → stopping RFID");
                switchScanMode(ScanMode.NONE);
            }
            return true;
        }

        return false;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Backward Compatibility
    // ═══════════════════════════════════════════════════════════════════

    @Deprecated
    public void showCartBottomSheet() {
        Log.d(TAG, "showCartBottomSheet: no-op (cart is always visible)");
    }

    @Deprecated
    public void showDiscountDialog() {
        Log.d(TAG, "showDiscountDialog: redirecting to checkout dialog");
        processCheckoutAndStockUpdate();
    }

} // ✅ نهاية الملف
