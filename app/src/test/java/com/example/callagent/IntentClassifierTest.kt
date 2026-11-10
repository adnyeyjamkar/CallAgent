package com.example.callagent

import com.example.callagent.nlu.Example
import com.example.callagent.nlu.IntentClassifier
import com.example.callagent.nlu.IntentDataset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/** Trains the real model from the shipped training file and checks it on unseen phrasings. */
class IntentClassifierTest {

    companion object {
        val examples: List<Example> by lazy {
            IntentDataset.parse(File("src/main/assets/intents.txt").readText())
        }
        val classifier: IntentClassifier by lazy { IntentClassifier.train(examples) }
    }

    @Test
    fun fitsTrainingData() {
        val correct = examples.count { classifier.predict(it.text).intent == it.intent }
        val accuracy = correct.toDouble() / examples.size
        println("Training accuracy: %.3f on %d examples".format(accuracy, examples.size))
        assertTrue("training accuracy $accuracy", accuracy > 0.97)
    }

    /** 5-fold cross-validation: every utterance is predicted by a model that never saw it. */
    @Test
    fun generalisesInCrossValidation() {
        val shuffled = examples.shuffled(Random(7))
        val folds = 5
        var correct = 0
        for (fold in 0 until folds) {
            val train = shuffled.filterIndexed { i, _ -> i % folds != fold }
            val test = shuffled.filterIndexed { i, _ -> i % folds == fold }
            val model = IntentClassifier.train(train)
            correct += test.count { model.predict(it.text).intent == it.intent }
        }
        val accuracy = correct.toDouble() / shuffled.size
        println("5-fold cross-validation accuracy: %.3f".format(accuracy))
        assertTrue("cross-validation accuracy $accuracy", accuracy > 0.75)
    }

    @Test
    fun recognisesUnseenPhrasings() {
        val unseen = mapOf(
            "hello good evening sir" to "greeting",
            "could i please talk to him" to "ask_for_owner",
            "please tell him to call me back in the evening" to "callback_request",
            "this is really urgent please" to "urgent",
            "am i speaking to a machine" to "who_are_you",
            "we are offering you a pre approved personal loan" to "sales_spam",
            "sir your parcel has arrived i am at the gate" to "delivery",
            "sorry i dialed a wrong number" to "wrong_number",
            "sorry could you repeat that please" to "repeat",
            "okay thanks a lot" to "thanks",
            "no that is all thank you" to "deny",
            "okay bye i will call later" to "goodbye",
            "i am calling about the meeting schedule for next week" to "inform",
        )
        val wrong = unseen.filter { (text, intent) -> classifier.predict(text).intent != intent }
        assertTrue("misclassified: " + wrong.map { "${it.key} -> ${classifier.predict(it.key)}" }, wrong.isEmpty())
    }

    @Test
    fun toleratesSpeechRecognitionErrors() {
        // Mis-heard words still share character n-grams with the real ones.
        assertEquals("urgent", classifier.predict("it is urgently").intent)
        assertEquals("delivery", classifier.predict("your couriers has arrive").intent)
    }

    @Test
    fun unknownWordsGiveZeroConfidence() {
        assertEquals(0f, classifier.predict("zzzz qqqq").confidence, 0f)
    }

    @Test
    fun inferenceIsFast() {
        repeat(200) { classifier.predict("please ask him to call me back it is urgent") } // warm up
        val runs = 2000
        val started = System.nanoTime()
        repeat(runs) { classifier.predict("please ask him to call me back it is urgent") }
        val micros = (System.nanoTime() - started) / 1000.0 / runs
        println("Inference: %.1f microseconds per utterance".format(micros))
        assertTrue("inference took $micros us", micros < 5_000)
    }
}
