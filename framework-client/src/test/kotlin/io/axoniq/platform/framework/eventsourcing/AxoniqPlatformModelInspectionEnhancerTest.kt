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
import org.axonframework.modelling.StateManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies the registration guard in [AxoniqPlatformModelInspectionEnhancer]: the
 * inspection responder must register only when the application has all three required
 * components (StateManager + EventStorageEngine + RSocketHandlerRegistrar). Missing any
 * of them is the AF4 / non-event-sourced case and registering would NPE on first request.
 */
class AxoniqPlatformModelInspectionEnhancerTest {

    private val enhancer = AxoniqPlatformModelInspectionEnhancer()

    @Test
    fun `registers the responder when StateManager, EventStorageEngine and RSocketHandlerRegistrar are all present`() {
        val registry = mockk<ComponentRegistry>(relaxed = true)
        every { registry.hasComponent(StateManager::class.java) } returns true
        every { registry.hasComponent(EventStorageEngine::class.java) } returns true
        every { registry.hasComponent(RSocketHandlerRegistrar::class.java) } returns true

        enhancer.enhance(registry)

        verify(exactly = 1) { registry.registerComponent(any<ComponentDefinition<*>>()) }
    }

    @Test
    fun `skips registration when StateManager is missing — typical AF4 application`() {
        val registry = mockk<ComponentRegistry>(relaxed = true)
        every { registry.hasComponent(StateManager::class.java) } returns false
        every { registry.hasComponent(EventStorageEngine::class.java) } returns true
        every { registry.hasComponent(RSocketHandlerRegistrar::class.java) } returns true

        enhancer.enhance(registry)

        verify(exactly = 0) { registry.registerComponent(any<ComponentDefinition<*>>()) }
    }

    @Test
    fun `skips registration when EventStorageEngine is missing`() {
        val registry = mockk<ComponentRegistry>(relaxed = true)
        every { registry.hasComponent(StateManager::class.java) } returns true
        every { registry.hasComponent(EventStorageEngine::class.java) } returns false
        every { registry.hasComponent(RSocketHandlerRegistrar::class.java) } returns true

        enhancer.enhance(registry)

        verify(exactly = 0) { registry.registerComponent(any<ComponentDefinition<*>>()) }
    }

    @Test
    fun `skips registration when RSocketHandlerRegistrar is missing — console client not wired`() {
        val registry = mockk<ComponentRegistry>(relaxed = true)
        every { registry.hasComponent(StateManager::class.java) } returns true
        every { registry.hasComponent(EventStorageEngine::class.java) } returns true
        every { registry.hasComponent(RSocketHandlerRegistrar::class.java) } returns false

        enhancer.enhance(registry)

        verify(exactly = 0) { registry.registerComponent(any<ComponentDefinition<*>>()) }
    }

    @Test
    fun `runs after the platform configurer enhancer so its components are visible`() {
        // The responder builder reads multiple components from the configuration during
        // start; this enhancer must run AFTER the platform enhancer that registers them.
        // Concretely: order > PLATFORM_ENHANCER_ORDER (currently +1 above it).
        val platformOrder = io.axoniq.platform.framework.AxoniqPlatformConfigurerEnhancer.PLATFORM_ENHANCER_ORDER
        assertEquals(platformOrder + 1, enhancer.order())
    }
}
