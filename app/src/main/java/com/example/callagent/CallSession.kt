package com.example.callagent

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Guarantees that only one conversation runs at a time, whichever component
 * (in-call service, accessibility service or the in-app test) started it.
 * Safe to call from any thread; the pipeline itself always runs on the main thread.
 */
object CallSession {
    private val main = Handler(Looper.getMainLooper())
    private var pipeline: AIPipeline? = null

    val isActive: Boolean
        @Synchronized get() = pipeline != null

    /** @return false if a conversation is already in progress. */
    @Synchronized
    fun start(
        context: Context,
        inCall: Boolean,
        callerNumber: String?,
        hangUp: () -> Unit,
        onEvent: (String) -> Unit = {},
        onFinished: () -> Unit = {},
    ): Boolean {
        if (pipeline != null) return false
        lateinit var created: AIPipeline
        created = AIPipeline(context, inCall, callerNumber, hangUp, onEvent) {
            synchronized(this) { if (pipeline === created) pipeline = null }
            onFinished()
        }
        pipeline = created
        main.post { created.start() }
        return true
    }

    @Synchronized
    fun stop() {
        val current = pipeline ?: return
        pipeline = null
        main.post { current.stop() }
    }
}
