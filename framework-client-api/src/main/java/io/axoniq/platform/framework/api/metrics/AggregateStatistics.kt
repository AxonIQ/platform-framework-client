/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.api.metrics

import com.fasterxml.jackson.annotation.JsonProperty

data class AggregateStatistics(
    @JsonProperty("c")
    val count: Double,
    @JsonProperty("f")
    val failed: Double,
    @JsonProperty("t")
    val timer: StatisticDistribution?,
    @JsonProperty("m")
    val metrics: Map<String, StatisticDistribution>,
)
