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

import io.axoniq.platform.framework.api.ModelDomainEventsQuery
import io.axoniq.platform.framework.api.ModelEntityStateAtSequenceQuery
import io.axoniq.platform.framework.api.ModelTimelineQuery
import io.axoniq.platform.framework.client.RSocketHandlerRegistrar
import io.axoniq.platform.framework.client.strategy.CborJackson3EncodingStrategy
import org.axonframework.common.configuration.AxonConfiguration
import org.axonframework.common.configuration.BaseModule
import org.axonframework.common.configuration.ComponentDefinition
import org.axonframework.common.configuration.Configuration
import org.axonframework.common.configuration.LifecycleRegistry
import org.axonframework.common.configuration.Module
import org.axonframework.eventsourcing.annotation.EventSourcedEntity
import org.axonframework.eventsourcing.annotation.EventTag
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer
import org.axonframework.eventsourcing.annotation.EventSourcingHandler
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule
import org.axonframework.messaging.eventhandling.EventSink
import org.axonframework.messaging.eventhandling.GenericEventMessage
import org.axonframework.messaging.core.MessageType
import org.axonframework.modelling.SimpleStateManager
import org.axonframework.modelling.StateManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * End-to-end test for the model inspection feature: spins up a real
 * [EventSourcingConfigurer] with the inspection enhancer wired in, registers an
 * event-sourced entity in a sub-module, publishes events through the in-memory event
 * store, then drives the responder's query handlers and asserts on their outputs.
 *
 * The submodule structure is what makes this interesting: the entity is registered as a
 * nested module (via [EventSourcedEntityModule.autodetected]) so the enhancer's
 * `doOnSubModules` walker has to drill in to find it. A bug there would cause the
 * registered-entities query to come back empty.
 */
class RSocketModelInspectionResponderIntegrationTest {

    private lateinit var configuration: AxonConfiguration
    private lateinit var responder: RSocketModelInspectionResponder

    @BeforeEach
    fun setUp() {
        // Build a minimal AF5 application:
        //   - InMemoryEventStorageEngine (default)
        //   - one annotated event-sourced entity registered as a sub-module
        //   - a stub RSocketHandlerRegistrar (the inspection enhancer requires its presence,
        //     but we don't exercise the RSocket transport — we call responder methods directly)
        //   - the AxoniqPlatformModelInspectionEnhancer registered manually
        // The AxoniqPlatformModelInspectionEnhancer is auto-discovered via the
        // META-INF/services SPI registration — no need to register it manually.
        configuration = EventSourcingConfigurer.create()
                .registerEntity(EventSourcedEntityModule.autodetected(String::class.java, Reservation::class.java))
                .componentRegistry { cr ->
                    cr.registerComponent(ComponentDefinition.ofType(RSocketHandlerRegistrar::class.java)
                            .withBuilder { RSocketHandlerRegistrar(CborJackson3EncodingStrategy()) })
                }
                .start()

        responder = configuration.getComponent(RSocketModelInspectionResponder::class.java)

        // Publish a known sequence of events for entity "RES-1".
        val sink = configuration.getComponent(EventSink::class.java)
        sink.publish(
                null,
                event(ReservationCreated("RES-1", "alice")),
                event(ReservationConfirmed("RES-1")),
                event(ReservationCancelled("RES-1", "double-booked")),
        ).get()
    }

    @AfterEach
    fun tearDown() {
        configuration.shutdown()
    }

    private fun event(payload: Any) = GenericEventMessage(
            MessageType(payload.javaClass),
            payload,
    )

    // ------------------------------------------------------------------------------------------
    //  Registered entities
    // ------------------------------------------------------------------------------------------

    @Test
    fun `registered entities query discovers entities defined in nested modules`() {
        val result = invokeRegisteredEntities()
        assertEquals(1, result.entities.size, "expected the Reservation entity to be visible")

        val entity = result.entities.first()
        assertEquals(Reservation::class.java.name, entity.entityType)
        assertEquals(1, entity.idTypes.size)
        assertEquals(String::class.java.name, entity.idTypes.first().type)
        // String is a simple id type — no sub-fields surface to the FE.
        assertTrue(entity.idTypes.first().idFields.isEmpty())
    }

