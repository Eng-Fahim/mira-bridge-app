package com.example.uhf.tools;

import android.content.Context;
import android.media.AudioManager;
import android.media.SoundPool;
import android.media.ToneGenerator;
import android.util.Log;
import com.example.uhf.R;

public class MiraSoundManager {
    private static final String TAG = "MiraSoundManager";
    private SoundPool soundPool;
    private int soundSuccess = 0;
    private int soundWarning = 0;
    private int soundAlarm = 0;
    private boolean isLoaded = false;
    private ToneGenerator fallbackToneGen;

    public MiraSoundManager(Context context) {
        try {
            // تهيئة SoundPool الحديثة
            SoundPool.Builder builder = new SoundPool.Builder();
            builder.setMaxStreams(3);
            soundPool = builder.build();

            soundPool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
                if (status == 0) {
                    isLoaded = true;
                }
            });

            // محاولة تحميل الملفات الصوتية إن وجدت بأمان تام
            int successRes = getResourceID(context, "beep_success");
            int warningRes = getResourceID(context, "beep_warning");
            int alarmRes = getResourceID(context, "alarm_sold_item");

            if (successRes != 0) soundSuccess = soundPool.load(context, successRes, 1);
            if (warningRes != 0) soundWarning = soundPool.load(context, warningRes, 1);
            if (alarmRes != 0) soundAlarm = soundPool.load(context, alarmRes, 1);

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize SoundPool: " + e.getMessage());
        }

        try {
            fallbackToneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize ToneGenerator fallback");
        }
    }

    private int getResourceID(Context context, String resName) {
        try {
            return context.getResources().getIdentifier(resName, "raw", context.getPackageName());
        } catch (Exception e) {
            return 0;
        }
    }

    public void playSuccess() {
        if (isLoaded && soundSuccess != 0) {
            soundPool.play(soundSuccess, 1.0f, 1.0f, 1, 0, 1.0f);
        } else if (fallbackToneGen != null) {
            fallbackToneGen.startTone(ToneGenerator.TONE_PROP_ACK, 120); // صفيرة نجاح ناعمة
        }
    }

    public void playWarning() {
        if (isLoaded && soundWarning != 0) {
            soundPool.play(soundWarning, 1.0f, 1.0f, 1, 0, 1.0f);
        } else if (fallbackToneGen != null) {
            fallbackToneGen.startTone(ToneGenerator.TONE_PROP_NACK, 150); // صفيرة تنبيه
        }
    }

    public void playAlarm() {
        if (isLoaded && soundAlarm != 0) {
            soundPool.play(soundAlarm, 1.0f, 1.0f, 1, 0, 1.0f);
        } else if (fallbackToneGen != null) {
            // إنذار مزدوج قوي للقطع المباعة بالرف
            fallbackToneGen.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 300);
        }
    }

    public void release() {
        try {
            if (soundPool != null) {
                soundPool.release();
                soundPool = null;
            }
            if (fallbackToneGen != null) {
                fallbackToneGen.release();
                fallbackToneGen = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
