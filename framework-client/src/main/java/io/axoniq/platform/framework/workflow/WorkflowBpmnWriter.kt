/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.workflow

import io.axoniq.platform.framework.api.WorkflowGraph
import io.axoniq.platform.framework.api.WorkflowGraphEdgeKind
import io.axoniq.platform.framework.api.WorkflowGraphNode
import io.axoniq.platform.framework.api.WorkflowGraphNodeKind
import io.axoniq.platform.framework.api.WorkflowStepPrimitive

/**
 * Writes a [WorkflowGraph] as BPMN 2.0, with diagram interchange so a viewer such as bpmn-js renders it without a
 * layout step of its own. Nodes are laid out left to right by their distance from the start, one column per step.
 */
internal object WorkflowBpmnWriter {
    private const val COLUMN = 170
    private const val ROW = 120
    private const val MARGIN = 100
    private const val LABEL_HEIGHT = 45
    private const val LABEL_WIDTH = 90
    private const val DETOUR_OFFSET = 20

    fun write(name: String, version: String, graph: WorkflowGraph): String {
        val processId = "Process_" + "$name-$version".replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val positions = layout(graph)
        val byId = graph.nodes.associateBy { it.id }
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append("""<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" """)
            append("""xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" """)
            append("""xmlns:di="http://www.omg.org/spec/DD/20100524/DI" id="Definitions_$processId" targetNamespace="https://axoniq.io/workflow">""")
            append('\n')
            append("""  <bpmn:process id="$processId" name="${escape("$name $version")}" isExecutable="false">""").append('\n')
            graph.nodes.forEach { append("    ").append(element(it)).append('\n') }
            graph.edges.forEachIndexed { index, edge ->
                val label = if (edge.kind == WorkflowGraphEdgeKind.ERROR) """ name="on error"""" else ""
                append("""    <bpmn:sequenceFlow id="Flow_$index" sourceRef="${edge.from}" targetRef="${edge.to}"$label/>""").append('\n')
            }
            append("  </bpmn:process>\n")
            append("""  <bpmndi:BPMNDiagram id="Diagram_$processId"><bpmndi:BPMNPlane id="Plane_$processId" bpmnElement="$processId">""").append('\n')
            graph.nodes.forEach { node ->
                val (cx, cy) = positions.getValue(node.id)
                val (width, height) = size(node)
                append("""    <bpmndi:BPMNShape id="${node.id}_di" bpmnElement="${node.id}">""")
                append("""<dc:Bounds x="${cx - width / 2}" y="${cy - height / 2}" width="$width" height="$height"/></bpmndi:BPMNShape>""").append('\n')
            }
            val boxes = graph.nodes.associate { it.id to box(it, positions) }
            graph.edges.forEachIndexed { index, edge ->
                val from = byId.getValue(edge.from)
                val to = byId.getValue(edge.to)
                append("""    <bpmndi:BPMNEdge id="Flow_${index}_di" bpmnElement="Flow_$index">""")
                waypoints(from, to, positions, boxes).forEach { (x, y) -> append("""<di:waypoint x="$x" y="$y"/>""") }
                append("</bpmndi:BPMNEdge>\n")
            }
            append("  </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>\n")
            append("</bpmn:definitions>\n")
        }
    }

    private fun element(node: WorkflowGraphNode): String {
        val id = node.id
        val label = escape(node.name ?: if (node.kind == WorkflowGraphNodeKind.STEP) "(name computed at runtime)" else "")
        val named = if (label.isEmpty()) "" else """ name="$label""""
        return when (node.kind) {
            WorkflowGraphNodeKind.START -> """<bpmn:startEvent id="$id"/>"""
            WorkflowGraphNodeKind.END -> """<bpmn:endEvent id="$id"/>"""
            WorkflowGraphNodeKind.FAIL -> """<bpmn:endEvent id="$id" name="failed"><bpmn:errorEventDefinition/></bpmn:endEvent>"""
            WorkflowGraphNodeKind.CANCEL -> """<bpmn:endEvent id="$id" name="cancelled"><bpmn:terminateEventDefinition/></bpmn:endEvent>"""
            WorkflowGraphNodeKind.EXCLUSIVE_GATEWAY -> """<bpmn:exclusiveGateway id="$id"/>"""
            WorkflowGraphNodeKind.PARALLEL_FORK -> """<bpmn:parallelGateway id="$id"/>"""
            WorkflowGraphNodeKind.PARALLEL_JOIN ->
                if (node.name == "anyMatch") """<bpmn:inclusiveGateway id="$id"$named/>""" else """<bpmn:parallelGateway id="$id"$named/>"""
            WorkflowGraphNodeKind.UNRECOGNISED ->
                """<bpmn:task id="$id" name="${escape("? ${node.name ?: "unrecognised"}")}"><bpmn:documentation>Code the analysis could not follow</bpmn:documentation></bpmn:task>"""
            WorkflowGraphNodeKind.STEP -> when (node.primitive) {
                WorkflowStepPrimitive.EXECUTE -> """<bpmn:serviceTask id="$id"$named/>"""
                WorkflowStepPrimitive.WAIT_FOR_EVENT -> """<bpmn:intermediateCatchEvent id="$id"$named><bpmn:messageEventDefinition/></bpmn:intermediateCatchEvent>"""
                WorkflowStepPrimitive.SLEEP -> """<bpmn:intermediateCatchEvent id="$id"$named><bpmn:timerEventDefinition/></bpmn:intermediateCatchEvent>"""
                WorkflowStepPrimitive.PUBLISH -> """<bpmn:intermediateThrowEvent id="$id"$named><bpmn:messageEventDefinition/></bpmn:intermediateThrowEvent>"""
                WorkflowStepPrimitive.MODIFY_PAYLOAD -> """<bpmn:scriptTask id="$id"$named/>"""
                else -> """<bpmn:task id="$id"$named/>"""
            }
        }
    }

    private fun size(node: WorkflowGraphNode): Pair<Int, Int> = when (node.kind) {
        WorkflowGraphNodeKind.EXCLUSIVE_GATEWAY, WorkflowGraphNodeKind.PARALLEL_FORK, WorkflowGraphNodeKind.PARALLEL_JOIN -> 50 to 50
        WorkflowGraphNodeKind.START, WorkflowGraphNodeKind.END, WorkflowGraphNodeKind.FAIL, WorkflowGraphNodeKind.CANCEL -> 36 to 36
        WorkflowGraphNodeKind.STEP -> when (node.primitive) {
            WorkflowStepPrimitive.WAIT_FOR_EVENT, WorkflowStepPrimitive.SLEEP, WorkflowStepPrimitive.PUBLISH -> 36 to 36
            else -> 110 to 80
        }
        WorkflowGraphNodeKind.UNRECOGNISED -> 110 to 80
    }

    /** Column = longest distance from the start over forward edges; nodes share a column top to bottom. */
    private fun layout(graph: WorkflowGraph): Map<String, Pair<Int, Int>> {
        val forward = graph.edges.filter { it.kind != WorkflowGraphEdgeKind.LOOP }
        val incoming = graph.nodes.associate { node -> node.id to forward.count { it.to == node.id } }.toMutableMap()
        val column = HashMap<String, Int>()
        val queue = ArrayDeque(graph.nodes.filter { incoming[it.id] == 0 }.map { it.id })
        queue.forEach { column[it] = 0 }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            forward.filter { it.from == current }.forEach { edge ->
                column[edge.to] = maxOf(column[edge.to] ?: 0, column.getValue(current) + 1)
                incoming[edge.to] = incoming.getValue(edge.to) - 1
                if (incoming[edge.to] == 0) queue.add(edge.to)
            }
        }
        val last = (column.values.maxOrNull() ?: 0) + 1
        graph.nodes.forEach { column.putIfAbsent(it.id, last) }
        val rows = HashMap<Int, Int>()
        return graph.nodes.associate { node ->
            val c = column.getValue(node.id)
            val r = rows.merge(c, 1, Int::plus)!! - 1
            node.id to (MARGIN + c * COLUMN to MARGIN + r * ROW)
        }
    }

    /** Where a node sits, label included: an event's or gateway's name is drawn below it. */
    private class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun crossedBy(a: Pair<Int, Int>, b: Pair<Int, Int>): Boolean {
            val (x1, y1) = a
            val (x2, y2) = b
            return if (y1 == y2) y1 in (top + 1) until bottom && maxOf(x1, x2) > left && minOf(x1, x2) < right
            else x1 in (left + 1) until right && maxOf(y1, y2) > top && minOf(y1, y2) < bottom
        }
    }

    private fun box(node: WorkflowGraphNode, positions: Map<String, Pair<Int, Int>>): Box {
        val (x, y) = positions.getValue(node.id)
        val (width, height) = size(node)
        val labelled = width < 100 && (!node.name.isNullOrEmpty() || node.kind == WorkflowGraphNodeKind.FAIL ||
                node.kind == WorkflowGraphNodeKind.CANCEL)
        val half = if (labelled) maxOf(width, LABEL_WIDTH) / 2 else width / 2
        return Box(x - half, y - height / 2, x + half, y + height / 2 + if (labelled) LABEL_HEIGHT else 0)
    }

    private fun column(x: Int) = (x - MARGIN) / COLUMN

    /** The free channel between column [column] and the next one. */
    private fun channel(column: Int) = MARGIN + column * COLUMN + COLUMN / 2

    /**
     * Routes an edge so it crosses no other node or label. A forward edge turns in one of the free channels between
     * columns; when every channel would cross something, it runs along a lane below the nodes it passes. An edge back
     * to an earlier column goes around underneath everything in between.
     */
    private fun waypoints(
            from: WorkflowGraphNode,
            to: WorkflowGraphNode,
            positions: Map<String, Pair<Int, Int>>,
            boxes: Map<String, Box>,
    ): List<Pair<Int, Int>> {
        val (fx, fy) = positions.getValue(from.id)
        val (tx, ty) = positions.getValue(to.id)
        val fromHalf = size(from).first / 2
        val toHalf = size(to).first / 2
        val columns = column(minOf(fx, tx))..column(maxOf(fx, tx))
        val others = boxes.filterKeys { it != from.id && it != to.id }.values
        val clear = { route: List<Pair<Int, Int>> -> route.zipWithNext().none { (a, b) -> others.any { it.crossedBy(a, b) } } }
        val below = boxes.values.filter { column((it.left + it.right) / 2) in columns }.maxOf { it.bottom } + 30
        if (tx <= fx) {
            return listOf(fx to fy + size(from).second / 2, fx to below, tx to below, tx to ty + size(to).second / 2)
        }
        val start = fx + fromHalf to fy
        val end = tx - toHalf to ty
        if (fy == ty && clear(listOf(start, end))) return listOf(start, end)
        (column(fx) until column(tx)).forEach { turn ->
            val x = channel(turn)
            val route = listOf(start, x to fy, x to ty, end)
            if (clear(route)) return route.distinct()
        }
        // Beside the channels rather than in them, so the detour does not run along an edge turning in the same channel.
        val out = channel(column(fx)) - DETOUR_OFFSET
        val into = channel(column(tx) - 1) + DETOUR_OFFSET
        return listOf(start, out to fy, out to below, into to below, into to ty, end).distinct()
    }

    private fun escape(text: String) = text
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
