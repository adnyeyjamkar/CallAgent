package com.example.callagent.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.vosk.Model
import org.vosk.android.StorageService

/**
 * Loads the bundled Vosk acoustic model once per process.
 *
 * The model ships inside the APK (`assets/model-en-in`). Native code cannot read
 * APK assets directly, so on first launch it is copied to app storage (a few
 * seconds, once); later launches only load it into memory.
 */
object SpeechModel {
    private const val TAG = "SpeechModel"
    private const val ASSET_DIR = "model-en-in"

    enum class Status { NOT_LOADED, LOADING, READY, FAILED }

    @Volatile
    var status = Status.NOT_LOADED
        private set

    @Volatile
    var error: String? = null
        private set

    private var model: Model? = null
    private val waiting = ArrayList<(Model?) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** [onReady] is always invoked on the main thread; with `null` if loading failed. */
    fun load(context: Context, onReady: (Model?) -> Unit = {}) {
        main.post {
            when (status) {
                Status.READY -> onReady(model)
                Status.LOADING -> waiting.add(onReady)
                Status.NOT_LOADED, Status.FAILED -> {
                    waiting.add(onReady)
                    status = Status.LOADING
                    StorageService.unpack(
                        context.applicationContext, ASSET_DIR, "model",
                        { loaded ->
                            model = loaded
                            status = Status.READY
                            Log.i(TAG, "Vosk model ready")
                            drain(loaded)
                        },
                        { exception ->
                            error = exception.message
                            status = Status.FAILED
                            Log.e(TAG, "Vosk model failed to load", exception)
                            drain(null)
                        },
                    )
                }
            }
        }
    }

    private fun drain(result: Model?) {
        val callbacks = ArrayList(waiting)
        waiting.clear()
        callbacks.forEach { it(result) }
    }
}
