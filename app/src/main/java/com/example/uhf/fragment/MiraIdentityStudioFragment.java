package com.example.uhf.fragment;

import android.Manifest;
import androidx.fragment.app.FragmentManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
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

import android.content.Intent;
import android.os.Environment;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.databinding.FragmentMiraIdentityStudioBinding;
import com.example.uhf.manager.IdentityFormManager;
import com.example.uhf.manager.IdentityPhotoManager;
import com.example.uhf.manager.IdentitySyncManager;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.adapter.PhotosAdapter;
import com.example.uhf.utils.BarcodeGenerator;
import com.example.uhf.utils.MiraExecutorService;
import com.example.uhf.utils.PricingCalculator;
import com.example.uhf.utils.TagPrinter;
import com.example.uhf.MiraRfid.MiraRfidService;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * MiraIdentityStudioFragment - الإصدار الاحترافي 3.0
 * 
 * ✅ الميزات:
 * - MiraExecutorService (مدير خيوط على مستوى التطبيق)
 * - IdentitySyncManager الاحترافي (CRUD كامل + Retry + Backoff)
 * - الربط مع SyncLogFragment (شاشة سجل تفاعلية)
 * - إدارة كاملة للصور والنموذج
 * - مزامنة تلقائية ويدوية
 * 
 * ✅ الفصل بين المسؤوليات:
 * - IdentityFormManager: إدارة النموذج والمسودة
 * - IdentityPhotoManager: إدارة الصور
 * - IdentitySyncManager: إدارة المزامنة
 * - PhotosAdapter: عرض الصور
 * - BarcodeGenerator: توليد الباركود
 * - PricingCalculator: حساب الأسعار
 * - TagPrinter: طباعة الملصقات
 * - MiraExecutorService: مدير الخيوط المشترك
 * - SyncLogFragment: عرض سجل المزامنة التفاعلي
 */
public class MiraIdentityStudioFragment extends MiraBaseFragment {

    private static final String TAG = "MiraIdentityStudio";
    private static final long RFID_TIMEOUT_MS = 30000;
    private final Runnable autoSyncRunnable = this::performAutoSync;

    private FragmentMiraIdentityStudioBinding binding;
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private MiraPriceManager priceManager;
    private PermissionManager permissionManager;
    
    private Runnable onResumeRunnable;
    // ✅ المديرون
    private IdentityFormManager formManager;
    private IdentityPhotoManager photoManager;
    private IdentitySyncManager syncManager;
    private PhotosAdapter photosAdapter;

    // ✅ مدير الخيوط المشترك
    private MiraExecutorService miraExecutor;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable rfidTimeoutRunnable;
    private boolean isRfidScanning = false;
    private volatile boolean isFormLocked = false;
    private boolean isArabic = true;

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

