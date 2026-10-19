/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.api.metrics

import com.fasterxml.jackson.annotation.JsonProperty

data class HandlerStatisticsMetricIdentifier(
    @JsonProperty("t")
    val type: HandlerType,
    @JsonProperty("c")
    val component: String?,
    @JsonProperty("m")
    val message: MessageIdentifier
)

