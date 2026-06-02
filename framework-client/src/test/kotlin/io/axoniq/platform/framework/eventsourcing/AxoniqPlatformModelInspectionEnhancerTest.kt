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
import io.mockk.verify
import org.axonframework.common.configuration.ComponentDefinition
import org.axonframework.common.configuration.ComponentRegistry
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Verifies the guard in [AxoniqPlatformModelInspectionEnhancer]: the responder must register
 * only when the platform client is wired ([RSocketHandlerRegistrar] present) and an
 * [EventStorageEngine] is available. Missing either is the no-event-sourcing / no-platform-client
 * case where registering would have nothing to act on.
 *
 * The enhancer itself does not probe for [EventStorageEngine] directly — it first probes for
 * {@code axon-eventsourcing} on the classpath and delegates the rest of the wiring to
 * [ModelInspectionDecorators], which is where the [EventStorageEngine] check lives. In tests
 * the classpath probe always succeeds (axon-eventsourcing is a test dependency), so we exercise
 * both branches via the registry mocks.
 *
 * StateManager is intentionally NOT probed: ModellingConfigurationDefaults registers it at
 * Integer.MAX_VALUE, after this enhancer's order, so the probe would falsely return false
 * during a real boot.
 *
 * Tests are grouped per behavioural branch via [Nested] so failures point straight at the
 * guard scenario they belong to.
 */
class AxoniqPlatformModelInspectionEnhancerTest {

    private val enhancer = AxoniqPlatformModelInspectionEnhancer()

    @Nested
    inner class Registration {

        @Test
        fun `registers the responder when EventStorageEngine and RSocketHandlerRegistrar are both present`() {
            val registry = mockk<ComponentRegistry>(relaxed = true)
            every { registry.hasComponent(EventStorageEngine::class.java) } returns true
            every { registry.hasComponent(RSocketHandlerRegistrar::class.java) } returns true

            enhancer.enhance(registry)

            verify(exactly = 1) { registry.registerComponent(any<ComponentDefinition<*>>()) }
        }

        @Test
        fun `skips registration when EventStorageEngine is missing — typical AF4 application`() {
            val registry = mockk<ComponentRegistry>(relaxed = true)
            every { registry.hasComponent(EventStorageEngine::class.java) } returns false
            every { registry.hasComponent(RSocketHandlerRegistrar::class.java) } returns true

            enhancer.enhance(registry)

            verify(exactly = 0) { registry.registerComponent(any<ComponentDefinition<*>>()) }
        }

        @Test
        fun `skips registration when RSocketHandlerRegistrar is missing — console client not wired`() {
            val registry = mockk<ComponentRegistry>(relaxed = true)
            every { registry.hasComponent(RSocketHandlerRegistrar::class.java) } returns false

            enhancer.enhance(registry)

            verify(exactly = 0) { registry.registerComponent(any<ComponentDefinition<*>>()) }
        }
    }

    @Nested
    inner class Order {

        @Test
        fun `runs after the platform configurer enhancer so its components are visible`() {
            // The responder builder reads multiple components from the configuration during
            // start; this enhancer must run AFTER the platform enhancer that registers them.
            // Concretely: order > PLATFORM_ENHANCER_ORDER (currently +1 above it).
            val platformOrder = io.axoniq.platform.framework.AxoniqPlatformConfigurerEnhancer.PLATFORM_ENHANCER_ORDER
            assertEquals(platformOrder + 1, enhancer.order())
        }
    }
}
