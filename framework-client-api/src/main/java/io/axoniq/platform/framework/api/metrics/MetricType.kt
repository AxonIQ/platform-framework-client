/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.api.metrics

import java.util.concurrent.TimeUnit

/**
 * Due to limitations of micrometer, we cannot make a distribution of a simple counter/gauge. We need a timer for
 * that. Since we want to measure stuff like p90 interval of events read for an aggregate, we may need to convert this.
 */
enum class MetricType(val metricPrefix: String, val distributionUnit: TimeUnit) {
    TIMER("tmr", TimeUnit.NANOSECONDS),
    COUNTER("cntr", TimeUnit.MILLISECONDS),
}
