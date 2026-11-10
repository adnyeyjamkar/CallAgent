package com.example.callagent.nlu

import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Result of classifying one utterance. */
data class Prediction(val intent: String, val confidence: Float) {
    companion object {
        const val UNKNOWN = "unknown"
    }
}

/**
 * Multinomial logistic regression (softmax) over TF-IDF weighted n-gram features.
 *
 * Everything - vocabulary building, IDF, SGD training and inference - is plain
 * Kotlin with no native code and no network, so it runs identically on the phone
 * and in JVM unit tests. Inference is a sparse dot product: microseconds per call.
 */
class IntentClassifier private constructor(
    val labels: List<String>,
    private val vocabulary: Map<String, Int>,
    private val idf: FloatArray,
    private val weights: Array<FloatArray>, // [label][feature]
    private val bias: FloatArray,
) {
    private class SparseVector(val indices: IntArray, val values: FloatArray)

    fun predict(text: String): Prediction {
        val vector = vectorize(TextFeatures.extract(text), vocabulary, idf)
        if (vector.indices.isEmpty()) return Prediction(Prediction.UNKNOWN, 0f)
        val probabilities = softmax(logits(vector, weights, bias))
        var best = 0
        for (i in probabilities.indices) if (probabilities[i] > probabilities[best]) best = i
        return Prediction(labels[best], probabilities[best])
    }

    companion object {
        /**
         * Trains a classifier from labelled examples.
         * Deterministic for a given [seed], so results are reproducible.
         */
        fun train(
            examples: List<Example>,
            epochs: Int = 120,
            learningRate: Float = 0.5f,
            l2: Float = 1e-4f,
            seed: Long = 42L,
        ): IntentClassifier {
            require(examples.isNotEmpty()) { "No training examples" }
            val labels = examples.map { it.intent }.distinct().sorted()
            val labelIndex = labels.withIndex().associate { it.value to it.index }
            val featureLists = examples.map { TextFeatures.extract(it.text) }

            // Vocabulary + document frequencies.
            val vocabulary = HashMap<String, Int>()
            val documentFrequency = ArrayList<Int>()
            for (features in featureLists) {
                for (feature in features.toSet()) {
                    val id = vocabulary.getOrPut(feature) {
                        documentFrequency.add(0)
                        documentFrequency.size - 1
                    }
                    documentFrequency[id] = documentFrequency[id] + 1
                }
            }
            val n = examples.size
            val idf = FloatArray(vocabulary.size) { ln((1f + n) / (1f + documentFrequency[it])) + 1f }

            val vectors = featureLists.map { vectorize(it, vocabulary, idf) }
            val targets = IntArray(n) { labelIndex.getValue(examples[it].intent) }
            val weights = Array(labels.size) { FloatArray(vocabulary.size) }
            val bias = FloatArray(labels.size)

            // Stochastic gradient descent on the cross-entropy loss.
            val random = Random(seed)
            val order = IntArray(n) { it }
            for (epoch in 0 until epochs) {
                for (i in n - 1 downTo 1) { // Fisher-Yates shuffle
                    val j = random.nextInt(i + 1)
                    val tmp = order[i]; order[i] = order[j]; order[j] = tmp
                }
                val rate = learningRate / (1f + 0.02f * epoch)
                for (sample in order) {
                    val vector = vectors[sample]
                    val probabilities = softmax(logits(vector, weights, bias))
                    for (label in labels.indices) {
                        val gradient = probabilities[label] - if (label == targets[sample]) 1f else 0f
                        val row = weights[label]
                        for (k in vector.indices.indices) {
                            val feature = vector.indices[k]
                            row[feature] -= rate * (gradient * vector.values[k] + l2 * row[feature])
                        }
                        bias[label] -= rate * gradient
                    }
                }
            }
            return IntentClassifier(labels, vocabulary, idf, weights, bias)
        }

        /** Sub-linear TF x IDF, L2-normalised, as a sparse vector. Unknown features are dropped. */
        private fun vectorize(
            features: List<String>,
            vocabulary: Map<String, Int>,
            idf: FloatArray,
        ): SparseVector {
            val counts = HashMap<Int, Int>()
            for (feature in features) {
                val id = vocabulary[feature] ?: continue
                counts[id] = (counts[id] ?: 0) + 1
            }
            val indices = IntArray(counts.size)
            val values = FloatArray(counts.size)
            var norm = 0f
            var k = 0
            for ((id, count) in counts) {
                val value = (1f + ln(count.toFloat())) * idf[id]
                indices[k] = id
                values[k] = value
                norm += value * value
                k++
            }
            norm = sqrt(norm)
            if (norm > 0f) for (i in values.indices) values[i] /= norm
            return SparseVector(indices, values)
        }

        private fun logits(vector: SparseVector, weights: Array<FloatArray>, bias: FloatArray): FloatArray {
            val out = FloatArray(bias.size)
            for (label in out.indices) {
                var sum = bias[label]
                val row = weights[label]
                for (k in vector.indices.indices) sum += row[vector.indices[k]] * vector.values[k]
                out[label] = sum
            }
            return out
        }

        private fun softmax(logits: FloatArray): FloatArray {
            val max = logits.max()
            var total = 0f
            val out = FloatArray(logits.size)
            for (i in logits.indices) {
                out[i] = exp(logits[i] - max)
                total += out[i]
            }
            for (i in out.indices) out[i] /= total
            return out
        }
    }
}
