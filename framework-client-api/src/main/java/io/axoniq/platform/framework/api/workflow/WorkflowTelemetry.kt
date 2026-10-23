package io.axoniq.platform.framework.api.workflow

const val WORKFLOW_TELEMETRY_ROUTE = "workflow-telemetry-v1"

data class WorkflowTelemetryBatch(
    val sourceId: String,
    val events: List<WorkflowTelemetryEvent>,
    val schemaVersion: Int = 1,
    val definition: WorkflowDefinitionObservation? = null,
)

data class WorkflowDefinitionObservation(
    val name: String,
    val version: String,
    val steps: List<String>,
    val bpmnXml: String? = null,
)

data class WorkflowTelemetryEvent(
    val eventId: String,
    val workflowId: String,
    val position: Long,
    val occurredAt: Long,
    val status: String,
    val definitionName: String? = null,
    val definitionVersion: String,
    val stepName: String? = null,
    val primitive: String? = null,
    val eventName: String? = null,
    val payloadJson: String? = null,
)

data class WorkflowTelemetryAck(val eventIds: List<String>)
