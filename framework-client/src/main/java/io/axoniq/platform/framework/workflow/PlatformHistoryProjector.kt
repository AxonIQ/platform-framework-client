package io.axoniq.platform.framework.workflow

import io.axoniq.framework.workflow.history.api.WorkflowHistory
import io.axoniq.framework.workflow.history.inmemory.InMemoryWorkflowHistoryRepository
import io.axoniq.framework.workflow.history.inmemory.WorkflowHistoryProjector
import io.axoniq.platform.framework.api.workflow.WorkflowDefinitionObservation
import io.axoniq.platform.framework.client.AxoniqConsoleRSocketClient
import org.axonframework.common.infra.ComponentDescriptor
import org.axonframework.messaging.core.Message
import org.axonframework.messaging.core.MessageStream
import org.axonframework.messaging.core.MessageType
import org.axonframework.messaging.core.QualifiedName
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.axonframework.messaging.eventhandling.EventHandlingComponent
import org.axonframework.messaging.eventhandling.EventMessage
import org.axonframework.messaging.eventhandling.GenericEventMessage
import org.axonframework.messaging.eventhandling.conversion.EventConverter
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Instant
import javax.sql.DataSource

class PlatformHistoryProjector(
    private val sourceId: String,
    client: AxoniqConsoleRSocketClient,
    dataSource: DataSource,
    private val includePayloads: Boolean = false,
) : EventHandlingComponent {
    private val sender = PlatformWorkflowTelemetryHandler(sourceId = sourceId, client = client)
    private val journal = PlatformWorkflowHistoryJournal(sourceId = sourceId, dataSource = dataSource)

    override fun handle(event: EventMessage, context: ProcessingContext): MessageStream.Empty<Message> {
        if (!event.metadata().containsKey("workflowId")) return MessageStream.empty()
        val position = sender.position(context)
        val history = journal.project(event = event, position = position, context = context)
        val telemetry = sender.normalize(event = event, context = context) ?: return MessageStream.empty()
        checkNotNull(history) { "Workflow history has no definition; start reporting from the beginning of the event stream" }
        val definitionId = history.state().workflowDefinitionId()
        val observation = if (history.state().workflowStatus().isTerminal && telemetry.stepName == null && telemetry.status != "STARTED") WorkflowDefinitionObservation(
            name = definitionId.qualifiedName().toString(), version = definitionId.version(),
            steps = history.state().workflowStepNames().filterNot { history.state().versionMigrations().containsKey(it) }.sorted(),
            bpmnXml = model(name = definitionId.qualifiedName().toString(), version = definitionId.version()),
        ) else null
        return sender.send(
            event = event,
            telemetry = telemetry.copy(
                definitionName = definitionId.qualifiedName().toString(), definitionVersion = definitionId.version(),
                eventName = event.type().qualifiedName().toString(),
                payloadJson = if (includePayloads) event.payloadAs(ByteArray::class.java)
                    ?.takeIf { it.size <= 16_384 }?.toString(Charsets.UTF_8) else null,
            ),
            definition = observation,
        )
    }

    private fun model(name: String, version: String): String? {
        val segment = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")
        if (!segment.matches(name) || !segment.matches(version) || name.contains("..") || version.contains("..")) return null
        return javaClass.classLoader.getResourceAsStream("META-INF/axoniq/workflows/$name/$version.bpmn")?.use {
            it.readNBytes(262_145).takeIf { bytes -> bytes.size <= 262_144 }?.toString(Charsets.UTF_8)
        }
    }

    override fun supportedEvents(): Set<QualifiedName> = emptySet()
    override fun supports(eventName: QualifiedName) = true
    override fun sequenceIdentifierFor(event: EventMessage, context: ProcessingContext): Any =
        event.metadata()["workflowId"] ?: event.identifier()

    override fun describeTo(descriptor: ComponentDescriptor) = descriptor.describeProperty("sourceId", sourceId)
}

