package com.neviswealth.search.entrypoint.worker

import com.neviswealth.search.domain.document.indexing.IndexPendingDocuments
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.logging.Logger

/** Scheduled trigger for the indexing scenario; the work itself lives in the domain. */
@ApplicationScoped
class DocumentIndexWorker
    @Inject
    constructor(
        private val indexPendingDocuments: IndexPendingDocuments,
    ) {
        private val log = Logger.getLogger(DocumentIndexWorker::class.java.name)

        @Scheduled(every = "{search.worker.interval}", concurrentExecution = ConcurrentExecution.SKIP)
        fun tick() {
            try {
                indexPendingDocuments.pollOnce()
            } catch (e: Exception) {
                log.warning("Indexing poll failed: ${e.message}")
            }
        }
    }
