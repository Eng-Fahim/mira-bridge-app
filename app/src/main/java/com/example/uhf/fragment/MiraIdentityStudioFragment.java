package com.example.uhf.fragment;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.databinding.FragmentMiraIdentityStudioBinding;
import com.google.android.material.chip.Chip;

import java.util.HashMap;
import java.util.Map;

public class MiraIdentityStudioFragment extends Fragment {

    private FragmentMiraIdentityStudioBinding binding;
    private final Map<String, String[]> typeSuggestions = new HashMap<>();
    
    // سعر جرام الذهب 24K افتراضي للتسهيل وحساب القيمة تلقائياً ($/gram)
    private double goldGramBasePriceUSD = 75.0; 

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
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
        typeSuggestions.put("rings", new String[]{"خاتم سوليتير ألماسي", "خاتم محبس كلاسيك", "خاتم توينز راقي", "خاتم فص زركون ملكي"});
        typeSuggestions.put("sets", new String[]{"طقم شبكة عروس كامل", "طقم ملكي مرصع بالألماس", "نصف طقم فاخر"});
        typeSuggestions.put("bracelets", new String[]{"بناجر / أساور خليجي", "سوار تنادي عصري", "إسورة زرد عريضة"});
        typeSuggestions.put("necklaces", new String[]{"عقد شوكر عصري", "قلادة بتصميم وردة", "سلسلة ذهبية ناعمة"});
    }

    private void setupListeners() {
        // 1. مراقبة حقل الـ EPC اليدوي والمسح
        binding.etEpc.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                binding.tvStatusBadge.setText(s.length() > 0 ? "BOUND" : "WAITING");
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        // 2. التحكم بظهور تفاصيل الأحجار
        binding.switchHasStones.setOnCheckedChangeListener((buttonView, isChecked) -> {
            binding.layoutStonesDetails.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        // 3. حساب سعر الذهب الخام تلقائياً عند كتابة الوزن
        binding.etGrossWeight.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                calculateEstimatedGoldValue();
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        // 4. تغيير العيار يعيد حساب القيمة
        binding.chipGroupKarat.setOnCheckedStateChangeListener((group, checkedIds) -> calculateEstimatedGoldValue());

        // 5. اقتراحات عناوين القطع
        binding.chipGroupTypes.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == binding.chipRing.getId()) populateSuggestions("rings");
            else if (id == binding.chipSet.getId()) populateSuggestions("sets");
            else if (id == binding.chipBracelet.getId()) populateSuggestions("bracelets");
            else if (id == binding.chipNecklace.getId()) populateSuggestions("necklaces");
        });

        // 6. التقاط الصور
        binding.btnAddPhotos.setOnClickListener(v -> {
            Toast.makeText(getContext(), "سيتم فتح كاميرا الجهاز لالتقاط صورة القطعة...", Toast.LENGTH_SHORT).show();
            binding.tvPhotosCount.setText("تم التقاط (1) صورة للقطعة");
        });

        // 7. اعتماد الهوية
        binding.btnSaveIdentity.setOnClickListener(v -> finalizeItemIdentity());
    }

    /**
 * 🧮 دالة حساب التكلفة بناءً على جدول أسعار الذهب والهوامش في نظام MIRA ID
 */
private void calculateEstimatedGoldValue() {
    String weightStr = binding.etGrossWeight.getText() != null ? binding.etGrossWeight.getText().toString().trim() : "";
    
    if (weightStr.isEmpty()) {
        binding.tvEstimatedGoldValue.setText("0.00 $");
        return;
    }

    try {
        double weight = Double.parseDouble(weightStr);
        double pricePerGram = getGoldPriceByKarat(); // جلب السعر حسب العيار المحدد من إعدادات النظام

        // 1️⃣ حساب قيمة الذهب الخام
        double rawGoldValue = weight * pricePerGram;
        
        // 2️⃣ عرض النتيجة (يمكنك تغيير الرمز إلى ﷼ أو $ حسب العملة المختارة في النظام)
        binding.tvEstimatedGoldValue.setText(String.format("%.2f $", rawGoldValue));

    } catch (NumberFormatException e) {
        binding.tvEstimatedGoldValue.setText("0.00 $");
    }
}

/**
 * 📡 جلب سعر جرام الذهب للعيار المختار من جدول أسعار MIRA ID (يمكن ربطها بـ API أو SharedPreferences)
 */
