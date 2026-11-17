package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;

import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.adapter.CartAdapter;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.dialog.POSCheckoutDialog;
import com.example.uhf.dialog.PosPowerSettingsDialog;
import com.example.uhf.dialog.RefundDialog;
import com.example.uhf.manager.GateSaleBridge;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraItemSyncManager;
import com.example.uhf.manager.MiraLocalItemCache;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.OfflineGateCache;
import com.example.uhf.manager.OfflineSalesSyncer;
import com.example.uhf.manager.PendingSale;
import com.example.uhf.manager.PendingSalesQueue;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.manager.PosCartManager;
import com.example.uhf.manager.PosPriceResolver;
import com.example.uhf.manager.PosUIManager;
import com.example.uhf.manager.SmartScaleManager;
import com.example.uhf.model.CartItem;
import com.example.uhf.navigation.NavigationManager;
import com.example.uhf.tools.DeviceDetector;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.tools.UIHelper;
import com.example.uhf.utils.MiraExecutorService;
import com.example.uhf.utils.ScaleSimulationHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import androidx.compose.ui.platform.ComposeView;
import com.example.uhf.ui.theme.MiraJewelryTheme;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  MiraPosFragment v7.0 — Jetpack Compose & Micro-Tag POS Enterprise Edition
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class MiraPosFragment extends MiraBaseFragment {

    private static final String TAG = "MiraPosFragment";

    private static final int  KEYCODE_RFID_TRIGGER_1 = 139;
    private static final int  KEYCODE_RFID_TRIGGER_2 = 280;
    private static final int  KEYCODE_RFID_TRIGGER_3 = 293;

    private static final long PENDING_EPC_TIMEOUT_MS  = 10_000;
    private static final long SCAN_COOLDOWN_MS        = 1500;
    private static final long LAST_SALE_VALIDITY_MS   = 5 * 60 * 1000;
    private static final long SHORT_CODE_DELAY_MS     = 800L;

    private static final int  AUTO_ADD_MIN_LEN        = 6;
    private static final int  PARTIAL_SERIAL_MIN_LEN  = 3;
    private static final int  PARTIAL_SERIAL_MAX_LEN  = 8;

    private static final long TRIGGER_GRACE_MS        = 2000;

    private static final String[] SERIAL_PREFIXES = {
            "MIRA-SN-", "MIRA-SN", "MIRASN-", "MIRASN",
            "MIRA-", "MIRA", "SN-", "SN", "JG-", "GOLD-", "GLD-"
    };

    private enum ScanMode { NONE, RFID, MANUAL, GS1 }
    private volatile ScanMode currentScanMode = ScanMode.NONE;

    private volatile boolean stopAfterFirstRead = false;
    private final AtomicBoolean oneShotConsumed = new AtomicBoolean(false);

    private final ExecutorService sdkExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "POS-SDK");
                t.setDaemon(true);
                return t;
            });

    private SessionManager sessionManager;
    private SmartScaleManager scaleManager;
    private ScaleSimulationHelper simulationHelper;
    private RefundDialog refundDialog;
    private boolean isRefundDialogHiddenForScan = false;
    private MiraExecutorService miraExecutor;
    private MiraApiClient apiClient;
    private MiraPriceManager priceManager;
    private MiraBranchesManager branchesManager;
    private PermissionManager permissionManager;

    private PosCartManager cartManager;
    private PosPriceResolver priceResolver;

    private RFIDManager rfidManager;

    @Nullable private PosCartManager.OnCartChangedListener cartListener;
    @Nullable private MiraIRFIDReader.OnTagReadListener rfidListener;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private Runnable cooldownCleanupRunnable;

    private volatile boolean isCheckoutDialogShowing = false;
    private volatile double  liveScaleWeight = 0.0;
    private volatile int     savedPowerBeforePos = -1;
    private volatile String  currentSaleUuid = null;

    @Nullable private POSCheckoutDialog activeCheckoutDialog = null;

    private PendingSalesQueue pendingSalesQueue;
    private OfflineSalesSyncer offlineSyncer;

    private final Set<String> pendingEpcs = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastScannedTimes = new ConcurrentHashMap<>();

    private volatile MiraApiClient.ApiResponse lastSaleResponse = null;
    private volatile long lastSaleResponseTime = 0;

    private final AtomicReference<RefundItem> selectedRefundItem = new AtomicReference<>(null);

    private OnBackPressedCallback backPressedCallback;
    private ComposeView composeView;

    // Compose Reactive States
    private final androidx.compose.runtime.MutableState<String> scaleStatusState =
            androidx.compose.runtime.SnapshotStateKt.mutableStateOf("⚖️ 0.00g");
    private final androidx.compose.runtime.MutableState<Integer> itemCountState =
            androidx.compose.runtime.SnapshotStateKt.mutableStateOf(0);
    private final androidx.compose.runtime.MutableState<String> totalAmountState =
            androidx.compose.runtime.SnapshotStateKt.mutableStateOf("$0.00");
    private final androidx.compose.runtime.MutableState<String> cartStatusState =
            androidx.compose.runtime.SnapshotStateKt.mutableStateOf("");
    private final androidx.compose.runtime.MutableState<Integer> pendingSalesCountState =
            androidx.compose.runtime.SnapshotStateKt.mutableStateOf(0);
    private final androidx.compose.runtime.MutableState<Boolean> canCheckoutState =
            androidx.compose.runtime.SnapshotStateKt.mutableStateOf(false);
    private final androidx.compose.runtime.MutableState<Boolean> canRefundState =
            androidx.compose.runtime.SnapshotStateKt.mutableStateOf(false);
    private final androidx.compose.runtime.MutableState<List<CartItem>> cartItemsState =
            androidx.compose.runtime.SnapshotStateKt.mutableStateOf(new ArrayList<>());
    private final androidx.compose.runtime.MutableState<Boolean> isRfidListeningState =
            androidx.compose.runtime.SnapshotStateKt.mutableStateOf(false);
    private final androidx.compose.runtime.MutableState<Integer> rfidCountState =
            androidx.compose.runtime.SnapshotStateKt.mutableStateOf(0);

    @Nullable private volatile String pendingBarcodeFromScanner = null;
    @Nullable private Runnable pendingStopRfid = null;

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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        try {
            composeView = new ComposeView(requireContext());
            composeView.setContent(() -> {
                MiraJewelryTheme.INSTANCE.content(
                        () -> MiraPosScreenKt.MiraPosScreen(
                                isArabic,
                                scaleStatusState.getValue(),
                                itemCountState.getValue(),
                                totalAmountState.getValue(),
                                cartStatusState.getValue(),
                                pendingSalesCountState.getValue(),
                                canCheckoutState.getValue(),
                                canRefundState.getValue(),
                                cartItemsState.getValue(),
                                isRfidListeningState.getValue(),
                                rfidCountState.getValue(),
                                () -> { if (scaleManager != null) scaleManager.showSettingsDialog(); },
                                this::openPendingSalesSheet,
                                this::showRefundDialog,
                                this::showLastReceiptDialog,
                                this::handleCustomBack,
                                this::toggleRfidListening,
                                this::openPosPowerSettings,
                                this::openBarcodeScanner,
                                this::submitManualEpcCode,
                                this::removeCartItemAt,
                                this::clearCartItems,
                                this::processCheckoutAndStockUpdate
                        ),
                        androidx.compose.runtime.ComposableSingletons$MiraPosFragmentKt.INSTANCE.getLambda-1()
                );
            });
            return composeView;
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

            branchesManager     = MiraBranchesManager.getInstance(ctx);
            priceManager        = MiraPriceManager.getInstance(ctx);
            permissionManager   = PermissionManager.getInstance(ctx);
            sessionManager      = new SessionManager(ctx);

            miraExecutor = MiraExecutorService.getInstance();
            if (miraExecutor == null || !miraExecutor.isHealthy()) {
                Log.e(TAG, "MiraExecutorService unhealthy");
                showToast(getString(R.string.pos_msg_engine_error));
                return;
            }
            priceResolver     = new PosPriceResolver(priceManager);
            cartManager       = PosCartManager.getInstance();
            apiClient         = MiraApiClient.getInstance(ctx);
            pendingSalesQueue = PendingSalesQueue.getInstance(ctx);
            offlineSyncer     = OfflineSalesSyncer.getInstance(ctx);

            rfidManager = RFIDManager.getInstance(ctx);
            if (rfidManager != null) {
                rfidListener = new MiraIRFIDReader.OnTagReadListener() {
                    @Override
                    public void onTagRead(String epc, int rssi) {
                        if (currentScanMode == ScanMode.RFID) {
                            safeRun(() -> onRingScanned(epc));
                        }
                    }
                    @Override public void onScanStarted() {}
                    @Override public void onScanStopped() {}
                    @Override
                    public void onConnectionChanged(boolean connected) {
                        safeRun(() -> {
                            if (currentScanMode == ScanMode.RFID) {
                                isRfidListeningState.setValue(connected);
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

            setupCartListener();
            applyPermissions();
            initSmartScale();
            requestScalePermissions();

            renderCart(cartManager.snapshot(), null, -1);
            setupBackPressedHandler();
            checkAutoOpenRefundIntent();

        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error", e);
        }
    }

    private void requestScalePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(requireContext(),
                    android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(requireContext(),
                    android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(requireActivity(),
                        new String[]{
                                android.Manifest.permission.BLUETOOTH_SCAN,
                                android.Manifest.permission.BLUETOOTH_CONNECT
                        }, 1002);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(requireContext(),
                    android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(requireActivity(),
                        new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 1002);
            }
        }
    }

    private void updatePendingSalesBadge() {
        try {
            if (pendingSalesQueue == null) return;
            int pending = pendingSalesQueue.countPending();
            pendingSalesCountState.setValue(pending);
        } catch (Exception e) {
            Log.e(TAG, "updatePendingSalesBadge error", e);
        }
    }

    private void openPendingSalesSheet() {
        try {
            if (!isFragmentAlive()) return;
            com.example.uhf.dialog.PendingSalesBottomSheet sheet =
                    new com.example.uhf.dialog.PendingSalesBottomSheet();
            sheet.show(getParentFragmentManager(), "PendingSalesSheet");
        } catch (Exception e) {
            Log.e(TAG, "openPendingSalesSheet error", e);
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

            isCheckoutDialogShowing = false;
            currentScanMode = ScanMode.NONE;
            stopAfterFirstRead = false;
            oneShotConsumed.set(false);
            cancelPendingStopIfAny();

            if (sdkExecutor.isShutdown()) {
                Log.w(TAG, "onResume: sdkExecutor shutdown — cannot recover");
            }

            if (scaleManager != null) scaleManager.connect();
            applyLowPowerForPos();

            if (cartManager != null) {
                renderCart(cartManager.snapshot(), null, -1);
            }

            processPendingBarcodeIfAny();
            updatePendingSalesBadge();
            triggerOfflineSyncIfNeeded();
            startCooldownCleanup();

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
            cancelPendingStopIfAny();
            stopCooldownCleanup();

            if (currentScanMode != ScanMode.NONE) {
                switchScanMode(ScanMode.NONE);
            }

            isCheckoutDialogShowing = false;

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
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);

        if (!hidden) {
            processPendingBarcodeIfAny();

            if (isRefundDialogHiddenForScan) {
                isRefundDialogHiddenForScan = false;
                if (refundDialog != null && !refundDialog.isShowing()) {
                    refundDialog.show();
                }
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        try {
            stopRFIDScan();

            if (backPressedCallback != null) {
                backPressedCallback.setEnabled(false);
                backPressedCallback.remove();
                backPressedCallback = null;
            }

            if (rfidManager != null && rfidListener != null) {
                rfidManager.removeListener(rfidListener);
                rfidListener = null;
            }

            if (cartManager != null && cartListener != null) {
                cartManager.removeListener(cartListener);
                cartListener = null;
            }

            restoreOriginalPower();

            if (scaleManager != null) scaleManager.cleanup();
            if (simulationHelper != null) simulationHelper.cleanup();

            pendingEpcs.clear();
            lastScannedTimes.clear();
            selectedRefundItem.set(null);
            currentScanMode = ScanMode.NONE;
            stopAfterFirstRead = false;
            oneShotConsumed.set(false);
            currentSaleUuid = null;

            activeCheckoutDialog = null;
            composeView = null;

            if (mainHandler != null) {
                mainHandler.removeCallbacksAndMessages(null);
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

            if (scaleManager != null) {
                scaleManager.cleanup();
                scaleManager = null;
            }

            if (sdkExecutor != null && !sdkExecutor.isShutdown()) {
                try { sdkExecutor.shutdownNow(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "onDestroy error", e);
        }
    }

    private void startCooldownCleanup() {
        stopCooldownCleanup();
        cooldownCleanupRunnable = new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                lastScannedTimes.entrySet().removeIf(
                        e -> (now - e.getValue()) > 60_000L);
                mainHandler.postDelayed(this, 60_000L);
            }
        };
        mainHandler.postDelayed(cooldownCleanupRunnable, 60_000L);
    }

    private void stopCooldownCleanup() {
        if (cooldownCleanupRunnable != null) {
            mainHandler.removeCallbacks(cooldownCleanupRunnable);
            cooldownCleanupRunnable = null;
        }
    }

    private void applyLowPowerForPos() {
        if (sdkExecutor == null || sdkExecutor.isShutdown()) return;

        sdkExecutor.execute(() -> {
            try {
                Context appCtx = getSafeAppContext();
                if (appCtx == null) return;

                com.example.uhf.MiraRfid.MiraReaderSettings settings =
                        com.example.uhf.MiraRfid.MiraReaderSettings.getInstance(appCtx);

                savedPowerBeforePos = settings.getPowerDbm();
                int posPower = settings.getPosPowerDbm();

                if (savedPowerBeforePos == posPower) return;
                settings.setPowerDbm(posPower);

                com.rscja.deviceapi.RFIDWithUHFUART reader =
                        com.example.uhf.activity.BaseTabFragmentActivity.getSharedReader();
                if (reader != null) {
                    try { reader.setPower(posPower); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.e(TAG, "applyLowPowerForPos error", e);
            }
        });
    }

    private void applyPosPowerImmediate(int newPowerDbm) {
        if (sdkExecutor == null || sdkExecutor.isShutdown()) return;

        sdkExecutor.execute(() -> {
            try {
                com.rscja.deviceapi.RFIDWithUHFUART reader =
                        com.example.uhf.activity.BaseTabFragmentActivity.getSharedReader();
                if (reader != null) {
                    try { reader.setPower(newPowerDbm); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.e(TAG, "applyPosPowerImmediate error", e);
            }
        });
    }

    private void restoreOriginalPower() {
        if (savedPowerBeforePos <= 0) return;
        if (sdkExecutor == null || sdkExecutor.isShutdown()) return;

        final int target = savedPowerBeforePos;
        savedPowerBeforePos = -1;

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
                    try { reader.setPower(target); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.e(TAG, "restoreOriginalPower error", e);
            }
        });
    }

    private boolean isFragmentAlive() {
        return isAdded() && !isRemoving() && !isDetached()
                && getActivity() != null && !getActivity().isFinishing()
                && !getActivity().isDestroyed();
    }

    @Nullable
    private Context getSafeAppContext() {
        try {
            if (getContext() != null) return getContext().getApplicationContext();
        } catch (Exception ignored) {}
        return null;
    }

    private void setupCartListener() {
        if (cartManager == null) return;

        cartListener = (snapshot, type, position) -> {
            safeRun(() -> renderCart(snapshot, type, position));
        };

        cartManager.addListener(cartListener);
    }

    private void renderCart(@NonNull PosCartManager.CartSnapshot snapshot,
                            @Nullable PosCartManager.ChangeType type,
                            int position) {
        if (!isFragmentAlive()) return;

        try {
            final int count = snapshot.count;
            cartItemsState.setValue(new ArrayList<>(snapshot.items));
            itemCountState.setValue(count);
            totalAmountState.setValue(buildTotalText(count, snapshot.totalUsd));

            if (count == 0) {
                cartStatusState.setValue(getString(R.string.pos_cart_empty));
            } else if (snapshot.hasUnavailable) {
                cartStatusState.setValue(getString(R.string.pos_cart_unavailable));
            } else {
                cartStatusState.setValue(getString(R.string.pos_cart_ready));
            }

            boolean checkoutAllowed = count > 0 && !snapshot.hasUnavailable && hasPosPermission();
            canCheckoutState.setValue(checkoutAllowed);

        } catch (Exception e) {
            Log.e(TAG, "renderCart error", e);
        }
    }

    private String buildTotalText(int count, double totalUsd) {
        if (count <= 0) {
            String def = getString(R.string.pos_default_amount);
            return (def == null || def.trim().isEmpty()) ? "$0.00" : def;
        }
        if (priceManager != null) {
            try {
                String formatted = priceManager.formatPriceDefault(totalUsd);
                if (formatted != null && !formatted.trim().isEmpty() && !"—".equals(formatted.trim())) {
                    return formatted;
                }
            } catch (Exception ignored) {}
        }
        return String.format(Locale.US, "$%,.2f", totalUsd);
    }

    private void toggleRfidListening() {
        if (currentScanMode == ScanMode.RFID) {
            switchScanMode(ScanMode.NONE);
        } else {
            switchScanMode(ScanMode.RFID);
        }
    }

    private void openPosPowerSettings() {
        if (!isFragmentAlive()) return;
        try {
            Context ctx = getSafeAppContext();
            if (ctx == null) return;

            PosPowerSettingsDialog.show(
                    ctx,
                    isArabic,
                    newPowerDbm -> {
                        applyPosPowerImmediate(newPowerDbm);
                        showToast(isArabic ? "✅ تم تحديث قوة القراءة إلى " + newPowerDbm + " dBm" : "✅ Power updated to " + newPowerDbm + " dBm");
                    }
            );
        } catch (Exception e) {
            Log.e(TAG, "openPosPowerSettings error", e);
        }
    }

    private void submitManualEpcCode(String code) {
        if (code == null || code.trim().isEmpty()) return;
        if (!hasPosPermission()) {
            showToast(getString(R.string.pos_msg_no_permission));
            return;
        }
        if (isCheckoutDialogShowing) return;

        UIHelper.hideKeyboard(getActivity());
        String epc = code.trim();

        if (cartManager != null) {
            boolean dup = cartManager.isIdScanned(epc);
            if (!dup && looksLikePartialSerial(epc)) {
                for (String prefix : SERIAL_PREFIXES) {
                    if (cartManager.isIdScanned(prefix + epc)) { dup = true; break; }
                }
            }
            if (dup) {
                showToast(getString(R.string.pos_msg_already_in_cart));
                playAlertSound(2);
                return;
            }
        }

        processRingEpc(epc, "manual");
    }

    private void removeCartItemAt(int index) {
        if (cartManager != null) {
            cartManager.removeItemAt(index);
        }
    }

    private void clearCartItems() {
        if (cartManager != null) {
            cartManager.clear();
            currentSaleUuid = null;
        }
    }

    private void processRingEpc(final String code, final String source) {
        if (!hasPosPermission()) {
            showToast(getString(R.string.pos_msg_no_permission));
            playAlertSound(2);
            return;
        }
        if (isCheckoutDialogShowing || code == null || code.trim().isEmpty() || !isFragmentAlive()) return;

        final String cleanCode = code.trim();
        final List<String> candidates = buildSearchCandidates(cleanCode);
        playAlertSound(1);

        cartStatusState.setValue(getString(R.string.pos_msg_searching));
        processRingEpcWithCandidates(cleanCode, source, candidates, 0);
    }

    private void processRingEpcWithCandidates(
            @NonNull final String originalCode,
            @NonNull final String source,
            @NonNull final List<String> candidates,
            final int index) {

        if (!isFragmentAlive()) return;

        if (index >= candidates.size()) {
            safeRun(() -> {
                if (!isFragmentAlive()) return;
                showToast(getString(R.string.pos_msg_not_found));
                playAlertSound(2);
            });
            return;
        }

        final String candidate = candidates.get(index);
        if (!pendingEpcs.add(candidate)) return;

        mainHandler.postDelayed(() -> pendingEpcs.remove(candidate), PENDING_EPC_TIMEOUT_MS);

        try {
            miraExecutor.getNetworkExecutor().execute(() -> {
                try {
                    Context context = getSafeAppContext();
                    if (context == null) return;

                    MiraItemSyncManager syncManager = MiraItemSyncManager.getInstance(context);
                    final int branchId = getActiveBranchId();

                    syncManager.findItem(candidate, branchId,
                            new MiraItemSyncManager.ItemLookupCallback() {
                                @Override
                                public void onItemFound(JSONObject item, boolean fromServer) {
                                    safeRun(() -> {
                                        if (!isFragmentAlive()) return;
                                        int itemBranchId = item.optInt("branch_id", -1);
                                        if (itemBranchId > 0 && itemBranchId != branchId) {
                                            showToast(getString(R.string.pos_msg_other_branch));
                                            playAlertSound(2);
                                            return;
                                        }
                                        parseItemResponse(candidate, item, source);
                                    });
                                }

                                @Override
                                public void onItemNotFound() {
                                    safeRun(() -> processRingEpcWithCandidates(
                                            originalCode, source, candidates, index + 1));
                                }
                            });
                } catch (Exception e) {
                    pendingEpcs.remove(candidate);
                    safeRun(() -> processRingEpcWithCandidates(
                            originalCode, source, candidates, index + 1));
                } finally {
                    pendingEpcs.remove(candidate);
                }
            });
        } catch (Exception e) {
            pendingEpcs.remove(candidate);
            safeRun(() -> processRingEpcWithCandidates(
                    originalCode, source, candidates, index + 1));
        }
    }

    private void parseItemResponse(String code, JSONObject data, String source) {
        try {
            if (!hasPosPermission()) return;

            JSONObject item = data.has("item") ? data.optJSONObject("item") : data;
            if (item == null) {
                showToast(getString(R.string.pos_msg_not_found));
                playAlertSound(2);
                return;
            }

            String vaultStatus = item.optString("status", item.optString("vault_status", "")).toLowerCase();
            if ("sold".equals(vaultStatus) || "exited".equals(vaultStatus)) {
                showToast(getString(R.string.pos_msg_already_sold));
                playAlertSound(2);
                return;
            }

            boolean isAvailable = !vaultStatus.equals("sold") && !vaultStatus.equals("reserved");
            String title = item.optString("title", item.optString("item_title", isArabic ? "قطعة" : "Item"));
            String karat = item.optString("karat", item.optString("gold_karat", "21K"));
            double weight = item.optDouble("weight", item.optDouble("gross_weight", 0));

            PosPriceResolver.PriceResult priceResult = priceResolver.resolve(item, getKaratNumber(karat), weight);
            double price = priceResult.priceUsd;

            CartItem cartItem = new CartItem(code, title, karat, weight, liveScaleWeight, price, isAvailable, vaultStatus.toUpperCase());
            cartItem.serialNo = extractString(item, "serial", "serial_number", "sn");
            cartItem.gtin = extractString(item, "gtin", "gtin13", "barcode", "code");
            cartItem.itemId = item.optInt("id", item.optInt("item_id", 0));

            String rfidEpcFromServer = extractString(item, "rfid_epc", "epc_final", "epc");
            cartItem.rfidEpc = !rfidEpcFromServer.isEmpty() ? rfidEpcFromServer : (cartItem.serialNo.isEmpty() ? code : cartItem.serialNo);
            cartItem.epc = cartItem.rfidEpc;

            if (cartManager != null && cartManager.containsItem(cartItem.epc, cartItem.serialNo, cartItem.gtin)) {
                showToast(getString(R.string.pos_msg_already_in_cart));
                playAlertSound(2);
                return;
            }

            if (!isAvailable) {
                showToast(getString(R.string.pos_msg_unavailable));
                return;
            }

            int pos = cartManager.addItem(cartItem);
            if (pos < 0) {
                showToast(getString(R.string.pos_msg_already_in_cart));
                return;
            }

            playAlertSound(1);
            showToast(getString(R.string.pos_msg_added, title));
            if ("rfid".equals(source)) {
                rfidCountState.setValue(rfidCountState.getValue() + 1);
            }
        } catch (Exception e) {
            Log.e(TAG, "parseItemResponse error", e);
        }
    }

    private void initSmartScale() {
        try {
            scaleManager = new SmartScaleManager(requireContext(), new SmartScaleManager.ScaleCallback() {
                @Override
                public void onWeightReceived(double weight) {
                    liveScaleWeight = weight;
                    safeRun(() -> {
                        scaleStatusState.setValue(String.format(Locale.US, "⚖️ %.2fg", weight));
                        updateTopCartItemScaleWeight(weight);
                    });
                }

                @Override
                public void onScaleConnected(String name) {
                    safeRun(() -> scaleStatusState.setValue("⚖️ " + name));
                }

                @Override
                public void onScaleDisconnected() {
                    liveScaleWeight = 0.0;
                    safeRun(() -> scaleStatusState.setValue("⚖️ Off"));
                }

                @Override
                public void onScaleError(String error) {
                    Log.e(TAG, "Scale Error: " + error);
                }
            });

            simulationHelper = new ScaleSimulationHelper(requireContext(), (weight, label) -> safeRun(() -> {
                liveScaleWeight = weight;
                scaleStatusState.setValue(String.format(Locale.US, "⚖️ %.2fg", weight));
                updateTopCartItemScaleWeight(weight);
            }));
        } catch (Exception e) {
            Log.e(TAG, "initSmartScale error", e);
        }
    }

    private void updateTopCartItemScaleWeight(double currentScaleWeight) {
        if (cartManager == null || cartManager.isEmpty() || currentScaleWeight <= 0) return;
        try {
            List<CartItem> items = cartManager.getItems();
            if (items.isEmpty()) return;
            CartItem topItem = items.get(0);
            if (topItem == null || topItem.scaleWeight == currentScaleWeight) return;
            topItem.scaleWeight = currentScaleWeight;
            cartManager.updateItemAt(0, topItem);
        } catch (Exception e) {
            Log.e(TAG, "updateTopCartItemScaleWeight error", e);
        }
    }

    private boolean looksLikePartialSerial(@Nullable String code) {
        if (code == null) return false;
        int len = code.length();
        if (len < PARTIAL_SERIAL_MIN_LEN || len > PARTIAL_SERIAL_MAX_LEN) return false;
        for (int i = 0; i < len; i++) {
            if (!Character.isDigit(code.charAt(i))) return false;
        }
        return true;
    }

    @NonNull
    private List<String> buildSearchCandidates(@NonNull String code) {
        List<String> candidates = new ArrayList<>();
        candidates.add(code);
        if (looksLikePartialSerial(code)) {
            for (String prefix : SERIAL_PREFIXES) {
                String candidate = prefix + code;
                if (!candidates.contains(candidate)) candidates.add(candidate);
            }
        }
        return candidates;
    }

    private void showToast(String msg) {
        PosUIManager.toast(getContext(), msg);
    }

    private void safeRun(Runnable r) {
        if (r == null) return;
        try {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (isFragmentAlive()) {
                        try { r.run(); } catch (Exception ignored) {}
                    }
                });
            }
        } catch (Exception ignored) {}
    }

    private void playAlertSound(int id) {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).playSound(id);
            }
        } catch (Exception ignored) {}
    }

    private int getActiveBranchId() {
        try {
            return branchesManager != null ? branchesManager.getActiveBranchId() : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    private boolean hasPosPermission() {
        return permissionManager != null && permissionManager.canUsePos();
    }

    private boolean canPerformCheckout() {
        return permissionManager != null && permissionManager.canUsePos()
                && (permissionManager.isPosOperator() || permissionManager.isManager() || permissionManager.isAdmin());
    }

    private boolean canPerformRefund() {
        return permissionManager != null && (permissionManager.isManager() || permissionManager.isAdmin() || permissionManager.isFinanceManager());
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

    private void processCheckoutAndStockUpdate() {
        try {
            if (!hasPosPermission() || !canPerformCheckout()) {
                showToast(getString(R.string.pos_msg_no_permission));
                return;
            }
            if (cartManager == null || cartManager.isEmpty()) {
                showToast(getString(R.string.pos_msg_cart_empty_toast));
                return;
            }
            if (isCheckoutDialogShowing || !isFragmentAlive()) return;

            UIHelper.hideKeyboard(getActivity());
            final List<Integer> itemIds = new ArrayList<>();
            for (CartItem item : cartManager.getItems()) {
                if (item != null && item.itemId > 0) itemIds.add(item.itemId);
            }

            if (itemIds.isEmpty()) {
                showToast(isArabic ? "لا يمكن تحديد معرّفات القطع" : "Cannot identify items");
                return;
            }

            refreshPricesBeforeCheckout(itemIds);
        } catch (Exception e) {
            Log.e(TAG, "processCheckoutAndStockUpdate error", e);
        }
    }

    private void refreshPricesBeforeCheckout(List<Integer> itemIds) {
        final AlertDialog loadingDialog = PosUIManager.showLoadingDialog(requireContext(), isArabic);

        miraExecutor.getNetworkExecutor().execute(() -> {
            try {
                Context ctx = getSafeAppContext();
                if (ctx == null) return;

                MiraApiClient client = MiraApiClient.getInstance(ctx);
                MiraApiClient.ApiResponse resp = client.prepareCheckout(itemIds);

                safeRun(() -> {
                    PosUIManager.dismissDialog(loadingDialog);

                    if (resp == null || !resp.isSuccess || resp.data == null) {
                        String err = (resp != null && resp.error != null) ? resp.error : (isArabic ? "فشل التحقق من الأسعار" : "Price check failed");
                        showToast("❌ " + err);
                        return;
                    }

                    try {
                        JSONArray freshItems = resp.data.optJSONArray("items");
                        double freshTotalUsd = resp.data.optDouble("total_usd", 0);

                        if (freshItems != null) {
                            for (int i = 0; i < freshItems.length(); i++) {
                                JSONObject it = freshItems.getJSONObject(i);
                                int id = it.optInt("id", 0);
                                double newPrice = it.optDouble("retail_price_usd", 0);
                                if (id > 0 && newPrice > 0) cartManager.updateItemPrice(id, newPrice);
                            }
                        }

                        JSONObject ratesObj = resp.data.optJSONObject("exchange_rates");
                        String ratesJson = (ratesObj != null) ? ratesObj.toString() : "{}";

                        openCheckoutDialogWithTotal(freshTotalUsd, ratesJson);
                    } catch (Exception e) {
                        Log.e(TAG, "refreshPrices parse error", e);
                    }
                });
            } catch (Exception e) {
                safeRun(() -> PosUIManager.dismissDialog(loadingDialog));
            }
        });
    }

    private void openCheckoutDialogWithTotal(double totalUsd, String ratesJson) {
        try {
            if (!isFragmentAlive()) return;
            isCheckoutDialogShowing = true;
            PosCartManager.CartSnapshot snap = cartManager.snapshot();

            POSCheckoutDialog dialog = POSCheckoutDialog.newInstance(totalUsd, snap.count, ratesJson);
            activeCheckoutDialog = dialog;

            dialog.setCheckoutListener(data -> {
                if (data != null) executeCheckoutDirectly(data);
            });

            dialog.setOnDismissListener(() -> {
                isCheckoutDialogShowing = false;
                activeCheckoutDialog = null;
            });

            FragmentManager fm = getParentFragmentManager();
            if (fm != null && !fm.isStateSaved()) {
                dialog.show(fm, "POSCheckoutDialog");
            } else {
                isCheckoutDialogShowing = false;
                activeCheckoutDialog = null;
            }
        } catch (Exception e) {
            isCheckoutDialogShowing = false;
            activeCheckoutDialog = null;
        }
    }

    private void executeCheckoutDirectly(final POSCheckoutDialog.POSCheckoutData data) {
        if (data == null || !isFragmentAlive()) return;
        AlertDialog loadingDialog = null;

        try {
            loadingDialog = PosUIManager.showLoadingDialog(requireContext(), isArabic);
            final AlertDialog finalLoadingDialog = loadingDialog;

            miraExecutor.getNetworkExecutor().execute(() -> {
                try {
                    Context ctx = getSafeAppContext();
                    if (ctx == null) return;

                    MiraApiClient client = MiraApiClient.getInstance(ctx);
                    JSONObject payload = buildCheckoutPayload(client, data);
                    MiraApiClient.ApiResponse response = client.checkout(payload);

                    safeRun(() -> {
                        PosUIManager.dismissDialog(finalLoadingDialog);

                        if (response != null && response.isSuccess) {
                            if (activeCheckoutDialog != null && activeCheckoutDialog.isAdded()) {
                                activeCheckoutDialog.onCheckoutSuccess();
                            }
                            handleSaleSuccess(response, data, false);
                        } else if (response != null && response.isNetworkError) {
                            if (activeCheckoutDialog != null && activeCheckoutDialog.isAdded()) {
                                activeCheckoutDialog.onCheckoutSuccess();
                            }
                            saveOfflineSale(payload, data);
                            handleSaleSuccess(response, data, true);
                        } else {
                            String errorMsg = (response != null && response.error != null) ? response.error : "Unknown error";
                            if (activeCheckoutDialog != null && activeCheckoutDialog.isAdded()) {
                                activeCheckoutDialog.onCheckoutError(errorMsg);
                            }
                        }
                    });
                } catch (Exception e) {
                    safeRun(() -> PosUIManager.dismissDialog(finalLoadingDialog));
                }
            });
        } catch (Exception e) {
            if (loadingDialog != null) PosUIManager.dismissDialog(loadingDialog);
        }
    }

    private void saveOfflineSale(JSONObject payload, POSCheckoutDialog.POSCheckoutData data) {
        try {
            if (payload == null || pendingSalesQueue == null) return;
            String localUuid = payload.optString("local_uuid", "");
            if (localUuid.isEmpty()) return;

            PendingSale sale = new PendingSale();
            sale.localUuid = localUuid;
            sale.payloadJson = payload.toString();
            sale.branchId = getActiveBranchId();
            sale.userId = sessionManager != null ? sessionManager.getUserId() : 0;
            sale.totalUsd = data != null ? data.finalAmount : 0;
            sale.itemCount = cartManager != null ? cartManager.size() : 0;
            sale.createdAt = System.currentTimeMillis();
            sale.status = PendingSale.STATUS_PENDING;

            if (pendingSalesQueue.enqueue(sale)) {
                updatePendingSalesBadge();
                showToast(isArabic ? "📥 سيُرسَل البيع تلقائياً عند عودة الاتصال" : "📥 Sale will be sent when online");
            }
        } catch (Exception ignored) {}
    }

    private void handleSaleSuccess(final MiraApiClient.ApiResponse response, final POSCheckoutDialog.POSCheckoutData data, final boolean isOffline) {
        if (!isFragmentAlive() || data == null) return;
        try {
            playAlertSound(1);
            lastSaleResponse = response;
            lastSaleResponseTime = System.currentTimeMillis();
            currentSaleUuid = null;

            final List<CartItem> soldItems = new ArrayList<>(cartManager.getItems());
            final long saleId = isOffline ? 0L : extractSaleIdFromResponse(response);

            Context appCtx = getSafeAppContext();
            if (appCtx != null) {
                GateSaleBridge.onSaleCompleted(appCtx, soldItems, saleId, data.finalAmount, getActiveBranchId(), sessionManager != null ? sessionManager.getUserId() : 0, isOffline);
            }

            cartManager.clear();
            if (!isOffline) {
                showReceipt(response);
                showToast(getString(R.string.pos_msg_sale_success));
            } else {
                showToast(getString(R.string.pos_msg_sale_offline));
            }
            rfidCountState.setValue(0);
        } catch (Exception e) {
            Log.e(TAG, "handleSaleSuccess error", e);
        }
    }

    private JSONObject buildCheckoutPayload(MiraApiClient client, POSCheckoutDialog.POSCheckoutData data) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("action", "COMPLETE_POS_SALE");
        payload.put("gate_id", client != null ? client.getGateId() : "");
        payload.put("branch_id", getActiveBranchId());
        payload.put("user_id", sessionManager != null ? sessionManager.getUserId() : 0);

        if (currentSaleUuid == null || currentSaleUuid.isEmpty()) currentSaleUuid = UUID.randomUUID().toString();
        payload.put("local_uuid", currentSaleUuid);

        String customerName = (data.customerName != null && !data.customerName.trim().isEmpty()) ? data.customerName.trim() : (isArabic ? "عميل نقدي" : "Cash Customer");
        payload.put("customer_name", customerName);

        JSONArray itemsArr = new JSONArray();
        for (CartItem item : cartManager.getItems()) {
            if (item != null) itemsArr.put(item.toJson());
        }
        payload.put("items", itemsArr);
        payload.put("item_count", cartManager.size());

        double finalUsd = data.finalAmount;
        payload.put("total_usd", finalUsd);
        payload.put("total_amount_usd", finalUsd);
        return payload;
    }

    private void showReceipt(MiraApiClient.ApiResponse response) {
        try {
            if (!isFragmentAlive()) return;
            String receiptJson = (response != null && response.data != null) ? response.data.toString() : "{}";
            MiraReceiptFragment receiptFragment = MiraReceiptFragment.newInstance(receiptJson);
            FragmentManager fm = getParentFragmentManager();
            if (fm != null && !fm.isStateSaved()) receiptFragment.show(fm, "MiraReceiptFragment");
        } catch (Exception ignored) {}
    }

    private long extractSaleIdFromResponse(MiraApiClient.ApiResponse response) {
        if (response == null || response.data == null) return 0L;
        return response.data.optLong("sale_id", response.data.optLong("id", 0L));
    }

    private void showRefundDialog() {
        if (!isFragmentAlive() || !canPerformRefund()) {
            showToast(isArabic ? "⛔ غير مصرح بالاسترجاع" : "⛔ Not authorized");
            return;
        }
        try {
            selectedRefundItem.set(null);
            refundDialog = new RefundDialog(requireContext(), new RefundDialog.RefundCallback() {
                @Override public void onRefundSuccess(JSONObject result) {}
                @Override public void onBarcodeScan() { openRefundBarcodeScanner(); }
                @Override public void onDialogClosed() {}
            });
            refundDialog.show();
        } catch (Exception ignored) {}
    }

    private void openRefundBarcodeScanner() {
        if (!isFragmentAlive()) return;
        MiraBarcodeScannerFragment scannerFragment = new MiraBarcodeScannerFragment();
        scannerFragment.setOnBarcodeScannedListener((rawData, format) -> {
            if (refundDialog != null) refundDialog.setQueryFromScan(rawData);
        });
        getParentFragmentManager().beginTransaction().hide(this).add(android.R.id.content, scannerFragment, "refund_scanner").addToBackStack("refund_scanner").commit();
    }

    public void showLastReceiptDialog() {
        if (lastSaleResponse != null) showReceipt(lastSaleResponse);
        else showToast(isArabic ? "لا يوجد إيصال حديث" : "No recent receipt");
    }

    private void applyPermissions() {
        canCheckoutState.setValue(canPerformCheckout());
        canRefundState.setValue(canPerformRefund());
    }

    @Override
    public boolean myOnKeyDwon(int keyCode, KeyEvent event) {
        boolean isTrigger = (keyCode == KEYCODE_RFID_TRIGGER_1 || keyCode == KEYCODE_RFID_TRIGGER_2 || keyCode == KEYCODE_RFID_TRIGGER_3);
        if (!isTrigger) return false;

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            cancelPendingStopIfAny();
            if (isCheckoutDialogShowing || !hasPosPermission()) return true;

            if (currentScanMode != ScanMode.RFID) {
                switchScanMode(ScanMode.RFID);
            } else {
                oneShotConsumed.set(false);
                stopAfterFirstRead = true;
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && currentScanMode == ScanMode.RFID) {
            scheduleDelayedStopIfNeeded();
            return true;
        }
        return false;
    }

    private void scheduleDelayedStopIfNeeded() {
        cancelPendingStopIfAny();
        pendingStopRfid = () -> {
            pendingStopRfid = null;
            if (currentScanMode == ScanMode.RFID && stopAfterFirstRead) {
                switchScanMode(ScanMode.NONE);
            }
        };
        mainHandler.postDelayed(pendingStopRfid, TRIGGER_GRACE_MS);
    }

    private void cancelPendingStopIfAny() {
        if (pendingStopRfid != null) {
            mainHandler.removeCallbacks(pendingStopRfid);
            pendingStopRfid = null;
        }
    }

    private void switchScanMode(ScanMode target) {
        if (!isFragmentAlive() || currentScanMode == target) return;
        if (currentScanMode == ScanMode.RFID) { cancelPendingStopIfAny(); stopRFIDScan(); }

        currentScanMode = target;
        if (target == ScanMode.RFID) {
            isRfidListeningState.setValue(true);
            startRFIDScan();
        } else {
            isRfidListeningState.setValue(false);
        }
    }

    private void startRFIDScan() {
        try {
            if (rfidManager == null || sdkExecutor.isShutdown()) return;
            oneShotConsumed.set(false);
            stopAfterFirstRead = true;
            sdkExecutor.execute(() -> rfidManager.startScan(null));
        } catch (Exception ignored) {}
    }

    private void stopRFIDScan() {
        try {
            if (rfidManager != null) rfidManager.stopScan();
        } catch (Exception ignored) {}
    }

    public void onRingScanned(String epc) {
        if (epc == null || epc.trim().isEmpty() || !hasPosPermission()) return;
        if (currentScanMode != ScanMode.RFID) return;

        final String cleanEpc = epc.trim().toUpperCase(Locale.US);
        long now = System.currentTimeMillis();
        Long lastTime = lastScannedTimes.get(cleanEpc);
        if (lastTime != null && (now - lastTime < SCAN_COOLDOWN_MS)) return;
        lastScannedTimes.put(cleanEpc, now);

        if (stopAfterFirstRead) {
            if (!oneShotConsumed.compareAndSet(false, true)) return;
            stopAfterFirstRead = false;
            cancelPendingStopIfAny();
            switchScanMode(ScanMode.NONE);
        }

        processRingEpc(cleanEpc, "rfid");
    }

    public void onBarcodeScanned(String rawData) {
        if (rawData == null || rawData.trim().isEmpty() || !hasPosPermission()) return;
        processRingEpc(rawData.trim(), "barcode");
    }

    private void openBarcodeScanner() {
        if (!isFragmentAlive() || !hasPosPermission()) return;
        MiraBarcodeScannerFragment scannerFragment = new MiraBarcodeScannerFragment();
        scannerFragment.setOnBarcodeScannedListener((data, format) -> {
            if (data != null && !data.isEmpty()) pendingBarcodeFromScanner = data;
        });
        getParentFragmentManager().beginTransaction().hide(this).add(android.R.id.content, scannerFragment, "barcode_scanner").addToBackStack("barcode_scanner").commit();
    }

    private void processPendingBarcodeIfAny() {
        final String code = pendingBarcodeFromScanner;
        if (code == null || code.isEmpty()) return;
        pendingBarcodeFromScanner = null;
        mainHandler.postDelayed(() -> onBarcodeScanned(code), 150);
    }

    private void triggerOfflineSyncIfNeeded() {
        try {
            if (offlineSyncer == null || pendingSalesQueue == null || offlineSyncer.isSyncing()) return;
            if (pendingSalesQueue.countPending() == 0) return;
            offlineSyncer.syncAll(new OfflineSalesSyncer.SyncCallback() {
                @Override public void onSyncStart(int total) {}
                @Override public void onSaleSynced(PendingSale sale, boolean success, @Nullable String error) {}
                @Override public void onSyncComplete(int synced, int failed, int remaining) {
                    updatePendingSalesBadge();
                }
            });
        } catch (Exception ignored) {}
    }

    private void setupBackPressedHandler() {
        if (getActivity() == null) return;
        backPressedCallback = new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { handleCustomBack(); }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backPressedCallback);
    }

    private void handleCustomBack() {
        try {
            if (isCheckoutDialogShowing) return;
            if (currentScanMode != ScanMode.NONE) {
                switchScanMode(ScanMode.NONE);
                return;
            }
            if (cartManager != null && !cartManager.isEmpty()) {
                showExitConfirmation();
                return;
            }
            navigateAwayFromPos();
        } catch (Exception ignored) {}
    }

    private void showExitConfirmation() {
        if (!isFragmentAlive()) return;
        new AlertDialog.Builder(requireContext())
                .setTitle(isArabic ? "مغادرة شاشة البيع؟" : "Leave POS?")
                .setMessage(isArabic ? "لديك عناصر في السلة. هل تريد المغادرة؟" : "You have items in cart. Leave?")
                .setPositiveButton(isArabic ? "مغادرة" : "Leave", (d, w) -> navigateAwayFromPos())
                .setNegativeButton(isArabic ? "البقاء" : "Stay", null)
                .show();
    }

    private void navigateAwayFromPos() {
        try {
            cancelPendingStopIfAny();
            stopRFIDScan();
            currentScanMode = ScanMode.NONE;
            if (!isFragmentAlive()) return;

            UHFMainActivity activity = (UHFMainActivity) getActivity();
            if (activity != null) {
                activity.setBottomNavVisible(true);
                activity.setNavigationLocked(false);
                NavigationManager nav = activity.getNavigationManager();
                if (nav != null) {
                    nav.goToDashboard();
                    activity.syncCurrentFragment();
                }
            }
        } catch (Exception ignored) {}
    }

    private void checkAutoOpenRefundIntent() {
        if (getArguments() != null && getArguments().getBoolean("auto_open_refund", false)) {
            getArguments().remove("auto_open_refund");
            mainHandler.postDelayed(this::showRefundDialog, 500);
        }
    }
}
