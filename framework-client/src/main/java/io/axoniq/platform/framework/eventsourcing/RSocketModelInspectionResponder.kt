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

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import io.axoniq.platform.framework.api.*
import io.axoniq.platform.framework.client.RSocketHandlerRegistrar
import io.axoniq.platform.framework.truncateToBytes
import org.axonframework.common.configuration.Configuration
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory
import org.axonframework.messaging.eventhandling.EventMessage
import org.axonframework.modelling.repository.Repository
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiConsumer

open class RSocketModelInspectionResponder(
        @Suppress("unused") private val eventStorageEngine: EventStorageEngine,
        private val registrar: RSocketHandlerRegistrar,
        private val configuration: Configuration,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val objectMapper = ObjectMapper().apply {
        findAndRegisterModules()
        disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
        // AF5 entities are typically Kotlin data classes / Java records with private fields and
        // no public bean-style getters. Field access lets Jackson surface the actual state
        // instead of `{}`.
        setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
        setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
        setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE)
    }

    private val unitOfWorkFactory: UnitOfWorkFactory by lazy {
        configuration.getComponent(UnitOfWorkFactory::class.java)
    }

    /**
     * Repositories collected at boot from each event-sourced submodule via the decorator hook
     * in [AxoniqPlatformModelInspectionEnhancer]. Replaces walking the top-level state manager,
     * which only sees the top-level state manager and misses everything registered in submodules.
     */
    private val repositories = ConcurrentHashMap<Pair<Class<*>, Class<*>>, Repository<Any, Any>>()

    @Suppress("UNCHECKED_CAST")
    fun registerRepository(repository: Repository<*, *>) {
        val key = repository.entityType() to repository.idType()
        repositories[key] = repository as Repository<Any, Any>
        logger.info("[ModelInspection] Registered repository for entity=[{}] id=[{}]",
                repository.entityType().name, repository.idType().name)
    }

    fun start() {
        registrar.registerHandlerWithoutPayload(
                Routes.Model.REGISTERED_ENTITIES,
                this::handleRegisteredEntities,
        )
        registrar.registerHandlerWithPayload(
                Routes.Model.DOMAIN_EVENTS,
                ModelDomainEventsQuery::class.java,
                this::handleDomainEvents,
        )
        registrar.registerHandlerWithPayload(
                Routes.Model.ENTITY_STATE_AT_SEQUENCE,
                ModelEntityStateAtSequenceQuery::class.java,
                this::handleEntityStateAtSequence,
        )
        registrar.registerHandlerWithPayload(
                Routes.Model.REPLAY_TIMELINE,
                ModelTimelineQuery::class.java,
                this::handleTimelineReplay,
        )
    }

    // ------------------------------------------------------------------------------------------
    //  Registered entities introspection
    // ------------------------------------------------------------------------------------------

    internal fun handleRegisteredEntities(): RegisteredEntitiesResult {
        logger.debug("Handling Axoniq Platform MODEL_REGISTERED_ENTITIES query")
        val grouped: Map<Class<*>, List<Class<*>>> = repositories.keys
                .groupBy({ it.first }, { it.second })

        val entities = grouped.map { (entityType, idClasses) ->
            RegisteredEntityInfo(
                    entityType = entityType.name,
                    idTypes = idClasses.map { idClass ->
                        IdType(
                                type = idClass.name,
                                idFields = describeIdFields(idClass),
                        )
                    },
            )
        }
        logger.debug("Found entities: {}", entities)
        return RegisteredEntitiesResult(entities = entities)
    }

    /**
     * Returns structural descriptors for the given id class. Empty for "simple" types (single
     * text input on the frontend); populated for compound types (one input per descriptor).
     */
    internal fun describeIdFields(idClass: Class<*>): List<IdFieldDescriptor> {
        if (isSimpleIdType(idClass)) {
            return emptyList()
        }
        if (idClass.isRecord) {
            return idClass.recordComponents.map { component ->
                IdFieldDescriptor(
                        name = component.name,
                        type = normalizedType(component.type),
                        javaType = component.type.name,
                )
            }
        }
        return idClass.declaredFields
                .filter { field ->
                    !java.lang.reflect.Modifier.isStatic(field.modifiers) && !field.isSynthetic
                }
                .map { field ->
                    IdFieldDescriptor(
                            name = field.name,
                            type = normalizedType(field.type),
                            javaType = field.type.name,
                    )
                }
    }

    internal fun isSimpleIdType(idClass: Class<*>): Boolean {
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

    private fun isKotlinValueClass(idClass: Class<*>): Boolean =
            idClass.isAnnotationPresent(JvmInline::class.java)

    private fun kotlinValueClassUnderlying(idClass: Class<*>): Class<*>? {
        val instanceFields = idClass.declaredFields.filter { f ->
            !java.lang.reflect.Modifier.isStatic(f.modifiers) && !f.isSynthetic
        }
        return instanceFields.singleOrNull()?.type
    }

    internal fun normalizedType(type: Class<*>): String {
        if (type == UUID::class.java) return "uuid"
        if (type == String::class.java) return "string"
        if (type == java.lang.Boolean::class.java || type == java.lang.Boolean.TYPE) return "boolean"
        if (type == java.lang.Character::class.java || type == java.lang.Character.TYPE) return "string"
        if (Number::class.java.isAssignableFrom(type) || type.isPrimitive) return "number"
        return "object"
    }

    // ------------------------------------------------------------------------------------------
    //  Id deserialization
    // ------------------------------------------------------------------------------------------

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

    // ------------------------------------------------------------------------------------------
    //  UoW-driven inspection load
    // ------------------------------------------------------------------------------------------

    /**
     * Resolves the (entityType, idType) pair to the registered repository. Returns null if no
     * matching repository was registered at boot — e.g. the entity exists in a non-event-sourced
     * module, or the user-supplied class names don't resolve.
     */
    private fun lookupRepository(entityType: Class<*>, idType: Class<*>): Repository<Any, Any>? {
        return repositories[entityType to idType]
    }

    /**
     * Runs [block] inside a real [org.axonframework.messaging.core.unitofwork.UnitOfWork], wiring
     * the supplied hooks onto the [ProcessingContext] so the framework's own event-sourcing
     * pipeline drives state replay. The repository's load is invoked through the framework path —
     * criteria resolution, payload conversion, and `@EventSourcingHandler` dispatch all happen as
     * they would in a real command flow. We never append events, so commit is a no-op for storage.
     */
    private fun <R> withInspectionUoW(
            repository: Repository<Any, Any>,
            typedId: Any,
            beforeConsumer: BiConsumer<EventMessage, Any?>? = null,
            afterConsumer: BiConsumer<EventMessage, Any?>? = null,
            maxIndex: Long? = null,
            extract: (entity: Any?) -> R,
    ): R {
        return unitOfWorkFactory.create().executeWithResult { ctx: ProcessingContext ->
            beforeConsumer?.let { ctx.putResource(AxoniqPlatformEntityEvolver.BEFORE_CONSUMER, it) }
            afterConsumer?.let { ctx.putResource(AxoniqPlatformEntityEvolver.AFTER_CONSUMER, it) }
            maxIndex?.let { ctx.putResource(AxoniqPlatformEntityEvolver.MAX_INDEX, it) }

            repository.load(typedId, ctx).thenApply { managed ->
                extract(managed?.entity())
            }
        }.get()
    }

    // ------------------------------------------------------------------------------------------
    //  Event payload utilities
    // ------------------------------------------------------------------------------------------

    /**
     * Extracts a human-readable type name. Events read from [EventStorageEngine] often have a
     * raw `byte[]` payload whose `payloadType()` returns `[B`; the proper event type is in
     * `message.type().name()`.
     */
    private fun extractPayloadTypeName(message: EventMessage): String {
        return try {
            message.type()?.name() ?: message.payloadType().name
        } catch (e: Exception) {
            message.payloadType().name
        }
    }

    /**
     * Converts the event payload to a String. Payloads sourced from the event store are
     * usually raw `byte[]` containing JSON or CBOR. UTF-8 first (works for JSON) and Jackson as
     * fallback for typed payloads.
     */
    private fun extractPayloadAsString(message: EventMessage): String? {
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

    private fun stateAsJson(state: Any?): String? {
        if (state == null) return null
        return try {
            objectMapper.writeValueAsString(state)
        } catch (e: Exception) {
            logger.debug("Could not serialize entity state of type [{}]: {}",
                    state::class.java.name, e.message)
            null
        }
    }

    // ------------------------------------------------------------------------------------------
    //  Query handlers
    // ------------------------------------------------------------------------------------------

    internal fun handleDomainEvents(query: ModelDomainEventsQuery): DomainEventsResult {
        logger.info("Handling Axoniq Platform MODEL_DOMAIN_EVENTS query for entity [{}] id [{}] idType [{}]",
                query.entityType, query.entityId, query.idType)

        val entityClass = Class.forName(query.entityType)
        val idClass = Class.forName(query.idType)
        val repository = lookupRepository(entityClass, idClass)
                ?: return DomainEventsResult(
                        entityId = query.entityId,
                        entityType = query.entityType,
                        domainEvents = emptyList(),
                        page = query.page,
                        pageSize = query.pageSize,
                        totalCount = 0L,
                )
        val typedId = deserializeEntityId(query.entityId, idClass)
                ?: throw IllegalArgumentException("Could not deserialize id [${query.entityId}] as [${query.idType}]")

        val collected = mutableListOf<DomainEvent>()
        try {
            withInspectionUoW(
                    repository = repository,
                    typedId = typedId,
                    beforeConsumer = { event, _ ->
                        collected += DomainEvent(
                                sequenceNumber = collected.size.toLong(),
                                timestamp = event.timestamp(),
                                payloadType = extractPayloadTypeName(event),
                                payload = extractPayloadAsString(event),
                        )
                    },
                    extract = {},
            )
        } catch (e: Exception) {
            logger.error("Error while sourcing events for entity [{}] id [{}]",
                    query.entityType, query.entityId, e)
        }

        val totalCount = collected.size.toLong()
        val start = query.page * query.pageSize
        val end = minOf(start + query.pageSize, collected.size)
        val pagedEvents = if (start < collected.size) collected.subList(start, end) else emptyList()

        return DomainEventsResult(
                entityId = query.entityId,
                entityType = query.entityType,
                domainEvents = pagedEvents,
                page = query.page,
                pageSize = query.pageSize,
                totalCount = totalCount,
        )
    }

    internal fun handleEntityStateAtSequence(query: ModelEntityStateAtSequenceQuery): EntityStateResult {
        logger.info("Handling Axoniq Platform MODEL_ENTITY_STATE_AT_SEQUENCE query for entity [{}] id [{}] idType [{}] seq [{}]",
                query.entityType, query.entityId, query.idType, query.maxSequenceNumber)

        val entityClass = Class.forName(query.entityType)
        val idClass = Class.forName(query.idType)
        val repository = lookupRepository(entityClass, idClass)
                ?: return EntityStateResult(
                        type = query.entityType,
                        entityId = query.entityId,
                        maxSequenceNumber = query.maxSequenceNumber,
                        state = null,
                )
        val typedId = deserializeEntityId(query.entityId, idClass)
                ?: throw IllegalArgumentException("Could not deserialize id [${query.entityId}] as [${query.idType}]")

        val finalState = try {
            withInspectionUoW(
                    repository = repository,
                    typedId = typedId,
                    // Negative sequence = "all events" — don't set MAX_INDEX so nothing is skipped.
                    maxIndex = if (query.maxSequenceNumber < 0) null else query.maxSequenceNumber,
                    extract = { entity -> entity },
            )
        } catch (e: Exception) {
            logger.error("Error while reconstructing state for entity [{}] id [{}]",
                    query.entityType, query.entityId, e)
            null
        }

        return EntityStateResult(
                type = query.entityType,
                entityId = query.entityId,
                maxSequenceNumber = query.maxSequenceNumber,
                state = stateAsJson(finalState),
        )
    }

    internal fun handleTimelineReplay(query: ModelTimelineQuery): ModelTimelineResult {
        logger.info("Handling Axoniq Platform MODEL_REPLAY_TIMELINE query for entity [{}] id [{}] idType [{}] offset [{}] limit [{}]",
                query.entityType, query.entityId, query.idType, query.offset, query.limit)

        val entityClass = Class.forName(query.entityType)
        val idClass = Class.forName(query.idType)
        val repository = lookupRepository(entityClass, idClass)
                ?: return ModelTimelineResult(
                        entityType = query.entityType,
                        entityId = query.entityId,
                        entries = emptyList(),
                        offset = query.offset,
                        totalEvents = 0,
                        truncated = false,
                )
        val typedId = deserializeEntityId(query.entityId, idClass)
                ?: throw IllegalArgumentException("Could not deserialize id [${query.entityId}] as [${query.idType}]")

        val offset = maxOf(0, query.offset)
        val limit = if (query.limit <= 0) 100 else query.limit
        // Per-entry truncation budgets sized so a page-of-100 timeline response stays under
        // ~2.5 MB pre-gzip (≈ 750 KB post-gzip with the RSocket gzip extension enabled).
        // Each entry holds an event payload + before + after state — keep events tight (5 KB)
        // and snapshots conservative (20 KB) since most entities serialize well below either
        // bound. Larger payloads still come through truncated with a "[truncated]" marker.
        val maxStateSizeBytes = 20 * 1024
        val maxEventSizeBytes = 5 * 1024

        val entries = mutableListOf<ModelTimelineEntry>()
        val totalEvents = intArrayOf(0)
        // BEFORE/AFTER snapshots have to be paired — capture stateBefore in BEFORE_CONSUMER so it
        // reflects the pre-evolve state even when the entity mutates in place.
        val pending = arrayOfNulls<Any>(2)  // [eventMessage, stateBeforeJson]

        try {
            withInspectionUoW(
                    repository = repository,
                    typedId = typedId,
                    beforeConsumer = { event, stateBefore ->
                        pending[0] = event
                        pending[1] = stateAsJson(stateBefore).truncateToBytes(maxStateSizeBytes)
                    },
                    afterConsumer = { event, stateAfter ->
                        val seq = totalEvents[0].toLong()
                        totalEvents[0]++
                        if (seq >= offset && entries.size < limit) {
                            entries += ModelTimelineEntry(
                                    sequenceNumber = seq,
                                    timestamp = event.timestamp().toString(),
                                    eventType = extractPayloadTypeName(event),
                                    eventPayload = extractPayloadAsString(event).truncateToBytes(maxEventSizeBytes),
                                    stateBefore = pending[1] as String?,
                                    stateAfter = stateAsJson(stateAfter).truncateToBytes(maxStateSizeBytes),
                            )
                        }
                        pending[0] = null
                        pending[1] = null
                    },
                    extract = {},
            )
        } catch (e: Exception) {
            logger.error("Error while sourcing events for timeline of entity [{}] id [{}]",
                    query.entityType, query.entityId, e)
        }

        val remainingAfterWindow = maxOf(0, totalEvents[0] - offset - entries.size)
        val truncated = remainingAfterWindow > 0
        logger.info("Sourced [{}] events for timeline of [{}] id [{}] (returning [{}] from offset [{}], truncated={})",
                totalEvents[0], query.entityType, query.entityId, entries.size, offset, truncated)

        return ModelTimelineResult(
                entityType = query.entityType,
                entityId = query.entityId,
                entries = entries,
                offset = offset,
                totalEvents = totalEvents[0],
                truncated = truncated,
        )
    }
}
