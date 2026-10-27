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
import io.axoniq.framework.workflow.runtime.execution.EventSourcedWorkflowState
import io.axoniq.platform.framework.AxoniqPlatformConfiguration
import io.axoniq.platform.framework.api.DomainEventAccessMode
import io.axoniq.platform.framework.api.Routes
import io.axoniq.platform.framework.api.WorkflowDefinitionMetrics
import io.axoniq.platform.framework.api.WorkflowExecutionEventDescription
import io.axoniq.platform.framework.api.WorkflowExecutionEventsQuery
import io.axoniq.platform.framework.api.WorkflowExecutionEventsResult
import io.axoniq.platform.framework.api.WorkflowExecutionQuery
import io.axoniq.platform.framework.api.WorkflowExecutionResult
import io.axoniq.platform.framework.api.WorkflowExecutionSummary
import io.axoniq.platform.framework.api.WorkflowExecutionsQuery
import io.axoniq.platform.framework.api.WorkflowExecutionsResult
import io.axoniq.platform.framework.api.WorkflowMetricsBucket
import io.axoniq.platform.framework.api.WorkflowMetricsQuery
import io.axoniq.platform.framework.api.WorkflowMetricsResult
import io.axoniq.platform.framework.api.WorkflowStepMetrics
import io.axoniq.platform.framework.client.RSocketHandlerRegistrar
import org.axonframework.common.configuration.Configuration
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.eventsourcing.eventstore.SourcingCondition
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory
import org.axonframework.messaging.eventhandling.EventMessage
import org.axonframework.messaging.eventhandling.conversion.EventConverter
import org.axonframework.messaging.eventstreaming.EventCriteria
import java.time.Instant

/**
 * Answers the workflow execution routes — the list of executions, one execution, its event journal and the metrics —
 * from the application's event store, when asked. Nothing is kept between requests.
 *
 * The engine tags the start and end of every instance as a lifecycle event and every event of an instance with its
 * workflow id. The list and the counts come from the lifecycle events; journals, waits and step metrics from the
 * events of the instances concerned. Every request reads all lifecycle events, so its cost grows with the number of
 * instances the application ever ran.
 */
