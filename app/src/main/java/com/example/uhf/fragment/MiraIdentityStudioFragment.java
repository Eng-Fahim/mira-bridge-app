package com.example.uhf.fragment;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import androidx.fragment.app.Fragment;
import android.os.Handler;

import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.databinding.FragmentMiraIdentityStudioBinding;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.fragment.MiraBarcodeScannerFragment;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

public class MiraIdentityStudioFragment extends Fragment {

    private static final String TAG = "MiraIdentityStudio";
    private static final String OFFLINE_PREFS = "identity_studio_cache";

    private FragmentMiraIdentityStudioBinding binding;
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private boolean isArabic;
    private SharedPreferences offlineCache;

    private final Map<String, String[]> typeSuggestions = new HashMap<>();
    
    private String currentPhotoPath;
    private final List<String> photoPaths = new ArrayList<>();
    private ActivityResultLauncher<Uri> takePhotoLauncher;
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private Uri photoUri;
    
    // ==================== متغيرات جديدة في بداية الكلاس ====================
private double goldPrice24K = 75.0;
private double goldPrice21K = 65.62;
private double goldPrice18K = 56.25;
private double goldPrice14K = 43.75;
private double makingChargePerGram = 10.0;
private double profitMarginPercent = 90.0;
private boolean goldRatesLoaded = false;

// ✅ ضع الدالة هنا
private double getGoldPriceByKarat() {
    int checkedId = binding.chipGroupKarat.getCheckedChipId();
    if (checkedId == binding.chip24K.getId()) return goldPrice24K;
    else if (checkedId == binding.chip21K.getId()) return goldPrice21K;
    else if (checkedId == binding.chip18K.getId()) return goldPrice18K;
    else if (checkedId == binding.chip14K.getId()) return goldPrice14K;
    return goldPrice21K;
}

// ==================== دوال جديدة ====================
private void loadGoldRates() {
    if (apiClient == null) return;
    
    new Thread(() -> {
        try {
            MiraApiClient.ApiResponse response = apiClient.getGoldRatesAndSettings();
            if (response != null && response.isSuccess && response.data != null) {
                JSONObject rates = response.data.optJSONObject("live_rates");
                if (rates != null) {
                    goldPrice24K = rates.optDouble("24K", 75.0);
                    goldPrice21K = rates.optDouble("21K", 65.62);
                    goldPrice18K = rates.optDouble("18K", 56.25);
                    goldPrice14K = rates.optDouble("14K", 43.75);
                }
                
                JSONObject makings = response.data.optJSONObject("default_making_charges");
                if (makings != null) {
                    makingChargePerGram = makings.optDouble("21K", 10.0);
                }
                
                JSONObject margins = response.data.optJSONObject("default_profit_margins");
                if (margins != null) {
                    profitMarginPercent = margins.optDouble("21K", 90.0);
                }
                
                goldRatesLoaded = true;
                
                // تحديث السعر المعروض بعد جلب البيانات
                safeRun(this::calculateEstimatedGoldValue);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading gold rates: " + e.getMessage());
        }
    }).start();
}

/**
 * 🟢 حساب السعر التقديري الكامل (ذهب + مصنعية + هامش ربح)
 */
private void calculateEstimatedGoldValue() {
    String weightStr = binding.etGrossWeight.getText() != null ? 
                       binding.etGrossWeight.getText().toString().trim() : "";
    
    if (weightStr.isEmpty()) { 
        binding.tvEstimatedGoldValue.setText(isArabic ? "0.00 ريال" : "0.00 YER"); 
        return; 
    }
    
    try {
        double weight = Double.parseDouble(weightStr);
        
        // 1. سعر الذهب الخام
        double gramPrice = getGoldPriceByKarat();
        double baseGoldCost = weight * gramPrice;
        
        // 2. المصنعية
        double totalMakingCost = weight * makingChargePerGram;
        
        // 3. الإجمالي مع هامش الربح
        double subtotal = baseGoldCost + totalMakingCost;
        double finalPrice = subtotal * (profitMarginPercent / 100.0);
        
        // عرض السعر النهائي
        binding.tvEstimatedGoldValue.setText(String.format(Locale.US, 
            "%,.0f %s", finalPrice, isArabic ? "ريال" : "YER"));
            
    } catch (NumberFormatException e) {
        binding.tvEstimatedGoldValue.setText(isArabic ? "0.00 ريال" : "0.00 YER");
    }
}



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
        apiClient = MiraApiClient.getInstance(getContext());
        branchesManager = MiraBranchesManager.getInstance(getContext());
        orgProfile = MiraOrgProfileManager.getInstance(getContext());
        offlineCache = getContext().getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
    }
    isArabic = Locale.getDefault().getLanguage().equals("ar");

