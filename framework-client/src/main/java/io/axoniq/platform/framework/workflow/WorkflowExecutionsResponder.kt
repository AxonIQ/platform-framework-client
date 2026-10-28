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
import io.axoniq.platform.framework.AxoniqPlatformConfiguration
import io.axoniq.platform.framework.api.DomainEventAccessMode
import io.axoniq.platform.framework.api.Routes
import io.axoniq.platform.framework.api.WorkflowExecutionEventDescription
import io.axoniq.platform.framework.api.WorkflowExecutionEventsQuery
import io.axoniq.platform.framework.api.WorkflowExecutionEventsResult
import io.axoniq.platform.framework.api.WorkflowExecutionQuery
import io.axoniq.platform.framework.api.WorkflowExecutionResult
import io.axoniq.platform.framework.api.WorkflowExecutionsQuery
import io.axoniq.platform.framework.api.WorkflowExecutionsResult
import io.axoniq.platform.framework.client.RSocketHandlerRegistrar
import org.axonframework.common.configuration.Configuration
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.messaging.eventhandling.EventMessage
import org.axonframework.messaging.eventhandling.conversion.EventConverter

/**
 * Answers the workflow execution routes — the list of executions, one execution and its event journal — from the
 * application's event store, when asked. Workflow metrics travel in the statistics report instead, see
 * [WorkflowMetricsRegistry].
 *
 * The list comes from a [WorkflowExecutionsProjection] that each request brings up to date with the lifecycle events
 * appended since the previous one. One execution and its journal are read from that execution's own events,
 * which the engine tags with its workflow id, so they show its payloads and stay cheap to poll.
 */
class WorkflowExecutionsResponder(
        private val registrar: RSocketHandlerRegistrar,
        private val configuration: Configuration,
) {
    private val projection = WorkflowExecutionsProjection(configuration)

    fun start() {
        registrar.registerHandlerWithPayload(Routes.Workflow.EXECUTIONS, WorkflowExecutionsQuery::class.java) { executions(it) }
        registrar.registerHandlerWithPayload(Routes.Workflow.EXECUTION, WorkflowExecutionQuery::class.java) { execution(it) }
        registrar.registerHandlerWithPayload(Routes.Workflow.EXECUTION_EVENTS, WorkflowExecutionEventsQuery::class.java) { events(it) }
    }

    fun executions(query: WorkflowExecutionsQuery): WorkflowExecutionsResult {
        require(query.limit in 1..MAX_PAGE && query.offset >= 0 && query.search.length <= 1024) { "Invalid execution page" }
        val search = query.search.lowercase()
        return projection.read { instances ->
            val matching = instances
                    .filter { query.status == null || it.status == query.status }
                    .filter { search.isEmpty() || search in it.workflowId.lowercase() || search in it.definitionName.lowercase() }
                    .sortedWith(compareByDescending<WorkflowInstance, Long?>(nullsFirst()) { it.startedAt }.thenBy { it.workflowId })
            WorkflowExecutionsResult(
                    items = matching.drop(query.offset).take(query.limit).map { it.summary() },
                    total = matching.size.toLong(),
            )
        }
    }

    /** Reads only the execution's own events, which include its start and end, so polling one execution stays cheap. */
    fun execution(query: WorkflowExecutionQuery): WorkflowExecutionResult {
        val journal = eventsOf(query.workflowId)
        val instance = WorkflowInstance(query.workflowId).apply { journal.forEach { apply(it.message) } }
        if (instance.status == WorkflowStatus.NONE.name) {
            return WorkflowExecutionResult(execution = null, observedSteps = emptyList())
        }
        val steps = journal.mapNotNull { it.entry.stepName }.distinct().sorted()
        return WorkflowExecutionResult(execution = instance.summary(), observedSteps = steps)
    }

    fun events(query: WorkflowExecutionEventsQuery): WorkflowExecutionEventsResult {
        require(query.limit in 1..MAX_PAGE && query.after >= -1) { "Invalid event page" }
        val includePayload = mayShowPayload()
        val page = eventsOf(query.workflowId)
                .filter { it.position > query.after }
                .take(query.limit + 1)
        return WorkflowExecutionEventsResult(
                events = page.take(query.limit).map { event ->
                    WorkflowExecutionEventDescription(
                            eventId = event.message.identifier(),
                            position = event.position,
                            occurredAt = event.entry.occurredAt,
                            status = event.entry.status,
                            stepName = event.entry.stepName,
                            primitive = event.entry.primitive,
                            eventName = event.message.type().qualifiedName().toString(),
                            payloadJson = if (includePayload) payloadJson(event.message) else null,
                    )
                },
                hasMore = page.size > query.limit,
        )
    }

    private class WorkflowEvent(val message: EventMessage, val position: Long, val entry: JournalEntry)

    /** The journal of one instance: its start, step and end events in the order they were appended. */
    private fun eventsOf(workflowId: String): List<WorkflowEvent> {
        val eventStore = configuration.getOptionalComponent(EventStore::class.java).orElse(null) ?: return emptyList()
        return sourceEvents(configuration, eventStore, EventSourcedWorkflowState.criteriaBuilder(workflowId))
                .mapNotNull { event -> journalEntryOf(event)?.let { event to it } }
                .mapIndexed { position, (event, entry) -> WorkflowEvent(event, position.toLong(), entry) }
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
    }
}
