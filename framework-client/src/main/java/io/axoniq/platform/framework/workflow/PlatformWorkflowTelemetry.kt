package io.axoniq.platform.framework.workflow

import io.axoniq.platform.framework.api.workflow.WORKFLOW_TELEMETRY_ROUTE
import io.axoniq.platform.framework.api.workflow.WorkflowTelemetryAck
import io.axoniq.platform.framework.api.workflow.WorkflowTelemetryBatch
import io.axoniq.platform.framework.api.workflow.WorkflowTelemetryEvent
import io.axoniq.platform.framework.api.workflow.WorkflowDefinitionObservation
import io.axoniq.platform.framework.client.AxoniqConsoleRSocketClient
import org.axonframework.common.infra.ComponentDescriptor
import org.axonframework.messaging.core.Message
import org.axonframework.messaging.core.MessageStream
import org.axonframework.messaging.core.QualifiedName
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory
import org.axonframework.messaging.eventhandling.EventHandlingComponent
import org.axonframework.messaging.eventhandling.EventMessage
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken
import org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore
import org.axonframework.messaging.eventstreaming.EventCriteria
import org.axonframework.messaging.eventstreaming.StreamableEventSource
import java.time.Duration
import javax.sql.DataSource

object PlatformWorkflowTelemetry {
    @JvmStatic
    @JvmOverloads
    fun module(sourceId: String, dataSource: DataSource, includePayloads: Boolean = false) = EventProcessorModule.pooledStreaming("PlatformWorkflowTelemetry[$sourceId]")
        .eventHandlingComponents { components ->
            components.declarative("PlatformWorkflowTelemetry") { configuration ->
                PlatformHistoryProjector(
                    sourceId = sourceId,
                    client = configuration.getComponent(AxoniqConsoleRSocketClient::class.java),
                    dataSource = dataSource,
                    includePayloads = includePayloads,
                )
            }
        }
        .customized { configuration, processor ->
            val tokenStore = configuration.getComponent(TokenStore::class.java)
            require(tokenStore !is InMemoryTokenStore) { "Workflow reporting requires a persistent TokenStore" }
            processor.eventSource(configuration.getComponent(StreamableEventSource::class.java))
                .tokenStore(tokenStore)
                .unitOfWorkFactory(configuration.getComponent(UnitOfWorkFactory::class.java))
                .initialToken { source -> source.firstToken(null) }
                .initialSegmentCount(1)
                .batchSize(1)
                .eventCriteria { EventCriteria.havingAnyTag() }
        }
        .build()
}

internal class PlatformWorkflowTelemetryHandler(
    private val sourceId: String,
    private val client: AxoniqConsoleRSocketClient,
) : EventHandlingComponent {
    init {
        require(sourceId.isNotBlank() && sourceId.length <= 255) { "Provide a stable workflow event source identifier" }
    }

    override fun handle(event: EventMessage, context: ProcessingContext): MessageStream.Empty<Message> {
        val telemetry = normalize(event = event, context = context) ?: return MessageStream.empty()
        return send(event = event, telemetry = telemetry)
    }

    internal fun send(event: EventMessage, telemetry: WorkflowTelemetryEvent, definition: WorkflowDefinitionObservation? = null): MessageStream.Empty<Message> {
        val future = client.sendAcknowledgedReport(
            payload = WorkflowTelemetryBatch(sourceId = sourceId, events = listOf(telemetry), definition = definition),
            route = WORKFLOW_TELEMETRY_ROUTE,
            responseType = WorkflowTelemetryAck::class.java,
        ).switchIfEmpty(reactor.core.publisher.Mono.error(IllegalStateException("Missing workflow acknowledgement")))
            .timeout(Duration.ofSeconds(30))
            .map { acknowledgement ->
                check(acknowledgement.eventIds == listOf(telemetry.eventId)) { "Incomplete workflow acknowledgement" }
                event as Message
            }.toFuture()
        return MessageStream.fromFuture(future).ignoreEntries()
    }

    internal fun normalize(event: EventMessage, context: ProcessingContext): WorkflowTelemetryEvent? {
        val metadata = event.metadata()
        val workflowId = metadata["workflowId"] ?: return null
        if (metadata.containsKey("versionChangeId")) return null
        val lifecycle = metadata["workflowStatus"]
        val status = lifecycle ?: metadata["stepType"] ?: return null
        return WorkflowTelemetryEvent(
            eventId = event.identifier(), workflowId = workflowId, position = position(context),
            occurredAt = event.timestamp().toEpochMilli(), status = status,
            definitionName = metadata["workflowDefinitionName"],
            definitionVersion = metadata["workflowDefinitionVersion"] ?: event.type().version(),
            stepName = if (lifecycle == null) requireNotNull(metadata["stepName"]) else null,
            primitive = if (lifecycle == null) metadata["stepPrimitive"] else null,
        )
    }

    internal fun position(context: ProcessingContext): Long {
        var token = TrackingToken.fromContext(context).orElseThrow {
            IllegalStateException("Workflow reporting requires a tracking token")
        }
        while (token is ReplayToken) token = requireNotNull(token.currentToken)
        require(token is GlobalSequenceTrackingToken) { "Workflow reporting requires a single global sequence event source" }
        return token.position().asLong
    }

    override fun supportedEvents(): Set<QualifiedName> = emptySet()
    override fun supports(eventName: QualifiedName) = true
    override fun sequenceIdentifierFor(event: EventMessage, context: ProcessingContext): Any =
        event.metadata()["workflowId"] ?: event.identifier()

    override fun describeTo(descriptor: ComponentDescriptor) {
        descriptor.describeProperty("sourceId", sourceId)
    }
}
