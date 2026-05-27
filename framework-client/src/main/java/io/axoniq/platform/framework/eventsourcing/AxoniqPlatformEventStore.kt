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
import io.axoniq.platform.framework.modelling.CurrentEntityContext
import io.axoniq.platform.framework.modelling.EntityMetricsRegistry
import org.axonframework.common.Registration
import org.axonframework.common.infra.ComponentDescriptor
import org.axonframework.eventsourcing.eventstore.ConsistencyMarker
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.eventsourcing.eventstore.EventStoreTransaction
import org.axonframework.eventsourcing.eventstore.Position
import org.axonframework.eventsourcing.eventstore.SourcingCondition
import org.axonframework.messaging.core.MessageStream
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.axonframework.messaging.eventhandling.EventMessage
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken
import org.axonframework.messaging.eventstreaming.StreamingCondition
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.function.BiFunction
import java.util.function.Consumer

class AxoniqPlatformEventStore(private val delegate: EventStore, private val entityMetricsRegistry: EntityMetricsRegistry) : EventStore {
    override fun transaction(processingContext: ProcessingContext): EventStoreTransaction {
        return AxoniqPlatformEventStoreTransaction(
                delegate.transaction(processingContext), processingContext
        )
    }

    override fun open(condition: StreamingCondition, context: ProcessingContext?): MessageStream<EventMessage> {
        return delegate.open(condition, context)
    }

    override fun firstToken(context: ProcessingContext?): CompletableFuture<TrackingToken> {
        return delegate.firstToken(context)
    }

    override fun latestToken(context: ProcessingContext?): CompletableFuture<TrackingToken> {
        return delegate.latestToken(context)
    }

    override fun tokenAt(at: Instant, context: ProcessingContext?): CompletableFuture<TrackingToken> {
        return delegate.tokenAt(at, context)
    }

    override fun subscribe(eventsBatchConsumer: BiFunction<List<out EventMessage>, ProcessingContext, CompletableFuture<*>>): Registration {
        return delegate.subscribe(eventsBatchConsumer)
    }

    override fun publish(context: ProcessingContext?, events: List<out EventMessage>): CompletableFuture<Void> {
        return delegate.publish(context, events)
    }

    override fun describeTo(descriptor: ComponentDescriptor) {
        delegate.describeTo(descriptor)
    }

    inner class AxoniqPlatformEventStoreTransaction(
            private val delegate: EventStoreTransaction,
            private val processingContext: ProcessingContext): EventStoreTransaction {
        override fun source(condition: SourcingCondition, resumePositionCallback: Consumer<Position>?): MessageStream<out EventMessage> {
            val entity = processingContext.getResource(CurrentEntityContext.RESOURCE_KEY)
            if (entity == null) {
                return delegate.source(condition, resumePositionCallback)
            }
            recordCriteriaSize(entity, condition.criteria().flatten().size)
            return wrapForStreamSize(entity, delegate.source(condition, resumePositionCallback))
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
                stream: MessageStream<out EventMessage>,
        ): MessageStream<out EventMessage> {
            val counter = AtomicLong(0)
            return stream
                    .onNext { counter.incrementAndGet() }
                    .onComplete {
                        // Count-style metric; see note in recordCriteriaSize for unit choice.
                        // Skip recording when no events were sourced — a fresh entity has nothing
                        // meaningful to contribute to the stream-size distribution.
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


        override fun appendEvent(eventMessage: EventMessage) {
            delegate.appendEvent(eventMessage)
        }

        override fun onAppend(callback: Consumer<EventMessage>) {
            delegate.onAppend(callback)
        }

        override fun appendPosition(): ConsistencyMarker {
            return delegate.appendPosition()
        }

    }
}