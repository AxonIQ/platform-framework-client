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

/**
 * Carries the entity statistic identifier that is currently being loaded through the
 * [org.axonframework.messaging.core.unitofwork.ProcessingContext], so downstream decorators
 * (event store, lock primitives) can attribute their metrics to the correct entity.
 */
object CurrentEntityContext {
    val RESOURCE_KEY: Context.ResourceKey<EntityStatisticIdentifier> =
            Context.ResourceKey.withLabel<EntityStatisticIdentifier>("Axoniq Platform - Current Entity")
}
