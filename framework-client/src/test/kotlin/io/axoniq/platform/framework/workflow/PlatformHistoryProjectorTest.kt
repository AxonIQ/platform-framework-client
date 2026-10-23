package io.axoniq.platform.framework.workflow

import io.axoniq.platform.framework.api.workflow.WorkflowTelemetryAck
import io.axoniq.platform.framework.api.workflow.WorkflowTelemetryBatch
import io.axoniq.platform.framework.client.AxoniqConsoleRSocketClient
import io.mockk.every
import io.mockk.mockk
import org.axonframework.messaging.core.MessageType
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.axonframework.messaging.eventhandling.GenericEventMessage
import org.axonframework.messaging.eventhandling.conversion.EventConverter
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter
import org.axonframework.conversion.jackson.JacksonConverter
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionException

class PlatformHistoryProjectorTest {
    private val client = mockk<AxoniqConsoleRSocketClient>()
    private val context = mockk<ProcessingContext>(relaxed = true)
    private lateinit var dataSource: JdbcDataSource
    private lateinit var projector: PlatformHistoryProjector
    private val batches = mutableListOf<WorkflowTelemetryBatch>()

    @BeforeEach
    fun setUp() {
        dataSource = JdbcDataSource().apply { setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1") }
        projector = newProjector()
        every { context.component(EventConverter::class.java) } returns DelegatingEventConverter(JacksonConverter())
        every { client.sendAcknowledgedReport(any(), any(), WorkflowTelemetryAck::class.java) } answers {
            val batch = firstArg<WorkflowTelemetryBatch>()
            batches.add(batch)
            Mono.just(WorkflowTelemetryAck(eventIds = batch.events.map { it.eventId }))
        }
    }

    @Nested
    inner class InspectionExport {
        @Test
        fun `payloads are excluded unless explicitly enabled`() {
            deliver(position = 1, status = "STARTED", payload = mapOf("order" to "sample"))
            assertNull(batches.single().events.single().payloadJson)
            projector = PlatformHistoryProjector(sourceId = "orders", client = client, dataSource = dataSource, includePayloads = true)
            deliver(position = 2, status = "COMPLETED", step = "reserve", payload = mapOf("reserved" to true))
            assertEquals("{\"reserved\":true}", batches.last().events.single().payloadJson)
            assertEquals("OrderEvent", batches.last().events.single().eventName)
        }

        @Test
        fun `oversize payloads do not prevent history reporting`() {
            projector = PlatformHistoryProjector(sourceId = "orders", client = client, dataSource = dataSource, includePayloads = true)
            deliver(position = 1, status = "STARTED", payload = mapOf("large" to "x".repeat(17000)))
            assertNull(batches.single().events.single().payloadJson)
        }
    }

    @Nested
    inner class ProjectedHistory {
        @Test
        fun `replayed retry payloads and version migrations use the framework state`() {
            deliver(position = 1, status = "STARTED")
            deliver(position = 2, status = "STARTED", step = "reserve")
            deliver(position = 3, status = "RETRYING", step = "reserve", payload = mapOf("attempt" to 1, "maxRetries" to 3, "error" to null))
            projector = newProjector()
            deliver(position = 4, status = "RETRY_STARTED", step = "reserve", payload = mapOf("attempt" to 1, "maxRetries" to 3, "error" to null))
            deliver(position = 5, status = "COMPLETED", step = "reserve")
            deliver(position = 6, status = "COMPLETED", step = "redesign", version = "3.0.0",
                extraMetadata = mapOf("versionChangeId" to "redesign", "version" to "3.0.0"))
            assertEquals(5, batches.size)
            projector = newProjector()
            deliver(position = 7, status = "COMPLETED", version = "3.0.0")
            assertEquals("3.0.0", batches.last().definition!!.version)
            assertEquals(listOf("reserve"), batches.last().definition!!.steps)
        }

        @Test
        fun `first completed history discovers its steps and uses the workflow version for published events`() {
            deliver(position = 1, status = "STARTED")
            deliver(position = 2, status = "STARTED", step = "reserve")
            deliver(position = 3, status = "COMPLETED", step = "reserve")
            deliver(position = 4, status = "COMPLETED", step = "publish", version = "99.0.0", primitive = "PUBLISH")
            deliver(position = 5, status = "COMPLETED")

            assertEquals("2.0.0", batches[3].events.single().definitionVersion)
            assertEquals(listOf("publish", "reserve"), batches.last().definition!!.steps)
            assertEquals("OrderWorkflow", batches.last().definition!!.name)
            assertTrue(batches.dropLast(1).all { it.definition == null })
        }

        @Test
        fun `restarts restore history before projecting the next event`() {
            deliver(position = 1, status = "STARTED")
            deliver(position = 2, status = "COMPLETED", step = "reserve")
            projector = newProjector()
            deliver(position = 3, status = "COMPLETED")
            assertEquals(listOf("reserve"), batches.last().definition!!.steps)
        }

        @Test
        fun `retry after failed acknowledgement produces the same event and definition`() {
            deliver(position = 1, status = "STARTED")
            deliver(position = 2, status = "COMPLETED", step = "reserve")
            every { client.sendAcknowledgedReport(any(), any(), WorkflowTelemetryAck::class.java) } returns Mono.error(IllegalStateException("offline"))
            assertThrows(CompletionException::class.java) { deliver(position = 3, status = "COMPLETED") }
            every { client.sendAcknowledgedReport(any(), any(), WorkflowTelemetryAck::class.java) } answers {
                val batch = firstArg<WorkflowTelemetryBatch>()
                batches.add(batch)
                Mono.just(WorkflowTelemetryAck(eventIds = batch.events.map { it.eventId }))
            }
            projector = newProjector()
            deliver(position = 3, status = "COMPLETED")
            assertEquals("event-3", batches.last().events.single().eventId)
            assertEquals(listOf("reserve"), batches.last().definition!!.steps)
            dataSource.connection.use { connection ->
                connection.createStatement().executeQuery("SELECT COUNT(*) FROM platform_workflow_history_event").use {
                    it.next()
                    assertEquals(3, it.getInt(1))
                }
            }
        }
    }

    private fun newProjector() = PlatformHistoryProjector(sourceId = "orders", client = client, dataSource = dataSource)

    private fun deliver(position: Long, status: String, step: String? = null, version: String = "2.0.0", primitive: String? = null,
                        payload: Any = emptyMap<String, Any>(), extraMetadata: Map<String, String> = emptyMap()) {
        every { context.getResource(TrackingToken.RESOURCE_KEY) } returns GlobalSequenceTrackingToken(position)
        val metadata = mutableMapOf("workflowId" to "workflow-1")
        if (step == null) metadata.putAll(mapOf("workflowStatus" to status, "workflowDefinitionName" to "OrderWorkflow", "workflowDefinitionVersion" to version))
        else metadata.putAll(mapOf("stepType" to status, "stepName" to step))
        primitive?.let { metadata["stepPrimitive"] = it }
        metadata.putAll(extraMetadata)
        val event = GenericEventMessage("event-$position", MessageType("OrderEvent", version), payload, metadata, Instant.now())
            .withConverter(context.component(EventConverter::class.java))
        projector.handle(event, context).asCompletableFuture().join()
    }
}
