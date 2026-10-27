/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.workflow

import io.axoniq.framework.workflow.annotation.Workflow
import io.axoniq.framework.workflow.configuration.WorkflowConfigurer
import io.axoniq.framework.workflow.configuration.WorkflowModule
import io.axoniq.framework.workflow.dsl.api.EventAssociationsUtils.equalsTo
import io.axoniq.framework.workflow.dsl.api.EventAssociationsUtils.payloadProperty
import io.axoniq.framework.workflow.dsl.api.EventConditions
import io.axoniq.framework.workflow.runtime.association.Associations.associate
import io.axoniq.framework.workflow.dsl.simple.SimpleWorkflowContext
import io.axoniq.platform.framework.AxoniqPlatformConfiguration
import io.axoniq.platform.framework.api.WorkflowDefinitionDescription
import io.axoniq.platform.framework.api.WorkflowGraphNodeKind
import io.axoniq.platform.framework.api.DomainEventAccessMode
import io.axoniq.platform.framework.api.WorkflowExecutionEventsQuery
import io.axoniq.platform.framework.api.WorkflowExecutionQuery
import io.axoniq.platform.framework.api.WorkflowExecutionsQuery
import io.axoniq.platform.framework.api.WorkflowMetricsQuery
import io.axoniq.platform.framework.client.RSocketHandlerRegistrar
import io.axoniq.platform.framework.client.strategy.CborJackson3EncodingStrategy
import org.axonframework.common.configuration.AxonConfiguration
import org.axonframework.common.configuration.ComponentDefinition
import org.axonframework.messaging.core.MessageType
import org.axonframework.messaging.core.QualifiedName
import org.axonframework.messaging.eventhandling.EventSink
import org.axonframework.messaging.eventhandling.GenericEventMessage
import org.awaitility.Awaitility.await
import java.time.Duration
import java.time.Instant
import java.util.function.UnaryOperator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class WorkflowDefinitionsResponderIntegrationTest {

    private lateinit var configuration: AxonConfiguration
    private lateinit var responder: WorkflowDefinitionsResponder

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
                    cr.registerComponent(ComponentDefinition.ofType(RSocketHandlerRegistrar::class.java)
                            .withBuilder { RSocketHandlerRegistrar(CborJackson3EncodingStrategy()) })
                    cr.registerComponent(ComponentDefinition.ofType(AxoniqPlatformConfiguration::class.java)
                            .withBuilder {
                                AxoniqPlatformConfiguration("test-env", "test-token", "test-app")
                                        .domainEventAccessMode(DomainEventAccessMode.FULL)
                            })
                }
                .start()

        responder = configuration.getComponent(WorkflowDefinitionsResponder::class.java)
    }

    @AfterEach
    fun tearDown() {
        configuration.shutdown()
    }

    private fun definition(name: String): WorkflowDefinitionDescription =
            responder.definitions().definitions.single { it.name == name }

    @Nested
    inner class Registration {

        @Test
        fun `every workflow module's definitions are listed`() {
            val names = responder.definitions().definitions.map { it.name }.toSet()

            assertEquals(setOf("annotatedOrder", "lambda"), names)
        }

        @Test
        fun `a definition lists the events that start it`() {
            assertEquals(listOf("OrderPlaced"), definition("annotatedOrder").startEvents)
            assertEquals(listOf("OrderPlaced"), definition("lambda").startEvents)
        }

        @Test
        fun `a definition is read once`() {
            assertSame(definition("annotatedOrder"), definition("annotatedOrder"))
        }
    }

    @Nested
    inner class Executions {

        private val executions get() = configuration.getComponent(WorkflowExecutionsResponder::class.java)

        @BeforeEach
        fun startBothWorkflows() {
            configuration.getComponent(EventSink::class.java)
                    .publish(null, GenericEventMessage(MessageType("OrderPlaced"), mapOf("orderId" to "order-1")))
                    .get()
            await().atMost(Duration.ofSeconds(10)).until {
                executions.executions(WorkflowExecutionsQuery()).items.map { it.status }.toSet() == setOf("STARTED", "COMPLETED")
            }
        }

        @Test
        fun `finished executions stay listed next to running ones`() {
            val page = executions.executions(WorkflowExecutionsQuery())

            assertEquals(2, page.total)
            val running = page.items.single { it.status == "STARTED" }
            assertEquals("order-1", running.workflowId)
            assertEquals("annotatedOrder", running.definitionName)
            assertNotNull(running.startedAt)
            assertEquals(null, running.finishedAt)
            assertNotNull(page.items.single { it.status == "COMPLETED" }.finishedAt)
        }

        @Test
        fun `executions are filtered by status and searched by id or definition`() {
            assertEquals(listOf("lambda"), executions.executions(WorkflowExecutionsQuery(status = "COMPLETED")).items.map { it.definitionName })
            assertEquals(listOf("order-1"), executions.executions(WorkflowExecutionsQuery(search = "ORDER-1")).items.map { it.workflowId })
            assertEquals(listOf("lambda"), executions.executions(WorkflowExecutionsQuery(search = "lamb")).items.map { it.definitionName })
        }

        @Test
        fun `the journal holds the start, every step event and the end, in order`() {
            val lambda = executions.executions(WorkflowExecutionsQuery(status = "COMPLETED")).items.single()

            val journal = executions.events(WorkflowExecutionEventsQuery(workflowId = lambda.workflowId)).events

            assertEquals(
                    listOf(null to "STARTED", "first" to "STARTED", "first" to "COMPLETED",
                            "second" to "STARTED", "second" to "COMPLETED", null to "COMPLETED"),
                    journal.map { it.stepName to it.status },
            )
            assertEquals((0L until journal.size).toList(), journal.map { it.position })
        }

        @Test
        fun `the journal is read in pages after a position`() {
            val first = executions.events(WorkflowExecutionEventsQuery(workflowId = "order-1", limit = 2))
            val rest = executions.events(WorkflowExecutionEventsQuery(workflowId = "order-1", after = first.events.last().position))

            assertTrue(first.hasMore)
            assertEquals(listOf(0L, 1L), first.events.map { it.position })
            assertEquals(2L, rest.events.first().position)
        }

        @Test
        fun `payloads are included because the access mode shows them`() {
            val start = executions.events(WorkflowExecutionEventsQuery(workflowId = "order-1")).events.first()

            assertTrue(start.payloadJson!!.contains("order-1"), start.payloadJson)
        }

        @Test
        fun `an execution lists the steps it recorded`() {
            val execution = executions.execution(WorkflowExecutionQuery(workflowId = "order-1"))

            assertEquals("STARTED", execution.execution!!.status)
            assertEquals(listOf("approve", "check", "notify", "paymentReceived"), execution.observedSteps)
            assertEquals(null, executions.execution(WorkflowExecutionQuery(workflowId = "unknown")).execution)
        }

        @Test
        fun `metrics count starts and ends, open instances and open waits`() {
            val now = Instant.now().epochSecond
            val metrics = executions.metrics(WorkflowMetricsQuery(timeStart = now - 600, timeEnd = now + 60))

            val annotated = metrics.workflows.single { it.definitionName == "annotatedOrder" }
            assertEquals(1, annotated.started)
            assertEquals(1, annotated.openInstances)
            assertEquals(1, annotated.waitingSteps)
            val lambda = metrics.workflows.single { it.definitionName == "lambda" }
            assertEquals(1, lambda.completed)
            assertEquals(0, lambda.openInstances)
            assertTrue(metrics.steps.isEmpty())
        }

        @Test
        fun `metrics of one definition have a series and measured steps`() {
            val now = Instant.now().epochSecond
            val metrics = executions.metrics(WorkflowMetricsQuery(
                    timeStart = now - 600, timeEnd = now + 60, definitionName = "lambda", definitionVersion = "0.0.1",
            ))

            assertEquals(1, metrics.series.sumOf { it.started })
            assertEquals(1, metrics.series.sumOf { it.completed })
            assertEquals(listOf("first", "second"), metrics.steps.map { it.stepName })
            assertTrue(metrics.steps.all { it.executions == 1L && it.completed == 1L && it.measuredAttempts == 1L })
            assertEquals(listOf("first", "second"), metrics.observedSteps)
        }
    }

    @Nested
    inner class Graphs {

        @Test
        fun `an annotated workflow method is read through the engine's handler`() {
            val description = definition("annotatedOrder")
            val graph = description.graph!!

            assertEquals(emptyList<String>(), description.warnings)
            assertEquals(setOf("check", "approve", "notify", "paymentReceived"), graph.nodes.mapNotNull { it.name }.toSet())
            assertTrue(graph.nodes.any { it.kind == WorkflowGraphNodeKind.EXCLUSIVE_GATEWAY })
        }

        @Test
        fun `a declarative lambda is read from the class that created it`() {
            val graph = definition("lambda").graph!!

            assertEquals(listOf("first", "second"), graph.nodes.mapNotNull { it.name })
        }

        @Test
        fun `definitions with different steps have different fingerprints`() {
            val fingerprints = responder.definitions().definitions.map { it.fingerprint }

            assertTrue(fingerprints.all { it != null && it.length == 12 })
            assertEquals(2, fingerprints.toSet().size)
        }

        @Test
        fun `every definition with a graph has a BPMN document`() {
            responder.definitions().definitions.forEach {
                assertNotNull(it.bpmnXml, it.name)
                assertTrue(it.bpmnXml!!.contains("<bpmn:process"), it.name)
            }
        }
    }
}

class AnnotatedOrderWorkflow {

    @Workflow(workflowName = "annotatedOrder", startOnEventName = "OrderPlaced", idProperty = "orderId")
    fun handle(ctx: SimpleWorkflowContext) {
        val ok = ctx.awaitExecute("check", Boolean::class.javaObjectType) { true }
        if (ok) {
            ctx.awaitExecute("approve", Boolean::class.javaObjectType) { true }
        }
        ctx.awaitExecute("notify", Boolean::class.javaObjectType) { true }
        ctx.awaitEvent(
                "paymentReceived",
                Map::class.java,
                associate(payloadProperty("orderId"), equalsTo(ctx.workflowPayload().getValue("orderId")!!)),
                UnaryOperator.identity(),
        )
    }
}
