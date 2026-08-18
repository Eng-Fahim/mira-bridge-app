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

public class MiraIdentityStudioFragment extends MiraBaseFragment {

    private static final String TAG = "MiraIdentityStudio";
    private static final String OFFLINE_PREFS = "identity_studio_cache";
    private static final long RFID_TIMEOUT_MS = 30000;

    private FragmentMiraIdentityStudioBinding binding;
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private MiraPriceManager priceManager;
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
                priceManager = MiraPriceManager.getInstance(getContext());
                offlineCache = getContext().getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
            }
            isArabic = Locale.getDefault().getLanguage().equals("ar");

            initSuggestionsData();
            setupPhotoLaunchers();
            setupPhotosRecyclerView();
            applyLanguage();
            setupListeners();
            populateSuggestions("rings");
            updatePendingSyncBadge();

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
        if (priceManager != null) {
            priceManager.refreshNow();
            handler.postDelayed(() -> {
                if (isAdded() && binding != null) {
                    calculateEstimatedGoldValue();
                }
            }, 1000);
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
            binding.btnScanRfid.setText(isRfidScanning ? "جارٍ المسح..." : "مسح رمز RFID");
            binding.etTitle.setHint("عنوان ووصف القطعة");
            binding.btnSaveIdentity.setText("اعتماد الموزون وتصدير MIRA ID");
            binding.btnAddPhotos.setText("📷 إرفاق صور القطعة");
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
            binding.btnScanRfid.setText(isRfidScanning ? "Scanning..." : "Scan RFID Tag");
            binding.etTitle.setHint("Item Title & Description");
            binding.btnSaveIdentity.setText("Approve & Export MIRA ID");
            binding.btnAddPhotos.setText("📷 Attach Photos");
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
        binding.rvPhotosPreview.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        binding.rvPhotosPreview.setAdapter(photosAdapter);
    }

    private void setupListeners() {
        try {
            binding.etEpc.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    try { 
                        binding.tvStatusBadge.setText(s.length() > 0 ? "BOUND" : "WAITING"); 
                    } catch (Exception ignored) {}
                }
                @Override public void afterTextChanged(Editable s) {}
            });

            if (binding.tilEpc != null) {
                binding.tilEpc.setEndIconOnClickListener(v -> openBarcodeScannerForEPC());
            }

            if (binding.btnScanRfid != null) {
                binding.btnScanRfid.setOnClickListener(v -> {
                    if (isRfidScanning) {
                        stopRfidScanningMode();
                    } else {
                        activateRFIDModeForEncoding();
                    }
                });
            }

            binding.switchHasStones.setOnCheckedChangeListener((buttonView, isChecked) -> {
                try {
                    binding.layoutStonesDetails.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                    calculateEstimatedGoldValue();
                } catch (Exception ignored) {}
            });

            TextWatcher calculationWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    calculateEstimatedGoldValue();
                }
                @Override public void afterTextChanged(Editable s) {}
            };

            binding.etGrossWeight.addTextChangedListener(calculationWatcher);
            binding.etStoneValue.addTextChangedListener(calculationWatcher);

            binding.chipGroupKarat.setOnCheckedStateChangeListener((group, checkedIds) -> calculateEstimatedGoldValue());

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

            binding.btnAddPhotos.setOnClickListener(v -> showImageSourceDialog());
            binding.btnSaveIdentity.setOnClickListener(v -> finalizeItemIdentity());
        } catch (Exception e) {
            Log.e(TAG, "setupListeners error: " + e.getMessage());
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

    private void calculateEstimatedGoldValue() {
        try {
            if (binding == null || binding.etGrossWeight == null) return;

            String weightStr = binding.etGrossWeight.getText() != null ?
                    binding.etGrossWeight.getText().toString().trim() : "";

            if (weightStr.isEmpty()) {
                binding.tvEstimatedGoldValue.setText("0.00 " + priceManager.getCurrencySymbol(priceManager.getDefaultCurrency()));
                return;
            }

            double weight = Double.parseDouble(weightStr);
            if (weight <= 0) {
                binding.tvEstimatedGoldValue.setText("0.00 " + priceManager.getCurrencySymbol(priceManager.getDefaultCurrency()));
                return;
            }

            int karat = getSelectedKaratNumber();
            double gramPrice = priceManager.getFinalPricePerGram(karat);
            double goldValue = weight * gramPrice;

            double stoneValue = 0;
            if (binding.switchHasStones != null && binding.switchHasStones.isChecked()) {
                try {
                    String stoneValueStr = binding.etStoneValue.getText() != null ?
                            binding.etStoneValue.getText().toString().trim() : "";
                    if (!stoneValueStr.isEmpty()) {
                        stoneValue = Double.parseDouble(stoneValueStr);
                    }
                } catch (Exception ignored) {}
            }

            double totalPrice = goldValue + stoneValue;
            String formattedPrice = priceManager.formatPriceDefault(totalPrice);
            binding.tvEstimatedGoldValue.setText(formattedPrice);

        } catch (Exception e) {
            Log.e(TAG, "calculateEstimatedGoldValue error: " + e.getMessage());
        }
    }

    private double[] calculatePricingDetails(double weight, double stoneValue) {
        int karat = getSelectedKaratNumber();

        double basePricePerGram = priceManager.getGoldPricePerGram(karat);
        double makingPerGram = priceManager.getMakingCharge(karat);
        double finalPricePerGram = priceManager.getFinalPricePerGram(karat);

        double goldValue = weight * basePricePerGram;
        double makingCost = weight * makingPerGram;
        double totalCost = weight * finalPricePerGram;
        double profitAmount = totalCost - goldValue - makingCost;
        double finalPrice = totalCost + stoneValue;

        return new double[]{goldValue, makingCost, stoneValue, totalCost, profitAmount, finalPrice};
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
                            try {
                                String path = copyImageToInternalStorage(uri);
                                if (path != null) {
                                    photoPaths.add(path);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error picking image: " + e.getMessage());
                            }
                        }
                        onPhotosUpdated();
                    }
                }
        );
    }

    private void showImageSourceDialog() {
        if (getContext() == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(isArabic ? "إضافة صورة" : "Add Photo");

        String[] options = {
                isArabic ? "📷 التقاط صورة" : "📷 Take Photo",
                isArabic ? "🖼️ اختيار من الاستوديو" : "🖼️ Choose from Gallery"
        };

        builder.setItems(options, (dialog, which) -> {
            if (which == 0) {
                checkCameraPermissionAndTakePhoto();
            } else {
                openGallery();
            }
        });

        builder.show();
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
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }

            outputStream.close();
            inputStream.close();

            return destFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "copyImageToInternalStorage error: " + e.getMessage());
            return null;
        }
    }

    private void onPhotosUpdated() {
        if (photosAdapter != null) {
            photosAdapter.notifyDataSetChanged();
        }
        updatePhotosCountText();
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

                isRfidScanning = true;
                binding.btnScanRfid.setText(isArabic ? "جارٍ المسح... (إيقاف)" : "Scanning... (Stop)");
                binding.tvStatusBadge.setText("SCANNING...");

                activity.setActiveRfidListener(epc -> {
                    try {
                        if (binding != null && binding.etEpc != null) {
                            binding.etEpc.setText(epc);
                            binding.tvStatusBadge.setText("BOUND");
                        }
                        if (activity != null) activity.playSound(1);
                        stopRfidScanningMode();
                    } catch (Exception ignored) {}
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
        try {
            if (getActivity() == null || binding == null) return;
            getActivity().runOnUiThread(() -> {
                try {
                    binding.etEpc.setText(epc);
                    binding.tvStatusBadge.setText("BOUND");
                    if (getActivity() instanceof UHFMainActivity) {
                        ((UHFMainActivity) getActivity()).playSound(1);
                    }
                    stopRfidScanningMode();
                } catch (Exception ignored) {}
            });
        } catch (Exception e) {
            Log.e(TAG, "onRfidScanned error: " + e.getMessage());
        }
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

        MaterialButton btnViewDigitalIdentity = view.findViewById(R.id.btnViewDigitalIdentity);
        MaterialButton btnNewEncode = view.findViewById(R.id.btnNewEncode);
        MaterialButton btnClose = view.findViewById(R.id.btnCloseDialog);

        if (tvTitle != null) tvTitle.setText(title);
        if (tvEpc != null) tvEpc.setText(epc);
        if (tvWeight != null) tvWeight.setText(weight + (isArabic ? " جرام" : " g"));

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
                showDigitalIdentityDialog(title, epc, weight, finalPrice);
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

    private void showDigitalIdentityDialog(String title, String epc, String weight, double finalPrice) {
        if (!isAdded() || getContext() == null) return;

        BottomSheetDialog identityDialog = new BottomSheetDialog(requireContext());
        View view = getLayoutInflater().inflate(R.layout.dialog_digital_identity, null);
        identityDialog.setContentView(view);

        TextView tvIdTitle = view.findViewById(R.id.tvIdTitle);
        TextView tvEpcCode = view.findViewById(R.id.tvEpcCode);
        TextView tvIdWeight = view.findViewById(R.id.tvIdWeight);
        TextView tvIdMetalKarat = view.findViewById(R.id.tvIdMetalKarat);
        MaterialButton btnClose = view.findViewById(R.id.btnCloseIdentity);

        if (tvIdTitle != null) tvIdTitle.setText(title);
        if (tvEpcCode != null) tvEpcCode.setText("EPC: " + epc);
        if (tvIdWeight != null) tvIdWeight.setText(weight + (isArabic ? " جرام" : " g"));
        if (tvIdMetalKarat != null) tvIdMetalKarat.setText(getSelectedKaratCode());

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                identityDialog.dismiss();
                resetFormForNextScan();
            });
        }

        identityDialog.show();
    }

    private void finalizeItemIdentity() {
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
                showValidationError(isArabic ?
                        "❌ رمز RFID غير صالح!\n\nيجب أن يكون EPC قياسي أو GTIN-13 أو Serial Number." :
                        "❌ Invalid RFID code!\n\nMust be EPC, GTIN-13, or Serial Number.");
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

            double grossWeight;
            try {
                grossWeight = Double.parseDouble(weightStr);
            } catch (NumberFormatException e) {
                showValidationError(isArabic ? "وزن غير صالح" : "Invalid weight");
                return;
            }

            if (grossWeight <= 0) {
                showValidationError(isArabic ? "يجب أن يكون الوزن أكبر من صفر" : "Weight must be greater than zero");
                return;
            }

            String stoneType = "";
            double stoneWeight = 0;
            int stoneCount = 0;
            double stoneValue = 0;

            if (binding.switchHasStones.isChecked()) {
                stoneType = binding.etStoneType.getText() != null ? binding.etStoneType.getText().toString().trim() : "";
                String stoneWeightStr = binding.etStoneWeight.getText() != null ? binding.etStoneWeight.getText().toString().trim() : "";
                String stoneCountStr = binding.etStoneCount.getText() != null ? binding.etStoneCount.getText().toString().trim() : "";
                String stoneValueStr = binding.etStoneValue.getText() != null ? binding.etStoneValue.getText().toString().trim() : "";

                if (!stoneValueStr.isEmpty()) {
                    try {
                        stoneValue = Double.parseDouble(stoneValueStr);
                    } catch (NumberFormatException e) {
                        showValidationError(isArabic ? "قيمة الأحجار غير صالحة" : "Invalid stone value");
                        return;
                    }
                }
                if (!stoneWeightStr.isEmpty()) {
                    try { stoneWeight = Double.parseDouble(stoneWeightStr); } catch (NumberFormatException ignored) {}
                }
                if (!stoneCountStr.isEmpty()) {
                    try { stoneCount = Integer.parseInt(stoneCountStr); } catch (NumberFormatException ignored) {}
                }
            }

            double[] pricing = calculatePricingDetails(grossWeight, stoneValue);
            final double finalGoldValue = pricing[0];
            final double finalMakingCost = pricing[1];
            final double finalTotalCost = pricing[3];
            final double finalProfitAmount = pricing[4];
            final double finalRetailPrice = pricing[5];

            final AlertDialog loadingDialog = showProfessionalLoadingDialog(
                    isArabic ? "🔄 جاري التكويد..." : "🔄 Encoding in progress...",
                    isArabic ? "يرجى الانتظار - يتم الآن حفظ القطعة وربطها بالنظام" : "Please wait - saving item and linking to system"
            );

            setFormEnabled(false);

            final String finalEpc = epc;
            final String finalTitle = title;
            final double fGrossWeight = grossWeight;
            final double fStoneWeight = stoneWeight;
            final int fStoneCount = stoneCount;
            final double fStoneValue = stoneValue;
            final String fStoneType = stoneType;
            final String selectedKarat = getSelectedKaratCode();
            final String selectedCategory = getSelectedCategory();
            final String location = isArabic ? "المعرض الرئيسي" : "Main Showroom";
            final int branchId = (branchesManager != null) ? branchesManager.getActiveBranchId() : 1;
            final List<String> finalPhotoPaths = new ArrayList<>(photoPaths);
            final int selectedKaratNum = getSelectedKaratNumber();
            final int currentUserId = getCurrentUserId();

            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    if (apiClient == null && getContext() != null) {
                        apiClient = MiraApiClient.getInstance(getContext());
                    }

                    JSONObject payload = new JSONObject();
                    payload.put("epc", finalEpc);
                    payload.put("title", finalTitle);
                    payload.put("purity_code", selectedKarat);
                    payload.put("gross_weight", fGrossWeight);
                    payload.put("net_weight", fGrossWeight - fStoneWeight);
                    payload.put("stone_weight", fStoneWeight);
                    payload.put("stone_count", fStoneCount);
                    payload.put("stone_value", fStoneValue);
                    payload.put("stone_type", fStoneType);
                    payload.put("gold_price_per_gram", priceManager.getGoldPricePerGram(selectedKaratNum));
                    payload.put("gold_value", finalGoldValue);
                    payload.put("making_per_gram", priceManager.getMakingCharge(selectedKaratNum));
                    payload.put("making_charge", finalMakingCost);
                    payload.put("total_cost", finalTotalCost);
                    payload.put("profit_margin", priceManager.getMargin(selectedKaratNum));
                    payload.put("profit_amount", finalProfitAmount);
                    payload.put("retail_price", finalRetailPrice);
                    payload.put("category", selectedCategory);
                    payload.put("location", location);
                    payload.put("branch_id", branchId);
                    payload.put("user_id", currentUserId);

                    if (!finalPhotoPaths.isEmpty()) {
                        JSONArray imagesArray = new JSONArray();
                        for (String path : finalPhotoPaths) {
                            imagesArray.put(path);
                        }
                        payload.put("image_paths", imagesArray);
                    }

                    boolean apiSuccess = false;
                    int serverItemId = 0;
                    String serverErrorMessage = "";

                    if (apiClient != null) {
                        try {
                            MiraApiClient.ApiResponse response = apiClient.encodeItem(payload);
                            apiSuccess = (response != null && response.isSuccess);
                            if (response != null && response.data != null) {
                                serverItemId = response.data.optInt("item_id", 0);
                            }
                            if (response != null && response.error != null) {
                                serverErrorMessage = response.error;
                            }
                        } catch (Exception apiError) {
                            serverErrorMessage = apiError.getMessage();
                        }
                    }

                    if (!apiSuccess) {
                        saveEncodeOffline(payload);
                    }

                    final boolean success = apiSuccess;
                    final int finalServerItemId = serverItemId;
                    final String finalServerErrorMessage = serverErrorMessage;

                    safeRun(() -> {
                        dismissDialog(loadingDialog);
                        setFormEnabled(true);

                        if (success) {
                            removePendingEncode(finalEpc);
                            playSuccessSound();

                            showEncodingSuccessDialog(
                                    finalTitle,
                                    finalEpc,
                                    String.format(Locale.US, "%.2f", fGrossWeight),
                                    finalRetailPrice
                            );

                            if (!finalPhotoPaths.isEmpty() && finalServerItemId > 0) {
                                uploadItemImages(finalServerItemId, finalPhotoPaths);
                            }
                        } else {
                            playSuccessSound();
                            updatePendingSyncBadge();
                            showToast(isArabic ? "💾 تم الحفظ محلياً (أوفلاين)" : "💾 Saved locally (Offline)");
                            showEncodingSuccessDialog(
                                    finalTitle,
                                    finalEpc,
                                    String.format(Locale.US, "%.2f", fGrossWeight),
                                    finalRetailPrice
                            );
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Background execution error: " + e.getMessage());
                    safeRun(() -> {
                        dismissDialog(loadingDialog);
                        setFormEnabled(true);
                        showRetryDialog(
                                isArabic ? "❌ خطأ في المعالجة" : "❌ Processing Error",
                                e.getMessage(),
                                this::finalizeItemIdentity
                        );
                    });
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "finalizeItemIdentity error: " + e.getMessage());
            showToast(isArabic ? "❌ خطأ: " + e.getMessage() : "❌ Error: " + e.getMessage());
        }
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
            binding.btnSaveIdentity.setEnabled(enabled);
            binding.btnAddPhotos.setEnabled(enabled);
            binding.etEpc.setEnabled(enabled);
            binding.etTitle.setEnabled(enabled);
            binding.etGrossWeight.setEnabled(enabled);
            binding.switchHasStones.setEnabled(enabled);
            binding.etStoneType.setEnabled(enabled);
            binding.etStoneWeight.setEnabled(enabled);
            binding.etStoneCount.setEnabled(enabled);
            binding.etStoneValue.setEnabled(enabled);
            binding.chipGroupKarat.setEnabled(enabled);
            binding.chipGroupTypes.setEnabled(enabled);
            binding.btnScanRfid.setEnabled(enabled);

            binding.btnSaveIdentity.setAlpha(enabled ? 1.0f : 0.5f);
        } catch (Exception e) {
            Log.e(TAG, "setFormEnabled error: " + e.getMessage());
        }
    }

    private void showRetryDialog(String title, String message, Runnable retryAction) {
        try {
            if (getContext() == null || !isAdded()) return;

            AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
            builder.setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(isArabic ? "🔄 إعادة المحاولة" : "🔄 Retry", (dialog, which) -> {
                        dialog.dismiss();
                        if (retryAction != null) retryAction.run();
                    })
                    .setNegativeButton(isArabic ? "إلغاء" : "Cancel", (dialog, which) -> dialog.dismiss())
                    .setCancelable(false)
                    .show();
        } catch (Exception e) {
            showToast(message);
        }
    }

    private void dismissDialog(AlertDialog dialog) {
        try {
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
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

            AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
            builder.setTitle(isArabic ? "⚠️ تنبيه" : "⚠️ Alert")
                    .setMessage(message)
                    .setPositiveButton(isArabic ? "حسناً" : "OK", null)
                    .show();
        } catch (Exception e) {
            showToast(message);
        }
    }

    private void uploadItemImages(int itemId, List<String> imagePaths) {
        if (imagePaths == null || imagePaths.isEmpty() || itemId <= 0) return;
        if (apiClient == null) return;

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
            updatePendingSyncBadge();
        } catch (Exception ignored) {}
    }

    private void updatePendingSyncBadge() {
        try {
            if (offlineCache == null || binding == null || binding.tvPendingSyncCount == null) return;
            String json = offlineCache.getString("pending_encodes", "[]");
            JSONArray arr = new JSONArray(json);
            int count = arr.length();
            if (count > 0) {
                binding.tvPendingSyncCount.setVisibility(View.VISIBLE);
                binding.tvPendingSyncCount.setText(isArabic ? "انتظار المزامنة: " + count : "Pending Sync: " + count);
            } else {
                binding.tvPendingSyncCount.setVisibility(View.GONE);
            }
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
            binding.etStoneType.setText("");
            binding.etStoneWeight.setText("");
            binding.etStoneCount.setText("");
            binding.etStoneValue.setText("");
            binding.tvStatusBadge.setText("WAITING");

            String currencySymbol = priceManager.getCurrencySymbol(priceManager.getDefaultCurrency());
            binding.tvEstimatedGoldValue.setText("0.00 " + currencySymbol);

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

    // ============================================
    // Inner Adapter for Photo Preview Thumbnails
    // ============================================
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

