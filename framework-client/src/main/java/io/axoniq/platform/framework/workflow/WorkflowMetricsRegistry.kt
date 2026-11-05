/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.workflow

import io.axoniq.framework.workflow.dsl.api.WorkflowStatus
import io.axoniq.framework.workflow.runtime.execution.EventSourcedWorkflowState
import io.axoniq.platform.framework.api.metrics.WorkflowStatisticIdentifier
import io.axoniq.platform.framework.api.metrics.WorkflowStatistics
import io.axoniq.platform.framework.api.metrics.WorkflowStatisticsWithIdentifier
import io.axoniq.platform.framework.createTimer
import io.axoniq.platform.framework.messaging.toDistribution
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.axonframework.common.configuration.Configuration
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.messaging.eventhandling.EventMessage
import org.axonframework.messaging.eventstreaming.EventCriteria
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * Counts what the workflows on this node do, per definition version and per step, from the events this node appends,
 * and measures each attempt from its start to its end. Every report carries the totals since the counting of each
 * workflow or step began on this node, so a report that is lost only delays the counts: Inspector takes the difference
 * between reports of the same node.
 *
 * Step events carry only the instance's id. The definition of an instance comes from [WorkflowExecutionsProjection],
 * which also gives the open instances and waits for the report. An event of an instance it does not know, because
 * it started on another node or before this node, is resolved in the background from that instance's own events.
 * All of an instance's metrics count towards the version it started with, also after a version migration.
 */
internal class WorkflowMetricsRegistry(
        configuration: Configuration,
        private val projection: WorkflowExecutionsProjection,
) : WorkflowStatisticsCollector {
    private val logger = KotlinLogging.logger { }
    private val instances = InstanceSource(configuration)
    private val meterRegistry = SimpleMeterRegistry()
    private val startedAt = Instant.now().epochSecond
    private val statistics = ConcurrentHashMap<WorkflowStatisticIdentifier, Counters>()
    private val attemptStarts = BoundedMap<Pair<String, String>, Long>(MAX_PENDING)
    private val unresolved = BoundedMap<String, EventMessage>(MAX_PENDING)
    private val working = AtomicBoolean(false)
    private val background = Executors.newSingleThreadExecutor { Thread(it, "axoniq-platform-workflows").apply { isDaemon = true } }

    override fun onAppended(events: List<EventMessage>) {
        events.forEach { record(it) }
    }

    override fun drain(): List<WorkflowStatisticsWithIdentifier> {
        workInBackground()
        val open = projection.openCounts()
        return (statistics.keys + open.orEmpty().keys).map { key ->
            val counters = statistics[key]
            val measured = counters?.measured?.sum() ?: 0
            val newlyMeasured = counters != null && counters.reportedMeasured.getAndSet(measured) != measured
            WorkflowStatisticsWithIdentifier(key, WorkflowStatistics(
                    started = counters?.started?.sum() ?: 0,
                    completed = counters?.completed?.sum() ?: 0,
                    failed = counters?.failed?.sum() ?: 0,
                    cancelled = counters?.cancelled?.sum() ?: 0,
                    timedOut = counters?.timedOut?.sum() ?: 0,
                    retries = counters?.retries?.sum() ?: 0,
                    measured = measured,
                    durationMillis = counters?.durationMillis?.sum() ?: 0,
                    duration = if (newlyMeasured) counters?.timer?.takeSnapshot()?.toDistribution() else null,
                    open = open?.let { it[key] ?: 0 },
                    since = counters?.since ?: startedAt,
            ))
        }
    }

    private fun workInBackground() {
        if (!working.compareAndSet(false, true)) return
        background.execute {
            try {
                projection.refresh()
                resolve(unresolved.drain())
            } catch (e: Exception) {
                logger.debug(e) { "Could not read workflow instances from the event store" }
            } finally {
                working.set(false)
            }
        }
    }

    private fun record(event: EventMessage) {
        val workflowId = event.metadata()[WORKFLOW_ID] ?: return
        val entry = journalEntryOf(event) ?: return
        if (entry.stepName == null && entry.status == WorkflowStatus.STARTED.name) {
            val definition = (event.metadata()[DEFINITION_NAME] ?: return) to event.metadata()[DEFINITION_VERSION].orEmpty()
            countOutcome(key(definition, null), entry.status)
        } else {
            val instance = projection.running(workflowId)
            if (instance == null) {
                unresolved.put(event.identifier(), event)
                return
            }
            val definition = instance.definitionName to instance.definitionVersion
            record(workflowId, Instance(definition, instance.startedAt ?: entry.occurredAt, instance.waits), entry, attemptStarts)
        }
        projection.apply(event)
    }

    /**
     * Reads the instances of events this node could not place, and counts those events where they stand in the
     * instance's journal. Steps after the instance's end are left out: they are what ending it did to its open steps.
     */
    private fun resolve(events: List<EventMessage>) {
        if (events.isEmpty()) return
        val byInstance = events.groupBy { it.metadata()[WORKFLOW_ID]!! }
        byInstance.keys.chunked(SOURCING_BATCH).forEach { batch ->
            val journals = instances.journals(batch)
            batch.forEach { workflowId ->
                val journal = journals[workflowId].orEmpty()
                val started = journal.firstOrNull { it.entry.stepName == null && it.entry.status == WorkflowStatus.STARTED.name }
                val definition = started?.definition ?: return@forEach
                val instance = Instance(definition, started.entry.occurredAt)
                val starts = BoundedMap<Pair<String, String>, Long>(MAX_PENDING)
                val pending = byInstance.getValue(workflowId).mapTo(HashSet()) { it.identifier() }
                var ended = false
                journal.forEach { recorded ->
                    val entry = recorded.entry
                    if (ended && entry.stepName != null) return@forEach
                    if (recorded.identifier in pending) {
                        record(workflowId, instance, entry, starts)
                    } else if (entry.status in ATTEMPT_STARTS && entry.stepName != null) {
                        starts.put(workflowId to entry.stepName, entry.occurredAt)
                    }
                    ended = ended || (entry.stepName == null && entry.status in TERMINAL)
                }
            }
        }
    }

    private fun record(workflowId: String, instance: Instance, entry: JournalEntry, starts: BoundedMap<Pair<String, String>, Long>) {
        val step = entry.stepName
        if (step == null) {
            val key = key(instance.definition, null)
            countOutcome(key, entry.status)
            if (entry.status in TERMINAL && entry.occurredAt >= instance.startedAt) {
                measure(key, entry.occurredAt - instance.startedAt)
            }
            return
        }
        val key = key(instance.definition, step)
        val attempt = workflowId to step
        when (entry.status) {
            in ATTEMPT_STARTS -> {
                countOutcome(key, entry.status)
                starts.put(attempt, entry.occurredAt)
            }
            in ATTEMPT_ENDS -> {
                countOutcome(key, entry.status)
                val start = starts.remove(attempt) ?: instance.waits[step]
                if (start != null && entry.occurredAt >= start) {
                    measure(key, entry.occurredAt - start)
                }
            }
        }
    }

    private fun key(definition: Pair<String, String>, step: String?) =
            WorkflowStatisticIdentifier(definitionName = definition.first, definitionVersion = definition.second, stepName = step)

    private fun countOutcome(key: WorkflowStatisticIdentifier, status: String) {
        val counters = statistics.computeIfAbsent(key) { Counters(createTimer(meterRegistry, "${it}_timer")) }
        when (status) {
            "STARTED" -> counters.started
            "COMPLETED" -> counters.completed
            "FAILED" -> counters.failed
            "CANCELLED" -> counters.cancelled
            "TIMED_OUT" -> counters.timedOut
            "RETRY_STARTED" -> counters.retries
            else -> null
        }?.increment()
    }

    private fun measure(key: WorkflowStatisticIdentifier, millis: Long) {
        val counters = statistics[key] ?: return
        counters.timer.record(millis, TimeUnit.MILLISECONDS)
        counters.measured.increment()
        counters.durationMillis.add(millis)
    }

    private class Instance(val definition: Pair<String, String>, val startedAt: Long, val waits: Map<String, Long> = emptyMap())

    private class Counters(val timer: Timer) {
        /** When this node began counting the workflow or step, which is at its first event, not at startup. */
        val since = Instant.now().epochSecond
        val started = LongAdder()
        val completed = LongAdder()
        val failed = LongAdder()
        val cancelled = LongAdder()
        val timedOut = LongAdder()
        val retries = LongAdder()
        val measured = LongAdder()
        val durationMillis = LongAdder()
        val reportedMeasured = AtomicLong()
    }

    private companion object {
        const val MAX_PENDING = 100_000
        const val SOURCING_BATCH = 100
        val TERMINAL = setOf("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT")
        val ATTEMPT_STARTS = setOf("STARTED", "RETRY_STARTED")
        val ATTEMPT_ENDS = TERMINAL + "RETRYING"
    }
}

