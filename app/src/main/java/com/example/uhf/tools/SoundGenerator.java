package com.example.uhf.tools;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * ✅ SoundGenerator v2.0 — نسخة آمنة للإنتاج
 * 
 * إصلاحات v2.0:
 * - AudioAttributes بدل STREAM_MUSIC (Android 12+)
 * - MODE_STREAM بدل MODE_STATIC (لا حد أقصى للذاكرة)
 * - try-finally لضمان release()
 * - ExecutorService آمن + إعادة إنشاء عند الفشل
 * - Buffer size آمن
 * - حماية كاملة ضد كل الاستثناءات
 */
public class SoundGenerator {

    private static final String TAG = "SoundGenerator";
    private static final int SAMPLE_RATE = 44100;
    private static final int BUFFER_SIZE_FACTOR = 4;

    // ✅ Executor محمي مع إعادة إنشاء تلقائي
    private static volatile ExecutorService soundExecutor;

    private static ExecutorService getExecutor() {
        if (soundExecutor == null || soundExecutor.isShutdown() || soundExecutor.isTerminated()) {
            synchronized (SoundGenerator.class) {
                if (soundExecutor == null || soundExecutor.isShutdown() || soundExecutor.isTerminated()) {
                    soundExecutor = new ThreadPoolExecutor(
                            1, 1,
                            0L, TimeUnit.MILLISECONDS,
                            new LinkedBlockingQueue<>(32),
                            r -> {
                                Thread t = new Thread(r, "SoundGenerator-Worker");
                                t.setDaemon(true);
                                t.setPriority(Thread.NORM_PRIORITY - 1);
                                return t;
                            },
                            new ThreadPoolExecutor.DiscardOldestPolicy()
                    );
                }
            }
        }
        return soundExecutor;
    }

    public static void playSound(String soundName) {
        if (soundName == null || soundName.equals("silent")) return;

        try {
            getExecutor().execute(() -> {
                try {
                    playSoundInternal(soundName);
                } catch (Throwable t) {
                    Log.e(TAG, "playSound internal error: " + t.getMessage(), t);
                }
            });
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "playSound rejected: " + e.getMessage());
        } catch (Throwable t) {
            Log.e(TAG, "playSound error: " + t.getMessage(), t);
        }
    }

    private static void playSoundInternal(String soundName) {
        switch (soundName) {
            case "beep_success": playSoftBeepInternal(); break;
            case "beep_soft": playVerySoftBeepInternal(); break;
            case "beep_double": playDoubleSuccessBeepInternal(); break;
            case "chime_elegant": playChimeInternal(); break;

            case "beep_warning": playWarningBeepInternal(); break;
            case "beep_error": playErrorBuzzInternal(); break;
            case "alarm_soft": playSoftAlarmInternal(); break;
            case "buzz_double": playDoubleBuzzInternal(); break;

            case "alarm_sold_item": playMediumAlarmInternal(); break;
            case "alarm_breach_security": playLoudAlarmInternal(); break;
            case "alarm_siren_strong": playSirenAlarmInternal(); break;
            case "alarm_police": playPoliceSirenInternal(); break;
            case "alarm_buzzer_loud": playLoudBuzzerInternal(); break;
            case "alarm_klaxon": playKlaxonInternal(); break;

            default: playMediumAlarmInternal();
        }
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
        try {
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
        } catch (Throwable t) {
            Log.e(TAG, "playPoliceSiren error: " + t.getMessage(), t);
        }
    }

    private static void playSirenAlarmInternal() {
        try {
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
        } catch (Throwable t) {
            Log.e(TAG, "playSirenAlarm error: " + t.getMessage(), t);
        }
    }

    private static void playLoudBuzzerInternal() { playTone(250, 600, 0.9); }

    private static void playKlaxonInternal() {
        try {
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
        } catch (Throwable t) {
            Log.e(TAG, "playKlaxon error: " + t.getMessage(), t);
        }
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
    // ✅ المحرك المُصلح — AudioAttributes + MODE_STREAM
    // ============================================
    private static void playTone(double frequency, int durationMs, double volume) {
        try {
            int numSamples = (durationMs * SAMPLE_RATE) / 1000;
            byte[] generatedSnd = new byte[2 * numSamples];

            for (int i = 0; i < numSamples; i++) {
                double sample = Math.sin(2 * Math.PI * frequency * i / SAMPLE_RATE);

                // تنعيم الحواف (Envelope) — 10ms
                int fadeSamples = 441;
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
        } catch (Throwable t) {
            Log.e(TAG, "playTone error: " + t.getMessage(), t);
        }
    }

    /**
     * ✅ streamAudio v2.0 — آمن من الانهيار
     * 
     * الفروقات عن v1:
     * - AudioAttributes بدل STREAM_MUSIC
     * - MODE_STREAM بدل MODE_STATIC
     * - try-finally لضمان release()
     * - حماية كاملة من IllegalStateException
     */
    private static void streamAudio(byte[] generatedSnd, int numSamples) {
        AudioTrack audioTrack = null;
        try {
            // ✅ 1) حجم الـ buffer الآمن
            int minBufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );

            if (minBufferSize <= 0) {
                Log.w(TAG, "Invalid minBufferSize: " + minBufferSize);
                return;
            }

            int bufferSize = Math.max(minBufferSize, generatedSnd.length);

            // ✅ 2) بناء AudioTrack مع AudioAttributes (Android 12+ compatible)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();

                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build();

                audioTrack = new AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
            } else {
                // ✅ Fallback للـ API < 23
                audioTrack = new AudioTrack(
                        AudioManager.STREAM_ALARM,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize,
                        AudioTrack.MODE_STREAM
                );
            }

            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack not initialized");
                return;
            }

            // ✅ 3) الكتابة والتشغيل
            audioTrack.write(generatedSnd, 0, generatedSnd.length);
            audioTrack.play();

            // ✅ 4) انتظار انتهاء التشغيل
            int durationMs = (numSamples * 1000) / SAMPLE_RATE;
            try {
                Thread.sleep(durationMs + 50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        } catch (Throwable t) {
            Log.e(TAG, "streamAudio error: " + t.getMessage(), t);
        } finally {
            // ✅ 5) تحرير آمن
            if (audioTrack != null) {
                try {
                    if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack.stop();
                    }
                } catch (Throwable ignored) {}

                try {
                    audioTrack.flush();
                } catch (Throwable ignored) {}

                try {
                    audioTrack.release();
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * ✅ إيقاف نظيف (اختياري — للاستدعاء في Application.onTerminate)
     */
    public static void shutdown() {
        try {
            if (soundExecutor != null && !soundExecutor.isShutdown()) {
                soundExecutor.shutdown();
                if (!soundExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    soundExecutor.shutdownNow();
                }
            }
        } catch (Throwable ignored) {}
    }
}