class WorkflowExecutionsResponder(
        private val registrar: RSocketHandlerRegistrar,
        private val configuration: Configuration,
) {
    fun start() {
        registrar.registerHandlerWithPayload(Routes.Workflow.EXECUTIONS, WorkflowExecutionsQuery::class.java) { executions(it) }
        registrar.registerHandlerWithPayload(Routes.Workflow.EXECUTION, WorkflowExecutionQuery::class.java) { execution(it) }
        registrar.registerHandlerWithPayload(Routes.Workflow.EXECUTION_EVENTS, WorkflowExecutionEventsQuery::class.java) { events(it) }
        registrar.registerHandlerWithPayload(Routes.Workflow.METRICS, WorkflowMetricsQuery::class.java) { metrics(it) }
    }

    fun executions(query: WorkflowExecutionsQuery): WorkflowExecutionsResult {
        require(query.limit in 1..MAX_PAGE && query.offset >= 0 && query.search.length <= 1024) { "Invalid execution page" }
        val search = query.search.lowercase()
        val matching = lifecycles().values
                .filter { query.status == null || it.status == query.status }
                .filter { search.isEmpty() || search in it.workflowId.lowercase() || search in it.definitionName.lowercase() }
                .sortedWith(compareByDescending<Lifecycle, Long?>(nullsFirst()) { it.startedAt }.thenBy { it.workflowId })
        return WorkflowExecutionsResult(
                items = matching.drop(query.offset).take(query.limit).map { it.summary() },
                total = matching.size.toLong(),
        )
    }

    /** Reads only the execution's own events, which include its start and end, so polling one execution stays cheap. */
    fun execution(query: WorkflowExecutionQuery): WorkflowExecutionResult {
        val journal = eventsOf(listOf(query.workflowId))[query.workflowId].orEmpty()
        val lifecycle = lifecyclesOf(journal.map { it.message })[query.workflowId]
                ?: return WorkflowExecutionResult(execution = null, observedSteps = emptyList())
        val steps = journal.mapNotNull { it.stepName }.distinct().sorted()
        return WorkflowExecutionResult(execution = lifecycle.summary(), observedSteps = steps)
    }

    fun events(query: WorkflowExecutionEventsQuery): WorkflowExecutionEventsResult {
        require(query.limit in 1..MAX_PAGE && query.after >= -1) { "Invalid event page" }
        val includePayload = mayShowPayload()
        val page = eventsOf(listOf(query.workflowId))[query.workflowId].orEmpty()
                .filter { it.position > query.after }
                .take(query.limit + 1)
        return WorkflowExecutionEventsResult(
                events = page.take(query.limit).map { event ->
                    WorkflowExecutionEventDescription(
                            eventId = event.message.identifier(),
                            position = event.position,
                            occurredAt = event.occurredAt,
                            status = event.status,
                            stepName = event.stepName,
                            primitive = event.primitive,
                            eventName = event.message.type().qualifiedName().toString(),
                            payloadJson = if (includePayload) payloadJson(event.message) else null,
                    )
                },
                hasMore = page.size > query.limit,
        )
    }

    fun metrics(query: WorkflowMetricsQuery): WorkflowMetricsResult {
        require((query.definitionName == null) == (query.definitionVersion == null)) { "Select a definition and version together" }
        val start = query.timeStart
        val end = minOf(query.timeEnd, Instant.now().epochSecond + 1)
        require(start >= 0 && end > start) { "Invalid workflow metrics time range" }
        require(end - start <= MAX_METRICS_SECONDS) { "Select at most 31 days" }
        val bucketSeconds = maxOf(60L, ((end - start + 299) / 300 + 59) / 60 * 60)
        val window = start * 1000 until end * 1000
        val lifecycles = lifecycles().values
        val open = lifecycles.filter { it.status == WorkflowStatus.STARTED.name }
        val waits = eventsOf(open.map { it.workflowId }).mapValues { (_, events) -> openWaits(events) }

        val registered = configuration.getOptionalComponent(WorkflowDefinitionsResponder::class.java).orElse(null)
                ?.definitions()?.definitions.orEmpty().map { it.name to it.version }
        val byDefinition = lifecycles.groupBy { it.definitionName to it.definitionVersion }
        val workflows = (registered + byDefinition.keys).distinct().sortedWith(compareBy({ it.first }, { it.second }))
                .map { (name, version) ->
                    val instances = byDefinition[name to version].orEmpty()
                    val ended = instances.filter { it.finishedAt != null && it.finishedAt!! in window }
                    WorkflowDefinitionMetrics(
                            definitionName = name,
                            definitionVersion = version,
                            started = instances.count { it.startedAt != null && it.startedAt!! in window }.toLong(),
                            completed = ended.count { it.status == WorkflowStatus.COMPLETED.name }.toLong(),
                            failed = ended.count { it.status == WorkflowStatus.FAILED.name }.toLong(),
                            cancelled = ended.count { it.status == WorkflowStatus.CANCELLED.name }.toLong(),
                            timedOut = ended.count { it.status == WorkflowStatus.TIMED_OUT.name }.toLong(),
                            openInstances = instances.count { it.status == WorkflowStatus.STARTED.name }.toLong(),
                            waitingSteps = instances.sumOf { waits[it.workflowId] ?: 0 }.toLong(),
                            lastReceivedAt = instances.maxOfOrNull { it.lastEventAt } ?: 0,
                    )
                }
        if (query.definitionName == null) {
            return WorkflowMetricsResult(
                    timeStart = start, timeEnd = end, bucketSeconds = bucketSeconds, workflows = workflows,
                    series = emptyList(), steps = emptyList(), observedSteps = emptyList(),
            )
        }
        val instances = byDefinition[query.definitionName to query.definitionVersion].orEmpty()
        val active = instances.filter { (it.startedAt ?: Long.MAX_VALUE) < end * 1000 && (it.finishedAt ?: Long.MAX_VALUE) >= start * 1000 }
        val events = eventsOf(active.map { it.workflowId })
        return WorkflowMetricsResult(
                timeStart = start,
                timeEnd = end,
                bucketSeconds = bucketSeconds,
                workflows = workflows,
                series = series(instances, start, end, bucketSeconds),
                steps = stepMetrics(events, window),
                observedSteps = events.values.flatten().mapNotNull { it.stepName }.distinct().sorted(),
        )
    }

    private fun series(instances: List<Lifecycle>, start: Long, end: Long, bucketSeconds: Long): List<WorkflowMetricsBucket> =
            generateSequence(start) { it + bucketSeconds }.takeWhile { it < end }.map { bucket ->
                val range = bucket * 1000 until minOf(bucket + bucketSeconds, end) * 1000
                val ended = instances.filter { it.finishedAt != null && it.finishedAt!! in range }
                WorkflowMetricsBucket(
                        bucket = Instant.ofEpochSecond(bucket).toString(),
                        seconds = minOf(bucketSeconds, end - bucket),
                        started = instances.count { it.startedAt != null && it.startedAt!! in range }.toLong(),
                        completed = ended.count { it.status == WorkflowStatus.COMPLETED.name }.toLong(),
                        failed = ended.count { it.status == WorkflowStatus.FAILED.name }.toLong(),
                        cancelled = ended.count { it.status == WorkflowStatus.CANCELLED.name }.toLong(),
                        timedOut = ended.count { it.status == WorkflowStatus.TIMED_OUT.name }.toLong(),
                )
            }.toList()

    /**
     * Step events in the window, leaving out what an instance's end does to its open steps. An attempt is measured from
     * the step's previous start (or retry start) to an end of that attempt.
     */
    private fun stepMetrics(events: Map<String, List<WorkflowEvent>>, window: LongRange): List<WorkflowStepMetrics> {
        class Measured(val workflowId: String, val status: String, val duration: Long?)
        val measured = events.flatMap { (workflowId, journal) ->
            val end = journal.indexOfFirst { it.stepName == null && it.status in TERMINAL }.takeIf { it >= 0 } ?: journal.size
            journal.take(end).withIndex()
                    .filter { (_, event) -> event.stepName != null && event.occurredAt in window }
                    .map { (index, event) ->
                        val previous = journal.subList(0, index).lastOrNull { it.stepName == event.stepName }
                        val duration = if (previous != null && previous.status in ATTEMPT_STARTS &&
                                event.status in ATTEMPT_ENDS && event.occurredAt >= previous.occurredAt
                        ) event.occurredAt - previous.occurredAt else null
                        event.stepName!! to Measured(workflowId, event.status, duration)
                    }
        }
        return measured.groupBy({ it.first }, { it.second }).toSortedMap().map { (step, attempts) ->
            val durations = attempts.mapNotNull { it.duration }
            WorkflowStepMetrics(
                    stepName = step,
                    executions = attempts.map { it.workflowId }.distinct().size.toLong(),
                    completed = attempts.count { it.status == "COMPLETED" }.toLong(),
                    failed = attempts.count { it.status == "FAILED" }.toLong(),
                    cancelled = attempts.count { it.status == "CANCELLED" }.toLong(),
                    timedOut = attempts.count { it.status == "TIMED_OUT" }.toLong(),
                    retries = attempts.count { it.status == "RETRY_STARTED" }.toLong(),
                    measuredAttempts = durations.size.toLong(),
                    meanDurationMillis = durations.takeIf { it.isNotEmpty() }?.average(),
            )
        }
    }

    /** Event or timer waits whose latest event is their start. */
    private fun openWaits(journal: List<WorkflowEvent>): Int =
            journal.filter { it.stepName != null }.groupBy { it.stepName }.values
                    .count { steps -> steps.last().let { it.status == "STARTED" && it.primitive == "WAIT_FOR_EVENT" } }

    private class Lifecycle(val workflowId: String) {
        var definitionName = ""
        var definitionVersion = ""
        var status = WorkflowStatus.NONE.name
        var startedAt: Long? = null
        var finishedAt: Long? = null
        var lastEventAt = 0L

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

    private class WorkflowEvent(
            val message: EventMessage,
            val position: Long,
            val occurredAt: Long,
            val status: String,
            val stepName: String?,
            val primitive: String?,
    )

    private fun lifecycles(): Map<String, Lifecycle> = lifecyclesOf(source(EventSourcedRunningWorkflows.criteriaBuilder()))

    private fun lifecyclesOf(events: List<EventMessage>): Map<String, Lifecycle> {
        val lifecycles = LinkedHashMap<String, Lifecycle>()
        events.forEach { event ->
            val metadata = event.metadata()
            val workflowId = metadata[WORKFLOW_ID] ?: return@forEach
            val status = metadata[WORKFLOW_STATUS]?.let { runCatching { WorkflowStatus.valueOf(it) }.getOrNull() } ?: return@forEach
            val occurredAt = event.timestamp().toEpochMilli()
            val lifecycle = lifecycles.getOrPut(workflowId) { Lifecycle(workflowId) }
            metadata[DEFINITION_NAME]?.let { lifecycle.definitionName = it }
            metadata[DEFINITION_VERSION]?.let { lifecycle.definitionVersion = it }
            lifecycle.status = status.name
            lifecycle.lastEventAt = maxOf(lifecycle.lastEventAt, occurredAt)
            when {
                status == WorkflowStatus.STARTED -> lifecycle.startedAt = occurredAt
                status.isTerminal -> lifecycle.finishedAt = occurredAt
            }
        }
        return lifecycles
    }

    /** The journal of each instance: its start, step and end events in the order they were appended. */
    private fun eventsOf(workflowIds: Collection<String>): Map<String, List<WorkflowEvent>> {
        val journals = HashMap<String, MutableList<WorkflowEvent>>()
        workflowIds.distinct().chunked(SOURCING_BATCH).forEach { batch ->
            source(EventCriteria.either(batch.map { EventSourcedWorkflowState.criteriaBuilder(it) })).forEach { event ->
                val metadata = event.metadata()
                val workflowId = metadata[WORKFLOW_ID] ?: return@forEach
                if (metadata.containsKey(VERSION_CHANGE_ID)) return@forEach
                val lifecycleStatus = metadata[WORKFLOW_STATUS]
                val status = lifecycleStatus ?: metadata[STEP_TYPE] ?: return@forEach
                val journal = journals.getOrPut(workflowId) { mutableListOf() }
                journal += WorkflowEvent(
                        message = event,
                        position = journal.size.toLong(),
                        occurredAt = event.timestamp().toEpochMilli(),
                        status = status,
                        stepName = if (lifecycleStatus == null) metadata[STEP_NAME] else null,
                        primitive = if (lifecycleStatus == null) metadata[STEP_PRIMITIVE] else null,
                )
            }
        }
        return journals
    }

    private fun source(criteria: EventCriteria): List<EventMessage> {
        val eventStore = configuration.getOptionalComponent(EventStore::class.java).orElse(null) ?: return emptyList()
        val events = mutableListOf<EventMessage>()
        configuration.getComponent(UnitOfWorkFactory::class.java).create().executeWithResult { context ->
            eventStore.transaction(context)
                    .source(SourcingCondition.conditionFor(criteria))
                    .reduce(Unit) { _, entry -> events += entry.message() }
                    .thenApply { events.size }
        }.get()
        return events
    }

    private fun mayShowPayload(): Boolean {
        val mode = configuration.getOptionalComponent(AxoniqPlatformConfiguration::class.java).orElse(null)
                ?.getDomainEventAccessMode() ?: DomainEventAccessMode.NONE
        return mode == DomainEventAccessMode.FULL || mode == DomainEventAccessMode.PREVIEW_PAYLOAD_ONLY
    }

    private fun payloadJson(event: EventMessage): String? = runCatching {
        configuration.getComponent(EventConverter::class.java).convertPayload<EventMessage, ByteArray>(event, ByteArray::class.java)
                ?.takeIf { it.size <= MAX_PAYLOAD_BYTES }
                ?.toString(Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val MAX_PAGE = 100
        const val MAX_PAYLOAD_BYTES = 16_384
        const val MAX_METRICS_SECONDS = 31L * 24 * 60 * 60
        const val SOURCING_BATCH = 100
        const val WORKFLOW_ID = "workflowId"
        const val WORKFLOW_STATUS = "workflowStatus"
        const val DEFINITION_NAME = "workflowDefinitionName"
        const val DEFINITION_VERSION = "workflowDefinitionVersion"
        const val STEP_TYPE = "stepType"
        const val STEP_NAME = "stepName"
        const val STEP_PRIMITIVE = "stepPrimitive"
        const val VERSION_CHANGE_ID = "versionChangeId"
        val TERMINAL = setOf("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT")
        val ATTEMPT_STARTS = setOf("STARTED", "RETRY_STARTED")
        val ATTEMPT_ENDS = setOf("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT", "RETRYING")
    }
}
