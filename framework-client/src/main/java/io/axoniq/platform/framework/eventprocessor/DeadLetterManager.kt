/*
 * Copyright (c) 2022-2026. AxonIQ B.V.
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

package io.axoniq.platform.framework.eventprocessor

import io.axoniq.framework.messaging.deadletter.DeadLetter
import io.axoniq.framework.messaging.deadletter.SequencedDeadLetterProcessor
import io.axoniq.framework.messaging.deadletter.SequencedDeadLetterQueue
import io.axoniq.platform.framework.api.DeadLetterResponse
import io.axoniq.platform.framework.api.SequenceLettersResponse
import org.axonframework.common.configuration.Configuration
import org.axonframework.messaging.eventhandling.EventMessage
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import io.axoniq.platform.framework.api.DeadLetter as ApiDeadLetter

private const val LETTER_PAYLOAD_SIZE_LIMIT = 1024
private val logger = LoggerFactory.getLogger(DeadLetterManager::class.java)

/**
 * Inspects and operates on the dead-letter queues belonging to event handling components configured on this
 * application.
 *
 * In AF5 each event handling component within a Pooled Streaming processor may have its own dead-letter queue.
 * Queues are registered in the [Configuration] under names of the form
 * `DeadLetterQueue[EventHandlingComponent[<processor>][<component>]]`.
 *
 * To stay compatible with the platform's AF4-based DLQ API (which expects a single "processing group" identifier per
 * DLQ) this manager exposes each DLQ under a synthesised identifier:
 *  - if a processor has a single DLQ the identifier equals the processor name (matches the issue requirement);
 *  - if a processor has multiple DLQs each is exposed as `<processorName>::<componentName>` so they remain
 *    addressable individually.
 */
