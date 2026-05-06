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

import io.axoniq.platform.framework.api.ModelEntityStateAtSequenceQuery
import io.axoniq.platform.framework.client.RSocketHandlerRegistrar
import io.axoniq.platform.framework.client.strategy.CborJackson3EncodingStrategy
import org.axonframework.common.configuration.AxonConfiguration
import org.axonframework.common.configuration.BaseModule
import org.axonframework.common.configuration.ComponentDefinition
import org.axonframework.eventsourcing.annotation.EventSourcedEntity
import org.axonframework.eventsourcing.annotation.EventTag
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId
import org.axonframework.eventsourcing.annotation.EventSourcingHandler
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer
import org.axonframework.messaging.core.MessageType
import org.axonframework.messaging.eventhandling.EventSink
import org.axonframework.messaging.eventhandling.GenericEventMessage
import org.axonframework.modelling.SimpleStateManager
import org.axonframework.modelling.StateManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * End-to-end test for the case the existing integration test doesn't cover: an event-sourced
 * entity buried inside a custom user-defined [BaseModule]. Without this, you can't tell whether
 * inspection works only for the conveniently top-level entity registration that
 * `registerEntity(...)` produces, or also for arbitrary user nesting.
 *
 * The setup:
 *   - {@link OuterEntity} registered at the top level via the usual `registerEntity(...)` path.
 *   - [MySubModule] — a hand-rolled [BaseModule] that registers its own [StateManager] and an
 *     {@link InnerEntity} inside it via `registerModule(EventSourcedEntityModule.autodetected(...))`.
 *
 * Both entities must surface in the registered-entities query, and queries against the inner one
 * must reconstruct state correctly — proving the model-inspection enhancer's submodule walk
 * reaches arbitrary depth, not just one level.
 */
class RSocketModelInspectionResponderNestedModuleIntegrationTest {

    private lateinit var configuration: AxonConfiguration
    private lateinit var responder: RSocketModelInspectionResponder

    @BeforeEach
    fun setUp() {
        configuration = EventSourcingConfigurer.create()
                .registerEntity(EventSourcedEntityModule.autodetected(String::class.java, OuterEntity::class.java))
                .componentRegistry { cr ->
                    cr.registerComponent(ComponentDefinition.ofType(RSocketHandlerRegistrar::class.java)
                            .withBuilder { RSocketHandlerRegistrar(CborJackson3EncodingStrategy()) })
                    // The custom BaseModule lives directly under the root component registry.
                    // Inside it, a further sub-module registers InnerEntity — two levels deep.
                    cr.registerModule(MySubModule())
                }
                .start()

        responder = configuration.getComponent(RSocketModelInspectionResponder::class.java)

        val sink = configuration.getComponent(EventSink::class.java)
        sink.publish(
                null,
                event(OuterCreated("OUTER-1", "blue")),
                event(InnerOpened("INNER-1", 42)),
                event(InnerClosed("INNER-1")),
        ).get()
    }

    @AfterEach
    fun tearDown() {
        configuration.shutdown()
    }

    private fun event(payload: Any) = GenericEventMessage(MessageType(payload.javaClass), payload)

    @Test
    fun `registered entities query surfaces both top-level and deeply nested entities`() {
        val result = responder.handleRegisteredEntities()
        val typeNames = result.entities.map { it.entityType }.toSet()

        assertTrue(typeNames.contains(OuterEntity::class.java.name),
                "expected OuterEntity (top-level) to be registered")
        assertTrue(typeNames.contains(InnerEntity::class.java.name),
                "expected InnerEntity (nested inside MySubModule) to be registered — submodule walker must reach it")
    }

    @Test
    fun `state at sequence reconstructs the inner entity in the nested module`() {
        val result = responder.handleEntityStateAtSequence(ModelEntityStateAtSequenceQuery(
                entityType = InnerEntity::class.java.name,
                entityId = "INNER-1",
                idType = String::class.java.name,
                maxSequenceNumber = -1,
        ))

        assertNotNull(result.state, "state must be reconstructed for the inner entity")
        assertTrue(result.state!!.contains("\"open\":false"), result.state)
        assertTrue(result.state!!.contains("\"value\":42"), result.state)
    }

    @Test
    fun `state at sequence reconstructs the outer entity registered at the root`() {
        val result = responder.handleEntityStateAtSequence(ModelEntityStateAtSequenceQuery(
                entityType = OuterEntity::class.java.name,
                entityId = "OUTER-1",
                idType = String::class.java.name,
                maxSequenceNumber = -1,
        ))

        assertNotNull(result.state)
        assertTrue(result.state!!.contains("\"colour\":\"blue\""), result.state)
    }

    // ------------------------------------------------------------------------------------------
    //  Test fixtures
    // ------------------------------------------------------------------------------------------

    /**
     * Custom user module that owns its own [StateManager] and registers an event-sourced entity
     * as a sub-module. Mirrors how a real application might package a bounded context.
     */
    class MySubModule : BaseModule<MySubModule>("MySubModule") {
        init {
            componentRegistry { cr ->
                cr.registerComponent(ComponentDefinition.ofType(StateManager::class.java)
                        .withBuilder { SimpleStateManager.named("MySubModuleStateManager") })
                cr.registerModule(EventSourcedEntityModule.autodetected(String::class.java, InnerEntity::class.java))
            }
        }
    }

    @EventSourcedEntity(tagKey = "outerId")
    class OuterEntity @EntityCreator constructor(
            @Suppress("unused") @InjectEntityId val outerId: String,
    ) {
        var colour: String = ""

        @EventSourcingHandler
        fun on(event: OuterCreated) {
            colour = event.colour
        }
    }

    data class OuterCreated(
            @field:EventTag(key = "outerId") val outerId: String,
            val colour: String,
    )

    @EventSourcedEntity(tagKey = "innerId")
    class InnerEntity @EntityCreator constructor(
            @Suppress("unused") @InjectEntityId val innerId: String,
    ) {
        var open: Boolean = false
        var value: Int = 0

        @EventSourcingHandler
        fun on(event: InnerOpened) {
            open = true
            value = event.value
        }

        @EventSourcingHandler
        fun on(@Suppress("unused") event: InnerClosed) {
            open = false
        }
    }

    data class InnerOpened(
            @field:EventTag(key = "innerId") val innerId: String,
            val value: Int,
    )

    data class InnerClosed(
            @field:EventTag(key = "innerId") val innerId: String,
    )
}
