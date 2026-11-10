package com.example.callagent.dialogue

import com.example.callagent.nlu.EntityExtractor
import com.example.callagent.nlu.IntentClassifier
import com.example.callagent.nlu.Prediction
import com.example.callagent.nlu.TextFeatures

/** What the agent says next, and whether the call should be hung up afterwards. */
data class Reply(val text: String, val endCall: Boolean = false)

/** One line of the conversation. */
data class Turn(val speaker: String, val text: String)

/** Everything the agent learned during one call. */
data class CallSummary(
    val callerName: String?,
    val message: String,
    val urgent: Boolean,
    val callbackRequested: Boolean,
    val callbackNumber: String?,
    val category: String,
    val transcript: List<Turn>,
)

/**
 * Slot-filling dialogue state machine: a receptionist that finds out who is
 * calling and why, then takes a message.
 *
 *   ASK_NAME -> ASK_PURPOSE -> (ASK_NUMBER) -> ANYTHING_ELSE -> ENDED
 *
 * The intent classifier decides how each caller utterance is routed; some
 * intents (spam, wrong number, goodbye, "repeat that") are handled in any state.
 * Pure Kotlin - no Android dependencies - so it is fully unit-testable.
 */
class DialogueManager(
    private val classifier: IntentClassifier,
    private val ownerName: String,
    private val confidenceThreshold: Float = 0.45f,
    private val maxCallerTurns: Int = 14,
) {
    enum class State { ASK_NAME, ASK_PURPOSE, ASK_NUMBER, ANYTHING_ELSE, ENDED }

    var state = State.ASK_NAME
        private set

    private val transcript = ArrayList<Turn>()
    private val messageParts = ArrayList<String>()
    private var callerName: String? = null
    private var callbackNumber: String? = null
    private var urgent = false
    private var callbackRequested = false
    private var category = "personal"
    private var nameAttempts = 0
    private var numberAttempts = 0
    private var silences = 0
    private var callerTurns = 0
    private var lastReply = ""

    fun greeting(): Reply = say(
        "Hello, this is $ownerName's assistant. $ownerName can't take your call right now. " +
            "May I know who is calling?"
    )

    /** Called when the caller stayed silent after the agent finished speaking. */
    fun onSilence(): Reply {
        if (state == State.ENDED) return Reply("", endCall = true)
        silences++
        return if (silences >= 2) {
            end("I can't hear anything, so I will hang up now. I'll let $ownerName know you called. Goodbye.")
        } else {
            say("Are you still there? " + promptFor(state))
        }
    }

    fun onCallerSaid(text: String): Reply {
        if (state == State.ENDED) return Reply("", endCall = true)
        transcript.add(Turn("caller", text))
        silences = 0
        callerTurns++

        val prediction = classifier.predict(text)
        val intent = if (prediction.confidence >= confidenceThreshold) prediction.intent else Prediction.UNKNOWN
        val tokenCount = TextFeatures.tokenize(text).size

        // Slots that can be picked up at any point in the call.
        val nameNow = if (callerName == null) EntityExtractor.extractName(text) else null
        if (nameNow != null) callerName = nameNow
        val numberNow = EntityExtractor.extractPhoneNumber(text)
        if (numberNow != null) callbackNumber = numberNow

        // Intents handled the same way in every state.
        when (intent) {
            "repeat" -> return say(lastReply)
            "wrong_number" -> {
                category = "wrong number"
                return end("No problem at all. Goodbye.")
            }
            "sales_spam" -> {
                category = "spam"
                messageParts.add(text)
                return end("Thank you, but $ownerName is not interested. Please remove this number from your list. Goodbye.")
            }
            "goodbye" -> return end(farewell())
            "who_are_you" -> return say(
                "I am an automated assistant running on $ownerName's phone. I can take a message. " + promptFor(state)
            )
            "delivery" -> {
                category = "delivery"
                messageParts.add(text)
                state = State.ANYTHING_ELSE
                return say(
                    "$ownerName can't come to the phone right now. Please leave the package at the door or with security. " +
                        "I'll let them know. Is there anything else?"
                )
            }
            "ask_for_owner" -> return say("Sorry, $ownerName is not available right now. " + promptFor(state))
            "greeting" -> return say("Hello! " + promptFor(state))
        }

        if (callerTurns >= maxCallerTurns) return end(farewell())

        var acknowledgement = ""
        if (intent == "urgent") {
            urgent = true
            acknowledgement = "I understand this is urgent, I'll flag it for $ownerName. "
        }
        if (intent == "callback_request") callbackRequested = true

        // Does this utterance carry content worth writing down?
        val isControl = intent in CONTROL_INTENTS
        // "can i leave a message" announces a message, it is not the message itself.
        val isAnnouncement = tokenCount <= 8 && MESSAGE_ANNOUNCEMENT.containsMatchIn(text.lowercase())
        val isContent = !isControl && !isAnnouncement && tokenCount >= 3 &&
            !(intent == "give_name" && nameNow != null && tokenCount <= 5)

        return when (state) {
            State.ASK_NAME -> {
                if (callerName == null && !isControl && !isContent) {
                    callerName = EntityExtractor.asBareName(text)
                }
                if (isContent) messageParts.add(text)
                when {
                    callerName != null || nameAttempts >= 1 -> {
                        val thanks = callerName?.let { "Thanks $it. " } ?: ""
                        if (messageParts.isNotEmpty()) afterMessage(acknowledgement + thanks)
                        else {
                            state = State.ASK_PURPOSE
                            say(acknowledgement + thanks + promptFor(state))
                        }
                    }
                    else -> {
                        nameAttempts++
                        val noted = if (isContent) "I've noted that. " else ""
                        say(acknowledgement + noted + "May I have your name, please?")
                    }
                }
            }

            State.ASK_PURPOSE -> when {
                intent == "deny" -> end(farewell())
                intent == "affirm" || intent == "thanks" -> say("Please go ahead, I'm listening.")
                isAnnouncement -> say("Of course. Please tell me the message.")
                else -> {
                    messageParts.add(text)
                    afterMessage(acknowledgement)
                }
            }

            State.ASK_NUMBER -> when {
                numberNow != null -> {
                    state = State.ANYTHING_ELSE
                    say("Got it, ${spellDigits(numberNow)}. Is there anything else?")
                }
                intent == "deny" || text.contains("same number") || text.contains("this number") -> {
                    callbackNumber = callbackNumber ?: "the number they called from"
                    state = State.ANYTHING_ELSE
                    say("Okay, I'll ask $ownerName to call back on this number. Is there anything else?")
                }
                numberAttempts >= 1 -> {
                    state = State.ANYTHING_ELSE
                    say("I couldn't catch the number, so I'll ask $ownerName to call back on this number. Is there anything else?")
                }
                else -> {
                    numberAttempts++
                    say("Sorry, I didn't catch that. Please say the phone number digit by digit.")
                }
            }

            State.ANYTHING_ELSE -> when {
                intent == "deny" || intent == "thanks" -> end(farewell())
                intent == "affirm" -> {
                    state = State.ASK_PURPOSE
                    say("Please go ahead, I'm listening.")
                }
                else -> {
                    if (isContent || intent == Prediction.UNKNOWN) messageParts.add(text)
                    afterMessage(acknowledgement)
                }
            }

            State.ENDED -> Reply("", endCall = true)
        }
    }

    fun summary() = CallSummary(
        callerName = callerName,
        message = messageParts.joinToString(". "),
        urgent = urgent,
        callbackRequested = callbackRequested,
        callbackNumber = callbackNumber,
        category = category,
        transcript = transcript.toList(),
    )

    private fun afterMessage(prefix: String): Reply {
        return if (callbackRequested && callbackNumber == null && numberAttempts == 0 && state != State.ASK_NUMBER) {
            state = State.ASK_NUMBER
            say(prefix + "Sure, I'll ask $ownerName to call you back. " + promptFor(state))
        } else {
            state = State.ANYTHING_ELSE
            say(prefix + "I've noted that for $ownerName. Is there anything else?")
        }
    }

    private fun promptFor(state: State): String = when (state) {
        State.ASK_NAME -> "May I know who is calling?"
        State.ASK_PURPOSE -> "What would you like me to tell $ownerName?"
        State.ASK_NUMBER -> "Which number should they call? Please say it digit by digit, or say same number."
        State.ANYTHING_ELSE -> "Is there anything else you'd like me to pass on?"
        State.ENDED -> ""
    }

    private fun farewell(): String {
        val name = callerName?.let { " $it" } ?: ""
        return if (messageParts.isEmpty()) "Okay$name. I'll let $ownerName know you called. Goodbye."
        else "Thank you$name. I'll pass your message on to $ownerName. Goodbye."
    }

    private fun spellDigits(number: String) = number.toCharArray().joinToString(" ")

    private fun say(text: String): Reply {
        lastReply = text
        transcript.add(Turn("agent", text))
        return Reply(text)
    }

    private fun end(text: String): Reply {
        state = State.ENDED
        transcript.add(Turn("agent", text))
        return Reply(text, endCall = true)
    }

    private companion object {
        val MESSAGE_ANNOUNCEMENT = Regex("\\b(leave|take|have|pass|give|convey|note)\\b.*\\bmessage\\b")
        val CONTROL_INTENTS = setOf("affirm", "deny", "thanks", "repeat", "greeting", "goodbye", "who_are_you")
    }
}
