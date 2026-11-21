package com.example.uhf.utils

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

class MiraUhfSoundEngine {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Mira-UHF-Audio").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
        }
    }

    @Volatile
    var isMuted: Boolean = false

    @Volatile
    var muteDuplicates: Boolean = false

    private var lastMatchMs = 0L
    private var lastDupMs = 0L
    private var lastAlertMs = 0L

    /**
     * 1. صوت مطابقة قطعة جديدة (نقرة UHF نقية 38ms شبيهة بقارئ Chainway الرسمي)
     */
    fun playMatchTick() {
        if (isMuted) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastMatchMs < 45L) return
        lastMatchMs = now
        executor.execute {
            playTone(freqHz = 2350.0, durationMs = 38, volume = 0.75f)
        }
    }

    /**
     * 2. صوت تكرار قراءة نفس البطاقة (نقرة خفيفة جداً 14ms لا تسبب إزعاجاً)
     */
    fun playDuplicateTick() {
        if (isMuted || muteDuplicates) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastDupMs < 160L) return
        lastDupMs = now
        executor.execute {
            playTone(freqHz = 920.0, durationMs = 14, volume = 0.22f)
        }
    }

    /**
     * 3. صوت تنبيه لقطعة غير مسجلة في المخزون أو خارج الفئة (نغمة مزدوجة)
     */
    fun playAlertChirp() {
        if (isMuted) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAlertMs < 280L) return
        lastAlertMs = now
        executor.execute {
            playTone(freqHz = 1350.0, durationMs = 55, volume = 0.85f)
            playTone(freqHz = 1850.0, durationMs = 75, volume = 0.90f)
        }
    }

    /**
     * 4. نبضة رادار البحث عن قطعة مفقودة (Geiger RSSI)
     */
    fun playGeigerPing(proximityPercent: Int) {
        if (isMuted) return
        val clamped = proximityPercent.coerceIn(0, 100)
        val dynamicFreq = 1150.0 + (clamped * 15.5) // من 1150Hz إلى 2700Hz كلما اقتربت القطعة
        executor.execute {
            playTone(freqHz = dynamicFreq, durationMs = 32, volume = 0.85f)
        }
    }

    private fun playTone(freqHz: Double, durationMs: Int, volume: Float) {
        val sampleRate = 22050
        val numSamples = (sampleRate * durationMs) / 1000
        if (numSamples <= 0) return

        val buffer = ShortArray(numSamples)
        val fadeSamples = (numSamples * 0.15).toInt().coerceAtLeast(1)

        for (i in 0 until numSamples) {
            // مغلف تنعيم لمنع الطقطقة الصوتية عند البداية والنهاية
            val envelope = when {
                i < fadeSamples -> i.toDouble() / fadeSamples
                i > numSamples - fadeSamples -> (numSamples - i).toDouble() / fadeSamples
                else -> 1.0
            }
            val angle = 2.0 * PI * i * freqHz / sampleRate
            val sample = sin(angle) * volume * envelope * Short.MAX_VALUE
            buffer[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        try {
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(buffer, 0, buffer.size)
            audioTrack.play()
            Thread.sleep(durationMs.toLong() + 8L)
            audioTrack.stop()
            audioTrack.release()
        } catch (_: Exception) {
        }
    }

    fun release() {
        executor.shutdownNow()
    }
}
