/*
 * Copyright (c) 2022-2026. Axoniq B.V.
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

import org.axonframework.eventhandling.GenericDomainEventMessage
import org.axonframework.eventsourcing.EventSourcingHandler
import org.axonframework.eventsourcing.EventSourcingRepository
import org.axonframework.eventsourcing.eventstore.DomainEventStream
import org.axonframework.eventsourcing.eventstore.EmbeddedEventStore
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine
import org.axonframework.messaging.unitofwork.DefaultUnitOfWork
import org.axonframework.modelling.command.AggregateIdentifier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AxoniqConsoleWrappedEventStoreTest {

    private val delegate = EmbeddedEventStore.builder()
        .storageEngine(UpcasterFilteringStorageEngine())
        .build()
    private val wrapped = AxoniqConsoleWrappedEventStore(delegate)

    @AfterEach
    fun tearDown() {
        delegate.shutDown()
    }

    @Test
    fun `Aggregate is initialized at the last sequence number of the stream when trailing events are filtered out`() {
        storeEvents(kept = 3, filtered = 2)

        val repository = EventSourcingRepository.builder(TestAggregate::class.java)
            .eventStore(wrapped)
            .build<EventSourcingRepository<TestAggregate>>()
        val uow = DefaultUnitOfWork.startAndGet(null)
        try {
            val aggregate = repository.load(AGGREGATE_ID)
            assertEquals(3, aggregate.invoke { it: TestAggregate -> it.appliedEvents })
            assertEquals(4L, aggregate.version())
        } finally {
            uow.rollback()
        }
    }

    @Test
    fun `Stream read from a sequence number keeps the last sequence number of the delegate`() {
        storeEvents(kept = 3, filtered = 2)

        val stream = wrapped.readEvents(AGGREGATE_ID, 1)
        stream.asStream().forEach { }

        assertEquals(4L, stream.lastSequenceNumber)
    }

    private fun storeEvents(kept: Int, filtered: Int) {
        val payloads = List(kept) { KeptEvent() } + List(filtered) { FilteredEvent() }
        delegate.publish(payloads.mapIndexed { index, payload ->
            GenericDomainEventMessage("TestAggregate", AGGREGATE_ID, index.toLong(), payload)
        })
    }

    /**
     * Drops [FilteredEvent]s while reporting the last sequence number of the stored stream, the same way the stream
     * returned by Axon Server behaves when an upcaster removes events.
     */
    private class UpcasterFilteringStorageEngine : InMemoryEventStorageEngine() {
        override fun readEvents(aggregateIdentifier: String, firstSequenceNumber: Long): DomainEventStream {
            val stored = super.readEvents(aggregateIdentifier, firstSequenceNumber)
            return DomainEventStream.of(
                stored.asStream().filter { it.payload !is FilteredEvent }
            ) { stored.lastSequenceNumber }
        }
    }

    class KeptEvent
    class FilteredEvent

    class TestAggregate() {
        @AggregateIdentifier
        private var id: String = AGGREGATE_ID
        var appliedEvents = 0

        @EventSourcingHandler
        fun on(event: KeptEvent) {
            appliedEvents++
        }
    }

    companion object {
        private const val AGGREGATE_ID = "aggregate-1"
    }
}
