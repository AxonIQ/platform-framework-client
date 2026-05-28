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

import io.axoniq.platform.framework.AxoniqPlatformConfiguration
import io.axoniq.platform.framework.api.DomainEventAccessMode
import io.axoniq.platform.framework.api.ModelDomainEventsQuery
import io.axoniq.platform.framework.api.ModelEntityStateAtSequenceQuery
import io.axoniq.platform.framework.api.ModelTimelineQuery
import io.axoniq.platform.framework.client.RSocketHandlerRegistrar
import io.axoniq.platform.framework.client.strategy.CborJackson3EncodingStrategy
import org.axonframework.common.configuration.AxonConfiguration
import org.axonframework.common.configuration.ComponentDefinition
import org.axonframework.eventsourcing.annotation.EventSourcedEntity
import org.axonframework.eventsourcing.annotation.EventTag
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer
import org.axonframework.eventsourcing.annotation.EventSourcingHandler
import org.axonframework.messaging.eventhandling.EventSink
import org.axonframework.messaging.eventhandling.GenericEventMessage
import org.axonframework.messaging.core.MessageType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
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
 *
 * Tests are grouped per endpoint via [Nested] so the surefire report mirrors the routes
 * the responder handles: registered-entities, domain-events, state-at-sequence, timeline.
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
        //   - an AxoniqPlatformConfiguration with FULL access mode so privacy gating doesn't
        //     null out payload/state in the happy-path assertions below.
        // The AxoniqPlatformModelInspectionEnhancer is auto-discovered via the
        // META-INF/services SPI registration — no need to register it manually.
        configuration = EventSourcingConfigurer.create()
                .registerEntity(EventSourcedEntityModule.autodetected(String::class.java, Reservation::class.java))
                .componentRegistry { cr ->
                    cr.registerComponent(ComponentDefinition.ofType(RSocketHandlerRegistrar::class.java)
                            .withBuilder { RSocketHandlerRegistrar(CborJackson3EncodingStrategy()) })
                    // Default to FULL in tests: most assertions below exercise the happy-path
                    // payload + state visibility. Access-mode gating itself is covered by the
                    // dedicated Nested AccessModeGates group in the helpers test.
                    cr.registerComponent(ComponentDefinition.ofType(AxoniqPlatformConfiguration::class.java)
                            .withBuilder { testPlatformConfig(DomainEventAccessMode.FULL) })
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

    @Nested
    inner class RegisteredEntities {

        @Test
        fun `query discovers entities defined in nested modules`() {
            val result = responder.handleRegisteredEntities()
            assertEquals(1, result.entities.size, "expected the Reservation entity to be visible")

            val entity = result.entities.first()
            assertEquals(Reservation::class.java.name, entity.entityType)
            assertEquals(1, entity.idTypes.size)
            assertEquals(String::class.java.name, entity.idTypes.first().type)
            // String is a simple id type — no sub-fields surface to the FE.
            assertTrue(entity.idTypes.first().idFields.isEmpty())
        }
    }

    @Nested
    inner class DomainEvents {

        @Test
        fun `query returns the published events in publication order with typed names`() {
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
        fun `query returns empty result for an unknown entity id without throwing`() {
            val result = responder.handleDomainEvents(domainEventsQuery("RES-DOES-NOT-EXIST"))
            assertEquals(0, result.totalCount)
            assertTrue(result.domainEvents.isEmpty())
        }
    }

    @Nested
    inner class EntityStateAtSequence {

        @Test
        fun `reconstructs intermediate state by replaying through the metamodel`() {
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
        fun `negative sequence replays all events`() {
            val result = responder.handleEntityStateAtSequence(stateQuery("RES-1", -1))
            assertNotNull(result.state)
            assertTrue(result.state!!.contains("\"status\":\"CANCELLED\""))
            assertTrue(result.state!!.contains("\"eventCount\":3"))
        }

        @Test
        fun `unknown id returns null state`() {
            val result = responder.handleEntityStateAtSequence(stateQuery("RES-MISSING", -1))
            assertNull(result.state)
        }
    }

    @Nested
    inner class TimelineReplay {

        @Test
        fun `sends stateBefore only on the first entry and chains state via stateAfter for the rest`() {
            // Wire-level optimization (Mitchell #146 follow-up): the responder drops stateBefore on
            // every entry past the first because it equals the previous entry's stateAfter by
            // definition of event sourcing. The FE rehydrates it during consumption. Here we
            // assert both halves of that contract: leading entry carries the value, the rest are
            // null, and the chain of stateAfter values matches the expected state evolution.
            val result = responder.handleTimelineReplay(timelineQuery("RES-1"))

            assertEquals(3, result.totalEvents)
            assertEquals(3, result.entries.size)

            // Event 0 — AF5's factory has just created the entity (defaults applied:
            // status=CREATED, eventCount=0, customerId=null) BEFORE the @EventSourcingHandler
            // runs. stateBefore IS sent for the leading entry — the FE has no prior entry to
            // look back at.
            val first = result.entries[0]
            assertEquals(0L, first.sequenceNumber)
            assertEquals(ReservationCreated::class.java.name, first.eventType)
            assertNotNull(first.stateBefore)
            assertTrue(first.stateBefore!!.contains("\"eventCount\":0"))
            assertTrue(first.stateBefore!!.contains("\"customerId\":null"))
            assertNotNull(first.stateAfter)
            assertTrue(first.stateAfter!!.contains("\"eventCount\":1"))
            assertTrue(first.stateAfter!!.contains("\"customerId\":\"alice\""))
            // entries[0].stateAfter is the implicit "before" for entries[1] — it must carry the
            // post-Created state so the FE can render the transition into Confirmed.
            assertTrue(first.stateAfter!!.contains("\"status\":\"CREATED\""))

            // Event 1 — server stops sending stateBefore. The CONFIRMED transition is verified
            // by reading stateAfter and pairing it with entries[0].stateAfter above.
            val second = result.entries[1]
            assertEquals(1L, second.sequenceNumber)
            assertNull(second.stateBefore, "stateBefore is rehydrated by the FE from entries[0].stateAfter")
            assertTrue(second.stateAfter!!.contains("\"status\":\"CONFIRMED\""))

            // Event 2 — same contract: server skips stateBefore, FE pairs with
            // entries[1].stateAfter.
            val third = result.entries[2]
            assertEquals(2L, third.sequenceNumber)
            assertNull(third.stateBefore, "stateBefore is rehydrated by the FE from entries[1].stateAfter")
            assertTrue(third.stateAfter!!.contains("\"status\":\"CANCELLED\""))
        }

        @Test
        fun `honours offset and limit`() {
            val result = responder.handleTimelineReplay(timelineQuery("RES-1", offset = 1, limit = 1))

            // Total still reflects the full stream so the FE can drive pagination.
            assertEquals(3, result.totalEvents)
            assertEquals(1, result.entries.size)
            assertEquals(1L, result.entries.first().sequenceNumber)
            assertEquals(ReservationConfirmed::class.java.name, result.entries.first().eventType)
            assertTrue(result.truncated, "events remain after the requested window")
        }
    }

    // ------------------------------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------------------------------

    private fun event(payload: Any) = GenericEventMessage(
            MessageType(payload.javaClass),
            payload,
    )

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

/**
 * Stamps out a stock test [AxoniqPlatformConfiguration] with the given access mode. The
 * non-null constructor parameters are dummy values — nothing in the inspection path inspects
 * them, the test just needs *something* registered so the responder's privacy gate has a
 * source for the mode.
 */
private fun testPlatformConfig(mode: DomainEventAccessMode): AxoniqPlatformConfiguration =
        AxoniqPlatformConfiguration("test-env", "test-token", "test-app")
                .domainEventAccessMode(mode)