    // ------------------------------------------------------------------------------------------
    //  Domain events listing
    // ------------------------------------------------------------------------------------------

    @Test
    fun `domain events query returns the published events in publication order with typed names`() {
        val result = responder.handleDomainEvents(domainEventsQuery("RES-1"))

        assertEquals(3, result.totalCount, "all three events should be returned")
        assertEquals(3, result.domainEvents.size)

        val payloadTypes = result.domainEvents.map { it.payloadType }
        assertEquals(
                listOf(
                        ReservationCreated::class.java.name,
                        ReservationConfirmed::class.java.name,
                        ReservationCancelled::class.java.name,
                ),
                payloadTypes,
        )

        // Sequence numbers are 0-indexed and dense across the listed events.
        assertEquals(listOf(0L, 1L, 2L), result.domainEvents.map { it.sequenceNumber })
    }

    @Test
    fun `domain events query returns empty result for an unknown entity id without throwing`() {
        val result = responder.handleDomainEvents(domainEventsQuery("RES-DOES-NOT-EXIST"))
        assertEquals(0, result.totalCount)
        assertTrue(result.domainEvents.isEmpty())
    }

    // ------------------------------------------------------------------------------------------
    //  Entity state at sequence
    // ------------------------------------------------------------------------------------------

    @Test
    fun `entity state at sequence reconstructs intermediate state by replaying through the metamodel`() {
        // After event 0 (created): status = CREATED, eventCount = 1
        val afterCreated = responder.handleEntityStateAtSequence(stateQuery("RES-1", 0))
        assertNotNull(afterCreated.state)
        assertTrue(afterCreated.state!!.contains("\"status\":\"CREATED\""), afterCreated.state)
        assertTrue(afterCreated.state!!.contains("\"eventCount\":1"), afterCreated.state)

        // After event 1 (confirmed): status = CONFIRMED, eventCount = 2
        val afterConfirmed = responder.handleEntityStateAtSequence(stateQuery("RES-1", 1))
        assertNotNull(afterConfirmed.state)
        assertTrue(afterConfirmed.state!!.contains("\"status\":\"CONFIRMED\""), afterConfirmed.state)
        assertTrue(afterConfirmed.state!!.contains("\"eventCount\":2"), afterConfirmed.state)

        // After event 2 (cancelled): status = CANCELLED, eventCount = 3, reason captured
        val afterCancelled = responder.handleEntityStateAtSequence(stateQuery("RES-1", 2))
        assertNotNull(afterCancelled.state)
        assertTrue(afterCancelled.state!!.contains("\"status\":\"CANCELLED\""), afterCancelled.state)
        assertTrue(afterCancelled.state!!.contains("\"eventCount\":3"), afterCancelled.state)
        assertTrue(afterCancelled.state!!.contains("\"cancelReason\":\"double-booked\""), afterCancelled.state)
    }

    @Test
    fun `entity state at negative sequence replays all events`() {
        val result = responder.handleEntityStateAtSequence(stateQuery("RES-1", -1))
        assertNotNull(result.state)
        assertTrue(result.state!!.contains("\"status\":\"CANCELLED\""))
        assertTrue(result.state!!.contains("\"eventCount\":3"))
    }

    @Test
    fun `entity state for unknown id returns null state`() {
        val result = responder.handleEntityStateAtSequence(stateQuery("RES-MISSING", -1))
        assertNull(result.state)
    }

    // ------------------------------------------------------------------------------------------
    //  Timeline replay
    // ------------------------------------------------------------------------------------------

