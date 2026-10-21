package com.example.uhf.fragment;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.MiraRfidService;
import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.adapter.PhotosAdapter;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.databinding.FragmentMiraIdentityStudioBinding;
import com.example.uhf.manager.IdentityFormManager;
import com.example.uhf.manager.IdentityPhotoManager;
import com.example.uhf.manager.IdentitySyncManager;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.scanner.DesktopScanHandler;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.utils.BarcodeGenerator;
import com.example.uhf.utils.MiraExecutorService;
import com.example.uhf.utils.PricingCalculator;
import com.example.uhf.utils.TagPrinter;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  MiraIdentityStudioFragment v6.5 — Trigger-Safe Edition
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * ✅ إصلاحات v6.5:
 *   - تتبع isTriggerDown الفيزيائي كمرجع وحيد
 *   - scanGeneration لمنع callbacks قديمة
 *   - حذف pendingStopFromTrigger / MIN_SCAN_DURATION_MS (مصدر الفوضى)
 *   - Reset شامل في onResume لتفادي الحالة العالقة
 *   - timeout تلقائي لـ stoppingReader (3 ثوانٍ)
 *   - حماية isProcessingTag من البقاء true بعد فشل سابق
 */
public class MiraIdentityStudioFragment extends MiraBaseFragment {

    private static final String TAG = "MiraIdentityStudio";

    // ═══════════════════════════════════════════════════════════════════
    //  ثوابت
    // ═══════════════════════════════════════════════════════════════════
    private static final long RFID_TIMEOUT_MS = 30000;
    private static final long STOPPING_READER_TIMEOUT_MS = 3000;
    private static final int  MIN_TID_LENGTH = 12;
    private static final int  MAX_TID_LENGTH = 32;
    private static final int  MAX_PHOTOS = 5;

    private static final boolean ENABLE_TID_LENGTH_VALIDATION = true;

    private DesktopScanHandler desktopScanHandler;

    private static final int KEYCODE_TRIGGER_1 = 139;
    private static final int KEYCODE_TRIGGER_2 = 280;
    private static final int KEYCODE_TRIGGER_3 = 293;

    // ═══════════════════════════════════════════════════════════════════
    //  الحالة الأساسية
    // ═══════════════════════════════════════════════════════════════════
    private FragmentMiraIdentityStudioBinding binding;
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private MiraPriceManager priceManager;
    private PermissionManager permissionManager;

    private IdentityFormManager formManager;
    private IdentityPhotoManager photoManager;
    private IdentitySyncManager syncManager;
    private PhotosAdapter photosAdapter;

    private MiraExecutorService miraExecutor;
    private Context appContext;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable rfidTimeoutRunnable;

    // ✅ [v6.5 NEW] الحالة الجديدة الحاكمة
    /** هل الزناد الفيزيائي مضغوط حالياً؟ */
    private volatile boolean isTriggerDown = false;
    /** ✅ [v6.5] قوة القراءة للمسح — أعلى بكثير من POS للسماح بالتقريب التدريجي */
    private volatile int savedPowerBeforeScan = -1;
    @Nullable private Runnable scanRetryRunnable;
    private static final long SCAN_RETRY_INTERVAL_MS = 5000;
    private static final int SCAN_POWER_DBM = 18;  // قوة مسح معقولة (عادة 5-30)
    /** عدّاد الجيل — يُبطل أي callback قديم */
    private final AtomicInteger scanGeneration = new AtomicInteger(0);
    /** هل القارئ في طور المسح النشط؟ */
    private volatile boolean isRfidScanning = false;
    /** هل استلمنا شريحة في هذه الدورة؟ */
    private volatile boolean tagReceivedThisScan = false;
    /** منع معالجة أكثر من شريحة في نفس وقت المسح */
    private final AtomicBoolean isProcessingTag = new AtomicBoolean(false);
    /** منع إعادة الدخول في stopRfidScanningMode */
    private final AtomicBoolean stoppingReader = new AtomicBoolean(false);

    private volatile boolean isFormLocked = false;
    private boolean isArabic = true;
    private AlertDialog activeLoadingDialog;

    private volatile boolean isEpcBound = false;
    private volatile String lastScannedEpc = "";
    private volatile String lastScanSource = "CHAINWAY";

    @Nullable private MiraIRFIDReader.OnTagReadListener encodingRfidListener;

    // ═══════════════════════════════════════════════════════════════════
    //  Photo Launchers
    // ═══════════════════════════════════════════════════════════════════
    private ActivityResultLauncher<Uri> takePhotoLauncher;
    private ActivityResultLauncher<String[]> pickImageLauncher;
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private Uri photoUri;
    private String currentPhotoPath;

    // ═══════════════════════════════════════════════════════════════════
    //  Power Management
    // ═══════════════════════════════════════════════════════════════════
    private volatile int savedPowerBeforeEncoding = -1;

    // ═══════════════════════════════════════════════════════════════════
    //  Scan Feedback Card
    // ═══════════════════════════════════════════════════════════════════
    @Nullable private View scanFeedbackOverlay;
    @Nullable private MaterialCardView scanFeedbackCard;
    @Nullable private ImageView ivFeedbackIcon;
    @Nullable private TextView tvFeedbackTitle;
    @Nullable private TextView tvFeedbackDetails;
    @Nullable private TextView tvFeedbackPrice;
    @Nullable private View vFeedbackAccent;
    @Nullable private Runnable pendingHideFeedback;

    @Nullable private volatile String pendingBarcodeFromScanner = null;