    initSuggestionsData();
    setupPhotoLaunchers();
    applyLanguage();
    setupListeners();
    loadGoldRates();
    
    // ✅ تسجيل هذا الـ fragment كمستقبل RFID
    if (getActivity() instanceof UHFMainActivity) {
        ((UHFMainActivity) getActivity()).setActiveEncodingFragment(this);
    }
}

@Override
public void onResume() {
    super.onResume();
    syncPendingEncodes();
    // ✅ إعادة تسجيل كمستقبل RFID
    if (getActivity() instanceof UHFMainActivity) {
        ((UHFMainActivity) getActivity()).setActiveEncodingFragment(this);
    }
}

@Override
public void onPause() {
    super.onPause();
    // ✅ إلغاء التسجيل
    if (getActivity() instanceof UHFMainActivity) {
        ((UHFMainActivity) getActivity()).setActiveEncodingFragment(null);
    }
}


    // ==================== Photo Launchers ====================

    private void setupPhotoLaunchers() {
        requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) openCamera();
                else Toast.makeText(getContext(),
                    isArabic ? "صلاحية الكاميرا مطلوبة" : "Camera permission required",
                    Toast.LENGTH_SHORT).show();
            }
        );

        takePhotoLauncher = registerForActivityResult(
            new ActivityResultContracts.TakePicture(),
            success -> {
                if (success && currentPhotoPath != null) {
                    photoPaths.add(currentPhotoPath);
                    updatePhotosCount();
                }
            }
        );
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
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getContext().getPackageManager()) != null) {
            File photoFile = null;
            try { photoFile = createImageFile(); }
            catch (IOException ex) { Log.e(TAG, "Error creating image file: " + ex.getMessage()); return; }
            if (photoFile != null) {
                try {
                    photoUri = FileProvider.getUriForFile(getContext(),
                        getContext().getApplicationContext().getPackageName() + ".fileprovider", photoFile);
                    takePhotoLauncher.launch(photoUri);
                } catch (Exception e) {
                    photoUri = Uri.fromFile(photoFile);
                    takePhotoLauncher.launch(photoUri);
                }
            }
        }
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File storageDir = getContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile("MIRA_" + timeStamp + "_", ".jpg", storageDir);
        currentPhotoPath = image.getAbsolutePath();
        return image;
    }

    private void updatePhotosCount() {
        int count = photoPaths.size();
        if (binding.tvPhotosCount != null) {
            binding.tvPhotosCount.setText(count == 0 ? 
                (isArabic ? "لم يتم إرفاق صور بعد" : "No photos attached yet") :
                (isArabic ? "تم إرفاق (" + count + ") صور" : "Attached (" + count + ") photos"));
        }
    }

    // ==================== Language ====================

    private void applyLanguage() {
        if (binding == null) return;
        if (isArabic) setArabicTexts(); else setEnglishTexts();
    }

    private void setArabicTexts() {
        binding.tvStepTitle.setText("الخطوة 1 من 4: الربط والتصنيف");
        binding.etEpc.setHint("رمز الشريحة (EPC / GTIN)...");
        binding.etTitle.setHint("عنوان ووصف القطعة");
        binding.btnSaveIdentity.setText("اعتماد الموزون وتصدير MIRA ID");
        binding.btnAddPhotos.setText("📷 التقاط / إرفاق صور القطعة");
        binding.tvPhotosCount.setText("لم يتم إرفاق صور بعد");
        binding.tvEstimatedGoldValue.setText("0.00 ريال");
    }

    private void setEnglishTexts() {
        binding.tvStepTitle.setText("Step 1 of 4: Bind & Classify");
        binding.etEpc.setHint("EPC / GTIN Code...");
        binding.etTitle.setHint("Item Title & Description");
        binding.btnSaveIdentity.setText("Approve & Export MIRA ID");
        binding.btnAddPhotos.setText("📷 Capture / Attach Photos");
        binding.tvPhotosCount.setText("No photos attached yet");
        binding.tvEstimatedGoldValue.setText("0.00 YER");
    }

    private void initSuggestionsData() {
        if (isArabic) {
            typeSuggestions.put("rings", new String[]{"خاتم سوليتير ألماسي", "خاتم محبس كلاسيك", "خاتم توينز راقي"});
            typeSuggestions.put("sets", new String[]{"طقم شبكة عروس كامل", "طقم ملكي مرصع بالألماس"});
            typeSuggestions.put("bracelets", new String[]{"بناجر / أساور خليجي", "سوار تنادي عصري"});
            typeSuggestions.put("necklaces", new String[]{"عقد شوكر عصري", "سلسلة ذهبية ناعمة"});
        } else {
            typeSuggestions.put("rings", new String[]{"Diamond Solitaire Ring", "Classic Band Ring"});
            typeSuggestions.put("sets", new String[]{"Complete Bridal Set", "Royal Diamond Set"});
            typeSuggestions.put("bracelets", new String[]{"Khaleeji Bangle", "Modern Tennis Bracelet"});
            typeSuggestions.put("necklaces", new String[]{"Modern Choker", "Fine Gold Chain"});
        }
    }

    private void setupListeners() {
        binding.etEpc.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                binding.tvStatusBadge.setText(s.length() > 0 ? "BOUND" : "WAITING");
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        
        // ==================== في setupListeners() - أضف هذا ====================

// ✅ تفعيل أيقونة الكاميرا في حقل EPC لفتح ماسح الباركود
if (binding.tilEpc != null) {
    binding.tilEpc.setEndIconOnClickListener(v -> {
        openBarcodeScannerForEPC();
    });
}

// ✅ زر إضافي لتفعيل وضع RFID (إذا كان موجوداً في الواجهة)
if (binding.btnScanRfid != null) {
    binding.btnScanRfid.setOnClickListener(v -> {
        activateRFIDModeForEncoding();
    });
}

        binding.switchHasStones.setOnCheckedChangeListener((buttonView, isChecked) -> {
            binding.layoutStonesDetails.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        binding.etGrossWeight.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { calculateEstimatedGoldValue(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        binding.chipGroupKarat.setOnCheckedStateChangeListener((group, checkedIds) -> calculateEstimatedGoldValue());

        binding.chipGroupTypes.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == binding.chipRing.getId()) populateSuggestions("rings");
            else if (id == binding.chipSet.getId()) populateSuggestions("sets");
            else if (id == binding.chipBracelet.getId()) populateSuggestions("bracelets");
            else if (id == binding.chipNecklace.getId()) populateSuggestions("necklaces");
        });

        binding.btnAddPhotos.setOnClickListener(v -> checkCameraPermissionAndTakePhoto());
        binding.btnSaveIdentity.setOnClickListener(v -> finalizeItemIdentity());
    }
// ==================== دوال المسح للتكويد ====================

/**
 * 🟢 فتح ماسح الباركود لقراءة EPC
 */
private void openBarcodeScannerForEPC() {
    if (!isAdded()) return;
    
    try {
        MiraBarcodeScannerFragment sf = new MiraBarcodeScannerFragment();
        sf.setOnBarcodeScannedListener((data, format) -> {
            // وضع الـ EPC المقروء في الحقل
            if (binding.etEpc != null) {
                binding.etEpc.setText(data);
                binding.tvStatusBadge.setText("BOUND");
            }
            // الرجوع من الماسح
            if (getParentFragmentManager().getBackStackEntryCount() > 0) {
                getParentFragmentManager().popBackStack();
            }
        });
        
        View view = getView();
        int cid = (view != null && view.getParent() instanceof View) ? 
                ((View) view.getParent()).getId() : android.R.id.content;
        getParentFragmentManager().beginTransaction()
                .replace(cid, sf)
                .addToBackStack("barcode_scanner_encode")
                .commit();
    } catch (Exception e) {
        Log.e(TAG, "Barcode scanner error: " + e.getMessage());
        showToast(isArabic ? "❌ خطأ في فتح الماسح" : "❌ Scanner error");
    }
}

/**
 * 🟢 تفعيل وضع RFID لاستقبال الشرائح الممسوحة
 */
private void activateRFIDModeForEncoding() {
    if (getActivity() instanceof UHFMainActivity) {
        UHFMainActivity activity = (UHFMainActivity) getActivity();
        
        // تسجيل هذا الـ fragment كمستقبل لمسح RFID
        activity.setActiveRfidListener(epc -> {
            if (binding.etEpc != null) {
                binding.etEpc.setText(epc);
                binding.tvStatusBadge.setText("BOUND");
            }
            if (activity != null) activity.playSound(1);
        });
        
        // تفعيل وضع RFID
        activity.startUHFScan();
        
        showToast(isArabic ? "📡 جاهز لمسح RFID - امسح الشريحة" : "📡 Ready for RFID scan");
        
        // إيقاف تلقائي بعد 30 ثانية
        new Handler().postDelayed(() -> {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).stopUHFScan();
                ((UHFMainActivity) getActivity()).setActiveRfidListener(null);
            }
        }, 30000);
    } else {
        showToast(isArabic ? "❌ القارئ غير متاح" : "❌ Reader not available");
    }
}

