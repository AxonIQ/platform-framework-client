/*
 * Copyright (c) 2022-2024. AxonIQ B.V.
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

package io.axoniq.console.framework.messaging

import io.axoniq.console.framework.api.metrics.PreconfiguredMetric
import org.axonframework.common.Registration
import org.axonframework.common.stream.BlockingStream
import org.axonframework.eventhandling.DomainEventMessage
import org.axonframework.eventhandling.EventMessage
import org.axonframework.eventhandling.TrackedEventMessage
import org.axonframework.eventhandling.TrackingToken
import org.axonframework.eventsourcing.eventstore.DomainEventStream
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.messaging.MessageDispatchInterceptor
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

class AxoniqConsoleWrappedEventStore(
    private val delegate: EventStore
) : EventStore {

    override fun storeSnapshot(p0: DomainEventMessage<*>) {
        delegate.storeSnapshot(p0)
    }

    override fun subscribe(messageProcessor: Consumer<MutableList<out EventMessage<*>>>): Registration {
        return delegate.subscribe(messageProcessor)
    }

    override fun registerDispatchInterceptor(dispatchInterceptor: MessageDispatchInterceptor<in EventMessage<*>>): Registration {
        return delegate.registerDispatchInterceptor(dispatchInterceptor)
    }

    override fun publish(events: MutableList<out EventMessage<*>>) {
        return delegate.publish(events)
    }

    override fun openStream(trackingToken: TrackingToken?): BlockingStream<TrackedEventMessage<*>> {
        return delegate.openStream(trackingToken)
    }

    override fun readEvents(aggregateIdentifier: String): DomainEventStream {
        val result = delegate.readEvents(aggregateIdentifier)
        val count = AtomicLong()
        registerAggregateEventsSizeOnPrepareCommit { count.get() }
        return DomainEventStream.of(result.asStream().peek { count.incrementAndGet() }) { result.lastSequenceNumber }
    }

    override fun readEvents(aggregateIdentifier: String, firstSequenceNumber: Long): DomainEventStream {
        val result = delegate.readEvents(aggregateIdentifier, firstSequenceNumber)
        registerAggregateEventsSizeOnPrepareCommit { result.lastSequenceNumber?.plus(1) ?: 0 }
        return result
    }

    /**
     * The returned stream is consumed lazily by the repository, so the size is only known once the unit of work
     * prepares its commit. The span is captured up front, as it may no longer be the current one by then.
     */
    private fun registerAggregateEventsSizeOnPrepareCommit(size: () -> Long) {
        val span = AxoniqConsoleSpanFactory.currentSpan() ?: return
        CurrentUnitOfWork.ifStarted { uow ->
            uow.onPrepareCommit {
                span.registerMetricValue(PreconfiguredMetric.AGGREGATE_EVENTS_SIZE, size())
            }
        }
    }
}
