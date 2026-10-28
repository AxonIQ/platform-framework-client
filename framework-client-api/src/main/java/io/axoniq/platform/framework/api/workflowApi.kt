/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.api

/**
 * The workflow definitions registered in an application, each with the graph the client read from the definition's
 * code. Requested on demand over [Routes.Workflow.DEFINITIONS]; nothing about workflows is pushed.
 */
data class WorkflowDefinitionsResult(
        val definitions: List<WorkflowDefinitionDescription>,
)

data class WorkflowDefinitionDescription(
        val name: String,
        val version: String,
        val startEvents: List<String>,
        /** Null when the definition's code could not be located; [warnings] says why. */
        val graph: WorkflowGraph?,
        /** BPMN 2.0 with diagram interchange, generated from [graph], so a BPMN viewer can render it as is. */
        val bpmnXml: String?,
        /** What the analysis could not read and drew as unrecognised, or why there is no graph. */
        val warnings: List<String>,
        /**
         * Identifies the shape of [graph]: its steps, their names and how they connect, not the code around them. Nodes
         * running different code for the same [version] report different fingerprints when the steps differ, which is
         * when resuming an instance on the other node fails replay.
         */
        val fingerprint: String? = null,
)

/**
 * The shape of a workflow as read from its definition's bytecode: steps and gateways, and the flow between them.
 * It is a static reading of the code, so a step whose name is computed at runtime shows as [WorkflowGraphNode.name]
 * `null`, and code the analysis cannot follow shows as [WorkflowGraphNodeKind.UNRECOGNISED].
 */
data class WorkflowGraph(
        val nodes: List<WorkflowGraphNode>,
        val edges: List<WorkflowGraphEdge>,
)

data class WorkflowGraphNode(
        val id: String,
        val kind: WorkflowGraphNodeKind,
        /** The step name for steps, the method name for unrecognised calls. */
        val name: String? = null,
        val primitive: WorkflowStepPrimitive? = null,
        /** Whether the definition waits for the step where it is started, rather than joining it later. */
        val awaited: Boolean = true,
        /** `Class.method:line` of the code the node was read from. */
        val source: String? = null,
)

enum class WorkflowGraphNodeKind {
    START,
    END,
    FAIL,
    CANCEL,
    STEP,
    EXCLUSIVE_GATEWAY,
    PARALLEL_FORK,
    PARALLEL_JOIN,
    UNRECOGNISED,
}

enum class WorkflowStepPrimitive {
    EXECUTE,
    WAIT_FOR_EVENT,
    SLEEP,
    PUBLISH,
    MODIFY_PAYLOAD,
    MIGRATE_VERSION,
    CANCEL_STEP,
}

data class WorkflowGraphEdge(
        val from: String,
        val to: String,
        val kind: WorkflowGraphEdgeKind = WorkflowGraphEdgeKind.NORMAL,
)

enum class WorkflowGraphEdgeKind {
    NORMAL,
    /** Flows back to an earlier point of the definition. */
    LOOP,
    /** Taken when the code before it throws, into a catch block of the definition. */
    ERROR,
}

/**
 * Asks for a page of workflow executions, newest first, finished ones included. [search] matches the workflow id or the
 * definition name, ignoring case; [status] is the instance's status (`STARTED` while it runs).
 */
data class WorkflowExecutionsQuery(
        val search: String = "",
        val status: String? = null,
        val offset: Int = 0,
        val limit: Int = 50,
)

data class WorkflowExecutionsResult(
        val items: List<WorkflowExecutionSummary>,
        val total: Long,
)

/** An execution as its start and end events record it; [lastEventAt] is the latest of those. */
data class WorkflowExecutionSummary(
        val workflowId: String,
        val definitionName: String,
        val definitionVersion: String,
        val status: String,
        val startedAt: Long?,
        val finishedAt: Long?,
        val lastEventAt: Long,
)

data class WorkflowExecutionQuery(
        val workflowId: String,
)

/** One execution; [execution] is null when the application has no such workflow. */
data class WorkflowExecutionResult(
        val execution: WorkflowExecutionSummary?,
        /** The step names the execution recorded, sorted. */
        val observedSteps: List<String>,
)

/** The execution's events after position [after], at most [limit] of them. */
data class WorkflowExecutionEventsQuery(
        val workflowId: String,
        val after: Long = -1,
        val limit: Int = 100,
)

data class WorkflowExecutionEventsResult(
        val events: List<WorkflowExecutionEventDescription>,
        val hasMore: Boolean,
)

/**
 * One event of an execution. [position] orders the execution's events, starting at 0. [status] is the instance's
 * status for a start or end event, otherwise the step's; [stepName] and [primitive] are set for step events only.
 * [payloadJson] is sent only when the application's domain event access mode shows payloads, and only up to 16 KiB.
 */
data class WorkflowExecutionEventDescription(
        val eventId: String,
        val position: Long,
        val occurredAt: Long,
        val status: String,
        val stepName: String?,
        val primitive: String?,
        val eventName: String?,
        val payloadJson: String?,
)
