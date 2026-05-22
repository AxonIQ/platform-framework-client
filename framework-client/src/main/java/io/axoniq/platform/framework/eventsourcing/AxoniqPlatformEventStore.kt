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

package io.axoniq.platform.framework.eventsourcing

import io.axoniq.platform.framework.api.metrics.EntityStatisticIdentifier
import io.axoniq.platform.framework.api.metrics.PreconfiguredMetric
import io.axoniq.platform.framework.messaging.HandlerMeasurement
import io.axoniq.platform.framework.messaging.HandlerMeasurement.Companion.RESOURCE_KEY
import io.axoniq.platform.framework.messaging.HandlerMetricsRegistry
import io.axoniq.platform.framework.messaging.toInformation
import io.axoniq.platform.framework.modelling.CurrentEntityContext
import io.axoniq.platform.framework.modelling.EntityMetricsRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
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
import java.util.concurrent.atomic.AtomicLong

class AxoniqPlatformEventStorageEngine(
        private val delegate: EventStorageEngine,
        private val registry: HandlerMetricsRegistry,
        private val entityMetricsRegistry: EntityMetricsRegistry,
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

    override fun source(condition: SourcingCondition): MessageStream<EventMessage> {
        val entity = CurrentEntityContext.getFromThread()
        // Capture the loadOrCreate-shared sourced-events counter at source() invocation time, while
        // we are still on the calling thread. The actual onNext callbacks may fire later/elsewhere
        // but they hold the reference through closure capture, so the count stays accurate.
        val sourcedCounter = CurrentEntityContext.getSourcedEventsCounterFromThread()
        val stream = delegate.source(condition)
        if (entity == null) {
            return stream
        }
        recordCriteriaSize(entity, condition)
        return wrapForStreamSize(entity, stream, sourcedCounter)
    }

    override fun stream(condition: StreamingCondition): MessageStream<EventMessage> {
        val entity = CurrentEntityContext.getFromThread()
        val sourcedCounter = CurrentEntityContext.getSourcedEventsCounterFromThread()
        val stream = delegate.stream(condition)
        if (entity == null) {
            return stream
        }
        recordCriteriaSize(entity, condition.criteria().flatten().size)
        return wrapForStreamSize(entity, stream, sourcedCounter)
    }

    override fun firstToken(): CompletableFuture<TrackingToken> = delegate.firstToken()

    override fun latestToken(): CompletableFuture<TrackingToken> = delegate.latestToken()

    override fun tokenAt(at: Instant): CompletableFuture<TrackingToken> = delegate.tokenAt(at)

    override fun describeTo(descriptor: ComponentDescriptor) {
        descriptor.describeWrapperOf(delegate)
    }

    private fun recordCriteriaSize(entity: EntityStatisticIdentifier, condition: SourcingCondition) {
        recordCriteriaSize(entity, condition.criteria().flatten().size)
    }

    private fun recordCriteriaSize(entity: EntityStatisticIdentifier, criteriaSize: Int) {
        // Timer is time-typed; for count-style metrics we record in MILLISECONDS so the
        // downstream `HistogramSnapshot.toDistribution()` (which reads values in ms) returns
        // the raw count verbatim — e.g. 5 criteria → 5.0. The UI overrides the unit label
        // to display this as "5" rather than "5ms".
        // Skip recording 0: HdrHistogram has no real zero bucket and would report the lowest
        // bucket midpoint (~0.001 with high precision) instead, polluting the distribution.
        if (criteriaSize <= 0) return
        entityMetricsRegistry.registerAdditionalTimer(
                entity,
                EntityMetricsRegistry.METRIC_CRITERIA_SIZE,
                criteriaSize.toLong(),
                TimeUnit.MILLISECONDS,
        )
    }

    private fun wrapForStreamSize(
            entity: EntityStatisticIdentifier,
            stream: MessageStream<EventMessage>,
            sharedSourcedCounter: AtomicLong?,
    ): MessageStream<EventMessage> {
        val counter = AtomicLong(0)
        return stream
                .onNext {
                    counter.incrementAndGet()
                    // Also bump the loadOrCreate-shared counter so the repository can tell load
                    // (count > 0) from creation (count == 0). May be null for direct event-store
                    // streaming outside a managed load.
                    sharedSourcedCounter?.incrementAndGet()
                }
                .onComplete {
                    // Count-style metric; see note in recordCriteriaSize for unit choice.
                    // Skip recording for fresh-entity creations (no events sourced) — that case
                    // belongs to the Creations counter, not the stream-size distribution.
                    val size = counter.get()
                    if (size > 0L) {
                        entityMetricsRegistry.registerAdditionalTimer(
                                entity,
                                EntityMetricsRegistry.METRIC_EVENT_STREAM_SIZE,
                                size,
                                TimeUnit.MILLISECONDS,
                        )
                    }
                }
    }

    companion object {
        private val logger = KotlinLogging.logger { }
    }
}