private double getGoldPriceByKarat() {
    int checkedId = binding.chipGroupKarat.getCheckedChipId();

    // ملاحظة: قم بتبديل هذه القيم بالقيم القادمة من API الإعدادات لديك
    if (checkedId == binding.chip24K.getId()) {
        return 75.0; // سعر بيع/شراء 24k المعتمد في النظام
    } else if (checkedId == binding.chip21K.getId()) {
        return 65.62; // سعر بيع/شراء 21k المعتمد في النظام (مثلاً: 54,250.80 YER / سعر الصرف)
    } else if (checkedId == binding.chip18K.getId()) {
        return 56.25; // سعر بيع/شراء 18k المعتمد في النظام
    } else if (checkedId == binding.chip14K.getId()) {
        return 43.75; // سعر بيع/شراء 14k المعتمد في النظام
    }

    return 65.62; // افتراضي (21k)
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
            android.view.ContextThemeWrapper contextWrapper = new android.view.ContextThemeWrapper(
                    requireContext(), 
                    com.google.android.material.R.style.Widget_MaterialComponents_Chip_Action
            );
            Chip chip = new Chip(contextWrapper);
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
        String barcode = binding.etBarcode != null && binding.etBarcode.getText() != null ? binding.etBarcode.getText().toString().trim() : "";

        // 1. التحقق من الحقول الإلزامية
        if (epc.isEmpty()) {
            Toast.makeText(getContext(), "يرجى مسح أو كتابة رمز RFID / GTIN أولاً", Toast.LENGTH_SHORT).show();
            return;
        }
        if (title.isEmpty()) {
            Toast.makeText(getContext(), "يرجى كتابة أو اختيار عنوان القطعة", Toast.LENGTH_SHORT).show();
            return;
        }
        if (weightStr.isEmpty()) {
            Toast.makeText(getContext(), "يرجى تحديد وزن القطعة", Toast.LENGTH_SHORT).show();
            return;
        }

        double grossWeight = Double.parseDouble(weightStr);
        String purityCode = getSelectedKaratCode(); // دالة مساعدة للحصول على العيار المختار (21K, 18K...)

        // تعطيل الزر مؤقتاً أثناء الإرسال لمنع التكرار
        binding.btnSaveIdentity.setEnabled(false);

        // 2. التنفيذ في Background Thread عبر MiraApiClient
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            com.example.uhf.api.MiraApiClient client = com.example.uhf.api.MiraApiClient.getInstance(requireContext());
            
            // إرسال طلب التكويد إلى السيرفر
            com.example.uhf.api.MiraApiClient.ApiResponse response = client.encodeItem(
                    epc,
                    barcode,
                    title,
                    purityCode,
                    grossWeight,
                    grossWeight, // netWeight
                    0.0,         // المصنعية (سيأخذ الافتراضي من السيرفر إذا كانت 0)
                    0.0,         // الهامش (سيأخذ الافتراضي من السيرفر إذا كانت 0)
                    "مجوهرات",
                    "المعرض الرئيسي"
            );

            // 3. العودة للـ UI Thread لتحديث الواجهة وتطبيق تجربة المستخدم (UX)
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    binding.btnSaveIdentity.setEnabled(true);

                    if (response.isSuccess) {
                        // تشغيل صوت النجاح
                        if (getActivity() instanceof UHFMainActivity) {
                            ((UHFMainActivity) getActivity()).playSound(1);
                        }

                        Toast.makeText(getContext(), "تمت المزامنة واعتُمدت الهوية الرقمية للقطعة بنجاح! 💎", Toast.LENGTH_LONG).show();

                        // 🔄 التفريغ الذكي للحقول للتكويد التالي
                        resetFormForNextScan();

                    } else {
                        Toast.makeText(getContext(), "خطأ في التكويد: " + response.error, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    /**
     * 🧹 تفريغ الحقول المتغيرة فقط لتجهيز الشاشة للقطعة التالية
     */
    private void resetFormForNextScan() {
        binding.etEpc.setText("");
        if (binding.etBarcode != null) binding.etBarcode.setText("");
        binding.etTitle.setText("");
        binding.etGrossWeight.setText("");
        binding.tvStatusBadge.setText("WAITING");
        binding.tvEstimatedGoldValue.setText("0.00 $");
        
        // إعادة التركيز لحقل المسح
        binding.etEpc.requestFocus();
    }

    /**
     * 🏷️ جلب كود العيار المختار من الـ Chips
     */
    private String getSelectedKaratCode() {
        int checkedId = binding.chipGroupKarat.getCheckedChipId();
        if (checkedId == binding.chip24K.getId()) return "24K";
        if (checkedId == binding.chip21K.getId()) return "21K";
        if (checkedId == binding.chip18K.getId()) return "18K";
        if (checkedId == binding.chip14K.getId()) return "14K";
        return "21K"; // افتراضي
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
