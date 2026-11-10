package com.example.callagent.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Streaming offline speech-to-text.
 *
 * A background thread pulls 16 kHz PCM from the microphone in 100 ms chunks and
 * feeds it straight into the Vosk decoder, which does its own end-of-utterance
 * detection. Because audio is decoded while the caller is still talking, the
 * final text is available a few hundred milliseconds after they stop.
 *
 * The recogniser is half-duplex: while the agent is speaking, [setListening]
 * is false and microphone audio is thrown away so the agent never hears itself.
 * It switches itself back to "not listening" after each utterance or silence timeout.
 */
class OfflineRecognizer(
    private val model: Model,
    private val listener: Listener,
    private val silenceTimeoutMs: Long = 8_000,
) {
    interface Listener {
        fun onPartial(text: String) {}
        fun onUtterance(text: String)
        fun onSilence()
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var listening = false
    @Volatile private var resetRequested = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "OfflineRecognizer").also { it.start() }
    }

    fun setListening(enabled: Boolean) {
        if (enabled) resetRequested = true
        listening = enabled
    }

    fun stop() {
        running = false
        listening = false
        thread = null
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested in MainActivity; failure is reported below.
    private fun openMicrophone(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = maxOf(minBuffer, SAMPLE_RATE * 2) // at least one second
        for (source in AUDIO_SOURCES) {
            try {
                val record = AudioRecord(
                    source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferBytes
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "Recording from audio source $source")
                    return record
                }
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "Audio source $source unavailable: ${e.message}")
            }
        }
        return null
    }

    private fun loop() {
        val record = openMicrophone()
        if (record == null) {
            main.post { listener.onError("Microphone unavailable (is the RECORD_AUDIO permission granted?)") }
            return
        }
        var recognizer: Recognizer? = null
        try {
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            record.startRecording()
            val buffer = ShortArray(SAMPLE_RATE / 10)
            var listenStarted = 0L
            var heardSpeech = false

            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) throw IllegalStateException("AudioRecord.read failed: $read")
                if (!listening || read == 0) continue

                if (resetRequested) {
                    resetRequested = false
                    recognizer.reset()
                    listenStarted = SystemClock.elapsedRealtime()
                    heardSpeech = false
                    continue // drop the chunk that straddles the agent's own speech
                }

                if (recognizer.acceptWaveForm(buffer, read)) {
                    val text = JSONObject(recognizer.result).optString("text").trim()
                    if (text.isNotEmpty() && text !in NOISE_WORDS) {
                        listening = false
                        main.post { listener.onUtterance(text) }
                    }
                } else {
                    val partial = JSONObject(recognizer.partialResult).optString("partial").trim()
                    if (partial.isNotEmpty() && partial !in NOISE_WORDS) {
                        heardSpeech = true
                        main.post { listener.onPartial(partial) }
                    }
                }

                if (listening && !heardSpeech &&
                    SystemClock.elapsedRealtime() - listenStarted > silenceTimeoutMs
                ) {
                    listening = false
                    main.post { listener.onSilence() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recognition loop failed", e)
            if (running) main.post { listener.onError(e.message ?: "Recognition failed") }
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            recognizer?.close()
        }
    }

    private companion object {
        const val TAG = "OfflineRecognizer"
        const val SAMPLE_RATE = 16_000

        /** Tried in order; the first one the device lets us open wins. */
        val AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )

        /** Things the small model hallucinates from line noise. */
        val NOISE_WORDS = setOf("the", "huh", "a", "uh", "um", "hm")
    }
}
