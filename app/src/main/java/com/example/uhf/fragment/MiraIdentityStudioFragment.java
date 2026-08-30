package com.example.uhf.fragment;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;


import com.example.uhf.MiraRfid.MiraRfidService;
import com.example.uhf.MiraRfid.readers.SimulatorReader;
import android.graphics.Color;

public class MiraIdentityStudioFragment extends MiraBaseFragment {

    private static final String TAG = "MiraIdentityStudio";
    private static final String OFFLINE_PREFS = "identity_studio_cache";
    private static final long RFID_TIMEOUT_MS = 30000;
    private static final double CARAT_TO_GRAM_FACTOR = 0.20;

    private FragmentMiraIdentityStudioBinding binding;
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private MiraPriceManager priceManager;
    private PermissionManager permissionManager;
    private SharedPreferences offlineCache;

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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentMiraIdentityStudioBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            if (getContext() != null) {
                apiClient = MiraApiClient.getInstance(getContext());
                branchesManager = MiraBranchesManager.getInstance(getContext());
                orgProfile = MiraOrgProfileManager.getInstance(getContext());
                priceManager = MiraPriceManager.getInstance(getContext());
                permissionManager = PermissionManager.getInstance(getContext());
                offlineCache = getContext().getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
            }
            isArabic = Locale.getDefault().getLanguage().equals("ar");

