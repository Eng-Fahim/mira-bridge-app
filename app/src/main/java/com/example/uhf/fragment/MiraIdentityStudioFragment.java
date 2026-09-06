package com.example.uhf.fragment;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
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
import android.view.animation.AccelerateDecelerateInterpolator;
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
import androidx.print.PrintHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.databinding.FragmentMiraIdentityStudioBinding;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.PermissionManager;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.datamatrix.encoder.SymbolShapeHint;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.example.uhf.MiraRfid.MiraRfidService;

public class MiraIdentityStudioFragment extends MiraBaseFragment {

    private static final String TAG = "MiraIdentityStudio";
    private static final String OFFLINE_PREFS = "identity_studio_cache";
    private static final long RFID_TIMEOUT_MS = 30000;
    private static final double CARAT_TO_GRAM_FACTOR = 0.20;
    private static final int MAX_PHOTOS = 5;
    private static final int IMAGE_COMPRESSION_QUALITY = 75;

    private FragmentMiraIdentityStudioBinding binding;
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private MiraPriceManager priceManager;
    private PermissionManager permissionManager;
    private SharedPreferences offlineCache;
    private ExecutorService executorService;

    private final Map<String, String[]> typeSuggestions = new HashMap<>();
    private String currentPhotoPath;
    private final List<String> photoPaths = new ArrayList<>();
    private PhotosAdapter photosAdapter;

    private ActivityResultLauncher<Uri> takePhotoLauncher;
    private ActivityResultLauncher<String[]> pickImageLauncher;
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private Uri photoUri;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable rfidTimeoutRunnable;
    private boolean isRfidScanning = false;
    private volatile boolean isFormLocked = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentMiraIdentityStudioBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    // ✅ تأجيل التهيئة الثقيلة لتجنب التجمد
    view.postDelayed(() -> {
        if (!isAdded()) return;
        
        try {
            initializeComponents();
            initSuggestionsData();
            setupPhotoLaunchers();
            setupPhotosRecyclerView();
            applyLanguage();
            setupListeners();
            applyPermissions();
            populateSuggestions("rings");
            updatePendingSyncBadge();
            restoreDraftIfExists();
            
            if (savedInstanceState != null) {
                currentPhotoPath = savedInstanceState.getString("camera_photo_path");
            }
            
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setActiveEncodingFragment(this);
            }
            
            Log.d(TAG, "✅ IdentityStudio initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error: " + e.getMessage(), e);
        }
    }, 150); // ✅ تأخير 150ms
}

    private void initializeComponents() {
        if (getContext() != null) {
            apiClient = MiraApiClient.getInstance(getContext());
            branchesManager = MiraBranchesManager.getInstance(getContext());
            orgProfile = MiraOrgProfileManager.getInstance(getContext());
            priceManager = MiraPriceManager.getInstance(getContext());
            permissionManager = PermissionManager.getInstance(getContext());
            offlineCache = getContext().getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
        }
        executorService = Executors.newSingleThreadExecutor();
        isArabic = Locale.getDefault().getLanguage().equals("ar");
    }

    private void restoreDraftIfExists() {
        try {
            if (offlineCache == null || binding == null) return;
            String draftJson = offlineCache.getString("current_draft", null);
            if (draftJson == null) return;

            JSONObject draft = new JSONObject(draftJson);
            
            if (binding.etEpc != null && draft.has("epc")) {
                binding.etEpc.setText(draft.optString("epc"));
            }
            if (binding.etTitle != null && draft.has("title")) {
                binding.etTitle.setText(draft.optString("title"));
            }
            if (binding.etGrossWeight != null && draft.has("gross_weight")) {
                binding.etGrossWeight.setText(draft.optString("gross_weight"));
            }
            
            if (binding.switchHasStones != null && draft.has("has_stones")) {
                boolean hasStones = draft.optBoolean("has_stones");
                binding.switchHasStones.setChecked(hasStones);
                if (hasStones) {
                    if (binding.etStoneType != null && draft.has("stone_type")) {
                        binding.etStoneType.setText(draft.optString("stone_type"));
                    }
                    if (binding.etStoneWeight != null && draft.has("stone_weight")) {
                        binding.etStoneWeight.setText(draft.optString("stone_weight"));
                    }
                    if (binding.etStoneCount != null && draft.has("stone_count")) {
                        binding.etStoneCount.setText(draft.optString("stone_count"));
                    }
                    if (binding.etStoneValue != null && draft.has("stone_value")) {
                        binding.etStoneValue.setText(draft.optString("stone_value"));
                    }
                }
            }
            
            if (draft.has("photo_paths")) {
                JSONArray photosArray = draft.optJSONArray("photo_paths");
                if (photosArray != null) {
                    for (int i = 0; i < photosArray.length(); i++) {
                        String path = photosArray.optString(i);
                        if (path != null && !path.isEmpty() && new File(path).exists()) {
                            photoPaths.add(path);
                        }
                    }
                    onPhotosUpdated();
                }
            }
            
            showToast(isArabic ? "📝 تم استعادة المسودة المحفوظة" : "📝 Draft restored");
            offlineCache.edit().remove("current_draft").apply();
            
        } catch (Exception e) {
            Log.e(TAG, "restoreDraftIfExists error: " + e.getMessage());
        }
    }

    private void saveCurrentDraft() {
        try {
            if (offlineCache == null || binding == null) return;
            
            String epc = binding.etEpc.getText() != null ? binding.etEpc.getText().toString().trim() : "";
            String title = binding.etTitle.getText() != null ? binding.etTitle.getText().toString().trim() : "";
            
            if (epc.isEmpty() && title.isEmpty() && photoPaths.isEmpty()) {
                return;
            }
            
            JSONObject draft = new JSONObject();
            draft.put("epc", epc);
            draft.put("title", title);
            
            String weightStr = binding.etGrossWeight.getText() != null ? binding.etGrossWeight.getText().toString().trim() : "";
            draft.put("gross_weight", weightStr);
            
            boolean hasStones = binding.switchHasStones.isChecked();
            draft.put("has_stones", hasStones);
            if (hasStones) {
                draft.put("stone_type", binding.etStoneType.getText() != null ? binding.etStoneType.getText().toString().trim() : "");
                draft.put("stone_weight", binding.etStoneWeight.getText() != null ? binding.etStoneWeight.getText().toString().trim() : "");
                draft.put("stone_count", binding.etStoneCount.getText() != null ? binding.etStoneCount.getText().toString().trim() : "");
                draft.put("stone_value", binding.etStoneValue.getText() != null ? binding.etStoneValue.getText().toString().trim() : "");
            }
            
            if (!photoPaths.isEmpty()) {
                JSONArray photosArray = new JSONArray();
                for (String path : photoPaths) {
                    photosArray.put(path);
                }
                draft.put("photo_paths", photosArray);
            }
            
            offlineCache.edit().putString("current_draft", draft.toString()).apply();
            
        } catch (Exception e) {
            Log.e(TAG, "saveCurrentDraft error: " + e.getMessage());
        }
    }

    private void applyPermissions() {
        if (binding == null) return;
        boolean canEdit = permissionManager.canEdit("identity") || permissionManager.isManager();
        
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
            showToast(isArabic ? "⛔ وضع القراءة فقط - غير مصرح بالتعديل" : "⛔ Read only mode - Access Denied");
        }
    }

    @Override
