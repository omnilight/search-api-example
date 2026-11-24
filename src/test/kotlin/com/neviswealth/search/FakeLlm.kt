package com.neviswealth.search

import com.neviswealth.search.domain.llm.LlmClient
import com.neviswealth.search.domain.llm.LlmHardException
import com.neviswealth.search.domain.llm.LlmTransientException
import io.quarkus.test.junit.QuarkusTestProfile
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import kotlin.math.sqrt

/**
 * Deterministic LLM stub for integration tests.
 *
 * Semantically related terms share one basis vector, so "address proof"
 * lands exactly on chunks about a "utility bill". Everything else gets a
 * per-text pseudo-random unit vector; two such vectors are near-orthogonal
 * (|cos| ~ 1/sqrt(384) ~ 0.05), which stays below the search threshold.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class FakeLlmClient : LlmClient {
    companion object {
        @JvmStatic var transientFailure: Boolean = false

        @JvmStatic var hardFailure: Boolean = false

        @JvmStatic var summaryFailure: Boolean = false

        /** Makes embedDocuments return wrong-size vectors to provoke a storage failure. */
        @JvmStatic var badVectorDimensions: Boolean = false

        /** When set, embedding any text containing this marker fails (hard). */
        @JvmStatic var poisonMarker: String? = null

        fun reset() {
            transientFailure = false
            hardFailure = false
            summaryFailure = false
            badVectorDimensions = false
            poisonMarker = null
        }

        fun vectorFor(text: String): FloatArray {
            val lower = text.lowercase()
            return when {
                "utility bill" in lower || "address proof" in lower || "proof of address" in lower -> vec(0)
                "passport" in lower -> vec(1)
                "bank statement" in lower || "income" in lower -> vec(2)
                else -> randomVec(lower)
            }
        }

        private fun vec(slot: Int): FloatArray = FloatArray(768) { if (it == slot) 1f else 0f }

        private fun randomVec(seed: String): FloatArray {
            val random = java.util.Random(seed.hashCode().toLong())
            val raw = FloatArray(768) { random.nextFloat() - 0.5f }
            val norm = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
            return FloatArray(768) { i -> raw[i] / norm }
        }
    }

    override fun embedQuery(text: String): FloatArray {
        if (transientFailure) throw LlmTransientException("fake transient failure")
        if (hardFailure) throw LlmHardException("fake hard failure")
        return vectorFor(text)
    }

    override fun embedDocuments(texts: List<String>): List<FloatArray> {
        if (transientFailure) throw LlmTransientException("fake transient failure")
        if (hardFailure) throw LlmHardException("fake hard failure")
        poisonMarker?.let { marker ->
            if (texts.any { it.contains(marker) }) throw LlmHardException("fake poison marker")
        }
        if (badVectorDimensions) return texts.map { FloatArray(3) { 0.1f } }
        return texts.map { vectorFor(it) }
    }

    override fun summarize(
        title: String,
        content: String,
    ): String {
        if (summaryFailure) throw LlmTransientException("fake summary failure")
        return "One-line summary of '$title'."
    }
}

/** Replaces the Ollama-backed LLM and freezes the scheduler; tests drive the worker by hand. */
class FakeLlmTestProfile : QuarkusTestProfile {
    override fun getEnabledAlternatives() = setOf(FakeLlmClient::class.java)

    override fun getConfigOverrides(): Map<String, String> =
        mapOf(
            "search.worker.interval" to "1h",
        )
}