/** The events of given instances, read by the workflow id the engine tags every one of them with. */
private class InstanceSource(private val configuration: Configuration) {

    class Recorded(val identifier: String, val entry: JournalEntry, val definition: Pair<String, String>?)

    fun journals(workflowIds: List<String>): Map<String, List<Recorded>> {
        val eventStore = configuration.getOptionalComponent(EventStore::class.java).orElse(null) ?: return emptyMap()
        return sourceEvents(configuration, eventStore, EventCriteria.either(workflowIds.map { EventSourcedWorkflowState.criteriaBuilder(it) }))
                .mapNotNull { event ->
                    val entry = journalEntryOf(event) ?: return@mapNotNull null
                    val definition = event.metadata()[DEFINITION_NAME]?.let { it to event.metadata()[DEFINITION_VERSION].orEmpty() }
                    event.metadata()[WORKFLOW_ID]?.let { it to Recorded(event.identifier(), entry, definition) }
                }
                .groupBy({ it.first }, { it.second })
    }
}

/** An insertion-ordered map that drops its oldest entries beyond [capacity]. */
internal class BoundedMap<K, V>(private val capacity: Int) {
    private val entries = object : LinkedHashMap<K, V>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > capacity
    }

    @Synchronized
    fun put(key: K, value: V) {
        entries[key] = value
    }

    @Synchronized
    fun remove(key: K): V? = entries.remove(key)

    @Synchronized
    fun contains(key: K) = entries.containsKey(key)

    @Synchronized
    fun drain(): List<V> = entries.values.toList().also { entries.clear() }
}
