package io.axoniq.platform.framework.workflow

import io.axoniq.platform.framework.api.workflow.WorkflowTelemetryAck
import io.axoniq.platform.framework.api.workflow.WorkflowTelemetryBatch
import io.axoniq.platform.framework.client.AxoniqConsoleRSocketClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.axonframework.messaging.core.MessageType
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.axonframework.messaging.eventhandling.GenericEventMessage
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Instant
import java.util.concurrent.CompletionException

class PlatformWorkflowTelemetryTest {
    private val client = mockk<AxoniqConsoleRSocketClient>()
    private val context = mockk<ProcessingContext>()
    private val handler = PlatformWorkflowTelemetryHandler(sourceId = "orders", client = client)
    private val metadata = mapOf("workflowId" to "workflow-1", "workflowStatus" to "STARTED",
        "workflowDefinitionName" to "OrderWorkflow", "workflowDefinitionVersion" to "2")

    @BeforeEach
    fun setUp() {
        every { context.getResource(TrackingToken.RESOURCE_KEY) } returns GlobalSequenceTrackingToken(42)
    }

    @Nested
    inner class Normalization {
        @Test
        fun `whitelists metadata without reading payload`() {
            val event = event(metadata + ("secret" to "never-export"))
            val result = handler.normalize(event = event, context = context)!!
            assertEquals("workflow-1", result.workflowId)
            assertEquals("event-1", result.eventId)
            assertEquals("OrderWorkflow", result.definitionName)
            assertEquals("2", result.definitionVersion)
            assertEquals(42, result.position)
            assertNull(result.stepName)
            assertFalse(result.toString().contains("never-export"))
        }

        @Test
        fun `step version comes from the message type and waits retain their primitive`() {
            val event = event(mapOf("workflowId" to "workflow-1", "stepType" to "TIMED_OUT",
                "stepName" to "sleep", "stepPrimitive" to "WAIT_FOR_EVENT"))
            val result = handler.normalize(event = event, context = context)!!
            assertEquals("3", result.definitionVersion)
            assertEquals("TIMED_OUT", result.status)
            assertEquals("WAIT_FOR_EVENT", result.primitive)
        }

        @Test
        fun `ignores business events and version markers`() {
            assertNull(handler.normalize(event = event(emptyMap()), context = context))
            assertNull(handler.normalize(event = event(metadata + ("versionChangeId" to "redesign")), context = context))
        }

        @Test
        fun `rejects token positions that cannot order individual events`() {
            every { context.getResource(TrackingToken.RESOURCE_KEY) } returns mockk<TrackingToken>()
            assertThrows(IllegalArgumentException::class.java) { handler.normalize(event = event(metadata), context = context) }
        }
    }

    @Nested
    inner class Acknowledgements {
        @Test
        fun `does not complete processing until the remote acknowledgement arrives`() {
            val acknowledgement = Sinks.one<WorkflowTelemetryAck>()
            val captured = slot<WorkflowTelemetryBatch>()
            every { client.sendAcknowledgedReport(capture(captured), any(), WorkflowTelemetryAck::class.java) } returns acknowledgement.asMono()
            val completion = handler.handle(event(metadata), context).asCompletableFuture()
            assertFalse(completion.isDone)
            assertEquals("orders", captured.captured.sourceId)
            acknowledgement.tryEmitValue(WorkflowTelemetryAck(eventIds = listOf("event-1")))
            completion.join()
            assertTrue(completion.isDone)
        }

        @Test
        fun `connection failure propagates so the processor can redeliver`() {
            every { client.sendAcknowledgedReport(any(), any(), WorkflowTelemetryAck::class.java) } returns Mono.error(IllegalStateException("disconnected"))
            assertThrows(CompletionException::class.java) { handler.handle(event(metadata), context).asCompletableFuture().join() }
        }

        @Test
        fun `empty and incomplete acknowledgements cannot advance processing`() {
            every { client.sendAcknowledgedReport(any(), any(), WorkflowTelemetryAck::class.java) } returns Mono.empty()
            assertThrows(CompletionException::class.java) { handler.handle(event(metadata), context).asCompletableFuture().join() }
            every { client.sendAcknowledgedReport(any(), any(), WorkflowTelemetryAck::class.java) } returns Mono.just(WorkflowTelemetryAck(eventIds = emptyList()))
            assertThrows(CompletionException::class.java) { handler.handle(event(metadata), context).asCompletableFuture().join() }
        }
    }

    private fun event(metadata: Map<String, String>) = GenericEventMessage(
        "event-1", MessageType("OrderEvent", "3"), "never-export", metadata, Instant.parse("2026-10-19T10:00:00Z"),
    )
}
