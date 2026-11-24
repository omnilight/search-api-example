package com.neviswealth.search.domain.document.indexing

import com.neviswealth.search.domain.document.Document
import java.util.UUID

interface DocumentIndexStorage {
    /** Claims pending documents; skipped by parallel workers via SKIP LOCKED. */
    fun claimPendingBatch(
        batchSize: Int,
        maxAttempts: Int,
    ): List<Document>

    /** Stores the chunk embeddings and marks the document searchable, atomically. */
    fun completeIndexing(
        documentId: UUID,
        chunks: List<Pair<String, FloatArray>>,
    )

    /** Best-effort summary; the document is already searchable. */
    fun updateSummary(
        documentId: UUID,
        summary: String,
    )

    fun recordSummaryError(
        documentId: UUID,
        error: String,
    )

    /** Back to 'pending': transient LLM failures are expected to heal. */
    fun releaseClaim(documentId: UUID)

    /** Counts a hard failure; the document ends up failed after [maxAttempts]. */
    fun markFailed(
        documentId: UUID,
        error: String,
        maxAttempts: Int,
    )

    /** Recovery after a crash: documents stuck in 'indexing' go back to the queue. */
    fun resetStaleIndexing()
}