            initSuggestionsData();
            setupPhotoLaunchers();
            setupPhotosRecyclerView();
            applyLanguage();
            setupListeners();
            applyPermissions();
            populateSuggestions("rings");
            updatePendingSyncBadge();

            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setActiveEncodingFragment(this);
            }
        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error: " + e.getMessage());
        }
    }

    private void applyPermissions() {
        boolean canEdit = permissionManager.canEdit("identity") || permissionManager.isManager();
        
        if (binding.btnSaveIdentity != null) {
            binding.btnSaveIdentity.setEnabled(canEdit);
            binding.btnSaveIdentity.setAlpha(canEdit ? 1.0f : 0.5f);
        }
        
        if (binding.btnScanRfid != null) {
            binding.btnScanRfid.setEnabled(canEdit);
        }
        
        if (binding.btnAddPhotos != null) {
            binding.btnAddPhotos.setEnabled(canEdit);
        }
        
        // إذا لا يملك صلاحية التعديل، عرض رسالة
        if (!canEdit) {
            showToast(isArabic ? "⛔ وضع القراءة فقط - غير مصرح بالتعديل" : "⛔ Read only mode - Access Denied");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
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
    }

    @Override
    public void onPause() {
        super.onPause();
        stopRfidScanningMode();
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
            binding.tvStepTitle.setText("الخطوة 1 من 4: الربط والتصنيف");
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
            binding.tvStepTitle.setText("Step 1 of 4: Bind & Classify");
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
            typeSuggestions.put("rings", new String[]{"خاتم سوليتير ألماسي", "خاتم محبس كلاسيك", "خاتم توينز راقي"});
            typeSuggestions.put("sets", new String[]{"طقم شبكة عروس كامل", "طقم ملكي مرصع بالألماس"});
            typeSuggestions.put("bracelets", new String[]{"بناجر / أساور خليجي", "سوار تنادي عصري"});
            typeSuggestions.put("necklaces", new String[]{"عقد شوكر عصري", "سلسلة ذهبية ناعمة"});
        } else {
            typeSuggestions.put("rings", new String[]{"Diamond Solitaire Ring", "Classic Band Ring", "Twins Ring"});
            typeSuggestions.put("sets", new String[]{"Complete Bridal Set", "Royal Diamond Set"});
            typeSuggestions.put("bracelets", new String[]{"Khaleeji Bangle", "Modern Tennis Bracelet"});
            typeSuggestions.put("necklaces", new String[]{"Modern Choker", "Fine Gold Chain"});
        }
    }

    private void setupPhotosRecyclerView() {
        if (binding == null || binding.rvPhotosPreview == null) return;
        photosAdapter = new PhotosAdapter(photoPaths, position -> {
            photoPaths.remove(position);
            photosAdapter.notifyItemRemoved(position);
            photosAdapter.notifyItemRangeChanged(position, photoPaths.size());
            updatePhotosCountText();
        });
        binding.rvPhotosPreview.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        binding.rvPhotosPreview.setAdapter(photosAdapter);
    }

    private void setupListeners() {
    try {
        // ==================== 1. EPC TextWatcher ====================
        binding.etEpc.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                binding.tvStatusBadge.setText(s.length() > 0 ? "BOUND" : "WAITING");
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // ==================== 2. Barcode Scanner ====================
        if (binding.tilEpc != null) {
            binding.tilEpc.setEndIconOnClickListener(v -> openBarcodeScannerForEPC());
        }

        // ==================== 3. RFID Scan Button ====================
        binding.btnScanRfid.setOnClickListener(v -> {
            if (permissionManager.canEdit("identity") || permissionManager.isManager()) {
                if (isRfidScanning) stopRfidScanningMode();
                else activateRFIDModeForEncoding();
            } else {
                showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            }
        });

        // ==================== 4. Stones Switch - حاوية كاملة قابلة للنقر ====================
        // ✅✅✅ الحاوية الكاملة للأحجار الكريمة
        View stonesContainer = binding.getRoot().findViewById(R.id.layoutStonesContainer);
        if (stonesContainer != null) {
            stonesContainer.setOnClickListener(v -> {
                // ✅ تبديل حالة الـ Switch
                boolean newState = !binding.switchHasStones.isChecked();
                binding.switchHasStones.setChecked(newState);
                
                // ✅ إظهار/إخفاء التفاصيل مع أنيميشن سلس
                toggleStonesDetails(newState);
                
                // ✅ إعادة حساب القيمة
                calculateEstimatedGoldValue();
            });
        }

        // ✅✅✅ الـ Switch نفسه - يستجيب أيضاً
        binding.switchHasStones.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // ✅ إظهار/إخفاء التفاصيل مع أنيميشن سلس
            toggleStonesDetails(isChecked);
            
            // ✅ إعادة حساب القيمة
            calculateEstimatedGoldValue();
        });

        // ==================== 5. Calculation TextWatcher ====================
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

        // ==================== 6. Karat ChipGroup ====================
        binding.chipGroupKarat.setOnCheckedStateChangeListener((group, checkedIds) -> calculateEstimatedGoldValue());

        // ==================== 7. Types ChipGroup ====================
        binding.chipGroupTypes.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == binding.chipRing.getId()) populateSuggestions("rings");
            else if (id == binding.chipSet.getId()) populateSuggestions("sets");
            else if (id == binding.chipBracelet.getId()) populateSuggestions("bracelets");
            else if (id == binding.chipNecklace.getId()) populateSuggestions("necklaces");
        });

        // ==================== 8. Add Photos Button ====================
        binding.btnAddPhotos.setOnClickListener(v -> {
            if (permissionManager.canEdit("identity") || permissionManager.isManager()) {
                showImageSourceDialog();
            } else {
                showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            }
        });
        
        // ==================== 9. Save Identity Button ====================
        binding.btnSaveIdentity.setOnClickListener(v -> {
            if (permissionManager.canEdit("identity") || permissionManager.isManager()) {
                finalizeItemIdentity();
            } else {
                showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            }
        });
        
        // ==================== 10. View Pending Button ====================
        binding.btnViewPending.setOnClickListener(v -> showPendingItemsDialog());

    } catch (Exception e) {
        Log.e(TAG, "setupListeners error: " + e.getMessage());
    }
}

/**
 * ✅✅✅ دالة جديدة - تبديل عرض تفاصيل الأحجار مع أنيميشن سلس
 */
