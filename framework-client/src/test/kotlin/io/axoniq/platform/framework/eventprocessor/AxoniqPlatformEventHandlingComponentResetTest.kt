/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.eventprocessor

import io.axoniq.platform.framework.messaging.HandlerMetricsRegistry
import io.axoniq.platform.framework.modelling.EntityMetricsRegistry
import io.mockk.every
import io.mockk.mockk
import org.axonframework.common.configuration.AxonConfiguration
import org.axonframework.common.configuration.ComponentDefinition
import org.axonframework.common.configuration.DecoratorDefinition
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer
import org.axonframework.messaging.core.MessageStream
import org.axonframework.messaging.eventhandling.EventHandlingComponent
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorModule
import org.axonframework.messaging.eventhandling.replay.ReplayStatus
import org.axonframework.messaging.eventhandling.replay.ReplayStatusChanged
import org.axonframework.messaging.eventhandling.replay.ReplayStatusChangedHandler
import org.axonframework.messaging.eventhandling.replay.ResetHandler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Resets a real pooled streaming processor whose event handling component is wrapped in
 * [AxoniqPlatformEventHandlingComponent], the way the platform client wraps every processor's components.
 */
class AxoniqPlatformEventHandlingComponentResetTest {

    companion object {
        private const val PROCESSOR_NAME = "projection"
    }

    private var configuration: AxonConfiguration? = null

    @AfterEach
    fun shutdown() {
        configuration?.shutdown()
    }

    @Test
    fun `resetting the processor invokes the reset handler of the wrapped component`() {
        val resets = CopyOnWriteArrayList<Any>()
        val processor = start(SimpleEventHandlingComponent.create("projector").subscribe(ResetHandler { resetContext, _ ->
            resets += resetContext
            MessageStream.empty()
        }))

        processor.shutdown().get(5, TimeUnit.SECONDS)
        processor.resetTokens().get(5, TimeUnit.SECONDS)

        assertEquals(1, resets.size)
    }

    @Test
    fun `the processor does not support a reset when the wrapped component does not`() {
        val processor = start(object : EventHandlingComponent by SimpleEventHandlingComponent.create("notifier") {
            override fun supportsReset() = false
        })

        assertFalse(processor.supportsReset())
    }

    @Test
    fun `replay status changes reach the wrapped component`() {
        val changes = CopyOnWriteArrayList<ReplayStatus>()
        val delegate = SimpleEventHandlingComponent.create("projector").subscribe(ReplayStatusChangedHandler { change, _ ->
            changes += change.status()
            MessageStream.empty()
        })
        val wrapper = AxoniqPlatformEventHandlingComponent(delegate, PROCESSOR_NAME, mockk(relaxed = true), mockk(relaxed = true))

        wrapper.handle(mockk<ReplayStatusChanged> { every { status() } returns ReplayStatus.REPLAY }, mockk(relaxed = true))

        assertEquals(listOf(ReplayStatus.REPLAY), changes)
    }

    private fun start(component: EventHandlingComponent): StreamingEventProcessor {
        val configuration = EventSourcingConfigurer.create()
                .componentRegistry { registry ->
                    registry.registerComponent(ComponentDefinition.ofType(HandlerMetricsRegistry::class.java)
                            .withBuilder { mockk(relaxed = true) })
                    registry.registerComponent(ComponentDefinition.ofType(ProcessorMetricsRegistry::class.java)
                            .withBuilder { ProcessorMetricsRegistry() })
                    registry.registerComponent(ComponentDefinition.ofType(EntityMetricsRegistry::class.java)
                            .withBuilder { EntityMetricsRegistry() })
                }
                .messaging { messaging ->
                    messaging.eventProcessing { eventProcessing ->
                        eventProcessing.pooledStreaming { psep ->
                            psep.processor(
                                    PooledStreamingEventProcessorModule(PROCESSOR_NAME)
                                            .eventHandlingComponents { components -> components.declarative("component") { _ -> component } }
                                            .notCustomized()
                                            .componentRegistry { moduleRegistry ->
                                                moduleRegistry.registerDecorator(
                                                        DecoratorDefinition.forType(EventHandlingComponent::class.java)
                                                                .with { cc, _, delegate ->
                                                                    AxoniqPlatformEventHandlingComponent(
                                                                            delegate,
                                                                            PROCESSOR_NAME,
                                                                            cc.getComponent(HandlerMetricsRegistry::class.java),
                                                                            cc.getComponent(ProcessorMetricsRegistry::class.java),
                                                                    )
                                                                }
                                                                .order(0),
                                                )
                                            },
                            )
                        }
                    }
                }
                .start()
        this.configuration = configuration
        return configuration.getModuleConfiguration("EventProcessor[$PROCESSOR_NAME]")
                .orElseThrow()
                .getComponent(StreamingEventProcessor::class.java, PROCESSOR_NAME)
    }
}
