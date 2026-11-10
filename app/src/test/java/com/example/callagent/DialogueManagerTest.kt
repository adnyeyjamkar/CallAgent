package com.example.callagent

import com.example.callagent.dialogue.DialogueManager
import com.example.callagent.dialogue.DialogueManager.State
import com.example.callagent.nlu.EntityExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whole conversations, driven with text exactly as the speech recogniser would produce it. */
class DialogueManagerTest {

    private fun newDialogue() = DialogueManager(IntentClassifierTest.classifier, "Sam")

    @Test
    fun takesAMessage() {
        val dialogue = newDialogue()
        assertTrue(dialogue.greeting().text.contains("Sam"))

        val afterName = dialogue.onCallerSaid("hi my name is rahul")
        assertTrue(afterName.text, afterName.text.contains("Rahul"))
        assertEquals(State.ASK_PURPOSE, dialogue.state)

        dialogue.onCallerSaid("i wanted to discuss the project report with him")
        assertEquals(State.ANYTHING_ELSE, dialogue.state)

        val end = dialogue.onCallerSaid("no that is all")
        assertTrue(end.endCall)

        val summary = dialogue.summary()
        assertEquals("Rahul", summary.callerName)
        assertTrue(summary.message.contains("project report"))
        assertFalse(summary.urgent)
        assertEquals("personal", summary.category)
    }

    @Test
    fun urgentCallbackCollectsNumber() {
        val dialogue = newDialogue()
        dialogue.greeting()
        dialogue.onCallerSaid("this is priya")
        dialogue.onCallerSaid("it is very urgent")
        val askNumber = dialogue.onCallerSaid("please ask him to call me back")
        assertEquals(askNumber.text, State.ASK_NUMBER, dialogue.state)
        dialogue.onCallerSaid("nine eight double two zero one two three four five")
        assertEquals(State.ANYTHING_ELSE, dialogue.state)
        assertTrue(dialogue.onCallerSaid("no thanks").endCall)

        val summary = dialogue.summary()
        assertEquals("Priya", summary.callerName)
        assertTrue(summary.urgent)
        assertTrue(summary.callbackRequested)
        assertEquals("9822012345", summary.callbackNumber)
    }

    @Test
    fun spamIsEndedImmediately() {
        val dialogue = newDialogue()
        dialogue.greeting()
        val reply = dialogue.onCallerSaid("we have a special offer on a pre approved personal loan for you")
        assertTrue(reply.endCall)
        assertEquals("spam", dialogue.summary().category)
    }

    @Test
    fun wrongNumberIsEndedImmediately() {
        val dialogue = newDialogue()
        dialogue.greeting()
        assertTrue(dialogue.onCallerSaid("oh sorry wrong number").endCall)
    }

    @Test
    fun deliveryGetsInstructions() {
        val dialogue = newDialogue()
        dialogue.greeting()
        val reply = dialogue.onCallerSaid("i am at your gate with the delivery")
        assertTrue(reply.text, reply.text.contains("package"))
        assertEquals("delivery", dialogue.summary().category)
    }

    @Test
    fun repeatsOnRequest() {
        val dialogue = newDialogue()
        val greeting = dialogue.greeting()
        assertEquals(greeting.text, dialogue.onCallerSaid("sorry can you repeat that").text)
    }

    @Test
    fun explainsItIsAnAssistant() {
        val dialogue = newDialogue()
        dialogue.greeting()
        val reply = dialogue.onCallerSaid("am i talking to a robot")
        assertTrue(reply.text, reply.text.contains("automated assistant"))
        assertEquals(State.ASK_NAME, dialogue.state)
    }

    @Test
    fun messageBeforeNameIsKept() {
        val dialogue = newDialogue()
        dialogue.greeting()
        dialogue.onCallerSaid("i am calling about the house rent payment")
        assertEquals(State.ASK_NAME, dialogue.state)
        dialogue.onCallerSaid("suresh")
        assertEquals(State.ANYTHING_ELSE, dialogue.state)
        val summary = dialogue.summary()
        assertEquals("Suresh", summary.callerName)
        assertTrue(summary.message.contains("house rent"))
    }

    @Test
    fun hangsUpAfterRepeatedSilence() {
        val dialogue = newDialogue()
        dialogue.greeting()
        assertFalse(dialogue.onSilence().endCall)
        assertTrue(dialogue.onSilence().endCall)
    }

    @Test
    fun extractsNames() {
        assertEquals("Rahul", EntityExtractor.extractName("hello my name is rahul"))
        assertEquals("Anjali Verma", EntityExtractor.extractName("this is anjali verma"))
        assertEquals("Rohan", EntityExtractor.extractName("rohan here"))
        assertEquals("Amit", EntityExtractor.extractName("i am amit from the office"))
        assertNull(EntityExtractor.extractName("i am calling from the bank"))
        assertNull(EntityExtractor.extractName("it is urgent"))
    }

    @Test
    fun extractsSpokenPhoneNumbers() {
        assertEquals("9822012345", EntityExtractor.extractPhoneNumber("it is nine eight double two zero one two three four five"))
        assertEquals("9876543210", EntityExtractor.extractPhoneNumber("call 9876543210 please"))
        assertNull(EntityExtractor.extractPhoneNumber("call me at five"))
    }
}
