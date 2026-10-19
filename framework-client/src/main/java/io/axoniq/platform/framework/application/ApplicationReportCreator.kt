/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.application

import io.axoniq.platform.framework.api.metrics.ApplicationMetricReport
import io.axoniq.platform.framework.api.metrics.MemoryPoolReport
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType

class ApplicationReportCreator(
        private val registry: ApplicationMetricRegistry,
) {
    private val memoryBeans = ManagementFactory.getMemoryPoolMXBeans()
    private val threadBean = ManagementFactory.getThreadMXBean()
    private val osBean = ManagementFactory.getOperatingSystemMXBean()
    private val cpuMetricsProvider = CpuMetricsProvider()

    fun createReport(): ApplicationMetricReport {
        return ApplicationMetricReport(
                loadAverage = osBean.systemLoadAverage,
                processCpuUsage = cpuMetricsProvider.getProcessCpuUsage(),
                systemCpuUsage = cpuMetricsProvider.getSystemCpuUsage(),
                heapUsage = determineMemoryUsage(),
                liveThreadCount = threadBean.threadCount,
                commandBus = registry.getCommandBusMetrics(),
                queryBus = registry.getQueryBusMetrics()
        )
    }

    private fun determineMemoryUsage(): MemoryPoolReport {
        return memoryBeans
                .filter { it.type == MemoryType.HEAP && it.usage.max > 0 }
                .fold(MemoryPoolReport(0.0, 0.0, 0.0)) { acc, bean ->
                    MemoryPoolReport(
                            acc.committed + bean.usage.used.toMb(),
                            acc.used + bean.usage.committed.toMb(),
                            acc.max + bean.usage.max.toMb(),
                    )
                }
    }

    private fun Number.toMb(): Double = this.toDouble() / 1024 / 1024
}
