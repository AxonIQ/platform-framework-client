/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.api.metrics

import com.fasterxml.jackson.annotation.JsonProperty

data class StatisticDistribution(
    @JsonProperty("-")
    val min: Double,
    @JsonProperty("me")
    val median: Double,
    @JsonProperty("m")
    val mean: Double,
    @JsonProperty("p95")
    val percentile95: Double,
    @JsonProperty("p90")
    val percentile90: Double,
    @JsonProperty("+")
    val max: Double,
)
