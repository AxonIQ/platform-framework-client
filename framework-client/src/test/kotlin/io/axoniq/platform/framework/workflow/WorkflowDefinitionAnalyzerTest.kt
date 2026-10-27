/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.workflow

import io.axoniq.framework.workflow.dsl.simple.SimpleWorkflowContext
import io.axoniq.framework.workflow.runtime.api.execution.context.WorkflowDefinition
import io.axoniq.platform.framework.api.WorkflowGraph
import io.axoniq.platform.framework.api.WorkflowGraphEdgeKind
import io.axoniq.platform.framework.api.WorkflowGraphNode
import io.axoniq.platform.framework.api.WorkflowGraphNodeKind
import io.axoniq.platform.framework.api.WorkflowStepPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.objectweb.asm.Type
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class WorkflowDefinitionAnalyzerTest {

    private val analyzer = WorkflowDefinitionAnalyzer(javaClass.classLoader)

    private fun javaMethod(name: String) = MethodRef(
            Type.getInternalName(JavaWorkflows::class.java),
            name,
            Type.getMethodDescriptor(JavaWorkflows::class.java.getMethod(name, SimpleWorkflowContext::class.java)),
    )

    private fun WorkflowGraph.step(name: String): WorkflowGraphNode =
            nodes.single { it.kind == WorkflowGraphNodeKind.STEP && it.name == name }

    private fun WorkflowGraph.single(kind: WorkflowGraphNodeKind) = nodes.single { it.kind == kind }

    private fun WorkflowGraph.next(node: WorkflowGraphNode) = edges.filter { it.from == node.id }.map { edge -> nodes.single { it.id == edge.to } }

    /** The next node, looking through a gateway the analysis put in front of a choice. */
    private fun WorkflowGraph.reachable(node: WorkflowGraphNode): List<WorkflowGraphNode> = next(node).flatMap {
        if (it.kind == WorkflowGraphNodeKind.EXCLUSIVE_GATEWAY) next(it) else listOf(it)
    }

    @Nested
    inner class StepsAndChoices {

        private val result = analyzer.analyze(javaMethod("orderFulfilment"))
        private val graph = result.graph

        @Test
        fun `every step the definition calls becomes a named step`() {
            val steps = graph.nodes.filter { it.kind == WorkflowGraphNodeKind.STEP }.map { it.name }.toSet()

            assertEquals(
                    setOf("reserveStock", "orderRejected", "paymentReceived", "prepareShipment", "waitForCourier", "orderShipped", "sendInvoice"),
                    steps,
            )
            assertEquals(WorkflowStepPrimitive.WAIT_FOR_EVENT, graph.step("paymentReceived").primitive)
            assertEquals(WorkflowStepPrimitive.SLEEP, graph.step("waitForCourier").primitive)
            assertFalse(graph.step("prepareShipment").awaited)
        }

        @Test
        fun `an if after a step becomes an exclusive gateway with both branches`() {
            val afterReservation = graph.next(graph.step("reserveStock")).single()

            assertEquals(WorkflowGraphNodeKind.EXCLUSIVE_GATEWAY, afterReservation.kind)
            val branches = graph.next(afterReservation)
            assertTrue(branches.any { it.name == "orderRejected" })
            assertTrue(branches.any { it.kind == WorkflowGraphNodeKind.PARALLEL_FORK })
        }

        @Test
        fun `a failure ends its branch`() {
            val rejected = graph.step("orderRejected")

            assertEquals(listOf(WorkflowGraphNodeKind.FAIL), graph.next(rejected).map { it.kind })
            assertTrue(graph.edges.none { it.from == graph.single(WorkflowGraphNodeKind.FAIL).id })
        }

        @Test
        fun `steps joined by allMatch run in parallel between a fork and a join`() {
            val fork = graph.single(WorkflowGraphNodeKind.PARALLEL_FORK)
            val join = graph.single(WorkflowGraphNodeKind.PARALLEL_JOIN)

            assertEquals(setOf("paymentReceived", "prepareShipment"), graph.next(fork).map { it.name }.toSet())
            assertEquals(listOf(join), graph.next(graph.step("paymentReceived")))
            assertEquals(listOf(join), graph.next(graph.step("prepareShipment")))
            assertEquals("allMatch", join.name)
        }

        @Test
        fun `a step repeated in a loop gets a loop edge`() {
            val courier = graph.step("waitForCourier")

            assertTrue(graph.edges.any { it.to == courier.id && it.kind == WorkflowGraphEdgeKind.LOOP })
            assertTrue(graph.reachable(courier).any { it.name == "orderShipped" })
        }

        @Test
        fun `a helper method that takes the context is followed`() {
            val invoice = graph.step("sendInvoice")

            assertTrue(graph.reachable(graph.step("orderShipped")).contains(invoice))
            assertEquals(listOf(WorkflowGraphNodeKind.END), graph.next(invoice).map { it.kind })
            assertTrue(invoice.source!!.contains("sendInvoice"))
        }

        @Test
        fun `nothing is reported as unreadable`() {
            assertEquals(emptyList<String>(), result.warnings)
            assertTrue(graph.nodes.none { it.kind == WorkflowGraphNodeKind.UNRECOGNISED })
        }
    }

    @Nested
    inner class StepsStartedInHelpers {

        private val result = analyzer.analyze(javaMethod("paymentAcrossHelpers"))
        private val graph = result.graph

        @Test
        fun `a step started without waiting runs on a branch from where it is started`() {
            val fork = graph.next(graph.single(WorkflowGraphNodeKind.START)).single()

            assertEquals(WorkflowGraphNodeKind.PARALLEL_FORK, fork.kind)
            assertEquals(setOf("paymentConfirmed", "reserve"), graph.next(fork).map { it.name }.toSet())
        }

        @Test
        fun `steps whose results are passed through helpers meet in one join`() {
            val join = graph.single(WorkflowGraphNodeKind.PARALLEL_JOIN)

            assertEquals("allMatch", join.name)
            assertEquals(listOf(join), graph.next(graph.step("paymentConfirmed")))
            assertEquals(listOf(join), graph.next(graph.step("initiatePayment")))
        }

        @Test
        fun `a step name chosen from constants lists every constant`() {
            assertNotNull(graph.step("recordTimeout / recordFailure"))
            assertEquals(emptyList<String>(), result.warnings)
        }
    }

    @Nested
    inner class WhatCannotBeRead {

        @Test
        fun `a step name computed at runtime is drawn without a name and reported`() {
            val result = analyzer.analyze(javaMethod("computedStepName"))

            val step = result.graph.nodes.single { it.kind == WorkflowGraphNodeKind.STEP }
            assertNull(step.name)
            assertTrue(result.warnings.single().contains("name computed at runtime"))
        }

        @Test
        fun `a return before any step still reaches the end`() {
            val graph = analyzer.analyze(javaMethod("earlyReturn")).graph

            val start = graph.single(WorkflowGraphNodeKind.START)
            val reachable = graph.reachable(start)
            assertTrue(reachable.any { it.kind == WorkflowGraphNodeKind.END })
            assertTrue(reachable.any { it.name == "onlyWithPayload" })
        }
    }

    @Nested
    inner class LocatingTheCode {

        private fun analyze(definition: Any, name: String): AnalysisResult {
            val warnings = mutableListOf<String>()
            val located = WorkflowDefinitionLocator.locate(definition, name, warnings)
            assertNotNull(located, "not located: $warnings")
            return WorkflowDefinitionAnalyzer(located!!.classLoader).analyze(located.method)
        }

        @Test
        fun `a Java lambda is read from the class that created it`() {
            val graph = analyze(JavaWorkflows.lambdaDefinition(), "lambda").graph

            val first = graph.step("first")
            assertEquals(listOf("second"), graph.next(first).map { it.name })
        }

        @Test
        fun `a method reference is read from the referenced method`() {
            val graph = analyze(JavaWorkflows.methodReferenceDefinition(), "reference").graph

            assertNotNull(graph.step("onlyWithPayload"))
        }

        @Test
        fun `a Kotlin lambda is read from the class that created it`() {
            val graph = analyze(KotlinWorkflows.definition, "kotlin").graph

            assertEquals(setOf("validate", "approve", "reject"), graph.nodes.mapNotNull { it.name }.toSet())
            val choice = graph.next(graph.step("validate")).single()
            assertEquals(WorkflowGraphNodeKind.EXCLUSIVE_GATEWAY, choice.kind)
        }
    }

    @Nested
    inner class Bpmn {

        private val graph = analyzer.analyze(javaMethod("orderFulfilment")).graph
        private val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(WorkflowBpmnWriter.write("orderFulfilment", "1.0", graph).byteInputStream())

        private fun elements(name: String) = document.getElementsByTagNameNS("*", name).let { list ->
            (0 until list.length).map { list.item(it) as Element }
        }

        @Test
        fun `every node is a BPMN element with a shape`() {
            val shapes = elements("BPMNShape").map { it.getAttribute("bpmnElement") }.toSet()

            assertEquals(graph.nodes.map { it.id }.toSet(), shapes)
            assertEquals(3, elements("serviceTask").size)
            assertEquals(2, elements("intermediateCatchEvent").size)
            assertEquals(3, elements("exclusiveGateway").size)
            assertEquals(2, elements("parallelGateway").size)
        }

        @Test
        fun `no flow runs through an element other than its own ends`() {
            listOf("orderFulfilment", "paymentAcrossHelpers").forEach { method ->
                val bpmn = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
                        .parse(WorkflowBpmnWriter.write(method, "1", analyzer.analyze(javaMethod(method)).graph).byteInputStream())
                val all = { name: String -> bpmn.getElementsByTagNameNS("*", name).let { list -> (0 until list.length).map { list.item(it) as Element } } }
                val bounds = all("BPMNShape").associate { shape ->
                    val box = shape.getElementsByTagNameNS("*", "Bounds").item(0) as Element
                    val (x, y) = box.getAttribute("x").toInt() to box.getAttribute("y").toInt()
                    shape.getAttribute("bpmnElement") to listOf(x, y, x + box.getAttribute("width").toInt(), y + box.getAttribute("height").toInt())
                }
                val flows = all("sequenceFlow").associateBy { it.getAttribute("id") }
                all("BPMNEdge").forEach { edge ->
                    val flow = flows.getValue(edge.getAttribute("bpmnElement"))
                    val ends = setOf(flow.getAttribute("sourceRef"), flow.getAttribute("targetRef"))
                    val points = edge.getElementsByTagNameNS("*", "waypoint").let { list ->
                        (0 until list.length).map { (list.item(it) as Element).let { p -> p.getAttribute("x").toInt() to p.getAttribute("y").toInt() } }
                    }
                    points.zipWithNext().forEach { (a, b) ->
                        bounds.filterKeys { it !in ends }.forEach { (element, box) ->
                            val (left, top, right, bottom) = box
                            val crosses = if (a.second == b.second) a.second in (top + 1) until bottom && maxOf(a.first, b.first) > left && minOf(a.first, b.first) < right
                            else a.first in (left + 1) until right && maxOf(a.second, b.second) > top && minOf(a.second, b.second) < bottom
                            assertFalse(crosses, "$method: ${flow.getAttribute("id")} $a-$b runs through $element")
                        }
                    }
                }
            }
        }

        @Test
        fun `every flow connects two elements and has an edge`() {
            val ids = graph.nodes.map { it.id }.toSet()
            val flows = elements("sequenceFlow")

            assertEquals(graph.edges.size, flows.size)
            assertTrue(flows.all { it.getAttribute("sourceRef") in ids && it.getAttribute("targetRef") in ids })
            assertEquals(flows.size, elements("BPMNEdge").size)
        }
    }
}

/** A workflow definition written as a Kotlin lambda. */
object KotlinWorkflows {
    val definition = WorkflowDefinition<SimpleWorkflowContext> { ctx ->
        val valid = ctx.awaitExecute("validate", Boolean::class.javaObjectType) { true }
        if (valid) {
            ctx.awaitExecute("approve", Boolean::class.javaObjectType) { true }
        } else {
            ctx.awaitExecute("reject", Boolean::class.javaObjectType) { false }
        }
    }
}
