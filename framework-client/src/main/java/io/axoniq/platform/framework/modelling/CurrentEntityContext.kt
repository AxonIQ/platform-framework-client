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

package io.axoniq.platform.framework.modelling

import io.axoniq.platform.framework.api.metrics.EntityStatisticIdentifier
import org.axonframework.messaging.core.Context
import java.util.concurrent.atomic.AtomicLong

/**
 * Carries the entity statistic identifier that is currently being loaded through the [Context] /
 * [org.axonframework.messaging.core.unitofwork.ProcessingContext], so downstream decorators (event store,
 * lock primitives) can attribute their metrics to the correct entity.
 *
 * Where [org.axonframework.messaging.core.unitofwork.ProcessingContext] is available (e.g. event-store
 * append), prefer [RESOURCE_KEY]. The [EventStorageEngine.source] API does not propagate a context, so
 * for that call site the same identifier is also exposed via a thread-local set by
 * [AxoniqPlatformRepository] just before delegating into the framework.
 *
 * The [SOURCED_EVENTS_TL] thread-local lets the repository distinguish a real load from a fresh-entity
 * creation: when `loadOrCreate` returns and the counter is still zero, the framework sourced no prior
 * events for this identifier, meaning the entity was just created. The counter is shared by reference
 * with the event-store wrapper so it stays correct even if events are delivered on a different thread.
 */
object CurrentEntityContext {
    val RESOURCE_KEY: Context.ResourceKey<EntityStatisticIdentifier> =
            Context.ResourceKey.withLabel<EntityStatisticIdentifier>("Axoniq Platform - Current Entity")
    val COUNTER_KEY: Context.ResourceKey<AtomicLong> = Context.ResourceKey.withLabel<AtomicLong>("Axoniq Platform - Entity source counter")
}