/**
 * 🟢 استقبال RFID (يُستدعى من UHFMainActivity)
 */
public void onRfidScanned(String epc) {
    if (getActivity() == null) return;
    getActivity().runOnUiThread(() -> {
        binding.etEpc.setText(epc);
        binding.tvStatusBadge.setText("BOUND");
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).playSound(1);
        }
    });
}

    
    /**
 * التحقق من صحة صيغة RFID EPC
 */
private boolean isValidEPC(String epc) {
    // EPC يجب أن يكون 6 أحرف سداسية عشرية على الأقل
    return epc != null && epc.matches("^[A-Fa-f0-9]{6,}$");
}

   

    

    private void populateSuggestions(String typeKey) {
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
    }

    // ==================== SAVE IDENTITY ====================

    // ==================== SAVE IDENTITY - النسخة المحسّنة ====================

private void finalizeItemIdentity() {
    String epc = binding.etEpc.getText() != null ? binding.etEpc.getText().toString().trim() : "";
    String title = binding.etTitle.getText() != null ? binding.etTitle.getText().toString().trim() : "";
    String weightStr = binding.etGrossWeight.getText() != null ? binding.etGrossWeight.getText().toString().trim() : "";

    // ========== التحققات ==========
    if (epc.isEmpty()) { showToast(isArabic ? "يرجى مسح RFID أولاً" : "Please scan RFID first"); return; }
    if (!isValidEPC(epc)) { showToast(isArabic ? "صيغة RFID غير صالحة" : "Invalid RFID format"); return; }
    if (title.isEmpty()) { showToast(isArabic ? "يرجى كتابة عنوان القطعة" : "Please enter item title"); return; }
    if (weightStr.isEmpty()) { showToast(isArabic ? "يرجى تحديد وزن القطعة" : "Please specify item weight"); return; }

    double grossWeight;
    try { grossWeight = Double.parseDouble(weightStr); }
    catch (NumberFormatException e) { showToast(isArabic ? "وزن غير صالح" : "Invalid weight"); return; }

    if (grossWeight <= 0) {
        showToast(isArabic ? "يجب أن يكون الوزن أكبر من صفر" : "Weight must be greater than zero");
        return;
    }

    // ========== جمع بيانات الأحجار ==========
    String stoneType = "";
    double stoneWeight = 0; int stoneCount = 0; double stoneValue = 0;
    
    if (binding.switchHasStones.isChecked()) {
        stoneType = binding.etStoneType.getText() != null ? binding.etStoneType.getText().toString().trim() : "";
        try { stoneWeight = Double.parseDouble(binding.etStoneWeight.getText().toString().trim()); } catch (Exception ignored) {}
        try { stoneCount = Integer.parseInt(binding.etStoneCount.getText().toString().trim()); } catch (Exception ignored) {}
        try { stoneValue = Double.parseDouble(binding.etStoneValue.getText().toString().trim()); } catch (Exception ignored) {}
    }

    // ========== إظهار Dialog التحميل ==========
    final AlertDialog loadingDialog = showLoadingDialog();
    
    // ========== تعطيل الأزرار ==========
    binding.btnSaveIdentity.setEnabled(false);
    binding.btnAddPhotos.setEnabled(false);
    binding.etEpc.setEnabled(false);
    binding.etTitle.setEnabled(false);
    binding.etGrossWeight.setEnabled(false);

    // ========== المتغيرات النهائية ==========
    final String finalEpc = epc;
    final String finalTitle = title;
    final double finalGrossWeight = grossWeight;
    final double finalStoneWeight = stoneWeight;
    final int finalStoneCount = stoneCount;
    final double finalStoneValue = stoneValue;
    final String finalStoneType = stoneType;
    final String selectedKarat = getSelectedKaratCode();
    final String selectedCategory = getSelectedCategory();
    final String location = isArabic ? "المعرض الرئيسي" : "Main Showroom";
    final int branchId = (branchesManager != null) ? branchesManager.getActiveBranchId() : 1;
    final List<String> finalPhotoPaths = new ArrayList<>(photoPaths);

    Executors.newSingleThreadExecutor().execute(() -> {
        try {
            // ========== بناء payload ==========
            JSONObject payload = new JSONObject();
            payload.put("epc", finalEpc);
            payload.put("title", finalTitle);
            payload.put("purity_code", selectedKarat);
            payload.put("gross_weight", finalGrossWeight);
            payload.put("net_weight", finalGrossWeight - finalStoneWeight);
            payload.put("stone_weight", finalStoneWeight);
            payload.put("stone_count", finalStoneCount);
            payload.put("stone_value", finalStoneValue);
            payload.put("stone_type", finalStoneType);
            payload.put("making_per_gram", 0);
            payload.put("profit_margin", 0);
            payload.put("category", selectedCategory);
            payload.put("location", location);
            payload.put("branch_id", branchId);
            
            // إضافة مسارات الصور إن وجدت
            if (!finalPhotoPaths.isEmpty()) {
                JSONArray imagesArray = new JSONArray();
                for (String path : finalPhotoPaths) {
                    imagesArray.put(path);
                }
                payload.put("image_paths", imagesArray);
            }

            // ========== محاولة API ==========
            boolean apiSuccess = false;
            String serverMessage = "";
            String serverBarcode = "";
            String serverSerial = "";
            int serverItemId = 0;
            
            try {
                MiraApiClient.ApiResponse response = apiClient.encodeItem(payload);
                apiSuccess = (response != null && response.isSuccess);
                if (response != null && response.data != null) {
                    serverMessage = response.data.optString("message", "");
                    serverBarcode = response.data.optString("barcode", "");
                    serverSerial = response.data.optString("serial_number", "");
                    serverItemId = response.data.optInt("item_id", 0);
                }
            } catch (Exception apiError) {
                Log.e(TAG, "API encode failed: " + apiError.getMessage());
            }

            final boolean success = apiSuccess;
            final String finalServerMessage = serverMessage;
            final String finalServerBarcode = serverBarcode;
            final String finalServerSerial = serverSerial;
            final int finalServerItemId = serverItemId;

            safeRun(() -> {
                // ========== إغلاق Dialog التحميل ==========
                if (loadingDialog != null && loadingDialog.isShowing()) {
                    loadingDialog.dismiss();
                }
                
                // ========== إعادة تفعيل الحقول ==========
                binding.btnSaveIdentity.setEnabled(true);
                binding.btnAddPhotos.setEnabled(true);
                binding.etEpc.setEnabled(true);
                binding.etTitle.setEnabled(true);
                binding.etGrossWeight.setEnabled(true);
                binding.btnSaveIdentity.setText(isArabic ? "اعتماد الموزون وتصدير MIRA ID" : "Approve & Export MIRA ID");

                if (success) {
    // ========== نجاح - عرض Dialog النجاح مع التفاصيل ==========
    removePendingEncode(finalEpc);
    playSuccessSound();
    showSuccessDialog(finalEpc, finalTitle, selectedKarat, finalGrossWeight, 
                      finalServerBarcode, finalServerSerial, finalServerItemId);
    
    // ✅ رفع الصور بعد نجاح التكويد
    if (!finalPhotoPaths.isEmpty() && finalServerItemId > 0) {
        uploadItemImages(finalServerItemId, finalPhotoPaths);
    }
    
    resetFormForNextScan();
} else {
                    // ========== فشل - حفظ محلي ==========
                    saveEncodeOffline(payload);
                    showToast(isArabic ? "💾 تم حفظ التكويد محلياً للمزامنة لاحقاً" : "💾 Encoding saved locally for later sync");
                    resetFormForNextScan();
                }
            });
        } catch (Exception e) {
            safeRun(() -> {
                if (loadingDialog != null && loadingDialog.isShowing()) {
                    loadingDialog.dismiss();
                }
                binding.btnSaveIdentity.setEnabled(true);
                binding.btnAddPhotos.setEnabled(true);
                binding.etEpc.setEnabled(true);
                binding.etTitle.setEnabled(true);
                binding.etGrossWeight.setEnabled(true);
                binding.btnSaveIdentity.setText(isArabic ? "اعتماد الموزون وتصدير MIRA ID" : "Approve & Export MIRA ID");
                showToast((isArabic ? "❌ خطأ: " : "❌ Error: ") + e.getMessage());
            });
        }
    });
}

