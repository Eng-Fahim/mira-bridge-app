package com.example.uhf.fragment;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.databinding.FragmentMiraIdentityStudioBinding;
import com.google.android.material.chip.Chip;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class MiraIdentityStudioFragment extends Fragment {

    private static final String TAG = "MiraIdentityStudio";
    private FragmentMiraIdentityStudioBinding binding;
    private final Map<String, String[]> typeSuggestions = new HashMap<>();
    private double goldGramBasePriceUSD = 75.0;

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
        initSuggestionsData();
        setupListeners();
    }

    private void initSuggestionsData() {
        boolean isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
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

        boolean isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        binding.btnAddPhotos.setOnClickListener(v -> {
            Toast.makeText(getContext(), isArabic ? "سيتم فتح كاميرا الجهاز لالتقاط صورة القطعة..." : "Opening camera to capture item photo...", Toast.LENGTH_SHORT).show();
            binding.tvPhotosCount.setText(isArabic ? "تم التقاط (1) صورة للقطعة" : "Captured (1) photo");
        });

        binding.btnSaveIdentity.setOnClickListener(v -> finalizeItemIdentity());
    }

    private void calculateEstimatedGoldValue() {
        String weightStr = binding.etGrossWeight.getText() != null ? binding.etGrossWeight.getText().toString().trim() : "";
        if (weightStr.isEmpty()) {
            binding.tvEstimatedGoldValue.setText("0.00 $");
            return;
        }
        try {
            double weight = Double.parseDouble(weightStr);
            double pricePerGram = getGoldPriceByKarat();
            double rawGoldValue = weight * pricePerGram;
            binding.tvEstimatedGoldValue.setText(String.format("%.2f $", rawGoldValue));
        } catch (NumberFormatException e) {
            binding.tvEstimatedGoldValue.setText("0.00 $");
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
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).playSound(1);
            }
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

    private void finalizeItemIdentity() {
        String epc = binding.etEpc.getText() != null ? binding.etEpc.getText().toString().trim() : "";
        String title = binding.etTitle.getText() != null ? binding.etTitle.getText().toString().trim() : "";
        String weightStr = binding.etGrossWeight.getText() != null ? binding.etGrossWeight.getText().toString().trim() : "";
        String barcode = ""; 
        boolean isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");

        // ✅ التحقق من الحقول الإلزامية
        if (epc.isEmpty()) {
            Toast.makeText(getContext(), isArabic ? "يرجى مسح أو كتابة رمز RFID / GTIN أولاً" : "Please scan or enter RFID / GTIN first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (title.isEmpty()) {
            Toast.makeText(getContext(), isArabic ? "يرجى كتابة أو اختيار عنوان القطعة" : "Please enter or select item title", Toast.LENGTH_SHORT).show();
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

        String purityCode = getSelectedKaratCode();
        String category = getSelectedCategory(isArabic);
        String location = isArabic ? "المعرض الرئيسي" : "Main Showroom";

        binding.btnSaveIdentity.setEnabled(false);
        binding.btnSaveIdentity.setText(isArabic ? "جاري التكويد..." : "Encoding...");

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                // ✅ بناء JSON كامل للإرسال
                JSONObject payload = new JSONObject();
                payload.put("epc", epc);
                payload.put("barcode", barcode);
                payload.put("title", title);
                payload.put("purity_code", purityCode);
                payload.put("gross_weight", grossWeight);
                payload.put("net_weight", grossWeight);
                payload.put("making_per_gram", 0);
                payload.put("profit_margin", 0);
                payload.put("category", category);
                payload.put("location", location);

                MiraApiClient client = MiraApiClient.getInstance(requireContext());
                MiraApiClient.ApiResponse response = client.encodeItem(payload);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        binding.btnSaveIdentity.setEnabled(true);
                        binding.btnSaveIdentity.setText(isArabic ? "اعتماد الهوية وتسجيلها ✨" : "Approve & Register Identity ✨");

                        if (response != null && response.isSuccess) {
                            if (getActivity() instanceof UHFMainActivity) {
                                ((UHFMainActivity) getActivity()).playSound(1);
                            }
                            Toast.makeText(getContext(),
                                isArabic ? "✅ تمت المزامنة واعتُمدت الهوية الرقمية للقطعة بنجاح!" : "✅ Digital identity successfully synchronized!",
                                Toast.LENGTH_LONG).show();
                            resetFormForNextScan();
                        } else {
                            String errorMsg = response != null ? response.optString("message", response.error) : "Connection failed";
                            if (errorMsg == null || errorMsg.isEmpty()) errorMsg = "Unknown error";
                            Log.e(TAG, "Encode failed: " + errorMsg);
                            Toast.makeText(getContext(),
                                (isArabic ? "❌ فشل التكويد: " : "❌ Encoding failed: ") + errorMsg,
                                Toast.LENGTH_LONG).show();
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Encode error: " + e.getMessage(), e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        binding.btnSaveIdentity.setEnabled(true);
                        binding.btnSaveIdentity.setText(isArabic ? "اعتماد الهوية وتسجيلها ✨" : "Approve & Register Identity ✨");
                        Toast.makeText(getContext(),
                            (isArabic ? "❌ خطأ: " : "❌ Error: ") + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    });
                }
            }
        });
    }

    private String getSelectedCategory(boolean isArabic) {
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
        if (binding.etBarcode != null) binding.etBarcode.setText("");
        //binding.tvStatusBadge.setText("WAITING");
        binding.tvEstimatedGoldValue.setText("0.00 $");
        binding.etEpc.requestFocus();
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
