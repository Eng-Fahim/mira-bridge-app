package com.example.uhf.fragment;

import android.Manifest;
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
import com.example.uhf.utils.PricingCalculator;
import com.example.uhf.utils.TagPrinter;
import com.example.uhf.MiraRfid.MiraRfidService;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * MiraIdentityStudioFragment - نسخة نظيفة ومنظمة
 * 
 * ✅ الفصل بين المسؤوليات:
 * - IdentityFormManager: إدارة النموذج والمسودة
 * - IdentityPhotoManager: إدارة الصور
 * - IdentitySyncManager: إدارة المزامنة
 * - PhotosAdapter: عرض الصور
 * - BarcodeGenerator: توليد الباركود
 * - PricingCalculator: حساب الأسعار
 * - TagPrinter: طباعة الملصقات
 */
public class MiraIdentityStudioFragment extends MiraBaseFragment {

    private static final String TAG = "MiraIdentityStudio";
    private static final long RFID_TIMEOUT_MS = 30000;

    private FragmentMiraIdentityStudioBinding binding;
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private MiraPriceManager priceManager;
    private PermissionManager permissionManager;

    // ✅ المديرون الجدد
    private IdentityFormManager formManager;
    private IdentityPhotoManager photoManager;
    private IdentitySyncManager syncManager;
    private PhotosAdapter photosAdapter;

    private ExecutorService executorService;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable rfidTimeoutRunnable;
    private boolean isRfidScanning = false;
    private volatile boolean isFormLocked = false;
    private boolean isArabic = true;

    private ActivityResultLauncher<Uri> takePhotoLauncher;
    private ActivityResultLauncher<String[]> pickImageLauncher;
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private Uri photoUri;

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
            initializeComponents();
            isArabic = Locale.getDefault().getLanguage().equals("ar");
            setupPhotoLaunchers();
            applyLanguage();
            setupPhotosRecyclerView();

            // تأجيل المهام الخفيفة
            view.postDelayed(() -> {
                if (!isAdded()) return;

                try {
                    setupListeners();
                    applyPermissions();

                    if (savedInstanceState != null) {
                        // استعادة الحالة
                    }

                    if (getActivity() instanceof UHFMainActivity) {
                        ((UHFMainActivity) getActivity()).setActiveEncodingFragment(this);
                    }

                    executorService.execute(() -> {
                        restoreDraftIfExists();
                        safeRun(this::updatePendingSyncBadge);
                    });

                    Log.d(TAG, "✅ IdentityStudio initialization complete");
                } catch (Exception e) {
                    Log.e(TAG, "Delayed init error: " + e.getMessage(), e);
                }
            }, 100);

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

