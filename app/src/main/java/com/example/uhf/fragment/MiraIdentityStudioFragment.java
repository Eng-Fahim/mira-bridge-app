package com.example.uhf.fragment;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.utils.BarcodeGenerator;
import com.example.uhf.utils.MiraExecutorService;
import com.example.uhf.utils.PricingCalculator;
import com.example.uhf.utils.TagPrinter;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
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

/**
 * MiraIdentityStudioFragment - الإصدار 6.0 (نهائي مع كل إصلاحات التجميد)
 */
public class MiraIdentityStudioFragment extends MiraBaseFragment {

    private static final String TAG = "MiraIdentityStudio";

    // ==================== الثوابت ====================
private static final long RFID_TIMEOUT_MS = 30000;
private static final int MIN_TID_LENGTH = 12;
private static final int MAX_TID_LENGTH = 32;
private static final int MAX_PHOTOS = 5;
/** ✅ [جديد] هل EPC مربوط حالياً؟ (يمنع إعادة المسح التلقائي) */
private volatile boolean isEpcBound = false;


// ⚠️ مؤقت: تعطيل فحص طول TID لدعم شرائح ذات TID قصير (مثل "2020")
//    أعد القيمة إلى true لتفعيل الفحص مجدداً
private static final boolean ENABLE_TID_LENGTH_VALIDATION = false;

    // ==================== الحالة ====================
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
    private Runnable onResumeRunnable;

    private volatile boolean isRfidScanning = false;
    private volatile boolean isFormLocked = false;
    private boolean isArabic = true;
    private AlertDialog activeLoadingDialog;

    // ✅ منع إعادة الدخول في stopRfidScanningMode
    private final AtomicBoolean stoppingReader = new AtomicBoolean(false);

    // ✅ Launchers
    private ActivityResultLauncher<Uri> takePhotoLauncher;
    private ActivityResultLauncher<String[]> pickImageLauncher;
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private Uri photoUri;
    private String currentPhotoPath;

    // ==================== Lifecycle ====================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
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
                isRfidScanning = savedInstanceState.getBoolean("is_rfid_scanning", false);
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

                    Log.d(TAG, "✅ IdentityStudio initialization complete");

                } catch (Exception e) {
                    Log.e(TAG, "Delayed init error: " + e.getMessage(), e);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error: " + e.getMessage(), e);
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

        photoManager = new IdentityPhotoManager(
                appContext,
                miraExecutor.getBackgroundExecutor());

        syncManager = new IdentitySyncManager(
                appContext,
                miraExecutor.getBackgroundExecutor());
    }

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
                        showToast(isArabic ? "🧹 تم حذف مسودة قديمة"
                                           : "🧹 Old draft removed");
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
                                showToast(isArabic ? "🗑️ تم حذف المسودة"
                                                   : "🗑️ Draft discarded");
                            })
                    .setCancelable(false)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showDraftFoundDialog error: " + e.getMessage());
        }
    }

    // ==================== إدارة النموذج ====================

    private void restoreDraftIfExists() {
        if (binding == null || formManager == null) return;

        if (isFormLocked) {
            Log.i(TAG, "restoreDraftIfExists skipped: form is locked");
            return;
        }

        try {
            final List<String> photoPaths = new ArrayList<>();

            boolean restored = formManager.restoreDraft(
                    new IdentityFormManager.FormData() {
                        @Override public String getEpc() { return safeGet(binding != null ? binding.etEpc : null); }
                        @Override public String getTitle() { return safeGet(binding != null ? binding.etTitle : null); }
                        @Override public String getGrossWeight() { return safeGet(binding != null ? binding.etGrossWeight : null); }
                        @Override public boolean isHasStones() {
                            return binding != null && binding.switchHasStones != null && binding.switchHasStones.isChecked();
                        }
                        @Override public String getStoneType() { return safeGet(binding != null ? binding.etStoneType : null); }
                        @Override public String getStoneWeight() { return safeGet(binding != null ? binding.etStoneWeight : null); }
                        @Override public String getStoneCount() { return safeGet(binding != null ? binding.etStoneCount : null); }
                        @Override public String getStoneValue() { return safeGet(binding != null ? binding.etStoneValue : null); }

                        @Override public void setEpc(String value) {
                            safeRun(() -> { if (binding != null && binding.etEpc != null && value != null) binding.etEpc.setText(value); });
                        }
                        @Override public void setTitle(String value) {
                            safeRun(() -> { if (binding != null && binding.etTitle != null && value != null) binding.etTitle.setText(value); });
                        }
                        @Override public void setGrossWeight(String value) {
                            safeRun(() -> { if (binding != null && binding.etGrossWeight != null && value != null) binding.etGrossWeight.setText(value); });
                        }
                        @Override public void setStoneType(String value) {
                            safeRun(() -> { if (binding != null && binding.etStoneType != null && value != null) binding.etStoneType.setText(value); });
                        }
                        @Override public void setStoneWeight(String value) {
                            safeRun(() -> { if (binding != null && binding.etStoneWeight != null && value != null) binding.etStoneWeight.setText(value); });
                        }
                        @Override public void setStoneCount(String value) {
                            safeRun(() -> { if (binding != null && binding.etStoneCount != null && value != null) binding.etStoneCount.setText(value); });
                        }
                        @Override public void setStoneValue(String value) {
                            safeRun(() -> { if (binding != null && binding.etStoneValue != null && value != null) binding.etStoneValue.setText(value); });
                        }
                        @Override public void setHasStones(boolean value) {
                            safeRun(() -> { if (binding != null && binding.switchHasStones != null) binding.switchHasStones.setChecked(value); });
                        }
                    },
                    photoPaths
            );

            if (restored) {
                if (photoManager != null && !photoPaths.isEmpty()) {
                    photoManager.restoreFromSavedPaths(photoPaths, photoCallback);
                }

                safeRun(() -> {
                    if (!isAdded() || binding == null) return;
                    onPhotosUpdated();
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "restoreDraftIfExists error: " + e.getMessage(), e);
        }
    }

    private void saveCurrentDraftAsync() {
        final FragmentMiraIdentityStudioBinding b = binding;
        if (formManager == null || b == null) return;

        final List<String> paths = photoManager != null ?
                photoManager.getPhotoPaths() : new ArrayList<>();

        formManager.saveDraft(new IdentityFormManager.FormData() {
            @Override public String getEpc() { return safeGet(b.etEpc); }
            @Override public String getTitle() { return safeGet(b.etTitle); }
            @Override public String getGrossWeight() { return safeGet(b.etGrossWeight); }
            @Override public boolean isHasStones() { return b.switchHasStones != null && b.switchHasStones.isChecked(); }
            @Override public String getStoneType() { return safeGet(b.etStoneType); }
            @Override public String getStoneWeight() { return safeGet(b.etStoneWeight); }
            @Override public String getStoneCount() { return safeGet(b.etStoneCount); }
            @Override public String getStoneValue() { return safeGet(b.etStoneValue); }

            @Override public void setEpc(String v) { /* no-op */ }
            @Override public void setTitle(String v) { /* no-op */ }
            @Override public void setGrossWeight(String v) { /* no-op */ }
            @Override public void setStoneType(String v) { /* no-op */ }
            @Override public void setStoneWeight(String v) { /* no-op */ }
            @Override public void setStoneCount(String v) { /* no-op */ }
            @Override public void setStoneValue(String v) { /* no-op */ }
            @Override public void setHasStones(boolean v) { /* no-op */ }
        }, paths);
    }

    private String safeGet(TextView tv) {
        return tv != null && tv.getText() != null ? tv.getText().toString() : "";
    }

    // ==================== إدارة الصور ====================

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

    private final IdentityPhotoManager.PhotoCallback photoCallback =
            new IdentityPhotoManager.PhotoCallback() {
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
                Glide.with(this)
                        .load(new File(path))
                        .placeholder(R.drawable.placeholder_image)
                        .error(R.drawable.ic_error_outline)
                        .into(ivPreview);
            }

            if (btnClose != null) {
                btnClose.setOnClickListener(v -> dialog.dismiss());
            }

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
                }
        );

        takePhotoLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    if (success && photoManager != null) {
                        photoManager.compressAndAddPhoto(currentPhotoPath, photoCallback);
                    }
                }
        );

        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                uris -> {
                    if (uris != null && !uris.isEmpty() && photoManager != null) {
                        for (Uri uri : uris) {
                            photoManager.copyAndAddPhoto(uri, photoCallback);
                        }
                    }
                }
        );
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
            binding.tvPhotosCount.setText(count == 0 ?
                    (isArabic ? "لم يتم إرفاق صور بعد" : "No photos attached yet") :
                    (isArabic ? "تم إرفاق (" + count + "/" + MAX_PHOTOS + ") صور"
                              : "Attached (" + count + "/" + MAX_PHOTOS + ") photos"));
        } catch (Exception e) {
            Log.e(TAG, "updatePhotosCountText error: " + e.getMessage());
        }
    }

    // ==================== الحسابات ====================

    private void calculateEstimatedGoldValue() {
        try {
            if (binding == null || binding.etGrossWeight == null) return;

            String weightStr = binding.etGrossWeight.getText() != null ?
                    binding.etGrossWeight.getText().toString().trim() : "";
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
                String sWeight = binding.etStoneWeight.getText() != null ?
                        binding.etStoneWeight.getText().toString().trim() : "";
                String sValue = binding.etStoneValue.getText() != null ?
                        binding.etStoneValue.getText().toString().trim() : "";

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
            String zeroText = "0.00 " + priceManager.getCurrencySymbol(priceManager.getDefaultCurrency());
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

    // ==================== المستمعون ====================

    private void setupListeners() {
        try {
            if (binding == null) return;

            binding.etEpc.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (binding != null && binding.tvStatusBadge != null) {
                        binding.tvStatusBadge.setText(s.length() > 0 ? "BOUND" : "WAITING");
                    }
                }
                @Override public void afterTextChanged(Editable s) {}
            });

            if (binding.tilEpc != null) {
                binding.tilEpc.setEndIconOnClickListener(v -> openBarcodeScannerForEPC());
            }

            binding.btnScanRfid.setOnClickListener(v -> {
                if (isFormLocked) {
                    showToast(isArabic ? "⛔ العملية قيد التنفيذ..." : "⛔ Operation in progress...");
                    return;
                }
                if (permissionManager.canEdit("identity") || permissionManager.isManager()) {
                    if (isRfidScanning) stopRfidScanningMode();
                    else activateRFIDModeForEncoding();
                } else {
                    showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
                }
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
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    calculateEstimatedGoldValue();
                }
                @Override public void afterTextChanged(Editable s) {}
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
                    showToast(isArabic ? "⚠️ الحد الأقصى " + MAX_PHOTOS + " صور"
                                       : "⚠️ Maximum " + MAX_PHOTOS + " photos");
                    return;
                }
                if (permissionManager.canEdit("identity") || permissionManager.isManager()) {
                    showImageSourceDialog();
                } else {
                    showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
                }
            });
            
            // ✅ [جديد] عند تغيير EPC يدوياً، أعِد تعيين الربط
