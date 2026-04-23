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
import org.axonframework.common.configuration.Configuration
import org.axonframework.eventsourcing.CriteriaResolver
import org.axonframework.eventsourcing.annotation.AnnotationBasedEventCriteriaResolver
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import org.axonframework.eventsourcing.eventstore.SourcingCondition
import org.axonframework.messaging.eventhandling.TerminalEventMessage
import org.axonframework.messaging.eventstreaming.EventCriteria
import org.axonframework.messaging.eventstreaming.Tag
import org.axonframework.modelling.StateManager
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap

open class RSocketModelInspectionResponder(
        private val stateManager: StateManager,
        private val eventStorageEngine: EventStorageEngine,
        private val registrar: RSocketHandlerRegistrar,
        private val configuration: Configuration
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val objectMapper = ObjectMapper().apply {
        findAndRegisterModules()
        disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
    }

    /**
     * Cache of [CriteriaResolver] instances per (entityType, idType) pair. Resolvers are
     * stateless and obtaining one via reflection is not free, so caching avoids repeating
     * the work on every query. Keyed by class identity so redeploys with different classloaders
     * get fresh entries naturally.
     */
    private val criteriaResolverCache = ConcurrentHashMap<Pair<Class<*>, Class<*>>, CriteriaResolver<Any>>()

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
            // Prefer the first registered id type for introspection. Entities registered with
            // multiple id types (rare) still get a sensible default; the frontend can extend
            // this to switch among id types later.
            val primaryIdType = idTypes.firstOrNull()
            RegisteredEntityInfo(
                    entityType = entityType.name,
                    idTypes = idTypes.map { it.name },
                    idFields = primaryIdType?.let { describeIdFields(it) } ?: emptyList()
            )
        }
        return RegisteredEntitiesResult(entities = entities)
    }

    /**
     * Returns structural descriptors for the given id class. Empty for "simple" types where
     * the frontend should show a single text input (String, primitives, UUID); populated for
     * records / data classes / plain objects where each property should get its own input.
     */
    private fun describeIdFields(idClass: Class<*>): List<IdFieldDescriptor> {
        if (isSimpleIdType(idClass)) {
            return emptyList()
        }
        // Records: use the declared record components in canonical order.
        if (idClass.isRecord) {
            return idClass.recordComponents.map { component ->
                IdFieldDescriptor(
                        name = component.name,
                        type = normalizedType(component.type),
                        javaType = component.type.name
                )
            }
        }
        // Kotlin data classes / POJOs: walk declared fields, skip synthetic/static.
        return idClass.declaredFields
                .filter { field ->
                    !java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                            !field.isSynthetic
                }
                .map { field ->
                    IdFieldDescriptor(
                            name = field.name,
                            type = normalizedType(field.type),
                            javaType = field.type.name
                    )
                }
    }

    /**
     * A "simple" id type is one the frontend can reasonably capture with a single text input.
     * Everything else is treated as compound (structured) and gets field-by-field descriptors.
     *
     * Kotlin `@JvmInline value class` wrappers (e.g. `value class OrderId(val value: String)`) are
     * also treated as simple when their underlying property is itself simple — the user sees one
     * input and we box the typed value at deserialization time.
     */
    private fun isSimpleIdType(idClass: Class<*>): Boolean {
        if (idClass.isPrimitive) return true
        if (idClass.isEnum) return true
        if (isKotlinValueClass(idClass)) {
            val underlying = kotlinValueClassUnderlying(idClass)
            return underlying != null && isSimpleIdType(underlying)
        }
        return when (idClass) {
            String::class.java,
            java.lang.Long::class.java,
            java.lang.Integer::class.java,
            java.lang.Short::class.java,
            java.lang.Byte::class.java,
            java.lang.Double::class.java,
            java.lang.Float::class.java,
            java.lang.Boolean::class.java,
            java.lang.Character::class.java,
            UUID::class.java,
            java.math.BigInteger::class.java,
            java.math.BigDecimal::class.java -> true
            else -> false
        }
    }

    /** True if the class is a Kotlin `@JvmInline value class`. */
    private fun isKotlinValueClass(idClass: Class<*>): Boolean {
        return idClass.isAnnotationPresent(JvmInline::class.java)
    }

    /**
     * Returns the underlying backing type of a Kotlin value class, or null when the class
     * doesn't expose exactly one instance field (shouldn't happen for well-formed value classes,
     * but we stay defensive against synthetic/static noise from compiler plugins).
     */
    private fun kotlinValueClassUnderlying(idClass: Class<*>): Class<*>? {
        val instanceFields = idClass.declaredFields.filter { f ->
            !java.lang.reflect.Modifier.isStatic(f.modifiers) && !f.isSynthetic
        }
        return instanceFields.singleOrNull()?.type
    }

    /** Maps a Java class to a frontend-friendly type label used to choose input widgets. */
    private fun normalizedType(type: Class<*>): String {
        if (type == UUID::class.java) return "uuid"
        if (type == String::class.java) return "string"
        if (type == java.lang.Boolean::class.java || type == java.lang.Boolean.TYPE) return "boolean"
        if (type == java.lang.Character::class.java || type == java.lang.Character.TYPE) return "string"
        if (Number::class.java.isAssignableFrom(type) || type.isPrimitive) return "number"
        return "object"
    }

    /**
     * Resolves the [EventCriteria] for a given entity query. Instead of hand-crafting a
     * single `Tag.of(tagKey, entityId)` (which only works for single-tag entities with
     * string ids), we invoke the entity's registered [CriteriaResolver] with a typed id,
     * so multi-tag and compound-id entities produce the correct criteria.
     *
     * Falls back to the legacy single-tag approach only if resolver construction or
     * invocation fails, so this stays backward-compatible with edge cases.
     */
    private fun resolveCriteria(entityTypeName: String, entityId: String): EventCriteria {
        return try {
            val entityClass = Class.forName(entityTypeName)
            val idClass = stateManager.registeredIdsFor(entityClass).firstOrNull()
                    ?: return legacyTagCriteria(entityTypeName, entityId)
            // Jackson can theoretically return null for an input of `"null"`; the resolver
            // signature is @Nonnull so we treat that as an invalid id and fall back.
            val typedId = deserializeEntityId(entityId, idClass)
                    ?: return legacyTagCriteria(entityTypeName, entityId)
            val resolver = obtainCriteriaResolver(entityClass, idClass)
            // ProcessingContext is @NonNull via JSpecify @NullMarked, but the default
            // AnnotationBasedEventCriteriaResolver never reads it (verified in AF5 source).
            // We bypass Kotlin's nullability check via reflection; custom resolvers that
            // actually rely on it will throw NPE, which is caught by the outer try/catch
            // and falls back to the legacy single-tag path.
            invokeResolveWithNullContext(resolver, typedId)
        } catch (e: Exception) {
            logger.warn("CriteriaResolver path failed for entity [{}] id [{}] — falling back to legacy tag lookup: {}",
                    entityTypeName, entityId, e.message)
            legacyTagCriteria(entityTypeName, entityId)
        }
    }

    private fun legacyTagCriteria(entityTypeName: String, entityId: String): EventCriteria {
        val tagKey = resolveTagKey(entityTypeName)
        return EventCriteria.havingTags(Tag.of(tagKey, entityId))
    }

    /**
     * Parses the incoming [entityId] wire string into the entity's id type. For simple id
     * types we parse directly; for compound types the wire format is a JSON object whose
     * keys match the id's property names.
     */
    private fun deserializeEntityId(entityId: String, idClass: Class<*>): Any? {
        val trimmed = entityId.trim()
        return when {
            idClass == String::class.java -> trimmed
            idClass == UUID::class.java -> UUID.fromString(trimmed)
            idClass == java.lang.Long::class.java || idClass == java.lang.Long.TYPE -> trimmed.toLong()
            idClass == java.lang.Integer::class.java || idClass == java.lang.Integer.TYPE -> trimmed.toInt()
            idClass == java.lang.Short::class.java || idClass == java.lang.Short.TYPE -> trimmed.toShort()
            idClass == java.lang.Byte::class.java || idClass == java.lang.Byte.TYPE -> trimmed.toByte()
            idClass == java.lang.Double::class.java || idClass == java.lang.Double.TYPE -> trimmed.toDouble()
            idClass == java.lang.Float::class.java || idClass == java.lang.Float.TYPE -> trimmed.toFloat()
            idClass == java.lang.Boolean::class.java || idClass == java.lang.Boolean.TYPE -> trimmed.toBoolean()
            idClass == java.math.BigInteger::class.java -> java.math.BigInteger(trimmed)
            idClass == java.math.BigDecimal::class.java -> java.math.BigDecimal(trimmed)
            idClass.isEnum -> {
                @Suppress("UNCHECKED_CAST")
                java.lang.Enum.valueOf(idClass as Class<out Enum<*>>, trimmed)
            }
            isKotlinValueClass(idClass) -> deserializeValueClass(trimmed, idClass)
            else -> objectMapper.readValue(trimmed, idClass)
        }
    }

    /**
     * Deserializes a Kotlin `@JvmInline value class` from its wire form. The frontend sends the
     * *underlying* value (e.g. `"abc-123"` for `value class OrderId(val value: String)`), we parse
     * it against the underlying type, and box it via the value class's public constructor so the
     * framework's [CriteriaResolver] sees the real typed id.
     */
    private fun deserializeValueClass(raw: String, idClass: Class<*>): Any? {
        val underlying = kotlinValueClassUnderlying(idClass) ?: return null
        val underlyingValue = deserializeEntityId(raw, underlying) ?: return null
        return try {
            idClass.getDeclaredConstructor(underlying)
                    .apply { isAccessible = true }
                    .newInstance(underlyingValue)
        } catch (e: NoSuchMethodException) {
            logger.debug("No public constructor({}) on Kotlin value class [{}]; falling back to Jackson",
                    underlying.name, idClass.name)
            objectMapper.readValue(raw, idClass)
        }
    }

    /**
     * Obtains a [CriteriaResolver] for the given (entityType, idType) pair. Strategy:
     *   1. Try to extract the registered resolver from the repository returned by
     *      [StateManager.repository] via reflection on its private `criteriaResolver` field.
     *      This honors custom resolvers that an application may have configured.
     *   2. Fall back to constructing a fresh [AnnotationBasedEventCriteriaResolver] from
     *      the available [Configuration], which covers the default annotation-driven setup.
     * Results are cached per class pair.
     */
    @Suppress("UNCHECKED_CAST")
    private fun obtainCriteriaResolver(entityClass: Class<*>, idClass: Class<*>): CriteriaResolver<Any> {
        return criteriaResolverCache.getOrPut(entityClass to idClass) {
            extractResolverFromRepository(entityClass, idClass)
                    ?: AnnotationBasedEventCriteriaResolver<Any, Any>(
                            entityClass as Class<Any>,
                            idClass as Class<Any>,
                            configuration
                    ) as CriteriaResolver<Any>
        }
    }

    /**
     * Invokes [CriteriaResolver.resolve] with a null [ProcessingContext] via reflection,
     * bypassing the JSpecify/Kotlin non-null check on the parameter. See caller for rationale.
     */
    private fun invokeResolveWithNullContext(resolver: CriteriaResolver<Any>, typedId: Any): EventCriteria {
        val method = resolver.javaClass.methods.first { it.name == "resolve" && it.parameterCount == 2 }
        return method.invoke(resolver, typedId, null) as EventCriteria
    }

    /**
     * Reflects on the repository returned by [StateManager] to extract its `criteriaResolver`
     * field. This yields the exact resolver the framework uses at runtime (respecting any
     * custom configuration). Returns null when the repository is not an EventSourcingRepository
     * or the field cannot be read — the caller then falls back to the annotation-based resolver.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractResolverFromRepository(entityClass: Class<*>, idClass: Class<*>): CriteriaResolver<Any>? {
        return try {
            val repository = stateManager.repository(entityClass, idClass) ?: return null
            val field = findField(repository.javaClass, "criteriaResolver") ?: return null
            field.isAccessible = true
            field.get(repository) as? CriteriaResolver<Any>
        } catch (e: Exception) {
            logger.debug("Could not extract CriteriaResolver from repository for [{}]: {}",
                    entityClass.name, e.message)
            null
        }
    }

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun handleDomainEvents(query: ModelDomainEventsQuery): DomainEventsResult {
        logger.info("Handling Axoniq Platform MODEL_DOMAIN_EVENTS query for entity type [{}] id [{}]",
                query.entityType, query.entityId)

        val criteria = resolveCriteria(query.entityType, query.entityId)
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

        logger.info("Sourced [{}] events for entity type [{}] id [{}]",
                allEvents.size, query.entityType, query.entityId)

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

        val criteria = resolveCriteria(query.entityType, query.entityId)
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

        val criteria = resolveCriteria(query.entityType, query.entityId)
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