// ==================== رفع الصور ====================

/**
 * 🟢 رفع الصور إلى السيرفر بعد نجاح التكويد
 */
private void uploadItemImages(int itemId, List<String> imagePaths) {
    if (imagePaths == null || imagePaths.isEmpty() || itemId <= 0) return;
    if (apiClient == null) return;
    
    new Thread(() -> {
        int uploaded = 0;
        int failed = 0;
        
        for (int i = 0; i < imagePaths.size(); i++) {
            String path = imagePaths.get(i);
            File imageFile = new File(path);
            if (!imageFile.exists()) {
                failed++;
                continue;
            }
            
            try {
                boolean isPrimary = (i == 0); // أول صورة هي الأساسية
                MiraApiClient.ApiResponse response = apiClient.uploadItemImage(
                    itemId, imageFile, isPrimary, 
                    isArabic ? "صورة " + (i + 1) : "Image " + (i + 1)
                );
                
                if (response != null && response.isSuccess) {
                    uploaded++;
                    Log.i(TAG, "✅ Image uploaded: " + path);
                } else {
                    failed++;
                    Log.w(TAG, "❌ Failed to upload: " + path);
                }
            } catch (Exception e) {
                failed++;
                Log.e(TAG, "Upload error: " + e.getMessage());
            }
        }
        
        final int finalUploaded = uploaded;
        final int finalFailed = failed;
        safeRun(() -> {
            if (finalUploaded > 0) {
                String msg = isArabic ? 
                    "📷 تم رفع " + finalUploaded + " صورة" : 
                    "📷 " + finalUploaded + " images uploaded";
                if (finalFailed > 0) {
                    msg += isArabic ? 
                        " (فشل " + finalFailed + ")" : 
                        " (" + finalFailed + " failed)";
                }
                showToast(msg);
            }
        });
    }).start();
}

