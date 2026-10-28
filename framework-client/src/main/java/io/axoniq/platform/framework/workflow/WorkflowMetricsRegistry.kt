/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.workflow

import io.axoniq.framework.workflow.dsl.api.WorkflowStatus
import io.axoniq.platform.framework.api.metrics.WorkflowStatisticIdentifier
import io.axoniq.platform.framework.api.metrics.WorkflowStatistics
import io.axoniq.platform.framework.api.metrics.WorkflowStatisticsWithIdentifier
import io.axoniq.platform.framework.createTimer
import io.axoniq.platform.framework.messaging.toDistribution
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.axonframework.common.configuration.Configuration
import org.axonframework.messaging.eventhandling.EventMessage
import org.axonframework.messaging.eventstreaming.EventCriteria
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.LongAdder

/**
 * Counts what the workflows on this node do, per definition version and per step, from the events this node appends,
 * and measures each attempt from its start to its end. The counts are the ones since the previous report, so Inspector
 * adds up the reports of all nodes.
 *
 * Step events carry only the instance's id, so the definition of an instance comes from [WorkflowInstanceTracker],
 * which also gives the open instances and waits for the report. A step event of an instance the tracker does not know
 * yet, started on another node a moment ago, waits for the tracker's next refresh.
 */
class WorkflowMetricsRegistry(configuration: Configuration) : WorkflowStatisticsCollector {
    private val logger = KotlinLogging.logger { }
    private val tracker = WorkflowInstanceTracker(configuration)
    private val meterRegistry = SimpleMeterRegistry()
    private val statistics = ConcurrentHashMap<WorkflowStatisticIdentifier, Counters>()
    private val attemptStarts = BoundedMap<Pair<String, String>, Long>(MAX_PENDING)
    private val unresolved = BoundedMap<String, EventMessage>(MAX_PENDING)
    private val refreshing = AtomicBoolean(false)
    private val refresher = Executors.newSingleThreadExecutor { Thread(it, "axoniq-platform-workflow-tracker").apply { isDaemon = true } }

    override fun onAppended(events: List<EventMessage>) {
        events.forEach { record(it) }
    }

    override fun drain(): List<WorkflowStatisticsWithIdentifier> {
        unresolved.drain().forEach { record(it) }
        refreshTrackerInBackground()
        val open = tracker.openCounts()
        return (statistics.keys + open.orEmpty().keys).map { key ->
            val counters = statistics[key]
            val measured = counters?.measured?.sumThenReset() ?: 0
            WorkflowStatisticsWithIdentifier(key, WorkflowStatistics(
                    started = counters?.started?.sumThenReset() ?: 0,
                    completed = counters?.completed?.sumThenReset() ?: 0,
                    failed = counters?.failed?.sumThenReset() ?: 0,
                    cancelled = counters?.cancelled?.sumThenReset() ?: 0,
                    timedOut = counters?.timedOut?.sumThenReset() ?: 0,
                    retries = counters?.retries?.sumThenReset() ?: 0,
                    measured = measured,
                    durationMillis = counters?.durationMillis?.sumThenReset() ?: 0,
                    duration = if (measured > 0) counters?.timer?.takeSnapshot()?.toDistribution() else null,
                    open = open?.let { it[key] ?: 0 },
            ))
        }
    }

    private fun refreshTrackerInBackground() {
        if (!refreshing.compareAndSet(false, true)) return
        refresher.execute {
            try {
                tracker.refresh()
            } catch (e: Exception) {
                logger.debug(e) { "Could not read the open workflow instances from the event store" }
            } finally {
                refreshing.set(false)
            }
        }
    }

    private fun record(event: EventMessage) {
        val metadata = event.metadata()
        val workflowId = metadata[WORKFLOW_ID] ?: return
        val entry = journalEntryOf(event) ?: return
        val instance = tracker.instance(workflowId)
        if (entry.stepName == null) {
            val definition = metadata[DEFINITION_NAME]?.let { it to metadata[DEFINITION_VERSION].orEmpty() }
                    ?: instance?.definition ?: return
            val key = WorkflowStatisticIdentifier(definitionName = definition.first, definitionVersion = definition.second)
            countOutcome(key, entry.status)
            if (entry.status in TERMINAL && instance != null) {
                measure(key, entry.occurredAt - instance.startedAt)
            }
        } else {
            if (tracker.hasEnded(workflowId)) return
            if (instance == null) {
                if (event.timestamp().isAfter(Instant.now().minus(MAX_UNRESOLVED_AGE))) {
                    unresolved.put(event.identifier(), event)
                }
                return
            }
            recordStep(workflowId, instance, entry)
        }
        tracker.apply(event)
    }

