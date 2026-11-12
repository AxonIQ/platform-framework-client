/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.eventprocessor

import io.axoniq.framework.messaging.deadletter.DeadLetter
import io.axoniq.framework.messaging.deadletter.SequencedDeadLetterProcessor
import io.axoniq.framework.messaging.deadletter.SequencedDeadLetterQueue
import io.axoniq.platform.framework.api.AutomaticRetryBackoff
import io.axoniq.platform.framework.api.AutomaticRetryCandidate
import io.axoniq.platform.framework.api.AutomaticRetryCandidatesResponse
import io.axoniq.platform.framework.api.AxoniqConsoleDlqMode
import io.axoniq.platform.framework.api.DeadLetterResponse
import io.axoniq.platform.framework.api.DeadLettersForAutomaticRetryRequest
import io.axoniq.platform.framework.api.SequenceLettersResponse
import io.axoniq.platform.framework.tenancy.PlatformTenancy
import org.apache.commons.codec.digest.DigestUtils
import org.axonframework.common.configuration.Configuration
import org.axonframework.messaging.core.Metadata
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory
import org.axonframework.messaging.eventhandling.EventHandlingComponent
import org.axonframework.messaging.eventhandling.EventMessage
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import io.axoniq.platform.framework.api.DeadLetter as ApiDeadLetter

private const val LETTER_PAYLOAD_SIZE_LIMIT = 1024
private const val MASKED = "<MASKED>"
private const val LIMITED = "<LIMITED>"

/**
 * Diagnostic key counting failed platform-driven (automated) retry attempts on a head letter.
 * Deliberately prefixed so it can't collide with user-maintained diagnostics such as the `retries`
 * example from the reference guide; manual retries never touch it.
 */
const val PLATFORM_RETRIES_DIAGNOSTIC = "__platform_retries"