/**
 * 🟢 رفع الصور للقطعة المخزنة محلياً عند المزامنة
 */
private void uploadPendingImages(int itemId, JSONArray imagePathsArray) {
    if (imagePathsArray == null || imagePathsArray.length() == 0 || itemId <= 0) return;
    
    List<String> paths = new ArrayList<>();
    for (int i = 0; i < imagePathsArray.length(); i++) {
        try {
            String path = imagePathsArray.optString(i, "");
            if (!path.isEmpty()) paths.add(path);
        } catch (Exception ignored) {}
    }
    
    uploadItemImages(itemId, paths);
}

// ==================== DIALOGS ====================

/**
 * عرض Dialog تحميل احترافي أثناء عملية التكويد
 */
private AlertDialog showLoadingDialog() {
    AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
    
    // إنشاء محتوى مخصص
    LinearLayout layout = new LinearLayout(requireContext());
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(40, 30, 40, 30);
    layout.setGravity(android.view.Gravity.CENTER);
    
    ProgressBar progressBar = new ProgressBar(requireContext());
    progressBar.setIndeterminate(true);
    layout.addView(progressBar);
    
    TextView tvLoading = new TextView(requireContext());
    tvLoading.setText(isArabic ? "🔄 جاري التكويد والربط السحابي..." : "🔄 Encoding & Cloud Linking...");
    tvLoading.setTextColor(Color.parseColor("#1E40AF"));
    tvLoading.setTextSize(14);
    tvLoading.setPadding(0, 16, 0, 0);
    tvLoading.setGravity(android.view.Gravity.CENTER);
    layout.addView(tvLoading);
    
    TextView tvSubtext = new TextView(requireContext());
    tvSubtext.setText(isArabic ? "يرجى الانتظار، يتم تسجيل القطعة في النظام" : "Please wait, registering item in system");
    tvSubtext.setTextColor(Color.parseColor("#64748B"));
    tvSubtext.setTextSize(11);
    tvSubtext.setPadding(0, 4, 0, 0);
    tvSubtext.setGravity(android.view.Gravity.CENTER);
    layout.addView(tvSubtext);
    
    builder.setView(layout);
    builder.setCancelable(false);
    
    AlertDialog dialog = builder.create();
    dialog.show();
    return dialog;
}

