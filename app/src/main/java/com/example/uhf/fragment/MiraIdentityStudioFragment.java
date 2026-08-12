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
    
    // متغيرات أسعار الذهب
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

        try {
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
            
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setActiveEncodingFragment(this);
            }
        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error: " + e.getMessage());
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

    // ==================== أسعار الذهب ====================

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
        try {
            if (binding == null || binding.etGrossWeight == null) return;
            
            String weightStr = binding.etGrossWeight.getText() != null ? 
                               binding.etGrossWeight.getText().toString().trim() : "";
            
            if (weightStr.isEmpty()) { 
                binding.tvEstimatedGoldValue.setText(isArabic ? "0.00 ريال" : "0.00 YER"); 
                return; 
            }
            
            double weight = Double.parseDouble(weightStr);
            double gramPrice = getGoldPriceByKarat();
            double baseGoldCost = weight * gramPrice;
            double totalMakingCost = weight * makingChargePerGram;
            double subtotal = baseGoldCost + totalMakingCost;
            double finalPrice = subtotal * (profitMarginPercent / 100.0);
            
            binding.tvEstimatedGoldValue.setText(String.format(Locale.US, 
                "%,.0f %s", finalPrice, isArabic ? "ريال" : "YER"));
                
        } catch (Exception e) {
            Log.e(TAG, "calculateEstimatedGoldValue error: " + e.getMessage());
        }
    }

    private double getGoldPriceByKarat() {
        try {
            int checkedId = binding.chipGroupKarat.getCheckedChipId();
            if (checkedId == binding.chip24K.getId()) return goldPrice24K;
            else if (checkedId == binding.chip21K.getId()) return goldPrice21K;
            else if (checkedId == binding.chip18K.getId()) return goldPrice18K;
            else if (checkedId == binding.chip14K.getId()) return goldPrice14K;
        } catch (Exception e) {
            Log.e(TAG, "getGoldPriceByKarat error: " + e.getMessage());
        }
        return goldPrice21K;
    }

    // ==================== الكاميرا ====================

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
                    updatePhotosCount();
                }
            }
        );
    }

    private void checkCameraPermissionAndTakePhoto() {
        try {
            if (getContext() == null) return;
            if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA) 
                    == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        } catch (Exception e) {
            Log.e(TAG, "checkCameraPermission error: " + e.getMessage());
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
            Log.e(TAG, "openCamera error: " + e.getMessage());
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
        try {
            if (binding == null || binding.tvPhotosCount == null) return;
            int count = photoPaths.size();
            binding.tvPhotosCount.setText(count == 0 ? 
                (isArabic ? "لم يتم إرفاق صور بعد" : "No photos attached yet") :
                (isArabic ? "تم إرفاق (" + count + ") صور" : "Attached (" + count + ") photos"));
        } catch (Exception e) {
            Log.e(TAG, "updatePhotosCount error: " + e.getMessage());
        }
    }

    // ==================== اللغة ====================

    private void applyLanguage() {
        if (binding == null) return;
        if (isArabic) setArabicTexts(); else setEnglishTexts();
    }

    private void setArabicTexts() {
        try {
            binding.tvStepTitle.setText("الخطوة 1 من 4: الربط والتصنيف");
            binding.etEpc.setHint("رمز الشريحة (EPC / GTIN)...");
            binding.etTitle.setHint("عنوان ووصف القطعة");
            binding.btnSaveIdentity.setText("اعتماد الموزون وتصدير MIRA ID");
            binding.btnAddPhotos.setText("📷 التقاط / إرفاق صور القطعة");
            binding.tvPhotosCount.setText("لم يتم إرفاق صور بعد");
            binding.tvEstimatedGoldValue.setText("0.00 ريال");
        } catch (Exception e) {
            Log.e(TAG, "setArabicTexts error: " + e.getMessage());
        }
    }

    private void setEnglishTexts() {
        try {
            binding.tvStepTitle.setText("Step 1 of 4: Bind & Classify");
            binding.etEpc.setHint("EPC / GTIN Code...");
            binding.etTitle.setHint("Item Title & Description");
            binding.btnSaveIdentity.setText("Approve & Export MIRA ID");
            binding.btnAddPhotos.setText("📷 Capture / Attach Photos");
            binding.tvPhotosCount.setText("No photos attached yet");
            binding.tvEstimatedGoldValue.setText("0.00 YER");
        } catch (Exception e) {
            Log.e(TAG, "setEnglishTexts error: " + e.getMessage());
        }
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
        try {
            binding.etEpc.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    try { binding.tvStatusBadge.setText(s.length() > 0 ? "BOUND" : "WAITING"); } catch (Exception ignored) {}
                }
                @Override public void afterTextChanged(Editable s) {}
            });
            
            if (binding.tilEpc != null) {
                binding.tilEpc.setEndIconOnClickListener(v -> openBarcodeScannerForEPC());
            }

            if (binding.btnScanRfid != null) {
                binding.btnScanRfid.setOnClickListener(v -> activateRFIDModeForEncoding());
            }

            binding.switchHasStones.setOnCheckedChangeListener((buttonView, isChecked) -> {
                try { binding.layoutStonesDetails.setVisibility(isChecked ? View.VISIBLE : View.GONE); } catch (Exception ignored) {}
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
                try {
                    if (checkedIds.isEmpty()) return;
                    int id = checkedIds.get(0);
                    if (id == binding.chipRing.getId()) populateSuggestions("rings");
                    else if (id == binding.chipSet.getId()) populateSuggestions("sets");
                    else if (id == binding.chipBracelet.getId()) populateSuggestions("bracelets");
                    else if (id == binding.chipNecklace.getId()) populateSuggestions("necklaces");
                } catch (Exception ignored) {}
            });

            binding.btnAddPhotos.setOnClickListener(v -> checkCameraPermissionAndTakePhoto());
            binding.btnSaveIdentity.setOnClickListener(v -> finalizeItemIdentity());
        } catch (Exception e) {
            Log.e(TAG, "setupListeners error: " + e.getMessage());
        }
    }

    // ==================== المسح ====================

    private void openBarcodeScannerForEPC() {
        try {
            if (!isAdded()) return;
            
            MiraBarcodeScannerFragment sf = new MiraBarcodeScannerFragment();
            sf.setOnBarcodeScannedListener((data, format) -> {
                try {
                    if (binding != null && binding.etEpc != null) {
                        binding.etEpc.setText(data);
                        binding.tvStatusBadge.setText("BOUND");
                    }
                    if (getParentFragmentManager().getBackStackEntryCount() > 0) {
                        getParentFragmentManager().popBackStack();
                    }
                } catch (Exception ignored) {}
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
        try {
            if (getActivity() instanceof UHFMainActivity) {
                UHFMainActivity activity = (UHFMainActivity) getActivity();
                
                activity.setActiveRfidListener(epc -> {
                    try {
                        if (binding != null && binding.etEpc != null) {
                            binding.etEpc.setText(epc);
                            binding.tvStatusBadge.setText("BOUND");
                        }
                        if (activity != null) activity.playSound(1);
                    } catch (Exception ignored) {}
                });
                
                activity.startUHFScan();
                showToast(isArabic ? "📡 جاهز لمسح RFID" : "📡 Ready for RFID scan");
                
                new Handler().postDelayed(() -> {
                    try {
                        if (getActivity() instanceof UHFMainActivity) {
                            ((UHFMainActivity) getActivity()).stopUHFScan();
                            ((UHFMainActivity) getActivity()).setActiveRfidListener(null);
                        }
                    } catch (Exception ignored) {}
                }, 30000);
            }
        } catch (Exception e) {
            Log.e(TAG, "activateRFIDMode error: " + e.getMessage());
        }
    }

    public void onRfidScanned(String epc) {
        try {
            if (getActivity() == null || binding == null) return;
            getActivity().runOnUiThread(() -> {
                try {
                    binding.etEpc.setText(epc);
                    binding.tvStatusBadge.setText("BOUND");
                    if (getActivity() instanceof UHFMainActivity) {
                        ((UHFMainActivity) getActivity()).playSound(1);
                    }
                } catch (Exception ignored) {}
            });
        } catch (Exception e) {
            Log.e(TAG, "onRfidScanned error: " + e.getMessage());
        }
    }

    private boolean isValidEPC(String epc) {
        return epc != null && epc.matches("^[A-Fa-f0-9]{6,}$");
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

    // ==================== حفظ التكويد ====================

    private void finalizeItemIdentity() {
        try {
            if (binding == null) return;
            
            String epc = binding.etEpc.getText() != null ? binding.etEpc.getText().toString().trim() : "";
            String title = binding.etTitle.getText() != null ? binding.etTitle.getText().toString().trim() : "";
            String weightStr = binding.etGrossWeight.getText() != null ? binding.etGrossWeight.getText().toString().trim() : "";

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

            String stoneType = "";
            double stoneWeight = 0;
            int stoneCount = 0;
            double stoneValue = 0;
            
            if (binding.switchHasStones.isChecked()) {
                try { stoneType = binding.etStoneType.getText().toString().trim(); } catch (Exception ignored) {}
                try { stoneWeight = Double.parseDouble(binding.etStoneWeight.getText().toString().trim()); } catch (Exception ignored) {}
                try { stoneCount = Integer.parseInt(binding.etStoneCount.getText().toString().trim()); } catch (Exception ignored) {}
                try { stoneValue = Double.parseDouble(binding.etStoneValue.getText().toString().trim()); } catch (Exception ignored) {}
            }

            final AlertDialog loadingDialog = showLoadingDialog(
                isArabic ? "🔄 جاري التكويد والربط السحابي..." : "🔄 Encoding & Cloud Linking..."
            );
            
            try {
                binding.btnSaveIdentity.setEnabled(false);
                binding.btnAddPhotos.setEnabled(false);
                binding.etEpc.setEnabled(false);
                binding.etTitle.setEnabled(false);
                binding.etGrossWeight.setEnabled(false);
            } catch (Exception ignored) {}

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
                    // ✅ تأكد من apiClient
                    if (apiClient == null && getContext() != null) {
                        apiClient = MiraApiClient.getInstance(getContext());
                    }
                    
                    if (apiClient == null) {
                        safeRun(() -> {
                            dismissDialog(loadingDialog);
                            showErrorDialog("خطأ", "API غير مهيأ");
                        });
                        return;
                    }
                    
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
                    String serverBarcode = "";
                    String serverSerial = "";
                    int serverItemId = 0;
                    
                    try {
                        MiraApiClient.ApiResponse response = apiClient.encodeItem(payload);
                        apiSuccess = (response != null && response.isSuccess);
                        if (response != null && response.data != null) {
                            serverBarcode = response.data.optString("barcode", "");
                            serverSerial = response.data.optString("serial_number", "");
                            serverItemId = response.data.optInt("item_id", 0);
                        }
                    } catch (Exception apiError) {
                        Log.e(TAG, "API encode failed: " + apiError.getMessage());
                    }

                    final boolean success = apiSuccess;
                    final String finalServerBarcode = serverBarcode;
                    final String finalServerSerial = serverSerial;
                    final int finalServerItemId = serverItemId;

                    safeRun(() -> {
                        try {
                            dismissDialog(loadingDialog);
                            
                            if (binding != null) {
                                binding.btnSaveIdentity.setEnabled(true);
                                binding.btnAddPhotos.setEnabled(true);
                                binding.etEpc.setEnabled(true);
                                binding.etTitle.setEnabled(true);
                                binding.etGrossWeight.setEnabled(true);
                                binding.btnSaveIdentity.setText(isArabic ? 
                                    "اعتماد الموزون وتصدير MIRA ID" : "Approve & Export MIRA ID");
                            }

                            if (success) {
                                removePendingEncode(finalEpc);
                                playSuccessSound();
                                
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
                        } catch (Exception e) {
                            Log.e(TAG, "UI update error: " + e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Background error: " + e.getMessage());
                    safeRun(() -> {
                        dismissDialog(loadingDialog);
                        showErrorDialog(isArabic ? "❌ فشل التكويد" : "❌ Encoding Failed", 
                            e.getMessage());
                    });
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "finalizeItemIdentity crash: " + e.getMessage());
            showToast(isArabic ? "❌ خطأ: " + e.getMessage() : "❌ Error: " + e.getMessage());
        }
    }

    // ==================== رفع الصور ====================

    private void uploadItemImages(int itemId, List<String> imagePaths) {
        if (imagePaths == null || imagePaths.isEmpty() || itemId <= 0) return;
        if (apiClient == null) return;
        
        new Thread(() -> {
            int uploaded = 0;
            int failed = 0;
            
            for (String path : imagePaths) {
                File imageFile = new File(path);
                if (!imageFile.exists()) { failed++; continue; }
                try {
                    boolean isPrimary = (imagePaths.indexOf(path) == 0);
                    MiraApiClient.ApiResponse response = apiClient.uploadItemImage(
                        itemId, imageFile, isPrimary, "Image");
                    if (response != null && response.isSuccess) uploaded++;
                    else failed++;
                } catch (Exception e) { failed++; }
            }
            
            final int fUploaded = uploaded;
            final int fFailed = failed;
            safeRun(() -> {
                if (fUploaded > 0) {
                    showToast(isArabic ? "📷 تم رفع " + fUploaded + " صورة" : "📷 " + fUploaded + " images uploaded");
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
                    if (s > 0) showToast(isArabic ? "✅ تمت مزامنة " + s + " تكويد" : "✅ Synced " + s + " encodings"); 
                });
            } catch (Exception e) { 
                Log.e(TAG, "Sync encodes error: " + e.getMessage()); 
            }
        }).start();
    }

    // ==================== Helpers ====================

    private void safeRun(Runnable r) { 
        try {
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    try {
                        if (isAdded()) r.run();
                    } catch (Exception e) {
                        Log.e(TAG, "safeRun inner error: " + e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "safeRun outer error: " + e.getMessage());
        }
    }
    
    private void playSuccessSound() { 
        try {
            if (getActivity() instanceof UHFMainActivity) 
                ((UHFMainActivity) getActivity()).playSound(1); 
        } catch (Exception e) {
            Log.e(TAG, "playSuccessSound error: " + e.getMessage());
        }
    }
    
    private void showToast(String msg) { 
        try {
            if (getContext() != null && isAdded()) 
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show(); 
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
            binding.etStoneWeight.setText(""); 
            binding.etStoneCount.setText(""); 
            binding.etStoneValue.setText("");
            binding.tvStatusBadge.setText("WAITING");
            binding.tvEstimatedGoldValue.setText(isArabic ? "0.00 ريال" : "0.00 YER");
            binding.switchHasStones.setChecked(false);
            binding.etEpc.requestFocus();
            photoPaths.clear(); 
            updatePhotosCount();
        } catch (Exception e) {
            Log.e(TAG, "resetFormForNextScan error: " + e.getMessage());
        }
    }

    private String getSelectedKaratCode() {
        try {
            int checkedId = binding.chipGroupKarat.getCheckedChipId();
            if (checkedId == binding.chip24K.getId()) return "24K";
            if (checkedId == binding.chip21K.getId()) return "21K";
            if (checkedId == binding.chip18K.getId()) return "18K";
            if (checkedId == binding.chip14K.getId()) return "14K";
        } catch (Exception e) {
            Log.e(TAG, "getSelectedKaratCode error: " + e.getMessage());
        }
        return "21K";
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
