package com.neviswealth.search.worker

import com.neviswealth.search.config.SearchConfig
import com.neviswealth.search.db.DocumentRepository
import com.neviswealth.search.llm.LlmClient
import com.neviswealth.search.llm.LlmTransientException
import com.neviswealth.search.text.ContentChunker
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Background index worker: claims pending documents, embeds their chunks,
 * stores the vectors in pgvector and generates the content summary.
 * Transient LLM failures put the document back into the queue; repeated
 * hard failures mark it as failed with the last error recorded.
 */
@ApplicationScoped
class IndexWorker
    @Inject
    constructor(
        private val documents: DocumentRepository,
        private val llm: LlmClient,
        private val config: SearchConfig,
    ) {
        private val log = Logger.getLogger(IndexWorker::class.java.name)
        private val started = AtomicBoolean(false)

        @Scheduled(every = "{search.worker.interval}", concurrentExecution = ConcurrentExecution.SKIP)
        fun tick() {
            // First run after a restart: documents stuck in 'indexing' return to the queue.
            if (started.compareAndSet(false, true)) {
                runCatching { documents.resetStaleIndexing() }
                    .onFailure { log.warning("Could not reset stale 'indexing' documents: ${it.message}") }
            }
            try {
                pollOnce()
            } catch (e: Exception) {
                log.warning("Indexing poll failed: ${e.message}")
            }
        }

        /** One polling cycle; called by the scheduler and directly by tests. */
        fun pollOnce() {
            val worker = config.worker()
            val batch = documents.claimPendingBatch(worker.batchSize(), worker.maxAttempts())
            for (document in batch) {
                indexOne(document)
            }
            if (batch.isNotEmpty()) {
                log.info("Indexed a batch of ${batch.size} document(s)")
            }
        }

        private fun indexOne(document: DocumentRepository.DocumentRow) {
            val chunks =
                buildList {
                    // The title becomes chunk 0 so short, title-only documents stay searchable.
                    add(document.title)
                    addAll(ContentChunker.chunk(document.content))
                }
            val vectors =
                try {
                    val embedded = llm.embedDocuments(chunks)
                    if (embedded.size != chunks.size) {
                        throw LlmTransientException("Expected ${chunks.size} embeddings, got ${embedded.size}")
                    }
                    embedded
                } catch (e: LlmTransientException) {
                    documents.releaseClaim(document.id) // keep pending; connectivity issues are expected to heal
                    log.warning("Transient LLM failure for document ${document.id}: ${e.message}")
                    return
                } catch (e: Exception) {
                    documents.markFailed(document.id, e.message ?: e.javaClass.simpleName, config.worker().maxAttempts())
                    log.severe("Failed to index document ${document.id}: ${e.message}")
                    return
                }

            // Embeddings are the core feature: store them first so the document
            // becomes semantically searchable even if the optional summary fails.
            documents.completeIndexing(document.id, chunks.mapIndexed { i, text -> text to vectors[i] })

            try {
                documents.updateSummary(document.id, llm.summarize(document.title, document.content))
            } catch (e: Exception) {
                // The summary is a best-effort extra: keep the document searchable.
                documents.recordSummaryError(document.id, e.message ?: e.javaClass.simpleName)
                log.warning("Summary generation failed for document ${document.id}: ${e.message}")
            }
        }
    }
