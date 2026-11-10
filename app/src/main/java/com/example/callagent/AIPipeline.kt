package com.example.callagent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.callagent.dialogue.DialogueManager
import com.example.callagent.dialogue.Reply
import com.example.callagent.speech.OfflineRecognizer
import com.example.callagent.speech.OfflineSpeaker
import com.example.callagent.speech.SpeechModel

/**
 * One conversation, fully on-device:
 *
 *   microphone -> Vosk STT -> intent classifier -> dialogue manager -> TTS -> loudspeaker
 *
 * All public methods and all callbacks run on the main thread.
 *
 * @param inCall   true for a real phone call, false for the in-app microphone test.
 * @param hangUp   ends the phone call (no-op for the microphone test).
 * @param onEvent  optional live feed of what is happening, for the UI.
 */
class AIPipeline(
    context: Context,
    private val inCall: Boolean,
    private val callerNumber: String?,
    private val hangUp: () -> Unit,
    private val onEvent: (String) -> Unit = {},
    private val onFinished: () -> Unit = {},
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var speaker: OfflineSpeaker? = null
    private var recognizer: OfflineRecognizer? = null
    private var dialogue: DialogueManager? = null

    private var stopped = false
    private var greetingDone = false
    private var wantsToListen = false

    fun start() {
        Log.i(TAG, "Pipeline starting (inCall=$inCall)")

        // 1. Brain: train/fetch the classifier off the main thread, then greet.
        Thread {
            val classifier = AgentBrain.classifier(context)
            main.post {
                if (stopped) return@post
                dialogue = DialogueManager(classifier, Settings.ownerName(context))
                maybeGreet()
            }
        }.start()

        // 2. Mouth.
        speaker = OfflineSpeaker(context, inCall) { ok ->
            main.post {
                if (stopped) return@post
                if (!ok) event("! Text-to-speech engine failed to start")
                speakerReady = true
                maybeGreet()
            }
        }

        // 3. Ears. The greeting does not wait for this - it can load while the agent talks.
        SpeechModel.load(context) { model ->
            if (stopped) return@load
            if (model == null) {
                event("! Speech model failed to load: ${SpeechModel.error}")
                return@load
            }
            recognizer = OfflineRecognizer(model, recognitionListener).also { it.start() }
            if (wantsToListen) listen()
        }
    }

    private var speakerReady = false

    private fun maybeGreet() {
        val manager = dialogue ?: return
        if (!speakerReady || greetingDone) return
        greetingDone = true
        deliver(manager.greeting())
    }

    private fun deliver(reply: Reply) {
        if (stopped) return
        recognizer?.setListening(false)
        wantsToListen = false
        event("Agent: ${reply.text}")
        val speak = speaker ?: return
        speak.speak(reply.text) {
            if (stopped) return@speak
            if (reply.endCall) finish(endCall = true)
            // Let the loudspeaker's tail die away before opening the microphone again.
            else main.postDelayed({ listen() }, 250)
        }
    }

    private fun listen() {
        if (stopped) return
        wantsToListen = true
        recognizer?.setListening(true)
    }

    private val recognitionListener = object : OfflineRecognizer.Listener {
        override fun onUtterance(text: String) {
            if (stopped) return
            val manager = dialogue ?: return
            val started = SystemClock.elapsedRealtimeNanos()
            val reply = manager.onCallerSaid(text)
            val micros = (SystemClock.elapsedRealtimeNanos() - started) / 1_000
            Log.d(TAG, "Understood \"$text\" in $micros us")
            event("Caller: $text")
            deliver(reply)
        }

        override fun onSilence() {
            if (stopped) return
            val manager = dialogue ?: return
            deliver(manager.onSilence())
        }

        override fun onError(message: String) {
            event("! $message")
            finish(endCall = false)
        }
    }

    /** Stops everything and stores the call summary. Safe to call more than once. */
    fun stop() = finish(endCall = false)

    private fun finish(endCall: Boolean) {
        if (stopped) return
        stopped = true
        main.removeCallbacksAndMessages(null)
        recognizer?.stop()
        speaker?.shutdown()
        dialogue?.let { manager ->
            val summary = manager.summary()
            if (inCall) CallLogStore.save(context, callerNumber, summary)
            event(CallLogStore.describe(callerNumber, summary))
        }
        if (endCall) {
            try { hangUp() } catch (e: Exception) { Log.w(TAG, "Hang up failed: ${e.message}") }
        }
        Log.i(TAG, "Pipeline finished")
        onFinished()
    }

    private fun event(message: String) {
        Log.d(TAG, message)
        onEvent(message)
    }

    private companion object {
        const val TAG = "AIPipeline"
    }
}
