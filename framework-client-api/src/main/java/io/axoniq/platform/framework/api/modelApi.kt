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
        val idTypes: List<String>,
)

data class ModelDomainEventsQuery(
        val entityType: String,
        val entityId: String,
        val page: Int = 0,
        val pageSize: Int = 10,
)

data class ModelEntityStateAtSequenceQuery(
        val entityType: String,
        val entityId: String,
        val maxSequenceNumber: Long = 0,
)

data class ModelTimelineQuery(
        val entityType: String,
        val entityId: String,
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
