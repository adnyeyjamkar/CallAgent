package com.example.callagent.nlu

/** Rule-based extraction of the two slots the agent needs: caller name and callback number. */
object EntityExtractor {

    private val NAME_PREFIX = Regex(
        "\\b(?:my name is|my name's|name is|this is|i am|i'm|it is|it's|myself|tell him it is|tell him it's)" +
            " ((?:doctor |mister |miss )?[a-z]+(?: [a-z]+)?)"
    )
    private val NAME_SUFFIX = Regex("\\b([a-z]+) (?:speaking|here|this side|calling)\\b")
    private val NAME_CALLED = Regex("\\btell (?:him|her) ([a-z]+) called\\b")

    /** Words that follow "this is / i am" but are never a name. */
    private val NOT_A_NAME = setOf(
        "a", "an", "the", "his", "her", "your", "my", "calling", "from", "looking", "trying", "not",
        "sorry", "just", "in", "at", "on", "so", "good", "fine", "going", "speaking", "very", "also",
        "about", "regarding", "urgent", "important", "here", "outside", "downstairs", "standing",
        "waiting", "interested", "sure", "done", "that", "this", "it", "he", "she", "is", "am",
        "really", "to", "for", "with", "and", "who", "what", "when", "i", "you", "him", "there",
        "okay", "ok", "yes", "no", "hello", "hi", "still", "back", "busy", "free", "glad", "afraid",
        "wondering", "hoping", "able", "unable", "now", "again", "an emergency", "emergency",
        "extremely", "already", "currently", "actually", "only", "one", "anyone", "someone",
    )

    fun extractName(text: String): String? {
        val lower = text.lowercase()
        val candidate = NAME_CALLED.find(lower)?.groupValues?.get(1)
            ?: NAME_PREFIX.find(lower)?.groupValues?.get(1)
            ?: NAME_SUFFIX.find(lower)?.groupValues?.get(1)
            ?: return null
        return cleanName(candidate)
    }

    /** Accepts a bare short reply ("rahul", "rahul sharma") as a name. */
    fun asBareName(text: String): String? {
        val tokens = TextFeatures.tokenize(text)
        if (tokens.isEmpty() || tokens.size > 3) return null
        if (tokens.any { it.any(Char::isDigit) }) return null
        return cleanName(tokens.joinToString(" "))
    }

    private fun cleanName(candidate: String): String? {
        val words = candidate.split(' ').filter { it.isNotBlank() }.toMutableList()
        if (words.isEmpty() || words.first() in NOT_A_NAME) return null
        while (words.size > 1 && words.last() in NOT_A_NAME) words.removeAt(words.size - 1)
        if (words.any { it.length < 2 }) return null
        return words.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }

    private val DIGIT_WORDS = mapOf(
        "zero" to "0", "oh" to "0", "o" to "0", "one" to "1", "two" to "2", "three" to "3",
        "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
    )

    /**
     * Finds a phone number spoken as words ("nine eight double two ...") or typed as digits.
     * Returns the digit string when at least 7 digits are found in a row.
     */
    fun extractPhoneNumber(text: String): String? {
        val tokens = TextFeatures.tokenize(text)
        var best = ""
        val current = StringBuilder()
        var repeat = 1
        fun flush() {
            if (current.length > best.length) best = current.toString()
            current.setLength(0)
            repeat = 1
        }
        for (token in tokens) {
            val digit = DIGIT_WORDS[token]
            when {
                token == "double" -> repeat = 2
                token == "triple" -> repeat = 3
                digit != null -> {
                    repeat(repeat) { current.append(digit) }
                    repeat = 1
                }
                token.all(Char::isDigit) -> {
                    current.append(token)
                    repeat = 1
                }
                else -> flush()
            }
        }
        flush()
        return best.takeIf { it.length >= 7 }
    }
}
