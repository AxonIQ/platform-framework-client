/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.workflow

import io.axoniq.framework.workflow.dsl.api.WorkflowStatus
import io.axoniq.framework.workflow.runtime.execution.EventSourcedRunningWorkflows
import io.axoniq.framework.workflow.runtime.execution.WorkflowEventTags
import io.axoniq.platform.framework.AxoniqPlatformConfiguration
import io.axoniq.platform.framework.api.WorkflowExecutionSummary
import io.axoniq.platform.framework.api.metrics.WorkflowStatisticIdentifier
import io.github.oshai.kotlinlogging.KotlinLogging
import org.axonframework.common.configuration.Configuration
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.eventsourcing.eventstore.GlobalIndexPosition
import org.axonframework.eventsourcing.eventstore.Position
import org.axonframework.eventsourcing.eventstore.SourcingCondition
import org.axonframework.eventsourcing.snapshot.api.Snapshot
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore
import org.axonframework.messaging.core.QualifiedName
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory
import org.axonframework.messaging.eventhandling.EventMessage
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken
import org.axonframework.messaging.eventstreaming.EventCriteria
import org.axonframework.messaging.eventstreaming.Tag
import tools.jackson.dataformat.cbor.CBORMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The workflow instances of this application, kept in memory from the lifecycle and wait events the engine tags:
 * every running instance with its open waits, and the finished ones within the retention, up to a maximum. The list
 * of executions and the open counts of the statistics report both read it, so the events are read once per node.
 *
 * A [refresh] reads the events appended since the previous one. The first one starts from the snapshot the nodes of
 * the application store in the [SnapshotStore] every few minutes, so the full history is read once, not at every
 * boot; without a snapshot store it starts from the beginning. A node skips storing when another node stored a
 * snapshot of the same definitions within that interval, so mostly one node stores it. Events this node appends are applied right away as
 * well, so its own instances are known before the next refresh. A request waits at most [refreshWait] for a refresh
 * and otherwise answers from what is known, so a long first read does not make it time out.
 *
 * Only instances of the workflows this application defines are kept, because applications that share an event store
 * see each other's events. Until the definitions are known nothing is read, and when they change everything is read
 * again. The events carry no application identity, so two applications that share a context and define a workflow of
 * the same name both list the instances of either.
 */
