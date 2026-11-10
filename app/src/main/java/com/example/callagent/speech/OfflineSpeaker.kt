package com.example.callagent.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Text-to-speech using the engine already installed on the phone, pinned to a
 * voice that does not need the network.
 *
 * During a call the audio is tagged as voice-communication so it follows the
 * call's audio route (the loudspeaker), where the phone's microphone picks it up
 * and carries it to the caller.
 */
class OfflineSpeaker(context: Context, private val inCall: Boolean, onReady: (Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = HashMap<String, () -> Unit>()
    private var counter = 0
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) configure()
            onReady(ok)
        }
    }

    private fun configure() {
        val engine = tts ?: return
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (inCall) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        val indian = Locale("en", "IN")
        val available = engine.isLanguageAvailable(indian) >= TextToSpeech.LANG_AVAILABLE
        engine.language = if (available) indian else Locale.US

        // Prefer an installed voice that works without a connection.
        try {
            val offline = engine.voices.orEmpty().filter {
                it.locale.language == "en" && !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }
            val voice = offline.firstOrNull { it.locale.country == "IN" }
                ?: offline.firstOrNull { it.locale.country == "US" }
                ?: offline.firstOrNull()
            if (voice != null) engine.voice = voice
            Log.i(TAG, "TTS voice: ${voice?.name ?: "engine default"}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not enumerate voices: ${e.message}")
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
        })
    }

    private fun finished(utteranceId: String?) {
        main.post { callbacks.remove(utteranceId)?.invoke() }
    }

    /** Speaks [text]; [onDone] runs on the main thread when playback has finished. */
    fun speak(text: String, onDone: () -> Unit) {
        val engine = tts
        val id = "utterance-${counter++}"
        callbacks[id] = onDone
        if (engine == null || engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            finished(id)
        }
    }

    fun shutdown() {
        callbacks.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private companion object {
        const val TAG = "OfflineSpeaker"
    }
}
