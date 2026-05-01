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

import io.axoniq.platform.framework.client.RSocketHandlerRegistrar
import io.mockk.every
import io.mockk.mockk
import org.axonframework.common.configuration.Configuration
import org.axonframework.conversion.Converter
import org.axonframework.eventsourcing.annotation.EventSourcingHandler
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import org.axonframework.messaging.core.MessageType
import org.axonframework.messaging.eventhandling.EventMessage
import org.axonframework.modelling.StateManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests the reflection-based `@EventSourcingHandler` dispatch fallback used when AF5's
 * own metamodel dispatch silently no-ops in our ad-hoc inspection context (no real
 * `ProcessingContext`, no interceptor chain).
 *
 * Two dispatch paths are exercised:
 *
 *  - **Path A**: payload is already a typed instance (the FQN [MessageType.name] case where
 *    [RSocketModelInspectionResponder.deserializePayload] succeeded). The matching handler
 *    is found by parameter-type assignability.
 *
 *  - **Path B**: payload is still raw `byte[]` because the [MessageType.name] is a short
 *    namespaced form (e.g. `quickstart.OrderCreatedEvent` from `@Event(namespace=...)`)
 *    that `Class.forName` can't resolve. The handler is selected by simple-name match
 *    against [MessageType.name] segments, then the [Converter] is invoked to turn the
 *    `byte[]` into the handler's parameter type.
 *
 * Critically: under Path B, Jackson permissive deserialization could happily build any of
 * the entity's event classes from any JSON byte sequence (filling whatever fields match,
 * defaulting the rest). We must pick the handler **before** invoking the converter,
 * otherwise the wrong handler fires (e.g. an `OrderCreated` event mutating the entity as
 * if it were `OrderShipped`).
 */
class RSocketModelInspectionResponderReflectionDispatchTest {

    private lateinit var responder: RSocketModelInspectionResponder
    private lateinit var configuration: Configuration
    private lateinit var converter: Converter

    @BeforeEach
    fun setUp() {
        configuration = mockk()
        converter = mockk()

        // Lazy `payloadConverter` reads `configuration.getComponent(Converter::class.java)`.
        // Stub it so Path B can use the converter.
        every { configuration.getComponent(Converter::class.java) } returns converter

        responder = RSocketModelInspectionResponder(
                stateManager = mockk<StateManager>(),
                eventStorageEngine = mockk<EventStorageEngine>(),
                registrar = mockk<RSocketHandlerRegistrar>(),
                configuration = configuration,
        )
    }

    // ---------------------------------------------------------------------------------------
    //  Path A — payload already deserialized
    // ---------------------------------------------------------------------------------------

    @Test
    fun `Path A invokes the @EventSourcingHandler whose param matches the typed payload`() {
        val entity = TestOrder()
        val event = TestOrder.OrderCreatedEvent("order-1", "Alice")
        val message = mockk<EventMessage>()
        every { message.payload() } returns event
        // Path A doesn't read message.type() at all — we never reach the converter call.

        responder.applyEventViaReflection(entity, message)

        assertEquals("order-1", entity.orderId)
        assertEquals("Alice", entity.customerName)
        assertEquals(TestOrder.Status.CREATED, entity.status)
    }

    @Test
    fun `Path A is a no-op when no handler matches the typed payload`() {
        val entity = TestOrder()
        // Use an event class the entity has no handler for.
        val unrelatedEvent = UnrelatedEvent("payload-content")
        val message = mockk<EventMessage>()
        every { message.payload() } returns unrelatedEvent

        responder.applyEventViaReflection(entity, message)

        // Entity untouched because no handler accepted UnrelatedEvent.
        assertNull(entity.orderId)
        assertEquals(TestOrder.Status.DRAFT, entity.status)
    }

    // ---------------------------------------------------------------------------------------
    //  Path B — raw byte[] payload, simple-name handler resolution
    // ---------------------------------------------------------------------------------------