internal class WorkflowExecutionsProjection(
        private val configuration: Configuration,
        retention: Duration? = null,
        maxFinished: Int? = null,
        refreshWait: Duration? = null,
        private val definitionNames: () -> Set<String>,
) {
    private val logger = KotlinLogging.logger { }
    private val events = IncrementalSource(configuration, EventCriteria.either(LIFECYCLE, WAITS))
    private val instances = ConcurrentHashMap<String, WorkflowInstance>()
    private val settings = configuration.getOptionalComponent(AxoniqPlatformConfiguration::class.java).orElse(null)
    private val retention = retention ?: settings?.workflowRetention ?: Duration.ofDays(30)
    private val maxFinished = maxFinished ?: settings?.workflowMaxFinishedExecutions ?: 10_000
    private val snapshotName = settings?.applicationName ?: "application"
    private val refreshWait = refreshWait ?: Duration.ofSeconds(5)
    private var lastSnapshotAt = Instant.EPOCH
    private var changedSinceSnapshot = false
    private val background = Executors.newSingleThreadExecutor { Thread(it, "axoniq-platform-workflow-executions").apply { isDaemon = true } }
    private val refreshLock = Any()
    private var running: CompletableFuture<Void>? = null

    @Volatile
    private var definitions = emptySet<String>()

    @Volatile
    private var ready = false

    @Synchronized
    fun refresh() {
        val defined = definitionNames()
        if (defined.isEmpty()) return
        if (defined != definitions) {
            definitions = defined
            instances.clear()
            events.position = Position.START
            restore()
        }
        if (events.read { apply(it) } > 0) changedSinceSnapshot = true
        prune()
        ready = true
        if (changedSinceSnapshot && Instant.now().isAfter(lastSnapshotAt.plus(SNAPSHOT_INTERVAL))) {
            storeSnapshot()
        }
    }

    /** Starts a refresh in the background, or returns the one that is running. */
    fun refreshInBackground(): CompletableFuture<Void> = synchronized(refreshLock) {
        running ?: CompletableFuture.runAsync(::refresh, background).also { refresh ->
            running = refresh
            refresh.whenComplete { _, _ -> synchronized(refreshLock) { if (running === refresh) running = null } }
        }
    }

    fun <T> read(read: (Collection<WorkflowInstance>) -> T): T {
        try {
            refreshInBackground().get(refreshWait.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            logger.debug { "Reading the workflow executions takes longer than $refreshWait; answering from what is known" }
        } catch (e: ExecutionException) {
            logger.debug(e.cause) { "Could not read the workflow executions from the event store" }
        }
        return read(instances.values)
    }

    /** A running instance, or null when this node does not know it as running. */
    fun running(workflowId: String): WorkflowInstance? = instances[workflowId]?.takeIf { it.isRunning() }

    /** Open instances per definition version and waiting instances per wait step; null until the first refresh. */
    fun openCounts(): Map<WorkflowStatisticIdentifier, Long>? {
        if (!ready) return null
        val counts = HashMap<WorkflowStatisticIdentifier, Long>()
        instances.values.filter { it.isRunning() }.forEach { instance ->
            val name = instance.definitionName
            val version = instance.definitionVersion
            counts.merge(WorkflowStatisticIdentifier(definitionName = name, definitionVersion = version), 1, Long::plus)
            instance.waits.keys.forEach { step ->
                counts.merge(WorkflowStatisticIdentifier(definitionName = name, definitionVersion = version, stepName = step), 1, Long::plus)
            }
        }
        return counts
    }

    fun apply(event: EventMessage) {
        val workflowId = event.metadata()[WORKFLOW_ID] ?: return
        val instance = instances[workflowId] ?: run {
            val name = event.metadata()[DEFINITION_NAME] ?: return
            if (event.metadata()[WORKFLOW_STATUS] != WorkflowStatus.STARTED.name) return
            if (name !in definitions) return
            instances.computeIfAbsent(workflowId) { WorkflowInstance(it) }
        }
        instance.apply(event)
    }

    /** Drops what ended before the retention, then the oldest finished beyond the maximum. */
    private fun prune() {
        val retainedFrom = Instant.now().minus(retention).toEpochMilli()
        instances.values.removeIf { it.finishedAt?.let { finished -> finished < retainedFrom } == true }
        val finished = instances.values.filter { it.finishedAt != null }
        if (finished.size > maxFinished) {
            finished.sortedBy { it.finishedAt }.take(finished.size - maxFinished).forEach { instances.remove(it.workflowId) }
        }
    }

    private fun restore() {
        val store = configuration.getOptionalComponent(SnapshotStore::class.java).orElse(null) ?: return
        try {
            val snapshot = store.load(SNAPSHOT_NAME, snapshotName, null).get(SNAPSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    ?.takeIf { it.version == SNAPSHOT_VERSION } ?: return
            val payload = GZIPInputStream(ByteArrayInputStream(snapshot.payload() as ByteArray)).use { mapper.readValue<SnapshotPayload>(it) }
            // A node that does not define all of ours left their instances out.
            if (!payload.definitions.containsAll(definitions)) return
            payload.instances.filter { it.name in definitions }.forEach { instances[it.id] = it.toInstance() }
            events.position = snapshot.position()
            logger.debug { "Restored ${payload.instances.size} workflow executions from the snapshot at ${snapshot.position()}" }
        } catch (e: Exception) {
            logger.debug(e) { "Could not restore the workflow executions from a snapshot; reading them from the event store" }
            instances.clear()
            events.position = Position.START
        }
    }

    private fun storeSnapshot() {
        val store = configuration.getOptionalComponent(SnapshotStore::class.java).orElse(null) ?: return
        lastSnapshotAt = Instant.now()
        if (storedRecently(store)) return
        val bytes = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { mapper.writeValue(it, SnapshotPayload(definitions, instances.values.map { instance -> instance.toSnapshot() })) }
        }.toByteArray()
        val metadata = mapOf(SNAPSHOT_DEFINITIONS to definitions.sorted().joinToString("\n"))
        store.store(SNAPSHOT_NAME, snapshotName, Snapshot(events.position, SNAPSHOT_VERSION, bytes, Instant.now(), metadata), null)
                .whenComplete { _, e -> if (e != null) logger.debug(e) { "Could not store the workflow executions snapshot" } }
        changedSinceSnapshot = false
    }

    private fun storedRecently(store: SnapshotStore): Boolean = try {
        store.load(SNAPSHOT_NAME, snapshotName, null).get(SNAPSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                ?.takeIf { it.version == SNAPSHOT_VERSION }
                ?.takeIf { it.metadata()[SNAPSHOT_DEFINITIONS]?.split("\n")?.toSet()?.containsAll(definitions) == true }
                ?.let { Instant.now().isBefore(it.timestamp().plus(SNAPSHOT_INTERVAL)) } == true
    } catch (e: Exception) {
        logger.debug(e) { "Could not read the stored workflow executions snapshot; storing this node's" }
        false
    }

    private data class SnapshotPayload(val definitions: Set<String>, val instances: List<SnapshotInstance>)

    private data class SnapshotInstance(
            val id: String,
            val name: String,
            val version: String,
            val status: String,
            val startedAt: Long?,
            val finishedAt: Long?,
            val lastEventAt: Long,
            val waits: Map<String, Long>,
    ) {
        fun toInstance() = WorkflowInstance(id).also {
            it.definitionName = name
            it.definitionVersion = version
            it.status = status
            it.startedAt = startedAt
            it.finishedAt = finishedAt
            it.lastEventAt = lastEventAt
            it.waits.putAll(waits)
        }
    }

    private fun WorkflowInstance.toSnapshot() = SnapshotInstance(
            id = workflowId, name = definitionName, version = definitionVersion, status = status,
            startedAt = startedAt, finishedAt = finishedAt, lastEventAt = lastEventAt, waits = HashMap(waits),
    )

    internal companion object {
        val SNAPSHOT_NAME = QualifiedName("io.axoniq.platform.WorkflowExecutions")
        const val SNAPSHOT_VERSION = "1"
        const val SNAPSHOT_DEFINITIONS = "definitions"
        private const val SNAPSHOT_TIMEOUT_SECONDS = 30L
        val SNAPSHOT_INTERVAL: Duration = Duration.ofMinutes(5)
        private val mapper: CBORMapper = CBORMapper.builder().addModule(KotlinModule.Builder().build()).build()
    }
}

/**
 * Sources the events matching [criteria] that were appended since the previous [read].
 *
 * The next read starts after the last event read, not at the resume position the store reports: under concurrent
 * appends that position can lie past events that were not visible yet, which would then never be read. The position
 * is the token of the last event read. Engines give an event the token after it and source from a position
 * inclusively, so the next read starts after that event. Events whose token is not past the position are skipped
 * anyway, so an engine that does return that event again neither applies it twice nor reports it as new.
 */
internal class IncrementalSource(private val configuration: Configuration, private val criteria: EventCriteria) {
    var position: Position = Position.START

    /** Reads the new events, returning how many there were. */
    fun read(consumer: (EventMessage) -> Unit): Int {
        val eventStore = configuration.getOptionalComponent(EventStore::class.java).orElse(null) ?: return 0
        val after = (position as? GlobalIndexPosition)?.let(GlobalIndexPosition::toIndex) ?: Long.MIN_VALUE
        var next: Position? = null
        var count = 0
        configuration.getComponent(UnitOfWorkFactory::class.java).create().executeWithResult { context ->
            eventStore.transaction(context)
                    .source(SourcingCondition.conditionFor(position, criteria))
                    .reduce(Unit) { _, entry ->
                        val index = TrackingToken.fromContext(entry).map { (it as? GlobalSequenceTrackingToken)?.globalIndex }.orElse(null)
                        if (index == null || index > after) {
                            consumer(entry.message())
                            count++
                            index?.let { next = GlobalIndexPosition(it) }
                        }
                    }
                    .thenApply { true }
        }.get()
        next?.let { position = it }
        return count
    }
}

/** One instance as its start and end events record it, with the wait-for-event steps it waits at. */
internal class WorkflowInstance(val workflowId: String) {
    @Volatile var definitionName = ""
    @Volatile var definitionVersion = ""
    @Volatile var status = WorkflowStatus.NONE.name
    @Volatile var startedAt: Long? = null
    @Volatile var finishedAt: Long? = null
    @Volatile var lastEventAt = 0L

    /** Wait-for-event steps whose latest event is their start, with that start. */
    val waits = ConcurrentHashMap<String, Long>()

    fun isRunning() = status == WorkflowStatus.STARTED.name

    /** Applying an event twice, or a start after the end, leaves the instance as it was. */
    @Synchronized
    fun apply(event: EventMessage) {
        val metadata = event.metadata()
        val occurredAt = event.timestamp().toEpochMilli()
        val lifecycle = metadata[WORKFLOW_STATUS]?.let { runCatching { WorkflowStatus.valueOf(it) }.getOrNull() }
        if (lifecycle == null) {
            val step = metadata[STEP_NAME] ?: return
            if (metadata[STEP_PRIMITIVE] != WAIT_FOR_EVENT || !isRunning()) return
            if (metadata[STEP_TYPE] == "STARTED") waits[step] = occurredAt else waits.remove(step)
            return
        }
        if (lifecycle == WorkflowStatus.STARTED && finishedAt != null) return
        metadata[DEFINITION_NAME]?.let { if (definitionName.isEmpty()) definitionName = it }
        metadata[DEFINITION_VERSION]?.let { if (definitionVersion.isEmpty()) definitionVersion = it }
        status = lifecycle.name
        lastEventAt = maxOf(lastEventAt, occurredAt)
        when {
            lifecycle == WorkflowStatus.STARTED -> startedAt = occurredAt
            lifecycle.isTerminal -> {
                finishedAt = occurredAt
                waits.clear()
            }
        }
    }

    fun summary() = WorkflowExecutionSummary(
            workflowId = workflowId,
            definitionName = definitionName,
            definitionVersion = definitionVersion,
            status = status,
            startedAt = startedAt,
            finishedAt = finishedAt,
            lastEventAt = lastEventAt,
    )
}

/** A start, step or end event of an instance; [stepName] is null for the instance's own start and end. */
internal class JournalEntry(val status: String, val stepName: String?, val primitive: String?, val occurredAt: Long)

internal fun journalEntryOf(event: EventMessage): JournalEntry? {
    val metadata = event.metadata()
    if (metadata.containsKey(VERSION_CHANGE_ID)) return null
    val lifecycleStatus = metadata[WORKFLOW_STATUS]
    val status = lifecycleStatus ?: metadata[STEP_TYPE] ?: return null
    return JournalEntry(
            status = status,
            stepName = if (lifecycleStatus == null) metadata[STEP_NAME] else null,
            primitive = if (lifecycleStatus == null) metadata[STEP_PRIMITIVE] else null,
            occurredAt = event.timestamp().toEpochMilli(),
    )
}

internal fun sourceEvents(configuration: Configuration, eventStore: EventStore, criteria: EventCriteria): List<EventMessage> {
    val events = mutableListOf<EventMessage>()
    configuration.getComponent(UnitOfWorkFactory::class.java).create().executeWithResult { context ->
        eventStore.transaction(context)
                .source(SourcingCondition.conditionFor(criteria))
                .reduce(Unit) { _, entry -> events += entry.message() }
                .thenApply { events.size }
    }.get()
    return events
}

internal val LIFECYCLE: EventCriteria = EventSourcedRunningWorkflows.criteriaBuilder()
internal val WAITS: EventCriteria = EventCriteria.havingTags(Tag.of(
        WorkflowEventTags.TAG_WORKFLOW_EVENT_TYPE, WorkflowEventTags.TAG_VALUE_EVENT_TYPE_WAIT_STEP,
))

internal const val WORKFLOW_ID = "workflowId"
internal const val WORKFLOW_STATUS = "workflowStatus"
internal const val DEFINITION_NAME = "workflowDefinitionName"
internal const val DEFINITION_VERSION = "workflowDefinitionVersion"
internal const val STEP_TYPE = "stepType"
internal const val STEP_NAME = "stepName"
internal const val STEP_PRIMITIVE = "stepPrimitive"
internal const val VERSION_CHANGE_ID = "versionChangeId"
internal const val WAIT_FOR_EVENT = "WAIT_FOR_EVENT"