            executorService = Executors.newSingleThreadExecutor();
            formManager = new IdentityFormManager(getContext());
            photoManager = new IdentityPhotoManager(getContext(), executorService);
            syncManager = new IdentitySyncManager(getContext(), executorService);
        }
        isArabic = Locale.getDefault().getLanguage().equals("ar");
    }

    // ==================== إدارة النموذج ====================

    private void restoreDraftIfExists() {
        boolean restored = formManager.restoreDraft(new IdentityFormManager.FormData() {
            @Override public String getEpc() { return binding.etEpc.getText().toString(); }
            @Override public String getTitle() { return binding.etTitle.getText().toString(); }
            @Override public String getGrossWeight() { return binding.etGrossWeight.getText().toString(); }
            @Override public boolean isHasStones() { return binding.switchHasStones.isChecked(); }
            @Override public String getStoneType() { return binding.etStoneType.getText().toString(); }
            @Override public String getStoneWeight() { return binding.etStoneWeight.getText().toString(); }
            @Override public String getStoneCount() { return binding.etStoneCount.getText().toString(); }
            @Override public String getStoneValue() { return binding.etStoneValue.getText().toString(); }
            @Override public void setEpc(String value) { binding.etEpc.setText(value); }
            @Override public void setTitle(String value) { binding.etTitle.setText(value); }
            @Override public void setGrossWeight(String value) { binding.etGrossWeight.setText(value); }
            @Override public void setStoneType(String value) { binding.etStoneType.setText(value); }
            @Override public void setStoneWeight(String value) { binding.etStoneWeight.setText(value); }
            @Override public void setStoneCount(String value) { binding.etStoneCount.setText(value); }
            @Override public void setStoneValue(String value) { binding.etStoneValue.setText(value); }
            @Override public void setHasStones(boolean value) { binding.switchHasStones.setChecked(value); }
        }, photoManager.getPhotoPaths());

        if (restored) {
            safeRun(() -> {
                showToast(isArabic ? "📝 تم استعادة المسودة المحفوظة" : "📝 Draft restored");
                onPhotosUpdated();
            });
        }
    }

    private void saveCurrentDraftAsync() {
        if (formManager == null || binding == null) return;

        formManager.saveDraft(new IdentityFormManager.FormData() {
            @Override public String getEpc() { return binding.etEpc.getText().toString(); }
            @Override public String getTitle() { return binding.etTitle.getText().toString(); }
            @Override public String getGrossWeight() { return binding.etGrossWeight.getText().toString(); }
            @Override public boolean isHasStones() { return binding.switchHasStones.isChecked(); }
            @Override public String getStoneType() { return binding.etStoneType.getText().toString(); }
            @Override public String getStoneWeight() { return binding.etStoneWeight.getText().toString(); }
            @Override public String getStoneCount() { return binding.etStoneCount.getText().toString(); }
            @Override public String getStoneValue() { return binding.etStoneValue.getText().toString(); }
            @Override public void setEpc(String value) { }
            @Override public void setTitle(String value) { }
            @Override public void setGrossWeight(String value) { }
            @Override public void setStoneType(String value) { }
            @Override public void setStoneWeight(String value) { }
            @Override public void setStoneCount(String value) { }
            @Override public void setStoneValue(String value) { }
            @Override public void setHasStones(boolean value) { }
        }, photoManager.getPhotoPaths());
    }

    // ==================== إدارة الصور ====================

    private void setupPhotosRecyclerView() {
        if (binding == null || binding.rvPhotosPreview == null) return;
        photosAdapter = new PhotosAdapter(photoManager.getPhotoPaths(), executorService, position -> {
            if (isFormLocked) {
                showToast(isArabic ? "⛔ النموذج مقفل" : "⛔ Form is locked");
                return;
            }
            photoManager.removePhoto(position);
            photosAdapter.notifyItemRemoved(position);
            photosAdapter.notifyItemRangeChanged(position, photoManager.getPhotoCount());
            updatePhotosCountText();
        });
        binding.rvPhotosPreview.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
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
                        photoManager.compressAndAddPhoto(currentPhotoPath);
                        safeRun(this::onPhotosUpdated);
                    }
                }
        );

        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                uris -> {
                    if (uris != null && !uris.isEmpty()) {
                        for (Uri uri : uris) {
                            photoManager.copyAndAddPhoto(uri);
                        }
                        safeRun(this::onPhotosUpdated);
                    }
                }
        );
    }

    private void onPhotosUpdated() {
        if (photosAdapter != null) photosAdapter.notifyDataSetChanged();
        updatePhotosCountText();
        if (binding != null && binding.rvPhotosPreview != null) {
            binding.rvPhotosPreview.setVisibility(photoManager.getPhotoCount() == 0 ? View.GONE : View.VISIBLE);
        }
    }

    private void updatePhotosCountText() {
        try {
            if (binding == null || binding.tvPhotosCount == null) return;
            int count = photoManager.getPhotoCount();
            binding.tvPhotosCount.setText(count == 0 ?
                    (isArabic ? "لم يتم إرفاق صور بعد" : "No photos attached yet") :
                    (isArabic ? "تم إرفاق (" + count + "/" + 5 + ") صور" : "Attached (" + count + "/" + 5 + ") photos"));
        } catch (Exception e) {
            Log.e(TAG, "updatePhotosCountText error: " + e.getMessage());
        }
    }

    // ==================== الحسابات ====================

    private void calculateEstimatedGoldValue() {
        try {
            if (binding == null || binding.etGrossWeight == null) return;

            String weightStr = binding.etGrossWeight.getText() != null ? binding.etGrossWeight.getText().toString().trim() : "";
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
                String sWeight = binding.etStoneWeight.getText() != null ? binding.etStoneWeight.getText().toString().trim() : "";
                String sValue = binding.etStoneValue.getText() != null ? binding.etStoneValue.getText().toString().trim() : "";

                if (!sWeight.isEmpty()) stoneWeightCarats = Double.parseDouble(sWeight);
                if (!sValue.isEmpty()) stoneValue = Double.parseDouble(sValue);
            }

            int karat = getSelectedKaratNumber();
            double[] pricing = PricingCalculator.calculatePricingDetails(grossWeight, stoneWeightCarats, stoneValue, karat, priceManager);
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

            binding.chipGroupKarat.setOnCheckedStateChangeListener((group, checkedIds) -> calculateEstimatedGoldValue());

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
                    showToast(isArabic ? "⛔ العملية قيد التنفيذ، يرجى الانتظار..." : "⛔ Operation in progress...");
                    return;
                }
                if (permissionManager.canEdit("identity") || permissionManager.isManager()) {
                    finalizeItemIdentity();
                } else {
                    showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
                }
            });

            binding.btnViewPending.setOnClickListener(v -> showPendingItemsDialog());

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
                    final String normalizedEpc = epc != null ? epc.trim().toUpperCase(Locale.US) : "";

                    executorService.execute(() -> {
                        boolean isDuplicate = syncManager.isEpcDuplicateLocally(normalizedEpc);
                        safeRun(() -> {
                            if (isDuplicate) {
                                playErrorSound();
                                showErrorDialog(
                                    isArabic ? "شريحة مكررة محلياً" : "Duplicate Local Tag",
                                    isArabic ? "هذه الشريحة (" + normalizedEpc + ") مسجلة بالفعل." : "This tag (" + normalizedEpc + ") is already registered."
                                );
                                stopRfidScanningMode();
                                return;
                            }

                            if (binding != null && binding.etEpc != null) {
                                binding.etEpc.setText(normalizedEpc);
                                binding.tvStatusBadge.setText("BOUND");
                            }
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
        final String normalizedEpc = epc != null ? epc.trim().toUpperCase(Locale.US) : "";

        executorService.execute(() -> {
            boolean isDuplicate = syncManager.isEpcDuplicateLocally(normalizedEpc);
            safeRun(() -> {
                if (binding == null) return;
                if (isDuplicate) {
                    playErrorSound();
                    showErrorDialog(
                        isArabic ? "شريحة مكررة محلياً" : "Duplicate Local Tag",
                        isArabic ? "هذه الشريحة مسجلة بالفعل." : "This tag is already registered."
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
                case "rings": return new String[]{"خاتم سوليتير ألماسي", "خاتم محبس كلاسيك", "خاتم توينز راقي", "خاتم خطوبة مميز"};
                case "sets": return new String[]{"طقم شبكة عروس كامل", "طقم ملكي مرصع بالألماس", "طقم سهرة فاخر"};
                case "bracelets": return new String[]{"بناجر / أساور خليجي", "سوار تنادي عصري", "سوار ذهبي كلاسيك"};
                case "necklaces": return new String[]{"عقد شوكر عصري", "سلسلة ذهبية ناعمة", "عقد لؤلؤ فاخر"};
            }
        } else {
            switch (typeKey) {
                case "rings": return new String[]{"Diamond Solitaire Ring", "Classic Band Ring", "Twins Ring", "Engagement Ring"};
                case "sets": return new String[]{"Complete Bridal Set", "Royal Diamond Set", "Luxury Evening Set"};
                case "bracelets": return new String[]{"Khaleeji Bangle", "Modern Tennis Bracelet", "Classic Gold Bracelet"};
                case "necklaces": return new String[]{"Modern Choker", "Fine Gold Chain", "Luxury Pearl Necklace"};
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
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
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
                    photoUri = FileProvider.getUriForFile(getContext(), getContext().getApplicationContext().getPackageName() + ".fileprovider", photoFile);
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
            if (!isAdded()) return;

            MiraBarcodeScannerFragment sf = new MiraBarcodeScannerFragment();
            sf.setOnBarcodeScannedListener((data, format) -> {
                if (binding != null && binding.etEpc != null) {
                    binding.etEpc.setText(data);
                    binding.tvStatusBadge.setText("BOUND");
                }
                if (getParentFragmentManager().getBackStackEntryCount() > 0) {
                    getParentFragmentManager().popBackStack();
                }
            });

            View view = getView();
            int cid = (view != null && view.getParent() instanceof View) ? ((View) view.getParent()).getId() : android.R.id.content;
            getParentFragmentManager().beginTransaction()
                    .replace(cid, sf)
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
            showToast(isArabic ? "⛔ العملية قيد التنفيذ، يرجى الانتظار..." : "⛔ Operation in progress...");
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

            String epc = binding.etEpc.getText() != null ? binding.etEpc.getText().toString().trim() : "";
            String title = binding.etTitle.getText() != null ? binding.etTitle.getText().toString().trim() : "";
            String weightStr = binding.etGrossWeight.getText() != null ? binding.etGrossWeight.getText().toString().trim() : "";

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
                stoneType = binding.etStoneType.getText() != null ? binding.etStoneType.getText().toString().trim() : "";
                String stoneWeightStr = binding.etStoneWeight.getText() != null ? binding.etStoneWeight.getText().toString().trim() : "";
                String stoneCountStr = binding.etStoneCount.getText() != null ? binding.etStoneCount.getText().toString().trim() : "";
                String stoneValueStr = binding.etStoneValue.getText() != null ? binding.etStoneValue.getText().toString().trim() : "";

                if (!stoneValueStr.isEmpty()) stoneValue = Double.parseDouble(stoneValueStr);
                if (!stoneWeightStr.isEmpty()) stoneWeightCarats = Double.parseDouble(stoneWeightStr);
                if (!stoneCountStr.isEmpty()) stoneCount = Integer.parseInt(stoneCountStr);
            }

            double stoneWeightGrams = PricingCalculator.caratsToGrams(stoneWeightCarats);
            if (stoneWeightGrams >= grossWeight) {
                showValidationError(isArabic ? "❌ وزن الأحجار لا يمكن أن يتجاوز أو يساوي الوزن الإجمالي للقطعة!" : "❌ Stone weight cannot exceed gross weight!");
                unlockFormFast();
                return;
            }

            int selectedKaratNum = getSelectedKaratNumber();
            double[] pricing = PricingCalculator.calculatePricingDetails(grossWeight, stoneWeightCarats, stoneValue, selectedKaratNum, priceManager);
            final double netGoldWeight = pricing[6];

            MiraPriceManager.PricingDetails details = priceManager.calculatePricingDetails(selectedKaratNum, netGoldWeight);

            final String localCurrency = priceManager.getDefaultCurrency();
            final double finalPriceUSD = details.finalPrice + stoneValue;
            final double finalPriceLocal = priceManager.convertFromUSD(finalPriceUSD, localCurrency);
            final double exchangeRate = priceManager.getExchangeRate(localCurrency);

            setFormEnabled(false);

            final AlertDialog loadingDialog = showProfessionalLoadingDialog(
                    isArabic ? "🔄 جاري التكويد وإنشاء الأكواد المجهرية..." : "🔄 Encoding & Generating Micro-Codes...",
                    isArabic ? "يرجى الانتظار - يتم الآن تسجيل القطعة في قاعدة البيانات" : "Please wait - saving item in database"
            );

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
            final String tid = rfidService.readTid("00000000");
            final boolean isSim = rfidService.isSimulationMode();

            executorService.execute(() -> {
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
                                if (response.isSuccess) {
                                    if (response.data != null) serverItemId = response.data.optInt("item_id", 0);
                                } else {
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

                    safeRun(() -> {
                        dismissDialog(loadingDialog);

                        if (success) {
                            playSuccessSound();
                            executorService.execute(() -> syncManager.removePendingEncode(finalEpc));
                            notifyDashboardUpdate("encode_success");

                            if (!finalPhotoPaths.isEmpty() && finalServerItemId > 0) {
                                uploadItemImages(finalServerItemId, finalPhotoPaths);
                            }

                            String activeEpc = finalEpc;
                            String gs1Code = "(01)0729000000" + (finalServerItemId > 0 ? String.format(Locale.US, "%05d", finalServerItemId) : "00000") + "(21)" + activeEpc;
                            String qrUrl = "https://ibreg.org/id/" + (finalServerItemId > 0 ? finalServerItemId : activeEpc);

                            if (finalResponseData != null) {
                                activeEpc = finalResponseData.optString("epc", activeEpc);
                                JSONObject gs1Obj = finalResponseData.optJSONObject("gs1");
                                if (gs1Obj != null) {
                                    gs1Code = gs1Obj.optString("raw_fnc1", gs1Code);
                                    qrUrl = gs1Obj.optString("digital_link", qrUrl);
                                }
                            }

                            if (binding != null && binding.etEpc != null) {
                                binding.etEpc.setText("");
                            }

                            showEncodingSuccessDialogEnhanced(
                                    finalTitle, activeEpc, tid, gs1Code, qrUrl,
                                    String.valueOf(fGrossWeight), selectedKarat,
                                    finalPriceLocal, localCurrency, finalPhotoPaths
                            );

                        } else if (networkError) {
                            executorService.execute(() -> {
                                syncManager.saveEncodeOffline(payload);
                                safeRun(this::updatePendingSyncBadge);
                            });
                            showToast(isArabic ? "💾 تم الحفظ محلياً (أوفلاين)" : "💾 Saved locally (Offline)");
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
                } catch (Exception e) {
                    Log.e(TAG, "Background execution error: " + e.getMessage(), e);
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
                    if (displayMessage.isEmpty()) displayMessage = message.isEmpty() ? messageEn : message;

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
                showValidationError(isArabic ? "يجب أن يكون الوزن أكبر من صفر" : "Weight must be greater than zero");
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
        if (epc != null && !epc.isEmpty()) payload.put("epc", epc);

        payload.put("title", title);
        payload.put("purity_code", karat);
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

        payload.put("category", category);
        payload.put("location", location);
        payload.put("branch_id", branchId);
        payload.put("user_id", userId);

        if (tid != null && !tid.isEmpty()) payload.put("rfid_tid", tid);

        if (!photoPathsList.isEmpty()) {
            JSONArray imagesArray = new JSONArray();
            for (String path : photoPathsList) imagesArray.put(path);
            payload.put("image_paths", imagesArray);
        }

        return payload;
    }

    // ==================== المزامنة ====================

    private void showPendingItemsDialog() {
        if (syncManager == null || !isAdded() || getContext() == null) return;

        executorService.execute(() -> {
            try {
                int pendingCount = syncManager.getPendingCount();

                safeRun(() -> {
                    if (!isAdded() || getContext() == null) return;
                    if (pendingCount == 0) {
                        showToast(isArabic ? "لا توجد عناصر معلقة" : "No pending items");
                        return;
                    }

                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(isArabic ? "العناصر المخزنة محلياً" : "Offline Stored Items")
                            .setMessage(isArabic ? "لديك " + pendingCount + " عنصر بانتظار المزامنة" : "You have " + pendingCount + " items waiting for sync")
                            .setPositiveButton(isArabic ? "مزامنة الآن" : "Sync Now", (d, w) -> executorService.execute(() -> {
                                int branchId = (branchesManager != null) ? branchesManager.getActiveBranchId() : 1;
                                syncManager.syncPendingEncodes(branchId, new IdentitySyncManager.SyncCallback() {
                                    @Override
                                    public void onSyncComplete(int syncedCount) {
                                        safeRun(() -> {
                                            if (syncedCount > 0) {
                                                showToast(isArabic ? "✅ تمت مزامنة " + syncedCount + " تكويد" : "✅ Synced " + syncedCount + " encodings");
                                                updatePendingSyncBadge();
                                            }
                                        });
                                    }

                                    @Override
                                    public void onSyncError(String error) {
                                        safeRun(() -> showToast(isArabic ? "❌ فشل المزامنة" : "❌ Sync failed"));
                                    }
                                });
                            }))
                            .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                            .show();
                });
            } catch (Exception e) {
                Log.e(TAG, "showPendingItemsDialog error: " + e.getMessage(), e);
            }
        });
    }

    private void updatePendingSyncBadge() {
        try {
            if (syncManager == null || binding == null) return;
            int count = syncManager.getPendingCount();

            if (binding.tvPendingSyncCount != null) {
                if (count > 0) {
                    binding.tvPendingSyncCount.setVisibility(View.VISIBLE);
                    binding.tvPendingSyncCount.setText(isArabic ? "انتظار المزامنة: " + count : "Pending Sync: " + count);
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

        executorService.execute(() -> {
            int uploaded = 0;
            for (String path : imagePaths) {
                File imageFile = new File(path);
                if (!imageFile.exists()) continue;
                try {
                    boolean isPrimary = (imagePaths.indexOf(path) == 0);
                    MiraApiClient.ApiResponse response = apiClient.uploadItemImage(itemId, imageFile, isPrimary, "Image");
                    if (response != null && response.isSuccess) uploaded++;
                } catch (Exception ignored) {
                    Log.e(TAG, "Failed to upload image: " + path, ignored);
                }
            }

            final int fUploaded = uploaded;
            safeRun(() -> {
                if (fUploaded > 0) {
                    showToast(isArabic ? "📷 تم رفع " + fUploaded + " صورة" : "📷 " + fUploaded + " images uploaded");
                }
            });
        });
    }

    // ==================== حوار النجاح ====================

    private void showEncodingSuccessDialogEnhanced(
        String title, String epc, String tid, String gs1Code, String qrUrl,
        String weight, String karat, double finalPrice, String currency, List<String> photos) {

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

        // توليد الباركود في الخلفية
        executorService.execute(() -> {
            Bitmap dmBitmap = (gs1Code != null && !gs1Code.isEmpty()) ? BarcodeGenerator.generateGS1DataMatrix(gs1Code, 250, 250) : null;
            Bitmap qrBitmap = (qrUrl != null && !qrUrl.isEmpty()) ? BarcodeGenerator.generateMicroQRCode(qrUrl, 250, 250) : null;

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

        // زر الطباعة
        View printView = view.findViewById(R.id.btnPrintTag);
        if (printView instanceof MaterialButton) {
            MaterialButton btnPrintTag = (MaterialButton) printView;
            btnPrintTag.setVisibility(View.VISIBLE);
            btnPrintTag.setOnClickListener(v -> executorService.execute(() -> {
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

        if (binding.btnScanRfid != null) binding.btnScanRfid.setEnabled(canEdit && !isFormLocked);
        if (binding.btnAddPhotos != null) binding.btnAddPhotos.setEnabled(canEdit && !isFormLocked);

        if (!canEdit) {
            showToast(isArabic ? "⛔ وضع القراءة فقط - غير مصرح بالتعديل" : "⛔ Read only mode - Access Denied");
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
            binding.btnViewPending.setText("📦 عرض الطلبات المحفوظة محلياً");
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
            binding.btnViewPending.setText("📦 View Offline Pending Items");
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

            SharedPreferences prefs = ctx.getSharedPreferences("app_refresh_flags", Context.MODE_PRIVATE);
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
            SharedPreferences prefs = getContext().getSharedPreferences("session", Context.MODE_PRIVATE);
            return prefs.getInt("user_id", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    private void safeRun(Runnable r) {
        try {
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (isAdded()) r.run();
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
        if (getView() != null) {
            getView().postDelayed(() -> {
                if (!isAdded()) return;
                try {
                    if (priceManager != null) {
                        priceManager.refreshNow();
                        handler.postDelayed(() -> {
                            if (isAdded() && binding != null) calculateEstimatedGoldValue();
                        }, 300);
                    }

                    if (getActivity() instanceof UHFMainActivity) {
                        ((UHFMainActivity) getActivity()).setActiveEncodingFragment(this);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "onResume error: " + e.getMessage(), e);
                }
            }, 150);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopRfidScanningMode();
        saveCurrentDraftAsync();
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setActiveEncodingFragment(null);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (currentPhotoPath != null) {
            outState.putString("camera_photo_path", currentPhotoPath);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopRfidScanningMode();

        if (executorService != null && !executorService.isShutdown()) {
            try {
                executorService.shutdown();
                if (!executorService.awaitTermination(300, TimeUnit.MILLISECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        handler.removeCallbacksAndMessages(null);
        binding = null;
    }
}
