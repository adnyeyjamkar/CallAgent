package com.example.callagent

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.callagent.nlu.IntentClassifier
import com.example.callagent.nlu.IntentDataset

/**
 * Holds the trained intent classifier for the whole process.
 * The model is trained once from `assets/intents.txt` and then kept in memory.
 */
object AgentBrain {
    private const val TAG = "AgentBrain"

    @Volatile
    private var classifier: IntentClassifier? = null

    /** Blocking on first use (a fraction of a second) - call from a background thread. */
    @Synchronized
    fun classifier(context: Context): IntentClassifier {
        classifier?.let { return it }
        val started = SystemClock.elapsedRealtime()
        val data = context.assets.open("intents.txt").bufferedReader().use { it.readText() }
        val examples = IntentDataset.parse(data)
        val trained = IntentClassifier.train(examples)
        Log.i(TAG, "Trained on ${examples.size} examples, ${trained.labels.size} intents in " +
            "${SystemClock.elapsedRealtime() - started} ms")
        classifier = trained
        return trained
    }
}
