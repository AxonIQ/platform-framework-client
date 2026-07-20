/*
 * Copyright (c) 2022-2025. AxonIQ B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.axoniq.platform.framework.api

import java.time.Instant

data class DeadLetter(
    val messageIdentifier: String,
    val message: String,
    val messageType: String,
    val causeType: String?,
    val causeMessage: String?,
    val enqueuedAt: Instant,
    val lastTouched: Instant,
    val diagnostics: Map<String, *>,
    val sequenceIdentifier: String
)

data class DeadLetterResponse(
    val sequences: List<List<DeadLetter>>,
    val totalCount: Long = -1
)

data class DeadLetterRequest(
    val processingGroup: String,
    val offset: Int,
    val size: Int,
    val maxSequenceLetters: Int,
)

data class DeadLetterSequenceSize(
    val processingGroup: String,
    val sequenceIdentifier: String
)

data class DeadLetterSequenceDeleteRequest(
    val processingGroup: String,
    val sequenceIdentifier: String,
)

data class DeadLetterSingleDeleteRequest(
    val processingGroup: String,
    val sequenceIdentifier: String,
    val messageIdentifier: String,
)

data class ProcessAllDeadLetterSequencesRequest(
    val processingGroup: String,
    val maxMessages: Int = 10
)

data class DeleteAllDeadLetterSequencesRequest(
        val processingGroup: String
)

data class DeadLetterProcessRequest(
    val processingGroup: String,
    val messageIdentifier: String
)

/**
 * Request paginated letters belonging to a single sequence inside the DLQ. Used by the platform UI
 * detail modal to browse long sequences without loading them all up-front.
 *
 * @param processingGroup     The processing group / DLQ identifier.
 * @param sequenceIdentifier  Synthetic sequence id as previously returned by [DeadLetter.sequenceIdentifier].
 * @param offset              Zero-based offset into the sequence.
 * @param size                Number of letters to return (capped server-side).
 */
data class FetchSequenceLettersRequest(
    val processingGroup: String,
    val sequenceIdentifier: String,
    val offset: Int,
    val size: Int,
)

/**
 * Response payload for [FetchSequenceLettersRequest]. Carries the requested slice of letters along
 * with the total number of letters in the sequence so the UI can render full pagination.
 */
data class SequenceLettersResponse(
    val letters: List<DeadLetter>,
    val totalCount: Long = letters.size.toLong(),
)

/**
 * Requests the sequences of a DLQ that are due for a platform-driven automatic retry. The platform
 * owns the retry settings (they live on the event-processor configuration) and passes them along;
 * the client filters locally so exhausted or still-backing-off sequences never leave the
 * application.
 *
 * A sequence is due when its head letter has fewer than [maxRetries] recorded automated attempts
 * (the `__platform_retries` diagnostic) and its `lastTouched` is at least the computed backoff ago.
 *
 * @param processingGroup The processing group / DLQ identifier.
 * @param backoff         The backoff algorithm to apply between attempts.
 * @param backoffMinutes  Base wait in minutes between attempts.
 * @param maxRetries      Maximum number of automated attempts per head letter.
 * @param limit           Maximum number of candidates to return.
 */
data class DeadLettersForAutomaticRetryRequest(
    val processingGroup: String,
    val backoff: AutomaticRetryBackoff,
    val backoffMinutes: Int,
    val maxRetries: Int,
    val limit: Int,
)

enum class AutomaticRetryBackoff {
    /** Fixed wait of `backoffMinutes` between every attempt. */
    CONSISTENT,

    /** Wait doubles each attempt: `backoffMinutes * 2^retries`. */
    EXPONENTIAL,
}

data class AutomaticRetryCandidatesResponse(
    val candidates: List<AutomaticRetryCandidate>,
)

/**
 * A sequence head that is due for an automated retry. Carries identifiers only — no payload,
 * cause, or diagnostics — so the platform can trigger the retry without the letter contents ever
 * leaving the application.
 */
data class AutomaticRetryCandidate(
    val sequenceIdentifier: String,
    val messageIdentifier: String,
    val retries: Int,
    val lastTouched: Instant,
)

/**
 * Processes the dead-letter sequence whose head letter matches [messageIdentifier], as an
 * automated (platform-driven) retry. Unlike the manual [DeadLetterProcessRequest], a failed
 * attempt increments the head letter's `__platform_retries` diagnostic so it counts against the
 * configured max-retry budget.
 */
data class AutomatedRetryRequest(
    val processingGroup: String,
    val messageIdentifier: String,
)
