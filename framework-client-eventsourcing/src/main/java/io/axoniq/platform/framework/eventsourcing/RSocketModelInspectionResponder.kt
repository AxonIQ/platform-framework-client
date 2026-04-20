/*
 * Copyright (c) 2022-2026. AxonIQ B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.axoniq.platform.framework.eventsourcing

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import io.axoniq.platform.framework.api.*
import io.axoniq.platform.framework.client.RSocketHandlerRegistrar
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import org.axonframework.eventsourcing.eventstore.SourcingCondition
import org.axonframework.messaging.eventhandling.TerminalEventMessage
import org.axonframework.messaging.eventstreaming.EventCriteria
import org.axonframework.messaging.eventstreaming.Tag
import org.axonframework.modelling.StateManager
import org.slf4j.LoggerFactory

open class RSocketModelInspectionResponder(
        private val stateManager: StateManager,
        private val eventStorageEngine: EventStorageEngine,
        private val registrar: RSocketHandlerRegistrar
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val objectMapper = ObjectMapper().apply {
        findAndRegisterModules()
        disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
    }

    fun start() {
        registrar.registerHandlerWithoutPayload(
                Routes.Model.REGISTERED_ENTITIES,
                this::handleRegisteredEntities
        )
        registrar.registerHandlerWithPayload(
                Routes.Model.DOMAIN_EVENTS,
                ModelDomainEventsQuery::class.java,
                this::handleDomainEvents
        )
        registrar.registerHandlerWithPayload(
                Routes.Model.ENTITY_STATE_AT_SEQUENCE,
                ModelEntityStateAtSequenceQuery::class.java,
                this::handleEntityStateAtSequence
        )
        registrar.registerHandlerWithPayload(
                Routes.Model.REPLAY_TIMELINE,
                ModelTimelineQuery::class.java,
                this::handleTimelineReplay
        )
    }

    /**
     * Resolves the tag key for a given entity type. Checks in order:
     * 1. Spring's @EventSourced annotation (directly or as meta-annotation) with non-empty tagKey
     * 2. Axon's @EventSourcedEntity annotation (directly or as meta-annotation) with non-empty tagKey
     * 3. Fallback to the entity class's simple name (consistent with AnnotationBasedEventCriteriaResolver)
     */
    private fun resolveTagKey(entityTypeName: String): String {
        return try {
            val entityClass = Class.forName(entityTypeName)

            // Walk all annotations on the class (including meta-annotations) and look for any with a non-empty tagKey
            val tagKey = findTagKeyInAnnotations(entityClass)
            if (!tagKey.isNullOrEmpty()) {
                return tagKey
            }

            // Fallback: simple name of the entity class
            entityClass.simpleName
        } catch (e: ClassNotFoundException) {
            logger.warn("Could not resolve entity class [{}], using last segment as tag key", entityTypeName)
            entityTypeName.substringAfterLast('.')
        }
    }

    /**
     * Recursively searches annotations on the class for any annotation that declares a non-empty `tagKey` attribute.
     * Supports both Spring's @EventSourced (which uses @AliasFor) and Axon's @EventSourcedEntity.
     */
    private fun findTagKeyInAnnotations(entityClass: Class<*>): String? {
        val visited = mutableSetOf<Class<out Annotation>>()
        for (annotation in entityClass.annotations) {
            val result = findTagKeyInAnnotation(annotation, visited)
            if (!result.isNullOrEmpty()) {
                return result
            }
        }
        return null
    }

    private fun findTagKeyInAnnotation(annotation: Annotation, visited: MutableSet<Class<out Annotation>>): String? {
        val annotationType = annotation.annotationClass.java
        if (!visited.add(annotationType)) {
            return null
        }
        // Skip standard Java annotations
        if (annotationType.name.startsWith("java.") || annotationType.name.startsWith("kotlin.")) {
            return null
        }
        // Check if this annotation itself has a tagKey attribute
        try {
            val tagKeyMethod = annotationType.getMethod("tagKey")
            val value = tagKeyMethod.invoke(annotation) as? String
            if (!value.isNullOrEmpty()) {
                logger.debug("Found tagKey [{}] on annotation [{}]", value, annotationType.name)
                return value
            }
        } catch (_: NoSuchMethodException) {
            // This annotation doesn't have a tagKey attribute
        } catch (e: Exception) {
            logger.debug("Error reading tagKey from annotation [{}]", annotationType.name, e)
        }
        // Recursively check meta-annotations
        for (metaAnnotation in annotationType.annotations) {
            val result = findTagKeyInAnnotation(metaAnnotation, visited)
            if (!result.isNullOrEmpty()) {
                return result
            }
        }
        return null
    }

    /**
     * Extracts a human-readable type name for the event. Events read directly from the
     * [EventStorageEngine] often have a raw byte[] payload whose `payloadType()` returns `[B`.
     * In that case the proper event type is available via `message.type().name()`.
     */
    private fun extractPayloadTypeName(message: org.axonframework.messaging.eventhandling.EventMessage): String {
        return try {
            // Prefer the qualified MessageType if present (AF5 way)
            message.type()?.name() ?: message.payloadType().name
        } catch (e: Exception) {
            message.payloadType().name
        }
    }

    /**
     * Converts the event payload to a String. When reading directly from [EventStorageEngine],
     * payloads are usually raw byte[] containing JSON or CBOR. We try UTF-8 decoding first
     * (works for JSON), falling back to Jackson serialization for typed payloads.
     */
    private fun extractPayloadAsString(message: org.axonframework.messaging.eventhandling.EventMessage): String? {
        val payload = message.payload() ?: return null
        return when (payload) {
            is ByteArray -> try {
                String(payload, Charsets.UTF_8)
            } catch (e: Exception) {
                payload.toString()
            }
            is String -> payload
            else -> try {
                objectMapper.writeValueAsString(payload)
            } catch (e: Exception) {
                payload.toString()
            }
        }
    }

    private fun handleRegisteredEntities(): RegisteredEntitiesResult {
        logger.debug("Handling Axoniq Platform MODEL_REGISTERED_ENTITIES query")
        val entities = stateManager.registeredEntities().map { entityType ->
            val idTypes = stateManager.registeredIdsFor(entityType)
            RegisteredEntityInfo(
                    entityType = entityType.name,
                    idTypes = idTypes.map { it.name }
            )
        }
        return RegisteredEntitiesResult(entities = entities)
    }

    private fun handleDomainEvents(query: ModelDomainEventsQuery): DomainEventsResult {
        logger.info("Handling Axoniq Platform MODEL_DOMAIN_EVENTS query for entity type [{}] id [{}]",
                query.entityType, query.entityId)

        val tagKey = resolveTagKey(query.entityType)
        logger.info("Resolved tag key [{}] for entity type [{}]", tagKey, query.entityType)
        val criteria = EventCriteria.havingTags(Tag.of(tagKey, query.entityId))
        val condition = SourcingCondition.conditionFor(criteria)

        // Use reduce() which returns a CompletableFuture that completes when the stream is done.
        // MessageStream is asynchronous by design; reduce is the recommended way to fully consume a finite stream.
        val stream = eventStorageEngine.source(condition)
        val allEvents = try {
            stream.reduce(mutableListOf<DomainEvent>()) { acc, entry ->
                val message = entry.message()
                // Skip the terminal marker that EventStorageEngine.source() always appends at the end
                if (message != null && message !is TerminalEventMessage) {
                    acc.add(DomainEvent(
                            sequenceNumber = acc.size.toLong(),
                            timestamp = message.timestamp(),
                            payloadType = extractPayloadTypeName(message),
                            payload = extractPayloadAsString(message)
                    ))
                }
                acc
            }.get()
        } catch (e: Exception) {
            logger.error("Error while sourcing events for entity type [{}] id [{}]", query.entityType, query.entityId, e)
            mutableListOf()
        }

        logger.info("Sourced [{}] events for entity type [{}] id [{}] with tag [{}={}]",
                allEvents.size, query.entityType, query.entityId, tagKey, query.entityId)

        val totalCount = allEvents.size.toLong()
        val start = query.page * query.pageSize
        val end = minOf(start + query.pageSize, allEvents.size)
        val pagedEvents = if (start < allEvents.size) allEvents.subList(start, end) else emptyList()

        return DomainEventsResult(
                entityId = query.entityId,
                entityType = query.entityType,
                domainEvents = pagedEvents,
                page = query.page,
                pageSize = query.pageSize,
                totalCount = totalCount,
        )
    }

    private fun handleEntityStateAtSequence(query: ModelEntityStateAtSequenceQuery): EntityStateResult {
        logger.info("Handling Axoniq Platform MODEL_ENTITY_STATE_AT_SEQUENCE query for entity type [{}] id [{}] seq [{}]",
                query.entityType, query.entityId, query.maxSequenceNumber)

        val tagKey = resolveTagKey(query.entityType)
        logger.info("Resolved tag key [{}] for entity type [{}]", tagKey, query.entityType)
        val criteria = EventCriteria.havingTags(Tag.of(tagKey, query.entityId))
        val condition = SourcingCondition.conditionFor(criteria)

        // Collect all events up to (and including) the max sequence number
        val stream = eventStorageEngine.source(condition)
        val collected = try {
            stream.reduce(mutableListOf<Any?>()) { acc, entry ->
                val message = entry.message()
                // Skip the terminal marker that EventStorageEngine.source() always appends at the end
                if (message !is TerminalEventMessage) {
                    // Include events up to and including the requested sequence number (0-indexed).
                    // A negative maxSequenceNumber means "all events".
                    val currentIndex = acc.size.toLong()
                    if (query.maxSequenceNumber < 0 || currentIndex <= query.maxSequenceNumber) {
                        acc.add(message.payload())
                    }
                }
                acc
            }.get()
        } catch (e: Exception) {
            logger.error("Error while sourcing events for entity type [{}] id [{}]", query.entityType, query.entityId, e)
            mutableListOf()
        }

        logger.info("Sourced [{}] events for entity state reconstruction of [{}] id [{}] at seq [{}]",
                collected.size, query.entityType, query.entityId, query.maxSequenceNumber)

        val state = collected.lastOrNull()?.let { payload ->
            when (payload) {
                is ByteArray -> String(payload, Charsets.UTF_8)
                is String -> payload
                else -> try {
                    objectMapper.writeValueAsString(payload)
                } catch (e: Exception) {
                    payload.toString()
                }
            }
        }

        return EntityStateResult(
                type = query.entityType,
                entityId = query.entityId,
                // Echo back the requested sequence number so the UI can navigate between sequences correctly
                maxSequenceNumber = query.maxSequenceNumber,
                state = state,
        )
    }

    /**
     * Handles a timeline replay query: sources all events for the given entity, then constructs
     * a list of [ModelTimelineEntry] capturing the entity's approximated state before and after each event.
     *
     * State reconstruction strategy (v1):
     *   Since assembling the real AF5 [org.axonframework.modelling.EntityEvolver] at runtime requires
     *   numerous core components from the Configuration (ParameterResolverFactory, MessageTypeResolver,
     *   MessageConverter, EventConverter) and access to the per-entity [AnnotatedEntityMetamodel], this
     *   initial implementation uses a pragmatic JSON deep-merge approximation:
     *     - Start with an empty JSON object as the accumulated state.
     *     - For each event, parse its payload as JSON (if possible) and deep-merge its fields into the
     *       accumulator. Primitive values overwrite, nested objects recurse, arrays replace.
     *   This is NOT the exact domain state that the framework would load via [StateManager.loadEntity],
     *   but it is visually useful for showing how fields evolve over time and supports diff rendering.
     *   A follow-up can replace this with true EntityEvolver-based reconstruction once we have a clean
     *   way to obtain the evolver for an arbitrary entity type.
     */
    private fun handleTimelineReplay(query: ModelTimelineQuery): ModelTimelineResult {
        logger.info("Handling Axoniq Platform MODEL_REPLAY_TIMELINE query for entity type [{}] id [{}] offset [{}] limit [{}]",
                query.entityType, query.entityId, query.offset, query.limit)

        val tagKey = resolveTagKey(query.entityType)
        logger.info("Resolved tag key [{}] for entity type [{}]", tagKey, query.entityType)
        val criteria = EventCriteria.havingTags(Tag.of(tagKey, query.entityId))
        val condition = SourcingCondition.conditionFor(criteria)

        val offset = maxOf(0, query.offset)
        val limit = if (query.limit <= 0) 100 else query.limit
        // Cap each state string to avoid blowing gRPC / RSocket message size limits.
        val maxStateSizeBytes = 10 * 1024 // 10 KB per state snapshot
        val entries = mutableListOf<ModelTimelineEntry>()
        var totalEvents = 0
        var currentState: JsonNode = objectMapper.createObjectNode()

        val stream = eventStorageEngine.source(condition)
        try {
            stream.reduce(Unit) { _, entry ->
                val message = entry.message()
                if (message !is TerminalEventMessage) {
                    val seq = totalEvents.toLong()
                    totalEvents++
                    // Always evolve state so stateBefore for the first event in the window is correct.
                    val payloadString = extractPayloadAsString(message)
                    val incomingNode = parseJsonOrNull(payloadString)
                    val evolvedState = if (incomingNode != null) {
                        mergeJsonState(currentState, incomingNode)
                    } else {
                        currentState
                    }
                    // Only serialize + collect entries inside the [offset, offset + limit) window.
                    if (seq >= offset && entries.size < limit) {
                        val stateBeforeJson = serializeJsonNode(currentState)
                        val stateAfterJson = serializeJsonNode(evolvedState)
                        entries.add(ModelTimelineEntry(
                                sequenceNumber = seq,
                                // ISO-8601 string avoids CBOR/Jackson Instant ambiguity.
                                timestamp = message.timestamp().toString(),
                                eventType = extractPayloadTypeName(message),
                                eventPayload = truncateString(payloadString, maxStateSizeBytes),
                                stateBefore = truncateString(stateBeforeJson, maxStateSizeBytes),
                                stateAfter = truncateString(stateAfterJson, maxStateSizeBytes),
                        ))
                    }
                    currentState = evolvedState
                }
                Unit
            }.get()
        } catch (e: Exception) {
            logger.error("Error while sourcing events for timeline of entity type [{}] id [{}]",
                    query.entityType, query.entityId, e)
        }

        val remainingAfterWindow = maxOf(0, totalEvents - offset - entries.size)
        val truncated = remainingAfterWindow > 0
        logger.info("Sourced [{}] events for timeline of [{}] id [{}] (returning [{}] from offset [{}], truncated={})",
                totalEvents, query.entityType, query.entityId, entries.size, offset, truncated)

        return ModelTimelineResult(
                entityType = query.entityType,
                entityId = query.entityId,
                entries = entries,
                offset = offset,
                totalEvents = totalEvents,
                truncated = truncated,
        )
    }

    /**
     * Truncates a string to at most [maxBytes] bytes (UTF-8). Appends a truncation marker if truncated.
     */
    private fun truncateString(value: String?, maxBytes: Int): String? {
        if (value == null) return null
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return value
        return String(bytes, 0, maxBytes, Charsets.UTF_8) + "\n... (truncated)"
    }

    /**
     * Parses the given string as a JSON tree. Returns null when the input is null/blank or when parsing fails.
     */
    private fun parseJsonOrNull(raw: String?): JsonNode? {
        if (raw.isNullOrBlank()) return null
        return try {
            objectMapper.readTree(raw)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Serializes a JsonNode to a pretty-printed JSON string. Returns null on failure.
     */
    private fun serializeJsonNode(node: JsonNode): String? {
        return try {
            objectMapper.writeValueAsString(node)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Deep-merges [incoming] into [current] and returns a new JsonNode representing the merged state.
     * - If both sides are objects, fields are merged recursively (incoming overrides on conflict).
     * - If [incoming] is not an object, it replaces [current] entirely.
     * - Arrays from [incoming] replace arrays in [current] (no element-level merging).
     * Does not mutate the inputs.
     */
    private fun mergeJsonState(current: JsonNode, incoming: JsonNode): JsonNode {
        if (!current.isObject || !incoming.isObject) {
            return incoming
        }
        val result: ObjectNode = (current as ObjectNode).deepCopy()
        val incomingObj = incoming as ObjectNode
        val fieldNames = incomingObj.fieldNames()
        while (fieldNames.hasNext()) {
            val name = fieldNames.next()
            val incomingValue = incomingObj.get(name)
            val existing = result.get(name)
            if (existing != null && existing.isObject && incomingValue.isObject) {
                result.set<JsonNode>(name, mergeJsonState(existing, incomingValue))
            } else {
                result.set<JsonNode>(name, incomingValue)
            }
        }
        return result
    }
}