    @Test
    fun `timeline replay produces stateBefore and stateAfter pairs for every event`() {
        val result = responder.handleTimelineReplay(timelineQuery("RES-1"))

        assertEquals(3, result.totalEvents)
        assertEquals(3, result.entries.size)

        // Event 0 — AF5's factory has just created the entity (defaults applied: status=CREATED,
        // eventCount=0, customerId=null) BEFORE the @EventSourcingHandler runs. So stateBefore
        // captures that just-constructed shape; stateAfter captures the post-handler state with
        // customerId set and eventCount=1.
        val first = result.entries[0]
        assertEquals(0L, first.sequenceNumber)
        assertEquals(ReservationCreated::class.java.name, first.eventType)
        assertNotNull(first.stateBefore)
        assertTrue(first.stateBefore!!.contains("\"eventCount\":0"))
        assertTrue(first.stateBefore!!.contains("\"customerId\":null"))
        assertNotNull(first.stateAfter)
        assertTrue(first.stateAfter!!.contains("\"eventCount\":1"))
        assertTrue(first.stateAfter!!.contains("\"customerId\":\"alice\""))

        // Event 1 — stateBefore = CREATED, stateAfter = CONFIRMED.
        val second = result.entries[1]
        assertEquals(1L, second.sequenceNumber)
        assertTrue(second.stateBefore!!.contains("\"status\":\"CREATED\""))
        assertTrue(second.stateAfter!!.contains("\"status\":\"CONFIRMED\""))

        // Event 2 — stateBefore = CONFIRMED, stateAfter = CANCELLED.
        val third = result.entries[2]
        assertEquals(2L, third.sequenceNumber)
        assertTrue(third.stateBefore!!.contains("\"status\":\"CONFIRMED\""))
        assertTrue(third.stateAfter!!.contains("\"status\":\"CANCELLED\""))
    }

    @Test
    fun `timeline replay honours offset and limit`() {
        val result = responder.handleTimelineReplay(timelineQuery("RES-1", offset = 1, limit = 1))

        // Total still reflects the full stream so the FE can drive pagination.
        assertEquals(3, result.totalEvents)
        assertEquals(1, result.entries.size)
        assertEquals(1L, result.entries.first().sequenceNumber)
        assertEquals(ReservationConfirmed::class.java.name, result.entries.first().eventType)
        assertTrue(result.truncated, "events remain after the requested window")
    }

    // ------------------------------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------------------------------

    private fun invokeRegisteredEntities() = responder.handleRegisteredEntities()

    private fun domainEventsQuery(id: String) = ModelDomainEventsQuery(
            entityType = Reservation::class.java.name,
            entityId = id,
            idType = String::class.java.name,
    )

    private fun stateQuery(id: String, maxSeq: Long) = ModelEntityStateAtSequenceQuery(
            entityType = Reservation::class.java.name,
            entityId = id,
            idType = String::class.java.name,
            maxSequenceNumber = maxSeq,
    )

    private fun timelineQuery(id: String, offset: Int = 0, limit: Int = 100) = ModelTimelineQuery(
            entityType = Reservation::class.java.name,
            entityId = id,
            idType = String::class.java.name,
            offset = offset,
            limit = limit,
    )

    // ------------------------------------------------------------------------------------------
    //  Test fixture: entity + events
    // ------------------------------------------------------------------------------------------

    /**
     * Status enum (declared as a top-level type within the test file) — using an enum gives us
     * a state field whose JSON representation is a clean string we can assert against.
     */
    enum class Status { CREATED, CONFIRMED, CANCELLED }

    /**
     * Mutable event-sourced entity with `@EventSourcingHandler` methods. We mutate in place
     * (the AF5 default for annotated entities) so the test exercises the same dispatch path
     * a real application uses.
     */
    @EventSourcedEntity(tagKey = "reservationId")
    class Reservation @EntityCreator constructor(
            // @InjectEntityId disambiguates the id from the payload parameter — without it,
            // AF5 treats the first ctor arg as the event payload type and no match exists.
            @Suppress("unused") @InjectEntityId val reservationId: String,
    ) {
        var status: Status = Status.CREATED
        var customerId: String? = null
        var cancelReason: String? = null
        var eventCount: Int = 0

        @EventSourcingHandler
        fun on(event: ReservationCreated) {
            customerId = event.customerId
            status = Status.CREATED
            eventCount++
        }

        @EventSourcingHandler
        fun on(@Suppress("unused") event: ReservationConfirmed) {
            status = Status.CONFIRMED
            eventCount++
        }

        @EventSourcingHandler
        fun on(event: ReservationCancelled) {
            status = Status.CANCELLED
            cancelReason = event.reason
            eventCount++
        }
    }

    data class ReservationCreated(
            @field:EventTag(key = "reservationId") val reservationId: String,
            val customerId: String,
    )

    data class ReservationConfirmed(
            @field:EventTag(key = "reservationId") val reservationId: String,
    )

    data class ReservationCancelled(
            @field:EventTag(key = "reservationId") val reservationId: String,
            val reason: String,
    )
}
