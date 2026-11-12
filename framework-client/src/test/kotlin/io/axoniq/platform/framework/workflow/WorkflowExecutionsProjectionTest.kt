/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.workflow

import io.axoniq.framework.workflow.configuration.WorkflowConfigurer
import io.axoniq.framework.workflow.configuration.WorkflowModule
import io.axoniq.framework.workflow.dsl.api.EventConditions
import io.axoniq.framework.workflow.dsl.simple.SimpleWorkflowContext
import io.axoniq.platform.framework.AxoniqPlatformConfiguration
import io.axoniq.platform.framework.api.WorkflowExecutionsQuery
import io.axoniq.platform.framework.client.RSocketHandlerRegistrar
import io.axoniq.platform.framework.client.strategy.CborJackson3EncodingStrategy
import org.axonframework.common.configuration.AxonConfiguration
import org.axonframework.common.configuration.ComponentDefinition
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import org.axonframework.eventsourcing.eventstore.GlobalIndexPosition
import org.axonframework.eventsourcing.snapshot.api.Snapshot
import org.axonframework.eventsourcing.snapshot.inmemory.InMemorySnapshotStore
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore
import org.axonframework.messaging.core.MessageType
import org.axonframework.messaging.core.QualifiedName
import org.axonframework.messaging.eventhandling.EventSink
import org.axonframework.messaging.eventhandling.GenericEventMessage
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class WorkflowExecutionsProjectionTest {

    private lateinit var configuration: AxonConfiguration
    private lateinit var reporter: WorkflowDefinitionsReporter
    private lateinit var engine: ControllableEngine

    @BeforeEach
    fun setUp() {
        configuration = WorkflowConfigurer.create()
                .registerWorkflowModule(WorkflowModule.configure("annotated", SimpleWorkflowContext::class.java)
                        .definition { it.autodetected { AnnotatedOrderWorkflow() } })
                .registerWorkflowModule(WorkflowModule.configure("declarative", SimpleWorkflowContext::class.java)
                        .definition {
                            it.declarative { JavaWorkflows.lambdaDefinition() }
                                    .workflowName("lambda")
                                    .on { EventConditions.fromQualifiedName(QualifiedName("OrderPlaced")) }
                                    .notCustomized()
                        })
                .componentRegistry { cr ->
                    cr.registerDecorator(EventStorageEngine::class.java, 0) { _, _, delegate: EventStorageEngine ->
                        ControllableEngine(delegate).also { engine = it }
                    }
                    cr.registerComponent(ComponentDefinition.ofType(SnapshotStore::class.java).withBuilder { InMemorySnapshotStore() })
                    cr.registerComponent(ComponentDefinition.ofType(RSocketHandlerRegistrar::class.java)
                            .withBuilder { RSocketHandlerRegistrar(CborJackson3EncodingStrategy()) })
                    cr.registerComponent(ComponentDefinition.ofType(AxoniqPlatformConfiguration::class.java)
                            .withBuilder { AxoniqPlatformConfiguration("test-env", "test-token", "test-app") })
                }
                .start()
        reporter = configuration.getComponent(WorkflowDefinitionsReporter::class.java)

        configuration.getComponent(EventSink::class.java)
                .publish(null, GenericEventMessage(MessageType("OrderPlaced"), mapOf("orderId" to "order-1")))
                .get()
        val executions = configuration.getComponent(WorkflowExecutionsResponder::class.java)
        await().atMost(Duration.ofSeconds(10)).until {
            executions.executions(WorkflowExecutionsQuery()).items.map { it.status }.toSet() == setOf("STARTED", "COMPLETED")
        }
    }

    @AfterEach
    fun tearDown() {
        engine.release()
        configuration.shutdown()
    }

    private fun projection(refreshWait: Duration? = null, definitionNames: () -> Set<String> = reporter::definitionNames) =
            WorkflowExecutionsProjection(configuration, refreshWait = refreshWait, definitionNames = definitionNames)

    private fun WorkflowExecutionsProjection.names() = read { instances -> instances.map { it.definitionName }.toSet() }

    private fun storeSnapshot(version: String, payload: Any) = configuration.getComponent(SnapshotStore::class.java)
            .store(WorkflowExecutionsProjection.SNAPSHOT_NAME, "test-app",
                    Snapshot(GlobalIndexPosition(1_000_000), version, payload, Instant.now(), emptyMap()), null)
            .get()

    @Nested
    inner class SnapshotFallback {

        @Test
        fun `a snapshot of an unknown version is ignored and the events are read from the start`() {
            storeSnapshot(version = "unknown", payload = ByteArray(0))

            assertEquals(setOf("annotatedOrder", "lambda"), projection().names())
        }

        @Test
        fun `a snapshot that cannot be read is ignored and the events are read from the start`() {
            storeSnapshot(version = WorkflowExecutionsProjection.SNAPSHOT_VERSION, payload = "not a snapshot".toByteArray())

            assertEquals(setOf("annotatedOrder", "lambda"), projection().names())
        }

        @Test
        fun `a snapshot of a node that does not define every workflow defined here is ignored`() {
            storeSnapshot(version = "unknown", payload = ByteArray(0))
            projection { setOf("lambda") }.refresh()

            assertEquals(setOf("annotatedOrder", "lambda"), projection().names())
        }
    }

    @Nested
    inner class SnapshotStoring {

        private fun stored(): Snapshot? = configuration.getComponent(SnapshotStore::class.java)
                .load(WorkflowExecutionsProjection.SNAPSHOT_NAME, "test-app", null).get()

        private fun storeOtherNodesSnapshot(storedAt: Instant, definitions: String) = configuration.getComponent(SnapshotStore::class.java)
                .store(WorkflowExecutionsProjection.SNAPSHOT_NAME, "test-app",
                        Snapshot(GlobalIndexPosition(1_000_000), WorkflowExecutionsProjection.SNAPSHOT_VERSION, "other node".toByteArray(),
                                storedAt, mapOf(WorkflowExecutionsProjection.SNAPSHOT_DEFINITIONS to definitions)), null)
                .get()

        private fun publishOrder(orderId: String) = configuration.getComponent(EventSink::class.java)
                .publish(null, GenericEventMessage(MessageType("OrderPlaced"), mapOf("orderId" to orderId)))
                .get()

        @Test
        fun `a node leaves the snapshot another node stored within the interval`() {
            await().atMost(Duration.ofSeconds(10)).until { stored() != null }
            val before = stored()!!
            publishOrder("order-2")

            projection().refresh()

            assertEquals(before.timestamp(), stored()!!.timestamp())
        }

        @Test
        fun `a node replaces a snapshot older than the interval`() {
            val storedAt = Instant.now().minus(WorkflowExecutionsProjection.SNAPSHOT_INTERVAL).minusSeconds(60)
            storeOtherNodesSnapshot(storedAt, definitions = "annotatedOrder\nlambda")

            projection().refresh()

            await().atMost(Duration.ofSeconds(10)).until { stored()!!.timestamp().isAfter(storedAt) }
        }

        @Test
        fun `a node replaces a recent snapshot that leaves out one of its definitions`() {
            val storedAt = Instant.now().minusSeconds(1)
            storeOtherNodesSnapshot(storedAt, definitions = "lambda")

            projection().refresh()

            await().atMost(Duration.ofSeconds(10)).until { stored()!!.timestamp().isAfter(storedAt) }
        }
    }

    @Nested
    inner class Definitions {

        @Test
        fun `nothing is listed when this application defines no workflows`() {
            val projection = projection { emptySet() }

            assertEquals(emptySet<String>(), projection.names())
            assertNull(projection.openCounts())
        }

        @Test
        fun `instances are listed once the definitions are known`() {
            var defined = emptySet<String>()
            val projection = projection { defined }
            assertEquals(emptySet<String>(), projection.names())

            defined = reporter.definitionNames()

            assertEquals(setOf("annotatedOrder", "lambda"), projection.names())
        }

        @Test
        fun `a definition added later brings in the instances read before it`() {
            var defined = setOf("lambda")
            val projection = projection { defined }
            assertEquals(setOf("lambda"), projection.names())

            defined = setOf("lambda", "annotatedOrder")

            assertEquals(setOf("annotatedOrder", "lambda"), projection.names())
        }
    }

    @Nested
    inner class Requests {

        @Test
        fun `a request answers from what is known when a refresh takes longer than the wait`() {
            storeSnapshot(version = "unknown", payload = ByteArray(0))
            val projection = projection(refreshWait = Duration.ofMillis(200))
            engine.hold()

            val started = System.nanoTime()
            val listed = projection.read { it.size }

            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(5))
            assertEquals(0, listed)
            engine.release()
            await().atMost(Duration.ofSeconds(10)).until { projection.read { it.size } == 2 }
        }

        @Test
        fun `requests during a refresh join it instead of starting another`() {
            val projection = projection(refreshWait = Duration.ofMillis(50))
            engine.hold()

            repeat(3) { projection.read { it.size } }

            assertEquals(1, engine.sources.get())
        }
    }
}