internal class PlatformWorkflowHistoryJournal(private val sourceId: String, private val dataSource: DataSource) {
    private val json = JsonMapper.builder().build()
    private val metadataType = object : TypeReference<Map<String, String>>() {}
    private data class CachedHistory(val repository: InMemoryWorkflowHistoryRepository, val position: Long)
    private val cache = LinkedHashMap<String, CachedHistory>(128, 0.75f, true)

    init {
        dataSource.connection.use { connection -> connection.createStatement().use { statement ->
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS platform_workflow_history_event (
                    workflow_key VARCHAR(64) NOT NULL, event_id VARCHAR(255) NOT NULL,
                    source_position BIGINT NOT NULL, event_name TEXT NOT NULL, event_version VARCHAR(128) NOT NULL,
                    occurred_at VARCHAR(40) NOT NULL, payload TEXT NOT NULL, metadata TEXT NOT NULL,
                    PRIMARY KEY (workflow_key, event_id), UNIQUE (workflow_key, source_position)
                )
            """.trimIndent())
        } }
    }

    @Synchronized
    fun project(event: EventMessage, position: Long, context: ProcessingContext): WorkflowHistory? {
        val workflowId = requireNotNull(event.metadata()["workflowId"])
        val key = MessageDigest.getInstance("SHA-256").digest("${sourceId.length}:$sourceId$workflowId".toByteArray())
            .joinToString("") { "%02x".format(it) }
        var repository = InMemoryWorkflowHistoryRepository()
        val converter = context.component(EventConverter::class.java)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val latest = connection.prepareStatement("SELECT MAX(source_position) FROM platform_workflow_history_event WHERE workflow_key = ? AND source_position < ?").use { statement ->
                    statement.setString(1, key)
                    statement.setLong(2, position)
                    statement.executeQuery().use { rows -> rows.next(); rows.getObject(1)?.let { (it as Number).toLong() } }
                }
                val cached = cache[key]?.takeIf { it.position == latest && it.position < position }
                if (cached != null) repository = cached.repository
                val projector = WorkflowHistoryProjector(repository)
                connection.prepareStatement("SELECT * FROM platform_workflow_history_event WHERE workflow_key = ? AND source_position < ? AND source_position > ? ORDER BY source_position").use { statement ->
                    statement.setString(1, key)
                    statement.setLong(2, position)
                    statement.setLong(3, cached?.position ?: -1)
                    statement.executeQuery().use { rows -> while (rows.next()) {
                        val restored = GenericEventMessage(
                            rows.getString("event_id"), MessageType(rows.getString("event_name"), rows.getString("event_version")),
                            json.readValue(rows.getString("payload"), Any::class.java),
                            json.readValue(rows.getString("metadata"), metadataType), Instant.parse(rows.getString("occurred_at")),
                        ).withConverter(converter)
                        projector.handle(restored, context).asCompletableFuture().join()
                    } }
                }
                projector.handle(event, context).asCompletableFuture().join()
                connection.prepareStatement("SELECT COUNT(*) FROM platform_workflow_history_event WHERE workflow_key = ? AND event_id = ?").use { exists ->
                    exists.setString(1, key)
                    exists.setString(2, event.identifier())
                    val present = exists.executeQuery().use { it.next(); it.getLong(1) > 0 }
                    if (!present) connection.prepareStatement("""
                        INSERT INTO platform_workflow_history_event
                        (workflow_key, event_id, source_position, event_name, event_version, occurred_at, payload, metadata)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()).use { insert ->
                        insert.setString(1, key)
                        insert.setString(2, event.identifier())
                        insert.setLong(3, position)
                        insert.setString(4, event.type().qualifiedName().toString())
                        insert.setString(5, event.type().version())
                        insert.setString(6, event.timestamp().toString())
                        insert.setString(7, json.readTree(event.payloadAs(ByteArray::class.java)).toString())
                        insert.setString(8, json.writeValueAsString(event.metadata()))
                        insert.executeUpdate()
                    }
                }
                connection.commit()
                cache[key] = CachedHistory(repository = repository, position = position)
                if (cache.size > 128) cache.remove(cache.keys.first())
            } catch (exception: Exception) {
                cache.remove(key)
                connection.rollback()
                throw exception
            }
        }
        return repository.findById(workflowId).join().orElse(null)
    }
}