    private fun recordStep(workflowId: String, instance: WorkflowInstanceTracker.OpenInstance, entry: JournalEntry) {
        val step = entry.stepName!!
        val key = WorkflowStatisticIdentifier(
                definitionName = instance.definition.first,
                definitionVersion = instance.definition.second,
                stepName = step,
        )
        val attempt = workflowId to step
        when (entry.status) {
            "STARTED", "RETRY_STARTED" -> {
                countOutcome(key, entry.status)
                attemptStarts.put(attempt, entry.occurredAt)
            }
            in ATTEMPT_ENDS -> {
                countOutcome(key, entry.status)
                val start = attemptStarts.remove(attempt) ?: instance.waits[step]
                if (start != null && entry.occurredAt >= start) {
                    measure(key, entry.occurredAt - start)
                }
            }
        }
    }

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

    private class Counters(val timer: Timer) {
        val started = LongAdder()
        val completed = LongAdder()
        val failed = LongAdder()
        val cancelled = LongAdder()
        val timedOut = LongAdder()
        val retries = LongAdder()
        val measured = LongAdder()
        val durationMillis = LongAdder()
    }

    private companion object {
        const val MAX_PENDING = 100_000
        val MAX_UNRESOLVED_AGE: Duration = Duration.ofMinutes(10)
        val TERMINAL = setOf("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT")
        val ATTEMPT_ENDS = TERMINAL + "RETRYING"
    }
}

/**
 * The workflow instances that have not ended, with their definition, start and open waits, read from the lifecycle
 * and wait events the engine tags. Every node reads the same events, so every node knows every open instance,
 * wherever it started. Ended instances are forgotten, except for their ids, briefly, so that events arriving after
 * an instance's end are recognised as such.
 */
internal class WorkflowInstanceTracker(configuration: Configuration) {
    private val events = IncrementalSource(configuration, EventCriteria.either(LIFECYCLE, WAITS))
    private val open = ConcurrentHashMap<String, OpenInstance>()
    private val ended = BoundedMap<String, Unit>(MAX_ENDED)

    @Volatile
    private var ready = false

    class OpenInstance(val definition: Pair<String, String>, val startedAt: Long) {
        /** Wait-for-event steps whose latest event is their start, with that start. */
        val waits = ConcurrentHashMap<String, Long>()
    }

    @Synchronized
    fun refresh() {
        events.read { apply(it) }
        ready = true
    }

    fun instance(workflowId: String): OpenInstance? = open[workflowId]

    fun hasEnded(workflowId: String) = ended.contains(workflowId)

    /** Open instances per definition version and waiting instances per wait step; null until first read. */
    fun openCounts(): Map<WorkflowStatisticIdentifier, Long>? {
        if (!ready) return null
        val counts = HashMap<WorkflowStatisticIdentifier, Long>()
        open.values.forEach { instance ->
            val (name, version) = instance.definition
            counts.merge(WorkflowStatisticIdentifier(definitionName = name, definitionVersion = version), 1, Long::plus)
            instance.waits.keys.forEach { step ->
                counts.merge(WorkflowStatisticIdentifier(definitionName = name, definitionVersion = version, stepName = step), 1, Long::plus)
            }
        }
        return counts
    }

    /** Applying an event twice, or a start after its instance ended, leaves the state as it was. */
    fun apply(event: EventMessage) {
        val metadata = event.metadata()
        val workflowId = metadata[WORKFLOW_ID] ?: return
        val lifecycle = metadata[WORKFLOW_STATUS]?.let { runCatching { WorkflowStatus.valueOf(it) }.getOrNull() }
        when {
            lifecycle == WorkflowStatus.STARTED -> if (!ended.contains(workflowId)) {
                val definition = (metadata[DEFINITION_NAME] ?: return) to metadata[DEFINITION_VERSION].orEmpty()
                open.putIfAbsent(workflowId, OpenInstance(definition, event.timestamp().toEpochMilli()))
            }
            lifecycle != null && lifecycle.isTerminal -> {
                ended.put(workflowId, Unit)
                open.remove(workflowId)
            }
            lifecycle == null && metadata[STEP_PRIMITIVE] == WAIT_FOR_EVENT -> {
                val step = metadata[STEP_NAME] ?: return
                val instance = open[workflowId] ?: return
                if (metadata[STEP_TYPE] == "STARTED") {
                    instance.waits[step] = event.timestamp().toEpochMilli()
                } else {
                    instance.waits.remove(step)
                }
            }
        }
    }

    private companion object {
        const val MAX_ENDED = 100_000
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
