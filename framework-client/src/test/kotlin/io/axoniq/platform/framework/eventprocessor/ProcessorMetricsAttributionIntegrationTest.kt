/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.eventprocessor

import io.axoniq.platform.framework.AxoniqPlatformConfiguration
import io.axoniq.platform.framework.api.metrics.HandlerStatisticsMetricIdentifier
import io.axoniq.platform.framework.getPropertyValue
import io.axoniq.platform.framework.messaging.HandlerMetricsRegistry
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Boots a real AF5 configuration with two pooled streaming processors, each handling its own event type, and lets the
 * platform enhancer instrument them as it does in an application. Each processor must report only the latency and
 * handler statistics of the events it handled itself.
 *
 * Regression for a customer report: a processor at the head of the stream showed 50+ days of latency, and its
 * Handler Insights showed old events. Those were another processor's events, replaying history, reported under the
 * wrong processor name.
 */
class ProcessorMetricsAttributionIntegrationTest {

    companion object {
        private const val ALPHA = "alpha-processor"
        private const val BETA = "beta-processor"
        private val ALPHA_AGE: Duration = Duration.ofDays(10)
        private val BETA_AGE: Duration = Duration.ofDays(30)
        private val ONE_DAY_MS = Duration.ofDays(1).toMillis().toDouble()
    }

    data class AlphaEvent(val id: String)
    data class BetaEvent(val id: String)

    private val alphaHandled = CountDownLatch(1)
    private val betaHandled = CountDownLatch(1)
    private lateinit var configuration: AxonConfiguration

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
                        eventProcessing.pooledStreaming { psep ->
                            psep.processor(processor(ALPHA, AlphaEvent::class.java, alphaHandled))
                                    .processor(processor(BETA, BetaEvent::class.java, betaHandled))
                        }
                    }
                }
                .start()

        configuration.getComponent(EventSink::class.java).publish(
                null,
                oldEvent(AlphaEvent("a-1"), ALPHA_AGE),
                oldEvent(BetaEvent("b-1"), BETA_AGE),
        ).get(10, TimeUnit.SECONDS)

        assert(alphaHandled.await(30, TimeUnit.SECONDS)) { "alpha never handled its event" }
        assert(betaHandled.await(30, TimeUnit.SECONDS)) { "beta never handled its event" }
    }

    @AfterEach
    fun tearDown() {
        configuration.shutdown()
    }

    @Nested
    inner class IngestLatency {

        @Test
        fun `each processor reports the age of its own event`() {
            val metrics = configuration.getComponent(ProcessorMetricsRegistry::class.java)

            assertEquals(ALPHA_AGE.toDays().toDouble(), metrics.ingestLatencyForProcessor(ALPHA, 0).getValue() / ONE_DAY_MS, 0.1,
                    "ingest latency of $ALPHA in days")
            assertEquals(BETA_AGE.toDays().toDouble(), metrics.ingestLatencyForProcessor(BETA, 0).getValue() / ONE_DAY_MS, 0.1,
                    "ingest latency of $BETA in days")
        }
    }

    @Nested
    inner class HandlerStatistics {

        @Test
        fun `each event is counted under the processor that handled it`() {
            val handlers = configuration.getComponent(HandlerMetricsRegistry::class.java)
                    .getPropertyValue<Map<HandlerStatisticsMetricIdentifier, Any>>("handlers")!!

            await().atMost(Duration.ofSeconds(10)).untilAsserted {
                val componentsPerEvent = handlers.keys
                        .groupBy({ it.message.name }, { it.component })
                        .mapValues { it.value.toSet() }
                assertEquals(setOf(ALPHA), componentsPerEvent[QualifiedName(AlphaEvent::class.java).fullName()], "components that handled AlphaEvent")
                assertEquals(setOf(BETA), componentsPerEvent[QualifiedName(BetaEvent::class.java).fullName()], "components that handled BetaEvent")
            }
        }
    }

    private fun processor(name: String, eventType: Class<*>, handled: CountDownLatch) =
            PooledStreamingEventProcessorModule(name)
                    .eventHandlingComponents { components ->
                        components.declarative("$name-component") { _ ->
                            SimpleEventHandlingComponent.create("$name-component")
                                    .subscribe(QualifiedName(eventType)) { _, _ ->
                                        handled.countDown()
                                        MessageStream.empty()
                                    }
                        }
                    }
                    .customized { _, config -> config.initialSegmentCount(1) }

    private fun oldEvent(payload: Any, age: Duration) = GenericEventMessage(
            UUID.randomUUID().toString(),
            MessageType(payload.javaClass),
            payload,
            emptyMap(),
            Instant.now().minus(age),
    )
}
