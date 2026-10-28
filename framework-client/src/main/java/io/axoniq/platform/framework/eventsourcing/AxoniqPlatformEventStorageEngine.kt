/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.eventsourcing

import io.axoniq.platform.framework.api.metrics.PreconfiguredMetric
import io.axoniq.platform.framework.messaging.HandlerMeasurement
import io.axoniq.platform.framework.messaging.HandlerMeasurement.Companion.RESOURCE_KEY
import io.axoniq.platform.framework.messaging.HandlerMetricsRegistry
import io.axoniq.platform.framework.messaging.toInformation
import io.axoniq.platform.framework.modelling.CurrentEntityContext
import io.axoniq.platform.framework.modelling.EntityMetricsRegistry
import io.axoniq.platform.framework.workflow.WorkflowStatisticsCollector
import org.axonframework.common.infra.ComponentDescriptor
import org.axonframework.eventsourcing.eventstore.AppendCondition
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import org.axonframework.eventsourcing.eventstore.SourcingCondition
import org.axonframework.eventsourcing.eventstore.TaggedEventMessage
import org.axonframework.messaging.core.MessageStream
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.axonframework.messaging.eventhandling.EventMessage
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken
import org.axonframework.messaging.eventstreaming.StreamingCondition
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class AxoniqPlatformEventStorageEngine(
        private val delegate: EventStorageEngine,
        private val registry: HandlerMetricsRegistry,
        private val entityMetricsRegistry: EntityMetricsRegistry,
        private val workflowStatistics: WorkflowStatisticsCollector? = null,
) : EventStorageEngine {

    override fun appendEvents(condition: AppendCondition, context: ProcessingContext?, events: List<TaggedEventMessage<*>>): CompletableFuture<EventStorageEngine.AppendTransaction<*>> {
        // First, report dispatches
        val container = context?.getResource(RESOURCE_KEY)
        if (container == null) {
            events.forEach { tm ->
                val event: EventMessage = tm.event()
                registry.registerMessageDispatchedWithoutHandling(event.toInformation())
            }
        } else {
            events.forEach { tm ->
                val event: EventMessage = tm.event()
                container.reportMessageDispatched(event.toInformation())
            }
        }
        if (context == null) {
            return delegate.appendEvents(condition, context, events)
        }
        workflowStatistics?.let { collector ->
            val workflowEvents = events.map { it.event() }.filter { it.metadata().containsKey(WORKFLOW_ID) }
            if (workflowEvents.isNotEmpty()) {
                context.runOnAfterCommit { collector.onAppended(workflowEvents) }
            }
        }
        // Second, measure commit time if measurement is ongoing
        val startTime = System.nanoTime()
        val currentEntity = context.getResource(CurrentEntityContext.RESOURCE_KEY)
        return delegate.appendEvents(condition, context, events)
                .whenComplete { _, _ ->
                    val endTime = System.nanoTime()
                    HandlerMeasurement.onContext(context) {
                        it.registerMetricValue(PreconfiguredMetric.EVENT_COMMIT_TIME, endTime - startTime)
                    }
                    currentEntity?.let {
                        entityMetricsRegistry.registerAdditionalTimer(
                                it,
                                EntityMetricsRegistry.METRIC_EVENT_COMMIT_TIME,
                                endTime - startTime,
                                TimeUnit.NANOSECONDS,
                        )
                    }
                }
    }

    override fun appendEvents(condition: AppendCondition, context: ProcessingContext?, vararg events: TaggedEventMessage<*>): CompletableFuture<EventStorageEngine.AppendTransaction<*>> {
        return appendEvents(condition, context, events.toList())
    }

    override fun source(condition: SourcingCondition, context: ProcessingContext?): MessageStream<EventMessage> {
        return delegate.source(condition, context)
    }

    override fun stream(condition: StreamingCondition): MessageStream<EventMessage> {
        return delegate.stream(condition)
    }

    override fun firstToken(): CompletableFuture<TrackingToken> = delegate.firstToken()

    override fun latestToken(): CompletableFuture<TrackingToken> = delegate.latestToken()

    override fun tokenAt(at: Instant): CompletableFuture<TrackingToken> = delegate.tokenAt(at)

    override fun describeTo(descriptor: ComponentDescriptor) {
        descriptor.describeWrapperOf(delegate)
    }
}

private const val WORKFLOW_ID = "workflowId"
