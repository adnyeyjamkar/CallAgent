package com.example.callagent

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log

/**
 * Used when CallAgent is the default phone app. This is the most reliable path:
 * Telecom hands us the call object, so we can answer, switch to loudspeaker and
 * hang up through official APIs.
 */
class CallHandlerService : InCallService() {
    private val handler = Handler(Looper.getMainLooper())
    private var agentCall: Call? = null

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (state == Call.STATE_ACTIVE && call === agentCall) startAgent(call)
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        if (!Settings.isEnabled(this) || stateOf(call) != Call.STATE_RINGING) return
        Log.d(TAG, "Incoming call, answering in ${Settings.RING_DELAY_MS} ms unless picked up")

        handler.postDelayed({
            if (stateOf(call) == Call.STATE_RINGING && !CallSession.isActive) {
                Log.i(TAG, "Answering call")
                agentCall = call
                call.registerCallback(callback)
                call.answer(0)
            }
        }, Settings.RING_DELAY_MS)
    }

    private fun startAgent(call: Call) {
        setAudioRoute(CallAudioState.ROUTE_SPEAKER)
        val number = call.details?.handle?.schemeSpecificPart
        CallSession.start(this, inCall = true, callerNumber = number, hangUp = { call.disconnect() })
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (call === agentCall) {
            call.unregisterCallback(callback)
            agentCall = null
            CallSession.stop()
        }
    }

    @Suppress("DEPRECATION") // Call.getState() is the only option below API 31.
    private fun stateOf(call: Call) = call.state

    private companion object {
        const val TAG = "CallHandlerService"
    }
}
