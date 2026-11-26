package com.ryan.robbaniexam.security

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/**
 * Synthesizes crisp alarm tones on Android's ALARM audio stream so alerts work offline
 * and remain audible for several seconds when a student violates exam rules.
 */
object ExamAlarmPlayer {
    private const val SAMPLE_RATE = 22050
    private val isPlaying = AtomicBoolean(false)

    @Volatile
    private var activeThread: Thread? = null

    @Volatile
    private var activeTrack: AudioTrack? = null

    fun playWarningBeep(context: Context) {
        triggerVibration(context, longArrayOf(0, 220, 120, 220))
        playSirenAsync(context.applicationContext, durationMs = 1200L, lowFreq = 780.0, highFreq = 1040.0, cycleMs = 300)
    }

    fun playEmergencySiren(context: Context, durationMs: Long = 4500L) {
        triggerVibration(context, longArrayOf(0, 400, 150, 400, 150, 500, 150, 400))
        playSirenAsync(context.applicationContext, durationMs = durationMs, lowFreq = 820.0, highFreq = 1320.0, cycleMs = 450)
    }

    fun stopAlarm() {
        isPlaying.set(false)
        try {
            activeTrack?.pause()
            activeTrack?.flush()
            activeTrack?.release()
        } catch (_: Exception) {
        } finally {
            activeTrack = null
        }
        try {
            activeThread?.interrupt()
        } catch (_: Exception) {
        } finally {
            activeThread = null
        }
    }

    private fun playSirenAsync(
        context: Context,
        durationMs: Long,
        lowFreq: Double,
        highFreq: Double,
        cycleMs: Int
    ) {
        stopAlarm()
        isPlaying.set(true)

        val thread = Thread {
            var track: AudioTrack? = null
            try {
                boostAlarmStreamIfMuted(context)

                val totalSamples = ((SAMPLE_RATE * durationMs) / 1000L).toInt().coerceAtLeast(SAMPLE_RATE / 2)
                val samples = ShortArray(totalSamples)
                val cycleSamples = ((SAMPLE_RATE * cycleMs) / 1000.0).coerceAtLeast(1.0)
                var phase = 0.0

                for (i in 0 until totalSamples) {
                    if (!isPlaying.get()) break
                    val cyclePos = (i % cycleSamples.toInt()) / cycleSamples
                    // Smooth triangle sweep between lowFreq and highFreq
                    val sweep = if (cyclePos < 0.5) cyclePos * 2.0 else (1.0 - cyclePos) * 2.0
                    val freq = lowFreq + (highFreq - lowFreq) * sweep
                    phase += 2.0 * PI * freq / SAMPLE_RATE

                    // Apply short fade-in/fade-out envelope to avoid clicks
                    val fadeSamples = (SAMPLE_RATE * 0.04).toInt()
                    val envelope = when {
                        i < fadeSamples -> i.toDouble() / fadeSamples
                        i > totalSamples - fadeSamples -> (totalSamples - i).toDouble() / fadeSamples
                        else -> 1.0
                    }.coerceIn(0.0, 1.0)

                    // Rich sawtooth/square-blended alarm timbre at high clarity
                    val fundamental = sin(phase)
                    val harmonic = 0.3 * sin(phase * 2.0)
                    val sampleVal = ((fundamental + harmonic) / 1.3) * envelope * 0.88
                    samples[i] = (sampleVal * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }

                if (!isPlaying.get()) return@Thread

                val minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferBytes = (samples.size * 2).coerceAtLeast(minBuf)

                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                val format = AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()

                track = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                activeTrack = track
                track.write(samples, 0, samples.size)
                track.play()

                val startTime = System.currentTimeMillis()
                while (isPlaying.get() && System.currentTimeMillis() - startTime < durationMs) {
                    Thread.sleep(100)
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isPlaying.set(false)
                try {
                    track?.stop()
                    track?.release()
                } catch (_: Exception) {
                }
                if (activeTrack === track) {
                    activeTrack = null
                }
            }
        }
        activeThread = thread
        thread.start()
    }

    private fun boostAlarmStreamIfMuted(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val currentVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val targetMin = (maxVol * 0.75f).toInt().coerceAtLeast(1)
            if (currentVol < targetMin) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, targetMin, 0)
            }
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun triggerVibration(context: Context, pattern: LongArray) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                vibrator.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
        }
    }
}

