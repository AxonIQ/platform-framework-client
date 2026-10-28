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
import io.axoniq.platform.framework.api.WorkflowExecutionSummary
import org.axonframework.common.configuration.Configuration
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.eventsourcing.eventstore.GlobalIndexPosition
import org.axonframework.eventsourcing.eventstore.Position
import org.axonframework.eventsourcing.eventstore.SourcingCondition
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory
import org.axonframework.messaging.eventhandling.EventMessage
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken
import org.axonframework.messaging.eventstreaming.EventCriteria
import org.axonframework.messaging.eventstreaming.Tag

/**
 * The start and end of every workflow instance the application ran, kept in memory and brought up to date on every
 * read with the lifecycle events appended since the previous read.
 */
internal class WorkflowExecutionsProjection(configuration: Configuration) {
    private val lifecycleEvents = IncrementalSource(configuration, LIFECYCLE)
    private val instances = LinkedHashMap<String, WorkflowInstance>()

    @Synchronized
    fun <T> read(read: (Collection<WorkflowInstance>) -> T): T {
        lifecycleEvents.read { event ->
            event.metadata()[WORKFLOW_ID]?.let { instances.getOrPut(it) { WorkflowInstance(it) }.apply(event) }
        }
        return read(instances.values)
    }
}

/**
 * Sources the events matching [criteria] that were appended since the previous [read].
 *
 * The next read starts after the last event read, not at the resume position the store reports: under concurrent
 * appends that position can lie past events that were not visible yet, which would then never be read.
 */
internal class IncrementalSource(private val configuration: Configuration, private val criteria: EventCriteria) {
    private var position: Position = Position.START

    fun read(consumer: (EventMessage) -> Unit) {
        val eventStore = configuration.getOptionalComponent(EventStore::class.java).orElse(null) ?: return
        var next: Position? = null
        configuration.getComponent(UnitOfWorkFactory::class.java).create().executeWithResult { context ->
            eventStore.transaction(context)
                    .source(SourcingCondition.conditionFor(position, criteria))
                    .reduce(Unit) { _, entry ->
                        consumer(entry.message())
                        TrackingToken.fromContext(entry)
                                .map { (it as? GlobalSequenceTrackingToken)?.globalIndex }
                                .ifPresent { next = GlobalIndexPosition(it) }
                    }
                    .thenApply { true }
        }.get()
        next?.let { position = it }
    }
}

/** One instance as its start and end events record it. */
internal class WorkflowInstance(val workflowId: String) {
    var definitionName = ""
    var definitionVersion = ""
    var status = WorkflowStatus.NONE.name
    var startedAt: Long? = null
    var finishedAt: Long? = null
    var lastEventAt = 0L

    fun apply(event: EventMessage) {
        val metadata = event.metadata()
        val lifecycle = metadata[WORKFLOW_STATUS]?.let { runCatching { WorkflowStatus.valueOf(it) }.getOrNull() } ?: return
        val occurredAt = event.timestamp().toEpochMilli()
        metadata[DEFINITION_NAME]?.let { definitionName = it }
        metadata[DEFINITION_VERSION]?.let { definitionVersion = it }
        status = lifecycle.name
        lastEventAt = maxOf(lastEventAt, occurredAt)
        when {
            lifecycle == WorkflowStatus.STARTED -> startedAt = occurredAt
            lifecycle.isTerminal -> finishedAt = occurredAt
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
