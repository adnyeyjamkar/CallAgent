package com.example.callagent.nlu

/** One labelled training utterance. */
data class Example(val text: String, val intent: String)

/** Parser for `assets/intents.txt` (see that file for the format). */
object IntentDataset {
    fun parse(content: String): List<Example> {
        val examples = ArrayList<Example>()
        var intent: String? = null
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("##") -> intent = line.removePrefix("##").trim()
                line.startsWith("#") -> Unit
                intent != null -> examples.add(Example(line, intent))
            }
        }
        return examples
    }
}