    @Test
    fun `Path B selects the handler by simple-name match and converts raw byte payload to it`() {
        val entity = TestOrder()
        val rawBytes = "{\"orderId\":\"order-2\",\"customerName\":\"Bob\"}".toByteArray()
        val typedEvent = TestOrder.OrderCreatedEvent("order-2", "Bob")
        val message = mockk<EventMessage>()
        every { message.payload() } returns rawBytes
        every { message.type() } returns MessageType("quickstart.OrderCreatedEvent")
        // Converter sees the request for the handler's param type and returns a typed instance.
        // Note: the simple-name resolver picks OrderCreatedEvent from `quickstart.OrderCreatedEvent`,
        // so the converter is invoked with that type — never with OrderShippedEvent or any other.
        every {
            message.payloadAs(TestOrder.OrderCreatedEvent::class.java, converter)
        } returns typedEvent

        responder.applyEventViaReflection(entity, message)

        assertEquals("order-2", entity.orderId)
        assertEquals("Bob", entity.customerName)
        assertEquals(TestOrder.Status.CREATED, entity.status)
    }

    @Test
    fun `Path B does not fire any handler when no entity handler param matches the simple-name`() {
        val entity = TestOrder()
        val rawBytes = "{\"foo\":\"bar\"}".toByteArray()
        val message = mockk<EventMessage>()
        every { message.payload() } returns rawBytes
        // Simple-name "Mystery" doesn't match any of TestOrder's handler param types.
        every { message.type() } returns MessageType("some.namespace.Mystery")

        responder.applyEventViaReflection(entity, message)

        // No handler fired → entity stays at defaults. Crucially, the converter was never
        // invoked either, because the resolver short-circuited on the missing simple-name.
        assertNull(entity.orderId)
        assertEquals(TestOrder.Status.DRAFT, entity.status)
    }

    @Test
    fun `Path B picks correct handler when multiple share an overlapping JSON shape`() {
        // Regression guard: under the previous "try every handler with Jackson" approach,
        // an OrderCreatedEvent JSON could permissively deserialize to OrderShippedEvent
        // (sharing only `orderId`), causing the OrderShipped handler to fire and set
        // status=SHIPPED instead of CREATED. The simple-name matcher prevents this.
        val entity = TestOrder()
        val createdJsonBytes = "{\"orderId\":\"order-3\",\"customerName\":\"Carol\"}".toByteArray()
        val message = mockk<EventMessage>()
        every { message.payload() } returns createdJsonBytes
        every { message.type() } returns MessageType("quickstart.OrderCreatedEvent")

        // Only the OrderCreated path should be exercised. We stub it; if the responder
        // mistakenly tried OrderShipped, MockK would throw on the unstubbed call.
        every {
            message.payloadAs(TestOrder.OrderCreatedEvent::class.java, converter)
        } returns TestOrder.OrderCreatedEvent("order-3", "Carol")

        responder.applyEventViaReflection(entity, message)

        assertEquals(TestOrder.Status.CREATED, entity.status) // not SHIPPED
        assertEquals("order-3", entity.orderId)
        assertEquals("Carol", entity.customerName)
    }

    // ---------------------------------------------------------------------------------------
    //  Test fixtures
    // ---------------------------------------------------------------------------------------

    /**
     * Mirrors the AF5 entity pattern under test: no-arg constructor + several
     * `@EventSourcingHandler` methods that mutate `this` in place.
     */
    class TestOrder {
        var orderId: String? = null
        var customerName: String? = null
        var carrier: String? = null
        var status: Status = Status.DRAFT

        enum class Status { DRAFT, CREATED, SHIPPED }

        @EventSourcingHandler
        fun on(event: OrderCreatedEvent) {
            this.orderId = event.orderId
            this.customerName = event.customerName
            this.status = Status.CREATED
        }

        @EventSourcingHandler
        fun on(event: OrderShippedEvent) {
            // If this handler ever fires on an OrderCreated payload (the "Jackson permissive"
            // bug we guard against), `status` would jump straight to SHIPPED.
            this.orderId = event.orderId
            this.carrier = event.carrier
            this.status = Status.SHIPPED
        }

        @JvmRecord
        data class OrderCreatedEvent(val orderId: String, val customerName: String)

        @JvmRecord
        data class OrderShippedEvent(val orderId: String, val carrier: String)
    }

    /** A class no `TestOrder` handler accepts — used to assert no-op behaviour. */
    @JvmRecord
    data class UnrelatedEvent(val payload: String)
}
