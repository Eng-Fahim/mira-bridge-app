package com.example.uhf.activity;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.example.uhf.databinding.ActivityItemStudioBinding;
import com.google.android.material.chip.Chip;

import java.util.HashMap;
import java.util.Map;

public class ItemStudioActivity extends AppCompatActivity {

    private ActivityItemStudioBinding binding;
    private final Map<String, String[]> typeSuggestions = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityItemStudioBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

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
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        // Handling Type Selection
        binding.chipGroupTypes.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            
            if (id == binding.chipRing.getId()) populateSuggestions("rings");
            else if (id == binding.chipSet.getId()) populateSuggestions("sets");
            else if (id == binding.chipBracelet.getId()) populateSuggestions("bracelets");
            else if (id == binding.chipNecklace.getId()) populateSuggestions("necklaces");
        });

        // Trigger Chainway RFID Hardware Reader Scan
        binding.btnScanRfid.setOnClickListener(v -> readChainwayRfidTag());

        // Final Save Handler
        binding.btnSaveIdentity.setOnClickListener(v -> finalizeItemIdentity());
    }

    private void populateSuggestions(String typeKey) {
        binding.chipGroupSuggestions.removeAllViews();
        String[] suggestions = typeSuggestions.get(typeKey);

        if (suggestions == null) return;

        for (String suggestion : suggestions) {
            Chip chip = new Chip(this);
            chip.setText(suggestion);
            chip.setClickable(true);
            chip.setCheckable(false);
            chip.setOnClickListener(v -> binding.etTitle.setText(suggestion));
            binding.chipGroupSuggestions.addView(chip);
        }
    }

    private void readChainwayRfidTag() {
        // الربط المباشر مع SDK الخاص بقارئ Chainway C72
        binding.tvRfidHint.setText("جاري قراءة الشريحة عبر UHF Serial...");
        
        /* 
         * يتم قراءة الـ EPC الممسوح من القارئ وتعيينه كـ GTIN 
         * مثال: RFIDReader.getInstance().readSingleTag();
         */
        
        // محاكاة التقاط الـ Tag وتحديث الواجهة مباشرة
        binding.getRoot().postDelayed(() -> {
            String scannedGtin = "070" + (long) (Math.random() * 9000000000L + 1000000000L);
            binding.tvGtinLive.setText("GTIN: " + scannedGtin);
            binding.tvStatusBadge.setText("BOUND");
            binding.tvStatusBadge.setBackgroundResource(com.example.uhf.R.drawable.bg_badge_gold);
            binding.tvRfidHint.setText("تم ربط الشريحة بنجاح!");
            Toast.makeText(this, "تم التقاط الـ RFID بنجاح", Toast.LENGTH_SHORT).show();
        }, 1000);
    }

    private void finalizeItemIdentity() {
        String title = binding.etTitle.getText() != null ? binding.etTitle.getText().toString() : "";
        String weight = binding.etGrossWeight.getText() != null ? binding.etGrossWeight.getText().toString() : "";

        if (title.isEmpty()) {
            Toast.makeText(this, "يرجى كتابة أو اختيار عنوان القطعة", Toast.LENGTH_SHORT).show();
            return;
        }

        if (weight.isEmpty()) {
            Toast.makeText(this, "يرجى تحديد وزن القطعة", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "تم اعتماد وتنسيق القطعة بنجاح بنظام MIRA ID", Toast.LENGTH_LONG).show();
        finish();
    }
}