public void onResume() {
    super.onResume();
    
    // ✅ تأجيل العمليات لتجنب التجميد
    if (getView() != null) {
        getView().postDelayed(() -> {
            if (!isAdded()) return;
            
            try {
                if (priceManager != null) {
                    priceManager.refreshNow();
                    handler.postDelayed(() -> {
                        if (isAdded() && binding != null) {
                            calculateEstimatedGoldValue();
                        }
                    }, 500);
                }
                
                syncPendingEncodes();
                updatePendingSyncBadge();
                
                if (getActivity() instanceof UHFMainActivity) {
                    ((UHFMainActivity) getActivity()).setActiveEncodingFragment(this);
                }
            } catch (Exception e) {
                Log.e(TAG, "onResume error: " + e.getMessage(), e);
            }
        }, 300); // ✅ تأخير 300ms
    }
}

    @Override
    public void onPause() {
        super.onPause();
        stopRfidScanningMode();
        saveCurrentDraft();
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setActiveEncodingFragment(null);
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

    private void initSuggestionsData() {
        typeSuggestions.clear();
        if (isArabic) {
            typeSuggestions.put("rings", new String[]{"خاتم سوليتير ألماسي", "خاتم محبس كلاسيك", "خاتم توينز راقي", "خاتم خطوبة مميز"});
            typeSuggestions.put("sets", new String[]{"طقم شبكة عروس كامل", "طقم ملكي مرصع بالألماس", "طقم سهرة فاخر"});
            typeSuggestions.put("bracelets", new String[]{"بناجر / أساور خليجي", "سوار تنادي عصري", "سوار ذهبي كلاسيك"});
            typeSuggestions.put("necklaces", new String[]{"عقد شوكر عصري", "سلسلة ذهبية ناعمة", "عقد لؤلؤ فاخر"});
        } else {
            typeSuggestions.put("rings", new String[]{"Diamond Solitaire Ring", "Classic Band Ring", "Twins Ring", "Engagement Ring"});
            typeSuggestions.put("sets", new String[]{"Complete Bridal Set", "Royal Diamond Set", "Luxury Evening Set"});
            typeSuggestions.put("bracelets", new String[]{"Khaleeji Bangle", "Modern Tennis Bracelet", "Classic Gold Bracelet"});
            typeSuggestions.put("necklaces", new String[]{"Modern Choker", "Fine Gold Chain", "Luxury Pearl Necklace"});
        }
    }

    private void setupPhotosRecyclerView() {
        if (binding == null || binding.rvPhotosPreview == null) return;
        photosAdapter = new PhotosAdapter(photoPaths, position -> {
            if (isFormLocked) {
                showToast(isArabic ? "⛔ النموذج مقفل" : "⛔ Form is locked");
                return;
            }
            if (position >= 0 && position < photoPaths.size()) {
                photoPaths.remove(position);
                photosAdapter.notifyItemRemoved(position);
                photosAdapter.notifyItemRangeChanged(position, photoPaths.size());
                updatePhotosCountText();
            }
        });
        binding.rvPhotosPreview.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        binding.rvPhotosPreview.setAdapter(photosAdapter);
    }

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
                if (photoPaths.size() >= MAX_PHOTOS) {
                    showToast(isArabic ? "⚠️ الحد الأقصى " + MAX_PHOTOS + " صور" : "⚠️ Maximum " + MAX_PHOTOS + " photos");
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
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
        } else {
            binding.layoutStonesDetails.animate()
                .alpha(0f)
                .translationY(-10f)
                .setDuration(200)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    if (binding != null && binding.layoutStonesDetails != null) {
                        binding.layoutStonesDetails.setVisibility(View.GONE);
                    }
                })
                .start();
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

            double stoneWeightGrams = 0.0;
            double stoneValue = 0.0;

            if (binding.switchHasStones.isChecked()) {
                String sWeight = binding.etStoneWeight.getText() != null ? binding.etStoneWeight.getText().toString().trim() : "";
                String sValue = binding.etStoneValue.getText() != null ? binding.etStoneValue.getText().toString().trim() : "";

                if (!sWeight.isEmpty()) {
                    stoneWeightGrams = Double.parseDouble(sWeight) * CARAT_TO_GRAM_FACTOR;
                }
                if (!sValue.isEmpty()) {
                    stoneValue = Double.parseDouble(sValue);
                }
            }

            double netGoldWeight = Math.max(0.0, grossWeight - stoneWeightGrams);
            int karat = getSelectedKaratNumber();

            MiraPriceManager.PricingDetails details = priceManager.calculatePricingDetails(karat, netGoldWeight);
            double totalPrice = details.finalPrice + stoneValue;

            String formattedPrice = priceManager.formatPriceDefault(totalPrice);
            binding.tvEstimatedGoldValue.setText(formattedPrice);
            binding.tvSummaryPrice.setText(formattedPrice);

        } catch (NumberFormatException e) {
            Log.e(TAG, "calculateEstimatedGoldValue NumberFormatException: " + e.getMessage());
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

    private double[] calculatePricingDetails(double grossWeight, double stoneWeightGrams, double stoneValue) {
        int karat = getSelectedKaratNumber();
        double netWeight = Math.max(0.0, grossWeight - stoneWeightGrams);

        MiraPriceManager.PricingDetails details = priceManager.calculatePricingDetails(karat, netWeight);
        
        double finalPrice = details.finalPrice + stoneValue;
        
        return new double[]{
            details.baseGoldCost,
            details.totalMakingCost,
            stoneValue,
            details.baseGoldCost + details.totalMakingCost,
            details.profitAmount,
            finalPrice,
            netWeight
        };
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (currentPhotoPath != null) {
            outState.putString("camera_photo_path", currentPhotoPath);
        }
    }

    private Bitmap generateGS1DataMatrix(String gs1Data, int width, int height) {
        if (gs1Data == null || gs1Data.trim().isEmpty()) return null;
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.DATA_MATRIX_SHAPE, SymbolShapeHint.FORCE_SQUARE);
            hints.put(EncodeHintType.MARGIN, 0);

            BitMatrix bitMatrix = new MultiFormatWriter().encode(
                    gs1Data, BarcodeFormat.DATA_MATRIX, width, height, hints);

            int w = bitMatrix.getWidth();
            int h = bitMatrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                int offset = y * w;
                for (int x = 0; x < w; x++) {
                    pixels[offset + x] = bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error generating GS1 DataMatrix: " + e.getMessage());
            return null;
        }
    }

    private Bitmap generateMicroQRCode(String qrContent, int width, int height) {
        if (qrContent == null || qrContent.trim().isEmpty()) {
            return null;
        }
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            int scaleFactor = 4;
            BitMatrix bitMatrix = new MultiFormatWriter().encode(
                    qrContent, 
                    BarcodeFormat.QR_CODE, 
                    width * scaleFactor, 
                    height * scaleFactor, 
                    hints);

            int matrixWidth = bitMatrix.getWidth();
            int matrixHeight = bitMatrix.getHeight();
            int[] pixels = new int[matrixWidth * matrixHeight];

            for (int y = 0; y < matrixHeight; y++) {
                int offset = y * matrixWidth;
                for (int x = 0; x < matrixWidth; x++) {
                    pixels[offset + x] = bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }

            Bitmap highResBitmap = Bitmap.createBitmap(matrixWidth, matrixHeight, Bitmap.Config.ARGB_8888);
            highResBitmap.setPixels(pixels, 0, matrixWidth, 0, 0, matrixWidth, matrixHeight);
            
            Bitmap finalBitmap = Bitmap.createScaledBitmap(
                highResBitmap, 
                width, 
                height, 
                true
            );
            
            if (highResBitmap != finalBitmap) {
                highResBitmap.recycle();
            }
            
            return finalBitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error generating Micro QR Code", e);
            return null;
        }
    }

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

            double stoneWeightGrams = stoneWeightCarats * CARAT_TO_GRAM_FACTOR;
            if (stoneWeightGrams >= grossWeight) {
                showValidationError(isArabic ? "❌ وزن الأحجار لا يمكن أن يتجاوز أو يساوي الوزن الإجمالي للقطعة!" : "❌ Stone weight cannot exceed gross weight!");
                unlockFormFast();
                return;
            }

            double[] pricing = calculatePricingDetails(grossWeight, stoneWeightGrams, stoneValue);
            final double netGoldWeight = pricing[6];

            final int selectedKaratNum = getSelectedKaratNumber();
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
            final List<String> finalPhotoPaths = new ArrayList<>(photoPaths);
            final int currentUserId = getCurrentUserId();
            MiraRfidService rfidService = MiraRfidService.getInstance(getContext());
            final String tid = rfidService.readTid("00000000");
            final boolean isSim = rfidService.isSimulationMode();
            
            executorService.execute(() -> {
                try {
                    JSONObject payload = buildPayload(
                        finalEpc,
                        finalTitle, selectedKarat, fGrossWeight, netGoldWeight,
                        fStoneWeightCarats, fStoneCount, fStoneValue, fStoneType,
                        details, selectedKaratNum, finalPriceUSD, finalPriceLocal,
                        localCurrency, exchangeRate, selectedCategory, location,
                        branchId, currentUserId, tid, isSim, finalPhotoPaths
                    );

                    final boolean[] apiSuccess = {false};
                    final int[] serverItemId = {0};
                    final String[] errorMessage = {null};
                    final boolean[] isNetworkError = {false};
                    final JSONObject[] responseDataObj = {null};

                    if (apiClient != null) {
                        try {
                            MiraApiClient.ApiResponse response = apiClient.encodeItem(payload);
                            
                            if (response != null) {
                                apiSuccess[0] = response.isSuccess;
                                responseDataObj[0] = response.data;
                                
                                if (response.isSuccess) {
                                    if (response.data != null) {
                                        serverItemId[0] = response.data.optInt("item_id", 0);
                                    }
                                    if (response.message != null && !response.message.isEmpty()) {
                                        errorMessage[0] = response.message;
                                    }
                                } else {
                                    errorMessage[0] = extractErrorMessage(response);
                                    isNetworkError[0] = response.isNetworkError;
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "API call exception", e);
                            errorMessage[0] = e.getMessage();
                            isNetworkError[0] = true;
                        }
                    }

                    final boolean success = apiSuccess[0];
                    final int finalServerItemId = serverItemId[0];
                    final String finalErrorMessage = errorMessage[0];
                    final boolean networkError = isNetworkError[0];
                    final JSONObject finalResponseData = responseDataObj[0];

                    safeRun(() -> {
                        dismissDialog(loadingDialog);

                        if (success) {
                            playSuccessSound();
                            removePendingEncode(finalEpc);
                            
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
                                } else {
                                    if (finalResponseData.has("gs1_datamatrix")) {
                                        gs1Code = finalResponseData.optString("gs1_datamatrix");
                                    } else if (finalResponseData.has("gs1_code")) {
                                        gs1Code = finalResponseData.optString("gs1_code");
                                    }
                                    
                                    if (finalResponseData.has("qr_url")) {
                                        qrUrl = finalResponseData.optString("qr_url");
                                    }
                                }
                            }

                            if (binding != null && binding.etEpc != null) {
                                binding.etEpc.setText("");
                            }

                            showEncodingSuccessDialogEnhanced(
                                    finalTitle,
                                    activeEpc,
                                    tid,
                                    gs1Code,
                                    qrUrl,
                                    String.valueOf(fGrossWeight),
                                    selectedKarat,
                                    finalPriceLocal,
                                    localCurrency,
                                    finalPhotoPaths
                            );
                            
                        } else if (networkError) {
                            saveEncodeOffline(payload);
                            updatePendingSyncBadge();
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
        if (response == null) {
            return isArabic ? "❌ خطأ غير معروف" : "❌ Unknown error";
        }
        
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
        
        if (response.message != null && !response.message.isEmpty()) {
            return response.message;
        }
        if (response.error != null && !response.error.isEmpty()) {
            return response.error;
        }
        return isArabic ? "❌ فشل العملية" : "❌ Operation failed";
    }

    @Override
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

    public void validateAndProcessEpc(String scannedEpc) {
        if (scannedEpc == null || scannedEpc.trim().isEmpty()) {
            showErrorDialog(
                isArabic ? "خطأ" : "Error", 
                isArabic ? "لم يتم قراءة رمز الشريحة بشكل صحيح." : "Invalid EPC tag."
            );
            return;
        }

        String normalizedEpc = scannedEpc.trim().toUpperCase(Locale.US);

        if (isEpcDuplicateLocally(normalizedEpc)) {
            playErrorSound();
            showErrorDialog(
                isArabic ? "شريحة مكررة محلياً" : "Duplicate Local Tag", 
                isArabic ? "هذه الشريحة (" + normalizedEpc + ") مسجلة بالفعل في قائمة الانتظار أو الكاش المحلي." 
                         : "This tag (" + normalizedEpc + ") is already stored in local pending items or cache."
            );
            return;
        }

        if (binding != null && binding.etEpc != null) {
            binding.etEpc.setText(normalizedEpc);
            binding.tvStatusBadge.setText("BOUND");
            playSuccessSound();
        }
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
        
        if (epc != null && !epc.isEmpty()) {
            payload.put("epc", epc);
        }
        
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
        
        if (tid != null && !tid.isEmpty()) {
            payload.put("rfid_tid", tid);
        }

        if (!photoPathsList.isEmpty()) {
            JSONArray imagesArray = new JSONArray();
            for (String path : photoPathsList) {
                imagesArray.put(path);
            }
            payload.put("image_paths", imagesArray);
        }
        
        return payload;
    }

    private void showPendingItemsDialog() {
        if (offlineCache == null || !isAdded() || getContext() == null) return;

        try {
            String json = offlineCache.getString("pending_encodes", "[]");
            JSONArray arr = new JSONArray(json);

            if (arr.length() == 0) {
                showToast(isArabic ? "لا توجد عناصر معلقة" : "No pending items");
                return;
            }

            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);
                builder.append("• ")
                        .append(item.optString("title"))
                        .append(" | ")
                        .append(item.optString("epc"))
                        .append(" (")
                        .append(item.optDouble("gross_weight"))
                        .append("g)\n");
            }

            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(isArabic ? "العناصر المخزنة محلياً" : "Offline Stored Items")
                    .setMessage(builder.toString())
                    .setPositiveButton(isArabic ? "مزامنة الآن" : "Sync Now", (d, w) -> syncPendingEncodes())
                    .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                    .show();

        } catch (Exception e) {
            Log.e(TAG, "showPendingItemsDialog error: " + e.getMessage(), e);
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
                    if (success && currentPhotoPath != null) {
                        String compressedPath = compressImage(currentPhotoPath);
                        if (compressedPath != null) {
                            photoPaths.add(compressedPath);
                            onPhotosUpdated();
                        }
                    }
                }
        );

        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                uris -> {
                    if (uris != null && !uris.isEmpty()) {
                        int remainingSlots = MAX_PHOTOS - photoPaths.size();
                        int count = 0;
                        for (Uri uri : uris) {
                            if (count >= remainingSlots) {
                                showToast(isArabic ? "⚠️ تم الوصول للحد الأقصى" : "⚠️ Maximum photos reached");
                                break;
                            }
                            String path = copyImageToInternalStorage(uri);
                            if (path != null) {
                                String compressedPath = compressImage(path);
                                if (compressedPath != null) {
                                    photoPaths.add(compressedPath);
                                    count++;
                                }
                            }
                        }
                        onPhotosUpdated();
                    }
                }
        );
    }

    private String compressImage(String imagePath) {
        try {
            File imageFile = new File(imagePath);
            if (!imageFile.exists()) return null;
            
            if (imageFile.length() < 500 * 1024) {
                return imagePath;
            }
            
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            
            Bitmap bitmap = BitmapFactory.decodeFile(imagePath, options);
            if (bitmap == null) return imagePath;
            
            String compressedPath = imagePath.replace(".jpg", "_compressed.jpg");
            FileOutputStream outputStream = new FileOutputStream(compressedPath);
            bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_COMPRESSION_QUALITY, outputStream);
            outputStream.close();
            bitmap.recycle();
            
            if (!compressedPath.equals(imagePath) && imageFile.exists()) {
                imageFile.delete();
            }
            
            return compressedPath;
            
        } catch (Exception e) {
            Log.e(TAG, "compressImage error: " + e.getMessage());
            return imagePath;
        }
    }

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

    private String copyImageToInternalStorage(Uri uri) {
        try {
            if (getContext() == null) return null;
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File storageDir = getContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            File destFile = new File(storageDir, "MIRA_" + timeStamp + "_" + System.currentTimeMillis() + ".jpg");

            try (InputStream inputStream = getContext().getContentResolver().openInputStream(uri);
                 FileOutputStream outputStream = new FileOutputStream(destFile)) {
                
                byte[] buffer = new byte[4096];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }
            }
            
            return destFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "copyImageToInternalStorage error: " + e.getMessage(), e);
            return null;
        }
    }

    private void onPhotosUpdated() {
        if (photosAdapter != null) photosAdapter.notifyDataSetChanged();
        updatePhotosCountText();
        if (binding != null && binding.rvPhotosPreview != null) {
            binding.rvPhotosPreview.setVisibility(photoPaths.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void updatePhotosCountText() {
        try {
            if (binding == null || binding.tvPhotosCount == null) return;
            int count = photoPaths.size();
            binding.tvPhotosCount.setText(count == 0 ?
                    (isArabic ? "لم يتم إرفاق صور بعد" : "No photos attached yet") :
                    (isArabic ? "تم إرفاق (" + count + "/" + MAX_PHOTOS + ") صور" : "Attached (" + count + "/" + MAX_PHOTOS + ") photos"));
        } catch (Exception e) {
            Log.e(TAG, "updatePhotosCountText error: " + e.getMessage());
        }
    }

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
                    String normalizedEpc = epc != null ? epc.trim().toUpperCase(Locale.US) : "";
                    
                    if (isEpcDuplicateLocally(normalizedEpc)) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                playErrorSound();
                                showErrorDialog(
                                    isArabic ? "شريحة مكررة محلياً" : "Duplicate Local Tag", 
                                    isArabic ? "هذه الشريحة (" + normalizedEpc + ") مسجلة بالفعل. يرجى استخدام شريحة جديدة." 
                                             : "This tag (" + normalizedEpc + ") is already registered. Please use a new tag."
                                );
                                if (binding != null) binding.tvStatusBadge.setText("DUPLICATE");
                                stopRfidScanningMode();
                            });
                        }
                        return;
                    }
                    
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (binding != null && binding.etEpc != null) {
                                binding.etEpc.setText(normalizedEpc);
                                binding.tvStatusBadge.setText("BOUND");
                            }
                            playSuccessSound();
                            stopRfidScanningMode();
                        });
                    }
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
        String normalizedEpc = epc != null ? epc.trim().toUpperCase(Locale.US) : "";
        
        getActivity().runOnUiThread(() -> {
            if (!isAdded() || binding == null) return;
            
            if (isEpcDuplicateLocally(normalizedEpc)) {
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
    }

    private void populateSuggestions(String typeKey) {
        try {
            if (binding == null) return;
            binding.chipGroupSuggestions.removeAllViews();
            String[] suggestions = typeSuggestions.get(typeKey);
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
        
        View printView = view.findViewById(R.id.btnPrintTag);
        if (printView instanceof MaterialButton) {
            MaterialButton btnPrintTag = (MaterialButton) printView;
            btnPrintTag.setVisibility(View.VISIBLE);
            btnPrintTag.setOnClickListener(v -> {
                Bitmap tagToPrint = generatePhysicalTagBitmap(epc, gs1Code, qrUrl);
                printTagDirectly(tagToPrint);
            });
        }
        
        if (tvIdTitle != null) tvIdTitle.setText(title);
        if (tvEpcCode != null) tvEpcCode.setText("EPC: " + epc);
        if (tvIdMetalKarat != null) tvIdMetalKarat.setText((isArabic ? "عيار " : "Karat ") + karat);
        if (tvIdWeight != null) tvIdWeight.setText(weight + (isArabic ? " جرام" : " g"));
        if (tvIdPrice != null && priceManager != null) {
            tvIdPrice.setText(priceManager.formatPriceDefault(finalPrice) + " " + currency);
        }

        if (ivGs1DataMatrix != null && gs1Code != null && !gs1Code.isEmpty()) {
            Bitmap dmBitmap = generateGS1DataMatrix(gs1Code, 250, 250);
            if (dmBitmap != null) {
                ivGs1DataMatrix.setImageBitmap(dmBitmap);
                ivGs1DataMatrix.setVisibility(View.VISIBLE);
            }
        }

        if (ivQrCode != null && qrUrl != null && !qrUrl.isEmpty()) {
            Bitmap qrBitmap = generateMicroQRCode(qrUrl, 250, 250);
            if (qrBitmap != null) {
                ivQrCode.setImageBitmap(qrBitmap);
                ivQrCode.setVisibility(View.VISIBLE);
            }
        }

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                successDialog.dismiss();
                resetFormForNextScan();
            });
        }

        successDialog.show();
    }

    private Bitmap generatePhysicalTagBitmap(String epc, String gs1Data, String qrUrl) {
        final float MM_TO_PX = 8.0f; // 203 DPI (8 dots per mm)
        
        int cardWidthPx = Math.round(30 * MM_TO_PX);   // 240 px
        int cardHeightPx = Math.round(15 * MM_TO_PX);  // 120 px
        
        int barcodeSizePx = Math.round(8 * MM_TO_PX);  // 64 px
        
        Bitmap tagBitmap = Bitmap.createBitmap(cardWidthPx, cardHeightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tagBitmap);
        canvas.drawColor(Color.WHITE);

        Paint titlePaint = new Paint();
        titlePaint.setColor(Color.BLACK);
        titlePaint.setAntiAlias(true);
        titlePaint.setTextSize(2.2f * MM_TO_PX);
        titlePaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        titlePaint.setTextAlign(Paint.Align.CENTER);

        canvas.drawText("MIRA JEWELRY RFID", cardWidthPx / 2f, 3 * MM_TO_PX, titlePaint);

        if (gs1Data != null && !gs1Data.isEmpty()) {
            Bitmap dmBitmap = generateGS1DataMatrix(gs1Data, barcodeSizePx, barcodeSizePx);
            if (dmBitmap != null) {
                int dmX = Math.round(1 * MM_TO_PX);
                int dmY = Math.round(4.5f * MM_TO_PX);
                canvas.drawBitmap(dmBitmap, dmX, dmY, null);
                dmBitmap.recycle();
            }
        }

        int rfidCenterX = Math.round(15 * MM_TO_PX);
        int rfidCenterY = Math.round(8.5f * MM_TO_PX);

        Paint rfidPaint = new Paint();
        rfidPaint.setColor(Color.LTGRAY);
        rfidPaint.setStyle(Paint.Style.STROKE);
        rfidPaint.setStrokeWidth(1f);

        int rfidWidth = Math.round(7 * MM_TO_PX);
        int rfidHeight = Math.round(7 * MM_TO_PX);
        canvas.drawRect(rfidCenterX - rfidWidth/2f, rfidCenterY - rfidHeight/2f, 
                        rfidCenterX + rfidWidth/2f, rfidCenterY + rfidHeight/2f, rfidPaint);

        Paint chipPaint = new Paint();
        chipPaint.setColor(Color.BLACK);
        canvas.drawCircle(rfidCenterX, rfidCenterY, 1.5f * MM_TO_PX, chipPaint);

        if (qrUrl != null && !qrUrl.isEmpty()) {
            Bitmap qrBitmap = generateMicroQRCode(qrUrl, barcodeSizePx, barcodeSizePx);
            if (qrBitmap != null) {
                int qrX = cardWidthPx - barcodeSizePx - Math.round(1 * MM_TO_PX);
                int qrY = Math.round(4.5f * MM_TO_PX);
                canvas.drawBitmap(qrBitmap, qrX, qrY, null);
                qrBitmap.recycle();
            }
        }

        Paint labelPaint = new Paint();
        labelPaint.setColor(Color.BLACK);
        labelPaint.setAntiAlias(true);
        labelPaint.setTextSize(1.8f * MM_TO_PX);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        canvas.drawText("GS1", 5 * MM_TO_PX, 14 * MM_TO_PX, labelPaint);
        canvas.drawText("QR", cardWidthPx - (5 * MM_TO_PX), 14 * MM_TO_PX, labelPaint);

        return tagBitmap;
    }

    private void printTagDirectly(Bitmap tagBitmap) {
        if (getContext() == null || tagBitmap == null) return;
        try {
            if (!PrintHelper.systemSupportsPrint()) {
                showToast(isArabic ? "⚠️ ميزة الطباعة غير مدعومة على هذا الجهاز" : "⚠️ Printing is not supported on this device");
                return;
            }

            PrintHelper printHelper = new PrintHelper(requireContext());
            printHelper.setScaleMode(PrintHelper.SCALE_MODE_FILL);
            printHelper.setColorMode(PrintHelper.COLOR_MODE_MONOCHROME);
            printHelper.setOrientation(PrintHelper.ORIENTATION_LANDSCAPE);
            
            printHelper.printBitmap("MIRA_TAG_30x15mm", tagBitmap);
        } catch (Exception e) {
            Log.e(TAG, "Print Error: " + e.getMessage(), e);
            showToast(isArabic ? "❌ تعذر إرسال أمر الطباعة" : "❌ Print request failed");
        }
    }

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

    protected void dismissDialog(AlertDialog dialog) {
        try {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        } catch (Exception e) {
            Log.e(TAG, "dismissDialog error: " + e.getMessage());
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

    private boolean isEpcDuplicateLocally(String epc) {
        if (epc == null || epc.trim().isEmpty() || offlineCache == null) {
            return false;
        }

        String cleanEpc = epc.trim().toUpperCase(Locale.US);

        try {
            String pendingJson = offlineCache.getString("pending_encodes", "[]");
            JSONArray pendingArray = new JSONArray(pendingJson);

            for (int i = 0; i < pendingArray.length(); i++) {
                JSONObject item = pendingArray.optJSONObject(i);
                if (item != null) {
                    String storedEpc = item.optString("epc", "").trim().toUpperCase(Locale.US);
                    if (cleanEpc.equals(storedEpc)) {
                        Log.w(TAG, "Duplicate found in Offline Pending Sync List: " + cleanEpc);
                        return true;
                    }
                }
            }

            String draftJson = offlineCache.getString("current_draft", null);
            if (draftJson != null) {
                JSONObject draft = new JSONObject(draftJson);
                String draftEpc = draft.optString("epc", "").trim().toUpperCase(Locale.US);
                if (cleanEpc.equals(draftEpc)) {
                    Log.w(TAG, "EPC matches current unsaved draft: " + cleanEpc);
                    return true;
                }
            }

            String localItemsJson = offlineCache.getString("cached_master_items", "[]");
            JSONArray localItemsArray = new JSONArray(localItemsJson);

            for (int i = 0; i < localItemsArray.length(); i++) {
                JSONObject item = localItemsArray.optJSONObject(i);
                if (item != null) {
                    String storedEpc = item.optString("epc", "").trim().toUpperCase(Locale.US);
                    if (cleanEpc.equals(storedEpc)) {
                        Log.w(TAG, "Duplicate found in Local Master Cache: " + cleanEpc);
                        return true;
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error checking duplicate EPC: " + e.getMessage(), e);
        }

        return false;
    }

    private void saveEncodeOffline(JSONObject payload) {
        try {
            if (offlineCache == null) return;
            String json = offlineCache.getString("pending_encodes", "[]");
            JSONArray arr = new JSONArray(json);
            
            String targetEpc = payload.optString("epc");
            for (int i = 0; i < arr.length(); i++) {
                if (arr.getJSONObject(i).optString("epc").equals(targetEpc)) {
                    arr.remove(i);
                    break;
                }
            }

            arr.put(payload);
            while (arr.length() > 200) arr.remove(0);
            offlineCache.edit().putString("pending_encodes", arr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "Save encode offline error: " + e.getMessage(), e);
        }
    }

    private void removePendingEncode(String epc) {
        try {
            if (offlineCache == null) return;
            String json = offlineCache.getString("pending_encodes", "[]");
            JSONArray arr = new JSONArray(json);
            JSONArray newArr = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null && !epc.equals(obj.optString("epc", ""))) newArr.put(obj);
            }
            offlineCache.edit().putString("pending_encodes", newArr.toString()).apply();
            updatePendingSyncBadge();
        } catch (Exception ignored) {
            Log.e(TAG, "removePendingEncode error", ignored);
        }
    }

    private void updatePendingSyncBadge() {
        try {
            if (offlineCache == null || binding == null) return;
            String json = offlineCache.getString("pending_encodes", "[]");
            JSONArray arr = new JSONArray(json);
            int count = arr.length();

            if (binding.tvPendingSyncCount != null) {
                if (count > 0) {
                    binding.tvPendingSyncCount.setVisibility(View.VISIBLE);
                    binding.tvPendingSyncCount.setText(isArabic ? "انتظار المزامنة: " + count : "Pending Sync: " + count);
                } else {
                    binding.tvPendingSyncCount.setVisibility(View.GONE);
                }
            }
        } catch (Exception ignored) {
            Log.e(TAG, "updatePendingSyncBadge error", ignored);
        }
    }

    private void syncPendingEncodes() {
        if (apiClient == null || offlineCache == null) return;
        if (!(permissionManager.canEdit("identity") || permissionManager.isManager())) return;
        
        final int branchId = (branchesManager != null) ? branchesManager.getActiveBranchId() : 1;

        executorService.execute(() -> {
            try {
                String json = offlineCache.getString("pending_encodes", "[]");
                JSONArray arr = new JSONArray(json);
                if (arr.length() == 0) return;

                JSONArray remainingArr = new JSONArray();
                int synced = 0;
                for (int i = 0; i < arr.length(); i++) {
                    try {
                        JSONObject payload = arr.getJSONObject(i);
                        if (!payload.has("branch_id") || payload.optInt("branch_id", 0) <= 0) {
                            payload.put("branch_id", branchId);
                        }

                        MiraApiClient.ApiResponse response = apiClient.encodeItem(payload);
                        if (response != null && response.isSuccess) {
                            synced++;
                        } else {
                            remainingArr.put(payload);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to sync item", e);
                        remainingArr.put(arr.getJSONObject(i));
                    }
                }
                offlineCache.edit().putString("pending_encodes", remainingArr.toString()).apply();

                final int s = synced;
                safeRun(() -> {
                    updatePendingSyncBadge();
                    if (s > 0) showToast(isArabic ? "✅ تمت مزامنة " + s + " تكويد" : "✅ Synced " + s + " encodings");
                });
            } catch (Exception e) {
                Log.e(TAG, "Sync encodes error: " + e.getMessage(), e);
            }
        });
    }

    private void safeRun(Runnable r) {
        try {
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (isAdded()) r.run();
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "safeRun error: " + e.getMessage(), e);
        }
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
            
            photoPaths.clear();
            onPhotosUpdated();
            
            if (offlineCache != null) {
                offlineCache.edit().remove("current_draft").apply();
            }

            activateRFIDModeForEncoding();
        } catch (Exception e) {
            Log.e(TAG, "resetFormForNextScan error: " + e.getMessage(), e);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopRfidScanningMode();
        
        if (executorService != null && !executorService.isShutdown()) {
            try {
                executorService.shutdown();
                if (!executorService.awaitTermination(800, TimeUnit.MILLISECONDS)) {
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

    private interface OnPhotoDeleteListener {
        void onDelete(int position);
    }

        private static class PhotosAdapter extends RecyclerView.Adapter<PhotosAdapter.PhotoViewHolder> {
        private final List<String> paths;
        private final OnPhotoDeleteListener deleteListener;

        public PhotosAdapter(List<String> paths, OnPhotoDeleteListener deleteListener) {
            this.paths = paths;
            this.deleteListener = deleteListener;
        }

        @NonNull
        @Override
        public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_photo_preview, parent, false);
            return new PhotoViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
            String path = paths.get(position);
            
            Bitmap thumbnail = decodeSampledBitmapFromFile(path, 100, 100);
            if (thumbnail != null) {
                holder.imgThumbnail.setImageBitmap(thumbnail);
            } else {
                // تم تعديل المورد لتفادي خطأ ic_placeholder المفقود
                holder.imgThumbnail.setImageResource(R.drawable.ic_photo);
            }

            holder.btnDelete.setOnClickListener(v -> {
                if (deleteListener != null) {
                    // تم تعديل الدالة إلى getAdapterPosition() لتتوافق مع الإصدار الحالي
                    int adapterPosition = holder.getAdapterPosition();
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        deleteListener.onDelete(adapterPosition);
                    }
                }
            });
        }

        @Override
        public int getItemCount() {
            return paths.size();
        }

        private Bitmap decodeSampledBitmapFromFile(String path, int reqWidth, int reqHeight) {
            try {
                final BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, options);

                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
                options.inJustDecodeBounds = false;
                return BitmapFactory.decodeFile(path, options);
            } catch (Exception e) {
                return null;
            }
        }

        private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
            final int height = options.outHeight;
            final int width = options.outWidth;
            int inSampleSize = 1;

            if (height > reqHeight || width > reqWidth) {
                final int halfHeight = height / 2;
                final int halfWidth = width / 2;

                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2;
                }
            }
            return inSampleSize;
        }

        static class PhotoViewHolder extends RecyclerView.ViewHolder {
            ImageView imgThumbnail;
            ImageView btnDelete;

            public PhotoViewHolder(@NonNull View itemView) {
                super(itemView);
                imgThumbnail = itemView.findViewById(R.id.imgThumbnail);
                btnDelete = itemView.findViewById(R.id.btnDeletePhoto);
            }
        }
    }
}
