package com.example.callagent

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Used when CallAgent is NOT the default phone app.
 *
 * Two jobs:
 *  1. Having an enabled accessibility service is what lets an ordinary app keep
 *     recording the microphone while a phone call is in progress (Android 10+).
 *  2. It notices the incoming-call screen, answers after a short delay, switches
 *     the call to loudspeaker and starts the agent.
 *
 * Window events only *wake* this service; the actual decisions are made from the
 * telephony call state, so outgoing calls and ordinary dialer use are ignored.
 */
class AccessibilityCallService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var watching = false
    private var ringingSince = 0L
    private var answerAttempts = 0
    private var agentStarted = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (watching || event == null || !Settings.isEnabled(this)) return
        if (holdsDialerRole()) return // CallHandlerService owns the call in that case.
        if (callState() == TelephonyManager.CALL_STATE_RINGING) {
            watching = true
            ringingSince = SystemClock.elapsedRealtime()
            answerAttempts = 0
            agentStarted = false
            Log.d(TAG, "Incoming call detected")
            handler.post(watch)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (agentStarted) CallSession.stop()
        super.onDestroy()
    }

    /** Polls the call state twice a second from ring to hang-up. */
    private val watch = object : Runnable {
        override fun run() {
            when (callState()) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    val waited = SystemClock.elapsedRealtime() - ringingSince
                    if (waited >= Settings.RING_DELAY_MS && Settings.isEnabled(this@AccessibilityCallService)) {
                        answer()
                    }
                }
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    // Only take over calls this service answered itself.
                    if (answerAttempts > 0 && !agentStarted) {
                        agentStarted = true
                        handler.postDelayed({ enableSpeaker(); startAgent() }, 600)
                    } else if (agentStarted) {
                        enableSpeaker()
                    }
                }
                else -> { // idle: the call is over
                    if (agentStarted) {
                        CallSession.stop()
                        releaseSpeaker()
                    }
                    watching = false
                    return
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    @SuppressLint("MissingPermission") // checked by hasPermission(); SecurityException is caught
    private fun answer() {
        answerAttempts++
        // First choice: the official API. Fallback: tap the answer button on screen.
        if (answerAttempts == 1 && hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) {
            try {
                @Suppress("DEPRECATION")
                getSystemService(TelecomManager::class.java).acceptRingingCall()
                Log.i(TAG, "Answered through TelecomManager")
                return
            } catch (e: Exception) {
                Log.w(TAG, "acceptRingingCall failed: ${e.message}")
            }
        }
        if (clickNode(ANSWER_KEYWORDS, ANSWER_FORBIDDEN)) Log.i(TAG, "Answered by tapping the answer button")
    }

    private fun startAgent() {
        if (callState() != TelephonyManager.CALL_STATE_OFFHOOK) return
        CallSession.start(this, inCall = true, callerNumber = null, hangUp = ::endCall)
    }

    @SuppressLint("MissingPermission") // checked by hasPermission(); SecurityException is caught
    private fun endCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) {
            try {
                @Suppress("DEPRECATION")
                if (getSystemService(TelecomManager::class.java).endCall()) return
            } catch (e: Exception) {
                Log.w(TAG, "endCall failed: ${e.message}")
            }
        }
        clickNode(END_KEYWORDS, emptyList())
    }

    /** The caller can only hear the agent, and the agent the caller, through the loudspeaker. */
    private fun enableSpeaker() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (isSpeakerOn(audio)) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audio.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { audio.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not switch to speaker through AudioManager: ${e.message}")
        }
        // Many phones ignore the request above for cellular calls; tap the on-screen button instead.
        if (!isSpeakerOn(audio)) clickNode(SPEAKER_KEYWORDS, emptyList())
    }

    private fun releaseSpeaker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).clearCommunicationDevice()
        }
    }

    private fun isSpeakerOn(audio: AudioManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn
        }

    // --- helpers -------------------------------------------------------------------------

    private fun clickNode(keywords: List<String>, forbidden: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        val clickable = ArrayList<AccessibilityNodeInfo>()
        collectClickable(root, clickable)
        for (node in clickable) {
            val text = node.text?.toString()?.lowercase().orEmpty()
            val description = node.contentDescription?.toString()?.lowercase().orEmpty()
            val id = node.viewIdResourceName?.lowercase().orEmpty()
            val label = "$text|$description|$id"
            if (forbidden.any { it in label }) continue
            if (keywords.any { it in label }) {
                Log.d(TAG, "Clicking node text='$text' desc='$description' id='$id'")
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        return false
    }

    private fun collectClickable(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isClickable) out.add(node)
        for (i in 0 until node.childCount) collectClickable(node.getChild(i), out)
    }

    private fun callState(): Int = try {
        @Suppress("DEPRECATION")
        getSystemService(TelephonyManager::class.java).callState
    } catch (e: SecurityException) {
        TelephonyManager.CALL_STATE_IDLE // READ_PHONE_STATE not granted yet
    }

    private fun holdsDialerRole(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)

    private fun hasPermission(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "AccessibilityCallSvc"
        val ANSWER_KEYWORDS = listOf("answer", "accept", "pickup", "pick up")
        val ANSWER_FORBIDDEN = listOf("decline", "reject", "hang up", "message", "dial")
        val SPEAKER_KEYWORDS = listOf("speaker")
        val END_KEYWORDS = listOf("end call", "hang up", "end_call", "endcall", "disconnect")
    }
}
