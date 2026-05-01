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
import org.axonframework.common.infra.ComponentDescriptor
import org.axonframework.common.infra.DescribableComponent
import org.axonframework.conversion.Converter
import org.axonframework.eventsourcing.CriteriaResolver
import org.axonframework.eventsourcing.EventSourcedEntityFactory
import org.axonframework.eventsourcing.annotation.AnnotationBasedEventCriteriaResolver
import org.axonframework.eventsourcing.annotation.EventSourcingHandler
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import org.axonframework.eventsourcing.eventstore.SourcingCondition
import org.axonframework.eventsourcing.handler.InitializingEntityEvolver
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.axonframework.messaging.eventhandling.EventMessage
import org.axonframework.messaging.eventhandling.GenericEventMessage
import org.axonframework.messaging.eventhandling.TerminalEventMessage
import org.axonframework.messaging.eventstreaming.EventCriteria
import org.axonframework.modelling.EntityEvolver
import org.axonframework.modelling.StateManager
import java.lang.reflect.Proxy
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
        // AF5 entities are typically Kotlin data classes / Java records with private fields and no
        // public getters in the bean-getter sense. Enable direct field access so Jackson surfaces
        // the entity's actual state instead of emitting `{}` for "no discoverable properties".
        setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
        setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
        setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE)
    }

    /**
     * Cache of [CriteriaResolver] instances per (entityType, idType) pair. Resolvers are
     * stateless and obtaining one via describe-walking or reflection is not free, so caching
     * avoids repeating the work on every query. Keyed by class identity so redeploys with
     * different classloaders get fresh entries naturally.
     */
    private val criteriaResolverCache = ConcurrentHashMap<Pair<Class<*>, Class<*>>, CriteriaResolver<Any>>()

    /**
     * Cache of [InitializingEntityEvolver] instances per (entityType, idType) pair. The
     * initializer combines the entity factory and the raw evolver: if the current state is
     * null on a given event, it creates a fresh entity via the factory; otherwise it
     * delegates to the evolver. This is exactly what we need for ad-hoc state replay
     * starting from a null state. Cache is safe because the initializer is stateless.
     */
    private val initializingEvolverCache = ConcurrentHashMap<Pair<Class<*>, Class<*>>, InitializingEntityEvolver<Any, Any>>()

    /**
     * The framework-wide [Converter] used to deserialize raw event payloads (read from the event
     * store as `byte[]`) into typed event objects, so the evolver's `@EventSourcingHandler`
     * methods can match them by parameter class. Resolved lazily from the [Configuration].
     */
    private val payloadConverter: Converter? by lazy {
        try {
            configuration.getComponent(Converter::class.java).also {
                logger.info("[ModelInspection] Using payload converter [{}] for event deserialization", it.javaClass.simpleName)
            }
        } catch (e: Exception) {
            logger.warn("[ModelInspection] Could not obtain Converter from configuration — events will be passed to evolver with raw payloads (state replay will likely be empty): {}",
                    e.message)
            null
        }
    }

    /**
     * Deserializes the event message's raw `byte[]` payload into a typed instance of the event
     * class indicated by `message.type().name()`. Without this, the evolver receives `byte[]`
     * payloads and no `@EventSourcingHandler(EventClass)` method matches, so state never advances
     * past entity creation defaults.
     *
     * Why not [EventMessage.withConvertedPayload]: that API short-circuits via
     * `convertedPayload.class.isAssignableFrom(payloadType())`. When the converter quietly
     * returns the same `byte[]` (no registered conversion path for `byte[] -> EventClass`),
     * the assignability check passes and the original message is returned untouched — so
     * `payloadType()` stays `byte[]` and the evolver can't dispatch to a typed handler.
     *
     * Instead, we explicitly call [Message.payloadAs] (forces conversion via the converter)
     * and reconstruct a [GenericEventMessage] with the typed payload, so the new
     * `payloadType()` is the actual event class — exactly what `AnnotatedEntityMetamodel.evolve`
     * expects to find a matching `@EventSourcingHandler`.
     *
     * Returns the original message untouched when the converter is unavailable, the type can't
     * be resolved on the classpath, conversion fails, or the result is unexpectedly null.
     */
    private fun deserializePayload(message: EventMessage): EventMessage {
        val converter = payloadConverter ?: return message
        val typeName = message.type()?.name() ?: return message
        return try {
            val cls = Class.forName(typeName)
            // Force the converter to produce a typed instance. This bypasses
            // withConvertedPayload's assignability short-circuit which keeps payloadType=byte[]
            // when the converter returns the input unchanged.
            val typedPayload: Any? = message.payloadAs(cls, converter)
            if (typedPayload == null || cls.isInstance(typedPayload).not()) {
                logger.debug("[ModelInspection] Converter returned unexpected payload for type [{}]: actual=[{}] — keeping original",
                        typeName, typedPayload?.javaClass?.name)
                return message
            }
            logger.info("[ModelInspection] Converted payload: msgType.name=[{}] msgType.version=[{}] payloadCls=[{}]",
                    message.type().name(),
                    message.type()?.version(),
                    typedPayload.javaClass.name)
            // Build a fresh GenericEventMessage whose payloadType() is the typed class.
            // GenericMessage's 4-arg constructor derives payloadType from payload.getClass(),
            // so the resulting message advertises the correct event class to the evolver.
            // Metadata extends Map<String, String> in AF5 but Kotlin needs an explicit cast
            // because of its stricter generics treatment of the Java collection.
            @Suppress("UNCHECKED_CAST")
            GenericEventMessage(
                    message.identifier(),
                    message.type(),
                    typedPayload,
                    message.metadata() as Map<String, String>,
                    message.timestamp(),
            )
        } catch (e: ClassNotFoundException) {
            logger.debug("Event type [{}] not on classpath — leaving payload as raw bytes", typeName)
            message
        } catch (e: Exception) {
            logger.debug("Could not convert event payload of type [{}]: {}", typeName, e.message)
            message
        }
    }

    /**
     * No-op [ProcessingContext] proxy used for ad-hoc evolve calls outside a real unit of work.
     * `AnnotatedEntityMetamodel.evolve` requires a non-null context (defensive `Objects.requireNonNull`),
     * but during model inspection replay we have no active processing context. The proxy returns
     * sane defaults: null for resource lookups, `false` for predicates, `this` for fluent setters,
     * and a no-op for void methods. Any method that would actually need a resource will see null
     * and either no-op or throw — caught at the [evolveSafely] boundary.
     */
    private val noOpProcessingContext: ProcessingContext = Proxy.newProxyInstance(
            ProcessingContext::class.java.classLoader,
            arrayOf(ProcessingContext::class.java)
    ) { proxy, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            ProcessingContext::class.java -> proxy
            Void.TYPE -> null
            else -> null
        }
    } as ProcessingContext

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

    // ------------------------------------------------------------------------------------------
    //  Registered entities introspection
    // ------------------------------------------------------------------------------------------

    private fun handleRegisteredEntities(): RegisteredEntitiesResult {
        logger.debug("Handling Axoniq Platform MODEL_REGISTERED_ENTITIES query")
        val entities = stateManager.registeredEntities().map { entityType ->
            val idTypeInfos = stateManager.registeredIdsFor(entityType).map { idClass ->
                IdType(
                        type = idClass.name,
                        idFields = describeIdFields(idClass),
                )
            }
            RegisteredEntityInfo(
                    entityType = entityType.name,
                    idTypes = idTypeInfos,
            )
        }
        return RegisteredEntitiesResult(entities = entities)
    }

    /**
     * Returns structural descriptors for the given id class. Empty for "simple" types where
     * the frontend should show a single text input (String, primitives, UUID); populated for
     * records / data classes / plain objects where each property should get its own input.
     * Only 1-deep properties are described — nested types surface as type = "object".
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
                        javaType = component.type.name
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
                            javaType = field.type.name
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
    //  Criteria resolution + id deserialization
    // ------------------------------------------------------------------------------------------

    /**
     * Resolves the [EventCriteria] for a given (entityType, idType, entityId) triple by
     * obtaining the registered [CriteriaResolver] for the chosen id type and invoking it
     * with the deserialized typed id. Multi-tag and compound-id entities produce the
     * correct criteria automatically — no tag-key resolution needed.
     */
    private fun resolveCriteria(entityType: Class<*>, idClass: Class<*>, entityId: String): EventCriteria {
        val typedId = deserializeEntityId(entityId, idClass)
                ?: throw IllegalArgumentException("Could not deserialize id [$entityId] as type [${idClass.name}]")
        return resolveCriteriaWithTypedId(entityType, idClass, typedId)
    }

    /**
     * Same as [resolveCriteria] but skips id deserialization — used by handlers that already
     * have the typed id (e.g. for state reconstruction via [InitializingEntityEvolver]).
     */
    private fun resolveCriteriaWithTypedId(entityType: Class<*>, idClass: Class<*>, typedId: Any): EventCriteria {
        val resolver = obtainCriteriaResolver(entityType, idClass)
        // ProcessingContext is @NonNull via JSpecify @NullMarked, but the default
        // AnnotationBasedEventCriteriaResolver never reads it. We bypass Kotlin's nullability
        // check via reflection; resolvers that actually rely on the context will throw NPE
        // which propagates to the handler-level catch and surfaces a clear error.
        return invokeResolveWithNullContext(resolver, typedId)
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
     *   1. Walk the registered repository via `describeTo` and pick out the first matching
     *      [CriteriaResolver] — this honors any custom resolver an application may have wired in.
     *   2. Fall back to constructing a fresh [AnnotationBasedEventCriteriaResolver] from the
     *      available [Configuration], which covers the default annotation-driven setup.
     * Results are cached per (entityType, idType) pair.
     */
    @Suppress("UNCHECKED_CAST")
    private fun obtainCriteriaResolver(entityClass: Class<*>, idClass: Class<*>): CriteriaResolver<Any> {
        return criteriaResolverCache.getOrPut(entityClass to idClass) {
            findInRepository(entityClass, idClass, CriteriaResolver::class.java) as CriteriaResolver<Any>?
                    ?: AnnotationBasedEventCriteriaResolver<Any, Any>(
                            entityClass as Class<Any>,
                            idClass as Class<Any>,
                            configuration
                    ) as CriteriaResolver<Any>
        }
    }

    /**
     * Invokes [CriteriaResolver.resolve] with a no-op [ProcessingContext] via reflection,
     * bypassing the JSpecify/Kotlin non-null check on the parameter. The default
     * `AnnotationBasedEventCriteriaResolver` doesn't read the context; resolvers that do
     * may interact with the proxy (returning null/false defaults) and either no-op or throw,
     * which propagates to the handler-level catch.
     */
    private fun invokeResolveWithNullContext(resolver: CriteriaResolver<Any>, typedId: Any): EventCriteria {
        val method = resolver.javaClass.methods.first { it.name == "resolve" && it.parameterCount == 2 }
        return method.invoke(resolver, typedId, noOpProcessingContext) as EventCriteria
    }

    // ------------------------------------------------------------------------------------------
    //  Entity evolver lookup + state reconstruction
    // ------------------------------------------------------------------------------------------

    /**
     * Obtains an [InitializingEntityEvolver] for the given (entityType, idType). AF5
     * repositories don't pre-instantiate `InitializingEntityEvolver`; they expose the
     * `entityFactory` and `entityEvolver` as separate describable properties. We find both
     * via the describe tree and construct the initializer manually — same constructor the
     * framework uses internally — so null initial state on the first event triggers entity
     * creation via the factory rather than no-op'ing.
     *
     * Returns null when either piece can't be located.
     */
    @Suppress("UNCHECKED_CAST")
    private fun obtainInitializingEvolver(entityClass: Class<*>, idClass: Class<*>): InitializingEntityEvolver<Any, Any>? {
        initializingEvolverCache[entityClass to idClass]?.let { return it }
        val factory = findInRepository(entityClass, idClass, EventSourcedEntityFactory::class.java)
                as EventSourcedEntityFactory<Any, Any>?
        val evolver = findInRepository(entityClass, idClass, EntityEvolver::class.java, preferredName = "entityEvolver")
                as EntityEvolver<Any>?
        if (factory == null || evolver == null) {
            logger.warn("Could not assemble InitializingEntityEvolver for [{}] / [{}] — factory={}, evolver={}",
                    entityClass.name, idClass.name, factory?.javaClass?.name, evolver?.javaClass?.name)
            return null
        }
        logger.info("Assembled InitializingEntityEvolver for [{}] / [{}] — factory=[{}], evolver=[{}]",
                entityClass.simpleName, idClass.simpleName, factory.javaClass.simpleName, evolver.javaClass.simpleName)
        val initializer = InitializingEntityEvolver(factory, evolver)
        initializingEvolverCache[entityClass to idClass] = initializer
        return initializer
    }

    /**
     * Invokes [InitializingEntityEvolver.evolve] with a null [ProcessingContext] via reflection
     * — same trick as [invokeResolveWithNullContext]. The initializer handles null state by
     * delegating to the entity factory before evolving. Custom code that relies on the context
     * will throw NPE; we catch at the caller and keep the previous state.
     *
     * The 4-arg signature is `(I id, E currentState, EventMessage event, ProcessingContext ctx)`.
     */
    private fun invokeEvolveWithNullContext(
            evolver: InitializingEntityEvolver<Any, Any>,
            typedId: Any,
            currentState: Any?,
            message: EventMessage,
    ): Any? {
        val method = evolver.javaClass.methods.first { it.name == "evolve" && it.parameterCount == 4 }
        return method.invoke(evolver, typedId, currentState, message, noOpProcessingContext)
    }

    /**
     * Walks the repository registered for (entityClass, idClass) via `describeTo` and returns
     * the first instance of [target] that surfaces. When [preferredName] is set, the property
     * with that exact name wins over any other match (used to prefer raw evolvers over
     * lifecycle-wrapped ones).
     */
    private fun <T : Any> findInRepository(
            entityClass: Class<*>,
            idClass: Class<*>,
            target: Class<T>,
            preferredName: String? = null,
    ): T? {
        return try {
            val repository = stateManager.repository(entityClass, idClass) ?: return null
            val repoClass = repository.javaClass.name
            // Diagnostic dump (DEBUG): on every lookup, list every describable property — useful
            // when debugging an unfamiliar repository implementation, but too noisy for INFO.
            if (logger.isDebugEnabled) {
                val dump = DescribePropertyDump()
                (repository as? DescribableComponent)?.describeTo(dump)
                logger.debug("[ModelInspection] Looking for [{}] in repository [{}] for [{}] / [{}]; describe tree:\n{}",
                        target.simpleName, repoClass, entityClass.simpleName, idClass.simpleName, dump.formatted())
            }
            val finder = NamedDescribablePropertyFinder(target, preferredName)
            (repository as? DescribableComponent)?.describeTo(finder) ?: return null
            finder.found
        } catch (e: Exception) {
            logger.debug("describeTo lookup of [{}] for entity [{}] / id [{}] failed: {}",
                    target.simpleName, entityClass.name, idClass.name, e.message)
            null
        }
    }

    /** Diagnostic descriptor that records every property name + value class seen, recursing into describable nested components. */
    private class DescribePropertyDump : ComponentDescriptor {
        private val lines = mutableListOf<String>()
        private var depth = 0
        private val visited = mutableSetOf<Int>() // identity-based cycle break

        private fun indent() = "  ".repeat(depth)

        override fun describeProperty(name: String, value: Any?) {
            val cls = value?.javaClass?.name ?: "null"
            lines += "${indent()}- $name : $cls"
            if (value is DescribableComponent && visited.add(System.identityHashCode(value))) {
                depth++
                try {
                    value.describeTo(this)
                } catch (_: Exception) { /* swallow — diagnostic only */ }
                depth--
            }
        }

        override fun describeProperty(name: String, value: Collection<*>?) {
            lines += "${indent()}- $name : Collection(size=${value?.size ?: 0})"
        }

        override fun describeProperty(name: String, value: Map<*, *>?) {
            lines += "${indent()}- $name : Map(size=${value?.size ?: 0})"
        }

        override fun describeProperty(name: String, value: String?) {
            lines += "${indent()}- $name : String = $value"
        }

        override fun describeProperty(name: String, value: Long?) {
            lines += "${indent()}- $name : Long = $value"
        }

        override fun describeProperty(name: String, value: Boolean?) {
            lines += "${indent()}- $name : Boolean = $value"
        }

        override fun describe(): String = "DescribePropertyDump"

        fun formatted(): String = lines.joinToString("\n")
    }

    /**
     * Drills through a repository's `describeTo` tree looking for a single instance of [target].
     * Property-name preference lets callers pick the canonical match (e.g. "entityEvolver")
     * over wrapper variants like "initializingEntityEvolver".
     */
    private class NamedDescribablePropertyFinder<T : Any>(
            private val target: Class<T>,
            private val preferredName: String?,
    ) : ComponentDescriptor {
        private var preferred: T? = null
        private var fallback: T? = null

        @Suppress("UNCHECKED_CAST")
        override fun describeProperty(name: String, value: Any?) {
            if (value == null) return
            if (target.isInstance(value)) {
                if (preferredName != null && name == preferredName && preferred == null) {
                    preferred = value as T
                } else if (fallback == null) {
                    fallback = value as T
                }
                // Don't recurse into matched component — we already have what we need at this level.
                return
            }
            if (value is DescribableComponent) {
                value.describeTo(this)
            }
        }

        override fun describeProperty(name: String, value: Collection<*>?) { /* not needed */ }
        override fun describeProperty(name: String, value: Map<*, *>?) { /* not needed */ }
        override fun describeProperty(name: String, value: String?) { /* not needed */ }
        override fun describeProperty(name: String, value: Long?) { /* not needed */ }
        override fun describeProperty(name: String, value: Boolean?) { /* not needed */ }
        override fun describe(): String = "NamedDescribablePropertyFinder<${target.simpleName}>"

        val found: T? get() = preferred ?: fallback
    }

    // ------------------------------------------------------------------------------------------
    //  Event payload utilities
    // ------------------------------------------------------------------------------------------

    /**
     * Extracts a human-readable type name for the event. Events read directly from the
     * [EventStorageEngine] often have a raw byte[] payload whose `payloadType()` returns `[B`.
     * In that case the proper event type is available via `message.type().name()`.
     */
    private fun extractPayloadTypeName(message: EventMessage): String {
        return try {
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

    private fun handleDomainEvents(query: ModelDomainEventsQuery): DomainEventsResult {
        logger.info("Handling Axoniq Platform MODEL_DOMAIN_EVENTS query for entity [{}] id [{}] idType [{}]",
                query.entityType, query.entityId, query.idType)

        val entityClass = Class.forName(query.entityType)
        val idClass = Class.forName(query.idType)
        val criteria = resolveCriteria(entityClass, idClass, query.entityId)
        val condition = SourcingCondition.conditionFor(criteria)

        val stream = eventStorageEngine.source(condition)
        val allEvents = try {
            stream.reduce(mutableListOf<DomainEvent>()) { acc, entry ->
                val message = entry.message()
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
            logger.error("Error while sourcing events for entity [{}] id [{}]",
                    query.entityType, query.entityId, e)
            mutableListOf()
        }

        logger.info("Sourced [{}] events for entity [{}] id [{}]",
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

    /**
     * Reconstructs the entity's state at a specific sequence number by replaying all events
     * up to (and including) that sequence through the registered [EntityEvolver]. Returns
     * the JSON-serialized state. If no EntityEvolver is registered for the entity, returns
     * null state — frontend can treat that as "state reconstruction not available".
     */
    private fun handleEntityStateAtSequence(query: ModelEntityStateAtSequenceQuery): EntityStateResult {
        logger.info("Handling Axoniq Platform MODEL_ENTITY_STATE_AT_SEQUENCE query for entity [{}] id [{}] idType [{}] seq [{}]",
                query.entityType, query.entityId, query.idType, query.maxSequenceNumber)

        val entityClass = Class.forName(query.entityType)
        val idClass = Class.forName(query.idType)
        val typedId = deserializeEntityId(query.entityId, idClass)
                ?: throw IllegalArgumentException("Could not deserialize id [${query.entityId}] as [${query.idType}]")
        val criteria = resolveCriteriaWithTypedId(entityClass, idClass, typedId)
        val condition = SourcingCondition.conditionFor(criteria)
        val evolver = obtainInitializingEvolver(entityClass, idClass)

        if (evolver == null) {
            logger.warn("No InitializingEntityEvolver found for entity [{}] / id [{}] — returning null state",
                    query.entityType, query.idType)
            return EntityStateResult(
                    type = query.entityType,
                    entityId = query.entityId,
                    maxSequenceNumber = query.maxSequenceNumber,
                    state = null,
            )
        }

        // Accumulator: (eventsConsumedSoFar, currentState). MessageStream doesn't expose the
        // entry index, so we maintain it inline.
        val stream = eventStorageEngine.source(condition)
        val finalState = try {
            stream.reduce(StateHolder(0L, null)) { holder, entry ->
                val message = entry.message()
                if (message == null || message is TerminalEventMessage) return@reduce holder
                // Sequence numbers are 0-indexed by event order. Negative maxSequenceNumber = "all events".
                val withinWindow = query.maxSequenceNumber < 0 || holder.index <= query.maxSequenceNumber
                if (!withinWindow) return@reduce holder
                StateHolder(holder.index + 1, evolveSafely(evolver, typedId, holder.state, deserializePayload(message)))
            }.get().state
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

    /** Reduce accumulator that tracks the running event index alongside the evolved state. */
    private data class StateHolder(val index: Long, val state: Any?)

    private fun evolveSafely(
            evolver: InitializingEntityEvolver<Any, Any>,
            typedId: Any,
            currentState: Any?,
            message: EventMessage,
    ): Any? {
        return try {
            // Capture JSON before AF5 dispatch so we can detect whether the metamodel actually
            // mutated the entity, and so the [Evolve] log shows the real pre-mutation state.
            // Without this snapshot, since entities mutate in place via @EventSourcingHandler,
            // re-serializing currentState after evolve would just print the post-mutation state.
            val beforeJson = if (currentState != null) safeJson(currentState) else "null"

            val result = invokeEvolveWithNullContext(evolver, typedId, currentState, message)

            val afterJsonInitial = if (result != null) safeJson(result) else "null"
            val mutatedByMetamodel = currentState != null && beforeJson != afterJsonInitial
            // If state already changed, the metamodel did its job — don't double-apply via
            // reflection. If no change AND we have a non-null entity AND a typed payload, try
            // direct reflection dispatch on the entity class.
            val finalResult = if (!mutatedByMetamodel && result != null && message.payload() != null) {
                applyEventViaReflection(result, message)
                result
            } else {
                result
            }

            if (logger.isInfoEnabled) {
                logger.info("[Evolve] event=[{}] before=[{}] after=[{}] beforeIs=[{}] afterIs=[{}] reflectionFallback=[{}]",
                        message.payloadType().simpleName,
                        beforeJson,
                        safeJson(finalResult),
                        currentState?.let { System.identityHashCode(it) } ?: 0,
                        finalResult?.let { System.identityHashCode(it) } ?: 0,
                        !mutatedByMetamodel && result != null)
            }
            finalResult
        } catch (e: Exception) {
            logger.warn("EntityEvolver.evolve threw for event [{}]: {} — keeping previous state",
                    message.payloadType().name, e.cause?.message ?: e.message, e)
            currentState
        }
    }

    /**
     * Reflection-based fallback for entities where AF5's [AnnotationBasedEntityEvolvingComponent]
     * dispatch silently no-ops in an ad-hoc inspection context (no real
     * [org.axonframework.messaging.core.unitofwork.ProcessingContext], no message handler
     * interceptor chain, etc.).
     *
     * Walks the entity's class hierarchy looking for methods annotated with
     * [EventSourcingHandler] whose first parameter type accepts the message payload. Each match
     * is invoked directly on the entity. The method is expected to mutate `this` in place
     * (typical AF5 pattern: `void on(SomeEvent e) { this.field = e.field(); }`). For methods
     * with extra parameters (e.g. for parameter resolvers), passes `null` so we don't break
     * compilation, but those handlers may NPE — caught at the boundary.
     *
     * Idempotent re-invocation of an event handler is the caller's contract; we only invoke this
     * path when the AF5 dispatch produced no observable mutation, so we won't re-apply changes.
     */
    internal fun applyEventViaReflection(entity: Any, message: EventMessage) {
        val payload = message.payload() ?: return
        val payloadCls = payload.javaClass
        val handlerCandidates = collectEventSourcingHandlers(entity.javaClass)

        // Path A: payload was already deserialized by [deserializePayload] (works when
        // event.type().name() is a FQN that Class.forName resolves, e.g. with
        // ClassBasedMessageTypeResolver). Match handlers whose first parameter accepts the
        // typed payload directly.
        if (payloadCls != ByteArray::class.java) {
            for ((declaringClass, method) in handlerCandidates) {
                val paramType = method.parameterTypes[0]
                if (!paramType.isInstance(payload)) continue
                invokeHandlerSafely(entity, declaringClass, method, payload, paramType)
                return
            }
            return
        }

        // Path B: payload is still raw byte[] because Class.forName couldn't resolve the type
        // name (common with @Event(namespace=...) which produces names like
        // "quickstart.OrderCreatedEvent" that don't match a real class). Resolve the handler by
        // matching the simple class name extracted from message.type().name() against each
        // handler's parameter simple name — picking exactly one. Convert byte[] to that
        // specific type only. This avoids Jackson's permissive deserialization successfully
        // returning a partially-filled instance of the wrong event class (e.g. OrderShippedEvent
        // accepting an OrderCreatedEvent JSON because they share an `orderId` field).
        val converter = payloadConverter ?: return
        val typeName = message.type()?.name() ?: return
        val expectedSimpleName = simpleNameFromMessageType(typeName)
        val match = handlerCandidates.firstOrNull { (_, method) ->
            method.parameterTypes[0].simpleName == expectedSimpleName
        } ?: run {
            logger.debug("[ModelInspection] No @EventSourcingHandler param simpleName matches [{}] (from type=[{}]) on [{}]",
                    expectedSimpleName, typeName, entity.javaClass.simpleName)
            return
        }
        val (declaringClass, method) = match
        val paramType = method.parameterTypes[0]
        val typedArg: Any? = try {
            message.payloadAs(paramType, converter)
        } catch (e: Exception) {
            logger.debug("[ModelInspection] Converter failed for type=[{}] -> param=[{}]: {}",
                    typeName, paramType.simpleName, e.message)
            null
        }
        if (typedArg == null || !paramType.isInstance(typedArg)) {
            logger.debug("[ModelInspection] Converter returned non-matching payload for type=[{}] (target=[{}])",
                    typeName, paramType.simpleName)
            return
        }
        invokeHandlerSafely(entity, declaringClass, method, typedArg, paramType)
    }

    /**
     * Extracts the simple Java class name from an [org.axonframework.messaging.core.MessageType]
     * name string. Handles both formats:
     * - FQN with optional `$` (nested class): `io.axoniq.quickstart.reservation.event.ReservationEvents$SeatReservedEvent`
     *   → `SeatReservedEvent`
     * - Namespaced short form from `@Event(namespace=...)`: `quickstart.OrderCreatedEvent`
     *   → `OrderCreatedEvent`
     */
    internal fun simpleNameFromMessageType(typeName: String): String {
        val afterDollar = typeName.substringAfterLast('$')
        return afterDollar.substringAfterLast('.')
    }

    private fun invokeHandlerSafely(
            entity: Any,
            declaringClass: Class<*>,
            method: java.lang.reflect.Method,
            payload: Any,
            paramType: Class<*>,
    ) {
        try {
            method.isAccessible = true
            val args: Array<Any?> = if (method.parameterCount == 1) {
                arrayOf(payload)
            } else {
                Array(method.parameterCount) { idx -> if (idx == 0) payload else null }
            }
            method.invoke(entity, *args)
            logger.debug("[ModelInspection] Reflection dispatch invoked [{}#{}] for param=[{}]",
                    declaringClass.simpleName, method.name, paramType.simpleName)
        } catch (e: Exception) {
            logger.debug("[ModelInspection] Reflection dispatch failed for [{}#{}]: {}",
                    declaringClass.simpleName, method.name, e.cause?.message ?: e.message)
        }
    }

    /**
     * Walks the entity's class hierarchy collecting `@EventSourcingHandler` methods with at
     * least one parameter (the event). Returned in declaring-class-first order.
     */
    private fun collectEventSourcingHandlers(entityClass: Class<*>): List<Pair<Class<*>, java.lang.reflect.Method>> {
        val result = mutableListOf<Pair<Class<*>, java.lang.reflect.Method>>()
        var current: Class<*>? = entityClass
        while (current != null && current != Any::class.java) {
            for (method in current.declaredMethods) {
                if (!method.isAnnotationPresent(EventSourcingHandler::class.java)) continue
                if (method.parameterCount < 1) continue
                result.add(current to method)
            }
            current = current.superclass
        }
        return result
    }

    private fun safeJson(value: Any?): String =
            if (value == null) "null"
            else try {
                objectMapper.writeValueAsString(value)
            } catch (e: Exception) {
                "<unserializable: ${e.message}>"
            }

    /**
     * Replays all events for the entity through the registered [EntityEvolver] and emits a
     * timeline of `(sequence, event, stateBefore, stateAfter)` entries within the requested window.
     *
     * State serialization is JSON via Jackson (handles records, Kotlin data classes, POJOs).
     * State strings are byte-truncated via [String.truncateToBytes] so a single oversize entity
     * cannot blow gRPC/RSocket message size limits.
     *
     * If no EntityEvolver is registered for the entity (e.g. non-event-sourced repositories),
     * `stateBefore`/`stateAfter` are emitted as null and the frontend can collapse that section.
     */
    private fun handleTimelineReplay(query: ModelTimelineQuery): ModelTimelineResult {
        logger.info("Handling Axoniq Platform MODEL_REPLAY_TIMELINE query for entity [{}] id [{}] idType [{}] offset [{}] limit [{}]",
                query.entityType, query.entityId, query.idType, query.offset, query.limit)

        val entityClass = Class.forName(query.entityType)
        val idClass = Class.forName(query.idType)
        val typedId = deserializeEntityId(query.entityId, idClass)
                ?: throw IllegalArgumentException("Could not deserialize id [${query.entityId}] as [${query.idType}]")
        val criteria = resolveCriteriaWithTypedId(entityClass, idClass, typedId)
        val condition = SourcingCondition.conditionFor(criteria)
        val evolver = obtainInitializingEvolver(entityClass, idClass)
        if (evolver == null) {
            logger.warn("No InitializingEntityEvolver found for entity [{}] / id [{}] — timeline state fields will be null",
                    query.entityType, query.idType)
        }

        val offset = maxOf(0, query.offset)
        val limit = if (query.limit <= 0) 100 else query.limit
        val maxStateSizeBytes = 100 * 1024 // 100 KB per state snapshot before truncation
        val maxEventSizeBytes = 50 * 1024  // 50 KB per event payload before truncation

        val entries = mutableListOf<ModelTimelineEntry>()
        var totalEvents = 0
        var currentState: Any? = null

        val stream = eventStorageEngine.source(condition)
        try {
            stream.reduce(Unit) { _, entry ->
                val message = entry.message()
                if (message == null || message is TerminalEventMessage) return@reduce Unit

                val seq = totalEvents.toLong()
                totalEvents++

                // Capture stateBefore JSON eagerly. Reservation-style entities mutate in place
                // via reflection fallback (see [evolveSafely]) so currentState and nextState are
                // the same instance. If we serialize stateBefore after evolveSafely, both fields
                // would show the post-mutation state and the timeline UI couldn't diff between
                // events. Snapshot the JSON now while currentState is still pre-mutation.
                val stateBeforeJson = stateAsJson(currentState).truncateToBytes(maxStateSizeBytes)

                val nextState = evolver?.let { evolveSafely(it, typedId, currentState, deserializePayload(message)) } ?: currentState

                if (seq >= offset && entries.size < limit) {
                    entries.add(ModelTimelineEntry(
                            sequenceNumber = seq,
                            timestamp = message.timestamp().toString(),
                            eventType = extractPayloadTypeName(message),
                            eventPayload = extractPayloadAsString(message).truncateToBytes(maxEventSizeBytes),
                            stateBefore = stateBeforeJson,
                            stateAfter = stateAsJson(nextState).truncateToBytes(maxStateSizeBytes),
                    ))
                }
                currentState = nextState
                Unit
            }.get()
        } catch (e: Exception) {
            logger.error("Error while sourcing events for timeline of entity [{}] id [{}]",
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
}
