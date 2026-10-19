/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.eventprocessor

import io.axoniq.platform.framework.AxoniqPlatformConfiguration
import io.axoniq.platform.framework.AxoniqPlatformConfigurerEnhancer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.axonframework.common.configuration.ComponentDefinition
import org.axonframework.common.configuration.ComponentRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies the guards in [AxoniqPlatformDeadLetterConfigurerEnhancer]: the DLQ components must
 * register only when the host application is wired with the platform client
 * ([AxoniqPlatformConfiguration] present) and the addon hasn't already been registered.
 *
 * The classpath probe is intentionally NOT covered here — `axoniq-dead-letter` is on the test
 * classpath via `provided` scope, so [Class.forName] always succeeds in tests. Exercising the
 * absent branch would require classloader trickery that adds more risk than coverage.
 */
class AxoniqPlatformDeadLetterConfigurerEnhancerTest {

    private val enhancer = AxoniqPlatformDeadLetterConfigurerEnhancer()

    @Test
    fun `registers all three components when AxoniqPlatformConfiguration is present and neither DLQ component is registered yet`() {
        val registry = mockk<ComponentRegistry>(relaxed = true)
        every { registry.hasComponent(AxoniqPlatformConfiguration::class.java) } returns true
        every { registry.hasComponent(DeadLetterManager::class.java) } returns false
        every { registry.hasComponent(ProcessingGroupInfoSource::class.java) } returns false

        enhancer.enhance(registry)

        // DeadLetterManager + ProcessingGroupInfoSource + RSocketDlqResponder
        verify(exactly = 3) { registry.registerComponent(any<ComponentDefinition<*>>()) }
    }

    @Test
    fun `no-op when AxoniqPlatformConfiguration is absent — host is not a platform client`() {
        val registry = mockk<ComponentRegistry>(relaxed = true)
        every { registry.hasComponent(AxoniqPlatformConfiguration::class.java) } returns false

        enhancer.enhance(registry)

        verify(exactly = 0) { registry.registerComponent(any<ComponentDefinition<*>>()) }
    }

    @Test
    fun `idempotent — no registrations when DeadLetterManager is already registered`() {
        val registry = mockk<ComponentRegistry>(relaxed = true)
        every { registry.hasComponent(AxoniqPlatformConfiguration::class.java) } returns true
        every { registry.hasComponent(DeadLetterManager::class.java) } returns true
        every { registry.hasComponent(ProcessingGroupInfoSource::class.java) } returns false

        enhancer.enhance(registry)

        verify(exactly = 0) { registry.registerComponent(any<ComponentDefinition<*>>()) }
    }

    @Test
    fun `idempotent — no registrations when ProcessingGroupInfoSource is already registered`() {
        val registry = mockk<ComponentRegistry>(relaxed = true)
        every { registry.hasComponent(AxoniqPlatformConfiguration::class.java) } returns true
        every { registry.hasComponent(DeadLetterManager::class.java) } returns false
        every { registry.hasComponent(ProcessingGroupInfoSource::class.java) } returns true

        enhancer.enhance(registry)

        verify(exactly = 0) { registry.registerComponent(any<ComponentDefinition<*>>()) }
    }

    // Note: the "Spring-path" branch in `register(...)` that skips re-registering
    // ProcessingGroupInfoSource when it's already exposed by the Spring-backed registry is not
    // reachable on the current code path — the top-level guard above bails out if EITHER
    // DeadLetterManager or ProcessingGroupInfoSource is already registered. The pair of
    // idempotency tests above therefore cover both flags of that combined guard. If the
    // top-level guard is ever loosened, this branch would need its own dedicated test.

    @Test
    fun `order is PLATFORM_ENHANCER_ORDER + 1 so the platform client components are visible`() {
        // RSocketDlqResponder needs RSocketHandlerRegistrar at start-time; that component is
        // registered by the main platform enhancer, so this enhancer must run after it.
        assertEquals(AxoniqPlatformConfigurerEnhancer.PLATFORM_ENHANCER_ORDER + 1, enhancer.order())
    }
}