private void toggleStonesDetails(boolean show) {
    if (binding == null || binding.layoutStonesDetails == null) return;
    
    if (show) {
        // ✅ إظهار مع أنيميشن
        binding.layoutStonesDetails.setVisibility(View.VISIBLE);
        binding.layoutStonesDetails.setAlpha(0f);
        binding.layoutStonesDetails.setTranslationY(-10f);
        binding.layoutStonesDetails.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(250)
            .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
            .start();
    } else {
        // ✅ إخفاء مع أنيميشن
        binding.layoutStonesDetails.animate()
            .alpha(0f)
            .translationY(-10f)
            .setDuration(200)
            .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction(() -> binding.layoutStonesDetails.setVisibility(View.GONE))
            .start();
    }
}

    private int getSelectedKaratNumber() {
        try {
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

        // ✅ استخدام الدالة الموحدة
        MiraPriceManager.PricingDetails details = priceManager.calculatePricingDetails(karat, netGoldWeight);
        double totalPrice = details.finalPrice + stoneValue;

        String formattedPrice = priceManager.formatPriceDefault(totalPrice);
        binding.tvEstimatedGoldValue.setText(formattedPrice);
        binding.tvSummaryPrice.setText(formattedPrice);

    } catch (Exception e) {
        Log.e(TAG, "calculateEstimatedGoldValue error: " + e.getMessage());
    }
}

    private void resetCalculatedDisplay() {
        String zeroText = "0.00 " + priceManager.getCurrencySymbol(priceManager.getDefaultCurrency());
        binding.tvEstimatedGoldValue.setText(zeroText);
        binding.tvSummaryPrice.setText(zeroText);
    }

    private double[] calculatePricingDetails(double grossWeight, double stoneWeightGrams, double stoneValue) {
    int karat = getSelectedKaratNumber();
    double netWeight = Math.max(0.0, grossWeight - stoneWeightGrams);

    // ✅ استخدام الدالة الموحدة من MiraPriceManager
    MiraPriceManager.PricingDetails details = priceManager.calculatePricingDetails(karat, netWeight);
    
    double finalPrice = details.finalPrice + stoneValue;
    
    return new double[]{
        details.baseGoldCost,      // goldValue
        details.totalMakingCost,   // makingCost
        stoneValue,                // stoneValue
        details.baseGoldCost + details.totalMakingCost, // totalCost (subtotal)
        details.profitAmount,      // profitAmount
        finalPrice,                // finalPrice (مع قيمة الأحجار)
        netWeight                  // netWeight
    };
}

    private void finalizeItemIdentity() {
    if (!(permissionManager.canEdit("identity") || permissionManager.isManager())) {
        showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
        return;
    }
    
    try {
        if (binding == null) return;

        String epc = binding.etEpc.getText() != null ? binding.etEpc.getText().toString().trim() : "";
        String title = binding.etTitle.getText() != null ? binding.etTitle.getText().toString().trim() : "";
        String weightStr = binding.etGrossWeight.getText() != null ? binding.etGrossWeight.getText().toString().trim() : "";

        if (epc.isEmpty()) {
            showValidationError(isArabic ? "يرجى مسح RFID أولاً" : "Please scan RFID first");
            return;
        }
        if (!isValidEPC(epc)) {
            showValidationError(isArabic ? "❌ رمز RFID غير صالح!\nيجب أن يكون EPC قياسي أو GTIN-13." : "❌ Invalid RFID code!\nMust be EPC or GTIN-13.");
            return;
        }
        if (title.isEmpty()) {
            showValidationError(isArabic ? "يرجى كتابة عنوان القطعة" : "Please enter item title");
            return;
        }
        if (weightStr.isEmpty()) {
            showValidationError(isArabic ? "يرجى تحديد وزن القطعة" : "Please specify item weight");
            return;
        }

        double grossWeight = Double.parseDouble(weightStr);
        if (grossWeight <= 0) {
            showValidationError(isArabic ? "يجب أن يكون الوزن أكبر من صفر" : "Weight must be greater than zero");
            return;
        }

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
            return;
        }

        double[] pricing = calculatePricingDetails(grossWeight, stoneWeightGrams, stoneValue);
        final double finalGoldValue = pricing[0];
        final double finalMakingCost = pricing[1];
        final double finalTotalCost = pricing[3];
        final double finalProfitAmount = pricing[4];
        final double finalRetailPrice = pricing[5];
        final double netGoldWeight = pricing[6];

        // ✅ تعريف المتغيرات مرة واحدة فقط
        final int selectedKaratNum = getSelectedKaratNumber();
        MiraPriceManager.PricingDetails details = priceManager.calculatePricingDetails(selectedKaratNum, netGoldWeight);
        
        // ✅ حفظ الأسعار والعملة
        final String localCurrency = priceManager.getDefaultCurrency();
        final double finalPriceUSD = details.finalPrice + stoneValue;
        final double finalPriceLocal = priceManager.convertFromUSD(finalPriceUSD, localCurrency);
        final double exchangeRate = priceManager.getExchangeRate(localCurrency);

        final AlertDialog loadingDialog = showProfessionalLoadingDialog(
                isArabic ? "🔄 جاري التكويد..." : "🔄 Encoding in progress...",
                isArabic ? "يرجى الانتظار - يتم الآن حفظ القطعة" : "Please wait - saving item"
        );

        setFormEnabled(false);

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
        
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("epc", finalEpc);
                payload.put("title", finalTitle);
                payload.put("purity_code", selectedKarat);
                payload.put("gross_weight", fGrossWeight);
                payload.put("net_weight", netGoldWeight);
                payload.put("stone_weight", fStoneWeightCarats);
                payload.put("stone_count", fStoneCount);
                payload.put("stone_value", fStoneValue);
                payload.put("stone_type", fStoneType);
                
                // ✅ القيم المالية
                payload.put("gold_price_per_gram", details.gramPrice);
                payload.put("gold_value", details.baseGoldCost);
                payload.put("making_per_gram", priceManager.getMakingCharge(selectedKaratNum));
                payload.put("making_charge", details.totalMakingCost);
                payload.put("total_cost", details.baseGoldCost + details.totalMakingCost);
                payload.put("profit_margin", priceManager.getProfitMargin(selectedKaratNum));
                payload.put("profit_amount", details.profitAmount);
                
                // ✅ الأسعار والعملة
                payload.put("retail_price_usd", finalPriceUSD);        // السعر الأساسي بالدولار
                payload.put("retail_price", finalPriceUSD);            // للتوافق مع القديم
                payload.put("retail_price_local", finalPriceLocal);    // السعر المحلي
                payload.put("currency", "USD");                         // العملة الأساسية
                payload.put("local_currency", localCurrency);           // العملة المحلية
                payload.put("exchange_rate", exchangeRate);             // سعر الصرف
                payload.put("currency_symbol", priceManager.getCurrencySymbol(localCurrency));
                
                payload.put("category", selectedCategory);
                payload.put("location", location);
                payload.put("branch_id", branchId);
                payload.put("user_id", currentUserId);

                if (!finalPhotoPaths.isEmpty()) {
                    JSONArray imagesArray = new JSONArray();
                    for (String path : finalPhotoPaths) imagesArray.put(path);
                    payload.put("image_paths", imagesArray);
                }

                boolean apiSuccess = false;
                int serverItemId = 0;

                if (apiClient != null) {
                    try {
                        MiraApiClient.ApiResponse response = apiClient.encodeItem(payload);
                        apiSuccess = (response != null && response.isSuccess);
                        if (response != null && response.data != null) {
                            serverItemId = response.data.optInt("item_id", 0);
                        }
                    } catch (Exception ignored) {}
                }

                if (!apiSuccess) {
                    saveEncodeOffline(payload);
                }

                final boolean success = apiSuccess;
                final int finalServerItemId = serverItemId;

                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    setFormEnabled(true);

                    playSuccessSound();
                    if (success) {
                        removePendingEncode(finalEpc);
                        if (!finalPhotoPaths.isEmpty() && finalServerItemId > 0) {
                            uploadItemImages(finalServerItemId, finalPhotoPaths);
                        }
                    } else {
                        updatePendingSyncBadge();
                        showToast(isArabic ? "💾 تم الحفظ محلياً (أوفلاين)" : "💾 Saved locally (Offline)");
                    }
                    
                    if (success && finalServerItemId > 0) {
                    programTagAutomatically(finalServerItemId, finalEpc, finalTitle);
                    }
                    showEncodingSuccessDialog(finalTitle, finalEpc, String.format(Locale.US, "%.2f", fGrossWeight), finalPriceLocal);
                });
            } catch (Exception e) {
                Log.e(TAG, "Background execution error: " + e.getMessage());
                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    setFormEnabled(true);
                    showValidationError(e.getMessage());
                });
            }
        });
    } catch (Exception e) {
        Log.e(TAG, "finalizeItemIdentity error: " + e.getMessage());
        showToast("❌ " + e.getMessage());
    }
}
/**
 * ✅ برمجة الشريحة تلقائياً بعد حفظ القطعة
 */