/**
 * عرض Dialog نجاح احترافي مع تفاصيل القطعة بعد التكويد
 */
private void showSuccessDialog(String epc, String title, String karat, double weight, 
                                String barcode, String serial, int itemId) {
    AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
    
    // إنشاء محتوى مخصص
    LinearLayout layout = new LinearLayout(requireContext());
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(30, 25, 30, 10);
    
    // ✅ أيقونة النجاح
    TextView tvIcon = new TextView(requireContext());
    tvIcon.setText("✅");
    tvIcon.setTextSize(40);
    tvIcon.setGravity(android.view.Gravity.CENTER);
    layout.addView(tvIcon);
    
    // العنوان
    TextView tvTitle = new TextView(requireContext());
    tvTitle.setText(isArabic ? "تم التكويد بنجاح!" : "Encoding Successful!");
    tvTitle.setTextColor(Color.parseColor("#16A34A"));
    tvTitle.setTextSize(18);
    // ✅ صحيح
tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);

    tvTitle.setGravity(android.view.Gravity.CENTER);
    tvTitle.setPadding(0, 10, 0, 20);
    layout.addView(tvTitle);
    
    // بطاقة تفاصيل القطعة
    com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(requireContext());
    card.setCardBackgroundColor(Color.parseColor("#F0FDF4"));
    card.setRadius(12);
    card.setCardElevation(2);
    card.setPadding(16, 16, 16, 16);
    
    LinearLayout cardLayout = new LinearLayout(requireContext());
    cardLayout.setOrientation(LinearLayout.VERTICAL);
    
    // دوال مساعدة للنص
    addDetailRow(cardLayout, isArabic ? "🔖 RFID" : "🔖 RFID", epc);
    addDetailRow(cardLayout, isArabic ? "📝 البيان" : "📝 Title", title);
    addDetailRow(cardLayout, isArabic ? "🏅 العيار" : "🏅 Karat", karat);
    addDetailRow(cardLayout, isArabic ? "⚖️ الوزن" : "⚖️ Weight", String.format(Locale.US, "%.2f جرام", weight));
    if (barcode != null && !barcode.isEmpty()) {
        addDetailRow(cardLayout, isArabic ? "📊 باركود" : "📊 Barcode", barcode);
    }
    if (serial != null && !serial.isEmpty()) {
        addDetailRow(cardLayout, isArabic ? "🔢 الرقم التسلسلي" : "🔢 Serial", serial);
    }
    if (itemId > 0) {
        addDetailRow(cardLayout, isArabic ? "🆔 معرف القطعة" : "🆔 Item ID", String.valueOf(itemId));
    }
    
    card.addView(cardLayout);
    layout.addView(card);
    
    builder.setView(layout);
    builder.setPositiveButton(isArabic ? "🎉 متابعة" : "🎉 Continue", (d, w) -> {
        d.dismiss();
        binding.etEpc.requestFocus();
    });
    builder.setNegativeButton(isArabic ? "🔄 تكويد آخر" : "🔄 Encode Another", (d, w) -> {
        d.dismiss();
        resetFormForNextScan();
        binding.etEpc.requestFocus();
    });
    builder.setCancelable(false);
    
    builder.show();
}

