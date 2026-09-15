package com.example.uhf.MiraRfid.sound;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MiraSoundSettingsFragment extends Fragment {

    private MiraSoundManager soundManager;
    private MaterialSwitch switchMasterSound;
    private MaterialSwitch switchMasterVibration;
    private Slider sliderVolume;
    private RecyclerView rvSoundEvents;
    private MaterialButton btnSaveSoundSettings;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_sound_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        soundManager = MiraSoundManager.getInstance(requireContext());

        initViews(view);
        setupMasterControls();
        setupRecyclerView();
        setupSaveButton();
    }

    private void initViews(View view) {
        switchMasterSound = view.findViewById(R.id.switchMasterSound);
        switchMasterVibration = view.findViewById(R.id.switchMasterVibration);
        sliderVolume = view.findViewById(R.id.sliderVolume);
        rvSoundEvents = view.findViewById(R.id.rvSoundEvents);
        btnSaveSoundSettings = view.findViewById(R.id.btnSaveSoundSettings);
    }

    private void setupMasterControls() {
        switchMasterSound.setChecked(soundManager.isMasterSoundEnabled());
        switchMasterVibration.setChecked(soundManager.isMasterVibrationEnabled());
        sliderVolume.setValue(soundManager.getVolumeLevel());

        switchMasterSound.setOnCheckedChangeListener((buttonView, isChecked) -> {
            soundManager.setMasterSoundEnabled(isChecked);
            if (isChecked) {
                soundManager.play(MiraSoundManager.SoundEvent.GENERAL_SUCCESS);
            }
        });

        switchMasterVibration.setOnCheckedChangeListener((buttonView, isChecked) -> {
            soundManager.setMasterVibrationEnabled(isChecked);
            if (isChecked) {
                soundManager.play(MiraSoundManager.SoundEvent.GENERAL_SUCCESS);
            }
        });

        sliderVolume.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {}

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                soundManager.setVolumeLevel((int) slider.getValue());
                soundManager.playPreview(
                        MiraSoundManager.SoundEvent.GENERAL_SUCCESS.defaultTone, 150);
            }
        });
    }

    private void setupRecyclerView() {
        rvSoundEvents.setLayoutManager(new LinearLayoutManager(requireContext()));
        List<MiraSoundManager.SoundEvent> events =
                new ArrayList<>(Arrays.asList(MiraSoundManager.SoundEvent.values()));
        SoundEventAdapter adapter =
                new SoundEventAdapter(requireContext(), events, soundManager);
        rvSoundEvents.setAdapter(adapter);
    }

    /**
     * ✅ زر الحفظ أصبح "استعادة الافتراضي" لأنه لا يوجد حفظ فعلي
     *    (كل تغيير يُحفظ فوراً في SharedPreferences)
     */
    private void setupSaveButton() {
        btnSaveSoundSettings.setText("🔄 استعادة الافتراضي");
        btnSaveSoundSettings.setOnClickListener(v -> showResetConfirmation());
    }

    private void showResetConfirmation() {
        new AlertDialog.Builder(requireContext())
                .setTitle("استعادة الإعدادات الافتراضية")
                .setMessage("هل أنت متأكد؟ سيتم مسح جميع تخصيصات الأصوات والاهتزازات.")
                .setPositiveButton("نعم، استعد", (dialog, which) -> {
                    soundManager.resetToDefaults();
                    refreshUI();
                    Toast.makeText(requireContext(),
                            "تمت استعادة الإعدادات الافتراضية ✅",
                            Toast.LENGTH_SHORT).show();
                    soundManager.play(MiraSoundManager.SoundEvent.GENERAL_SUCCESS);
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void refreshUI() {
        switchMasterSound.setChecked(soundManager.isMasterSoundEnabled());
        switchMasterVibration.setChecked(soundManager.isMasterVibrationEnabled());
        sliderVolume.setValue(soundManager.getVolumeLevel());
        if (rvSoundEvents.getAdapter() != null) {
            rvSoundEvents.getAdapter().notifyDataSetChanged();
        }
    }

    // =========================================================================
    // Adapter لتوليد كروت التحكم بالحدث ودعم المعاينة والتجربة المباشرة
    // =========================================================================

    private static class SoundEventAdapter
            extends RecyclerView.Adapter<SoundEventAdapter.EventViewHolder> {

        private final Context context;
        private final List<MiraSoundManager.SoundEvent> eventList;
        private final MiraSoundManager soundManager;

        // ✅ Adapter مشترك للنغمات (يُنشأ مرة واحدة)
        private final ArrayAdapter<String> tonesAdapter;
        private final Integer[] toneValues;

        public SoundEventAdapter(Context context,
                                 List<MiraSoundManager.SoundEvent> eventList,
                                 MiraSoundManager soundManager) {
            this.context = context;
            this.eventList = eventList;
            this.soundManager = soundManager;

            String[] tones = new String[]{
                    "نغمة افتراضية",
                    "تنبيه تأكيد",
                    "تنبيه خطأ",
                    "نغمة قصيرة",
                    "تنبيه مرتفع"
            };
            this.toneValues = new Integer[]{
                    -1, // placeholder - سيُستبدل بقيمة كل حدث
                    android.media.ToneGenerator.TONE_PROP_ACK,
                    android.media.ToneGenerator.TONE_SUP_ERROR,
                    android.media.ToneGenerator.TONE_PROP_BEEP,
                    android.media.ToneGenerator.TONE_CDMA_HIGH_L
            };
            this.tonesAdapter = new ArrayAdapter<>(
                    context, android.R.layout.simple_spinner_dropdown_item, tones);
        }

        @NonNull
        @Override
        public EventViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            MaterialCardView card = new MaterialCardView(context);
            ViewGroup.MarginLayoutParams cardParams = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(0, 0, 0, 24);
            card.setLayoutParams(cardParams);
            card.setRadius(24f);
            card.setCardElevation(2f);
            card.setStrokeWidth(2);

            View layout = createItemView();
            card.addView(layout);

            return new EventViewHolder(card, layout);
        }

        private View createItemView() {
            android.widget.LinearLayout container = new android.widget.LinearLayout(context);
            container.setOrientation(android.widget.LinearLayout.VERTICAL);
            container.setPadding(32, 24, 32, 24);

            android.widget.LinearLayout row1 = new android.widget.LinearLayout(context);
            row1.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row1.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView tvName = new TextView(context);
            tvName.setTextSize(15);
            tvName.setTypeface(null, android.graphics.Typeface.BOLD);
            android.widget.LinearLayout.LayoutParams nameParams =
                    new android.widget.LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            tvName.setLayoutParams(nameParams);

            MaterialSwitch switchSound = new MaterialSwitch(context);

            row1.addView(tvName);
            row1.addView(switchSound);

            android.widget.LinearLayout row2 = new android.widget.LinearLayout(context);
            row2.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row2.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row2.setPadding(0, 16, 0, 0);

            MaterialSwitch switchVibe = new MaterialSwitch(context);
            switchVibe.setText("📳 اهتزاز ");

            Spinner spinnerTone = new Spinner(context);
            android.widget.LinearLayout.LayoutParams spinnerParams =
                    new android.widget.LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            spinnerTone.setLayoutParams(spinnerParams);

            MaterialButton btnTest = new MaterialButton(context);
            btnTest.setText("🔊 معاينة");

            row2.addView(switchVibe);
            row2.addView(spinnerTone);
            row2.addView(btnTest);

            container.addView(row1);
            container.addView(row2);

            return container;
        }

        @Override
        public void onBindViewHolder(@NonNull EventViewHolder holder, int position) {
            MiraSoundManager.SoundEvent event = eventList.get(position);

            holder.tvName.setText(event.displayName);

            // ✅ إزالة الـ listeners القديمة قبل إعادة الربط
            holder.switchSound.setOnCheckedChangeListener(null);
            holder.switchVibe.setOnCheckedChangeListener(null);
            holder.spinnerTone.setOnItemSelectedListener(null);
            holder.spinnerTone.setOnTouchListener(null);

            // --- صوت الحدث ---
            holder.switchSound.setChecked(soundManager.isEventSoundEnabled(event));
            holder.switchSound.setOnCheckedChangeListener((btn, isChecked) -> {
                if (btn.isPressed()) {
                    soundManager.setEventSoundEnabled(event, isChecked);
                    if (isChecked) soundManager.play(event);
                }
            });

            // --- اهتزاز الحدث ---
            holder.switchVibe.setChecked(soundManager.isEventVibrationEnabled(event));
            holder.switchVibe.setOnCheckedChangeListener((btn, isChecked) -> {
                if (btn.isPressed()) {
                    soundManager.setEventVibrationEnabled(event, isChecked);
                }
            });

            // --- Spinner النغمات ---
            // ✅ استخدام adapter مشترك
            holder.spinnerTone.setAdapter(tonesAdapter);

            // ✅ بناء قيم النغمات مع قيمة "افتراضية" خاصة بالحدث
            Integer[] eventToneValues = new Integer[]{
                    event.defaultTone,
                    android.media.ToneGenerator.TONE_PROP_ACK,
                    android.media.ToneGenerator.TONE_SUP_ERROR,
                    android.media.ToneGenerator.TONE_PROP_BEEP,
                    android.media.ToneGenerator.TONE_CDMA_HIGH_L
            };

            int currentTone = soundManager.getEventTone(event);
            int selectedIndex = 0;
            for (int i = 0; i < eventToneValues.length; i++) {
                if (eventToneValues[i] == currentTone) {
                    selectedIndex = i;
                    break;
                }
            }

            // ✅ استخدام tag لمنع التشغيل التلقائي عند setSelection
            holder.spinnerTone.setTag(false);
            holder.spinnerTone.setSelection(selectedIndex, false);

            holder.spinnerTone.setOnTouchListener((v, eventTouch) -> {
                v.setTag(true);
                return false;
            });

            holder.spinnerTone.setOnItemSelectedListener(
                    new AdapterView.OnItemSelectedListener() {
                        @Override
                        public void onItemSelected(AdapterView<?> parent, View view,
                                                   int pos, long id) {
                            Object tag = holder.spinnerTone.getTag();
                            boolean isUserTouched = tag instanceof Boolean && (Boolean) tag;
                            if (isUserTouched) {
                                if (eventToneValues[pos] != soundManager.getEventTone(event)) {
                                    soundManager.setEventTone(event, eventToneValues[pos]);
                                }
                                holder.spinnerTone.setTag(false);
                            }
                        }

                        @Override
                        public void onNothingSelected(AdapterView<?> parent) {}
                    });

            // --- زر المعاينة ---
            holder.btnTest.setOnClickListener(v -> {
                int toneToPlay = soundManager.getEventTone(event);
                soundManager.playPreview(toneToPlay, event.defaultDuration);
            });
        }

        @Override
        public int getItemCount() {
            return eventList.size();
        }

        static class EventViewHolder extends RecyclerView.ViewHolder {
            TextView tvName;
            MaterialSwitch switchSound;
            MaterialSwitch switchVibe;
            Spinner spinnerTone;
            MaterialButton btnTest;

            public EventViewHolder(@NonNull View itemView, View layoutView) {
                super(itemView);
                ViewGroup container = (ViewGroup) layoutView;
                ViewGroup row1 = (ViewGroup) container.getChildAt(0);
                ViewGroup row2 = (ViewGroup) container.getChildAt(1);

                tvName = (TextView) row1.getChildAt(0);
                switchSound = (MaterialSwitch) row1.getChildAt(1);

                switchVibe = (MaterialSwitch) row2.getChildAt(0);
                spinnerTone = (Spinner) row2.getChildAt(1);
                btnTest = (MaterialButton) row2.getChildAt(2);
            }
        }
    }
}
