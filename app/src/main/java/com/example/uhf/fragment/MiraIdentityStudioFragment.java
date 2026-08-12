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
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.databinding.FragmentMiraIdentityStudioBinding;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.tools.MiraDialogHelper;
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

public class MiraIdentityStudioFragment extends MiraBaseFragment {

    private static final String TAG = "MiraIdentityStudio";
    private static final String OFFLINE_PREFS = "identity_studio_cache";

    private FragmentMiraIdentityStudioBinding binding;
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private SharedPreferences offlineCache;

    private final Map<String, String[]> typeSuggestions = new HashMap<>();
    
    private String currentPhotoPath;
    private final List<String> photoPaths = new ArrayList<>();
    private ActivityResultLauncher<Uri> takePhotoLauncher;
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private Uri photoUri;
    
    // ==================== متغيرات أسعار الذهب ====================
    private double goldPrice24K = 75.0;
    private double goldPrice21K = 65.62;
    private double goldPrice18K = 56.25;
    private double goldPrice14K = 43.75;
    private double makingChargePerGram = 10.0;
    private double profitMarginPercent = 90.0;
    private boolean goldRatesLoaded = false;

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
        
        // تسجيل هذا الـ fragment كمستقبل RFID
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setActiveEncodingFragment(this);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        syncPendingEncodes();
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setActiveEncodingFragment(this);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setActiveEncodingFragment(null);
        }
    }

    // ==================== أسعار الذهب الديناميكية ====================

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
                    safeRun(this::calculateEstimatedGoldValue);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading gold rates: " + e.getMessage());
            }
        }).start();
    }

    private void calculateEstimatedGoldValue() {
        String weightStr = binding.etGrossWeight.getText() != null ? 
                           binding.etGrossWeight.getText().toString().trim() : "";
        
        if (weightStr.isEmpty()) { 
            binding.tvEstimatedGoldValue.setText(isArabic ? "0.00 ريال" : "0.00 YER"); 
            return; 
        }
        
        try {
            double weight = Double.parseDouble(weightStr);
            double gramPrice = getGoldPriceByKarat();
            double baseGoldCost = weight * gramPrice;
            double totalMakingCost = weight * makingChargePerGram;
            double subtotal = baseGoldCost + totalMakingCost;
            double finalPrice = subtotal * (profitMarginPercent / 100.0);
            
            binding.tvEstimatedGoldValue.setText(String.format(Locale.US, 
                "%,.0f %s", finalPrice, isArabic ? "ريال" : "YER"));
                
        } catch (NumberFormatException e) {
            binding.tvEstimatedGoldValue.setText(isArabic ? "0.00 ريال" : "0.00 YER");
        }
    }

    private double getGoldPriceByKarat() {
        int checkedId = binding.chipGroupKarat.getCheckedChipId();
        if (checkedId == binding.chip24K.getId()) return goldPrice24K;
        else if (checkedId == binding.chip21K.getId()) return goldPrice21K;
        else if (checkedId == binding.chip18K.getId()) return goldPrice18K;
        else if (checkedId == binding.chip14K.getId()) return goldPrice14K;
        return goldPrice21K;
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
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA) 
                == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void openCamera() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getContext().getPackageManager()) != null) {
            File photoFile = null;
            try { 
                photoFile = createImageFile(); 
            } catch (IOException ex) { 
                Log.e(TAG, "Error creating image file: " + ex.getMessage()); 
                return; 
            }
            if (photoFile != null) {
                try {
                    photoUri = FileProvider.getUriForFile(getContext(),
                        getContext().getApplicationContext().getPackageName() + ".fileprovider", 
                        photoFile);
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
        
        // تفعيل أيقونة الكاميرا في حقل EPC
        if (binding.tilEpc != null) {
            binding.tilEpc.setEndIconOnClickListener(v -> openBarcodeScannerForEPC());
        }

        // زر RFID
        if (binding.btnScanRfid != null) {
            binding.btnScanRfid.setOnClickListener(v -> activateRFIDModeForEncoding());
        }

        binding.switchHasStones.setOnCheckedChangeListener((buttonView, isChecked) -> {
            binding.layoutStonesDetails.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        binding.etGrossWeight.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { 
                calculateEstimatedGoldValue(); 
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        binding.chipGroupKarat.setOnCheckedStateChangeListener((group, checkedIds) -> 
            calculateEstimatedGoldValue());

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

    // ==================== المسح للتكويد ====================

    private void openBarcodeScannerForEPC() {
        if (!isAdded()) return;
        
        try {
            MiraBarcodeScannerFragment sf = new MiraBarcodeScannerFragment();
            sf.setOnBarcodeScannedListener((data, format) -> {
                if (binding.etEpc != null) {
                    binding.etEpc.setText(data);
                    binding.tvStatusBadge.setText("BOUND");
                }
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

    private void activateRFIDModeForEncoding() {
        if (getActivity() instanceof UHFMainActivity) {
            UHFMainActivity activity = (UHFMainActivity) getActivity();
            
            activity.setActiveRfidListener(epc -> {
                if (binding.etEpc != null) {
                    binding.etEpc.setText(epc);
                    binding.tvStatusBadge.setText("BOUND");
                }
                if (activity != null) activity.playSound(1);
            });
            
            activity.startUHFScan();
            showToast(isArabic ? "📡 جاهز لمسح RFID - امسح الشريحة" : "📡 Ready for RFID scan");
            
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

    // ==================== التحقق من صحة EPC ====================

    private boolean isValidEPC(String epc) {
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

    // ==================== حفظ التكويد ====================

    private void finalizeItemIdentity() {
        String epc = binding.etEpc.getText() != null ? binding.etEpc.getText().toString().trim() : "";
        String title = binding.etTitle.getText() != null ? binding.etTitle.getText().toString().trim() : "";
        String weightStr = binding.etGrossWeight.getText() != null ? binding.etGrossWeight.getText().toString().trim() : "";

        if (epc.isEmpty()) { 
            showToast(isArabic ? "يرجى مسح RFID أولاً" : "Please scan RFID first"); 
            return; 
        }
        if (!isValidEPC(epc)) { 
            showToast(isArabic ? "صيغة RFID غير صالحة" : "Invalid RFID format"); 
            return; 
        }
        if (title.isEmpty()) { 
            showToast(isArabic ? "يرجى كتابة عنوان القطعة" : "Please enter item title"); 
            return; 
        }
        if (weightStr.isEmpty()) { 
            showToast(isArabic ? "يرجى تحديد وزن القطعة" : "Please specify item weight"); 
            return; 
        }

        double grossWeight;
        try { 
            grossWeight = Double.parseDouble(weightStr); 
        } catch (NumberFormatException e) { 
            showToast(isArabic ? "وزن غير صالح" : "Invalid weight"); 
            return; 
        }

        if (grossWeight <= 0) {
            showToast(isArabic ? "يجب أن يكون الوزن أكبر من صفر" : "Weight must be greater than zero");
            return;
        }

        String stoneType = "";
        double stoneWeight = 0;
        int stoneCount = 0;
        double stoneValue = 0;
        
        if (binding.switchHasStones.isChecked()) {
            stoneType = binding.etStoneType.getText() != null ? 
                binding.etStoneType.getText().toString().trim() : "";
            try { stoneWeight = Double.parseDouble(binding.etStoneWeight.getText().toString().trim()); } catch (Exception ignored) {}
            try { stoneCount = Integer.parseInt(binding.etStoneCount.getText().toString().trim()); } catch (Exception ignored) {}
            try { stoneValue = Double.parseDouble(binding.etStoneValue.getText().toString().trim()); } catch (Exception ignored) {}
        }

        // ✅ استخدام Dialog الموحد من MiraBaseFragment
        final AlertDialog loadingDialog = showLoadingDialog(
            isArabic ? "🔄 جاري التكويد والربط السحابي..." : "🔄 Encoding & Cloud Linking..."
        );
        
        binding.btnSaveIdentity.setEnabled(false);
        binding.btnAddPhotos.setEnabled(false);
        binding.etEpc.setEnabled(false);
        binding.etTitle.setEnabled(false);
        binding.etGrossWeight.setEnabled(false);

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
                
                if (!finalPhotoPaths.isEmpty()) {
                    JSONArray imagesArray = new JSONArray();
                    for (String path : finalPhotoPaths) {
                        imagesArray.put(path);
                    }
                    payload.put("image_paths", imagesArray);
                }

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
                    dismissDialog(loadingDialog);
                    
                    binding.btnSaveIdentity.setEnabled(true);
                    binding.btnAddPhotos.setEnabled(true);
                    binding.etEpc.setEnabled(true);
                    binding.etTitle.setEnabled(true);
                    binding.etGrossWeight.setEnabled(true);
                    binding.btnSaveIdentity.setText(isArabic ? 
                        "اعتماد الموزون وتصدير MIRA ID" : "Approve & Export MIRA ID");

                    if (success) {
                        removePendingEncode(finalEpc);
                        playSuccessSound();
                        
                        // ✅ استخدام Dialog التفاصيل الموحد
                        List<String[]> details = new ArrayList<>();
                        details.add(new String[]{"🔖 RFID", finalEpc});
                        details.add(new String[]{"📝 البيان", finalTitle});
                        details.add(new String[]{"🏅 العيار", selectedKarat});
                        details.add(new String[]{"⚖️ الوزن", 
                            String.format(Locale.US, "%.2f جرام", finalGrossWeight)});
                        if (finalServerBarcode != null && !finalServerBarcode.isEmpty()) {
                            details.add(new String[]{"📊 باركود", finalServerBarcode});
                        }
                        if (finalServerSerial != null && !finalServerSerial.isEmpty()) {
                            details.add(new String[]{"🔢 الرقم التسلسلي", finalServerSerial});
                        }
                        if (finalServerItemId > 0) {
                            details.add(new String[]{"🆔 معرف القطعة", String.valueOf(finalServerItemId)});
                        }
                        
                        showDetailsDialog(
                            isArabic ? "✅ تم التكويد بنجاح!" : "✅ Encoding Successful!",
                            details.toArray(new String[0][])
                        );
                        
                        if (!finalPhotoPaths.isEmpty() && finalServerItemId > 0) {
                            uploadItemImages(finalServerItemId, finalPhotoPaths);
                        }
                        
                        resetFormForNextScan();
                    } else {
                        saveEncodeOffline(payload);
                        showToast(isArabic ? "💾 تم حفظ التكويد محلياً للمزامنة لاحقاً" 
                            : "💾 Encoding saved locally for later sync");
                        resetFormForNextScan();
                    }
                });
            } catch (Exception e) {
                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    binding.btnSaveIdentity.setEnabled(true);
                    binding.btnAddPhotos.setEnabled(true);
                    binding.etEpc.setEnabled(true);
                    binding.etTitle.setEnabled(true);
                    binding.etGrossWeight.setEnabled(true);
                    binding.btnSaveIdentity.setText(isArabic ? 
                        "اعتماد الموزون وتصدير MIRA ID" : "Approve & Export MIRA ID");
                    showErrorDialog(isArabic ? "❌ فشل التكويد" : "❌ Encoding Failed", 
                        e.getMessage());
                });
            }
        });
    }

    // ==================== رفع الصور ====================

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
                    boolean isPrimary = (i == 0);
                    MiraApiClient.ApiResponse response = apiClient.uploadItemImage(
                        itemId, imageFile, isPrimary, 
                        isArabic ? "صورة " + (i + 1) : "Image " + (i + 1)
                    );
                    
                    if (response != null && response.isSuccess) {
                        uploaded++;
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    failed++;
                }
            }
            
            final int finalUploaded = uploaded;
            final int finalFailed = failed;
            safeRun(() -> {
                if (finalUploaded > 0) {
                    String msg = isArabic ? "📷 تم رفع " + finalUploaded + " صورة" 
                        : "📷 " + finalUploaded + " images uploaded";
                    if (finalFailed > 0) {
                        msg += isArabic ? " (فشل " + finalFailed + ")" 
                            : " (" + finalFailed + " failed)";
                    }
                    showToast(msg);
                }
            });
        }).start();
    }

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

    // ==================== Offline ====================

    private void saveEncodeOffline(JSONObject payload) {
        try {
            if (offlineCache == null) return;
            String json = offlineCache.getString("pending_encodes", "[]");
            JSONArray arr = new JSONArray(json);
            arr.put(payload);
            while (arr.length() > 100) arr.remove(0);
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
        } catch (Exception ignored) {}
    }

    private void syncPendingEncodes() {
        if (apiClient == null || offlineCache == null) return;
        
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
                            String epc = payload.optString("epc", "");
                            
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
                    if (s > 0) showToast(isArabic ? "✅ تمت مزامنة " + s + " تكويد" 
                        : "✅ Synced " + s + " encodings"); 
                });
            } catch (Exception e) { 
                Log.e(TAG, "Sync encodes error: " + e.getMessage()); 
            }
        }).start();
    }

    // ==================== Helpers ====================

    private void safeRun(Runnable r) { 
        if (getActivity() != null) getActivity().runOnUiThread(r); 
    }
    
    private void playSuccessSound() { 
        if (getActivity() instanceof UHFMainActivity) 
            ((UHFMainActivity) getActivity()).playSound(1); 
    }
    
    private void showToast(String msg) { 
        if (getContext() != null && isAdded()) 
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show(); 
    }

    private String getSelectedCategory() {
        int checkedId = binding.chipGroupTypes.getCheckedChipId();
        if (checkedId == binding.chipRing.getId()) return isArabic ? "خواتم" : "Rings";
        if (checkedId == binding.chipSet.getId()) return isArabic ? "أطقم" : "Sets";
        if (checkedId == binding.chipBracelet.getId()) return isArabic ? "أساور" : "Bracelets";
        if (checkedId == binding.chipNecklace.getId()) return isArabic ? "عقود" : "Necklaces";
        return isArabic ? "مجوهرات" : "Jewelry";
    }

    private void resetFormForNextScan() {
        binding.etEpc.setText(""); 
        binding.etTitle.setText(""); 
        binding.etGrossWeight.setText("");
        binding.etStoneWeight.setText(""); 
        binding.etStoneCount.setText(""); 
        binding.etStoneValue.setText("");
        binding.tvStatusBadge.setText("WAITING");
        binding.tvEstimatedGoldValue.setText(isArabic ? "0.00 ريال" : "0.00 YER");
        binding.switchHasStones.setChecked(false);
        binding.etEpc.requestFocus();
        photoPaths.clear(); 
        updatePhotosCount();
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
