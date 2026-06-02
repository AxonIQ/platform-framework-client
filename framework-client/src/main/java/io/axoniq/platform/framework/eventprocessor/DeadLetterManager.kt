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
import io.axoniq.platform.framework.api.AxoniqConsoleDlqMode
import io.axoniq.platform.framework.api.DeadLetterResponse
import io.axoniq.platform.framework.api.SequenceLettersResponse
import org.apache.commons.codec.digest.DigestUtils
import org.axonframework.common.configuration.Configuration
import org.axonframework.messaging.core.EmptyApplicationContext
import org.axonframework.messaging.core.Metadata
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory
import org.axonframework.messaging.eventhandling.EventHandlingComponent
import org.axonframework.messaging.eventhandling.EventMessage
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import io.axoniq.platform.framework.api.DeadLetter as ApiDeadLetter

private const val LETTER_PAYLOAD_SIZE_LIMIT = 1024
private const val MASKED = "<MASKED>"
private const val LIMITED = "<LIMITED>"
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
 *
 * Sequence identifiers exposed through the API come from the [EventHandlingComponent]'s configured sequencing
 * policy (via [EventHandlingComponent.sequenceIdentifierFor]), matching AF4 semantics. This makes sequence ids
 * stable across letter eviction — deleting the first letter no longer renames the sequence as it did under the
 * earlier "first letter's message id" synthetic scheme.
 */
