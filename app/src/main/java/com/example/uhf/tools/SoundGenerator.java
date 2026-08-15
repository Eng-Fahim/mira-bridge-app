package com.example.uhf.tools;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 🎵 SoundGenerator - مولّد الأصوات المحسّن للإنتاج
 * آمن من ناحية الذاكرة ويتحمل المسح السريع متوالي الأداء.
 */
public class SoundGenerator {

    private static final String TAG = "SoundGenerator";
    private static final int SAMPLE_RATE = 44100;
    
    // محرك تنفيذي أحادي لضمان عدم تداخل الخيوط والتسريب
    private static final ExecutorService soundExecutor = Executors.newSingleThreadExecutor();

    public static void playSound(String soundName) {
        if (soundName == null || soundName.equals("silent")) return;

        soundExecutor.execute(() -> {
            switch (soundName) {
                // أصوات النجاح
                case "beep_success": playSoftBeepInternal(); break;
                case "beep_soft": playVerySoftBeepInternal(); break;
                case "beep_double": playDoubleSuccessBeepInternal(); break;
                case "chime_elegant": playChimeInternal(); break;

                // أصوات الرفض
                case "beep_warning": playWarningBeepInternal(); break;
                case "beep_error": playErrorBuzzInternal(); break;
                case "alarm_soft": playSoftAlarmInternal(); break;
                case "buzz_double": playDoubleBuzzInternal(); break;

                // أصوات الإنذار
                case "alarm_sold_item": playMediumAlarmInternal(); break;
                case "alarm_breach_security": playLoudAlarmInternal(); break;
                case "alarm_siren_strong": playSirenAlarmInternal(); break;
                case "alarm_police": playPoliceSirenInternal(); break;
                case "alarm_buzzer_loud": playLoudBuzzerInternal(); break;
                case "alarm_klaxon": playKlaxonInternal(); break;

                default: playMediumAlarmInternal();
            }
        });
    }

    // ============================================
    // 1️⃣ المستوى الأول: هادئ جداً
    // ============================================
    private static void playVerySoftBeepInternal() { playTone(550, 200, 0.15); }
    private static void playSoftBeepInternal() { playTone(880, 250, 0.4); }

    private static void playChimeInternal() {
        playTone(523, 180, 0.3);
        sleep(50);
        playTone(659, 180, 0.3);
        sleep(50);
        playTone(784, 250, 0.3);
    }

    // ============================================
    // 2️⃣ المستوى الثاني: متوسط
    // ============================================
    private static void playWarningBeepInternal() { playTone(440, 350, 0.5); }
    private static void playSoftAlarmInternal() { playTone(600, 600, 0.5); }

    private static void playDoubleBuzzInternal() {
        playTone(300, 180, 0.5);
        sleep(80);
        playTone(300, 250, 0.5);
    }

    // ============================================
    // 3️⃣ المستوى الثالث: قوي
    // ============================================
    private static void playLoudAlarmInternal() { playTone(1000, 800, 0.8); }
    private static void playMediumAlarmInternal() { playTone(800, 600, 0.7); }

    // ============================================
    // 4️⃣ المستوى الرابع: فاضح
    // ============================================
    private static void playPoliceSirenInternal() {
        int durationMs = 1500;
        int numSamples = (durationMs * SAMPLE_RATE) / 1000;
        byte[] generatedSnd = new byte[2 * numSamples];

        for (int i = 0; i < numSamples; i++) {
            int timeMs = (int) ((i / (double) SAMPLE_RATE) * 1000);
            double freq = ((timeMs / 250) % 2 == 0) ? 800.0 : 1200.0;
            double sample = Math.sin(2 * Math.PI * freq * i / SAMPLE_RATE);
            short val = (short) (sample * 32767 * 0.9);
            generatedSnd[2 * i] = (byte) (val & 0x00ff);
            generatedSnd[2 * i + 1] = (byte) ((val & 0xff00) >>> 8);
        }
        streamAudio(generatedSnd, numSamples);
    }

    private static void playSirenAlarmInternal() {
        int durationMs = 1200;
        int numSamples = (durationMs * SAMPLE_RATE) / 1000;
        byte[] generatedSnd = new byte[2 * numSamples];

        for (int i = 0; i < numSamples; i++) {
            double t = (double) i / SAMPLE_RATE;
            double modulation = 600 + 300 * Math.sin(2 * Math.PI * 0.8 * t);
            double sample = Math.sin(2 * Math.PI * modulation * t);
            short val = (short) (sample * 32767 * 0.85);
            generatedSnd[2 * i] = (byte) (val & 0x00ff);
            generatedSnd[2 * i + 1] = (byte) ((val & 0xff00) >>> 8);
        }
        streamAudio(generatedSnd, numSamples);
    }

    private static void playLoudBuzzerInternal() { playTone(250, 600, 0.9); }

    private static void playKlaxonInternal() {
        int durationMs = 800;
        int numSamples = (durationMs * SAMPLE_RATE) / 1000;
        byte[] generatedSnd = new byte[2 * numSamples];

        for (int i = 0; i < numSamples; i++) {
            double t = (double) i / SAMPLE_RATE;
            double sawtooth = 2 * (t % (1.0 / 350)) * 350 - 1;
            short val = (short) (sawtooth * 32767 * 0.7);
            generatedSnd[2 * i] = (byte) (val & 0x00ff);
            generatedSnd[2 * i + 1] = (byte) ((val & 0xff00) >>> 8);
        }
        streamAudio(generatedSnd, numSamples);
    }

    // ============================================
    // أصوات النجاح والخطأ
    // ============================================
    private static void playDoubleSuccessBeepInternal() {
        playTone(880, 120, 0.4);
        sleep(60);
        playTone(1046, 180, 0.4);
    }

    private static void playErrorBuzzInternal() { playTone(250, 400, 0.6); }

    // ============================================
    // المحرك المساعد والمُحسَّن
    // ============================================
    private static void playTone(double frequency, int durationMs, double volume) {
        int numSamples = (durationMs * SAMPLE_RATE) / 1000;
        byte[] generatedSnd = new byte[2 * numSamples];

        for (int i = 0; i < numSamples; i++) {
            double sample = Math.sin(2 * Math.PI * frequency * i / SAMPLE_RATE);

            // تنعيم الحواف (Envelope)
            int fadeSamples = 441; // 10ms fade in/out
            if (i < fadeSamples) {
                sample *= (i / (double) fadeSamples);
            } else if (i > numSamples - fadeSamples) {
                sample *= ((numSamples - i) / (double) fadeSamples);
            }

            short val = (short) (sample * 32767 * volume);
            generatedSnd[2 * i] = (byte) (val & 0x00ff);
            generatedSnd[2 * i + 1] = (byte) ((val & 0xff00) >>> 8);
        }
        streamAudio(generatedSnd, numSamples);
    }

    private static void streamAudio(byte[] generatedSnd, int numSamples) {
        try {
            AudioTrack audioTrack = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    generatedSnd.length,
                    AudioTrack.MODE_STATIC
            );

            audioTrack.write(generatedSnd, 0, generatedSnd.length);
            audioTrack.play();

            // الانتظار حتى ينتهي التشغيل فعلياً ثم التحرير
            int durationMs = (numSamples * 1000) / SAMPLE_RATE;
            Thread.sleep(durationMs + 20);

            audioTrack.stop();
            audioTrack.release();
        } catch (Exception e) {
            Log.e(TAG, "streamAudio error: " + e.getMessage());
        }
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }
}
