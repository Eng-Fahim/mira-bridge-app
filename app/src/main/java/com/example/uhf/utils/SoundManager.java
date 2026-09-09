package com.example.uhf.utils;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.uhf.R;

public class SoundManager {

    private static final String TAG = "SoundManager";
    private static final int MAX_SOUND_STREAMS = 5;
    private static final float BEEP_VOLUME = 0.7f;
    private static final float BEEP_RATE = 1.0f;
    private static final int BEEP_PRIORITY = 0;
    private static final int BEEP_LOOP = 0;
    private static final long BEEP_THROTTLE_MS = 60;

    public enum FeedbackType {
        KNOWN_ITEM,
        UNKNOWN_ITEM,
        DUPLICATE_ITEM,
        MISSING_ITEM,
        COMPLETED,
        ERROR
    }

    private SoundPool soundPool;
    private int beepSoundId;
    private int successSoundId;
    private int errorSoundId;
    private int duplicateSoundId;
    private boolean soundLoaded = false;
    private long lastBeepTime = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public SoundManager(Context context) {
        initSoundPool(context);
    }

    private void initSoundPool(Context context) {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        soundPool = new SoundPool.Builder()
                .setMaxStreams(MAX_SOUND_STREAMS)
                .setAudioAttributes(attributes)
                .build();

        beepSoundId = soundPool.load(context, R.raw.barcodebeep, 1);
        successSoundId = soundPool.load(context, R.raw.barcodebeep, 1);
        errorSoundId = soundPool.load(context, R.raw.barcodebeep, 1);
        duplicateSoundId = soundPool.load(context, R.raw.barcodebeep, 1);

        soundPool.setOnLoadCompleteListener((sp, sampleId, status) -> {
            soundLoaded = (status == 0);
            if (!soundLoaded) {
                Log.w(TAG, "Failed to load sounds, status: " + status);
            }
        });
    }

    public void playBeep(FeedbackType feedbackType) {
        long now = System.currentTimeMillis();
        if (!soundLoaded || (now - lastBeepTime <= BEEP_THROTTLE_MS)) {
            return;
        }

        float rate = BEEP_RATE;
        float volume = BEEP_VOLUME;
        int soundId = beepSoundId;

        switch (feedbackType) {
            case KNOWN_ITEM:
                rate = 1.0f;
                volume = 0.8f;
                soundId = successSoundId;
                break;
            case UNKNOWN_ITEM:
                rate = 0.7f;
                volume = 0.6f;
                soundId = errorSoundId;
                break;
            case DUPLICATE_ITEM:
                rate = 1.5f;
                volume = 0.5f;
                soundId = duplicateSoundId;
                break;
            case MISSING_ITEM:
                rate = 0.5f;
                volume = 0.9f;
                soundId = errorSoundId;
                break;
            case COMPLETED:
                final float completedRate = 1.2f;
                final float completedVolume = 1.0f;
                final int completedSoundId = successSoundId;

                soundPool.play(completedSoundId, completedVolume, completedVolume,
                        BEEP_PRIORITY, BEEP_LOOP, completedRate);
                handler.postDelayed(() -> {
                    if (soundPool != null) {
                        soundPool.play(completedSoundId, completedVolume, completedVolume,
                                BEEP_PRIORITY, BEEP_LOOP, completedRate);
                    }
                }, 200);
                lastBeepTime = now;
                return;
            case ERROR:
                rate = 0.6f;
                volume = 0.8f;
                soundId = errorSoundId;
                break;
        }

        final float finalRate = rate;
        final float finalVolume = volume;
        final int finalSoundId = soundId;

        try {
            if (soundPool != null) {
                soundPool.play(finalSoundId, finalVolume, finalVolume,
                        BEEP_PRIORITY, BEEP_LOOP, finalRate);
                lastBeepTime = now;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing sound", e);
        }
    }

    public void release() {
        if (soundPool != null) {
            try {
                soundPool.release();
                soundPool = null;
            } catch (Exception e) {
                Log.e(TAG, "Error releasing sound pool", e);
            }
        }
    }
    public void cleanup() {
    soundExecutor.shutdown();
}
}
