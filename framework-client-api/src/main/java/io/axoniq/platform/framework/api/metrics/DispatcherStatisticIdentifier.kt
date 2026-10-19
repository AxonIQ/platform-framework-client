/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.api.metrics

import com.fasterxml.jackson.annotation.JsonProperty

data class DispatcherStatisticIdentifier(
    @JsonProperty("h")
    val handlerInformation: HandlerStatisticsMetricIdentifier?,
    @JsonProperty("m")
    val message: MessageIdentifier,
)