class DeadLetterManager @JvmOverloads constructor(
        private val configuration: Configuration,
        private val dlqMode: AxoniqConsoleDlqMode = AxoniqConsoleDlqMode.NONE,
        private val dlqDiagnosticsWhitelist: List<String> = emptyList(),
) : ProcessingGroupInfoSource {

    @Volatile
    private var entries: List<DlqEntry>? = null

    /**
     * Factory used to materialise a real [org.axonframework.messaging.core.unitofwork.UnitOfWork] when the
     * manager needs to call [EventHandlingComponent.sequenceIdentifierFor] on a dead letter — that call
     * requires a non-null [ProcessingContext] because some decorator layers (notably
     * `SequenceCachingEventHandlingComponent`) store per-event resources on the context.
     *
     * An [EmptyApplicationContext] is used because the stock sequencing-policy chain (constant,
     * property, metadata, hierarchical, fallback) does not look up application components; the context
     * is only consulted as a resource bag. If a custom policy ever needs richer context resolution the
     * wiring can be revisited.
     */
    private val unitOfWorkFactory: UnitOfWorkFactory =
            SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE)

    /**
     * Discovers the DLQs configured on this application by walking each event-processor module.
     * Called once via the lifecycle; subsequent invocations refresh the cached view.
     *
     * Logs the active [dlqMode] at INFO so operators can confirm from application logs that the
     * configured exposure level (`axoniq.platform.dlq-mode`) has actually taken effect. We stay at
     * INFO regardless of mode because some users alert on WARN-and-above and an expected
     * configuration choice shouldn't trip those alerts.
     */
    fun start() {
        entries = discoverEntries()
        when (dlqMode) {
            AxoniqConsoleDlqMode.FULL -> logger.info(
                    "Axoniq Platform DLQ inspection initialised in FULL mode — payloads, causes and diagnostics are exposed verbatim.")
            AxoniqConsoleDlqMode.LIMITED -> logger.info(
                    "Axoniq Platform DLQ inspection initialised in LIMITED mode — payloads are hidden; diagnostics are filtered through whitelist {} (empty whitelist removes all diagnostic entries).",
                    dlqDiagnosticsWhitelist)
            AxoniqConsoleDlqMode.MASKED -> logger.info(
                    "Axoniq Platform DLQ inspection initialised in MASKED mode — sequence ids are SHA-256 hashed; payloads, causes and diagnostics are not exposed. Operator delete/process actions still work via the hashed identifier.")
            AxoniqConsoleDlqMode.NONE -> logger.info(
                    "Axoniq Platform DLQ inspection initialised in NONE mode — only sequence counts are exposed. List queries return empty results regardless of letter contents.")
        }
    }

    override fun infoFor(processorName: String): List<ProcessingGroupInfoSource.ProcessingGroupInfo> =
            dlqInfoForProcessor(processorName).map {
                ProcessingGroupInfoSource.ProcessingGroupInfo(it.processingGroup, it.sequenceCount)
            }


    /**
     * Internal view of a discovered DLQ together with all metadata required to address it through the public API.
     *
     * The [eventHandlingComponent] reference is captured during discovery so the sequence identifier of every
     * letter can be derived from the same [EventHandlingComponent.sequenceIdentifierFor] the framework uses on
     * enqueue. May be `null` if the component cannot be resolved from the configuration — in that case the
     * manager falls back to the letter's own message id (documented in [sequenceIdentifierFor]).
     */
    private data class DlqEntry(
            val processingGroup: String,
            val processorName: String,
            val componentName: String,
            val dlq: SequencedDeadLetterQueue<EventMessage>,
            val processor: SequencedDeadLetterProcessor<EventMessage>,
            val eventHandlingComponent: EventHandlingComponent?,
    )

    private val dlqNamePattern =
            Regex("""^DeadLetterQueue\[EventHandlingComponent\[([^]]+)]\[(.+)]]$""")

    fun deadLetters(
            processingGroup: String,
            offset: Int = 0,
            size: Int = 25,
            // Capped at 10 to keep poll payloads small; long sequences are browsed via sequenceLetters(...).
            maxSequenceLetters: Int = 10,
    ): DeadLetterResponse {
        val entry = dlqFor(processingGroup)
        if (dlqMode == AxoniqConsoleDlqMode.NONE) {
            return DeadLetterResponse(emptyList(), entry.dlq.amountOfSequences(null).join())
        }
        val sequences = entry.dlq.deadLetters(null).join()
        val pageOfSequences = sequences
                .drop(offset)
                .take(size)
                .map { sequence ->
                    val letters = sequence.toList()
                    val rawSequenceId = letters.firstOrNull()?.let { sequenceIdentifierFor(entry, it) } ?: ""
                    val apiSequenceId = if (dlqMode == AxoniqConsoleDlqMode.MASKED) rawSequenceId.hashed() else rawSequenceId
                    letters
                            .take(maxSequenceLetters)
                            .map { it.toApiLetter(apiSequenceId) }
                }
        val total = entry.dlq.amountOfSequences(null).join()
        return DeadLetterResponse(pageOfSequences, total)
    }

    fun sequenceSize(processingGroup: String, sequenceIdentifier: String): Long {
        val entry = dlqFor(processingGroup)
        return findSequence(entry, sequenceIdentifier)?.count()?.toLong() ?: 0L
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
        val entry = dlqFor(processingGroup)
        if (dlqMode == AxoniqConsoleDlqMode.NONE) {
            return SequenceLettersResponse(emptyList(), 0)
        }
        val sequence = findSequence(entry, sequenceIdentifier)
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
     * @return the number of letters that were actually evicted (0 when the id no longer resolves — e.g. the
     *         operator's view was stale).
     */
    fun delete(processingGroup: String, sequenceIdentifier: String): Int {
        val entry = dlqFor(processingGroup)
        val sequence = findSequence(entry, sequenceIdentifier)
        if (sequence == null) {
            logger.warn(
                    "DLQ delete-sequence: no sequence in [{}] matches id [{}] — nothing to evict",
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
            entry.dlq.evict(it, null).join()
            evicted++
        }
        return evicted
    }

    /**
     * Evicts a single letter identified by [messageIdentifier] from the sequence identified by
     * [sequenceIdentifier]. Returns `true` when an eviction was performed; `false` indicates the
     * sequence id or message id no longer resolves (typically because the caller's view was stale).
     */
    fun delete(processingGroup: String, sequenceIdentifier: String, messageIdentifier: String): Boolean {
        val entry = dlqFor(processingGroup)
        val sequence = findSequence(entry, sequenceIdentifier)
        if (sequence == null) {
            logger.warn(
                    "DLQ delete-letter: no sequence in [{}] matches id [{}] (message id was [{}]) — caller view likely stale",
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
        entry.dlq.evict(target, null).join()
        return true
    }

    /**
     * Resolves a DLQ sequence by the identifier this manager exposes through the API. Walks every sequence
     * in the queue, derives each sequence's identifier via [sequenceIdentifierFor], and matches.
     *
     * When [dlqMode] is [AxoniqConsoleDlqMode.MASKED] the API-side identifier is a SHA-256 hash, so the
     * lookup compares the hash of each candidate id against the supplied [sequenceIdentifier] — this keeps
     * the delete/process operations working even when the operator only sees masked ids.
     */
    private fun findSequence(
            entry: DlqEntry,
            sequenceIdentifier: String,
    ): List<DeadLetter<out EventMessage>>? {
        val sequences = entry.dlq.deadLetters(null).join()
        // Track candidates so we can log a diagnostic when nothing matches — the most common cause
        // is a stale identifier (the sequence's first letter has changed) or a mode mismatch between
        // the value the UI cached and what the manager now computes. Capped to keep log noise low.
        val candidates = mutableListOf<String>()
        for (sequence in sequences) {
            val letters = sequence.toList()
            val firstLetter = letters.firstOrNull() ?: continue
            val rawId = sequenceIdentifierFor(entry, firstLetter)
            val candidateId = if (dlqMode == AxoniqConsoleDlqMode.MASKED) rawId.hashed() else rawId
            if (candidateId == sequenceIdentifier) {
                return letters
            }
            if (candidates.size < 5) candidates.add(candidateId)
        }
        logger.warn(
                "DLQ findSequence: no sequence in [{}] matches id [{}] (dlqMode={}, scanned {} sequence(s), first {} candidate id(s): {}). Operator's view may be stale, or the first letter of the sequence has changed since the list query.",
                entry.processingGroup,
                sequenceIdentifier,
                dlqMode,
                candidates.size,
                candidates.size,
                candidates,
        )
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
                    .getOptionalComponent(EventHandlingComponent::class.java, ehcName)
                    .map { ehc ->
                        if(ehc is AxoniqPlatformEventHandlingComponent) {
                            ehc.delegate as? SequencedDeadLetterProcessor<*>
                        } else null
                    }
                    .orElseThrow {
                        IllegalStateException(
                                "Component [$ehcName] is not wrapped with dead-letter processing")
                    } as SequencedDeadLetterProcessor<EventMessage>
            // The EHC is needed for sequence-identifier resolution. Looking it up here (once per discovery
            // run) keeps the hot path on deadLetters/lettersForSequence cheap and matches the "discover once"
            // shape of the rest of this manager.
            val ehc = it.module
                    .getOptionalComponent(EventHandlingComponent::class.java, ehcName)
                    .orElse(null)
            DlqEntry(
                    processingGroup = if (perProcessor[it.processor] == 1) it.processor else "${it.processor}::${it.component}",
                    processorName = it.processor,
                    componentName = it.component,
                    dlq = it.dlq,
                    processor = processor,
                    eventHandlingComponent = ehc,
            )
        }
    }

    private fun discover(): List<DlqEntry> = entries ?: discoverEntries().also { entries = it }

    /**
     * Resolves the sequence identifier for a letter by spinning up a real
     * [org.axonframework.messaging.core.unitofwork.UnitOfWork] and calling
     * [EventHandlingComponent.sequenceIdentifierFor] with its [ProcessingContext]. The UoW gives the
     * decorator chain (including `SequenceCachingEventHandlingComponent`) a non-null context to read
     * resources from, matching the framework's own invariants and avoiding the NPE that calling with
     * `null` would trigger. The UoW does no real work — the lambda completes synchronously on the
     * direct executor, so there's no scheduling cost. Result shape mirrors the AF4 implementation:
     *  - String results are used verbatim;
     *  - non-String results fall back to `hashCode().toString()`;
     *  - if the EHC reference could not be captured at discovery time, or sequence resolution throws
     *    or returns `null`, the letter's message identifier is used so each letter still has a
     *    unique id.
     */
    private fun sequenceIdentifierFor(
            entry: DlqEntry,
            letter: DeadLetter<out EventMessage>,
    ): String {
        val message = letter.message()
        val ehc = entry.eventHandlingComponent ?: return message.identifier()
        val raw: Any? = try {
            unitOfWorkFactory.create().executeWithResult { context: ProcessingContext ->
                CompletableFuture.completedFuture<Any?>(ehc.sequenceIdentifierFor(message, context))
            }.join()
        } catch (ex: Exception) {
            logger.debug(
                    "Sequence identifier resolution threw for message [{}] in [{}] — falling back to message id.",
                    message.identifier(), entry.processingGroup, ex,
            )
            null
        }
        return when (raw) {
            null -> message.identifier()
            is String -> raw
            else -> raw.hashCode().toString()
        }
    }

    private fun DeadLetter<out EventMessage>.toApiLetter(sequenceIdentifier: String): ApiDeadLetter {
        val message = this.message()
        // `sequenceIdentifier` is expected to be in its final API form (hashed in MASKED, raw in FULL/
        // LIMITED). Hashing is the caller's responsibility — see `deadLetters(...)`.
        return when (dlqMode) {
            AxoniqConsoleDlqMode.NONE -> error(
                    "DLQ in NONE mode must not serialise letters — short-circuit in deadLetters/lettersForSequence was bypassed.")
            AxoniqConsoleDlqMode.MASKED -> ApiDeadLetter(
                    messageIdentifier = message.identifier(),
                    message = MASKED,
                    messageType = messageTypeOf(message),
                    causeType = this.cause().map { it.type() }.orElse(null),
                    causeMessage = this.cause().map { MASKED }.orElse(null),
                    enqueuedAt = this.enqueuedAt(),
                    lastTouched = this.lastTouched(),
                    diagnostics = emptyMap<String, Any>(),
                    sequenceIdentifier = sequenceIdentifier,
            )
            AxoniqConsoleDlqMode.LIMITED -> ApiDeadLetter(
                    messageIdentifier = message.identifier(),
                    message = LIMITED,
                    messageType = messageTypeOf(message),
                    causeType = this.cause().map { it.type() }.orElse(null),
                    causeMessage = this.cause().map { LIMITED }.orElse(null),
                    enqueuedAt = this.enqueuedAt(),
                    lastTouched = this.lastTouched(),
                    diagnostics = this.diagnostics().filteredByWhitelist(),
                    sequenceIdentifier = sequenceIdentifier,
            )
            AxoniqConsoleDlqMode.FULL -> ApiDeadLetter(
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
    }

    /**
     * Best-effort human-readable type name for the message. In AF5 the qualified name carried on the
     * message's [org.axonframework.messaging.core.MessageType] is the primary type identifier and is
     * always set; the payload class is only a fallback for the unlikely case the type lookup throws.
     */
    private fun messageTypeOf(message: EventMessage): String =
            runCatching { message.type().name() }.getOrDefault(message.payloadType().name)

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
     * Applies the whitelist filter used in LIMITED mode. Returns only entries whose key is in the
     * configured whitelist; an empty whitelist removes all diagnostics.
     */
    private fun Metadata.filteredByWhitelist(): Map<String, *> =
            if (dlqDiagnosticsWhitelist.isEmpty()) emptyMap<String, Any>()
            else subset(*dlqDiagnosticsWhitelist.toTypedArray())

    private fun String.hashed(): String = DigestUtils.sha256Hex(this)

    /**
     * Lightweight DTO returned to [ProcessorReportCreator] so it can populate per-processor DLQ size information
     * without exposing the full dead-letter API.
     */
    data class DlqInfo(val processingGroup: String, val sequenceCount: Long)
}
