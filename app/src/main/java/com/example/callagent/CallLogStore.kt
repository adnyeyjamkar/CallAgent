package com.example.callagent

import android.content.Context
import android.util.Log
import com.example.callagent.dialogue.CallSummary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stores a summary of every answered call in a private file on the device (JSON lines). */
object CallLogStore {
    private const val TAG = "CallLogStore"
    private const val FILE = "call_log.jsonl"

    @Synchronized
    fun save(context: Context, callerNumber: String?, summary: CallSummary) {
        try {
            val json = JSONObject()
                .put("time", System.currentTimeMillis())
                .put("number", callerNumber ?: "")
                .put("summary", describe(callerNumber, summary))
                .put("transcript", JSONArray(summary.transcript.map { "${it.speaker}: ${it.text}" }))
            File(context.filesDir, FILE).appendText(json.toString() + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Could not save call summary", e)
        }
    }

    /** Most recent first. */
    @Synchronized
    fun recent(context: Context, limit: Int = 20): List<String> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        val format = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
        return file.readLines().takeLast(limit).reversed().mapNotNull { line ->
            try {
                val json = JSONObject(line)
                "${format.format(Date(json.getLong("time")))}\n${json.getString("summary")}"
            } catch (e: Exception) {
                null
            }
        }
    }

    @Synchronized
    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    fun describe(callerNumber: String?, summary: CallSummary): String = buildString {
        if (summary.urgent) append("URGENT - ")
        append(summary.callerName ?: "Unknown caller")
        if (!callerNumber.isNullOrBlank()) append(" ($callerNumber)")
        append(" [${summary.category}]")
        append("\nMessage: ").append(summary.message.ifBlank { "(no message left)" })
        if (summary.callbackRequested) {
            append("\nWants a call back")
            summary.callbackNumber?.let { append(" on $it") }
        }
    }
}