if (binding.etEpc != null) {
    binding.etEpc.addTextChangedListener(new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(Editable s) {}
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            // إذا المستخدم حذف الحقل، أعِد تعيين الربط
            if (s.length() == 0 && isEpcBound) {
                isEpcBound = false;
                Log.d(TAG, "🔓 EPC unbound (field cleared)");
            }
        }
    });
}

            binding.btnSaveIdentity.setOnClickListener(v -> {
                if (isFormLocked) {
                    showToast(isArabic ? "⛔ العملية قيد التنفيذ، يرجى الانتظار..."
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

    private void toggleStonesDetails(boolean show) {
        if (binding == null || binding.layoutStonesDetails == null) return;

        if (show) {
            binding.layoutStonesDetails.setVisibility(View.VISIBLE);
            binding.layoutStonesDetails.setAlpha(0f);
            binding.layoutStonesDetails.setTranslationY(-10f);
            binding.layoutStonesDetails.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(250)
                    .start();
        } else {
            binding.layoutStonesDetails.animate()
                    .alpha(0f)
                    .translationY(-10f)
                    .setDuration(200)
                    .withEndAction(() -> {
                        if (binding != null && binding.layoutStonesDetails != null) {
                            binding.layoutStonesDetails.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    }

    // ==================== RFID ====================
private void processRfidTagAsync(final boolean closeScannerAfterSuccess) {
    // ✅ [إصلاح] لا تعالج إذا كان EPC مربوطاً مسبقاً
    if (isEpcBound && binding != null && binding.etEpc != null) {
        String existing = binding.etEpc.getText() != null
                ? binding.etEpc.getText().toString().trim() : "";
        if (!existing.isEmpty()) {
            Log.d(TAG, "⏭️ processRfidTagAsync: EPC already bound (" 
                    + existing + ") — skipping re-read");
            safeRun(() -> stopRfidScanningMode());
            return;
        }
    }

    if (appContext == null) {
        safeRun(() -> {
            playErrorSound();
            showErrorDialog(
                    isArabic ? "❌ خطأ داخلي" : "❌ Internal Error",
                    isArabic ? "لم يتم تهيئة السياق" : "Context not initialized"
            );
            stopRfidScanningMode();
        });
        return;
    }

    miraExecutor.getBackgroundExecutor().execute(() -> {
        try {
            MiraRfidService rfidService = MiraRfidService.getInstance(appContext);

            final String tid = rfidService.readTid(null);
            final boolean isSim = rfidService.isSimulationMode();

            if (tid == null || tid.trim().isEmpty()) {
                safeRun(() -> {
                    playErrorSound();
                    showErrorDialog(
                            isArabic ? "❌ فشل قراءة TID" : "❌ TID Read Failed",
                            isArabic ? "تعذّر قراءة TID الشريحة. يرجى إعادة المسح."
                                     : "Failed to read tag TID. Please re-scan."
                    );
                    stopRfidScanningMode();
                });
                return;
            }

            final String normalizedTid = tid.trim().toUpperCase(Locale.US);

            // ✅ فحص طول TID (يمكن تعطيله عبر ENABLE_TID_LENGTH_VALIDATION)
            if (ENABLE_TID_LENGTH_VALIDATION) {
                if (normalizedTid.length() < MIN_TID_LENGTH) {
                    safeRun(() -> {
                        playErrorSound();
                        showErrorDialog(
                                isArabic ? "❌ TID غير صالح" : "❌ Invalid TID",
                                isArabic ? "طول TID غير كافٍ (" + normalizedTid.length() +
                                           " حرف، الحد الأدنى " + MIN_TID_LENGTH + ")"
                                         : "TID length too short (" + normalizedTid.length() +
                                           " chars, minimum " + MIN_TID_LENGTH + ")"
                        );
                        stopRfidScanningMode();
                    });
                    return;
                }

                if (normalizedTid.length() > MAX_TID_LENGTH) {
                    safeRun(() -> {
                        playErrorSound();
                        showErrorDialog(
                                isArabic ? "❌ TID غير صالح" : "❌ Invalid TID",
                                isArabic ? "طول TID أكبر من المتوقع (" + normalizedTid.length() + ")"
                                         : "TID length exceeds expected (" + normalizedTid.length() + ")"
                        );
                        stopRfidScanningMode();
                    });
                    return;
                }
            } else {
                Log.d(TAG, "⏭️ TID length validation DISABLED — accepting TID: "
                        + normalizedTid + " (len=" + normalizedTid.length() + ")");
            }

            final boolean isTagAllowed = isSim || rfidService.verifyTagAllowed(normalizedTid);
            final boolean isDuplicate = syncManager.isEpcDuplicateLocally(normalizedTid);

            safeRun(() -> {
                if (binding == null) {
                    stopRfidScanningMode();
                    return;
                }

                if (!isTagAllowed) {
                    playErrorSound();
                    showErrorDialog(
                            isArabic ? "⛔ شريحة غير معتمدة" : "⛔ Unauthorized Tag",
                            isArabic ?
                                    "هذه الشريحة (TID: " + normalizedTid + ") غير مسجلة في مخزون الشرائح المعتمدة.\n\n" +
                                    "يرجى استخدام شريحة من مورد معتمد (SunlandRFID)." :
                                    "This tag (TID: " + normalizedTid + ") is not in the authorized inventory.\n\n" +
                                    "Please use a tag from an approved supplier."
                    );
                    stopRfidScanningMode();
                    return;
                }

                if (isDuplicate) {
                    playErrorSound();
                    showErrorDialog(
                            isArabic ? "شريحة مكررة" : "Duplicate Tag",
                            isArabic ?
                                    "هذه الشريحة (TID: " + normalizedTid + ") مسجلة بالفعل." :
                                    "This tag (TID: " + normalizedTid + ") is already registered."
                    );
                    stopRfidScanningMode();
                    return;
                }

                if (binding.etEpc != null) {
                    binding.etEpc.setText(normalizedTid);
                    binding.etEpc.setTag(normalizedTid);
                }
                if (binding.tvStatusBadge != null) {
                    binding.tvStatusBadge.setText("BOUND");
                }

                isEpcBound = true;  // ✅ علّم الربط

                Log.i(TAG, "✅ Tag verified: TID=" + normalizedTid);
                playSuccessSound();
                stopRfidScanningMode();
            });

        } catch (Exception e) {
            Log.e(TAG, "processRfidTagAsync error: " + e.getMessage(), e);
            safeRun(() -> {
                playErrorSound();
                showErrorDialog(
                        isArabic ? "❌ خطأ في قراءة الشريحة" : "❌ Tag Read Error",
                        e.getMessage() != null ? e.getMessage() :
                                (isArabic ? "حدث خطأ غير متوقع" : "Unexpected error")
                );
                stopRfidScanningMode();
            });
        }
    });
}

    private void activateRFIDModeForEncoding() {
        if (!(permissionManager.canEdit("identity") || permissionManager.isManager())) {
            showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            return;
        }

        try {
            if (!(getActivity() instanceof UHFMainActivity)) return;

            if (appContext == null) {
                Log.e(TAG, "appContext is null");
                return;
            }

            isRfidScanning = true;
            if (binding != null) {
                binding.btnScanRfid.setText(isArabic ? "إيقاف المسح..." : "Stop Scan...");
                binding.tvStatusBadge.setText("SCANNING...");
            }

            MiraIRFIDReader reader = RFIDManager.getInstance(appContext).getActiveReader();
            if (reader == null) {
                Log.e(TAG, "❌ No active reader");
                stopRfidScanningMode();
                return;
            }

            reader.setOnTagReadListener(new MiraIRFIDReader.OnTagReadListener() {
                @Override
                public void onTagRead(String epc, int rssi) {
                    if (!isRfidScanning || epc == null || epc.trim().isEmpty()) return;

                    final String receivedEpc = epc.trim().toUpperCase(Locale.US);
                    Log.i(TAG, "📡 EPC received: " + receivedEpc);

                    handler.post(() -> stopRfidScanningMode());
                    handler.post(() -> processReceivedTag(receivedEpc));
                }

                @Override public void onScanStarted() {}
                @Override public void onScanStopped() {}
                @Override public void onConnectionChanged(boolean connected) {}
                @Override
                public void onError(String error) {
                    Log.e(TAG, "RFID error: " + error);
                }
            });

            boolean ok = reader.startScan();
            Log.i(TAG, "startScan result: " + ok);

            if (!ok) {
                stopRfidScanningMode();
                showErrorDialog(
                        isArabic ? "❌ فشل بدء المسح" : "❌ Scan start failed",
                        isArabic ? "تعذر تشغيل القارئ" : "Could not start reader"
                );
                return;
            }

            showToast(isArabic ? "📡 جاري البحث عن شريحة RFID..." : "📡 Searching for RFID tag...");

            if (rfidTimeoutRunnable != null) handler.removeCallbacks(rfidTimeoutRunnable);
            rfidTimeoutRunnable = this::stopRfidScanningMode;
            handler.postDelayed(rfidTimeoutRunnable, RFID_TIMEOUT_MS);

        } catch (Exception e) {
            Log.e(TAG, "activateRFIDMode error: " + e.getMessage(), e);
            stopRfidScanningMode();
        }
    }

    private void processReceivedTag(String epc) {
        if (epc == null || epc.isEmpty()) return;

        if (!isAdded() || binding == null || isRemoving() || isDetached()) {
            Log.d(TAG, "processReceivedTag skipped: fragment not attached");
            return;
        }

        Log.i(TAG, "🎯 Processing received EPC: " + epc);

        miraExecutor.getBackgroundExecutor().execute(() -> {
            boolean isTagAllowed = false, isDuplicate = false;
            try {
                MiraRfidService rfidService = MiraRfidService.getInstance(appContext);
                boolean isSim = rfidService.isSimulationMode();
                isTagAllowed = isSim || rfidService.verifyTagAllowed(epc);
                isDuplicate = syncManager.isEpcDuplicateLocally(epc);
            } catch (Exception e) {
                Log.e(TAG, "processReceivedTag checks error: " + e.getMessage(), e);
                return;
            }

            final boolean fAllowed = isTagAllowed;
            final boolean fDuplicate = isDuplicate;

            safeRun(() -> {
                if (binding == null || !isAdded()) return;

                if (!fAllowed) {
                    playErrorSound();
                    showErrorDialog(
                            isArabic ? "⛔ شريحة غير معتمدة" : "⛔ Unauthorized Tag",
                            isArabic ? "هذه الشريحة (" + epc + ") غير مسجلة."
                                     : "This tag (" + epc + ") is not authorized."
                    );
                    return;
                }

                if (fDuplicate) {
                    playErrorSound();
                    showErrorDialog(
                            isArabic ? "شريحة مكررة" : "Duplicate Tag",
                            isArabic ? "هذه الشريحة (" + epc + ") مسجلة بالفعل."
                                     : "This tag (" + epc + ") is already registered."
                    );
                    return;
                }

                // ✅ [إصلاح] لا تستبدل EPC إذا كان مربوطاً
if (isEpcBound && binding.etEpc != null) {
    String existing = binding.etEpc.getText() != null
            ? binding.etEpc.getText().toString().trim() : "";
    if (!existing.isEmpty() && !existing.equals(epc)) {
        Log.w(TAG, "⚠️ Ignoring new EPC (" + epc +
                ") because field is already bound to: " + existing);
        return;
    }
}

if (binding.etEpc != null) {
    binding.etEpc.setText(epc);
    binding.etEpc.setTag(epc);
    isEpcBound = true;  // ✅ علّم الربط
}
if (binding.tvStatusBadge != null) {
    binding.tvStatusBadge.setText("BOUND");
}


                Log.i(TAG, "✅ Tag accepted: " + epc);
                playSuccessSound();
            });
        });
    }

    private void stopRfidScanningMode() {
        if (!stoppingReader.compareAndSet(false, true)) {
            return;
        }

        isRfidScanning = false;

        if (rfidTimeoutRunnable != null) {
            handler.removeCallbacks(rfidTimeoutRunnable);
            rfidTimeoutRunnable = null;
        }

        safeRun(() -> {
            if (binding != null && binding.btnScanRfid != null) {
                binding.btnScanRfid.setText(isArabic ? "مسح رمز RFID" : "Scan RFID Tag");
                if (binding.tvStatusBadge != null &&
                        "SCANNING...".equals(binding.tvStatusBadge.getText().toString())) {
                    binding.tvStatusBadge.setText("WAITING");
                }
            }
        });

        if (appContext == null || miraExecutor == null) {
            stoppingReader.set(false);
            return;
        }

        miraExecutor.getBackgroundExecutor().execute(() -> {
            try {
                MiraIRFIDReader reader = RFIDManager.getInstance(appContext).getActiveReader();
                if (reader != null) {
                    try { reader.setOnTagReadListener(null); } catch (Exception ignored) {}
                    try { reader.stopScan(); } catch (Exception ignored) {}
                    Log.d(TAG, "✅ Reader stopped on background");
                }
            } finally {
                stoppingReader.set(false);
            }
        });
    }

    public void onRfidScanned(String epc) {
    if (!isAdded() || getActivity() == null) return;

    // ✅ [إصلاح] لا تعالج إذا EPC مربوط
    if (isEpcBound) {
        Log.d(TAG, "⏭️ onRfidScanned: EPC already bound — ignoring trigger");
        return;
    }

    final String scannedEpc = epc != null ?
            epc.trim().toUpperCase(Locale.US) : "";
    Log.i(TAG, "📡 onRfidScanned (EPC ignored: " + scannedEpc + ")");

    processRfidTagAsync(false);
}

    // ==================== الاقتراحات ====================

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

    // ==================== الصور ====================

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
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED) {
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
                    photoUri = FileProvider.getUriForFile(getContext(),
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

    // ==================== الباركود ====================

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
                if (data == null || data.trim().isEmpty()) {
                    safeRun(() -> {
                        playErrorSound();
                        showToast(isArabic ? "❌ باركود فارغ" : "❌ Empty barcode");
                    });
                    return;
                }

                final String scannedCode = data.trim().toUpperCase(Locale.US);

                if (scannedCode.length() < 6 || scannedCode.length() > 128) {
                    safeRun(() -> {
                        playErrorSound();
                        showErrorDialog(
                                isArabic ? "❌ باركود غير صالح" : "❌ Invalid Barcode",
                                isArabic ? "طول الباركود غير منطقي (" + scannedCode.length() + ")"
                                         : "Barcode length unreasonable (" + scannedCode.length() + ")"
                        );
                    });
                    return;
                }

                miraExecutor.getBackgroundExecutor().execute(() -> {
                    final boolean isDuplicate = syncManager.isEpcDuplicateLocally(scannedCode);

                    safeRun(() -> {
                        if (isDuplicate) {
                            playErrorSound();
                            showErrorDialog(
                                    isArabic ? "باركود مكرر" : "Duplicate Barcode",
                                    isArabic ? "هذا الكود مسجل بالفعل:\n" + scannedCode
                                             : "This code is already registered:\n" + scannedCode
                            );
                        } else {
                            if (binding != null && binding.etEpc != null) {
                                binding.etEpc.setText(scannedCode);
                                binding.etEpc.setTag(scannedCode);
                                if (binding.tvStatusBadge != null) {
                                    binding.tvStatusBadge.setText("BOUND");
                                }
                            }
                            isEpcBound = true;  // ✅ علّم الربط
                            playSuccessSound();
                            Log.i(TAG, "✅ Barcode accepted: " + scannedCode);
                        }

                        try {
                            if (fm.getBackStackEntryCount() > 0) fm.popBackStack();
                        } catch (Exception e) {
                            Log.e(TAG, "popBackStack error", e);
                        }
                    });
                });
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
                    .replace(cid, sf, "barcode_scanner_tag")
                    .addToBackStack("barcode_scanner_encode")
                    .commit();

        } catch (Exception e) {
            Log.e(TAG, "Barcode scanner error: " + e.getMessage(), e);
            showToast(isArabic ? "❌ خطأ في فتح الماسح" : "❌ Scanner error");
        }
    }

    // ==================== الحفظ والتكويد ====================

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

            // ==================== 1) قراءة البيانات ====================
            String identifier = binding.etEpc.getText() != null ?
                    binding.etEpc.getText().toString().trim() : "";
            String title = binding.etTitle.getText() != null ?
                    binding.etTitle.getText().toString().trim() : "";
            String weightStr = binding.etGrossWeight.getText() != null ?
                    binding.etGrossWeight.getText().toString().trim() : "";

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
                stoneType = binding.etStoneType.getText() != null ?
                        binding.etStoneType.getText().toString().trim() : "";
                String stoneWeightStr = binding.etStoneWeight.getText() != null ?
                        binding.etStoneWeight.getText().toString().trim() : "";
                String stoneCountStr = binding.etStoneCount.getText() != null ?
                        binding.etStoneCount.getText().toString().trim() : "";
                String stoneValueStr = binding.etStoneValue.getText() != null ?
                        binding.etStoneValue.getText().toString().trim() : "";

                if (!stoneValueStr.isEmpty()) stoneValue = Double.parseDouble(stoneValueStr);
                if (!stoneWeightStr.isEmpty()) stoneWeightCarats = Double.parseDouble(stoneWeightStr);
                if (!stoneCountStr.isEmpty()) stoneCount = Integer.parseInt(stoneCountStr);
            }

            double stoneWeightGrams = PricingCalculator.caratsToGrams(stoneWeightCarats);
            if (stoneWeightGrams >= grossWeight) {
                showValidationError(isArabic ?
                        "❌ وزن الأحجار لا يمكن أن يتجاوز أو يساوي الوزن الإجمالي!" :
                        "❌ Stone weight cannot exceed gross weight!");
                unlockFormFast();
                return;
            }

            // ==================== 2) حساب التسعير ====================
            int selectedKaratNum = getSelectedKaratNumber();
            double[] pricing = PricingCalculator.calculatePricingDetails(
                    grossWeight, stoneWeightCarats, stoneValue, selectedKaratNum, priceManager);
            final double netGoldWeight = pricing[6];

            MiraPriceManager.PricingDetails details =
                    priceManager.calculatePricingDetails(selectedKaratNum, netGoldWeight);

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
                             : "Please wait - saving item in database"
            );

            // ==================== 3) تثبيت المتغيرات ====================
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

            String tidFromField = binding.etEpc.getText() != null
                    ? binding.etEpc.getText().toString().trim().toUpperCase(Locale.US)
                    : "";

            if (tidFromField.isEmpty()) {
                safeRun(() -> {
                    dismissDialog(activeLoadingDialog);
                    activeLoadingDialog = null;
                    unlockFormFast();
                    setFormEnabled(true);
                    showErrorDialog(
                            isArabic ? "❌ فشل التكويد" : "❌ Encoding Failed",
                            isArabic ?
                                    "لا يمكن قراءة TID الشريحة.\n\n" +
                                    "يرجى:\n" +
                                    "• مسح الشريحة أولاً\n" +
                                    "• أو إدخال TID يدوياً" :
                                    "Cannot read tag TID.\n\n" +
                                    "Please:\n" +
                                    "• Scan the tag first\n" +
                                    "• Or enter TID manually"
                    );
                });
                return;
            }

            // ✅ فحص طول TID (يمكن تعطيله عبر ENABLE_TID_LENGTH_VALIDATION)
if (ENABLE_TID_LENGTH_VALIDATION) {
    if (tidFromField.length() < MIN_TID_LENGTH) {
        safeRun(() -> {
            dismissDialog(activeLoadingDialog);
            activeLoadingDialog = null;
            unlockFormFast();
            setFormEnabled(true);
            showErrorDialog(
                    isArabic ? "❌ TID غير صالح" : "❌ Invalid TID",
                    isArabic ? "طول TID غير كافٍ (" + tidFromField.length() +
                               " حرف، الحد الأدنى " + MIN_TID_LENGTH + ")"
                             : "TID length too short (" + tidFromField.length() +
                               " chars, minimum " + MIN_TID_LENGTH + ")"
            );
        });
        return;
    }

    if (tidFromField.length() > MAX_TID_LENGTH) {
        safeRun(() -> {
            dismissDialog(activeLoadingDialog);
            activeLoadingDialog = null;
            unlockFormFast();
            setFormEnabled(true);
            showErrorDialog(
                    isArabic ? "❌ TID غير صالح" : "❌ Invalid TID",
                    isArabic ? "طول TID أكبر من المتوقع (" + tidFromField.length() + ")"
                             : "TID length exceeds expected (" + tidFromField.length() + ")"
            );
        });
        return;
    }
} else {
    Log.d(TAG, "⏭️ TID length validation DISABLED — proceeding with TID: "
            + tidFromField + " (len=" + tidFromField.length() + ")");
}

            final String tid = tidFromField;
            final String gtin = tid;

            final boolean isSim;
            try {
                MiraRfidService rfidService = MiraRfidService.getInstance(appContext);
                isSim = rfidService.isSimulationMode();
            } catch (Exception e) {
                Log.e(TAG, "Failed to check simulation mode", e);
                safeRun(() -> {
                    dismissDialog(activeLoadingDialog);
                    activeLoadingDialog = null;
                    unlockFormFast();
                    setFormEnabled(true);
                });
                return;
            }

            Log.i(TAG, "✅ Proceeding with TID=" + tid + " | GTIN=" + gtin + " | SIM=" + isSim);

            // ==================== 4) إرسال البيانات ====================
            miraExecutor.getBackgroundExecutor().execute(() -> {
                try {
                    JSONObject payload = buildPayload(
                            tid, finalTitle, selectedKarat, fGrossWeight, netGoldWeight,
                            fStoneWeightCarats, fStoneCount, fStoneValue, fStoneType,
                            details, selectedKaratNum, finalPriceUSD, finalPriceLocal,
                            localCurrency, exchangeRate, selectedCategory, location,
                            branchId, currentUserId, tid, isSim, finalPhotoPaths, gtin
                    );

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

                    // ==================== 5) حالة الفشل ====================
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
                                String errorMsg = finalErrorMessage != null ? finalErrorMessage :
                                        (isArabic ? "❌ فشل التكويد" : "❌ Encoding failed");
                                showErrorDialog(isArabic ? "خطأ في التكويد" : "Encoding Error", errorMsg);
                                unlockFormFast();
                                setFormEnabled(true);
                            }
                        });
                        return;
                    }

                    // ==================== 6) كتابة EPC ====================
                    Log.i(TAG, "✅ Server OK — Starting hardware encoding sequence");

                    MiraRfidService rfidService = MiraRfidService.getInstance(appContext);

                    String miraId = (finalResponseData != null) ?
                            finalResponseData.optString("mira_id", finalIdentifier) : finalIdentifier;

                    String newEpc = rfidService.generateEpcFromMiraId(miraId);
                    Log.i(TAG, "🎯 Generated new EPC: " + newEpc + " (from mira_id=" + miraId + ")");

                    boolean writeOk = rfidService.writeEpc(newEpc, null);
                    boolean alreadyEncoded = false;

                    if (!writeOk) {
                        Log.w(TAG, "⚠️ writeEpc failed — checking if tag is pre-encoded...");
                        String existingEpc = rfidService.readEpc(null);
                        if (existingEpc != null && !existingEpc.trim().isEmpty()) {
                            Log.i(TAG, "✅ Tag is pre-encoded with EPC: " + existingEpc);
                            newEpc = existingEpc.trim().toUpperCase(Locale.US);
                            writeOk = true;
                            alreadyEncoded = true;
                        } else {
                            Log.e(TAG, "❌ Tag is blank AND write failed — hardware issue");
                        }
                    }

                    boolean verifyOk = writeOk && rfidService.verifyWrite(newEpc, null);

                    boolean lockOk = false;
                    if (verifyOk && !alreadyEncoded) {
                        lockOk = rfidService.setTagLockState(null, 1, true);
                    } else if (alreadyEncoded) {
                        lockOk = true;
                    }

                    if (lockOk || verifyOk) {
                        try {
                            rfidService.markTagProgrammed(tid, miraId, newEpc);
                        } catch (Exception e) {
                            Log.w(TAG, "markTagProgrammed exception", e);
                        }
                    }

                    final boolean finalWrite = writeOk;
                    final boolean finalVerify = verifyOk;
                    final boolean finalLock = lockOk;
                    final boolean finalWasPreEncoded = alreadyEncoded;
                    final String finalNewEpc = newEpc;

                    // ==================== 7) تحديث السيرفر ====================
                    if (finalWrite) {
                        try {
                            JSONObject updatePayload = new JSONObject();
                            updatePayload.put("item_id", finalServerItemId);
                            updatePayload.put("rfid_epc_final", finalNewEpc);
                            updatePayload.put("rfid_tid", tid);
                            updatePayload.put("rfid_locked", finalLock);
                            updatePayload.put("rfid_verified", finalVerify);
                            updatePayload.put("rfid_pre_encoded", finalWasPreEncoded);

                            MiraApiClient.ApiResponse updateResp =
                                    apiClient.postWithAuth("/items/update-rfid", updatePayload);
                            if (updateResp != null && updateResp.isSuccess) {
                                Log.i(TAG, "✅ RFID metadata synced to server");
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to update RFID metadata", e);
                        }
                    }

                    // ==================== 8) عرض النتائج ====================
                    safeRun(() -> {
                        dismissDialog(activeLoadingDialog);
                        activeLoadingDialog = null;
                        playSuccessSound();

                        miraExecutor.getSingleThreadExecutor().execute(() ->
                                syncManager.removePendingEncode(finalIdentifier));
                        if (formManager != null) formManager.clearDraft();

                        notifyDashboardUpdate("encode_success");

                        if (!finalPhotoPaths.isEmpty() && finalServerItemId > 0) {
                            uploadItemImages(finalServerItemId, finalPhotoPaths);
                        }

                        String displayEpc = finalWrite ? finalNewEpc : finalIdentifier;

                        String gs1Code = "(01)0729000000" +
                                (finalServerItemId > 0 ?
                                        String.format(Locale.US, "%05d", finalServerItemId) :
                                        "00000") +
                                "(21)" + displayEpc;
                        String qrUrl = "https://ibreg.org/id/" +
                                (finalServerItemId > 0 ? finalServerItemId : displayEpc);

                        if (finalResponseData != null) {
                            JSONObject gs1Obj = finalResponseData.optJSONObject("gs1");
                            if (gs1Obj != null) {
                                gs1Code = gs1Obj.optString("raw_fnc1", gs1Code);
                                qrUrl = gs1Obj.optString("digital_link", qrUrl);
                            }
                        }

                        if (binding != null && binding.etEpc != null) {
                            binding.etEpc.setText("");
                        }

                        if (finalWrite && finalVerify && finalLock) {
                            if (finalWasPreEncoded) {
                                showToast(isArabic ?
                                        "ℹ️ تم ربط شريحة مبرمجة مسبقاً بنجاح" :
                                        "ℹ️ Pre-encoded tag bound successfully");
                            } else {
                                showToast(isArabic ?
                                        "🔒 تم التكويد والقفل بنجاح" :
                                        "🔒 Encoded & locked successfully");
                            }
                        } else if (finalWrite && finalVerify && !finalLock) {
                            showToast(isArabic ?
                                    "⚠️ تم التكويد لكن القفل فشل — الشريحة غير محمية" :
                                    "⚠️ Encoded but lock failed — tag unprotected");
                        } else if (finalWrite && !finalVerify) {
                            showToast(isArabic ?
                                    "⚠️ تمت الكتابة لكن التحقق فشل — يرجى إعادة المسح" :
                                    "⚠️ Written but verification failed — re-scan needed");
                        } else {
                            showToast(isArabic ?
                                    "❌ فشلت كتابة EPC — الشريحة تحمل الرمز القديم" :
                                    "❌ EPC write failed — tag has old code");
                        }

                        showEncodingSuccessDialogEnhanced(
                                finalTitle, displayEpc, tid, gs1Code, qrUrl,
                                String.valueOf(fGrossWeight), selectedKarat,
                                finalPriceLocal, localCurrency, finalPhotoPaths,
                                fStoneType, fStoneWeightCarats, fStoneCount, fStoneValue,
                                netGoldWeight
                        );
                    });

                } catch (Exception e) {
                    Log.e(TAG, "Background execution error", e);
                    safeRun(() -> {
                        dismissDialog(activeLoadingDialog);
                        activeLoadingDialog = null;
                        unlockFormFast();
                        setFormEnabled(true);
                        showErrorDialog(isArabic ? "خطأ للنظام" : "System Error",
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

    private void unlockFormFast() {
        isFormLocked = false;
        if (binding != null && binding.btnSaveIdentity != null) {
            boolean canEdit = permissionManager.canEdit("identity") || permissionManager.isManager();
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
                    if (displayMessage.isEmpty()) displayMessage =
                            message.isEmpty() ? messageEn : message;

                    JSONObject details = errorObj.optJSONObject("details");
                    if (details != null) {
                        String suggestion = details.optString("suggestion", "");
                        if (!suggestion.isEmpty()) displayMessage += "\n\n💡 " + suggestion;
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
        if (title.isEmpty()) {
            showValidationError(isArabic ? "يرجى كتابة عنوان القطعة" : "Please enter item title");
            return false;
        }
        if (title.length() < 3) {
            showValidationError(isArabic ? "العنوان قصير جداً" : "Title is too short");
            return false;
        }
        if (weightStr.isEmpty()) {
            showValidationError(isArabic ? "يرجى تحديد وزن القطعة" : "Please specify item weight");
            return false;
        }

        try {
            double weight = Double.parseDouble(weightStr);
            if (weight <= 0) {
                showValidationError(isArabic ? "يجب أن يكون الوزن أكبر من صفر" :
                        "Weight must be greater than zero");
                return false;
            }
            if (weight > 10000) {
                showValidationError(isArabic ? "الوزن كبير جداً" : "Weight is too large");
                return false;
            }
        } catch (NumberFormatException e) {
            showValidationError(isArabic ? "صيغة الوزن غير صحيحة" : "Invalid weight format");
            return false;
        }

        return true;
    }

    private JSONObject buildPayload(
            String epc, String title, String karat, double grossWeight, double netWeight,
            double stoneWeightCarats, int stoneCount, double stoneValue, String stoneType,
            MiraPriceManager.PricingDetails details, int karatNum, double priceUSD,
            double priceLocal, String currency, double exchangeRate, String category,
            String location, int branchId, int userId, String tid, boolean isSim,
            List<String> photoPathsList, String gtin) throws Exception {

        JSONObject payload = new JSONObject();

        if (epc != null && !epc.isEmpty()) {
            payload.put("epc", epc);
        }

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
            payload.put("epc", tid);
            payload.put("tid", tid);
            payload.put("rfid_tid", tid);
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

    // ==================== المزامنة ====================

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

    /**
     * ✅ المزامنة مستقلة عن دورة حياة الـ Fragment (لا تستخدم handler)
     */
    private void scheduleAutoSync() {
        if (syncManager == null) return;

        Log.d(TAG, "🔄 Auto-sync scheduled (independent of fragment lifecycle)");

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

            int branchId = (branchesManager != null) ?
                    branchesManager.getActiveBranchId() : 1;

            miraExecutor.getNetworkExecutor().execute(() -> {
                syncManager.syncPendingEncodes(branchId, new IdentitySyncManager.SyncCallback() {
                    @Override
                    public void onSyncComplete(int syncedCount) {
                        if (syncedCount > 0) {
                            Log.i(TAG, "✅ Auto-synced " + syncedCount + " encodings");
                            safeRun(() -> {
                                updatePendingSyncBadge();
                                if (isAdded() && getContext() != null) {
                                    showToast(isArabic ?
                                            "✅ تمت مزامنة " + syncedCount + " تكويد" :
                                            "✅ Synced " + syncedCount + " encodings");
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
                    binding.tvPendingSyncCount.setText(isArabic ?
                            "⏳ انتظار المزامنة: " + count :
                            "⏳ Pending Sync: " + count);
                } else {
                    binding.tvPendingSyncCount.setVisibility(View.GONE);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "updatePendingSyncBadge error: " + e.getMessage());
        }
    }

    // ==================== رفع الصور ====================

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
                    showToast(isArabic ?
                            "📷 تم رفع " + fUploaded + " صورة" :
                            "📷 " + fUploaded + " images uploaded");
                }
            });
        });
    }

    // ==================== حوار النجاح ====================

    private void showEncodingSuccessDialogEnhanced(
            String title, String epc, String tid, String gs1Code, String qrUrl,
            String weight, String karat, double finalPrice, String currency,
            List<String> photos,
            String stoneType, double stoneWeightCarats, int stoneCount, double stoneValue,
            double netWeight) {

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
        if (tvEpcCode != null) tvEpcCode.setText("EPC: " + epc);

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
            tvIdPrice.setText(priceManager.formatPriceDefault(finalPrice));
        }

        TextView tvIdStonesInfo = view.findViewById(R.id.tvIdStonesInfo);
        if (tvIdStonesInfo != null) {
            if (stoneWeightCarats > 0 || stoneValue > 0 || stoneCount > 0) {
                String stonesText;
                if (isArabic) {
                    stonesText = "الأحجار: " + (stoneType.isEmpty() ? "غير محدد" : stoneType);
                    if (stoneWeightCarats > 0) stonesText += " | وزن: " + stoneWeightCarats + " قيراط";
                    if (stoneCount > 0) stonesText += " | عدد: " + stoneCount + " حجر";
                    if (stoneValue > 0)
                        stonesText += " | قيمة: " + priceManager.formatPriceDefault(stoneValue) + " " + currency;
                } else {
                    stonesText = "Stones: " + (stoneType.isEmpty() ? "N/A" : stoneType);
                    if (stoneWeightCarats > 0) stonesText += " | Wt: " + stoneWeightCarats + " ct";
                    if (stoneCount > 0) stonesText += " | Pcs: " + stoneCount;
                    if (stoneValue > 0)
                        stonesText += " | Val: " + priceManager.formatPriceDefault(stoneValue) + " " + currency;
                }
                tvIdStonesInfo.setText(stonesText);
                tvIdStonesInfo.setVisibility(View.VISIBLE);
            } else {
                tvIdStonesInfo.setVisibility(View.GONE);
            }
        }

        miraExecutor.getImageExecutor().execute(() -> {
            Bitmap dmBitmap = (gs1Code != null && !gs1Code.isEmpty()) ?
                    BarcodeGenerator.generateGS1DataMatrix(gs1Code, 250, 250) : null;
            Bitmap qrBitmap = (qrUrl != null && !qrUrl.isEmpty()) ?
                    BarcodeGenerator.generateMicroQRCode(qrUrl, 250, 250) : null;

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
                        Bitmap tagToPrint = TagPrinter.generatePhysicalTagBitmap(epc, gs1Code, qrUrl);
                        safeRun(() -> TagPrinter.printTagDirectly(requireContext(), tagToPrint));
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

    // ==================== إعادة تعيين النموذج ====================

    private void resetFormForNextScan() {
    isEpcBound = false;  // ✅ أعِد التعيين
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
            binding.etEpc.requestFocus();

            if (photoManager != null) {
                photoManager.clearPhotos(false);
            }
            onPhotosUpdated();

            if (formManager != null) {
                formManager.clearDraft();
            }

            // ✅ ابدأ المسح فقط بعد تحقق صريح من أن الشاشة ما زالت نشِطة
            if (isResumed() && !isRemoving() && !isDetached() && binding != null) {
                binding.btnScanRfid.postDelayed(() -> {
                    if (isAdded() && isResumed() && !isRemoving()
                            && !isRfidScanning && !isFormLocked && !stoppingReader.get()) {
                        activateRFIDModeForEncoding();
                    }
                }, 500);
            }

        } catch (Exception e) {
            Log.e(TAG, "resetFormForNextScan error: " + e.getMessage(), e);
        }
    }

    // ==================== الصلاحيات والواجهة ====================

    private void applyPermissions() {
        if (binding == null) return;
        boolean canEdit = permissionManager.canEdit("identity") || permissionManager.isManager();

        if (binding.btnSaveIdentity != null) {
            binding.btnSaveIdentity.setEnabled(canEdit && !isFormLocked);
            binding.btnSaveIdentity.setAlpha((canEdit && !isFormLocked) ? 1.0f : 0.5f);
        }

        if (binding.btnScanRfid != null)
            binding.btnScanRfid.setEnabled(canEdit && !isFormLocked);
        if (binding.btnAddPhotos != null)
            binding.btnAddPhotos.setEnabled(canEdit && !isFormLocked);

        if (!canEdit) {
            showToast(isArabic ? "⛔ وضع القراءة فقط - غير مصرح بالتعديل" :
                    "⛔ Read only mode - Access Denied");
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
            binding.tvStepTitle.setText("الخطوة 1 من 4: الربط والتصنيف وتوليد الاكواد المجهرية");
            binding.tilEpc.setHint("رمز الشريحة (EPC / GTIN)...");
            binding.btnScanRfid.setText(isRfidScanning ? "إيقاف المسح..." : "مسح رمز RFID");
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
            boolean canEdit = permissionManager.canEdit("identity") || permissionManager.isManager();
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

    // ==================== أدوات مساعدة ====================

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
                .setPositiveButton(isArabic ? "موافق" : "OK", (dialog, which) -> dialog.dismiss())
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

    private void notifyDashboardUpdate(String source) {
        try {
            if (!isAdded() || getContext() == null) return;

            Context ctx = getContext();
            int branchId = (branchesManager != null) ?
                    branchesManager.getActiveBranchId() : 0;
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
            if (appContext == null) return 0;
            SharedPreferences prefs = appContext.getSharedPreferences(
                    "session", Context.MODE_PRIVATE);
            return prefs.getInt("user_id", 0);
        } catch (Exception e) {
            return 0;
        }
    }

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

    // ==================== دورة الحياة ====================

    @Override
    public void onResume() {
        super.onResume();

        if (!isAdded() || getActivity() == null) return;

        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setBottomNavVisible(false);
            ((UHFMainActivity) getActivity()).setActiveEncodingFragment(this);
        }

        if (binding == null || getView() == null) {
            handler.postDelayed(() -> {
                if (!isAdded() || binding == null || isRemoving()) return;
                performOnResumeSetup();

// ✅ [إصلاح] لا تبدأ المسح التلقائي إذا EPC مربوط بالفعل
if (!isRfidScanning && !isFormLocked && !stoppingReader.get() && !isEpcBound) {
    autoStartRfidScanning();
} else if (isEpcBound) {
    Log.d(TAG, "⏭️ Auto-scan skipped: EPC is already bound");
}
            }, 100);
            return;
        }

        performOnResumeSetup();
        if (!isRfidScanning && !isFormLocked && !stoppingReader.get()) {
            autoStartRfidScanning();
        }
    }

    private void autoStartRfidScanning() {
    if (isDestroyed() || !isAdded() || binding == null) return;
    if (isFormLocked || isRfidScanning || stoppingReader.get()) return;

    // ✅ [إصلاح] لا تبدأ إذا EPC مربوط
    if (isEpcBound) {
        Log.d(TAG, "⏭️ autoStartRfidScanning: EPC already bound — skip");
        return;
    }

    // ✅ [إصلاح] لا تبدأ إذا الحقل يحتوي على قيمة
    if (binding.etEpc != null) {
        String currentText = binding.etEpc.getText() != null
                ? binding.etEpc.getText().toString().trim() : "";
        if (!currentText.isEmpty()) {
            Log.d(TAG, "⏭️ autoStartRfidScanning: etEpc has value (" +
                    currentText + ") — skip");
            return;
        }
    }

    handler.postDelayed(() -> {
        if (isDestroyed() || !isAdded() || binding == null) return;
        if (isFormLocked || isRfidScanning || stoppingReader.get()) return;
        if (isEpcBound) return;
        if (getActivity() == null || getActivity().isFinishing()) return;

        // ✅ [إصلاح] فحص أخير: الحقل ما زال فارغاً
        if (binding.etEpc != null) {
            String currentText = binding.etEpc.getText() != null
                    ? binding.etEpc.getText().toString().trim() : "";
            if (!currentText.isEmpty()) return;
        }

        try {
            Log.i(TAG, "🚀 Auto-starting RFID scan for encoding");
            activateRFIDModeForEncoding();
        } catch (Exception e) {
            Log.e(TAG, "autoStartRfidScanning error: " + e.getMessage());
        }
    }, 800);
}

    private boolean isDestroyed() {
        return isRemoving() || isDetached() || !isAdded();
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
                        if (formManager != null) {
                            formManager.cleanupOldDrafts();
                        }
                        if (photoManager != null) {
                            photoManager.cleanupOrphanFiles(
                                    photoManager.getPhotoPaths());
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

        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setBottomNavVisible(true);
            ((UHFMainActivity) getActivity()).setActiveEncodingFragment(null);
        }

        View v = getView();
        if (v != null && onResumeRunnable != null) {
            v.removeCallbacks(onResumeRunnable);
            onResumeRunnable = null;
        }

        // ✅ نظّف handler من الـ runnables المرتبطة بالواجهة فقط
        // (المزامنة الآن مستقلة — تعمل عبر MiraExecutorService)
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        stopRfidScanningMode();
        saveCurrentDraftAsync();
    }

    @Override
    public void onDestroyView() {
        Log.d(TAG, "onDestroyView: cleaning up");

        // ✅ 1) أوقف الـ RFID أولاً (الآن غير مُعطِّل)
        stopRfidScanningMode();

        // ✅ 2) أغلق حوار التحميل
        dismissDialog(activeLoadingDialog);
        activeLoadingDialog = null;

        // ✅ 3) نظّف handler
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        rfidTimeoutRunnable = null;
        onResumeRunnable = null;

        // ✅ 4) افصل مرجع النشاط
        if (getActivity() instanceof UHFMainActivity) {
            try {
                ((UHFMainActivity) getActivity()).setActiveEncodingFragment(null);
            } catch (Exception e) {
                Log.e(TAG, "Failed to clear active encoding fragment", e);
            }
        }

        // ✅ 5) نظّف RecyclerView قبل الـ Adapter
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

        // ✅ 6) أخيراً ألغِ binding
        binding = null;

        super.onDestroyView();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("is_form_locked", isFormLocked);
        outState.putBoolean("is_rfid_scanning", isRfidScanning);
        if (currentPhotoPath != null) {
            outState.putString("camera_photo_path", currentPhotoPath);
        }
    }
}
