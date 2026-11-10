package com.example.callagent.nlu

/**
 * Turns raw recogniser text into a bag of string features.
 *
 * Three feature families are used so the classifier stays robust to the kind of
 * mistakes a small offline speech model makes:
 *  - `w:` word unigrams          ("w:urgent")
 *  - `b:` word bigrams           ("b:call_back")
 *  - `c:` character trigrams     ("c:urg", "c:rge" ...) - these still fire when a
 *         word is slightly mis-recognised ("urgent" -> "urgently", "courier" -> "courie").
 */
object TextFeatures {
    private val WORD = Regex("[a-z0-9']+")

    fun tokenize(text: String): List<String> =
        WORD.findAll(text.lowercase()).map { it.value }.toList()

    fun extract(text: String): List<String> {
        val tokens = tokenize(text)
        val features = ArrayList<String>(tokens.size * 6)
        for (token in tokens) features.add("w:$token")
        for (i in 0 until tokens.size - 1) features.add("b:${tokens[i]}_${tokens[i + 1]}")
        for (token in tokens) {
            val padded = "<$token>"
            for (i in 0..padded.length - 3) features.add("c:" + padded.substring(i, i + 3))
        }
        return features
    }
}