    // ═══════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMiraIdentityStudioBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (getContext() != null) {
            appContext = getContext().getApplicationContext();
        }

        try {
            if (savedInstanceState != null) {
                isFormLocked = savedInstanceState.getBoolean("is_form_locked", false);
                currentPhotoPath = savedInstanceState.getString("camera_photo_path", null);
            }

            initializeComponents();
            isArabic = Locale.getDefault().getLanguage().equals("ar");
            setupPhotoLaunchers();
            applyLanguage();
            setupPhotosRecyclerView();
            setupDraftNotificationCallback();

            view.post(() -> {
                if (!isAdded() || binding == null) return;
                try {
                    setupListeners();
                    initScanFeedbackCard(view);
                    applyPermissions();

                    if (getActivity() instanceof UHFMainActivity) {
                        ((UHFMainActivity) getActivity()).setActiveEncodingFragment(this);
                    }

                    if (miraExecutor != null && miraExecutor.isHealthy()) {
                        miraExecutor.getSingleThreadExecutor().execute(() -> {
                            if (formManager != null) {
                                formManager.cleanupOldDrafts();
                                formManager.checkAndNotifyDraft();
                            }
                            if (photoManager != null) {
                                photoManager.cleanupOrphanFiles(null);
                            }
                            safeRun(this::updatePendingSyncBadge);
                        });
                    }

                    Log.d(TAG, "✅ IdentityStudio v6.5 initialization complete");

                } catch (Exception e) {
                    Log.e(TAG, "Delayed init error: " + e.getMessage(), e);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error: " + e.getMessage(), e);
        }
    }
/**
 * ✅ [FIX-CRITICAL] يُستدعى بعد أن يستعيد Android حالة View (نص etEpc)
 *    نستخدمه لاستعادة isEpcBound فقط إذا كان الحقل لا يزال يطابق القيمة الممسوحة
 */
@Override
public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
    super.onViewStateRestored(savedInstanceState);

    if (savedInstanceState == null) return;
    if (!savedInstanceState.getBoolean("is_epc_bound", false)) return;
    if (binding == null || binding.etEpc == null) return;

    String savedBoundValue = savedInstanceState.getString("epc_bound_value", null);
    if (savedBoundValue == null || savedBoundValue.isEmpty()) return;

    // قراءة القيمة الحالية (بعد استعادة Android لها)
    String currentText = binding.etEpc.getText() != null
            ? binding.etEpc.getText().toString().trim() : "";

    // ✅ استعد isEpcBound فقط إذا كان الحقل لا يزال يطابق القيمة الأصلية
    if (currentText.equalsIgnoreCase(savedBoundValue)) {
        isEpcBound = true;
        binding.etEpc.setTag(savedBoundValue);

        if (binding.tvStatusBadge != null) {
            binding.tvStatusBadge.setText(
                    isArabic ? "✅ موثّق" : "✅ VERIFIED");
            binding.tvStatusBadge.setTextColor(0xFF22C55E);
        }

        Log.d(TAG, "♻️ isEpcBound restored from bundle (field matched)");
    } else {
        Log.w(TAG, "⚠️ Field mismatch — not restoring bound state. "
                + "Saved=" + maskEpc(savedBoundValue)
                + " Current=" + maskEpc(currentText));
        // الحقل تغيّر — المستخدم يجب أن يعيد المسح
        isEpcBound = false;
        if (binding.tvStatusBadge != null) {
            binding.tvStatusBadge.setText(
                    isArabic ? "⚠️ أعد المسح" : "⚠️ Rescan");
            binding.tvStatusBadge.setTextColor(0xFFF59E0B);
        }
    }
}
    private void initializeComponents() {
        if (appContext == null && getContext() != null) {
            appContext = getContext().getApplicationContext();
        }
        if (appContext == null) {
            Log.e(TAG, "initializeComponents: appContext is null!");
            return;
        }

        apiClient = MiraApiClient.getInstance(appContext);
        branchesManager = MiraBranchesManager.getInstance(appContext);
        orgProfile = MiraOrgProfileManager.getInstance(appContext);
        priceManager = MiraPriceManager.getInstance(appContext);
        permissionManager = PermissionManager.getInstance(appContext);
        miraExecutor = MiraExecutorService.getInstance();
        formManager = new IdentityFormManager(appContext);
        photoManager = new IdentityPhotoManager(appContext, miraExecutor.getBackgroundExecutor());
        syncManager = new IdentitySyncManager(appContext, miraExecutor.getBackgroundExecutor());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Draft Notification
    // ═══════════════════════════════════════════════════════════════════

    private void setupDraftNotificationCallback() {
        if (formManager == null) return;
        formManager.setNotificationCallback(new IdentityFormManager.DraftNotificationCallback() {
            @Override
            public void onDraftFound(@NonNull IdentityFormManager.DraftInfo info,
                                     @NonNull String ageText) {
                safeRun(() -> showDraftFoundDialog(info, ageText));
            }

            @Override
            public void onDraftRestored(@NonNull IdentityFormManager.DraftInfo info) {
                safeRun(() -> {
                    showToast(isArabic ? "📝 تم استعادة المسودة" : "📝 Draft restored");
                    onPhotosUpdated();
                });
            }

            @Override
            public void onDraftExpired(@NonNull IdentityFormManager.DraftInfo info) {
                safeRun(() -> {
                    if (isAdded()) {
                        showToast(isArabic ? "🧹 تم حذف مسودة قديمة" : "🧹 Old draft removed");
                    }
                });
            }
        });
    }

    private void showDraftFoundDialog(IdentityFormManager.DraftInfo info, String ageText) {
        if (!isAdded() || getContext() == null) return;
        try {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(isArabic ? "📝 مسودة محفوظة" : "📝 Saved Draft")
                    .setMessage(formManager.getDraftSummary())
                    .setPositiveButton(isArabic ? "استعادة" : "Restore",
                            (d, w) -> restoreDraftIfExists())
                    .setNegativeButton(isArabic ? "تجاهل" : "Discard",
                            (d, w) -> {
                                if (formManager != null) formManager.clearDraft();
                                showToast(isArabic ? "🗑️ تم حذف المسودة" : "🗑️ Draft discarded");
                            })
                    .setCancelable(false)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showDraftFoundDialog error: " + e.getMessage());
        }
    }

    private void restoreDraftIfExists() {
        if (binding == null || formManager == null) return;
        if (isFormLocked) return;

        try {
            final List<String> photoPaths = new ArrayList<>();
            boolean restored = formManager.restoreDraft(
                    new IdentityFormManager.FormData() {
                        @Override public String getEpc() { return safeGet(binding != null ? binding.etEpc : null); }
                        @Override public String getTitle() { return safeGet(binding != null ? binding.etTitle : null); }
                        @Override public String getGrossWeight() { return safeGet(binding != null ? binding.etGrossWeight : null); }
                        @Override public boolean isHasStones() {
                            return binding != null && binding.switchHasStones != null
                                    && binding.switchHasStones.isChecked();
                        }
                        @Override public String getStoneType() { return safeGet(binding != null ? binding.etStoneType : null); }
                        @Override public String getStoneWeight() { return safeGet(binding != null ? binding.etStoneWeight : null); }
                        @Override public String getStoneCount() { return safeGet(binding != null ? binding.etStoneCount : null); }
                        @Override public String getStoneValue() { return safeGet(binding != null ? binding.etStoneValue : null); }

                        @Override public void setEpc(String v) {
                            safeRun(() -> { if (binding != null && binding.etEpc != null && v != null) binding.etEpc.setText(v); });
                        }
                        @Override public void setTitle(String v) {
                            safeRun(() -> { if (binding != null && binding.etTitle != null && v != null) binding.etTitle.setText(v); });
                        }
                        @Override public void setGrossWeight(String v) {
                            safeRun(() -> { if (binding != null && binding.etGrossWeight != null && v != null) binding.etGrossWeight.setText(v); });
                        }
                        @Override public void setStoneType(String v) {
                            safeRun(() -> { if (binding != null && binding.etStoneType != null && v != null) binding.etStoneType.setText(v); });
                        }
                        @Override public void setStoneWeight(String v) {
                            safeRun(() -> { if (binding != null && binding.etStoneWeight != null && v != null) binding.etStoneWeight.setText(v); });
                        }
                        @Override public void setStoneCount(String v) {
                            safeRun(() -> { if (binding != null && binding.etStoneCount != null && v != null) binding.etStoneCount.setText(v); });
                        }
                        @Override public void setStoneValue(String v) {
                            safeRun(() -> { if (binding != null && binding.etStoneValue != null && v != null) binding.etStoneValue.setText(v); });
                        }
                        @Override public void setHasStones(boolean v) {
                            safeRun(() -> { if (binding != null && binding.switchHasStones != null) binding.switchHasStones.setChecked(v); });
                        }
                    }, photoPaths);

            if (restored) {
                if (photoManager != null && !photoPaths.isEmpty()) {
                    photoManager.restoreFromSavedPaths(photoPaths, photoCallback);
                }
                // بعد اكتمال الاستعادة
safeRun(() -> {
    if (binding != null && binding.etEpc != null
            && binding.etEpc.getText() != null
            && binding.etEpc.getText().length() > 0) {
        // المسودة قديمة — نطلب إعادة مسح
        isEpcBound = false;
        if (binding.tvStatusBadge != null) {
            binding.tvStatusBadge.setText(
                    isArabic ? "⚠️ أعد مسح الشريحة" : "⚠️ Rescan tag");
            binding.tvStatusBadge.setTextColor(0xFFF59E0B);
        }
        showToast(isArabic
                ? "⚠️ المسودة استُعيدت — أعد مسح الشريحة قبل الحفظ"
                : "⚠️ Draft restored — rescan tag before saving");
    }
});
            }
        } catch (Exception e) {
            Log.e(TAG, "restoreDraftIfExists error: " + e.getMessage(), e);
        }
    }

    private void saveCurrentDraftAsync() {
        final FragmentMiraIdentityStudioBinding b = binding;
        if (formManager == null || b == null) return;
        final List<String> paths = photoManager != null ? photoManager.getPhotoPaths() : new ArrayList<>();

        formManager.saveDraft(new IdentityFormManager.FormData() {
            @Override public String getEpc() { return safeGet(b.etEpc); }
            @Override public String getTitle() { return safeGet(b.etTitle); }
            @Override public String getGrossWeight() { return safeGet(b.etGrossWeight); }
            @Override public boolean isHasStones() {
                return b.switchHasStones != null && b.switchHasStones.isChecked();
            }
            @Override public String getStoneType() { return safeGet(b.etStoneType); }
            @Override public String getStoneWeight() { return safeGet(b.etStoneWeight); }
            @Override public String getStoneCount() { return safeGet(b.etStoneCount); }
            @Override public String getStoneValue() { return safeGet(b.etStoneValue); }

            @Override public void setEpc(String v) { }
            @Override public void setTitle(String v) { }
            @Override public void setGrossWeight(String v) { }
            @Override public void setStoneType(String v) { }
            @Override public void setStoneWeight(String v) { }
            @Override public void setStoneCount(String v) { }
            @Override public void setStoneValue(String v) { }
            @Override public void setHasStones(boolean v) { }
        }, paths);
    }

    private String safeGet(TextView tv) {
        return tv != null && tv.getText() != null ? tv.getText().toString() : "";
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Photos
    // ═══════════════════════════════════════════════════════════════════

    private void setupPhotosRecyclerView() {
        if (binding == null || binding.rvPhotosPreview == null) return;

        photosAdapter = new PhotosAdapter(
                requireContext(),
                photoManager != null ? photoManager.getPhotoPaths() : new ArrayList<>(),
                (position, path) -> {
                    if (isFormLocked) {
                        showToast(isArabic ? "⛔ النموذج مقفل" : "⛔ Form is locked");
                        return;
                    }
                    if (photoManager != null) {
                        photoManager.removePhoto(position, false, photoCallback);
                    }
                });

        photosAdapter.setOnPhotoClickListener((position, path, sourceView) -> {
            showPhotoPreviewDialog(path);
        });

        binding.rvPhotosPreview.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        binding.rvPhotosPreview.setAdapter(photosAdapter);
    }

    private final IdentityPhotoManager.PhotoCallback photoCallback = new IdentityPhotoManager.PhotoCallback() {
        @Override
        public void onPhotoAdded(@NonNull String path, int totalCount) {
            safeRun(() -> {
                if (photosAdapter != null && photoManager != null) {
                    photosAdapter.submitList(photoManager.getPhotoPaths());
                }
                updatePhotosCountText();
                if (binding != null && binding.rvPhotosPreview != null) {
                    binding.rvPhotosPreview.setVisibility(
                            (photoManager != null && photoManager.getPhotoCount() > 0)
                                    ? View.VISIBLE : View.GONE);
                }
            });
        }

        @Override
        public void onPhotoRemoved(@NonNull String path, int totalCount) {
            safeRun(() -> {
                if (photosAdapter != null && photoManager != null) {
                    photosAdapter.submitList(photoManager.getPhotoPaths());
                }
                updatePhotosCountText();
                if (binding != null && binding.rvPhotosPreview != null) {
                    binding.rvPhotosPreview.setVisibility(
                            (photoManager != null && photoManager.getPhotoCount() > 0)
                                    ? View.VISIBLE : View.GONE);
                }
            });
        }

        @Override
        public void onError(@NonNull String message) {
            safeRun(() -> showToast("❌ " + message));
        }
    };

    private void showPhotoPreviewDialog(@NonNull String path) {
        if (!isAdded() || getContext() == null) return;
        try {
            BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
            View view = getLayoutInflater().inflate(R.layout.dialog_photo_preview, null);
            dialog.setContentView(view);

            ImageView ivPreview = view.findViewById(R.id.ivPreview);
            MaterialButton btnClose = view.findViewById(R.id.btnClosePreview);

            if (ivPreview != null) {
                Glide.with(this).load(new File(path))
                        .placeholder(R.drawable.placeholder_image)
                        .error(R.drawable.ic_error_outline)
                        .into(ivPreview);
            }
            if (btnClose != null) btnClose.setOnClickListener(v -> dialog.dismiss());
            dialog.show();
        } catch (Exception e) {
            Log.e(TAG, "showPhotoPreviewDialog error: " + e.getMessage());
        }
    }

    private void setupPhotoLaunchers() {
        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) openCamera();
                    else showToast(isArabic ? "صلاحية الكاميرا مطلوبة" : "Camera permission required");
                });

        takePhotoLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    if (success && photoManager != null) {
                        photoManager.compressAndAddPhoto(currentPhotoPath, photoCallback);
                    }
                });

        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                uris -> {
                    if (uris == null || uris.isEmpty() || photoManager == null) return;
                    int currentCount = photoManager.getPhotoCount();
                    int remaining = MAX_PHOTOS - currentCount;
                    if (remaining <= 0) {
                        showToast(isArabic
                                ? "⚠️ وصلت للحد الأقصى (" + MAX_PHOTOS + " صور)"
                                : "⚠️ Max photos reached (" + MAX_PHOTOS + ")");
                        return;
                    }
                    int toAdd = Math.min(uris.size(), remaining);
                    for (int i = 0; i < toAdd; i++) {
                        photoManager.copyAndAddPhoto(uris.get(i), photoCallback);
                    }
                    if (uris.size() > remaining) {
                        showToast(isArabic
                                ? "⚠️ أضيفت " + toAdd + " فقط (الحد " + MAX_PHOTOS + ")"
                                : "⚠️ Added " + toAdd + " only (max " + MAX_PHOTOS + ")");
                    }
                });
    }

    private void onPhotosUpdated() {
        if (!isAdded() || binding == null || isRemoving() || isDetached()) return;
        if (photosAdapter != null && photoManager != null) {
            photosAdapter.submitList(photoManager.getPhotoPaths());
        }
        updatePhotosCountText();
        if (binding.rvPhotosPreview != null && photoManager != null) {
            binding.rvPhotosPreview.setVisibility(
                    photoManager.getPhotoCount() == 0 ? View.GONE : View.VISIBLE);
        }
    }

    private void updatePhotosCountText() {
        try {
            if (binding == null || binding.tvPhotosCount == null) return;
            int count = photoManager != null ? photoManager.getPhotoCount() : 0;
            binding.tvPhotosCount.setText(count == 0
                    ? (isArabic ? "لم يتم إرفاق صور بعد" : "No photos attached yet")
                    : (isArabic ? "تم إرفاق (" + count + "/" + MAX_PHOTOS + ") صور"
                                : "Attached (" + count + "/" + MAX_PHOTOS + ") photos"));
        } catch (Exception e) {
            Log.e(TAG, "updatePhotosCountText error: " + e.getMessage());
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
//  🎯 Physical RFID Trigger — الإصلاح الجذري
// ═══════════════════════════════════════════════════════════════════

@Override
public boolean myOnKeyDwon(int keyCode, KeyEvent event) {
    boolean isTrigger = (keyCode == KEYCODE_TRIGGER_1
            || keyCode == KEYCODE_TRIGGER_2
            || keyCode == KEYCODE_TRIGGER_3);
    if (!isTrigger) return false;

    // ═══════════════════════════════════════════════════════
    //  ACTION_DOWN — بدء المسح
    // ═══════════════════════════════════════════════════════
    if (event.getAction() == KeyEvent.ACTION_DOWN
            && event.getRepeatCount() == 0) {

        // ✅ منع تكرار ACTION_DOWN بدون ACTION_UP
        if (isTriggerDown) {
            Log.d(TAG, "⚠️ Trigger already down — ignoring repeated DOWN");
            return true;
        }

        // ✅ فحص الصلاحيات قبل كل شيء
        if (permissionManager == null
                || (!permissionManager.canEdit("identity")
                    && !permissionManager.isManager())) {
            showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            return true;
        }

        // ✅ إذا فيه شريحة قيد المعالجة، ارفض
        if (isProcessingTag.get()) {
            Log.d(TAG, "⏭️ Tag processing in progress — trigger ignored");
            showToast(isArabic ? "⏳ جاري معالجة الشريحة..." : "⏳ Processing tag...");
            return true;
        }

        if (isFormLocked) {
            showToast(isArabic ? "⛔ العملية قيد التنفيذ" : "⛔ Operation in progress");
            return true;
        }

        // ✅ علّم الزناد كمضغوط — المصدر الوحيد للحقيقة
        isTriggerDown = true;
        Log.i(TAG, "🎯 Trigger DOWN — starting scan");
        activateRFIDModeForEncoding();
        return true;
    }

    // ═══════════════════════════════════════════════════════
    //  ACTION_UP — إيقاف المسح فوراً (بدون flip-flop)
    // ═══════════════════════════════════════════════════════
    if (event.getAction() == KeyEvent.ACTION_UP) {
        if (!isTriggerDown) {
            Log.d(TAG, "⚠️ Trigger UP but was not down — ignoring");
            return true;
        }

        isTriggerDown = false;
        Log.i(TAG, "🎯 Trigger UP — stopping scan immediately");

        if (isRfidScanning) {
            stopRfidScanningMode();
        }
        return true;
    }

    return false;
}

/**
 * ✅ [v6.5] يبدأ المسح — إما مباشرة أو بعد تهيئة القارئ
 * بدون استدعاء متكرر (no recursion)
 */
private void activateRFIDModeForEncoding() {
    if (permissionManager == null) return;
    if (!(permissionManager.canEdit("identity") || permissionManager.isManager())) {
        showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
        return;
    }
    if (appContext == null) {
        Log.e(TAG, "appContext is null");
        return;
    }
    if (isRfidScanning) {
        Log.d(TAG, "⏭️ Already scanning — ignoring activate");
        return;
    }
    if (!isTriggerDown) {
        Log.d(TAG, "⏭️ Trigger not down — aborting activate");
        return;
    }

    RFIDManager mgr = RFIDManager.getInstance(appContext);
    if (mgr == null) {
        Log.e(TAG, "❌ RFIDManager null");
        return;
    }

    final int myGen = scanGeneration.incrementAndGet();
    isRfidScanning = true;
    updateScanningUI(true);
    
    // ✅ [v6.5] ارفع القوة للمسح
applyScanPowerForReading();

    if (!mgr.isReady()) {
        Log.i(TAG, "⏳ Reader not ready — waiting...");
        showToast(isArabic ? "⏳ جاري تهيئة القارئ..." : "⏳ Initializing reader...");

        mgr.ensureReady(new RFIDManager.ReadyCallback() {
            @Override
            public void onReady(MiraIRFIDReader reader) {
                safeRun(() -> onReaderReadyForScan(myGen, mgr));
            }

            @Override
            public void onError(String reason) {
                safeRun(() -> onReaderInitError(myGen, reason));
            }
        });
        return;
    }

    // القارئ جاهز — ابدأ المسح فوراً
    startScanWithManager(myGen, mgr);
}

/**
 * ✅ callback عند جاهزية القارئ — فحوصات أمان صارمة
 */
private void onReaderReadyForScan(int gen, RFIDManager mgr) {
    // 1) هل الـ callback لا يزال حديثاً؟
    if (scanGeneration.get() != gen) {
        Log.d(TAG, "⏭️ Stale ready callback (gen " + gen + " vs " + scanGeneration.get() + ") — ignoring");
        return;
    }

    // 2) هل الزناد لا يزال مضغوطاً؟
    if (!isTriggerDown) {
        Log.i(TAG, "⏭️ Trigger released during init — aborting");
        isRfidScanning = false;
        updateScanningUI(false);
        return;
    }

    // 3) هل الـ fragment لا يزال حياً؟
    if (!isAdded() || binding == null) {
        Log.d(TAG, "⏭️ Fragment detached — aborting");
        isRfidScanning = false;
        return;
    }

    // تابع المسح
    startScanWithManager(gen, mgr);
}

private void onReaderInitError(int gen, String reason) {
    if (scanGeneration.get() != gen) return;
    Log.e(TAG, "❌ Reader init failed: " + reason);
    isRfidScanning = false;
    updateScanningUI(false);
    showErrorDialog(
            isArabic ? "⚠️ القارئ غير متاح" : "⚠️ Reader unavailable",
            reason != null ? reason : "Unknown error");
}

/**
 * ✅ يبدأ المسح الفعلي — التسجيل + startScan + timeout
 */
private void startScanWithManager(int gen, RFIDManager mgr) {
    if (scanGeneration.get() != gen) return;
    if (!isTriggerDown) {
        isRfidScanning = false;
        updateScanningUI(false);
        return;
    }

    try {
        // إنشاء listener
        encodingRfidListener = new MiraIRFIDReader.OnTagReadListener() {
        @Override
            public void onTagRead(String epc, int rssi) {
    if (!isRfidScanning || epc == null || epc.trim().isEmpty()) return;
    if (!isProcessingTag.compareAndSet(false, true)) return;

    tagReceivedThisScan = true;
    final String receivedEpc = epc.trim().toUpperCase(Locale.US);

    // ✅ استعادة القوة فوراً — خارج safeRun
    restorePowerForEncoding();
    stopRfidScanningMode();

    safeRun(() -> {
        
                    

                    if (miraExecutor == null) {
                        isProcessingTag.set(false);
                        return;
                    }

                    miraExecutor.getBackgroundExecutor().execute(() -> {
                        try { Thread.sleep(50); } catch (InterruptedException ignored) {}

                        String tid = null;
                        try {
                            tid = MiraRfidService.getInstance(appContext).readTid(null);
                        } catch (Exception e) {
                            Log.w(TAG, "readTid failed", e);
                        }

                        if (tid == null || tid.trim().isEmpty()) {
                            Log.e(TAG, "❌ TID read failed");
                            isProcessingTag.set(false);
                            safeRun(() -> {
                                playErrorSound();
                                showErrorDialog(
                                        isArabic ? "❌ فشل قراءة TID" : "❌ TID Read Failed",
                                        isArabic
                                                ? "لا يمكن قراءة TID الشريحة.\n\n💡 قرّب الشريحة وأعد المحاولة."
                                                : "Cannot read tag TID.\n\n💡 Move tag closer and retry.");
                            });
                            return;
                        }

                        final String finalTid = tid.trim().toUpperCase(Locale.US);
                        secureLog("✅ TID read: " + maskEpc(finalTid));
                        safeRun(() -> processReceivedTagWithTid(receivedEpc, finalTid));
                    });
                });
            }

            @Override public void onScanStarted() {}
            @Override public void onScanStopped() {}
            @Override public void onConnectionChanged(boolean c) {}
            @Override
            public void onError(String error) {
                Log.e(TAG, "RFID error: " + error);
            }
        };

        mgr.addListener(encodingRfidListener);

        mgr.startScan(new RFIDManager.ReadyCallback() {
            @Override
            public void onReady(MiraIRFIDReader reader) {
                Log.i(TAG, "✅ Encoding scan started");
            }

            @Override
            public void onError(String reason) {
                Log.e(TAG, "❌ Encoding scan failed: " + reason);
                safeRun(() -> {
                    stopRfidScanningMode();
                    showErrorDialog(
                            isArabic ? "❌ فشل بدء المسح" : "❌ Scan start failed",
                            reason != null ? reason : "Reader not ready");
                });
            }
        });

        showToast(isArabic
                ? "📡 جاري البحث عن شريحة RFID..."
                : "📡 Searching for RFID tag...");

        if (rfidTimeoutRunnable != null) {
            handler.removeCallbacks(rfidTimeoutRunnable);
        }
        rfidTimeoutRunnable = this::stopRfidScanningMode;
        handler.postDelayed(rfidTimeoutRunnable, RFID_TIMEOUT_MS);
        // ✅ [v6.5] إعادة تشغيل المسح تلقائياً كل 5 ثوانٍ
scheduleScanRetry(gen, mgr);

    } catch (Exception e) {
        Log.e(TAG, "startScanWithManager error: " + e.getMessage(), e);
        stopRfidScanningMode();
    }
}

/**
 * ✅ [v6.5] بعض القارئات تتوقف بعد دورة inventory واحدة.
 *    نعيد startScan كل 5 ثوانٍ طالما الزناد مضغوط ولم نقرأ شيئاً.
 */
private void scheduleScanRetry(int gen, RFIDManager mgr) {
    if (scanRetryRunnable != null) {
        handler.removeCallbacks(scanRetryRunnable);
        scanRetryRunnable = null;
    }

    scanRetryRunnable = () -> {
        // فحوصات: الجيل صحيح؟ الزناد مضغوط؟ لم نقرأ شريحة؟ المسح نشط؟
        if (scanGeneration.get() != gen) return;
        if (!isTriggerDown) return;
        if (tagReceivedThisScan) return;
        if (!isRfidScanning) return;
        if (!isAdded() || binding == null) return;

        Log.d(TAG, "🔄 Scan retry tick (5s) — restarting startScan");

        try {
            mgr.startScan(new RFIDManager.ReadyCallback() {
                @Override public void onReady(MiraIRFIDReader reader) {
                    Log.d(TAG, "🔄 Scan retry — started");
                }
                @Override public void onError(String reason) {
                    Log.w(TAG, "🔄 Scan retry error: " + reason);
                }
            });

            // جدولة المحاولة القادمة
            scheduleScanRetry(gen, mgr);

        } catch (Exception e) {
            Log.w(TAG, "scheduleScanRetry startScan failed", e);
        }
    };

    handler.postDelayed(scanRetryRunnable, SCAN_RETRY_INTERVAL_MS);
}

/**
 * ✅ [v6.5] إيقاف آمن — لا يُغلق نفسه
 * يُصفّر كل الحالة ويلغي callbacks قديمة
 */
 
private void stopRfidScanningMode() {
    scanGeneration.incrementAndGet();

    boolean wasScanning = isRfidScanning;
    isRfidScanning = false;
    tagReceivedThisScan = false;

    if (rfidTimeoutRunnable != null) {
        handler.removeCallbacks(rfidTimeoutRunnable);
        rfidTimeoutRunnable = null;
    }

    // ✅ [v6.5] ألغِ retry
    if (scanRetryRunnable != null) {
        handler.removeCallbacks(scanRetryRunnable);
        scanRetryRunnable = null;
    }

    updateScanningUI(false);

    if (!wasScanning) return;
    if (appContext == null || miraExecutor == null) return;

    // 4) حماية من التكرار + timeout
    if (!stoppingReader.compareAndSet(false, true)) {
        // ⚠️ عالق من قبل — جدول reset بعد 3 ثوان
        handler.postDelayed(() -> {
            if (stoppingReader.get()) {
                stoppingReader.set(false);
                Log.w(TAG, "⚠️ stoppingReader force-reset after timeout");
            }
        }, STOPPING_READER_TIMEOUT_MS);
        return;
    }

    final MiraIRFIDReader.OnTagReadListener toRemove = encodingRfidListener;
    encodingRfidListener = null;

    try {
        miraExecutor.getBackgroundExecutor().execute(() -> {
            try {
                RFIDManager mgr = RFIDManager.getInstance(appContext);
                if (mgr == null) return;

                if (toRemove != null) {
                    try { mgr.removeListener(toRemove); }
                    catch (Exception ignored) {}
                }

                try { mgr.stopScan(); }
                catch (Exception ignored) {}

                Log.d(TAG, "✅ RFID stopped via manager");
            } finally {
                stoppingReader.set(false);
            }
        });
    } catch (Exception e) {
        // If executor rejected (e.g., shutdown), release lock
        stoppingReader.set(false);
        Log.e(TAG, "stopRfidScanningMode execute failed", e);
    }
}

private void updateScanningUI(boolean scanning) {
    safeRun(() -> {
        if (binding == null) return;
        if (binding.btnScanRfid != null) {
            binding.btnScanRfid.setText(scanning
                    ? (isArabic ? "جاري المسح..." : "Scanning...")
                    : (isArabic ? "مسح رمز RFID" : "Scan RFID Tag"));
        }
        if (binding.tvStatusBadge != null) {
            if (scanning) {
                binding.tvStatusBadge.setText("SCANNING...");
            } else {
                binding.tvStatusBadge.setText(isEpcBound ? "BOUND" : "WAITING");
            }
        }
    });
}

// ═══════════════════════════════════════════════════════════════════
//  Tag Processing
// ═══════════════════════════════════════════════════════════════════

private void processReceivedTagWithTid(final String epc,
                                       @Nullable final String preReadTid,
                                       final String source) {
    // ✅ [v6.5] نضمن إعادة تعيين isProcessingTag في كل مسار خروج
    if (epc == null || epc.isEmpty()) {
        isProcessingTag.set(false);
        return;
    }
    if (!isAdded() || binding == null || isRemoving() || isDetached()) {
        isProcessingTag.set(false);
        return;
    }

    final String cleanEpc = epc.trim().toUpperCase(Locale.US);
    lastScannedEpc = cleanEpc;
    lastScanSource = source;
    secureLog("🎯 Processing tag: " + maskEpc(cleanEpc) + " | source=" + source);

    miraExecutor.getBackgroundExecutor().execute(() -> {
        boolean isTagAllowed = false, isDuplicate = false;
        String actualTid = cleanEpc;
        boolean isTidSkipped = false;
        String idForDuplicateCheck = cleanEpc;

        try {
            MiraRfidService rfidService = MiraRfidService.getInstance(appContext);

            if ("DESKTOP_RFID".equals(source) && (preReadTid == null || preReadTid.trim().isEmpty())) {
                actualTid = "DESKTOP_SKIP_TID";
                isTagAllowed = true;
                isTidSkipped = true;
                idForDuplicateCheck = cleanEpc;
                Log.i(TAG, "🖥️ Desktop Reader: Skipping TID verification (allowed by default).");
            } else if (preReadTid != null && !preReadTid.trim().isEmpty()) {
                actualTid = preReadTid.trim().toUpperCase(Locale.US);
                boolean isSim = rfidService.isSimulationMode();
                boolean localAllowed = isSim || rfidService.verifyTagAllowed(actualTid);
                if (!localAllowed && !isSim) {
                    Log.w(TAG, "⚠️ Local whitelist rejected " + maskEpc(actualTid)
                            + " — server verify in /verify-encoding-tag will decide");
                }
                isTagAllowed = true;
                idForDuplicateCheck = actualTid;
                Log.i(TAG, "✅ Using pre-read TID: " + maskEpc(actualTid));
            } else {
                String tidFromTag = rfidService.readTid(null);
                if (tidFromTag != null && !tidFromTag.trim().isEmpty()) {
                    actualTid = tidFromTag.trim().toUpperCase(Locale.US);
                }
                boolean isSim = rfidService.isSimulationMode();
                boolean localAllowed = isSim || rfidService.verifyTagAllowed(actualTid);
                if (!localAllowed && !isSim) {
                    Log.w(TAG, "⚠️ Local whitelist rejected " + maskEpc(actualTid)
                            + " — server verify in /verify-encoding-tag will decide");
                }
                isTagAllowed = true;
                idForDuplicateCheck = actualTid;
                Log.i(TAG, "✅ Read TID from reader: " + maskEpc(actualTid));
            }

            isDuplicate = syncManager.isEpcDuplicateLocally(idForDuplicateCheck);

        } catch (Exception e) {
            Log.e(TAG, "processReceivedTagWithTid checks error: " + e.getMessage(), e);
            isProcessingTag.set(false);
            return;
        }

        final String finalTid = actualTid;

        // فحص طول TID
        if (!isTidSkipped && ENABLE_TID_LENGTH_VALIDATION) {
            if (finalTid.length() < MIN_TID_LENGTH || finalTid.length() > MAX_TID_LENGTH) {
                isProcessingTag.set(false);
                safeRun(() -> {
                    playErrorSound();
                    showErrorDialog(
                            isArabic ? "⚠️ TID غير صالح" : "⚠️ Invalid TID",
                            isArabic ? "طول TID غير متوقع: " + finalTid.length() + " حرف"
                                     : "Unexpected TID length: " + finalTid.length() + " chars");
                });
                return;
            }
        }

        final boolean fAllowed = isTagAllowed;
        final boolean fDuplicate = isDuplicate;
        final boolean fTidSkipped = isTidSkipped;
        final String displayEpcValue = isTidSkipped ? cleanEpc : actualTid;

        safeRun(() -> {
            try {
                if (binding == null || !isAdded()) return;

                if (!fAllowed) {
                    playErrorSound();
                    showScanFeedback(
                            isArabic ? "❌ شريحة غير معتمدة" : "❌ Unauthorized Tag",
                            isArabic ? "TID: " + finalTid + "\nليست من الموردين المعتمدين"
                                     : "TID: " + finalTid + "\nNot from approved suppliers",
                            null, android.R.drawable.ic_delete, 0xFFDC2626);
                    showErrorDialog(
                            isArabic ? "⛔ شريحة غير معتمدة" : "⛔ Unauthorized Tag",
                            isArabic ? "هذه الشريحة ليست من مخزون الشرائح المعتمدة:\n\n🔹 TID: " + finalTid + "\n\n💡 استخدم شريحة من مورد معتمد."
                                     : "This tag is not from approved inventory:\n\n🔹 TID: " + finalTid + "\n\n💡 Use a tag from an approved supplier.");
                    return;
                }

                if (fDuplicate) {
                    playErrorSound();
                    showScanFeedback(
                            isArabic ? "⚠️ شريحة مكررة" : "⚠️ Duplicate Tag",
                            isArabic ? "ID: " + displayEpcValue + "\nمسجلة بالفعل في النظام"
                                     : "ID: " + displayEpcValue + "\nAlready registered",
                            null, android.R.drawable.ic_dialog_alert, 0xFF3B82F6);
                    showErrorDialog(
                            isArabic ? "شريحة مكررة" : "Duplicate Tag",
                            isArabic ? "هذه الشريحة مسجلة بالفعل في النظام:\n\n🔹 ID: " + displayEpcValue + "\n\n💡 استخدم شريحة جديدة."
                                     : "This tag is already registered:\n\n🔹 ID: " + displayEpcValue + "\n\n💡 Use a new tag.");
                    return;
                }

                boolean wasReplacing = false;
                if (isEpcBound && binding.etEpc != null) {
                    String existing = binding.etEpc.getText() != null
                            ? binding.etEpc.getText().toString().trim() : "";
                    if (!existing.isEmpty() && !existing.equals(displayEpcValue)) {
                        wasReplacing = true;
                    }
                }

                if (binding.etEpc != null) {
                    binding.etEpc.setText(displayEpcValue);
                    binding.etEpc.setTag(displayEpcValue);
                    isEpcBound = true;
                }
                if (binding.tvStatusBadge != null) {
                    binding.tvStatusBadge.setText("BOUND");
                }

                String titleText = wasReplacing
                        ? (isArabic ? "🔄 تم استبدال الوسم" : "🔄 Tag Replaced")
                        : (isArabic ? "✅ شريحة صالحة" : "✅ Valid Tag");

                String tidDisplay = fTidSkipped
                        ? (isArabic ? "غير متوفر (قارئ مكتبي)" : "N/A (Desktop)")
                        : finalTid;

                String detailsText = wasReplacing
                        ? (isArabic ? "TID جديد: " + tidDisplay + "\nجاهز للتكويد"
                                    : "New TID: " + tidDisplay + "\nReady for encoding")
                        : (isArabic ? "TID: " + tidDisplay + "\nجاهزة للتكويد"
                                    : "TID: " + tidDisplay + "\nReady for encoding");

                showScanFeedback(titleText, detailsText, null,
                        R.drawable.ic_pos_cart_gold, 0xFF22C55E);
                playSuccessSound();

            } finally {
                isProcessingTag.set(false);
            }
        });
    });
}

private void processReceivedTagWithTid(final String epc, @Nullable final String preReadTid) {
    processReceivedTagWithTid(epc, preReadTid, "CHAINWAY");
}

/**
 * ✅ نقطة الدخول من UHFMainActivity (نادرة)
 */
public void onRfidScanned(String epc) {
    if (!isAdded() || getActivity() == null) return;
    if (epc == null || epc.trim().isEmpty()) return;
    if (appContext == null || miraExecutor == null) return;

    if (permissionManager == null
            || (!permissionManager.canEdit("identity") && !permissionManager.isManager())) {
        Log.w(TAG, "onRfidScanned: unauthorized — ignoring");
        return;
    }
    if (isFormLocked) return;

    final String scannedEpc = epc.trim().toUpperCase(Locale.US);
    Log.i(TAG, "📡 onRfidScanned (external): " + maskEpc(scannedEpc));
    lastScannedEpc = scannedEpc;

    if (!isProcessingTag.compareAndSet(false, true)) {
        Log.d(TAG, "⏭️ onRfidScanned skipped — already processing");
        return;
    }

    miraExecutor.getBackgroundExecutor().execute(() -> {
        String tid = null;
        try {
            tid = MiraRfidService.getInstance(appContext).readTid(null);
        } catch (Exception e) {
            Log.w(TAG, "onRfidScanned: readTid failed", e);
        }
        final String finalTid = (tid != null) ? tid.trim().toUpperCase(Locale.US) : null;

        safeRun(() -> {
            if (finalTid == null || finalTid.isEmpty()) {
                isProcessingTag.set(false);
                playErrorSound();
                showErrorDialog(
                        isArabic ? "❌ فشل قراءة TID" : "❌ TID Read Failed",
                        isArabic ? "أعد المحاولة مع تقريب الشريحة."
                                 : "Retry with tag closer to reader.");
                return;
            }
            processReceivedTagWithTid(scannedEpc, finalTid);
        });
    });
}

// ═══════════════════════════════════════════════════════════════════
//  Server Verification
// ═══════════════════════════════════════════════════════════════════

private String verifyTagWithServerBeforeEncodingSync(String tid, String epc, String source) {
    if (apiClient == null) {
        return isArabic ? "❌ لا اتصال بالسيرفر" : "❌ No server connection";
    }
    try {
        JSONObject payload = new JSONObject();
        payload.put("source", source != null ? source : "CHAINWAY");

        if ("DESKTOP_RFID".equals(source)) {
            if (epc != null && !epc.isEmpty()) payload.put("epc", epc);
        } else {
            if (tid != null && !tid.isEmpty()) payload.put("tid", tid);
            if (epc != null && !epc.isEmpty()) payload.put("epc", epc);
        }

        Log.d(TAG, "[VERIFY] Sending: " + payload.toString());

        MiraApiClient.ApiResponse response = apiClient.postWithAuth("/verify-encoding-tag", payload);

        if (response == null) {
            return isArabic ? "❌ لم يتم استلام رد من السيرفر" : "❌ No response from server";
        }
        if (!response.isSuccess) {
            String errorMsg = extractErrorMessage(response);
            Log.w(TAG, "[VERIFY] Rejected: " + errorMsg);
            return errorMsg != null ? errorMsg
                    : (isArabic ? "الشريحة غير معتمدة" : "Tag not authorized");
        }

        Log.i(TAG, "✅ [VERIFY] Tag approved by server");
        return null;
    } catch (Exception e) {
        Log.e(TAG, "[VERIFY] Exception", e);
        return isArabic
                ? "❌ خطأ في التحقق: " + e.getMessage()
                : "❌ Verification error: " + e.getMessage();
    }
}
    // ═══════════════════════════════════════════════════════════════════
    //  Calculations
    // ═══════════════════════════════════════════════════════════════════

    private void calculateEstimatedGoldValue() {
        try {
            if (binding == null || binding.etGrossWeight == null) return;

            String weightStr = binding.etGrossWeight.getText() != null
                    ? binding.etGrossWeight.getText().toString().trim() : "";
            if (weightStr.isEmpty()) {
                resetCalculatedDisplay();
                return;
            }

            double grossWeight = Double.parseDouble(weightStr);
            if (grossWeight <= 0) {
                resetCalculatedDisplay();
                return;
            }

            double stoneWeightCarats = 0.0;
            double stoneValue = 0.0;

            if (binding.switchHasStones.isChecked()) {
                String sWeight = binding.etStoneWeight.getText() != null
                        ? binding.etStoneWeight.getText().toString().trim() : "";
                String sValue = binding.etStoneValue.getText() != null
                        ? binding.etStoneValue.getText().toString().trim() : "";

                if (!sWeight.isEmpty()) stoneWeightCarats = Double.parseDouble(sWeight);
                if (!sValue.isEmpty()) stoneValue = Double.parseDouble(sValue);
            }

            int karat = getSelectedKaratNumber();
            double[] pricing = PricingCalculator.calculatePricingDetails(
                    grossWeight, stoneWeightCarats, stoneValue, karat, priceManager);
            double totalPrice = pricing[5];

            String formattedPrice = priceManager.formatPriceDefault(totalPrice);
            binding.tvEstimatedGoldValue.setText(formattedPrice);
            binding.tvSummaryPrice.setText(formattedPrice);

        } catch (NumberFormatException e) {
            resetCalculatedDisplay();
        } catch (Exception e) {
            Log.e(TAG, "calculateEstimatedGoldValue error: " + e.getMessage(), e);
        }
    }

    private void resetCalculatedDisplay() {
        try {
            if (binding == null) return;
            String zeroText = "0.00 " + priceManager.getCurrencySymbol(
                    priceManager.getDefaultCurrency());
            binding.tvEstimatedGoldValue.setText(zeroText);
            binding.tvSummaryPrice.setText(zeroText);
        } catch (Exception e) {
            Log.e(TAG, "resetCalculatedDisplay error: " + e.getMessage());
        }
    }

    private int getSelectedKaratNumber() {
        try {
            if (binding == null) return 21;
            int checkedId = binding.chipGroupKarat.getCheckedChipId();
            if (checkedId == binding.chip24K.getId()) return 24;
            if (checkedId == binding.chip21K.getId()) return 21;
            if (checkedId == binding.chip18K.getId()) return 18;
            if (checkedId == binding.chip14K.getId()) return 14;
        } catch (Exception e) {
            Log.e(TAG, "getSelectedKaratNumber error: " + e.getMessage());
        }
        return 21;
    }

    private String getSelectedKaratCode() {
        return getSelectedKaratNumber() + "K";
    }

    private String getSelectedCategory() {
        try {
            if (binding == null) return isArabic ? "مجوهرات" : "Jewelry";
            int checkedId = binding.chipGroupTypes.getCheckedChipId();
            if (checkedId == binding.chipRing.getId()) return isArabic ? "خواتم" : "Rings";
            if (checkedId == binding.chipSet.getId()) return isArabic ? "أطقم" : "Sets";
            if (checkedId == binding.chipBracelet.getId()) return isArabic ? "أساور" : "Bracelets";
            if (checkedId == binding.chipNecklace.getId()) return isArabic ? "عقود" : "Necklaces";
        } catch (Exception e) {
            Log.e(TAG, "getSelectedCategory error: " + e.getMessage());
        }
        return isArabic ? "مجوهرات" : "Jewelry";
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Listeners
    // ═══════════════════════════════════════════════════════════════════

    private void setupListeners() {
        try {
            if (binding == null) return;

            binding.etEpc.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (binding == null || binding.tvStatusBadge == null) return;

                    if (s.length() == 0) {
                        binding.tvStatusBadge.setText("WAITING");
                        binding.tvStatusBadge.setTextColor(0xFF6B7280);
                        if (isEpcBound) {
                            isEpcBound = false;
                            Log.d(TAG, "🔓 EPC unbound (field cleared)");
                        }
                    } else if (!isEpcBound) {
                        binding.tvStatusBadge.setText(
                                isArabic ? "⚠️ كتابة يدوية" : "⚠️ TYPED");
                        binding.tvStatusBadge.setTextColor(0xFFF59E0B);
                    } else {
                        binding.tvStatusBadge.setText(
                                isArabic ? "✅ موثّق" : "✅ VERIFIED");
                        binding.tvStatusBadge.setTextColor(0xFF22C55E);
                    }
                }
            });

            if (binding.tilEpc != null) {
                binding.tilEpc.setEndIconOnClickListener(v -> openBarcodeScannerForEPC());
            }

            binding.btnScanRfid.setOnClickListener(v -> showScanMethodOptions());

            binding.btnScanRfid.setOnLongClickListener(v -> {
                openEncodingPowerSettings();
                return true;
            });

            View stonesContainer = binding.getRoot().findViewById(R.id.layoutStonesContainer);
            if (stonesContainer != null) {
                stonesContainer.setOnClickListener(v -> {
                    if (isFormLocked) {
                        showToast(isArabic ? "⛔ النموذج مقفل" : "⛔ Form is locked");
                        return;
                    }
                    boolean newState = !binding.switchHasStones.isChecked();
                    binding.switchHasStones.setChecked(newState);
                    toggleStonesDetails(newState);
                    calculateEstimatedGoldValue();
                });
            }

            binding.switchHasStones.setOnCheckedChangeListener((buttonView, isChecked) -> {
                toggleStonesDetails(isChecked);
                calculateEstimatedGoldValue();
            });

            TextWatcher calculationWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    calculateEstimatedGoldValue();
                }
            };

            binding.etGrossWeight.addTextChangedListener(calculationWatcher);
            binding.etStoneWeight.addTextChangedListener(calculationWatcher);
            binding.etStoneValue.addTextChangedListener(calculationWatcher);

            binding.chipGroupKarat.setOnCheckedStateChangeListener(
                    (group, checkedIds) -> calculateEstimatedGoldValue());

            binding.chipGroupTypes.setOnCheckedStateChangeListener((group, checkedIds) -> {
                if (checkedIds.isEmpty()) return;
                int id = checkedIds.get(0);
                if (id == binding.chipRing.getId()) populateSuggestions("rings");
                else if (id == binding.chipSet.getId()) populateSuggestions("sets");
                else if (id == binding.chipBracelet.getId()) populateSuggestions("bracelets");
                else if (id == binding.chipNecklace.getId()) populateSuggestions("necklaces");
            });

            binding.btnAddPhotos.setOnClickListener(v -> {
                if (isFormLocked) {
                    showToast(isArabic ? "⛔ النموذج مقفل" : "⛔ Form is locked");
                    return;
                }
                if (photoManager != null && photoManager.getPhotoCount() >= MAX_PHOTOS) {
                    showToast(isArabic
                            ? "⚠️ الحد الأقصى " + MAX_PHOTOS + " صور"
                            : "⚠️ Maximum " + MAX_PHOTOS + " photos");
                    return;
                }
                if (permissionManager.canEdit("identity") || permissionManager.isManager()) {
                    showImageSourceDialog();
                } else {
                    showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
                }
            });

            binding.btnSaveIdentity.setOnClickListener(v -> {
                if (isFormLocked) {
                    showToast(isArabic
                            ? "⛔ العملية قيد التنفيذ، يرجى الانتظار..."
                            : "⛔ Operation in progress...");
                    return;
                }
                if (permissionManager.canEdit("identity") || permissionManager.isManager()) {
                    finalizeItemIdentity();
                } else {
                    showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
                }
            });

            binding.btnViewPending.setOnClickListener(v -> openSyncLogFragment());

        } catch (Exception e) {
            Log.e(TAG, "setupListeners error: " + e.getMessage(), e);
        }
    }

    private void showScanMethodOptions() {
        if (isFormLocked) {
            showToast(isArabic ? "⛔ النموذج مقفل" : "⛔ Form is locked");
            return;
        }

        String[] options = {
                isArabic ? "📡 مسح بالقارئ اليدوي (الزناد)" : "📡 Scan with Handheld Reader",
                isArabic ? "🖥️ مسح بالقارئ المكتبي (USB)" : "🖥️ Scan with Desktop Reader"
        };

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "اختر طريقة المسح" : "Choose Scan Method")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showToast(isArabic
                                ? "🎯 اضغط زر الزناد في القارئ اليدوي الآن"
                                : "🎯 Press the handheld reader trigger now");
                    } else {
                        activateDesktopScannerMode();
                    }
                })
                .show();
    }

    private void toggleStonesDetails(boolean show) {
        if (binding == null || binding.layoutStonesDetails == null) return;

        if (show) {
            binding.layoutStonesDetails.setVisibility(View.VISIBLE);
            binding.layoutStonesDetails.setAlpha(0f);
            binding.layoutStonesDetails.setTranslationY(-10f);
            binding.layoutStonesDetails.animate()
                    .alpha(1f).translationY(0f).setDuration(250).start();
        } else {
            binding.layoutStonesDetails.animate()
                    .alpha(0f).translationY(-10f).setDuration(200)
                    .withEndAction(() -> {
                        if (binding != null && binding.layoutStonesDetails != null) {
                            binding.layoutStonesDetails.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Suggestions
    // ═══════════════════════════════════════════════════════════════════

    private void populateSuggestions(String typeKey) {
        try {
            if (binding == null) return;
            binding.chipGroupSuggestions.removeAllViews();
            String[] suggestions = getSuggestions(typeKey);
            if (suggestions == null || getContext() == null) return;
            for (String suggestion : suggestions) {
                Chip chip = new Chip(requireContext());
                chip.setText(suggestion);
                chip.setClickable(true);
                chip.setCheckable(false);
                chip.setOnClickListener(v -> {
                    if (binding != null) binding.etTitle.setText(suggestion);
                });
                binding.chipGroupSuggestions.addView(chip);
            }
        } catch (Exception e) {
            Log.e(TAG, "populateSuggestions error: " + e.getMessage(), e);
        }
    }

    private String[] getSuggestions(String typeKey) {
        if (isArabic) {
            switch (typeKey) {
                case "rings": return new String[]{
                        "خاتم سوليتير ألماسي", "خاتم محبس كلاسيك",
                        "خاتم توينز راقي", "خاتم خطوبة مميز"};
                case "sets": return new String[]{
                        "طقم شبكة عروس كامل", "طقم ملكي مرصع بالألماس",
                        "طقم سهرة فاخر"};
                case "bracelets": return new String[]{
                        "بناجر / أساور خليجي", "سوار تنادي عصري",
                        "سوار ذهبي كلاسيك"};
                case "necklaces": return new String[]{
                        "عقد شوكر عصري", "سلسلة ذهبية ناعمة", "عقد لؤلؤ فاخر"};
            }
        } else {
            switch (typeKey) {
                case "rings": return new String[]{
                        "Diamond Solitaire Ring", "Classic Band Ring",
                        "Twins Ring", "Engagement Ring"};
                case "sets": return new String[]{
                        "Complete Bridal Set", "Royal Diamond Set",
                        "Luxury Evening Set"};
                case "bracelets": return new String[]{
                        "Khaleeji Bangle", "Modern Tennis Bracelet",
                        "Classic Gold Bracelet"};
                case "necklaces": return new String[]{
                        "Modern Choker", "Fine Gold Chain", "Luxury Pearl Necklace"};
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Photos source + Camera + Gallery
    // ═══════════════════════════════════════════════════════════════════

    private void showImageSourceDialog() {
        if (getContext() == null || !isAdded()) return;
        if (!(permissionManager.canEdit("identity") || permissionManager.isManager())) {
            showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            return;
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setTitle(isArabic ? "إضافة صورة" : "Add Photo");
        String[] options = {
                isArabic ? "📷 التقاط صورة" : "📷 Take Photo",
                isArabic ? "🖼️ اختيار من الاستوديو" : "🖼️ Choose from Gallery"
        };
        builder.setItems(options, (dialog, which) -> {
            if (which == 0) checkCameraPermissionAndTakePhoto();
            else openGallery();
        });
        builder.show();
    }

    private void checkCameraPermissionAndTakePhoto() {
        if (getContext() == null) return;
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void openCamera() {
        try {
            if (getContext() == null) return;
            Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (takePictureIntent.resolveActivity(getContext().getPackageManager()) != null) {
                File photoFile = createImageFile();
                if (photoFile != null) {
                    photoUri = FileProvider.getUriForFile(
                            getContext(),
                            getContext().getApplicationContext().getPackageName() + ".fileprovider",
                            photoFile);
                    takePhotoLauncher.launch(photoUri);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "openCamera error: " + e.getMessage(), e);
        }
    }

    private void openGallery() {
        try {
            pickImageLauncher.launch(new String[]{"image/*"});
        } catch (Exception e) {
            Log.e(TAG, "openGallery error: " + e.getMessage(), e);
        }
    }

    private File createImageFile() throws IOException {
        if (getContext() == null) return null;
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File storageDir = getContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile("MIRA_" + timeStamp + "_", ".jpg", storageDir);
        currentPhotoPath = image.getAbsolutePath();
        return image;
    }

    private void activateDesktopScannerMode() {
        if (binding == null || binding.etEpc == null) return;

        binding.etEpc.setHint(isArabic
                ? "قرب الشريحة من القارئ المكتبي..."
                : "Approach tag to Desktop Reader...");
        binding.etEpc.requestFocus();

        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(binding.etEpc.getWindowToken(), 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to hide keyboard", e);
        }

        if (desktopScanHandler == null) {
            desktopScanHandler = new DesktopScanHandler(binding.etEpc);
        }

        desktopScanHandler.setOnDesktopScanListener(epc -> {
            Log.i(TAG, "🖥️ Desktop Reader Scanned: " + maskEpc(epc));
            processReceivedTagWithTid(epc, null, "DESKTOP_RFID");
        });

        showToast(isArabic ? "🖥️ جاهز للمسح بالقارئ المكتبي" : "🖥️ Desktop Reader Ready");
    }

    private void stopDesktopScannerMode() {
        if (binding != null && binding.etEpc != null) {
            binding.etEpc.setHint(isArabic ? "رمز الشريحة (EPC / GTIN)..." : "EPC / GTIN Code...");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Barcode Scanner (for EPC)
    // ═══════════════════════════════════════════════════════════════════

    private void openBarcodeScannerForEPC() {
        try {
            if (!isAdded() || getActivity() == null) return;

            FragmentManager fm = getParentFragmentManager();
            if (fm.isStateSaved()) {
                Log.w(TAG, "State saved, cannot open scanner");
                return;
            }
            if (fm.findFragmentByTag("barcode_scanner_tag") != null) {
                Log.w(TAG, "Scanner already open");
                return;
            }

            MiraBarcodeScannerFragment sf = new MiraBarcodeScannerFragment();
            sf.setOnBarcodeScannedListener((data, format) -> {
                if (data != null && !data.trim().isEmpty()) {
                    pendingBarcodeFromScanner = data.trim().toUpperCase(Locale.US);
                }
                try {
                    if (fm.getBackStackEntryCount() > 0) fm.popBackStack();
                } catch (Exception e) {
                    Log.e(TAG, "popBackStack error", e);
                }
            });

            View view = getView();
            int cid = (view != null && view.getParent() instanceof View)
                    ? ((View) view.getParent()).getId()
                    : android.R.id.content;

            fm.beginTransaction()
                    .setReorderingAllowed(true)
                    .setCustomAnimations(
                            android.R.anim.fade_in, android.R.anim.fade_out,
                            android.R.anim.fade_in, android.R.anim.fade_out)
                    .hide(this)
                    .add(cid, sf, "barcode_scanner_tag")
                    .addToBackStack("barcode_scanner_encode")
                    .commit();

        } catch (Exception e) {
            Log.e(TAG, "Barcode scanner error: " + e.getMessage(), e);
            showToast(isArabic ? "❌ خطأ في فتح الماسح" : "❌ Scanner error");
        }
    }

    private void processPendingBarcode() {
        final String scannedCode = pendingBarcodeFromScanner;
        if (scannedCode == null || scannedCode.isEmpty()) return;
        pendingBarcodeFromScanner = null;

        final String extractedIdentifier = extractIdentifierFromBarcode(scannedCode);

        if (extractedIdentifier.isEmpty()
                || extractedIdentifier.length() < 2
                || extractedIdentifier.length() > 128) {
            playErrorSound();
            showScanFeedback(
                    isArabic ? "❌ باركود غير صالح" : "❌ Invalid Barcode",
                    isArabic ? "الطول: " + extractedIdentifier.length() + " حرف"
                             : "Length: " + extractedIdentifier.length() + " chars",
                    null, android.R.drawable.ic_dialog_alert, 0xFFDC2626);
            showErrorDialog(
                    isArabic ? "❌ باركود غير صالح" : "❌ Invalid Barcode",
                    isArabic ? "طول الباركود غير منطقي أو غير معروف"
                             : "Barcode length unreasonable or unknown");
            return;
        }

        if (miraExecutor == null) return;
        miraExecutor.getBackgroundExecutor().execute(() -> {
            final boolean isDuplicate = syncManager != null
                    && syncManager.isEpcDuplicateLocally(extractedIdentifier);

            safeRun(() -> {
                if (binding == null || !isAdded()) return;

                if (isDuplicate) {
                    playErrorSound();
                    showScanFeedback(
                            isArabic ? "⚠️ باركود مكرر" : "⚠️ Duplicate Barcode",
                            isArabic ? extractedIdentifier + "\nمسجل بالفعل"
                                     : extractedIdentifier + "\nAlready registered",
                            null, android.R.drawable.ic_dialog_alert, 0xFF3B82F6);
                    showErrorDialog(
                            isArabic ? "باركود مكرر" : "Duplicate Barcode",
                            isArabic ? "هذا الكود مسجل بالفعل:\n" + extractedIdentifier
                                     : "This code is already registered:\n" + extractedIdentifier);
                    return;
                }

                if (binding.etEpc != null) {
                    binding.etEpc.setText(extractedIdentifier);
                    binding.etEpc.setTag(extractedIdentifier);
                }
                if (binding.tvStatusBadge != null) {
                    binding.tvStatusBadge.setText(
                            isArabic ? "⚠️ باركود (للبحث فقط)"
                                     : "⚠️ Barcode (lookup only)");
                    binding.tvStatusBadge.setTextColor(0xFFF59E0B);
                }

                isEpcBound = false;

                showScanFeedback(
                        isArabic ? "🔍 باركود للبحث فقط" : "🔍 Barcode (Lookup)",
                        isArabic
                                ? "الباركود يحدد القطعة، لكن يجب مسح شريحة RFID للتكويد"
                                : "Barcode identifies item, but RFID scan is required to encode",
                        null, android.R.drawable.ic_dialog_info, 0xFF3B82F6);

                showErrorDialog(
                        isArabic ? "⚠️ الباركود لا يكفي" : "⚠️ Barcode is not sufficient",
                        isArabic
                                ? "لتكويد القطعة:\n\n" +
                                  "1. استخدم زر مسح RFID\n" +
                                  "2. قرّب الشريحة من القارئ\n" +
                                  "3. اضغط الزناد"
                                : "To encode:\n\n" +
                                  "1. Use RFID scan button\n" +
                                  "2. Move tag near reader\n" +
                                  "3. Press trigger");

                Log.i(TAG, "🔍 Barcode accepted for lookup only: " + extractedIdentifier);
            });
        });
    }

    private String extractIdentifierFromBarcode(String rawBarcode) {
        if (rawBarcode == null || rawBarcode.isEmpty()) return "";
        String clean = rawBarcode.trim();

        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            try {
                android.net.Uri uri = android.net.Uri.parse(clean);
                String lastSegment = uri.getLastPathSegment();
                if (lastSegment != null && lastSegment.matches("^MIRA-SN-\\d+$")) {
                    return lastSegment;
                }
                String serial = uri.getQueryParameter("serial");
                if (serial == null) serial = uri.getQueryParameter("sn");
                if (serial != null && serial.matches("^MIRA-SN-\\d+$")) {
                    return serial;
                }
                return "";
            } catch (Exception ignored) {
                return "";
            }
        }

        String gs1 = clean.replace('\u001D', '|').replace("(#)", "|");
        java.util.regex.Matcher mSerial = java.util.regex.Pattern
                .compile("(?:^|\\|)21(MIRA-SN-\\d+)")
                .matcher(gs1);
        if (mSerial.find()) {
            String serial = mSerial.group(1);
            if (serial != null && serial.matches("^MIRA-SN-\\d+$")) {
                return serial;
            }
        }

        if (clean.matches("^MIRA-SN-\\d+$")) {
            return clean;
        }

        Log.w(TAG, "[SECURITY] Rejected barcode format: "
                + (clean.length() > 20 ? clean.substring(0, 20) + "..." : clean));
        return "";
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Save & Encode — finalizeItemIdentity
    // ═══════════════════════════════════════════════════════════════════

    private void finalizeItemIdentity() {
        if (isFormLocked) {
            showToast(isArabic ? "⛔ العملية قيد التنفيذ..." : "⛔ Operation in progress...");
            return;
        }

        if (!(permissionManager.canEdit("identity") || permissionManager.isManager())) {
            showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            return;
        }

        isFormLocked = true;
        if (binding != null && binding.btnSaveIdentity != null) {
            binding.btnSaveIdentity.setEnabled(false);
            binding.btnSaveIdentity.setAlpha(0.5f);
        }

        try {
            if (binding == null || appContext == null) {
                unlockFormFast();
                return;
            }

            String identifier = binding.etEpc.getText() != null
                    ? binding.etEpc.getText().toString().trim() : "";
            String title = binding.etTitle.getText() != null
                    ? binding.etTitle.getText().toString().trim() : "";
            String weightStr = binding.etGrossWeight.getText() != null
                    ? binding.etGrossWeight.getText().toString().trim() : "";

            if (!validateInputData(identifier, title, weightStr)) {
                unlockFormFast();
                return;
            }

            double grossWeight = Double.parseDouble(weightStr);
            String stoneType = "";
            double stoneWeightCarats = 0;
            int stoneCount = 0;
            double stoneValue = 0;

            if (binding.switchHasStones.isChecked()) {
                stoneType = binding.etStoneType.getText() != null
                        ? binding.etStoneType.getText().toString().trim() : "";
                String stoneWeightStr = binding.etStoneWeight.getText() != null
                        ? binding.etStoneWeight.getText().toString().trim() : "";
                String stoneCountStr = binding.etStoneCount.getText() != null
                        ? binding.etStoneCount.getText().toString().trim() : "";
                String stoneValueStr = binding.etStoneValue.getText() != null
                        ? binding.etStoneValue.getText().toString().trim() : "";

                if (!stoneValueStr.isEmpty()) stoneValue = Double.parseDouble(stoneValueStr);
                if (!stoneWeightStr.isEmpty()) stoneWeightCarats = Double.parseDouble(stoneWeightStr);
                if (!stoneCountStr.isEmpty()) stoneCount = Integer.parseInt(stoneCountStr);
            }

            double stoneWeightGrams = PricingCalculator.caratsToGrams(stoneWeightCarats);
            if (stoneWeightGrams >= grossWeight) {
                showValidationError(isArabic
                        ? "❌ وزن الأحجار لا يمكن أن يتجاوز أو يساوي الوزن الإجمالي!"
                        : "❌ Stone weight cannot exceed gross weight!");
                unlockFormFast();
                return;
            }

            int selectedKaratNum = getSelectedKaratNumber();
            double[] pricing = PricingCalculator.calculatePricingDetails(
                    grossWeight, stoneWeightCarats, stoneValue, selectedKaratNum, priceManager);
            final double netGoldWeight = pricing[6];

            MiraPriceManager.PricingDetails details = priceManager.calculatePricingDetails(
                    selectedKaratNum, netGoldWeight);

            final String localCurrency = priceManager.getDefaultCurrency();
            final double finalPriceUSD = details.finalPrice + stoneValue;
            final double finalPriceLocal = priceManager.convertFromUSD(finalPriceUSD, localCurrency);
            final double exchangeRate = priceManager.getExchangeRate(localCurrency);

            setFormEnabled(false);

            dismissDialog(activeLoadingDialog);
            activeLoadingDialog = showProfessionalLoadingDialog(
                    isArabic ? "🔄 جاري التكويد وإنشاء الأكواد المجهرية..."
                             : "🔄 Encoding & Generating Micro-Codes...",
                    isArabic ? "يرجى الانتظار - يتم الآن تسجيل القطعة في قاعدة البيانات"
                             : "Please wait - saving item in database");

            final String finalIdentifier = identifier;
            final String finalTitle = title;
            final double fGrossWeight = grossWeight;
            final double fStoneWeightCarats = stoneWeightCarats;
            final int fStoneCount = stoneCount;
            final double fStoneValue = stoneValue;
            final String fStoneType = stoneType;
            final String selectedKarat = getSelectedKaratCode();
            final String selectedCategory = getSelectedCategory();
            final String location = isArabic ? "المعرض الرئيسي" : "Main Showroom";
            final int branchId = (branchesManager != null) ? branchesManager.getActiveBranchId() : 1;
            final List<String> finalPhotoPaths = new ArrayList<>(
                    photoManager != null ? photoManager.getPhotoPaths() : new ArrayList<>());
            final int currentUserId = getCurrentUserId();

            if (currentUserId <= 0) {
                dismissDialog(activeLoadingDialog);
                activeLoadingDialog = null;
                unlockFormFast();
                setFormEnabled(true);
                showErrorDialog(
                        isArabic ? "❌ جلسة منتهية" : "❌ Session Expired",
                        isArabic ? "يرجى تسجيل الدخول مجدداً" : "Please log in again");
                return;
            }

            miraExecutor.getBackgroundExecutor().execute(() -> {
                String tidFromTagWorking = "";
                String epcFromTagWorking = "";
                boolean isSim = false;
                final boolean isDesktopScan = "DESKTOP_RFID".equals(lastScanSource);

                try {
                    MiraRfidService rfidSvc = MiraRfidService.getInstance(appContext);

                    if (isDesktopScan) {
                        tidFromTagWorking = "";
                        epcFromTagWorking = lastScannedEpc;
                        isSim = false;
                        Log.i(TAG, "🖥️ Desktop scan — no TID");
                    } else {
                        tidFromTagWorking = rfidSvc.readTid(null);
                        epcFromTagWorking = lastScannedEpc;
                        if (epcFromTagWorking == null || epcFromTagWorking.isEmpty()) {
                            epcFromTagWorking = rfidSvc.readEpc(null);
                        }
                        isSim = rfidService_isSimulation(rfidSvc);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Read TID/EPC error", e);
                }

                if (!isDesktopScan) {
                    if (tidFromTagWorking == null || tidFromTagWorking.trim().isEmpty()) {
                        safeRun(() -> {
                            dismissDialog(activeLoadingDialog);
                            activeLoadingDialog = null;
                            unlockFormFast();
                            setFormEnabled(true);
                            showErrorDialog(
                                    isArabic ? "❌ فشل التكويد" : "❌ Encoding Failed",
                                    isArabic
                                            ? "لم نتمكن من قراءة TID الشريحة.\n\n💡 أعد المسح."
                                            : "Cannot read tag TID.\n\n💡 Rescan the tag.");
                        });
                        return;
                    }
                }
                if (epcFromTagWorking == null) epcFromTagWorking = "";

                final String tid = tidFromTagWorking.trim().toUpperCase(Locale.US);
                final String originalEpc = epcFromTagWorking.trim().toUpperCase(Locale.US);
                final String gtin = "";
                final boolean fIsSim = isSim;

                Log.i(TAG, "🔒 [SECURITY] Verifying tag with server...");
                String verifyError = verifyTagWithServerBeforeEncodingSync(
                        tid, originalEpc, lastScanSource);

                if (verifyError != null) {
                    Log.e(TAG, "❌ [SECURITY] Tag rejected: " + verifyError);
                    safeRun(() -> {
                        dismissDialog(activeLoadingDialog);
                        activeLoadingDialog = null;
                        unlockFormFast();
                        setFormEnabled(true);
                        playErrorSound();
                        showErrorDialog(
                                isArabic ? "⛔ الشريحة مرفوضة" : "⛔ Tag Rejected",
                                verifyError);
                    });
                    return;
                }

                Log.i(TAG, "✅ [SECURITY] Tag verified, proceeding with encoding...");

                try {
                    JSONObject payload = buildPayload(
                            tid, finalTitle, selectedKarat, fGrossWeight, netGoldWeight,
                            fStoneWeightCarats, fStoneCount, fStoneValue, fStoneType,
                            details, selectedKaratNum, finalPriceUSD, finalPriceLocal,
                            localCurrency, exchangeRate, selectedCategory, location,
                            branchId, currentUserId, tid, originalEpc,
                            fIsSim, finalPhotoPaths, gtin);

                    boolean apiSuccess = false;
                    int serverItemId = 0;
                    String errorMessage = null;
                    boolean isNetworkError = false;
                    JSONObject responseDataObj = null;

                    if (apiClient != null) {
                        try {
                            MiraApiClient.ApiResponse response = apiClient.encodeItem(payload);
                            if (response != null) {
                                apiSuccess = response.isSuccess;
                                responseDataObj = response.data;
                                if (response.isSuccess && response.data != null) {
                                    serverItemId = response.data.optInt("item_id", 0);
                                } else if (!response.isSuccess) {
                                    errorMessage = extractErrorMessage(response);
                                    isNetworkError = response.isNetworkError;
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "API call exception", e);
                            errorMessage = e.getMessage();
                            isNetworkError = true;
                        }
                    }

                    final boolean success = apiSuccess;
                    final int finalServerItemId = serverItemId;
                    final String finalErrorMessage = errorMessage;
                    final boolean networkError = isNetworkError;
                    final JSONObject finalResponseData = responseDataObj;

                    if (!success) {
                        safeRun(() -> {
                            dismissDialog(activeLoadingDialog);
                            activeLoadingDialog = null;
                            if (networkError) {
                                miraExecutor.getSingleThreadExecutor().execute(() -> {
                                    syncManager.saveEncodeOffline(payload);
                                    safeRun(this::updatePendingSyncBadge);
                                });
                                showToast(isArabic ? "💾 تم الحفظ محلياً (أوفلاين)"
                                                   : "💾 Saved locally (Offline)");
                                scheduleAutoSync();
                                unlockFormFast();
                                setFormEnabled(true);
                            } else {
                                String errorMsg = finalErrorMessage != null
                                        ? finalErrorMessage
                                        : (isArabic ? "❌ فشل التكويد" : "❌ Encoding failed");
                                showErrorDialog(
                                        isArabic ? "خطأ في التكويد" : "Encoding Error",
                                        errorMsg);
                                unlockFormFast();
                                setFormEnabled(true);
                            }
                        });
                        return;
                    }

                    MiraRfidService rfidService = MiraRfidService.getInstance(appContext);

                    String miraIdWorking = (finalResponseData != null)
                            ? finalResponseData.optString("mira_id",
                                    finalResponseData.optString("serial_number", finalIdentifier))
                            : finalIdentifier;
                    if (miraIdWorking == null || miraIdWorking.trim().isEmpty()) {
                        miraIdWorking = finalIdentifier;
                    }
                    final String miraId = miraIdWorking;

                    String serverEpc = (finalResponseData != null)
                            ? finalResponseData.optString("epc", "") : "";
                    String existingEpc = (!serverEpc.isEmpty()) ? serverEpc : lastScannedEpc;
                    if (existingEpc == null) existingEpc = "";
                    existingEpc = existingEpc.trim().toUpperCase(Locale.US);

                    boolean hasValidEpc = existingEpc.matches("^E2[0-9A-F]{22}$")
                            && !existingEpc.startsWith("3008")
                            && !existingEpc.equals("300800000000000000000000");

                    final String finalNewEpc;
                    boolean writeOk;
                    boolean wasWritten = false;

                    if (hasValidEpc) {
                        finalNewEpc = existingEpc;
                        writeOk = true;
                        wasWritten = false;
                    } else {
                        finalNewEpc = (finalResponseData != null)
                                ? finalResponseData.optString("tid", tid) : tid;
                        writeOk = true;
                        wasWritten = false;
                    }

                    boolean verifyOk = true;
                    if (wasWritten) {
                        String readBackEpcWorking = null;
                        if (writeOk) {
                            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                            try {
                                readBackEpcWorking = rfidService.readEpc(null);
                            } catch (Exception e) {
                                Log.e(TAG, "readEpc verification error (non-fatal)", e);
                            }
                            if (readBackEpcWorking != null) {
                                readBackEpcWorking = readBackEpcWorking.trim().toUpperCase(Locale.US);
                            }
                        }
                        final String readBackEpc = readBackEpcWorking;
                        verifyOk = writeOk && readBackEpc != null
                                && !readBackEpc.isEmpty()
                                && readBackEpc.equals(finalNewEpc);

                        if (!verifyOk) {
                            safeRun(() -> {
                                dismissDialog(activeLoadingDialog);
                                activeLoadingDialog = null;
                                unlockFormFast();
                                setFormEnabled(true);
                                showErrorDialog(
                                        isArabic ? "❌ فشل التحقق من الكتابة"
                                                 : "❌ Write Verification Failed",
                                        isArabic ? "لم يتم كتابة EPC بنجاح على الشريحة."
                                                 : "Failed to write EPC on tag.");
                            });
                            return;
                        }
                    }

                    boolean lockOk = false;
                    if (wasWritten) {
                        try {
                            lockOk = rfidService.setTagLockState(null, 1, true);
                        } catch (Exception e) {
                            Log.e(TAG, "setTagLockState error (non-fatal)", e);
                        }
                    }

                    try {
                        rfidService.markTagProgrammed(tid, miraId, finalNewEpc);
                    } catch (Exception e) {
                        Log.w(TAG, "markTagProgrammed error (non-fatal)", e);
                    }

                    final boolean finalWrite = writeOk;
                    final boolean finalVerify = verifyOk;
                    final boolean finalLock = lockOk;
                    final boolean finalWasPreEncoded = !wasWritten;

                    try {
                        JSONObject updatePayload = new JSONObject();
                        updatePayload.put("item_id", finalServerItemId);
                        if (finalNewEpc != null && finalNewEpc.matches("^[0-9A-F]{24}$")) {
                            updatePayload.put("rfid_epc_final", finalNewEpc);
                        }
                        if (tid != null && !tid.isEmpty()) {
                            updatePayload.put("rfid_tid", tid);
                        }
                        updatePayload.put("rfid_locked", finalLock);
                        updatePayload.put("rfid_verified", finalVerify);
                        updatePayload.put("rfid_pre_encoded", finalWasPreEncoded);

                        apiClient.postWithAuth("/items/update-rfid", updatePayload);
                    } catch (Exception e) {
                        Log.w(TAG, "update-rfid failed (non-fatal)", e);
                    }

                    final String finalServerEpc = serverEpc;
                    final String finalServerSerial = (finalResponseData != null)
                            ? finalResponseData.optString("serial_number", "") : "";

                    safeRun(() -> {
                        dismissDialog(activeLoadingDialog);
                        activeLoadingDialog = null;
                        playSuccessSound();

                        miraExecutor.getSingleThreadExecutor().execute(
                                () -> syncManager.removePendingEncode(finalIdentifier));
                        if (formManager != null) formManager.clearDraft();

                        notifyDashboardUpdate("encode_success");

                        if (!finalPhotoPaths.isEmpty() && finalServerItemId > 0) {
                            uploadItemImages(finalServerItemId, finalPhotoPaths);
                        }

                        String displayEpc = finalServerEpc;
                        if (displayEpc.isEmpty()) displayEpc = finalNewEpc;

                        String shortBarcode = finalServerSerial;
                        if (shortBarcode.isEmpty()) shortBarcode = finalIdentifier;

                        String gs1Code = "(21)" + shortBarcode;
                        String qrUrl = "https://sa.mirabarcode.com/" + shortBarcode;

                        if (finalResponseData != null) {
                            JSONObject gs1Obj = finalResponseData.optJSONObject("gs1");
                            if (gs1Obj != null) {
                                String sGs1 = gs1Obj.optString("raw_fnc1", "");
                                if (!sGs1.isEmpty()) gs1Code = sGs1;
                                String sUrl = gs1Obj.optString("digital_link", "");
                                if (!sUrl.isEmpty()) qrUrl = sUrl;
                            }
                        }

                        if (binding != null && binding.etEpc != null) {
                            binding.etEpc.setText("");
                        }

                        if (finalWasPreEncoded) {
                            showToast(isArabic
                                    ? "✅ تم استخدام EPC الموجود بنجاح"
                                    : "✅ Existing EPC used successfully");
                        } else if (finalWrite && finalVerify && finalLock) {
                            showToast(isArabic
                                    ? "🔒 تم التكويد والقفل بنجاح"
                                    : "🔒 Encoded & locked successfully");
                        } else if (finalWrite && finalVerify && !finalLock) {
                            showToast(isArabic
                                    ? "⚠️ تم التكويد لكن القفل فشل"
                                    : "⚠️ Encoded but lock failed");
                        }

                        showEncodingSuccessDialogEnhanced(
                                finalTitle,
                                displayEpc,
                                shortBarcode,
                                tid,
                                gs1Code,
                                qrUrl,
                                String.valueOf(fGrossWeight),
                                selectedKarat,
                                finalPriceUSD,
                                localCurrency,
                                finalPhotoPaths,
                                fStoneType,
                                fStoneWeightCarats,
                                fStoneCount,
                                fStoneValue,
                                netGoldWeight);
                    });

                } catch (Exception e) {
                    Log.e(TAG, "Background execution error", e);
                    safeRun(() -> {
                        dismissDialog(activeLoadingDialog);
                        activeLoadingDialog = null;
                        unlockFormFast();
                        setFormEnabled(true);
                        showErrorDialog(
                                isArabic ? "خطأ للنظام" : "System Error",
                                e.getMessage() != null ? e.getMessage() : "Unknown");
                    });
                }
            });

        } catch (NumberFormatException e) {
            showValidationError(isArabic ? "❌ خطأ في الأرقام المدخلة" : "❌ Invalid number format");
            unlockFormFast();
        } catch (Exception e) {
            Log.e(TAG, "finalizeItemIdentity error: " + e.getMessage(), e);
            unlockFormFast();
            setFormEnabled(true);
            showToast("❌ " + e.getMessage());
        }
    }

    private boolean rfidService_isSimulation(MiraRfidService svc) {
        try {
            return svc.isSimulationMode();
        } catch (Exception e) {
            Log.w(TAG, "isSimulationMode check failed", e);
            return false;
        }
    }

    private void unlockFormFast() {
        isFormLocked = false;
        if (binding != null && binding.btnSaveIdentity != null) {
            boolean canEdit = permissionManager.canEdit("identity")
                    || permissionManager.isManager();
            binding.btnSaveIdentity.setEnabled(canEdit);
            binding.btnSaveIdentity.setAlpha(canEdit ? 1.0f : 0.5f);
        }
    }

    private String extractErrorMessage(MiraApiClient.ApiResponse response) {
        if (response == null) return isArabic ? "❌ خطأ غير معروف" : "❌ Unknown error";
        if (response.data != null) {
            try {
                JSONObject errorObj = response.data.optJSONObject("error");
                if (errorObj != null) {
                    String message = errorObj.optString("message", "");
                    String messageEn = errorObj.optString("message_en", "");
                    String displayMessage = isArabic ? message : messageEn;
                    if (displayMessage.isEmpty()) {
                        displayMessage = message.isEmpty() ? messageEn : message;
                    }

                    JSONObject details = errorObj.optJSONObject("details");
                    if (details != null) {
                        String suggestion = details.optString("suggestion", "");
                        if (!suggestion.isEmpty()) {
                            displayMessage += "\n\n💡 " + suggestion;
                        }
                    }
                    return displayMessage;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error extracting error message", e);
            }
        }
        if (response.message != null && !response.message.isEmpty()) return response.message;
        if (response.error != null && !response.error.isEmpty()) return response.error;
        return isArabic ? "❌ فشل العملية" : "❌ Operation failed";
    }

    private boolean validateInputData(String epc, String title, String weightStr) {
        if (epc == null || epc.trim().isEmpty()) {
            showValidationError(isArabic
                    ? "❌ يجب مسح شريحة RFID قبل الحفظ"
                    : "❌ Must scan RFID tag before saving");
            return false;
        }

        if (!isEpcBound) {
            showValidationError(isArabic
                    ? "❌ لم يتم التحقق من الشريحة عبر RFID\nاستخدم زر المسح"
                    : "❌ Tag not verified via RFID\nUse the scan button");
            return false;
        }

        String epcClean = epc.trim();
        boolean validFormat = epcClean.matches("^[0-9A-F]{16,32}$")
                || epcClean.matches("^E2[0-9A-F]{22}$");

        if (!validFormat) {
            showValidationError(isArabic
                    ? "❌ صيغة الشريحة غير صالحة\nاستخدم المسح بدل الكتابة اليدوية"
                    : "❌ Invalid tag format\nUse scan instead of manual typing");
            return false;
        }

        if (title.isEmpty()) {
            showValidationError(isArabic ? "يرجى كتابة عنوان القطعة"
                                         : "Please enter item title");
            return false;
        }
        if (title.length() < 3) {
            showValidationError(isArabic ? "العنوان قصير جداً" : "Title is too short");
            return false;
        }
        if (weightStr.isEmpty()) {
            showValidationError(isArabic ? "يرجى تحديد وزن القطعة"
                                         : "Please specify item weight");
            return false;
        }

        try {
            double weight = Double.parseDouble(weightStr);
            if (weight <= 0) {
                showValidationError(isArabic ? "يجب أن يكون الوزن أكبر من صفر"
                                             : "Weight must be greater than zero");
                return false;
            }
            if (weight > 10000) {
                showValidationError(isArabic ? "الوزن كبير جداً" : "Weight is too large");
                return false;
            }
        } catch (NumberFormatException e) {
            showValidationError(isArabic ? "صيغة الوزن غير صحيحة"
                                         : "Invalid weight format");
            return false;
        }

        return true;
    }

    private JSONObject buildPayload(
            String epc, String title, String karat, double grossWeight, double netWeight,
            double stoneWeightCarats, int stoneCount, double stoneValue, String stoneType,
            MiraPriceManager.PricingDetails details, int karatNum, double priceUSD,
            double priceLocal, String currency, double exchangeRate, String category,
            String location, int branchId, int userId, String tid, String originalEpc,
            boolean isSim, List<String> photoPathsList, String gtin) throws Exception {

        JSONObject payload = new JSONObject();

        payload.put("title", title);
        payload.put("purity_code", karat);
        payload.put("category", category);
        payload.put("location", location);
        payload.put("branch_id", branchId);
        payload.put("user_id", userId);

        payload.put("gross_weight", grossWeight);
        payload.put("net_weight", netWeight);
        payload.put("stone_weight", stoneWeightCarats);
        payload.put("stone_count", stoneCount);
        payload.put("stone_value", stoneValue);
        payload.put("stone_type", stoneType);

        payload.put("gold_price_per_gram", details.gramPrice);
        payload.put("gold_value", details.baseGoldCost);
        payload.put("making_per_gram", priceManager.getMakingCharge(karatNum));
        payload.put("making_charge", details.totalMakingCost);
        payload.put("total_cost", details.baseGoldCost + details.totalMakingCost);
        payload.put("profit_margin", priceManager.getProfitMargin(karatNum));
        payload.put("profit_amount", details.profitAmount);

        payload.put("retail_price_usd", priceUSD);
        payload.put("retail_price", priceUSD);
        payload.put("retail_price_local", priceLocal);
        payload.put("currency", "USD");
        payload.put("local_currency", currency);
        payload.put("exchange_rate", exchangeRate);
        payload.put("currency_symbol", priceManager.getCurrencySymbol(currency));

        if (tid != null && !tid.isEmpty()) {
            payload.put("tid", tid);
            payload.put("rfid_tid", tid);
        }
        if (originalEpc != null && !originalEpc.isEmpty()) {
            payload.put("epc", originalEpc);
        }
        if (gtin != null && !gtin.isEmpty()) {
            payload.put("gtin", gtin);
        }

        payload.put("rfid_epc_original", "");
        payload.put("rfid_epc_final", "");
        payload.put("rfid_locked", false);
        payload.put("rfid_verified", false);

        if (!photoPathsList.isEmpty()) {
            JSONArray imagesArray = new JSONArray();
            for (String path : photoPathsList) {
                imagesArray.put(path);
            }
            payload.put("image_paths", imagesArray);
        }

        return payload;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Sync Log
    // ═══════════════════════════════════════════════════════════════════

    private void openSyncLogFragment() {
        try {
            if (!isAdded() || getActivity() == null) return;

            FragmentManager fm = getParentFragmentManager();
            if (fm.isStateSaved()) return;
            if (fm.findFragmentByTag("sync_log_tag") != null) return;

            View view = getView();
            int cid = (view != null && view.getParent() instanceof View)
                    ? ((View) view.getParent()).getId()
                    : android.R.id.content;

            SyncLogFragment syncLogFragment = new SyncLogFragment();

            fm.beginTransaction()
                    .setReorderingAllowed(true)
                    .setCustomAnimations(
                            android.R.anim.fade_in, android.R.anim.fade_out,
                            android.R.anim.fade_in, android.R.anim.fade_out)
                    .replace(cid, syncLogFragment, "sync_log_tag")
                    .addToBackStack("sync_log")
                    .commit();

            Log.i(TAG, "✅ Opened SyncLogFragment");
        } catch (Exception e) {
            Log.e(TAG, "openSyncLogFragment error: " + e.getMessage(), e);
            showToast(isArabic ? "❌ خطأ في فتح السجل" : "❌ Error opening log");
        }
    }

    private void scheduleAutoSync() {
        if (syncManager == null) return;

        Log.d(TAG, "🔄 Auto-sync scheduled (independent of lifecycle)");

        miraExecutor.getSingleThreadExecutor().execute(() -> {
            try {
                Thread.sleep(3000);
                performAutoSync();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.e(TAG, "scheduleAutoSync error: " + e.getMessage());
            }
        });
    }

    private void performAutoSync() {
        try {
            if (syncManager == null) return;

            int pendingCount = syncManager.getPendingCount();
            if (pendingCount == 0) {
                Log.d(TAG, "✅ No pending items to sync");
                return;
            }

            Log.i(TAG, "🔄 Starting auto-sync for " + pendingCount + " items");

            int branchId = (branchesManager != null) ? branchesManager.getActiveBranchId() : 1;

            miraExecutor.getNetworkExecutor().execute(() -> {
                syncManager.syncPendingEncodes(branchId,
                        new IdentitySyncManager.SyncCallback() {
                            @Override
                            public void onSyncComplete(int syncedCount) {
                                if (syncedCount > 0) {
                                    Log.i(TAG, "✅ Auto-synced " + syncedCount);
                                    safeRun(() -> {
                                        updatePendingSyncBadge();
                                        if (isAdded() && getContext() != null) {
                                            showToast(isArabic
                                                    ? "✅ تمت مزامنة " + syncedCount + " تكويد"
                                                    : "✅ Synced " + syncedCount + " encodings");
                                        }
                                        notifyDashboardUpdate("sync_complete");
                                    });
                                }
                            }

                            @Override
                            public void onSyncError(String error) {
                                Log.w(TAG, "⚠️ Auto-sync failed: " + error);
                            }
                        });
            });
        } catch (Exception e) {
            Log.e(TAG, "performAutoSync error: " + e.getMessage());
        }
    }

    private void updatePendingSyncBadge() {
        try {
            if (syncManager == null || binding == null) return;
            int count = syncManager.getPendingCount();

            if (binding.tvPendingSyncCount != null) {
                if (count > 0) {
                    binding.tvPendingSyncCount.setVisibility(View.VISIBLE);
                    binding.tvPendingSyncCount.setText(isArabic
                            ? "⏳ انتظار المزامنة: " + count
                            : "⏳ Pending Sync: " + count);
                } else {
                    binding.tvPendingSyncCount.setVisibility(View.GONE);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "updatePendingSyncBadge error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Image upload
    // ═══════════════════════════════════════════════════════════════════

    private void uploadItemImages(int itemId, List<String> imagePaths) {
        if (imagePaths == null || imagePaths.isEmpty() || itemId <= 0 || apiClient == null) return;

        miraExecutor.getImageExecutor().execute(() -> {
            int uploaded = 0;
            for (int i = 0; i < imagePaths.size(); i++) {
                String path = imagePaths.get(i);
                File imageFile = new File(path);
                if (!imageFile.exists()) continue;
                try {
                    boolean isPrimary = (i == 0);
                    MiraApiClient.ApiResponse response =
                            apiClient.uploadItemImage(itemId, imageFile, isPrimary, "Image");
                    if (response != null && response.isSuccess) uploaded++;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to upload image: " + path, e);
                }
            }

            final int fUploaded = uploaded;
            safeRun(() -> {
                if (fUploaded > 0) {
                    showToast(isArabic
                            ? "📷 تم رفع " + fUploaded + " صورة"
                            : "📷 " + fUploaded + " images uploaded");
                }
            });
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Success Dialog
    // ═══════════════════════════════════════════════════════════════════

    private void showEncodingSuccessDialogEnhanced(
            String title, String epc, String shortBarcode, String tid, String gs1Code, String qrUrl,
            String weight, String karat, double finalPriceUSD, String currency,
            List<String> photos,
            String stoneType, double stoneWeightCarats, int stoneCount,
            double stoneValue, double netWeight) {

        if (!isAdded() || getContext() == null) return;

        BottomSheetDialog successDialog = new BottomSheetDialog(requireContext());
        View view = getLayoutInflater().inflate(R.layout.dialog_mira_identity_summary, null);
        successDialog.setContentView(view);
        successDialog.setCancelable(false);
        successDialog.setCanceledOnTouchOutside(false);

        TextView tvIdTitle = view.findViewById(R.id.tvIdTitle);
        TextView tvEpcCode = view.findViewById(R.id.tvEpcCode);
        TextView tvIdMetalKarat = view.findViewById(R.id.tvIdMetalKarat);
        TextView tvIdWeight = view.findViewById(R.id.tvIdWeight);
        TextView tvIdPrice = view.findViewById(R.id.tvIdPrice);
        ImageView ivGs1DataMatrix = view.findViewById(R.id.ivGs1DataMatrix);
        ImageView ivQrCode = view.findViewById(R.id.ivQrCode);
        MaterialButton btnClose = view.findViewById(R.id.btnCloseIdentity);

        if (tvIdTitle != null) tvIdTitle.setText(title);

        if (tvEpcCode != null) {
            tvEpcCode.setText(isArabic
                    ? "الباركود: " + shortBarcode + " | EPC: " + epc
                    : "Barcode: " + shortBarcode + " | EPC: " + epc);
        }

        ImageView imgIdentityPhoto = view.findViewById(R.id.imgIdentityPhoto);
        if (imgIdentityPhoto != null && photos != null && !photos.isEmpty()) {
            String firstPhotoPath = photos.get(0);
            File imgFile = new File(firstPhotoPath);
            if (imgFile.exists()) {
                imgIdentityPhoto.setVisibility(View.VISIBLE);
                Glide.with(requireContext())
                        .load(imgFile)
                        .placeholder(R.drawable.placeholder_image)
                        .error(R.drawable.ic_error_outline)
                        .into(imgIdentityPhoto);
            } else {
                imgIdentityPhoto.setVisibility(View.GONE);
            }
        } else if (imgIdentityPhoto != null) {
            imgIdentityPhoto.setVisibility(View.GONE);
        }

        if (tvIdMetalKarat != null)
            tvIdMetalKarat.setText((isArabic ? "عيار " : "Karat ") + karat);
        if (tvIdWeight != null)
            tvIdWeight.setText(weight + (isArabic ? " جرام" : " g"));

        if (tvIdPrice != null && priceManager != null) {
            tvIdPrice.setText(priceManager.formatPriceDefault(finalPriceUSD));
        }

        TextView tvIdStonesInfo = view.findViewById(R.id.tvIdStonesInfo);
        if (tvIdStonesInfo != null) {
            if (stoneWeightCarats > 0 || stoneValue > 0 || stoneCount > 0) {
                String stonesText;
                if (isArabic) {
                    stonesText = "الأحجار: " + (stoneType.isEmpty() ? "غير محدد" : stoneType);
                    if (stoneWeightCarats > 0)
                        stonesText += " | وزن: " + stoneWeightCarats + " قيراط";
                    if (stoneCount > 0)
                        stonesText += " | عدد: " + stoneCount + " حجر";
                    if (stoneValue > 0)
                        stonesText += " | قيمة: " + priceManager.formatPriceDefault(stoneValue)
                                + " " + currency;
                } else {
                    stonesText = "Stones: " + (stoneType.isEmpty() ? "N/A" : stoneType);
                    if (stoneWeightCarats > 0) stonesText += " | Wt: " + stoneWeightCarats + " ct";
                    if (stoneCount > 0) stonesText += " | Pcs: " + stoneCount;
                    if (stoneValue > 0)
                        stonesText += " | Val: " + priceManager.formatPriceDefault(stoneValue)
                                + " " + currency;
                }
                tvIdStonesInfo.setText(stonesText);
                tvIdStonesInfo.setVisibility(View.VISIBLE);
            } else {
                tvIdStonesInfo.setVisibility(View.GONE);
            }
        }

        miraExecutor.getImageExecutor().execute(() -> {
            Bitmap dmBitmap = (gs1Code != null && !gs1Code.isEmpty())
                    ? BarcodeGenerator.generateGS1DataMatrix(gs1Code, 250, 250)
                    : null;
            Bitmap qrBitmap = (qrUrl != null && !qrUrl.isEmpty())
                    ? BarcodeGenerator.generateMicroQRCode(qrUrl, 250, 250)
                    : null;

            safeRun(() -> {
                if (ivGs1DataMatrix != null && dmBitmap != null) {
                    ivGs1DataMatrix.setImageBitmap(dmBitmap);
                    ivGs1DataMatrix.setVisibility(View.VISIBLE);
                }
                if (ivQrCode != null && qrBitmap != null) {
                    ivQrCode.setImageBitmap(qrBitmap);
                    ivQrCode.setVisibility(View.VISIBLE);
                }
            });
        });

        View printView = view.findViewById(R.id.btnPrintTag);
        if (printView instanceof MaterialButton) {
            MaterialButton btnPrintTag = (MaterialButton) printView;
            btnPrintTag.setVisibility(View.VISIBLE);
            btnPrintTag.setOnClickListener(v ->
                    miraExecutor.getImageExecutor().execute(() -> {
                        Bitmap tagToPrint = TagPrinter.generatePhysicalTagBitmap(
                                shortBarcode, gs1Code, qrUrl);
                        safeRun(() -> TagPrinter.printTagDirectly(
                                requireContext(), tagToPrint));
                    }));
        }

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                successDialog.dismiss();
                safeRun(this::resetFormForNextScan);
            });
        }

        successDialog.show();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Reset for next scan
    // ═══════════════════════════════════════════════════════════════════

    private void resetFormForNextScan() {
        isEpcBound = false;
        lastScannedEpc = "";
        isProcessingTag.set(false);
        isTriggerDown = false;

        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::resetFormForNextScan);
            return;
        }

        try {
            unlockFormFast();
            setFormEnabled(true);

            if (appContext != null) {
                MiraRfidService rfidService = MiraRfidService.getInstance(appContext);
                rfidService.resetForNewTag();
            }

            if (binding == null) return;

            binding.etEpc.setText("");
            binding.etTitle.setText("");
            binding.etGrossWeight.setText("");
            binding.etStoneType.setText("");
            binding.etStoneWeight.setText("");
            binding.etStoneCount.setText("");
            binding.etStoneValue.setText("");
            binding.tvStatusBadge.setText("WAITING");

            resetCalculatedDisplay();

            binding.switchHasStones.setChecked(false);
            binding.chipRing.setChecked(true);
            binding.chip21K.setChecked(true);

            if (currentPhotoPath != null) {
                try {
                    File f = new File(currentPhotoPath);
                    if (f.exists()) f.delete();
                } catch (Exception ignored) {}
                currentPhotoPath = null;
            }
            binding.etEpc.requestFocus();

            if (photoManager != null) {
                photoManager.clearPhotos(false);
            }
            onPhotosUpdated();

            if (formManager != null) {
                formManager.clearDraft();
            }

            Log.d(TAG, "✅ Form reset — waiting for physical trigger");

        } catch (Exception e) {
            Log.e(TAG, "resetFormForNextScan error: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Permissions & UI
    // ═══════════════════════════════════════════════════════════════════

    private void applyPermissions() {
        if (binding == null) return;
        boolean canEdit = permissionManager.canEdit("identity")
                || permissionManager.isManager();

        if (binding.btnSaveIdentity != null) {
            binding.btnSaveIdentity.setEnabled(canEdit && !isFormLocked);
            binding.btnSaveIdentity.setAlpha((canEdit && !isFormLocked) ? 1.0f : 0.5f);
        }
        if (binding.btnScanRfid != null) {
            binding.btnScanRfid.setEnabled(canEdit && !isFormLocked);
        }
        if (binding.btnAddPhotos != null) {
            binding.btnAddPhotos.setEnabled(canEdit && !isFormLocked);
        }

        if (!canEdit) {
            showToast(isArabic ? "⛔ وضع القراءة فقط - غير مصرح بالتعديل"
                               : "⛔ Read only mode - Access Denied");
        }
    }

    private void applyLanguage() {
        if (binding == null) return;
        applyLanguageDirection(binding.getRoot());
        if (isArabic) setArabicTexts(); else setEnglishTexts();
    }

    private void setArabicTexts() {
        try {
            if (binding == null) return;
            binding.tvStepTitle.setText(
                    "الخطوة 1 من 4: الربط والتصنيف وتوليد الاكواد المجهرية");
            binding.tilEpc.setHint("رمز الشريحة (EPC / GTIN)...");
            binding.btnScanRfid.setText(isRfidScanning
                    ? "إيقاف المسح..." : "مسح رمز RFID");
            binding.etTitle.setHint("عنوان ووصف القطعة");
            binding.btnSaveIdentity.setText("اعتماد الموزون وتصدير MIRA ID");
            binding.btnAddPhotos.setText("📷 إرفاق صور القطعة");
            binding.btnViewPending.setText("📦 عرض سجل المزامنة");
            updatePhotosCountText();
            calculateEstimatedGoldValue();
        } catch (Exception e) {
            Log.e(TAG, "setArabicTexts error: " + e.getMessage());
        }
    }

    private void setEnglishTexts() {
        try {
            if (binding == null) return;
            binding.tvStepTitle.setText("Step 1 of 4: Bind, Classify & Generate Codes");
            binding.tilEpc.setHint("EPC / GTIN Code...");
            binding.btnScanRfid.setText(isRfidScanning ? "Stop Scan..." : "Scan RFID Tag");
            binding.etTitle.setHint("Item Title & Description");
            binding.btnSaveIdentity.setText("Approve & Export MIRA ID");
            binding.btnAddPhotos.setText("📷 Attach Photos");
            binding.btnViewPending.setText("📦 View Sync Log");
            updatePhotosCountText();
            calculateEstimatedGoldValue();
        } catch (Exception e) {
            Log.e(TAG, "setEnglishTexts error: " + e.getMessage());
        }
    }

    private void setFormEnabled(boolean enabled) {
        try {
            if (binding == null) return;
            boolean canEdit = permissionManager.canEdit("identity")
                    || permissionManager.isManager();
            boolean finalEnabled = enabled && canEdit && !isFormLocked;

            binding.btnSaveIdentity.setEnabled(finalEnabled);
            binding.btnAddPhotos.setEnabled(finalEnabled);
            binding.etEpc.setEnabled(finalEnabled);
            binding.etTitle.setEnabled(finalEnabled);
            binding.etGrossWeight.setEnabled(finalEnabled);
            binding.switchHasStones.setEnabled(finalEnabled);
            binding.etStoneType.setEnabled(finalEnabled);
            binding.etStoneWeight.setEnabled(finalEnabled);
            binding.etStoneCount.setEnabled(finalEnabled);
            binding.etStoneValue.setEnabled(finalEnabled);
            binding.chipGroupKarat.setEnabled(finalEnabled);
            binding.chipGroupTypes.setEnabled(finalEnabled);
            binding.btnScanRfid.setEnabled(finalEnabled);

            binding.btnSaveIdentity.setAlpha(finalEnabled ? 1.0f : 0.5f);
        } catch (Exception e) {
            Log.e(TAG, "setFormEnabled error: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    private AlertDialog showProfessionalLoadingDialog(String title, String message) {
        if (getContext() == null || !isAdded()) return null;

        dismissDialog(activeLoadingDialog);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setCancelable(false);

        ProgressBar progressBar = new ProgressBar(requireContext());
        progressBar.setIndeterminate(true);
        progressBar.setPadding(40, 20, 40, 20);
        builder.setView(progressBar);

        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(false);
        dialog.show();

        activeLoadingDialog = dialog;
        return dialog;
    }

    protected void dismissDialog(AlertDialog dialog) {
        try {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        } catch (Exception e) {
            Log.e(TAG, "dismissDialog error: " + e.getMessage());
        }
    }

    private void showValidationError(String message) {
        try {
            if (getContext() == null || !isAdded()) return;
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(isArabic ? "⚠️ تنبيه الحماية" : "⚠️ Validation Alert")
                    .setMessage(message)
                    .setPositiveButton(isArabic ? "حسناً" : "OK", null)
                    .setCancelable(false)
                    .show();
        } catch (Exception e) {
            showToast(message);
        }
    }

    protected AlertDialog showErrorDialog(String title, String message) {
        if (getContext() == null || !isAdded()) return null;
        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setIcon(R.drawable.ic_error_outline)
                .setPositiveButton(isArabic ? "موافق" : "OK",
                        (dialog, which) -> dialog.dismiss())
                .setCancelable(false)
                .show();
    }

    private void playSuccessSound() {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).playSound(1);
            }
        } catch (Exception e) {
            Log.e(TAG, "playSuccessSound error: " + e.getMessage());
        }
    }

    private void playErrorSound() {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).playSound(2);
            }
        } catch (Exception e) {
            Log.e(TAG, "playErrorSound error: " + e.getMessage());
        }
    }

    private void showToast(String msg) {
        try {
            if (getContext() != null && isAdded()) {
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "showToast error: " + e.getMessage());
        }
    }

    private void secureLog(String message) {
        if (com.example.uhf.BuildConfig.DEBUG) {
            Log.d(TAG, message);
        }
    }

    private String maskEpc(String epc) {
        if (epc == null || epc.length() < 8) return "***";
        return epc.substring(0, 4) + "****" + epc.substring(epc.length() - 4);
    }

    private void notifyDashboardUpdate(String source) {
        try {
            if (!isAdded() || getContext() == null) return;

            Context ctx = getContext();
            int branchId = (branchesManager != null) ? branchesManager.getActiveBranchId() : 0;
            long timestamp = System.currentTimeMillis();

            Intent inventoryIntent = new Intent("com.example.uhf.REFRESH_INVENTORY");
            inventoryIntent.setPackage(ctx.getPackageName());
            inventoryIntent.putExtra("source", source);
            inventoryIntent.putExtra("branch_id", branchId);
            inventoryIntent.putExtra("timestamp", timestamp);
            ctx.sendBroadcast(inventoryIntent);

            Intent dashboardIntent = new Intent("com.example.uhf.REFRESH_DASHBOARD");
            dashboardIntent.setPackage(ctx.getPackageName());
            dashboardIntent.putExtra("source", source);
            dashboardIntent.putExtra("branch_id", branchId);
            dashboardIntent.putExtra("timestamp", timestamp);
            ctx.sendBroadcast(dashboardIntent);

            SharedPreferences prefs = ctx.getSharedPreferences(
                    "app_refresh_flags", Context.MODE_PRIVATE);
            prefs.edit()
                    .putLong("last_data_change", timestamp)
                    .putString("last_change_type", source)
                    .putInt("last_change_branch_id", branchId)
                    .putBoolean("needs_refresh", true)
                    .apply();

        } catch (Exception e) {
            Log.e(TAG, "notifyDashboardUpdate error: " + e.getMessage());
        }
    }

    private int getCurrentUserId() {
        try {
            if (appContext == null && getContext() != null) {
                appContext = getContext().getApplicationContext();
            }
            if (appContext == null) {
                Log.e(TAG, "getCurrentUserId: appContext null");
                return -1;
            }

            int userId = com.example.uhf.tools.SecureSessionManager
                    .getInstance(appContext).getUserId();

            if (userId > 0) {
                Log.d(TAG, "getCurrentUserId: " + userId + " (SecureSessionManager)");
                return userId;
            }

            SharedPreferences oldPrefs = appContext.getSharedPreferences(
                    "session", Context.MODE_PRIVATE);
            int legacyId = oldPrefs.getInt("user_id", -1);
            if (legacyId > 0) {
                Log.w(TAG, "getCurrentUserId: LEGACY fallback = " + legacyId);
                return legacyId;
            }

            Log.e(TAG, "getCurrentUserId: NO USER ID anywhere");
            return -1;

        } catch (Exception e) {
            Log.e(TAG, "getCurrentUserId error", e);
            return -1;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Power Management
    // ═══════════════════════════════════════════════════════════════════

    private void applyLowPowerForEncoding() {
        if (appContext == null || miraExecutor == null) return;

        miraExecutor.getSingleThreadExecutor().execute(() -> {
            try {
                com.example.uhf.MiraRfid.MiraReaderSettings settings =
                        com.example.uhf.MiraRfid.MiraReaderSettings.getInstance(appContext);

                savedPowerBeforeEncoding = settings.getPowerDbm();
                int encodingPower = settings.getPosPowerDbm();

                if (savedPowerBeforeEncoding == encodingPower) {
                    Log.d(TAG, "⚡ Encoding power already at " + encodingPower);
                    return;
                }

                settings.setPowerDbm(encodingPower);

                com.rscja.deviceapi.RFIDWithUHFUART reader =
                        com.example.uhf.activity.BaseTabFragmentActivity.getSharedReader();
                if (reader != null) {
                    try {
                        reader.setPower(encodingPower);
                        Log.i(TAG, "⚡ Encoding Low-Power: " + encodingPower
                                + " dBm (saved=" + savedPowerBeforeEncoding + ")");
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.e(TAG, "applyLowPowerForEncoding error", e);
            }
        });
    }
    
    /**
 * ✅ [v6.5] يرفع قوة القراءة أثناء المسح للسماح بتقريب الشريحة تدريجياً
 */
private void applyScanPowerForReading() {
    if (appContext == null || miraExecutor == null) return;

    miraExecutor.getSingleThreadExecutor().execute(() -> {
        try {
            com.example.uhf.MiraRfid.MiraReaderSettings settings =
                    com.example.uhf.MiraRfid.MiraReaderSettings.getInstance(appContext);

            savedPowerBeforeScan = settings.getPowerDbm();

            if (savedPowerBeforeScan == SCAN_POWER_DBM) {
                Log.d(TAG, "⚡ Scan power already at " + SCAN_POWER_DBM);
                return;
            }

            settings.setPowerDbm(SCAN_POWER_DBM);

            com.rscja.deviceapi.RFIDWithUHFUART reader =
                    com.example.uhf.activity.BaseTabFragmentActivity.getSharedReader();
            if (reader != null) {
                try {
                    reader.setPower(SCAN_POWER_DBM);
                    Log.i(TAG, "⚡ Scan power raised to " + SCAN_POWER_DBM
                            + " dBm (saved=" + savedPowerBeforeScan + ")");
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "applyScanPowerForReading error", e);
        }
    });
}

/**
 * ✅ [v6.5] يخفض القوة إلى POS لبدء الكتابة (بعد قراءة الشريحة)
 */
private void restorePowerForEncoding() {
    if (appContext == null || miraExecutor == null) return;
    if (savedPowerBeforeScan <= 0) return;

    final int target = savedPowerBeforeScan;
    savedPowerBeforeScan = -1;

    miraExecutor.getSingleThreadExecutor().execute(() -> {
        try {
            com.example.uhf.MiraRfid.MiraReaderSettings settings =
                    com.example.uhf.MiraRfid.MiraReaderSettings.getInstance(appContext);

            int encodingPower = settings.getPosPowerDbm();
            settings.setPowerDbm(encodingPower);

            com.rscja.deviceapi.RFIDWithUHFUART reader =
                    com.example.uhf.activity.BaseTabFragmentActivity.getSharedReader();
            if (reader != null) {
                try {
                    reader.setPower(encodingPower);
                    Log.i(TAG, "⚡ Power lowered to POS: " + encodingPower + " dBm");
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "restorePowerForEncoding error", e);
        }
    });
}

    private void restoreOriginalPowerEncoding() {
        if (savedPowerBeforeEncoding <= 0) return;
        if (appContext == null || miraExecutor == null) return;

        final int target = savedPowerBeforeEncoding;
        savedPowerBeforeEncoding = -1;

        miraExecutor.getSingleThreadExecutor().execute(() -> {
            try {
                com.example.uhf.MiraRfid.MiraReaderSettings settings =
                        com.example.uhf.MiraRfid.MiraReaderSettings.getInstance(appContext);
                settings.setPowerDbm(target);

                com.rscja.deviceapi.RFIDWithUHFUART reader =
                        com.example.uhf.activity.BaseTabFragmentActivity.getSharedReader();
                if (reader != null) {
                    try {
                        reader.setPower(target);
                        Log.i(TAG, "⚡ Encoding power restored: " + target);
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.e(TAG, "restoreOriginalPowerEncoding error", e);
            }
        });
    }

    private void openEncodingPowerSettings() {
        if (!isAdded() || getContext() == null) return;

        try {
            com.example.uhf.dialog.PosPowerSettingsDialog.show(
                    requireContext().getApplicationContext(),
                    isArabic,
                    newPowerDbm -> {
                        Log.i(TAG, "⚡ Encoding power set to: " + newPowerDbm);

                        if (appContext != null && miraExecutor != null) {
                            miraExecutor.getSingleThreadExecutor().execute(() -> {
                                try {
                                    com.rscja.deviceapi.RFIDWithUHFUART reader =
                                            com.example.uhf.activity
                                                    .BaseTabFragmentActivity.getSharedReader();
                                    if (reader != null) reader.setPower(newPowerDbm);
                                } catch (Exception ignored) {}
                            });
                        }

                        showToast(isArabic
                                ? "✅ تم تحديث قوة القراءة إلى " + newPowerDbm + " dBm"
                                : "✅ Power updated to " + newPowerDbm + " dBm");
                    });
        } catch (Exception e) {
            Log.e(TAG, "openEncodingPowerSettings error", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Scan Feedback Card
    // ═══════════════════════════════════════════════════════════════════

    private int dp(int value) {
        try {
            return Math.round(value * getResources().getDisplayMetrics().density);
        } catch (Exception e) {
            return value;
        }
    }

    private void initScanFeedbackCard(@NonNull View fragmentRoot) {
        try {
            if (!(fragmentRoot instanceof ViewGroup)) return;
            ViewGroup root = (ViewGroup) fragmentRoot;

            FrameLayout overlay = new FrameLayout(requireContext());
            overlay.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            overlay.setClickable(false);
            overlay.setFocusable(false);
            overlay.setVisibility(View.GONE);

            MaterialCardView card = new MaterialCardView(requireContext());
            FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(dp(16), dp(72), dp(16), 0);
            cardParams.gravity = Gravity.TOP;
            card.setLayoutParams(cardParams);
            card.setRadius(dp(16));
            card.setCardElevation(dp(8));
            card.setCardBackgroundColor(0xFFFFFFFF);
            card.setClickable(false);

            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setGravity(Gravity.CENTER_VERTICAL);

            ImageView icon = new ImageView(requireContext());
            LinearLayout.LayoutParams iconParams =
                    new LinearLayout.LayoutParams(dp(44), dp(44));
            icon.setLayoutParams(iconParams);
            row.addView(icon);

            LinearLayout textCol = new LinearLayout(requireContext());
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textParams =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textParams.setMargins(dp(14), 0, dp(14), 0);
            textCol.setLayoutParams(textParams);

            TextView title = new TextView(requireContext());
            title.setTextSize(15);
            title.setTypeface(null, Typeface.BOLD);
            title.setTextColor(0xFF1A1A1A);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(title);

            TextView details = new TextView(requireContext());
            details.setTextSize(12);
            details.setTextColor(0xFF666666);
            details.setMaxLines(3);
            details.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams detParams =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            detParams.topMargin = dp(3);
            details.setLayoutParams(detParams);
            textCol.addView(details);

            TextView price = new TextView(requireContext());
            price.setTextSize(17);
            price.setTypeface(null, Typeface.BOLD);
            price.setTextColor(0xFFB89456);
            LinearLayout.LayoutParams priceParams =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            priceParams.topMargin = dp(4);
            price.setLayoutParams(priceParams);
            textCol.addView(price);

            row.addView(textCol);

            View accent = new View(requireContext());
            LinearLayout.LayoutParams accentParams =
                    new LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT);
            accent.setLayoutParams(accentParams);
            accent.setBackgroundColor(0xFF22C55E);
            row.addView(accent);

            card.addView(row);
            overlay.addView(card);
            root.addView(overlay);

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

    private void showScanFeedback(@NonNull String title,
                                  @Nullable String details,
                                  @Nullable String price,
                                  int iconRes,
                                  int accentColor) {
        if (!isAdded() || scanFeedbackOverlay == null) return;

        try {
            if (pendingHideFeedback != null) {
                handler.removeCallbacks(pendingHideFeedback);
                pendingHideFeedback = null;
            }

            if (tvFeedbackTitle != null) tvFeedbackTitle.setText(title);

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

            final View card = scanFeedbackCard;
            final View overlay = scanFeedbackOverlay;

            overlay.setVisibility(View.VISIBLE);
            if (card != null) {
                card.setAlpha(0f);
                card.setTranslationY(-dp(20));
                card.animate()
                        .alpha(1f).translationY(0f).setDuration(250)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
            }

            pendingHideFeedback = () -> {
                if (card == null || overlay == null) return;
                card.animate()
                        .alpha(0f).translationY(-dp(20)).setDuration(200)
                        .withEndAction(() -> {
                            if (overlay != null) overlay.setVisibility(View.GONE);
                        })
                        .start();
                pendingHideFeedback = null;
            };
            handler.postDelayed(pendingHideFeedback, 3000);

        } catch (Exception e) {
            Log.e(TAG, "showScanFeedback error", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  safeRun
    // ═══════════════════════════════════════════════════════════════════

    private void safeRun(Runnable r) {
        try {
            if (isAdded() && getActivity() != null && !isRemoving() && !isDetached()) {
                getActivity().runOnUiThread(() -> {
                    if (isAdded() && !isRemoving() && !isDetached()
                            && binding != null && getView() != null) {
                        try {
                            r.run();
                        } catch (Exception e) {
                            Log.e(TAG, "safeRun inner error: " + e.getMessage(), e);
                        }
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "safeRun error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Lifecycle — onResume / onPause / onDestroyView / onSaveInstanceState
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void onResume() {
        super.onResume();

        if (!isAdded() || getActivity() == null) return;

        // ✅ [v6.5 CRITICAL] إعادة تعيين كل الحالة العالقة من جلسة سابقة
        //    هذا هو المفتاح لحل مشكلة "first press بعد login لا يعمل"
        boolean wasStuck = isProcessingTag.get();
        isProcessingTag.set(false);
        isTriggerDown = false;
        isRfidScanning = false;
        tagReceivedThisScan = false;
        scanGeneration.incrementAndGet();

        if (stoppingReader.get()) {
            stoppingReader.set(false);
            Log.w(TAG, "⚠️ Force-reset stoppingReader on resume");
        }
        if (wasStuck) {
            Log.w(TAG, "⚠️ Force-reset isProcessingTag on resume (was stuck)");
        }

        // ✅ تنظيف timeout المسح المعلق
        if (handler != null) {
            if (rfidTimeoutRunnable != null) {
                handler.removeCallbacks(rfidTimeoutRunnable);
                rfidTimeoutRunnable = null;
            }
            if (pendingHideFeedback != null) {
                handler.removeCallbacks(pendingHideFeedback);
                pendingHideFeedback = null;
            }
        }

        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setBottomNavVisible(false);
            ((UHFMainActivity) getActivity()).setActiveEncodingFragment(this);
        }

        applyLowPowerForEncoding();

        if (binding == null || getView() == null) {
            handler.postDelayed(() -> {
                if (!isAdded() || binding == null || isRemoving()) return;
                performOnResumeSetup();
            }, 100);
            return;
        }

        performOnResumeSetup();

        Log.d(TAG, "⏸️ Auto-scan disabled — use physical trigger");
    }

    private void performOnResumeSetup() {
        try {
            if (!isAdded() || binding == null) return;

            if (priceManager != null) {
                priceManager.refreshNow();
                calculateEstimatedGoldValue();
            }

            scheduleAutoSync();

            if (miraExecutor != null && miraExecutor.isHealthy()) {
                miraExecutor.getBackgroundExecutor().execute(() -> {
                    try {
                        if (formManager != null) formManager.cleanupOldDrafts();
                        if (photoManager != null) {
                            photoManager.cleanupOrphanFiles(photoManager.getPhotoPaths());
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "onResume cleanup error: " + e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "performOnResumeSetup error: " + e.getMessage(), e);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        restorePowerForEncoding();

        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setBottomNavVisible(true);
            ((UHFMainActivity) getActivity()).setActiveEncodingFragment(null);
        }

        restoreOriginalPowerEncoding();

        // ✅ إيقاف نظيف — يشمل كل الحالة
        isTriggerDown = false;
        isProcessingTag.set(false);

        if (handler != null) {
            if (rfidTimeoutRunnable != null) {
                handler.removeCallbacks(rfidTimeoutRunnable);
                rfidTimeoutRunnable = null;
            }
            if (pendingHideFeedback != null) {
                handler.removeCallbacks(pendingHideFeedback);
                pendingHideFeedback = null;
            }
        }

        stopRfidScanningMode();
        stopDesktopScannerMode();
        saveCurrentDraftAsync();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            processPendingBarcode();
        } else {
            // ✅ عند الإخفاء — أوقف كل شيء فوراً
            isTriggerDown = false;
            stopRfidScanningMode();
        }
    }

    @Override
    public void onDestroyView() {
        Log.d(TAG, "onDestroyView: cleaning up");

        // 1) إيقاف كامل
        isTriggerDown = false;
        isProcessingTag.set(false);
        stopRfidScanningMode();

        // 2) حوارات مفتوحة
        dismissDialog(activeLoadingDialog);
        activeLoadingDialog = null;

        // 3) handler
        if (handler != null) {
            if (rfidTimeoutRunnable != null) {
                handler.removeCallbacks(rfidTimeoutRunnable);
                rfidTimeoutRunnable = null;
            }
            if (pendingHideFeedback != null) {
                handler.removeCallbacks(pendingHideFeedback);
                pendingHideFeedback = null;
            }
        }

        // 4) Scan Feedback Card
        scanFeedbackOverlay = null;
        scanFeedbackCard = null;
        ivFeedbackIcon = null;
        tvFeedbackTitle = null;
        tvFeedbackDetails = null;
        tvFeedbackPrice = null;
        vFeedbackAccent = null;

        // 5) فصل النشاط
        if (getActivity() instanceof UHFMainActivity) {
            try {
                ((UHFMainActivity) getActivity()).setActiveEncodingFragment(null);
            } catch (Exception e) {
                Log.e(TAG, "Failed to clear active encoding fragment", e);
            }
        }

        // 6) RecyclerView
        if (binding != null && binding.rvPhotosPreview != null) {
            binding.rvPhotosPreview.setAdapter(null);
            binding.rvPhotosPreview.setLayoutManager(null);
            binding.rvPhotosPreview.clearOnScrollListeners();
        }
        if (photosAdapter != null) {
            photosAdapter.destroy();
            photosAdapter = null;
        }
        if (formManager != null) {
            formManager.setNotificationCallback(null);
        }

        // 7) reset
        binding = null;
        stoppingReader.set(false);

        super.onDestroyView();
    }

    @Override
public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putBoolean("is_form_locked", isFormLocked);
    // ✅ [FIX] احفظ isEpcBound + القيمة المربوطة
    outState.putBoolean("is_epc_bound", isEpcBound);
    String boundValue = null;
    if (binding != null && binding.etEpc != null) {
        Object tag = binding.etEpc.getTag();
        if (tag instanceof String) boundValue = (String) tag;
    }
    if (boundValue != null && !boundValue.isEmpty()) {
        outState.putString("epc_bound_value", boundValue);
    }
    if (currentPhotoPath != null) {
        outState.putString("camera_photo_path", currentPhotoPath);
    }
}

} // ✅ نهاية الملف