private void programTagAutomatically(int itemId, String miraId, String title) {
    try {
        Log.d(TAG, "🔧 بدء البرمجة التلقائية للشريحة...");
        
        // ✅ الحصول على خدمة RFID
        MiraRfidService rfidService = MiraRfidService.getInstance(getContext());
        
        // ✅ إعادة تهيئة للشريحة الجديدة
        rfidService.resetForNewTag();
        
        // ✅ قراءة TID من الشريحة
        String tid = rfidService.readTid("00000000");
        Log.d(TAG, "TID: " + tid);
        
        if (tid == null || tid.isEmpty()) {
            Log.e(TAG, "❌ فشل قراءة TID");
            safeRun(() -> showToast(isArabic ? "⚠️ تم حفظ القطعة ولكن تعذر قراءة الشريحة" : "⚠️ Item saved but RFID read failed"));
            return;
        }
        
        // ✅ توليد EPC من معرف القطعة
        String generatedEpc = rfidService.generateEpcFromMiraId(miraId);
        Log.d(TAG, "Generated EPC: " + generatedEpc);
        
        // ✅ كتابة EPC على الشريحة
        boolean writeSuccess = rfidService.writeEpc(generatedEpc, "00000000");
        Log.d(TAG, "Write: " + writeSuccess);
        
        // ✅ التحقق من الكتابة
        boolean verifySuccess = writeSuccess && rfidService.verifyWrite(generatedEpc, "00000000");
        Log.d(TAG, "Verify: " + verifySuccess);
        
        // ✅ تسجيل الربط في قاعدة البيانات
        if (verifySuccess) {
            boolean registered = rfidService.registerRfidBinding(miraId, generatedEpc, tid);
            Log.d(TAG, "Register: " + registered);
            
            final boolean success = registered;
            final String finalEpc = generatedEpc;
            final String finalTid = tid;
            
            safeRun(() -> {
                if (success) {
                    showToast(isArabic ? "✅ تم برمجة الشريحة وربطها بالقطعة بنجاح!" : "✅ RFID programmed and bound successfully!");
                } else {
                    showToast(isArabic ? "⚠️ تم حفظ القطعة ولكن فشل تسجيل الربط" : "⚠️ Item saved but binding failed");
                }
            });
        } else {
            safeRun(() -> showToast(isArabic ? "⚠️ تم حفظ القطعة ولكن فشلت برمجة الشريحة" : "⚠️ Item saved but RFID programming failed"));
        }
        
    } catch (Exception e) {
        Log.e(TAG, "programTagAutomatically error: " + e.getMessage(), e);
        safeRun(() -> showToast(isArabic ? "⚠️ تم حفظ القطعة ولكن خطأ في البرمجة" : "⚠️ Item saved but programming error"));
    }
}
    private void showPendingItemsDialog() {
        if (offlineCache == null || !isAdded()) return;

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

            new AlertDialog.Builder(requireContext())
                    .setTitle(isArabic ? "العناصر المخزنة محلياً" : "Offline Stored Items")
                    .setMessage(builder.toString())
                    .setPositiveButton(isArabic ? "مزامنة الآن" : "Sync Now", (d, w) -> syncPendingEncodes())
                    .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                    .show();

        } catch (Exception e) {
            Log.e(TAG, "showPendingItemsDialog error: " + e.getMessage());
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
                        photoPaths.add(currentPhotoPath);
                        onPhotosUpdated();
                    }
                }
        );

        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                uris -> {
                    if (uris != null && !uris.isEmpty()) {
                        for (Uri uri : uris) {
                            String path = copyImageToInternalStorage(uri);
                            if (path != null) photoPaths.add(path);
                        }
                        onPhotosUpdated();
                    }
                }
        );
    }

    private void showImageSourceDialog() {
        if (getContext() == null) return;
        if (!(permissionManager.canEdit("identity") || permissionManager.isManager())) {
            showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
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
            Log.e(TAG, "openCamera error: " + e.getMessage());
        }
    }

    private void openGallery() {
        try {
            pickImageLauncher.launch(new String[]{"image/*"});
        } catch (Exception e) {
            Log.e(TAG, "openGallery error: " + e.getMessage());
        }
    }

    private File createImageFile() throws IOException {
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

            InputStream inputStream = getContext().getContentResolver().openInputStream(uri);
            FileOutputStream outputStream = new FileOutputStream(destFile);

            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) outputStream.write(buffer, 0, length);

            outputStream.close();
            inputStream.close();
            return destFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "copyImageToInternalStorage error: " + e.getMessage());
            return null;
        }
    }

    private void onPhotosUpdated() {
        if (photosAdapter != null) photosAdapter.notifyDataSetChanged();
        updatePhotosCountText();
        if (binding.rvPhotosPreview != null) {
            binding.rvPhotosPreview.setVisibility(photoPaths.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void updatePhotosCountText() {
        try {
            if (binding == null || binding.tvPhotosCount == null) return;
            int count = photoPaths.size();
            binding.tvPhotosCount.setText(count == 0 ?
                    (isArabic ? "لم يتم إرفاق صور بعد" : "No photos attached yet") :
                    (isArabic ? "تم إرفاق (" + count + ") صور" : "Attached (" + count + ") photos"));
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
            Log.e(TAG, "Barcode scanner error: " + e.getMessage());
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
                binding.btnScanRfid.setText(isArabic ? "إيقاف المسح..." : "Stop Scan...");
                binding.tvStatusBadge.setText("SCANNING...");

                activity.setActiveRfidListener(epc -> {
                    if (binding != null && binding.etEpc != null) {
                        binding.etEpc.setText(epc);
                        binding.tvStatusBadge.setText("BOUND");
                    }
                    activity.playSound(1);
                    stopRfidScanningMode();
                });

                activity.startUHFScan();
                showToast(isArabic ? "📡 جاري البحث عن شريحة RFID..." : "📡 Searching for RFID tag...");

                if (rfidTimeoutRunnable != null) handler.removeCallbacks(rfidTimeoutRunnable);
                rfidTimeoutRunnable = this::stopRfidScanningMode;
                handler.postDelayed(rfidTimeoutRunnable, RFID_TIMEOUT_MS);
            }
        } catch (Exception e) {
            Log.e(TAG, "activateRFIDMode error: " + e.getMessage());
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
            Log.e(TAG, "stopRfidScanningMode error: " + e.getMessage());
        }
    }

    public void onRfidScanned(String epc) {
        if (getActivity() == null || binding == null) return;
        getActivity().runOnUiThread(() -> {
            binding.etEpc.setText(epc);
            binding.tvStatusBadge.setText("BOUND");
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).playSound(1);
            }
            stopRfidScanningMode();
        });
    }

    private boolean isValidEPC(String epc) {
        if (epc == null || epc.isEmpty()) return false;
        if (epc.matches("^[A-Fa-f0-9]{24,}$")) return true;
        if (epc.matches("^\\d{13}$")) return true;
        return epc.matches("^[A-Za-z0-9\\-]{6,50}$");
    }

    private void populateSuggestions(String typeKey) {
        try {
            binding.chipGroupSuggestions.removeAllViews();
            String[] suggestions = typeSuggestions.get(typeKey);
            if (suggestions == null || getContext() == null) return;
            for (String suggestion : suggestions) {
                Chip chip = new Chip(requireContext());
                chip.setText(suggestion);
                chip.setClickable(true);
                chip.setCheckable(false);
                chip.setOnClickListener(v -> binding.etTitle.setText(suggestion));
                binding.chipGroupSuggestions.addView(chip);
            }
        } catch (Exception e) {
            Log.e(TAG, "populateSuggestions error: " + e.getMessage());
        }
    }

    private void showEncodingSuccessDialog(String title, String epc, String weight, double finalPrice) {
    if (!isAdded() || getContext() == null) return;

    BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(requireContext());
    View view = getLayoutInflater().inflate(R.layout.dialog_encoding_success, null);
    bottomSheetDialog.setContentView(view);

    TextView tvTitle = view.findViewById(R.id.tvSummaryTitle);
    TextView tvEpc = view.findViewById(R.id.tvSummaryEpc);
    TextView tvWeight = view.findViewById(R.id.tvSummaryWeight);
    TextView tvPrice = view.findViewById(R.id.tvSummaryPrice); // ✅ جديد
    ImageView imgItemPhoto = view.findViewById(R.id.imgSummaryPhoto); // ✅ جديد

    MaterialButton btnViewDigitalIdentity = view.findViewById(R.id.btnViewDigitalIdentity);
    MaterialButton btnNewEncode = view.findViewById(R.id.btnNewEncode);
    MaterialButton btnClose = view.findViewById(R.id.btnCloseDialog);

    if (tvTitle != null) tvTitle.setText(title);
    if (tvEpc != null) tvEpc.setText(epc);
    if (tvWeight != null) tvWeight.setText(weight + (isArabic ? " جرام" : " g"));
    
    // ✅ عرض المبلغ
    if (tvPrice != null && priceManager != null) {
        tvPrice.setText(priceManager.formatPriceDefault(finalPrice));
    }
    
    // ✅ عرض الصورة الأولى
    if (imgItemPhoto != null && !photoPaths.isEmpty()) {
        File photoFile = new File(photoPaths.get(0));
        if (photoFile.exists()) {
            imgItemPhoto.setImageURI(Uri.fromFile(photoFile));
            imgItemPhoto.setVisibility(View.VISIBLE);
        }
    }

    // ✅ تمرير البيانات إلى الديجيتال آيد
    final String firstPhotoPath = !photoPaths.isEmpty() ? photoPaths.get(0) : null;

    if (btnNewEncode != null) {
        btnNewEncode.requestFocus();
        btnNewEncode.setOnClickListener(v -> {
            bottomSheetDialog.dismiss();
            resetFormForNextScan();
        });
    }

    if (btnViewDigitalIdentity != null) {
        btnViewDigitalIdentity.setOnClickListener(v -> {
            bottomSheetDialog.dismiss();
            showDigitalIdentityDialog(title, epc, weight, finalPrice, firstPhotoPath);
        });
    }

    if (btnClose != null) {
        btnClose.setOnClickListener(v -> {
            bottomSheetDialog.dismiss();
            resetFormForNextScan();
        });
    }

    bottomSheetDialog.show();
}


    private void showDigitalIdentityDialog(String title, String epc, String weight, double finalPrice, String photoPath) {
    if (!isAdded() || getContext() == null) return;

    BottomSheetDialog identityDialog = new BottomSheetDialog(requireContext());
    View view = getLayoutInflater().inflate(R.layout.dialog_digital_identity, null);
    identityDialog.setContentView(view);

    TextView tvIdTitle = view.findViewById(R.id.tvIdTitle);
    TextView tvEpcCode = view.findViewById(R.id.tvEpcCode);
    TextView tvIdWeight = view.findViewById(R.id.tvIdWeight);
    TextView tvIdMetalKarat = view.findViewById(R.id.tvIdMetalKarat);
    TextView tvIdPrice = view.findViewById(R.id.tvIdPrice); // ✅ جديد
    ImageView imgIdentityPhoto = view.findViewById(R.id.imgIdentityPhoto); // ✅ جديد
    MaterialButton btnClose = view.findViewById(R.id.btnCloseIdentity);

    if (tvIdTitle != null) tvIdTitle.setText(title);
    if (tvEpcCode != null) tvEpcCode.setText("EPC: " + epc);
    if (tvIdWeight != null) tvIdWeight.setText(weight + (isArabic ? " جرام" : " g"));
    if (tvIdMetalKarat != null) tvIdMetalKarat.setText(getSelectedKaratCode());
    
    // ✅ عرض المبلغ
    if (tvIdPrice != null && priceManager != null) {
        tvIdPrice.setText(priceManager.formatPriceDefault(finalPrice));
    }
    
    // ✅ عرض الصورة
    if (imgIdentityPhoto != null && photoPath != null) {
        File photoFile = new File(photoPath);
        if (photoFile.exists()) {
            imgIdentityPhoto.setImageURI(Uri.fromFile(photoFile));
            imgIdentityPhoto.setVisibility(View.VISIBLE);
        }
    }

    if (btnClose != null) {
        btnClose.setOnClickListener(v -> {
            identityDialog.dismiss();
            resetFormForNextScan();
        });
    }

    identityDialog.show();
}

    private AlertDialog showProfessionalLoadingDialog(String title, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setCancelable(false);

        ProgressBar progressBar = new ProgressBar(requireContext());
        progressBar.setIndeterminate(true);
        progressBar.setPadding(40, 20, 40, 20);
        builder.setView(progressBar);

        AlertDialog dialog = builder.create();
        dialog.show();
        return dialog;
    }

    private void setFormEnabled(boolean enabled) {
        try {
            boolean canEdit = permissionManager.canEdit("identity") || permissionManager.isManager();
            
            binding.btnSaveIdentity.setEnabled(enabled && canEdit);
            binding.btnAddPhotos.setEnabled(enabled && canEdit);
            binding.etEpc.setEnabled(enabled && canEdit);
            binding.etTitle.setEnabled(enabled && canEdit);
            binding.etGrossWeight.setEnabled(enabled && canEdit);
            binding.switchHasStones.setEnabled(enabled && canEdit);
            binding.etStoneType.setEnabled(enabled && canEdit);
            binding.etStoneWeight.setEnabled(enabled && canEdit);
            binding.etStoneCount.setEnabled(enabled && canEdit);
            binding.etStoneValue.setEnabled(enabled && canEdit);
            binding.chipGroupKarat.setEnabled(enabled && canEdit);
            binding.chipGroupTypes.setEnabled(enabled && canEdit);
            binding.btnScanRfid.setEnabled(enabled && canEdit);

            binding.btnSaveIdentity.setAlpha((enabled && canEdit) ? 1.0f : 0.5f);
        } catch (Exception e) {
            Log.e(TAG, "setFormEnabled error: " + e.getMessage());
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

            new AlertDialog.Builder(requireContext())
                    .setTitle(isArabic ? "⚠️ تنبيه الحماية" : "⚠️ Validation Alert")
                    .setMessage(message)
                    .setPositiveButton(isArabic ? "حسناً" : "OK", null)
                    .show();
        } catch (Exception e) {
            showToast(message);
        }
    }

    private void uploadItemImages(int itemId, List<String> imagePaths) {
        if (imagePaths == null || imagePaths.isEmpty() || itemId <= 0 || apiClient == null) return;

        new Thread(() -> {
            int uploaded = 0;
            for (String path : imagePaths) {
                File imageFile = new File(path);
                if (!imageFile.exists()) continue;
                try {
                    boolean isPrimary = (imagePaths.indexOf(path) == 0);
                    MiraApiClient.ApiResponse response = apiClient.uploadItemImage(itemId, imageFile, isPrimary, "Image");
                    if (response != null && response.isSuccess) uploaded++;
                } catch (Exception ignored) {}
            }

            final int fUploaded = uploaded;
            safeRun(() -> {
                if (fUploaded > 0) {
                    showToast(isArabic ? "📷 تم رفع " + fUploaded + " صورة" : "📷 " + fUploaded + " images uploaded");
                }
            });
        }).start();
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
            Log.e(TAG, "Save encode offline error: " + e.getMessage());
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
        } catch (Exception ignored) {}
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
        } catch (Exception ignored) {}
    }

    private void syncPendingEncodes() {
        if (apiClient == null || offlineCache == null) return;
        if (!(permissionManager.canEdit("identity") || permissionManager.isManager())) return;
        
        final int branchId = (branchesManager != null) ? branchesManager.getActiveBranchId() : 1;

        new Thread(() -> {
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
                Log.e(TAG, "Sync encodes error: " + e.getMessage());
            }
        }).start();
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

    private void playSuccessSound() {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).playSound(1);
            }
        } catch (Exception e) {
            Log.e(TAG, "playSuccessSound error: " + e.getMessage());
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

            activateRFIDModeForEncoding();
        } catch (Exception e) {
            Log.e(TAG, "resetFormForNextScan error: " + e.getMessage());
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopRfidScanningMode();
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
            holder.imgThumbnail.setImageURI(Uri.fromFile(new File(path)));
            holder.btnDelete.setOnClickListener(v -> {
                if (deleteListener != null) deleteListener.onDelete(holder.getAdapterPosition());
            });
        }

        @Override
        public int getItemCount() {
            return paths.size();
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