/**
 * إضافة صف نصي لبطاقة التفاصيل
 */
private void addDetailRow(LinearLayout parent, String label, String value) {
    LinearLayout row = new LinearLayout(requireContext());
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setPadding(0, 4, 0, 4);
    
    TextView tvLabel = new TextView(requireContext());
    tvLabel.setText(label);
    tvLabel.setTextColor(Color.parseColor("#475569"));
    tvLabel.setTextSize(12);
    tvLabel.setTypeface(null, android.graphics.Typeface.BOLD);
    tvLabel.setMinWidth(120);
    row.addView(tvLabel);
    
    TextView tvValue = new TextView(requireContext());
    tvValue.setText(value);
    tvValue.setTextColor(Color.parseColor("#0F172A"));
    tvValue.setTextSize(12);
    tvValue.setPadding(8, 0, 0, 0);
    row.addView(tvValue);
    
    parent.addView(row);
}
    // ==================== OFFLINE ====================

    private void saveEncodeOffline(JSONObject payload) {
        try {
            if (offlineCache == null) return;
            String json = offlineCache.getString("pending_encodes", "[]");
            JSONArray arr = new JSONArray(json);
            arr.put(payload);
            while (arr.length() > 100) arr.remove(0);
            offlineCache.edit().putString("pending_encodes", arr.toString()).apply();
            Log.i(TAG, "💾 Encode saved offline");
        } catch (Exception e) { Log.e(TAG, "Save encode offline error: " + e.getMessage()); }
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
        } catch (Exception ignored) {}
    }

    private void syncPendingEncodes() {
    if (apiClient == null || offlineCache == null) return;
    
    // ✅ التأكد من وجود branch_id
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
                    
                    // ✅ تأكد أن branch_id موجود في payload
                    if (!payload.has("branch_id") || payload.optInt("branch_id", 0) <= 0) {
                        payload.put("branch_id", branchId);
                    }
                    
                    MiraApiClient.ApiResponse response = apiClient.encodeItem(payload);
                    if (response != null && response.isSuccess) {
    synced++;
    String epc = payload.optString("epc", "");
    
    // ✅ رفع الصور المخزنة محلياً
    if (response.data != null) {
        int syncedItemId = response.data.optInt("item_id", 0);
        JSONArray imagePaths = payload.optJSONArray("image_paths");
        if (syncedItemId > 0 && imagePaths != null && imagePaths.length() > 0) {
            uploadPendingImages(syncedItemId, imagePaths);
        }
    }
    
    if (!epc.isEmpty()) removePendingEncode(epc);
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
                if (s > 0) showToast(isArabic ? "✅ تمت مزامنة " + s + " تكويد" : "✅ Synced " + s + " encodings"); 
            });
        } catch (Exception e) { 
            Log.e(TAG, "Sync encodes error: " + e.getMessage()); 
        }
    }).start();
}

    // ==================== HELPERS ====================

    private void safeRun(Runnable r) { if (getActivity() != null) getActivity().runOnUiThread(r); }
    private void playSuccessSound() { if (getActivity() instanceof UHFMainActivity) ((UHFMainActivity) getActivity()).playSound(1); }
    private void showToast(String msg) { if (getContext() != null && isAdded()) Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show(); }

    private String getSelectedCategory() {
        int checkedId = binding.chipGroupTypes.getCheckedChipId();
        if (checkedId == binding.chipRing.getId()) return isArabic ? "خواتم" : "Rings";
        if (checkedId == binding.chipSet.getId()) return isArabic ? "أطقم" : "Sets";
        if (checkedId == binding.chipBracelet.getId()) return isArabic ? "أساور" : "Bracelets";
        if (checkedId == binding.chipNecklace.getId()) return isArabic ? "عقود" : "Necklaces";
        return isArabic ? "مجوهرات" : "Jewelry";
    }

    private void resetFormForNextScan() {
        binding.etEpc.setText(""); binding.etTitle.setText(""); binding.etGrossWeight.setText("");
        binding.etStoneWeight.setText(""); binding.etStoneCount.setText(""); binding.etStoneValue.setText("");
        binding.tvStatusBadge.setText("WAITING");
        binding.tvEstimatedGoldValue.setText(isArabic ? "0.00 ريال" : "0.00 YER");
        binding.switchHasStones.setChecked(false);
        binding.etEpc.requestFocus();
        photoPaths.clear(); updatePhotosCount();
    }

    private String getSelectedKaratCode() {
        int checkedId = binding.chipGroupKarat.getCheckedChipId();
        if (checkedId == binding.chip24K.getId()) return "24K";
        if (checkedId == binding.chip21K.getId()) return "21K";
        if (checkedId == binding.chip18K.getId()) return "18K";
        if (checkedId == binding.chip14K.getId()) return "14K";
        return "21K";
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
        }