class DeadLetterManager(
        private val configuration: Configuration,
) : ProcessingGroupInfoSource {

    @Volatile
    private var entries: List<DlqEntry>? = null

    /**
     * Discovers the DLQs configured on this application by walking each event-processor module.
     * Called once via the lifecycle; subsequent invocations refresh the cached view.
     */
    fun start() {
        entries = discoverEntries()
    }

    override fun infoFor(processorName: String): List<ProcessingGroupInfoSource.ProcessingGroupInfo> =
            dlqInfoForProcessor(processorName).map {
                ProcessingGroupInfoSource.ProcessingGroupInfo(it.processingGroup, it.sequenceCount)
            }


    /**
     * Internal view of a discovered DLQ together with all metadata required to address it through the public API.
     */
    private data class DlqEntry(
            val processingGroup: String,
            val processorName: String,
            val componentName: String,
            val dlq: SequencedDeadLetterQueue<EventMessage>,
            val processor: SequencedDeadLetterProcessor<EventMessage>,
    )

    private val dlqNamePattern =
            Regex("""^DeadLetterQueue\[EventHandlingComponent\[([^]]+)]\[(.+)]]$""")

    fun deadLetters(
            processingGroup: String,
            offset: Int = 0,
            size: Int = 25,
            // Per-sequence cap intentionally small. The list query is meant to give the platform UI
            // a *page* of sequences with enough preview letters to seed the detail modal — not to
            // ship every letter every refresh. Mitchell observed 7-second refresh cycles on a local
            // DLQ when this defaulted to 1000 (page-size 25 sequences x up to 1000 letters each =
            // ~25k letter records serialised on every poll). 10 matches the historical "10+"
            // placeholder behaviour the platform UI already displays for capped sequences and keeps
            // the per-letter payload off the hot path. The detail modal pulls full pages lazily
            // through `sequenceLetters(...)` (FetchDeadLettersForSequence) so long sequences are
            // still browsable end-to-end without inflating the list query.
            maxSequenceLetters: Int = 10,
    ): DeadLetterResponse {
        val entry = dlqFor(processingGroup)
        val sequences = entry.dlq.deadLetters(null).join()
        val pageOfSequences = sequences
                .drop(offset)
                .take(size)
                .map { sequence ->
                    val letters = sequence.toList()
                    // The AF5 SequencedDeadLetterQueue does not expose the underlying sequence
                    // identifier (the Object passed to enqueue()) on a DeadLetter, so we synthesise
                    // a stable identifier from the first letter's message id and apply it to every
                    // letter in the sequence. Operations look up sequences by walking deadLetters()
                    // and matching this synthetic id — see findSequence(...).
                    val syntheticSequenceId = letters.firstOrNull()?.message()?.identifier() ?: ""
                    letters
                            .take(maxSequenceLetters)
                            .map { it.toApiLetter(syntheticSequenceId) }
                }
        val total = entry.dlq.amountOfSequences(null).join()
        return DeadLetterResponse(pageOfSequences, total)
    }

    fun sequenceSize(processingGroup: String, sequenceIdentifier: String): Long {
        val dlq = dlqFor(processingGroup).dlq
        return findSequence(dlq, sequenceIdentifier)?.count()?.toLong() ?: 0L
    }

    /**
     * Returns a paginated slice of letters belonging to the sequence identified by [sequenceIdentifier].
     * Used by the platform UI's detail modal so very long sequences can be browsed without loading
     * them all up-front through the [deadLetters] batch query.
     */
    fun lettersForSequence(
            processingGroup: String,
            sequenceIdentifier: String,
            offset: Int,
            size: Int,
    ): SequenceLettersResponse {
        val sequence = findSequence(dlqFor(processingGroup).dlq, sequenceIdentifier)
                ?: return SequenceLettersResponse(emptyList(), 0)
        val total = sequence.size.toLong()
        val safeOffset = offset.coerceAtLeast(0)
        val safeSize = size.coerceAtLeast(1)
        val slice = sequence
                .drop(safeOffset)
                .take(safeSize)
                .map { it.toApiLetter(sequenceIdentifier) }
        return SequenceLettersResponse(slice, total)
    }

    /**
     * Evicts every letter belonging to the sequence identified by [sequenceIdentifier].
     *
     * @return the number of letters that were actually evicted (0 when the synthetic id no longer
     *         resolves — e.g. the operator's view was stale).
     */
    fun delete(processingGroup: String, sequenceIdentifier: String): Int {
        val dlq = dlqFor(processingGroup).dlq
        val sequence = findSequence(dlq, sequenceIdentifier)
        if (sequence == null) {
            logger.warn(
                    "DLQ delete-sequence: no sequence in [{}] matches synthetic id [{}] — nothing to evict",
                    processingGroup, sequenceIdentifier,
            )
            return 0
        }
        logger.info(
                "DLQ delete-sequence: evicting {} letters from sequence [{}] in [{}]",
                sequence.size, sequenceIdentifier, processingGroup,
        )
        var evicted = 0
        sequence.forEach {
            dlq.evict(it, null).join()
            evicted++
        }
        return evicted
    }

    /**
     * Evicts a single letter identified by [messageIdentifier] from the sequence identified by
     * [sequenceIdentifier]. Returns `true` when an eviction was performed; `false` indicates the
     * synthetic id or message id no longer resolves (typically because the caller's view was stale).
     */
    fun delete(processingGroup: String, sequenceIdentifier: String, messageIdentifier: String): Boolean {
        val dlq = dlqFor(processingGroup).dlq
        val sequence = findSequence(dlq, sequenceIdentifier)
        if (sequence == null) {
            logger.warn(
                    "DLQ delete-letter: no sequence in [{}] matches synthetic id [{}] (message id was [{}]) — caller view likely stale",
                    processingGroup, sequenceIdentifier, messageIdentifier,
            )
            return false
        }
        val target = sequence.firstOrNull { it.message().identifier() == messageIdentifier }
        if (target == null) {
            logger.warn(
                    "DLQ delete-letter: sequence [{}] in [{}] (size={}) does not contain message id [{}] — already evicted?",
                    sequenceIdentifier, processingGroup, sequence.size, messageIdentifier,
            )
            return false
        }
        logger.info(
                "DLQ delete-letter: evicting message [{}] from sequence [{}] in [{}]",
                messageIdentifier, sequenceIdentifier, processingGroup,
        )
        dlq.evict(target, null).join()
        return true
    }

    /**
     * Resolves a DLQ sequence by the synthetic identifier this manager exposes through the API
     * (the message id of the sequence's first letter). Walks all sequences once and matches.
     */
    private fun findSequence(
            dlq: SequencedDeadLetterQueue<EventMessage>,
            syntheticSequenceId: String,
    ): List<DeadLetter<out EventMessage>>? {
        val sequences = dlq.deadLetters(null).join()
        for (sequence in sequences) {
            val letters = sequence.toList()
            if (letters.firstOrNull()?.message()?.identifier() == syntheticSequenceId) {
                return letters
            }
        }
        return null
    }

    fun process(processingGroup: String, messageIdentifier: String): Boolean {
        val processor = dlqFor(processingGroup).processor
        return processor.process { it.message().identifier() == messageIdentifier }
                .get(60, TimeUnit.SECONDS)
    }

    fun processAll(
            processingGroup: String,
            maxMessages: Int? = null,
            timeoutSeconds: Long = 600,
    ): Int {
        val processor = dlqFor(processingGroup).processor
        var processed = 0
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (maxMessages == null || processed < maxMessages) {
            if (System.nanoTime() > deadline) break
            val didProcess = processor.process { true }.get(timeoutSeconds, TimeUnit.SECONDS)
            if (!didProcess) break
            processed++
        }
        return processed
    }

    fun deleteAll(processingGroup: String, timeoutSeconds: Long = 600): Int {
        val dlq = dlqFor(processingGroup).dlq
        val totalCount = dlq.size(null).get(timeoutSeconds, TimeUnit.SECONDS).toInt()
        dlq.clear(null).get(timeoutSeconds, TimeUnit.SECONDS)
        return totalCount
    }

    /**
     * Returns the DLQ entries belonging to the given processor — used by [ProcessorReportCreator] to surface DLQ size
     * per processing group in the processor report.
     */
    fun dlqInfoForProcessor(processorName: String): List<DlqInfo> =
            discover()
                    .filter { it.processorName == processorName }
                    .map { DlqInfo(it.processingGroup, it.dlq.amountOfSequences(null).join()) }

    private fun dlqFor(processingGroup: String): DlqEntry =
            discover().firstOrNull { it.processingGroup == processingGroup }
                    ?: throw IllegalArgumentException(
                            "There is no dead-letter queue for processing group [$processingGroup]")

    @Suppress("UNCHECKED_CAST")
    private fun discoverEntries(): List<DlqEntry> {
        data class Parsed(
                val module: Configuration,
                val processor: String,
                val component: String,
                val dlq: SequencedDeadLetterQueue<EventMessage>,
        )

        val parsed = configuration.moduleConfigurations.flatMap { module ->
            module.getComponents(SequencedDeadLetterQueue::class.java)
                    .mapNotNull { (name, dlq) ->
                        val match = dlqNamePattern.find(name) ?: return@mapNotNull null
                        Parsed(
                                module = module,
                                processor = match.groupValues[1],
                                component = match.groupValues[2],
                                dlq = dlq as SequencedDeadLetterQueue<EventMessage>,
                        )
                    }
        }
        val perProcessor = parsed.groupingBy { it.processor }.eachCount()
        return parsed.map {
            val ehcName = "EventHandlingComponent[${it.processor}][${it.component}]"
            val processor = it.module
                    .getOptionalComponent(SequencedDeadLetterProcessor::class.java, ehcName)
                    .orElseThrow {
                        IllegalStateException(
                                "Component [$ehcName] is not wrapped with dead-letter processing")
                    } as SequencedDeadLetterProcessor<EventMessage>
            DlqEntry(
                    processingGroup = if (perProcessor[it.processor] == 1) it.processor else "${it.processor}::${it.component}",
                    processorName = it.processor,
                    componentName = it.component,
                    dlq = it.dlq,
                    processor = processor,
            )
        }
    }

    private fun discover(): List<DlqEntry> = entries ?: discoverEntries().also { entries = it }

    private fun DeadLetter<out EventMessage>.toApiLetter(sequenceIdentifier: String): ApiDeadLetter {
        val message = this.message()
        return ApiDeadLetter(
                messageIdentifier = message.identifier(),
                message = serializePayload(message),
                messageType = messageTypeOf(message),
                causeType = this.cause().map { it.type() }.orElse(null),
                causeMessage = this.cause().map { it.message() }.orElse(null),
                enqueuedAt = this.enqueuedAt(),
                lastTouched = this.lastTouched(),
                diagnostics = this.diagnostics(),
                sequenceIdentifier = sequenceIdentifier,
        )
    }

    /**
     * Best-effort human-readable type name for the payload. When the DLQ has the message in its
     * still-serialised form the JVM type is `byte[]`, which is useless to display, so we fall back
     * to the qualified name carried on the message's [org.axonframework.messaging.core.MessageType].
     */
    private fun messageTypeOf(message: EventMessage): String {
        val payloadClass = message.payloadType()
        if (payloadClass == ByteArray::class.java) {
            return runCatching { message.type().name() }.getOrDefault("byte[]")
        }
        return payloadClass.simpleName ?: payloadClass.name
    }

    private fun serializePayload(message: EventMessage): String {
        val raw: String = try {
            when (val payload = message.payload()) {
                null -> ""
                is ByteArray -> String(payload, Charsets.UTF_8)
                is String -> payload
                else -> payload.toString()
            }
        } catch (_: Exception) {
            ""
        }
        // UTF-8-safe truncation so multi-byte characters can't get split mid-codepoint.
        return raw.toByteArray(Charsets.UTF_8)
                .let { if (it.size <= LETTER_PAYLOAD_SIZE_LIMIT) raw else String(it, 0, LETTER_PAYLOAD_SIZE_LIMIT, Charsets.UTF_8) }
    }

    /**
     * Lightweight DTO returned to [ProcessorReportCreator] so it can populate per-processor DLQ size information
     * without exposing the full dead-letter API.
     */
    data class DlqInfo(val processingGroup: String, val sequenceCount: Long)
}
