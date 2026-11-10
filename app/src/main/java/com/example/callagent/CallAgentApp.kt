package com.example.callagent

import android.app.Application
import com.example.callagent.speech.SpeechModel

class CallAgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Warm both models as soon as the process exists, so nothing has to load
        // when the phone actually rings.
        SpeechModel.load(this)
        Thread { AgentBrain.classifier(this) }.start()
    }
}
