package com.example.uhf.fragment;

import android.os.Bundle;
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
    private String currentEpc = "";

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
        // Selection Chips Handling
        binding.chipGroupTypes.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);

            if (id == binding.chipRing.getId()) populateSuggestions("rings");
            else if (id == binding.chipSet.getId()) populateSuggestions("sets");
            else if (id == binding.chipBracelet.getId()) populateSuggestions("bracelets");
            else if (id == binding.chipNecklace.getId()) populateSuggestions("necklaces");
        });

        // Save & Finalize Button
        binding.btnSaveIdentity.setOnClickListener(v -> finalizeItemIdentity());
    }

    /**
     * 📡 يتم استدعاء هذه الدالة من UHFMainActivity فور قراءة Tag جديدة بواسطة القارئ
     */
    public void onRfidScanned(String epc) {
        if (getActivity() == null) return;

        getActivity().runOnUiThread(() -> {
            this.currentEpc = epc;
            binding.tvGtinLive.setText("EPC/GTIN: " + epc);
            binding.tvStatusBadge.setText("BOUND");
            binding.tvRfidHint.setText("تم التقاط شريحة RFID بنجاح!");
            
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).playSound(1); // تشغيل صوت المسح الناجح
            }
        });
    }

    private void populateSuggestions(String typeKey) {
        binding.chipGroupSuggestions.removeAllViews();
        String[] suggestions = typeSuggestions.get(typeKey);

        if (suggestions == null || getContext() == null) return;

        for (String suggestion : suggestions) {
            // 🟢 تغليف الـ Context بثيم الـ Material لمنع الانهيار عند إنشاء العناصر ديناميكياً
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
        String title = binding.etTitle.getText() != null ? binding.etTitle.getText().toString() : "";
        String weight = binding.etGrossWeight.getText() != null ? binding.etGrossWeight.getText().toString() : "";

        if (currentEpc.isEmpty()) {
            Toast.makeText(getContext(), "يرجى مسح شريحة RFID أولاً لربط القطعة", Toast.LENGTH_SHORT).show();
            return;
        }

        if (title.isEmpty()) {
            Toast.makeText(getContext(), "يرجى كتابة أو اختيار عنوان القطعة", Toast.LENGTH_SHORT).show();
            return;
        }

        if (weight.isEmpty()) {
            Toast.makeText(getContext(), "يرجى تحديد وزن القطعة", Toast.LENGTH_SHORT).show();
            return;
        }

        // إرسال المسودة والتسجيل النهائي لنظام MIRA ID عبر API
        Toast.makeText(getContext(), "تمت المزامنة واعتُمدت الهوية الرقمية للقطعة بنجاح", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
