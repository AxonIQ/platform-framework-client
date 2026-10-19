/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.api.metrics

data class ApplicationMetricReport(
        val loadAverage: Double,
        val systemCpuUsage: Double,
        val processCpuUsage: Double,
        val liveThreadCount: Int,
        val heapUsage: MemoryPoolReport,
        val commandBus: BusMetricReport?,
        val queryBus: BusMetricReport?,
)

data class BusMetricReport(
        val capacity: Int,
        val usedCapacity: Double,
)

data class MemoryPoolReport(
        val used: Double,
        val committed: Double,
        val max: Double,
)
