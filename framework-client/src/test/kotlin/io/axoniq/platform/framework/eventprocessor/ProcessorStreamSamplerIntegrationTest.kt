/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.eventprocessor

import io.axoniq.platform.framework.AxoniqPlatformConfiguration
import org.awaitility.Awaitility.await
import org.axonframework.common.configuration.AxonConfiguration
import org.axonframework.common.configuration.ComponentDefinition
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer
import org.axonframework.messaging.core.MessageStream
import org.axonframework.messaging.core.MessageType
import org.axonframework.messaging.core.QualifiedName
import org.axonframework.messaging.eventhandling.EventSink
import org.axonframework.messaging.eventhandling.GenericEventMessage
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorModule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A processor stuck behind a slow handler must read as behind, both with events waiting on its stream and with the
 * last event of the stream still in the handler. Once it handled them it must read as caught up again, and it must be
 * able to fall behind again afterwards, which the framework's own `caughtUp` flag cannot.
 */
class ProcessorStreamSamplerIntegrationTest {

    companion object {
        private const val PROCESSOR = "lagging-processor"
        private const val BACKLOG = 5000
    }

    data class HandledEvent(val id: String)

    private val gate = AtomicReference(CountDownLatch(0))
    private val blocked = AtomicReference(CountDownLatch(1))
    private lateinit var configuration: AxonConfiguration
    private lateinit var sampler: ProcessorStreamSampler

    @BeforeEach
    fun boot() {
        // The platform client keeps retrying when it cannot connect, so a closed local port keeps it offline.
        val unusedPort = ServerSocket(0).use { it.localPort }
        configuration = EventSourcingConfigurer.create()
                .componentRegistry { registry ->
                    registry.registerComponent(ComponentDefinition.ofType(AxoniqPlatformConfiguration::class.java)
                            .withBuilder {
                                AxoniqPlatformConfiguration("test-env", "test-token", "test-app")
                                        .host("localhost")
                                        .port(unusedPort)
                            })
                }
                .messaging { messaging ->
                    messaging.eventProcessing { eventProcessing ->
                        eventProcessing.pooledStreaming { psep -> psep.processor(processor()) }
                    }
                }
                .start()
        sampler = configuration.getComponent(ProcessorStreamSampler::class.java)
    }

    @AfterEach
    fun tearDown() {
        gate.get().countDown()
        configuration.shutdown()
    }

    @Test
    fun `reads behind while events wait on the stream, and caught up once they are handled, every time`() {
        awaitCaughtUpAfter(Instant.EPOCH)

        repeat(2) { round ->
            assertBehindUntilReleased(round, backlog = BACKLOG)
        }
    }

    @Test
    fun `reads behind while the last event of the stream is still in a handler`() {
        awaitCaughtUpAfter(Instant.EPOCH)

        assertBehindUntilReleased(round = 0, backlog = 0)
    }

    /**
     * Publishes an event whose handler blocks, followed by [backlog] more events, and checks the processor reads as
     * behind until the handler is released, and as caught up after.
     */
    private fun assertBehindUntilReleased(round: Int, backlog: Int) {
        val release = CountDownLatch(1)
        gate.set(release)
        blocked.set(CountDownLatch(1))
        val events = listOf(event(HandledEvent("block"))) + (1..backlog).map { event(HandledEvent("e-$it")) }
        configuration.getComponent(EventSink::class.java).publish(null, *events.toTypedArray()).get(10, TimeUnit.SECONDS)
        assertTrue(blocked.get().await(10, TimeUnit.SECONDS), "handler never started")
        val behindSince = Instant.now()

        repeat(3) {
            Thread.sleep(500)
            sampler.sample()
            val lastCaughtUp = sampler.lastCaughtUpAt(PROCESSOR)
            assertTrue(lastCaughtUp != null && lastCaughtUp.isBefore(behindSince),
                    "round $round: processor must read as behind, last caught up at $lastCaughtUp")
        }

        release.countDown()
        awaitCaughtUpAfter(behindSince)
    }

    private fun awaitCaughtUpAfter(moment: Instant) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            sampler.sample()
            val lastCaughtUp = sampler.lastCaughtUpAt(PROCESSOR)
            assertTrue(lastCaughtUp != null && lastCaughtUp.isAfter(moment),
                    "processor must read as caught up after $moment, last caught up at $lastCaughtUp")
        }
    }

    private fun processor() =
            PooledStreamingEventProcessorModule(PROCESSOR)
                    .eventHandlingComponents { components ->
                        components.declarative("$PROCESSOR-component") { _ ->
                            SimpleEventHandlingComponent.create("$PROCESSOR-component")
                                    .subscribe(QualifiedName(HandledEvent::class.java)) { event, _ ->
                                        if ((event.payload() as HandledEvent).id == "block") {
                                            blocked.get().countDown()
                                            gate.get().await(30, TimeUnit.SECONDS)
                                        }
                                        MessageStream.empty()
                                    }
                        }
                    }
                    .customized { _, config -> config.initialSegmentCount(1) }

    private fun event(payload: Any) = GenericEventMessage(
            UUID.randomUUID().toString(),
            MessageType(payload.javaClass),
            payload,
            emptyMap(),
            Instant.now(),
    )
}