        // ✅ استخدام post بدون تأخير صناعي
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
                        restoreDraftIfExists();
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
        if (getContext() != null) {
            apiClient = MiraApiClient.getInstance(getContext());
            branchesManager = MiraBranchesManager.getInstance(getContext());
            orgProfile = MiraOrgProfileManager.getInstance(getContext());
            priceManager = MiraPriceManager.getInstance(getContext());
            permissionManager = PermissionManager.getInstance(getContext());

            // ✅ مدير الخيوط المشترك
            miraExecutor = MiraExecutorService.getInstance();

            formManager = new IdentityFormManager(getContext());
            photoManager = new IdentityPhotoManager(getContext(), miraExecutor.getBackgroundExecutor());
            syncManager = new IdentitySyncManager(getContext(), miraExecutor.getBackgroundExecutor());
        }
        isArabic = Locale.getDefault().getLanguage().equals("ar");
    }

    // ==================== إدارة النموذج ====================

    private void restoreDraftIfExists() {
    if (binding == null || formManager == null) {
        Log.w(TAG, "restoreDraftIfExists skipped: binding or formManager is null");
        return;
    }

    try {
        boolean restored = formManager.restoreDraft(
            new IdentityFormManager.FormData() {

                // ==================== Getters (تُقرأ من main thread) ====================
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

                // ==================== Setters (تُنفَّذ في main thread عبر safeRun) ====================
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
            photoManager != null ? photoManager.getPhotoPaths() : new ArrayList<>()
        );

        if (restored) {
            safeRun(() -> {
                if (!isAdded() || binding == null) return;
                showToast(isArabic ? "📝 تم استعادة المسودة المحفوظة" : "📝 Draft restored");
                onPhotosUpdated();
            });
        }
    } catch (Exception e) {
        Log.e(TAG, "restoreDraftIfExists error: " + e.getMessage(), e);
    }
}
    private void saveCurrentDraftAsync() {
    final FragmentMiraIdentityStudioBinding b = binding;   // ← احتفظ بمرجع محلي
    if (formManager == null || b == null) return;

    formManager.saveDraft(new IdentityFormManager.FormData() {
        @Override public String getEpc() { return safeGet(b.etEpc); }
        @Override public String getTitle() { return safeGet(b.etTitle); }
        @Override public String getGrossWeight() { return safeGet(b.etGrossWeight); }
        @Override public boolean isHasStones() { return b.switchHasStones != null && b.switchHasStones.isChecked(); }
        @Override public String getStoneType() { return safeGet(b.etStoneType); }
        @Override public String getStoneWeight() { return safeGet(b.etStoneWeight); }
        @Override public String getStoneCount() { return safeGet(b.etStoneCount); }
        @Override public String getStoneValue() { return safeGet(b.etStoneValue); }
        // setters: فارغة لأنها read-only
        @Override public void setEpc(String v) {}
        @Override public void setTitle(String v) {}
        @Override public void setGrossWeight(String v) {}
        @Override public void setStoneType(String v) {}
        @Override public void setStoneWeight(String v) {}
        @Override public void setStoneCount(String v) {}
        @Override public void setStoneValue(String v) {}
        @Override public void setHasStones(boolean v) {}
    }, photoManager != null ? photoManager.getPhotoPaths() : new ArrayList<>());
}

private String safeGet(android.widget.TextView tv) {
    return tv != null && tv.getText() != null ? tv.getText().toString() : "";
}

    // ==================== إدارة الصور ====================

    private void setupPhotosRecyclerView() {
        if (binding == null || binding.rvPhotosPreview == null) return;
        photosAdapter = new PhotosAdapter(photoManager.getPhotoPaths(), 
                miraExecutor.getImageExecutor(), position -> {
            if (isFormLocked) {
                showToast(isArabic ? "⛔ النموذج مقفل" : "⛔ Form is locked");
                return;
            }
            photoManager.removePhoto(position);
            photosAdapter.notifyItemRemoved(position);
            photosAdapter.notifyItemRangeChanged(position, photoManager.getPhotoCount());
            updatePhotosCountText();
        });
        binding.rvPhotosPreview.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        binding.rvPhotosPreview.setAdapter(photosAdapter);
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
                    if (success) {
                        miraExecutor.getImageExecutor().execute(() -> {
                            photoManager.compressAndAddPhoto(currentPhotoPath);
                            safeRun(this::onPhotosUpdated);
                        });
                    }
                }
        );

        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                uris -> {
                    if (uris != null && !uris.isEmpty()) {
                        miraExecutor.getImageExecutor().execute(() -> {
                            for (Uri uri : uris) {
                                photoManager.copyAndAddPhoto(uri);
                            }
                            safeRun(this::onPhotosUpdated);
                        });
                    }
                }
        );
    }

    private void onPhotosUpdated() {
        if (photosAdapter != null) photosAdapter.notifyDataSetChanged();
        updatePhotosCountText();
        if (binding != null && binding.rvPhotosPreview != null) {
            binding.rvPhotosPreview.setVisibility(
                    photoManager.getPhotoCount() == 0 ? View.GONE : View.VISIBLE);
        }
    }

    private void updatePhotosCountText() {
        try {
            if (binding == null || binding.tvPhotosCount == null) return;
            int count = photoManager.getPhotoCount();
            binding.tvPhotosCount.setText(count == 0 ?
                    (isArabic ? "لم يتم إرفاق صور بعد" : "No photos attached yet") :
                    (isArabic ? "تم إرفاق (" + count + "/" + 5 + ") صور" : 
                                "Attached (" + count + "/" + 5 + ") photos"));
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

    // ==================== [يتبع في المرحلة 2] ====================


// ==================== المستمعون ====================

private void setupListeners() {
    try {
        if (binding == null) return;

        binding.etEpc.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (binding != null) {
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
            if (photoManager.getPhotoCount() >= 5) {
                showToast(isArabic ? "⚠️ الحد الأقصى 5 صور" : "⚠️ Maximum 5 photos");
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
                showToast(isArabic ? "⛔ العملية قيد التنفيذ، يرجى الانتظار..." : 
                                   "⛔ Operation in progress...");
                return;
            }
            if (permissionManager.canEdit("identity") || permissionManager.isManager()) {
                finalizeItemIdentity();
            } else {
                showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            }
        });

        // ✅ ربط زر "عرض الطلبات المحفوظة" بشاشة سجل المزامنة الجديدة
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

private void activateRFIDModeForEncoding() {
    if (!(permissionManager.canEdit("identity") || permissionManager.isManager())) {
        showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
        return;
    }

    try {
        if (getActivity() instanceof UHFMainActivity) {
            UHFMainActivity activity = (UHFMainActivity) getActivity();
            isRfidScanning = true;
            if (binding != null) {
                binding.btnScanRfid.setText(isArabic ? "إيقاف المسح..." : "Stop Scan...");
                binding.tvStatusBadge.setText("SCANNING...");
            }

            activity.setActiveRfidListener(epc -> {
                final String normalizedEpc = epc != null ?
                        epc.trim().toUpperCase(Locale.US) : "";

                // ✅ المرحلة 1: قراءة TID + التحقق من مخزون SunlandRFID
                miraExecutor.getBackgroundExecutor().execute(() -> {
                    MiraRfidService rfidService = MiraRfidService.getInstance(getContext());
                    final String tid = rfidService.readTid(null);
                    final boolean isSim = rfidService.isSimulationMode();

                    // ✅ التحقق من أن الشريحة معتمدة
                    boolean isTagAllowed = isSim || rfidService.verifyTagAllowed(tid);

                    // ✅ التحقق من عدم التكرار
                    boolean isDuplicate = syncManager.isEpcDuplicateLocally(normalizedEpc);

                    safeRun(() -> {
                        // فحص أول: شريحة غير معتمدة
                        if (!isTagAllowed) {
                            playErrorSound();
                            showErrorDialog(
                                isArabic ? "⛔ شريحة غير معتمدة" : "⛔ Unauthorized Tag",
                                isArabic ?
                                    "هذه الشريحة (TID: " + tid + ") غير مسجلة في مخزون الشرائح المعتمدة.\n\n" +
                                    "يرجى استخدام شريحة من مورد معتمد (SunlandRFID)." :
                                    "This tag (TID: " + tid + ") is not in the authorized inventory.\n\n" +
                                    "Please use a tag from an approved supplier."
                            );
                            stopRfidScanningMode();
                            return;
                        }

                        // فحص ثانٍ: شريحة مكررة محلياً
                        if (isDuplicate) {
                            playErrorSound();
                            showErrorDialog(
                                isArabic ? "شريحة مكررة محلياً" : "Duplicate Local Tag",
                                isArabic ?
                                    "هذه الشريحة (" + normalizedEpc + ") مسجلة بالفعل." :
                                    "This tag (" + normalizedEpc + ") is already registered."
                            );
                            stopRfidScanningMode();
                            return;
                        }

                        // ✅ نجاح: تعبئة EPC و TID
                        if (binding != null && binding.etEpc != null) {
                            binding.etEpc.setText(normalizedEpc);
                            binding.tvStatusBadge.setText("BOUND");
                        }

                        // ✅ إخفاء TID في مكان ما (اختياري)
                        Log.i(TAG, "✅ Tag verified: EPC=" + normalizedEpc + " TID=" + tid);

                        playSuccessSound();
                        stopRfidScanningMode();
                    });
                });
            });

            activity.startUHFScan();
            showToast(isArabic ? "📡 جاري البحث عن شريحة RFID..." : "📡 Searching for RFID tag...");

            if (rfidTimeoutRunnable != null) handler.removeCallbacks(rfidTimeoutRunnable);
            rfidTimeoutRunnable = this::stopRfidScanningMode;
            handler.postDelayed(rfidTimeoutRunnable, RFID_TIMEOUT_MS);
        }
    } catch (Exception e) {
        Log.e(TAG, "activateRFIDMode error: " + e.getMessage(), e);
    }
}

private void stopRfidScanningMode() {
    try {
        isRfidScanning = false;
        if (rfidTimeoutRunnable != null) {
            handler.removeCallbacks(rfidTimeoutRunnable);
            rfidTimeoutRunnable = null;
        }
        if (getActivity() instanceof UHFMainActivity) {
            UHFMainActivity activity = (UHFMainActivity) getActivity();
            activity.stopUHFScan();
            activity.setActiveRfidListener(null);
        }
        if (binding != null && binding.btnScanRfid != null) {
            binding.btnScanRfid.setText(isArabic ? "مسح رمز RFID" : "Scan RFID Tag");
            if ("SCANNING...".equals(binding.tvStatusBadge.getText().toString())) {
                binding.tvStatusBadge.setText("WAITING");
            }
        }
    } catch (Exception e) {
        Log.e(TAG, "stopRfidScanningMode error: " + e.getMessage(), e);
    }
}

public void onRfidScanned(String epc) {
    if (!isAdded() || getActivity() == null) return;
    final String normalizedEpc = epc != null ? 
            epc.trim().toUpperCase(Locale.US) : "";

    miraExecutor.getBackgroundExecutor().execute(() -> {
        // ✅ قراءة TID + التحقق من مخزون SunlandRFID
        MiraRfidService rfidService = MiraRfidService.getInstance(getContext());
        final String tid = rfidService.readTid(null);  // ✅ null
        final boolean isSim = rfidService.isSimulationMode();
        
        boolean isTagAllowed = isSim || rfidService.verifyTagAllowed(tid);
        boolean isDuplicate = syncManager.isEpcDuplicateLocally(normalizedEpc);

        safeRun(() -> {
            if (binding == null) return;
            
            // ✅ تحقق أول: شريحة معتمدة
            if (!isTagAllowed) {
                playErrorSound();
                showErrorDialog(
                    isArabic ? "⛔ شريحة غير معتمدة" : "⛔ Unauthorized Tag",
                    isArabic ? "هذه الشريحة (TID: " + tid + ") غير مسجلة." :
                               "This tag (TID: " + tid + ") is not authorized."
                );
                stopRfidScanningMode();
                return;
            }
            
            // ✅ تحقق ثاني: عدم التكرار
            if (isDuplicate) {
                playErrorSound();
                showErrorDialog(
                    isArabic ? "شريحة مكررة محلياً" : "Duplicate Local Tag",
                    isArabic ? "هذه الشريحة مسجلة بالفعل." : 
                               "This tag is already registered."
                );
                stopRfidScanningMode();
                return;
            }

            binding.etEpc.setText(normalizedEpc);
            binding.tvStatusBadge.setText("BOUND");
            playSuccessSound();
            stopRfidScanningMode();
        });
    });
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
        // ✅ منع التراكم
        if (fm.findFragmentByTag("barcode_scanner_tag") != null) {
            Log.w(TAG, "Scanner already open");
            return;
        }

        MiraBarcodeScannerFragment sf = new MiraBarcodeScannerFragment();
        sf.setOnBarcodeScannedListener((data, format) -> {
            if (binding != null && binding.etEpc != null) {
                binding.etEpc.setText(data);
                binding.tvStatusBadge.setText("BOUND");
            }
            if (fm.getBackStackEntryCount() > 0) fm.popBackStack();
        });

        View view = getView();
        int cid = (view != null && view.getParent() instanceof View)
                ? ((View) view.getParent()).getId()
                : android.R.id.content;

        fm.beginTransaction()
                .setReorderingAllowed(true)   // ✅ مهم
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
        if (binding == null) {
            unlockFormFast();
            return;
        }

        // ==================== 1) قراءة البيانات من النموذج ====================
        String epc = binding.etEpc.getText() != null ?
                binding.etEpc.getText().toString().trim() : "";
        String title = binding.etTitle.getText() != null ?
                binding.etTitle.getText().toString().trim() : "";
        String weightStr = binding.etGrossWeight.getText() != null ?
                binding.etGrossWeight.getText().toString().trim() : "";

        if (!validateInputData(epc, title, weightStr)) {
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

        final AlertDialog loadingDialog = showProfessionalLoadingDialog(
                isArabic ? "🔄 جاري التكويد وإنشاء الأكواد المجهرية..." :
                           "🔄 Encoding & Generating Micro-Codes...",
                isArabic ? "يرجى الانتظار - يتم الآن تسجيل القطعة في قاعدة البيانات" :
                           "Please wait - saving item in database"
        );

        // ==================== 3) تثبيت المتغيرات للمعالجة الخلفية ====================
        final String finalEpc = epc;
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
        final List<String> finalPhotoPaths = new ArrayList<>(photoManager.getPhotoPaths());
        final int currentUserId = getCurrentUserId();

        MiraRfidService rfidService = MiraRfidService.getInstance(getContext());
        final String tid = rfidService.readTid(null);
        final boolean isSim = rfidService.isSimulationMode();

        // ✅ كلمة المرور الافتراضية للشريحة (بديل null)
        //final String DEFAULT_PWD = "00000000";

        // ==================== 4) إرسال البيانات للسيرفر ====================
        miraExecutor.getBackgroundExecutor().execute(() -> {
            try {
                JSONObject payload = buildPayload(
                    finalEpc, finalTitle, selectedKarat, fGrossWeight, netGoldWeight,
                    fStoneWeightCarats, fStoneCount, fStoneValue, fStoneType,
                    details, selectedKaratNum, finalPriceUSD, finalPriceLocal,
                    localCurrency, exchangeRate, selectedCategory, location,
                    branchId, currentUserId, tid, isSim, finalPhotoPaths
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
                        dismissDialog(loadingDialog);

                        if (networkError) {
                            // حفظ offline
                            miraExecutor.getSingleThreadExecutor().execute(() -> {
                                syncManager.saveEncodeOffline(payload);
                                safeRun(this::updatePendingSyncBadge);
                            });
                            showToast(isArabic ? "💾 تم الحفظ محلياً (أوفلاين)" :
                                               "💾 Saved locally (Offline)");
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

                // ==================== 6) نجاح السيرفر → كتابة EPC على الشريحة ====================
                Log.i(TAG, "✅ Server OK — Starting hardware encoding sequence");
                Log.i(TAG, "📡 TID=" + tid + " | EPC_original=" + finalEpc + " | SIM=" + isSim);

                // استخراج mira_id من الاستجابة
                String miraId = (finalResponseData != null) ?
                        finalResponseData.optString("mira_id", finalEpc) : finalEpc;

                // توليد EPC الجديد
                String newEpc = rfidService.generateEpcFromMiraId(miraId);
                Log.i(TAG, "🎯 Generated new EPC: " + newEpc + " (from mira_id=" + miraId + ")");

                // ──────── 6.1: كتابة EPC ────────
                boolean writeOk = rfidService.writeEpc(newEpc, null);
                boolean alreadyEncoded = false;

                // ✅ اكتشاف الشرائح المبرمجة مسبقاً (Pre-encoded)
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

                // ──────── 6.2: التحقق من الكتابة ────────
                boolean verifyOk = writeOk && rfidService.verifyWrite(newEpc, null);
                if (verifyOk) {
                    Log.i(TAG, "✅ verifyWrite OK — EPC confirmed on tag: " + newEpc);
                } else if (writeOk) {
                    Log.w(TAG, "⚠️ verifyWrite failed — EPC may not be stable");
                }

                // ──────── 6.3: قفل EPC Bank ────────
                boolean lockOk = false;
                if (verifyOk && !alreadyEncoded) {
                    lockOk = rfidService.setTagLockState(null, 1, true);
                    if (lockOk) {
                        Log.i(TAG, "🔒 EPC Bank locked successfully");
                    } else {
                        Log.w(TAG, "⚠️ setTagLockState failed — tag remains writable");
                    }
                } else if (alreadyEncoded) {
                    Log.i(TAG, "ℹ️ Skipped lock — tag was pre-encoded (may already be locked)");
                    lockOk = true; // اعتبرها محمية ضمنياً
                }

                // ──────── 6.4: إبلاغ السيرفر بنجاح البرمجة ────────
                if (lockOk || verifyOk) {
                    try {
                        boolean marked = rfidService.markTagProgrammed(tid, miraId, newEpc);
                        Log.i(TAG, "📤 markTagProgrammed: " + (marked ? "OK" : "FAILED"));
                    } catch (Exception e) {
                        Log.w(TAG, "markTagProgrammed exception", e);
                    }
                }

                final boolean finalWrite = writeOk;
                final boolean finalVerify = verifyOk;
                final boolean finalLock = lockOk;
                final boolean finalWasPreEncoded = alreadyEncoded;
                final String finalNewEpc = newEpc;

                // ==================== 7) تحديث السيرفر بـ RFID النهائي ====================
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
                        } else {
                            Log.w(TAG, "⚠️ RFID metadata sync failed");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to update RFID metadata", e);
                    }
                }

                // ==================== 8) عرض النتائج ====================
                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    playSuccessSound();

                    // إزالة المسودة
                    miraExecutor.getSingleThreadExecutor().execute(() ->
                            syncManager.removePendingEncode(finalEpc));
                    if (formManager != null) formManager.clearDraft();

                    notifyDashboardUpdate("encode_success");

                    // رفع الصور
                    if (!finalPhotoPaths.isEmpty() && finalServerItemId > 0) {
                        uploadItemImages(finalServerItemId, finalPhotoPaths);
                    }

                    // EPC النهائي للعرض
                    String displayEpc = finalWrite ? finalNewEpc : finalEpc;

                    // توليد GS1 و QR
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

                    // ✅ رسائل الحالة الدقيقة
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
                            finalPriceLocal, localCurrency, finalPhotoPaths
                    );
                });

            } catch (Exception e) {
                Log.e(TAG, "Background execution error", e);
                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    unlockFormFast();
                    setFormEnabled(true);
                    showErrorDialog(isArabic ? "خطأ للنظام" : "System Error", e.getMessage());
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
    List<String> photoPathsList) throws Exception {

    JSONObject payload = new JSONObject();

    // ==================== 1) البيانات الأساسية للقطعة ====================
    if (epc != null && !epc.isEmpty()) {
        payload.put("epc", epc);
    }
    payload.put("title", title);
    payload.put("purity_code", karat);
    payload.put("category", category);
    payload.put("location", location);
    payload.put("branch_id", branchId);
    payload.put("user_id", userId);

    // ==================== 2) الأوزان ====================
    payload.put("gross_weight", grossWeight);
    payload.put("net_weight", netWeight);
    payload.put("stone_weight", stoneWeightCarats);
    payload.put("stone_count", stoneCount);
    payload.put("stone_value", stoneValue);
    payload.put("stone_type", stoneType);

    // ==================== 3) تفاصيل التسعير ====================
    payload.put("gold_price_per_gram", details.gramPrice);
    payload.put("gold_value", details.baseGoldCost);
    payload.put("making_per_gram", priceManager.getMakingCharge(karatNum));
    payload.put("making_charge", details.totalMakingCost);
    payload.put("total_cost", details.baseGoldCost + details.totalMakingCost);
    payload.put("profit_margin", priceManager.getProfitMargin(karatNum));
    payload.put("profit_amount", details.profitAmount);

    // ==================== 4) الأسعار النهائية والعملة ====================
    payload.put("retail_price_usd", priceUSD);
    payload.put("retail_price", priceUSD);
    payload.put("retail_price_local", priceLocal);
    payload.put("currency", "USD");
    payload.put("local_currency", currency);
    payload.put("exchange_rate", exchangeRate);
    payload.put("currency_symbol", priceManager.getCurrencySymbol(currency));

    // ==================== 5) بيانات RFID (مُوحَّدة) ====================
    if (tid != null && !tid.isEmpty()) {
        payload.put("rfid_tid", tid);
    }
    payload.put("rfid_sim_mode", isSim);
    payload.put("rfid_epc_original", epc);   // EPC الأصلي على الشريحة قبل الكتابة
    payload.put("rfid_epc_final", "");       // سيُحدَّث لاحقاً عبر /items/update-rfid
    payload.put("rfid_locked", false);       // سيُحدَّث بعد القفل
    payload.put("rfid_verified", false);     // سيُحدَّث بعد التحقق

    // ==================== 6) صور القطعة ====================
    if (!photoPathsList.isEmpty()) {
        JSONArray imagesArray = new JSONArray();
        for (String path : photoPathsList) {
            imagesArray.put(path);
        }
        payload.put("image_paths", imagesArray);
    }

    return payload;
}

    // ==================== ✅ المزامنة الاحترافية ====================

    /**
     * ✅ فتح شاشة سجل المزامنة التفاعلية
     */
    private void openSyncLogFragment() {
    try {
        if (!isAdded() || getActivity() == null) return;

        FragmentManager fm = getParentFragmentManager();
        if (fm.isStateSaved()) return;
        // ✅ منع التراكم
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

private void openRfidSettings() {
    try {
        if (!isAdded() || getActivity() == null) return;

        FragmentManager fm = getParentFragmentManager();
        if (fm.isStateSaved()) return;
        if (fm.findFragmentByTag("rfid_settings_tag") != null) return;

        MiraRfidSettingsFragment settingsFragment = new MiraRfidSettingsFragment();

        View view = getView();
        int cid = (view != null && view.getParent() instanceof View)
                ? ((View) view.getParent()).getId()
                : android.R.id.content;

        fm.beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(
                        android.R.anim.fade_in, android.R.anim.fade_out,
                        android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(cid, settingsFragment, "rfid_settings_tag")
                .addToBackStack("rfid_settings")
                .commit();
    } catch (Exception e) {
        Log.e(TAG, "openRfidSettings error: " + e.getMessage(), e);
    }
}


    /**
     * ✅ جدولة مزامنة تلقائية بعد حفظ Offline
     */
    private void scheduleAutoSync() {
    if (syncManager == null || !isAdded()) return;
    // ✅ إزالة أي جدولة سابقة
    handler.removeCallbacks(autoSyncRunnable);
    handler.postDelayed(autoSyncRunnable, 3000);
    Log.d(TAG, "🔄 Auto-sync scheduled in 3s");
}
    /**
     * ✅ تنفيذ المزامنة التلقائية
     */
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
                        safeRun(() -> {
                            if (syncedCount > 0) {
                                Log.i(TAG, "✅ Auto-synced " + syncedCount + " encodings");
                                updatePendingSyncBadge();
                                
                                if (isAdded() && getContext() != null) {
                                    showToast(isArabic ? 
                                            "✅ تمت مزامنة " + syncedCount + " تكويد" : 
                                            "✅ Synced " + syncedCount + " encodings");
                                }
                                
                                notifyDashboardUpdate("sync_complete");
                            }
                        });
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

    /**
     * ✅ تحديث شارة المزامنة
     */
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
            for (String path : imagePaths) {
                File imageFile = new File(path);
                if (!imageFile.exists()) continue;
                try {
                    boolean isPrimary = (imagePaths.indexOf(path) == 0);
                    MiraApiClient.ApiResponse response = 
                            apiClient.uploadItemImage(itemId, imageFile, isPrimary, "Image");
                    if (response != null && response.isSuccess) uploaded++;
                } catch (Exception ignored) {
                    Log.e(TAG, "Failed to upload image: " + path, ignored);
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
        List<String> photos) {

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
        if (tvIdMetalKarat != null) tvIdMetalKarat.setText((isArabic ? "عيار " : "Karat ") + karat);
        if (tvIdWeight != null) tvIdWeight.setText(weight + (isArabic ? " جرام" : " g"));
        if (tvIdPrice != null && priceManager != null) {
            tvIdPrice.setText(priceManager.formatPriceDefault(finalPrice) + " " + currency);
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
                resetFormForNextScan();
            });
        }

        successDialog.show();
    }

    // ==================== إعادة تعيين النموذج ====================

    private void resetFormForNextScan() {
        try {
            unlockFormFast();
            setFormEnabled(true);

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

            photoManager.clearPhotos();
            onPhotosUpdated();

            if (formManager != null) {
                formManager.clearDraft();
            }

            activateRFIDModeForEncoding();
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
            if (getContext() == null) return 0;
            SharedPreferences prefs = getContext().getSharedPreferences(
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
                // ✅ فحص صارم ثلاثي
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
    if (!isAdded() || binding == null || getView() == null) return;

    try {
        if (priceManager != null) {
            priceManager.refreshNow();
            calculateEstimatedGoldValue();
        }

        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setActiveEncodingFragment(this);
        }

        scheduleAutoSync();
    } catch (Exception e) {
        Log.e(TAG, "onResume error: " + e.getMessage(), e);
    }
}

@Override
public void onPause() {
    super.onPause();
    // ✅ إلغاء المهمة المعلّقة
    View v = getView();
    if (v != null && onResumeRunnable != null) {
        v.removeCallbacks(onResumeRunnable);
        onResumeRunnable = null;
    }
    stopRfidScanningMode();
    saveCurrentDraftAsync();
    if (getActivity() instanceof UHFMainActivity) {
        ((UHFMainActivity) getActivity()).setActiveEncodingFragment(null);
    }
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

@Override
public void onDestroyView() {
    Log.d(TAG, "onDestroyView: cleaning up");

    // 1) إيقاف RFID
    stopRfidScanningMode();

    // 2) إزالة كل callbacks من handler
    if (handler != null) {
        handler.removeCallbacksAndMessages(null);
    }
    if (rfidTimeoutRunnable != null) {
        rfidTimeoutRunnable = null;
    }

    // 3) فصل مرجع النشاط
    if (getActivity() instanceof UHFMainActivity) {
        try {
            ((UHFMainActivity) getActivity()).setActiveEncodingFragment(null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear active encoding fragment", e);
        }
    }

    // 4) ✅ تنظيف RecyclerView بالكامل
    if (binding != null && binding.rvPhotosPreview != null) {
        binding.rvPhotosPreview.setAdapter(null);
        binding.rvPhotosPreview.setLayoutManager(null);
        binding.rvPhotosPreview.clearOnScrollListeners();
    }

    // 5) ✅ تفريغ الـ Adapter
    if (photosAdapter != null) {
        photosAdapter.clearListeners();  // سنضيف هذه الدالة في الـ Adapter
        photosAdapter = null;
    }

    // 6) إلغاء الـ Runnable المعلّق في onResume
    View v = getView();
    if (v != null && onResumeRunnable != null) {
        v.removeCallbacks(onResumeRunnable);
        onResumeRunnable = null;
    }

    // 7) أخيراً: إلغاء binding
    binding = null;

    super.onDestroyView();
}
} // ✅ نهاية الكلاس MiraIdentityStudioFragment
