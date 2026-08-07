package com.example.uhf.fragment;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.databinding.FragmentMiraIdentityStudioBinding;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.google.android.material.chip.Chip;

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

    private FragmentMiraIdentityStudioBinding binding;
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private boolean isArabic;

    private final Map<String, String[]> typeSuggestions = new HashMap<>();
    
    // متغيرات الصور
    private String currentPhotoPath;
    private final List<String> photoPaths = new ArrayList<>();
    private ActivityResultLauncher<Uri> takePhotoLauncher;
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

        if (getContext() != null) {
            apiClient = MiraApiClient.getInstance(getContext());
            branchesManager = MiraBranchesManager.getInstance(getContext());
            orgProfile = MiraOrgProfileManager.getInstance(getContext());
        }
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        initSuggestionsData();
        setupPhotoLaunchers();
        applyLanguage();
        setupListeners();
    }

    // ==================== Photo Launchers ====================

    private void setupPhotoLaunchers() {
        // طلب صلاحية الكاميرا
        requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) {
                    openCamera();
                } else {
                    Toast.makeText(getContext(),
                        isArabic ? "صلاحية الكاميرا مطلوبة لالتقاط الصور" : "Camera permission is required to take photos",
                        Toast.LENGTH_SHORT).show();
                }
            }
        );

        // استقبال الصورة بعد الالتقاط
        takePhotoLauncher = registerForActivityResult(
            new ActivityResultContracts.TakePicture(),
            success -> {
                if (success && currentPhotoPath != null) {
                    photoPaths.add(currentPhotoPath);
                    updatePhotosCount();
                    Toast.makeText(getContext(),
                        isArabic ? "✅ تم التقاط الصورة بنجاح" : "✅ Photo captured successfully",
                        Toast.LENGTH_SHORT).show();
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
        
        // التأكد من وجود تطبيق كاميرا
        if (takePictureIntent.resolveActivity(getContext().getPackageManager()) != null) {
            File photoFile = null;
            try {
                photoFile = createImageFile();
            } catch (IOException ex) {
                Log.e(TAG, "Error creating image file: " + ex.getMessage());
                Toast.makeText(getContext(),
                    isArabic ? "❌ خطأ في إنشاء ملف الصورة" : "❌ Error creating image file",
                    Toast.LENGTH_SHORT).show();
                return;
            }

            if (photoFile != null) {
                try {
                    photoUri = FileProvider.getUriForFile(getContext(),
                        getContext().getApplicationContext().getPackageName() + ".fileprovider",
                        photoFile);
                    takePhotoLauncher.launch(photoUri);
                } catch (Exception e) {
                    Log.e(TAG, "Error launching camera: " + e.getMessage());
                    // محاولة بدون FileProvider كحل بديل
                    photoUri = Uri.fromFile(photoFile);
                    takePhotoLauncher.launch(photoUri);
                }
            }
        } else {
            Toast.makeText(getContext(),
                isArabic ? "❌ لا يوجد تطبيق كاميرا متاح" : "❌ No camera app available",
                Toast.LENGTH_SHORT).show();
        }
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String imageFileName = "MIRA_" + timeStamp + "_";
        File storageDir = getContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile(imageFileName, ".jpg", storageDir);
        currentPhotoPath = image.getAbsolutePath();
        return image;
    }

    private void updatePhotosCount() {
        int count = photoPaths.size();
        if (binding.tvPhotosCount != null) {
            if (count == 0) {
                binding.tvPhotosCount.setText(isArabic ? "لم يتم إرفاق صور بعد" : "No photos attached yet");
            } else {
                binding.tvPhotosCount.setText(isArabic ? 
                    "تم إرفاق (" + count + ") صور" : 
                    "Attached (" + count + ") photos");
            }
        }
    }

    // ==================== Language ====================

    private void applyLanguage() {
        if (binding == null) return;
        
        // استخدم النصوص من موارد strings.xml
        if (isArabic) {
            setArabicTexts();
        } else {
            setEnglishTexts();
        }
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

    // ==================== Suggestions ====================

    private void initSuggestionsData() {
        if (isArabic) {
            typeSuggestions.put("rings", new String[]{"خاتم سوليتير ألماسي", "خاتم محبس كلاسيك", "خاتم توينز راقي", "خاتم فص زركون ملكي"});
            typeSuggestions.put("sets", new String[]{"طقم شبكة عروس كامل", "طقم ملكي مرصع بالألماس", "نصف طقم فاخر"});
            typeSuggestions.put("bracelets", new String[]{"بناجر / أساور خليجي", "سوار تنادي عصري", "إسورة زرد عريضة"});
            typeSuggestions.put("necklaces", new String[]{"عقد شوكر عصري", "قلادة بتصميم وردة", "سلسلة ذهبية ناعمة"});
        } else {
            typeSuggestions.put("rings", new String[]{"Diamond Solitaire Ring", "Classic Band Ring", "Luxury Twin Ring", "Royal Zircon Ring"});
            typeSuggestions.put("sets", new String[]{"Complete Bridal Set", "Royal Diamond Set", "Luxury Half Set"});
            typeSuggestions.put("bracelets", new String[]{"Khaleeji Bangle", "Modern Tennis Bracelet", "Wide Link Bracelet"});
            typeSuggestions.put("necklaces", new String[]{"Modern Choker", "Flower Pendant Necklace", "Fine Gold Chain"});
        }
    }

    // ==================== Listeners ====================

    private void setupListeners() {
        binding.etEpc.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                binding.tvStatusBadge.setText(s.length() > 0 ? "BOUND" : "WAITING");
            }
            @Override public void afterTextChanged(Editable s) {}
        });

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

        binding.chipGroupKarat.setOnCheckedStateChangeListener((group, checkedIds) -> calculateEstimatedGoldValue());

        binding.chipGroupTypes.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == binding.chipRing.getId()) populateSuggestions("rings");
            else if (id == binding.chipSet.getId()) populateSuggestions("sets");
            else if (id == binding.chipBracelet.getId()) populateSuggestions("bracelets");
            else if (id == binding.chipNecklace.getId()) populateSuggestions("necklaces");
        });

        // ✅ زر الصور - تم الإصلاح
        binding.btnAddPhotos.setOnClickListener(v -> checkCameraPermissionAndTakePhoto());

        binding.btnSaveIdentity.setOnClickListener(v -> finalizeItemIdentity());
    }

    // ==================== Gold Calculation ====================

    private void calculateEstimatedGoldValue() {
        String weightStr = binding.etGrossWeight.getText() != null ? binding.etGrossWeight.getText().toString().trim() : "";
        if (weightStr.isEmpty()) {
            binding.tvEstimatedGoldValue.setText(isArabic ? "0.00 ريال" : "0.00 YER");
            return;
        }
        try {
            double weight = Double.parseDouble(weightStr);
            double pricePerGram = getGoldPriceByKarat();
            double rawGoldValue = weight * pricePerGram;
            binding.tvEstimatedGoldValue.setText(String.format(Locale.US, "%,.0f %s", rawGoldValue, isArabic ? "ريال" : "YER"));
        } catch (NumberFormatException e) {
            binding.tvEstimatedGoldValue.setText(isArabic ? "0.00 ريال" : "0.00 YER");
        }
    }

    private double getGoldPriceByKarat() {
        int checkedId = binding.chipGroupKarat.getCheckedChipId();
        if (checkedId == binding.chip24K.getId()) return 75.0;
        else if (checkedId == binding.chip21K.getId()) return 65.62;
        else if (checkedId == binding.chip18K.getId()) return 56.25;
        else if (checkedId == binding.chip14K.getId()) return 43.75;
        return 65.62;
    }

    // ==================== RFID Scan ====================

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

    // ==================== Suggestions ====================

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

    // ==================== Save Identity ====================

    private void finalizeItemIdentity() {
        String epc = binding.etEpc.getText() != null ? binding.etEpc.getText().toString().trim() : "";
        String title = binding.etTitle.getText() != null ? binding.etTitle.getText().toString().trim() : "";
        String weightStr = binding.etGrossWeight.getText() != null ? binding.etGrossWeight.getText().toString().trim() : "";

        if (epc.isEmpty()) {
            Toast.makeText(getContext(), isArabic ? "يرجى مسح أو كتابة رمز RFID أولاً" : "Please scan or enter RFID first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (title.isEmpty()) {
            Toast.makeText(getContext(), isArabic ? "يرجى كتابة عنوان القطعة" : "Please enter item title", Toast.LENGTH_SHORT).show();
            return;
        }
        if (weightStr.isEmpty()) {
            Toast.makeText(getContext(), isArabic ? "يرجى تحديد وزن القطعة" : "Please specify item weight", Toast.LENGTH_SHORT).show();
            return;
        }

        double grossWeight;
        try {
            grossWeight = Double.parseDouble(weightStr);
        } catch (NumberFormatException e) {
            Toast.makeText(getContext(), isArabic ? "وزن غير صالح" : "Invalid weight", Toast.LENGTH_SHORT).show();
            return;
        }

        String stoneType = binding.etStoneType.getText() != null ? binding.etStoneType.getText().toString().trim() : "";
        double stoneWeight = 0;
        int stoneCount = 0;
        double stoneValue = 0;

        if (binding.switchHasStones.isChecked()) {
            try {
                String sw = binding.etStoneWeight.getText() != null ? binding.etStoneWeight.getText().toString().trim() : "0";
                stoneWeight = sw.isEmpty() ? 0 : Double.parseDouble(sw);
            } catch (Exception ignored) {}
            try {
                String sc = binding.etStoneCount.getText() != null ? binding.etStoneCount.getText().toString().trim() : "0";
                stoneCount = sc.isEmpty() ? 0 : Integer.parseInt(sc);
            } catch (Exception ignored) {}
            try {
                String sv = binding.etStoneValue.getText() != null ? binding.etStoneValue.getText().toString().trim() : "0";
                stoneValue = sv.isEmpty() ? 0 : Double.parseDouble(sv);
            } catch (Exception ignored) {}
        }

        String purityCode = getSelectedKaratCode();
        String category = getSelectedCategory();
        String location = isArabic ? "المعرض الرئيسي" : "Main Showroom";

        binding.btnSaveIdentity.setEnabled(false);
        binding.btnSaveIdentity.setText(isArabic ? "جاري التكويد..." : "Encoding...");

        final double fStoneWeight = stoneWeight;
        final int fStoneCount = stoneCount;
        final double fStoneValue = stoneValue;

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("epc", epc);
                payload.put("title", title);
                payload.put("purity_code", purityCode);
                payload.put("gross_weight", grossWeight);
                payload.put("net_weight", grossWeight - fStoneWeight);
                payload.put("stone_weight", fStoneWeight);
                payload.put("stone_count", fStoneCount);
                payload.put("stone_value", fStoneValue);
                payload.put("stone_type", stoneType);
                payload.put("making_per_gram", 0);
                payload.put("profit_margin", 0);
                payload.put("category", category);
                payload.put("location", location);
                payload.put("branch_id", branchesManager.getActiveBranchId());

                MiraApiClient.ApiResponse response = apiClient.encodeItem(payload);

                safeRun(() -> {
                    binding.btnSaveIdentity.setEnabled(true);
                    binding.btnSaveIdentity.setText(isArabic ? "اعتماد الموزون وتصدير MIRA ID" : "Approve & Export MIRA ID");

                    if (response != null && response.isSuccess) {
                        playSuccessSound();
                        Toast.makeText(getContext(),
                            isArabic ? "✅ تم تكويد القطعة وربطها بنجاح!" : "✅ Item encoded & linked successfully!",
                            Toast.LENGTH_LONG).show();
                        resetFormForNextScan();
                    } else {
                        String errorMsg = "Unknown error";
                        if (response != null) {
                            errorMsg = response.optString("message", "");
                            if (errorMsg.isEmpty()) errorMsg = response.error;
                            if (errorMsg == null || errorMsg.isEmpty()) errorMsg = "HTTP " + response.code;
                        }
                        Toast.makeText(getContext(),
                            (isArabic ? "❌ فشل التكويد: " : "❌ Encoding failed: ") + errorMsg,
                            Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                safeRun(() -> {
                    binding.btnSaveIdentity.setEnabled(true);
                    binding.btnSaveIdentity.setText(isArabic ? "اعتماد الموزون وتصدير MIRA ID" : "Approve & Export MIRA ID");
                    Toast.makeText(getContext(),
                        (isArabic ? "❌ خطأ: " : "❌ Error: ") + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ==================== Helpers ====================

    private void safeRun(Runnable r) {
        if (getActivity() != null) getActivity().runOnUiThread(r);
    }

    private void playSuccessSound() {
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).playSound(1);
        }
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
        
        // إعادة تعيين الصور
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
