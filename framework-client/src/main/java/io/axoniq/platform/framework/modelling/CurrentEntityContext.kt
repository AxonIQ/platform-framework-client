/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
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