/** Caps the exponential backoff shift so `backoffMinutes shl retries` cannot overflow. */
private const val MAX_EXPONENTIAL_SHIFT = 20
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
        clock: Clock = Clock.systemUTC(),
        tenantSizeTtl: Duration = Duration.ofSeconds(30),
        private val tenancyOf: (Configuration) -> PlatformTenancy? = PlatformTenancy::of,
) : ProcessingGroupInfoSource {

    private val tenantSizes = TenantSizeCache(clock = clock, ttl = tenantSizeTtl)

    @Volatile
    private var entries: List<DlqEntry>? = null

    /**
     * The application's own factory, so tenant-bound DLQ calls run in a unit of work with its transaction
     * management, which JDBC and JPA tenant queues need (as the tenant dead-letter queue reference guide
     * prescribes). [EventHandlingComponent.sequenceIdentifierFor] needs a non-null [ProcessingContext] too,
     * because `SequenceCachingEventHandlingComponent` stores per-event resources on it.
     *
     * The default factory's contexts are backed by the [Configuration]: a property-based policy (for example
     * `@SequencingPolicy(type = PropertySequencingPolicy::class, ...)`) converts the payload with the
     * `EventConverter` it looks up on the context, and without it every sequence falls back to the
     * message identifier.
     */
    private val unitOfWorkFactory: UnitOfWorkFactory by lazy {
        configuration.getComponent(UnitOfWorkFactory::class.java)
    }

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
                ProcessingGroupInfoSource.ProcessingGroupInfo(it.processingGroup, it.sequenceCount, it.sequenceCountPerTenant)
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

    /**
     * Null when the application is not multi-tenant. With multi-tenancy every queue is tenant-routed and refuses a
     * call without a tenant on the processing context, so each operation below runs once per tenant it covers.
     */
    private val tenancy: PlatformTenancy? by lazy { tenancyOf(configuration) }

    /** One sequence of a queue, with the tenant it belongs to (null in a single-tenant application). */
    private data class TenantSequence(val tenant: String?, val letters: List<DeadLetter<out EventMessage>>)

    /** The tenants a call covers: the one asked for, every tenant when none is, or none in a single-tenant application. */
    private fun tenantsFor(tenant: String?): List<String?> {
        val tenancy = tenancy ?: return listOf(null)
        return if (tenant != null) listOf(tenant) else tenancy.tenants()
    }

    private fun <R : Any> inTenant(tenant: String?, call: (ProcessingContext?) -> CompletableFuture<R>): CompletableFuture<R> {
        val tenancy = tenancy
        if (tenant == null || tenancy == null) {
            return call(null)
        }
        return unitOfWorkFactory.create().executeWithResult<R> { context ->
            tenancy.bind(context, tenant)
            call(context)
        }
    }

    private fun sequencesOf(entry: DlqEntry, tenant: String?): List<TenantSequence> =
            inTenant(tenant) { context ->
                entry.dlq.deadLetters(context).thenApply { sequences ->
                    sequences.map { TenantSequence(tenant, it.toList()) }
                }
            }.join()

    private fun amountOfSequences(entry: DlqEntry, tenant: String?): Long =
            inTenant(tenant) { context -> entry.dlq.amountOfSequences(context) }.join()

    private fun apiSequenceIdsOf(entry: DlqEntry, heads: List<DeadLetter<out EventMessage>>, tenant: String?): List<String> {
        val rawIds = sequenceIdentifiersFor(entry, heads, tenant)
        return if (dlqMode == AxoniqConsoleDlqMode.MASKED) rawIds.map { it.hashed() } else rawIds
    }

    fun deadLetters(
            processingGroup: String,
            offset: Int = 0,
            size: Int = 25,
            // Capped at 10 to keep poll payloads small; long sequences are browsed via sequenceLetters(...).
            maxSequenceLetters: Int = 10,
            tenant: String? = null,
    ): DeadLetterResponse {
        val entry = dlqFor(processingGroup)
        val tenancy = tenancy
        if (tenant == null && tenancy != null) {
            return deadLettersOfAllTenants(entry, tenancy, offset, size, maxSequenceLetters)
        }
        val scope = tenantsFor(tenant).single()
        val total = amountOfSequences(entry, scope)
        if (dlqMode == AxoniqConsoleDlqMode.NONE) {
            return DeadLetterResponse(emptyList(), total)
        }
        val pageOfSequences = sequencesOf(entry, scope)
                .drop(offset)
                .take(size)
                .toApiSequences(entry, scope, maxSequenceLetters)
        return DeadLetterResponse(pageOfSequences, total)
    }

    /**
     * Cuts the page from the per-tenant counts and loads only the tenants it falls in. The counts come from the
     * size cache the processor report keeps warm, so paging does not count every tenant again. Loading a tenant
     * reveals its real size, and a tenant on the page whose size no longer matches the cache sends the request to
     * a fresh count, as does a tenant set that changed. A change in a tenant outside the page shows in the total
     * once the TTL passes. A fresh count is not checked again: the page is cut from it, so it agrees with the total
     * it is returned with.
     */
    private fun deadLettersOfAllTenants(
            entry: DlqEntry,
            tenancy: PlatformTenancy,
            offset: Int,
            size: Int,
            maxSequenceLetters: Int,
    ): DeadLetterResponse {
        val tenants = tenancy.tenants()
        val (counts, page) = tenantSizes.peek(entry.processingGroup)
                ?.takeIf { it.keys == tenants.toSet() }
                ?.let { cached -> loadPage(entry, cached, offset, size).takeIf { it.matches(cached) }?.let { cached to it } }
                ?: tenantSizes.refresh(entry.processingGroup) { tenants.associateWith { amountOfSequences(entry, it) } }
                        .let { fresh -> fresh to loadPage(entry, fresh, offset, size) }
        val pageOfSequences = page.flatMap { (slice, sequences) ->
            sequences.drop(slice.skip).take(slice.take).toApiSequences(entry, slice.tenant, maxSequenceLetters)
        }
        return DeadLetterResponse(pageOfSequences, counts.values.sum())
    }

    private data class LoadedSlice(val slice: TenantSlice, val sequences: List<TenantSequence>)

    private fun loadPage(entry: DlqEntry, counts: Map<String, Long>, offset: Int, size: Int): List<LoadedSlice> =
            if (dlqMode == AxoniqConsoleDlqMode.NONE) {
                emptyList()
            } else {
                pageSlices(counts = counts, offset = offset, size = size)
                        .map { LoadedSlice(slice = it, sequences = sequencesOf(entry, it.tenant)) }
            }

    private fun List<LoadedSlice>.matches(counts: Map<String, Long>): Boolean =
            all { counts[it.slice.tenant] == it.sequences.size.toLong() }

    private data class TenantSlice(val tenant: String, val skip: Int, val take: Int)

    /**
     * The part of each tenant that the page covers when the tenants' sequences are laid end to end in tenant id
     * order. Cut by [counts] rather than by what a queue holds once loaded, so a tenant that gained sequences since
     * it was counted does not push its neighbours' sequences onto the wrong page.
     */
    private fun pageSlices(counts: Map<String, Long>, offset: Int, size: Int): List<TenantSlice> {
        var skip = offset.coerceAtLeast(0).toLong()
        var remaining = size.coerceAtLeast(0).toLong()
        val slices = mutableListOf<TenantSlice>()
        for ((tenant, count) in counts.toSortedMap()) {
            if (remaining == 0L) break
            if (skip >= count) {
                skip -= count
                continue
            }
            val take = minOf(remaining, count - skip)
            slices += TenantSlice(tenant = tenant, skip = skip.toInt(), take = take.toInt())
            remaining -= take
            skip = 0
        }
        return slices
    }

    private fun List<TenantSequence>.toApiSequences(
            entry: DlqEntry,
            tenant: String?,
            maxSequenceLetters: Int,
    ): List<List<ApiDeadLetter>> {
        val ids = apiSequenceIdsOf(entry, mapNotNull { it.letters.firstOrNull() }, tenant).iterator()
        return map { sequence ->
            val apiSequenceId = if (sequence.letters.isEmpty()) "" else ids.next()
            sequence.letters
                    .take(maxSequenceLetters)
                    .map { it.toApiLetter(apiSequenceId, sequence.tenant) }
        }
    }

    fun sequenceSize(processingGroup: String, sequenceIdentifier: String, tenant: String? = null): Long {
        val entry = dlqFor(processingGroup)
        return findSequence(entry, sequenceIdentifier, tenant)?.letters?.count()?.toLong() ?: 0L
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
            tenant: String? = null,
    ): SequenceLettersResponse {
        val entry = dlqFor(processingGroup)
        if (dlqMode == AxoniqConsoleDlqMode.NONE) {
            return SequenceLettersResponse(emptyList(), 0)
        }
        val sequence = findSequence(entry, sequenceIdentifier, tenant)
                ?: return SequenceLettersResponse(emptyList(), 0)
        val total = sequence.letters.size.toLong()
        val safeOffset = offset.coerceAtLeast(0)
        val safeSize = size.coerceAtLeast(1)
        val slice = sequence.letters
                .drop(safeOffset)
                .take(safeSize)
                .map { it.toApiLetter(sequenceIdentifier, sequence.tenant) }
        return SequenceLettersResponse(slice, total)
    }

    /**
     * Evicts every letter belonging to the sequence identified by [sequenceIdentifier].
     *
     * @return the number of letters that were actually evicted (0 when the id no longer resolves — e.g. the
     *         operator's view was stale).
     */
    fun delete(processingGroup: String, sequenceIdentifier: String, tenant: String? = null): Int = mutating(processingGroup) {
        val entry = dlqFor(processingGroup)
        val sequence = findSequence(entry, sequenceIdentifier, tenant)
        if (sequence == null) {
            logger.warn(
                    "DLQ delete-sequence: no sequence in [{}] matches id [{}] — nothing to evict",
                    processingGroup, sequenceIdentifier,
            )
            return@mutating 0
        }
        logger.info(
                "DLQ delete-sequence: evicting {} letters from sequence [{}] in [{}]",
                sequence.letters.size, sequenceIdentifier, processingGroup,
        )
        var evicted = 0
        sequence.letters.forEach { letter ->
            inTenant(sequence.tenant) { context -> entry.dlq.evict(letter, context) }.join()
            evicted++
        }
        evicted
    }

    /**
     * Evicts a single letter identified by [messageIdentifier] from the sequence identified by
     * [sequenceIdentifier]. Returns `true` when an eviction was performed; `false` indicates the
     * sequence id or message id no longer resolves (typically because the caller's view was stale).
     */
    fun delete(
            processingGroup: String,
            sequenceIdentifier: String,
            messageIdentifier: String,
            tenant: String? = null,
    ): Boolean = mutating(processingGroup) {
        val entry = dlqFor(processingGroup)
        val sequence = findSequence(entry, sequenceIdentifier, tenant)
        if (sequence == null) {
            logger.warn(
                    "DLQ delete-letter: no sequence in [{}] matches id [{}] (message id was [{}]) — caller view likely stale",
                    processingGroup, sequenceIdentifier, messageIdentifier,
            )
            return@mutating false
        }
        val target = sequence.letters.firstOrNull { it.message().identifier() == messageIdentifier }
        if (target == null) {
            logger.warn(
                    "DLQ delete-letter: sequence [{}] in [{}] (size={}) does not contain message id [{}] — already evicted?",
                    sequenceIdentifier, processingGroup, sequence.letters.size, messageIdentifier,
            )
            return@mutating false
        }
        logger.info(
                "DLQ delete-letter: evicting message [{}] from sequence [{}] in [{}]",
                messageIdentifier, sequenceIdentifier, processingGroup,
        )
        inTenant(sequence.tenant) { context -> entry.dlq.evict(target, context) }.join()
        true
    }

    /**
     * Resolves a DLQ sequence by the identifier this manager exposes through the API. Walks every sequence
     * in the queue, derives each sequence's identifier via [sequenceIdentifiersFor], and matches.
     *
     * When [dlqMode] is [AxoniqConsoleDlqMode.MASKED] the API-side identifier is a SHA-256 hash, so the
     * lookup compares the hash of each candidate id against the supplied [sequenceIdentifier] — this keeps
     * the delete/process operations working even when the operator only sees masked ids.
     *
     * Without a [tenant], a multi-tenant application searches every tenant. The same sequence identifier can exist
     * in two tenants (an aggregate id is only unique per tenant), and acting on the wrong one is worse than not
     * acting, so a match in more than one tenant is refused.
     */
    private fun findSequence(
            entry: DlqEntry,
            sequenceIdentifier: String,
            tenant: String?,
    ): TenantSequence? {
        val matches = mutableListOf<TenantSequence>()
        // Track candidates so we can log a diagnostic when nothing matches — the most common cause
        // is a stale identifier (the sequence's first letter has changed) or a mode mismatch between
        // the value the UI cached and what the manager now computes. Capped to keep log noise low.
        val candidates = mutableListOf<String>()
        for (scope in tenantsFor(tenant)) {
            val sequences = sequencesOf(entry, scope).filter { it.letters.isNotEmpty() }
            val ids = apiSequenceIdsOf(entry, sequences.map { it.letters.first() }, scope)
            for ((sequence, candidateId) in sequences.zip(ids)) {
                if (candidateId == sequenceIdentifier) {
                    matches += sequence
                } else if (candidates.size < 5) {
                    candidates.add(candidateId)
                }
            }
        }
        if (matches.size > 1) {
            throw IllegalArgumentException(
                    "Sequence [$sequenceIdentifier] of [${entry.processingGroup}] exists in tenants " +
                            "${matches.map { it.tenant }}; name the tenant to act on.")
        }
        if (matches.isEmpty()) {
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
        return matches.single()
    }

    /**
     * The tenant to act on for the letter [messageIdentifier]: the one asked for, or in a multi-tenant application the
     * tenant whose queue holds it. Message identifiers are unique, so unlike [findSequence] there is no ambiguity.
     *
     * @return [Located.Nowhere] when a multi-tenant application holds the letter in no tenant.
     */
    private fun locate(entry: DlqEntry, messageIdentifier: String, tenant: String?): Located {
        if (tenant != null || tenancy == null) {
            return Located.In(tenant)
        }
        val holder = tenantsFor(null).firstOrNull { scope ->
            sequencesOf(entry, scope).any { sequence ->
                sequence.letters.any { it.message().identifier() == messageIdentifier }
            }
        }
        return if (holder == null) Located.Nowhere else Located.In(holder)
    }

    private sealed interface Located {
        data class In(val tenant: String?) : Located
        data object Nowhere : Located
    }

    private fun processMatching(
            entry: DlqEntry,
            tenant: String?,
            filter: (DeadLetter<out EventMessage>) -> Boolean,
    ): CompletableFuture<Boolean> =
            inTenant(tenant) { context ->
                if (context == null) entry.processor.process(filter) else entry.processor.process(filter, context)
            }

    fun process(processingGroup: String, messageIdentifier: String, tenant: String? = null): Boolean = mutating(processingGroup) {
        val entry = dlqFor(processingGroup)
        val located = locate(entry, messageIdentifier, tenant) as? Located.In ?: return@mutating false
        processMatching(entry, located.tenant) { it.message().identifier() == messageIdentifier }
                .get(60, TimeUnit.SECONDS)
    }

    fun processAll(
            processingGroup: String,
            maxMessages: Int? = null,
            timeoutSeconds: Long = 600,
            tenant: String? = null,
    ): Int = mutating(processingGroup) {
        val entry = dlqFor(processingGroup)
        var processed = 0
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        for (scope in tenantsFor(tenant)) {
            while (maxMessages == null || processed < maxMessages) {
                if (System.nanoTime() > deadline) return@mutating processed
                val didProcess = processMatching(entry, scope) { true }.get(timeoutSeconds, TimeUnit.SECONDS)
                if (!didProcess) break
                processed++
            }
        }
        processed
    }

    /**
     * Returns the sequences of the DLQ that are due for a platform-driven automatic retry,
     * filtered client-side so exhausted or still-backing-off sequences never leave the
     * application. A sequence is due when its head letter has fewer than
     * [DeadLettersForAutomaticRetryRequest.maxRetries] recorded automated attempts (the
     * [PLATFORM_RETRIES_DIAGNOSTIC] diagnostic) and its `lastTouched` is at least the computed
     * backoff ago.
     *
     * Only identifiers ever cross the wire, so this works in every [dlqMode] except
     * [AxoniqConsoleDlqMode.NONE]: sequence identifiers can carry domain meaning (e.g. an
     * aggregate id), and NONE's contract is that nothing but counts is exposed — so it fails
     * closed with an empty result. In MASKED mode the sequence identifier is hashed, consistent
     * with the other queries.
     */
    fun deadLettersForAutomaticRetry(request: DeadLettersForAutomaticRetryRequest): AutomaticRetryCandidatesResponse {
        val entry = dlqFor(request.processingGroup)
        if (dlqMode == AxoniqConsoleDlqMode.NONE) {
            return AutomaticRetryCandidatesResponse(emptyList())
        }
        val now = Instant.now()
        val due = tenantsFor(request.tenant).asSequence()
                .flatMap { sequencesOf(entry, it) }
                .mapNotNull { sequence -> sequence.letters.firstOrNull()?.let { sequence.tenant to it } }
                .map { (tenant, head) -> Triple(tenant, head, head.platformRetries()) }
                .filter { (_, _, retries) -> retries < request.maxRetries }
                .filter { (_, head, retries) -> !head.lastTouched().plus(request.backoffFor(retries)).isAfter(now) }
                .take(request.limit.coerceAtLeast(0))
                .toList()
        val idsPerTenant = due.groupBy { (tenant, _, _) -> tenant }
                .mapValues { (tenant, heads) -> apiSequenceIdsOf(entry, heads.map { it.second }, tenant).iterator() }
        val candidates = due.map { (tenant, head, retries) ->
            AutomaticRetryCandidate(
                    sequenceIdentifier = idsPerTenant.getValue(tenant).next(),
                    messageIdentifier = head.message().identifier(),
                    retries = retries,
                    lastTouched = head.lastTouched(),
                    tenant = tenant,
            )
        }
        return AutomaticRetryCandidatesResponse(candidates)
    }

    /**
     * Processes the sequence whose head letter matches [messageIdentifier] as an automated
     * (platform-driven) retry. Behaves like [process], except that a failed attempt increments the
     * head letter's [PLATFORM_RETRIES_DIAGNOSTIC] diagnostic — manual retries deliberately leave
     * that counter untouched so operator actions don't consume the automated retry budget.
     *
     * The counter is bumped after the framework has requeued the failed letter (rather than by
     * decorating the enqueue policy) so a custom user [io.axoniq.framework.messaging.deadletter.EnqueuePolicy]
     * that rewrites diagnostics wholesale can't wipe it. Attempts are counted per head letter: when
     * a retry round processes some letters before failing on a later one, the new head starts with
     * a fresh counter — the sequence made progress, so it earned a fresh budget.
     */
    fun automatedRetry(processingGroup: String, messageIdentifier: String, tenant: String? = null): Boolean = mutating(processingGroup) {
        val entry = dlqFor(processingGroup)
        val located = locate(entry, messageIdentifier, tenant) as? Located.In ?: return@mutating false
        val success = try {
            processMatching(entry, located.tenant) { it.message().identifier() == messageIdentifier }
                    .get(60, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            // A sequence that fails by being slow must still consume its retry budget — otherwise
            // it is retried every round forever without ever counting toward maxRetries.
            logger.warn(
                    "Automated retry of message [{}] in [{}] timed out — counting it as a failed attempt.",
                    messageIdentifier, entry.processingGroup,
            )
            false
        }
        if (!success) {
            registerFailedAutomatedAttempt(entry, messageIdentifier, located.tenant)
        }
        success
    }

    private fun registerFailedAutomatedAttempt(entry: DlqEntry, messageIdentifier: String, tenant: String?) {
        val head = sequencesOf(entry, tenant)
                .asSequence()
                .mapNotNull { it.letters.firstOrNull() }
                .firstOrNull { it.message().identifier() == messageIdentifier }
        if (head == null) {
            logger.warn(
                    "Automated retry of message [{}] in [{}] failed, but the letter is no longer the head of a " +
                            "sequence — skipping the retry-counter update.",
                    messageIdentifier, entry.processingGroup,
            )
            return
        }
        val next = (head.platformRetries() + 1).toString()
        inTenant(tenant) { context ->
            entry.dlq.requeue(
                    head,
                    { letter -> letter.withDiagnostics { it.and(PLATFORM_RETRIES_DIAGNOSTIC, next) } },
                    context,
            )
        }.join()
    }

    private fun DeadLetter<out EventMessage>.platformRetries(): Int =
            diagnostics()[PLATFORM_RETRIES_DIAGNOSTIC]?.toIntOrNull() ?: 0

    private fun DeadLettersForAutomaticRetryRequest.backoffFor(retries: Int): Duration {
        val minutes = when (backoff) {
            AutomaticRetryBackoff.CONSISTENT -> backoffMinutes.toLong()
            AutomaticRetryBackoff.EXPONENTIAL ->
                backoffMinutes.toLong() shl retries.coerceAtMost(MAX_EXPONENTIAL_SHIFT)
        }
        return Duration.ofMinutes(minutes)
    }

    fun deleteAll(processingGroup: String, timeoutSeconds: Long = 600, tenant: String? = null): Int = mutating(processingGroup) {
        val dlq = dlqFor(processingGroup).dlq
        tenantsFor(tenant).sumOf { scope ->
            val count = inTenant(scope) { context -> dlq.size(context) }.get(timeoutSeconds, TimeUnit.SECONDS).toInt()
            inTenant(scope) { context -> dlq.clear(context) }.get(timeoutSeconds, TimeUnit.SECONDS)
            count
        }
    }

    /** Drops the group's cached tenant sizes even when [block] fails halfway through a batch it partly applied. */
    private inline fun <R> mutating(processingGroup: String, block: () -> R): R =
            try {
                block()
            } finally {
                tenantSizes.invalidate(processingGroup)
            }

    /**
     * Returns the DLQ entries belonging to the given processor — used by [ProcessorReportCreator] to surface DLQ size
     * per processing group in the processor report.
     *
     * In a multi-tenant application the per-tenant sizes are cached, because counting asks every tenant's queue
     * (and makes the framework create one for each tenant) on every report. Letters the processor dead-letters
     * itself show up after the TTL. A mutation made through this manager drops the cache of this node only: the
     * other nodes of the application keep reporting their cached sizes until the TTL passes. A single-tenant
     * application counts on every report as before: it is one call, and caching it would only add lag.
     */
    fun dlqInfoForProcessor(processorName: String): List<DlqInfo> =
            discover()
                    .filter { it.processorName == processorName }
                    .map { entry ->
                        val perTenant = tenancy?.let { t ->
                            tenantSizes.get(entry.processingGroup) {
                                t.tenants().associateWith { amountOfSequences(entry, it) }
                            }
                        }
                        DlqInfo(
                                processingGroup = entry.processingGroup,
                                sequenceCount = perTenant?.values?.sum() ?: amountOfSequences(entry, null),
                                sequenceCountPerTenant = perTenant,
                        )
                    }

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
        return parsed.mapNotNull {
            val ehcName = "EventHandlingComponent[${it.processor}][${it.component}]"
            val ehc = it.module
                    .getOptionalComponent(EventHandlingComponent::class.java, ehcName)
                    .orElse(null)
            val processor = ehc?.findDeadLetterProcessor()
                    ?: it.module
                            .getOptionalComponent(SequencedDeadLetterProcessor::class.java, ehcName)
                            .orElse(null)
                            ?.let {
                                @Suppress("UNCHECKED_CAST")
                                it as SequencedDeadLetterProcessor<EventMessage>
                            }
            if (processor == null) {
                logger.warn(
                        "Skipping DLQ for component [{}] — could not resolve a SequencedDeadLetterProcessor " +
                                "from the registered EventHandlingComponent. Retry/process actions will be " +
                                "unavailable for this component; deadLetters and delete remain functional.",
                        ehcName
                )
                return@mapNotNull null
            }
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

    @Suppress("UNCHECKED_CAST")
    private fun EventHandlingComponent.findDeadLetterProcessor(): SequencedDeadLetterProcessor<EventMessage>? {
        var current: Any? = this
        val seen = mutableSetOf<Any>()
        while (current != null && seen.add(current)) {
            if (current is SequencedDeadLetterProcessor<*>) {
                return current as SequencedDeadLetterProcessor<EventMessage>
            }
            current = when (current) {
                is AxoniqPlatformEventHandlingComponent -> current.delegate
                else -> current.javaClass.declaredFields
                        .firstOrNull { EventHandlingComponent::class.java.isAssignableFrom(it.type) }
                        ?.also { it.isAccessible = true }
                        ?.get(current)
            }
        }
        return null
    }

    private fun discover(): List<DlqEntry> = entries ?: discoverEntries().also { entries = it }

    /**
     * Resolves the sequence identifier of each of [letters] by calling [EventHandlingComponent.sequenceIdentifierFor]
     * with the [ProcessingContext] of a real [org.axonframework.messaging.core.unitofwork.UnitOfWork], which the
     * decorator chain (including `SequenceCachingEventHandlingComponent`) needs to read resources from.
     *
     * All letters share one unit of work, bound to [tenant]: with a transactional factory every unit of work is a
     * transaction, and one per letter made a page cost up to 25 and a sequence lookup one per sequence in the
     * tenant. Sharing it is safe because `SequenceCachingEventHandlingComponent` caches per message identifier.
     *
     * Result shape mirrors the AF4 implementation:
     *  - String results are used verbatim;
     *  - non-String results fall back to `hashCode().toString()`;
     *  - if the EHC reference could not be captured at discovery time, or sequence resolution throws
     *    or returns `null`, the letter's message identifier is used so each letter still has a
     *    unique id.
     */
    private fun sequenceIdentifiersFor(
            entry: DlqEntry,
            letters: List<DeadLetter<out EventMessage>>,
            tenant: String?,
    ): List<String> {
        val messages = letters.map { it.message() }
        val ehc = entry.eventHandlingComponent
        if (ehc == null || messages.isEmpty()) {
            return messages.map { it.identifier() }
        }
        val raw: List<Any?> = try {
            unitOfWorkFactory.create().executeWithResult { context: ProcessingContext ->
                tenant?.let { tenancy?.bind(context, it) }
                CompletableFuture.completedFuture(messages.map { sequenceIdentifierOrNull(entry, ehc, it, context) })
            }.join()
        } catch (ex: Exception) {
            logger.debug(
                    "Could not resolve sequence identifiers in [{}] — falling back to message ids.",
                    entry.processingGroup, ex,
            )
            messages.map { null }
        }
        return messages.zip(raw) { message, id ->
            when (id) {
                null -> message.identifier()
                is String -> id
                else -> id.hashCode().toString()
            }
        }
    }

    private fun sequenceIdentifierOrNull(
            entry: DlqEntry,
            ehc: EventHandlingComponent,
            message: EventMessage,
            context: ProcessingContext,
    ): Any? = try {
        ehc.sequenceIdentifierFor(message, context)
    } catch (ex: Exception) {
        logger.debug(
                "Sequence identifier resolution threw for message [{}] in [{}] — falling back to message id.",
                message.identifier(), entry.processingGroup, ex,
        )
        null
    }

    private fun DeadLetter<out EventMessage>.toApiLetter(sequenceIdentifier: String, tenant: String?): ApiDeadLetter {
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
                    tenant = tenant,
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
                    tenant = tenant,
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
                    tenant = tenant,
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
    data class DlqInfo(
            val processingGroup: String,
            val sequenceCount: Long,
            val sequenceCountPerTenant: Map<String, Long>? = null,
    )
}

/**
 * Per processing group sizes, with an invalidation generation so a count that was running while a mutation
 * invalidated the group is returned but not stored.
 */
private class TenantSizeCache(private val clock: Clock, private val ttl: Duration) {

    private data class Entry(val sizes: Map<String, Long>, val expiresAt: Instant)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val generation = AtomicLong()

    fun get(processingGroup: String, count: () -> Map<String, Long>): Map<String, Long> =
            peek(processingGroup) ?: refresh(processingGroup, count)

    fun peek(processingGroup: String): Map<String, Long>? =
            entries[processingGroup]?.takeIf { clock.instant().isBefore(it.expiresAt) }?.sizes

    fun refresh(processingGroup: String, count: () -> Map<String, Long>): Map<String, Long> {
        val now = clock.instant()
        val startedAt = generation.get()
        val sizes = count()
        entries.compute(processingGroup) { _, current ->
            if (generation.get() == startedAt) Entry(sizes = sizes, expiresAt = now.plus(ttl)) else current
        }
        return sizes
    }

    fun invalidate(processingGroup: String) {
        entries.compute(processingGroup) { _, _ ->
            generation.incrementAndGet()
            null
        }
    }
}
