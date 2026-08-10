package com.example.uhf.fragment;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
    }

    @Override
    public void onResume() {
        super.onResume();
        syncPendingEncodes();
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

    private void calculateEstimatedGoldValue() {
        String weightStr = binding.etGrossWeight.getText() != null ? binding.etGrossWeight.getText().toString().trim() : "";
        if (weightStr.isEmpty()) { binding.tvEstimatedGoldValue.setText(isArabic ? "0.00 ريال" : "0.00 YER"); return; }
        try {
            double weight = Double.parseDouble(weightStr);
            double rawGoldValue = weight * getGoldPriceByKarat();
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

    public void onRfidScanned(String epc) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            binding.etEpc.setText(epc);
            binding.tvStatusBadge.setText("BOUND");
            if (getActivity() instanceof UHFMainActivity) ((UHFMainActivity) getActivity()).playSound(1);
        });
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

    private void finalizeItemIdentity() {
        String epc = binding.etEpc.getText() != null ? binding.etEpc.getText().toString().trim() : "";
        String title = binding.etTitle.getText() != null ? binding.etTitle.getText().toString().trim() : "";
        String weightStr = binding.etGrossWeight.getText() != null ? binding.etGrossWeight.getText().toString().trim() : "";

        if (epc.isEmpty()) { showToast(isArabic ? "يرجى مسح RFID أولاً" : "Please scan RFID first"); return; }
        if (title.isEmpty()) { showToast(isArabic ? "يرجى كتابة عنوان القطعة" : "Please enter item title"); return; }
        if (weightStr.isEmpty()) { showToast(isArabic ? "يرجى تحديد وزن القطعة" : "Please specify item weight"); return; }

        double grossWeight;
        try { grossWeight = Double.parseDouble(weightStr); }
        catch (NumberFormatException e) { showToast(isArabic ? "وزن غير صالح" : "Invalid weight"); return; }

        String stoneType = binding.etStoneType.getText() != null ? binding.etStoneType.getText().toString().trim() : "";
        double stoneWeight = 0; int stoneCount = 0; double stoneValue = 0;

        if (binding.switchHasStones.isChecked()) {
            try { stoneWeight = Double.parseDouble(binding.etStoneWeight.getText().toString().trim()); } catch (Exception ignored) {}
            try { stoneCount = Integer.parseInt(binding.etStoneCount.getText().toString().trim()); } catch (Exception ignored) {}
            try { stoneValue = Double.parseDouble(binding.etStoneValue.getText().toString().trim()); } catch (Exception ignored) {}
        }

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
                payload.put("purity_code", getSelectedKaratCode());
                payload.put("gross_weight", grossWeight);
                payload.put("net_weight", grossWeight - fStoneWeight);
                payload.put("stone_weight", fStoneWeight);
                payload.put("stone_count", fStoneCount);
                payload.put("stone_value", fStoneValue);
                payload.put("stone_type", stoneType);
                payload.put("making_per_gram", 0);
                payload.put("profit_margin", 0);
                payload.put("category", getSelectedCategory());
                payload.put("location", isArabic ? "المعرض الرئيسي" : "Main Showroom");
                payload.put("branch_id", branchesManager.getActiveBranchId());

                // ✅ محاولة API أولاً
                boolean apiSuccess = false;
                try {
                    MiraApiClient.ApiResponse response = apiClient.encodeItem(payload);
                    apiSuccess = (response != null && response.isSuccess);
                } catch (Exception apiError) {
                    Log.e(TAG, "API encode failed: " + apiError.getMessage());
                }

                final boolean success = apiSuccess;

                safeRun(() -> {
                    binding.btnSaveIdentity.setEnabled(true);
                    binding.btnSaveIdentity.setText(isArabic ? "اعتماد الموزون وتصدير MIRA ID" : "Approve & Export MIRA ID");

                    if (success) {
                        removePendingEncode(epc);
                        playSuccessSound();
                        showToast(isArabic ? "✅ تم تكويد القطعة وربطها بنجاح!" : "✅ Item encoded & linked successfully!");
                        resetFormForNextScan();
                    } else {
                        // حفظ محلي
                        saveEncodeOffline(payload);
                        showToast(isArabic ? "💾 تم حفظ التكويد محلياً للمزامنة لاحقاً" : "💾 Encoding saved locally for later sync");
                        resetFormForNextScan();
                    }
                });
            } catch (Exception e) {
                safeRun(() -> {
                    binding.btnSaveIdentity.setEnabled(true);
                    binding.btnSaveIdentity.setText(isArabic ? "اعتماد الموزون وتصدير MIRA ID" : "Approve & Export MIRA ID");
                    showToast((isArabic ? "❌ خطأ: " : "❌ Error: ") + e.getMessage());
                });
            }
        });
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
                        MiraApiClient.ApiResponse response = apiClient.encodeItem(payload);
                        if (response != null && response.isSuccess) synced++;
                        else remainingArr.put(payload);
                    } catch (Exception e) { remainingArr.put(arr.getJSONObject(i)); }
                }
                offlineCache.edit().putString("pending_encodes", remainingArr.toString()).apply();
                
                final int s = synced;
                safeRun(() -> { if (s > 0) showToast(isArabic ? "✅ تمت مزامنة " + s + " تكويد" : "✅ Synced " + s + " encodings"); });
            } catch (Exception e) { Log.e(TAG, "Sync encodes error: " + e.getMessage()); }
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
