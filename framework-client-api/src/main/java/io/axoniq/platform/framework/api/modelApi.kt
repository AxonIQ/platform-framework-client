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

package io.axoniq.platform.framework.api

data class RegisteredEntitiesResult(
        val entities: List<RegisteredEntityInfo>
)

data class RegisteredEntityInfo(
        val entityType: String,
        /**
         * All id types registered for this entity. AF5 entities can be addressed by multiple
         * id types (e.g. one per command in a build agent), each producing different criteria.
         * The frontend should let the user pick which id type to query against.
         */
        val idTypes: List<IdType>,
)

data class IdType(
        /** Fully qualified Java type name of the id class. */
        val type: String,
        /**
         * Structural descriptors of the id class's properties. Empty for "simple" types
         * (String, primitives, UUID, etc.) — frontend renders a single text input. Populated
         * for compound types (records / data classes / plain objects) — frontend renders one
         * input per descriptor and sends the entityId as a JSON object keyed by descriptor names.
         * Only 1-deep properties are described; nested objects are exposed as type "object" and
         * left for the user to provide as raw JSON.
         */
        val idFields: List<IdFieldDescriptor> = emptyList(),
)

data class IdFieldDescriptor(
        val name: String,
        /** Normalized form-friendly type: "string", "number", "boolean", "uuid", or "object". */
        val type: String,
        /** Fully qualified Java type name, useful for diagnostics / future extensions. */
        val javaType: String,
)

data class ModelDomainEventsQuery(
        val entityType: String,
        val entityId: String,
        /** FQ Java type name of the id type the user selected (must match one of [RegisteredEntityInfo.idTypes].type). */
        val idType: String,
        val page: Int = 0,
        val pageSize: Int = 10,
)

data class ModelEntityStateAtSequenceQuery(
        val entityType: String,
        val entityId: String,
        /** FQ Java type name of the id type the user selected. */
        val idType: String,
        val maxSequenceNumber: Long = 0,
)

data class ModelTimelineQuery(
        val entityType: String,
        val entityId: String,
        /** FQ Java type name of the id type the user selected. */
        val idType: String,
        val offset: Int = 0,
        val limit: Int = 100,
)

data class ModelTimelineResult(
        val entityType: String,
        val entityId: String,
        val entries: List<ModelTimelineEntry>,
        val offset: Int = 0,
        val totalEvents: Int,
        val truncated: Boolean,
)

data class ModelTimelineEntry(
        val sequenceNumber: Long,
        /**
         * ISO-8601 formatted timestamp (from [java.time.Instant.toString]).
         * String is used here — instead of [java.time.Instant] — to avoid ambiguity in how
         * the different serializers (CBOR on the RSocket leg, Jackson on the query-handler leg)
         * encode the Instant: some emit an epoch-seconds number, which the frontend would
         * then incorrectly treat as milliseconds.
         */
        val timestamp: String,
        val eventType: String,
        val eventPayload: String?,
        val stateBefore: String?,
        val stateAfter: String?,
)
