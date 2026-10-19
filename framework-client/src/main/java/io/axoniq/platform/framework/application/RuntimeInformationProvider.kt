/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.application

import io.axoniq.platform.framework.api.RuntimeInformation
import java.lang.management.ManagementFactory

/**
 * Collects what the application is running on, for the setup payload.
 *
 * Only facts about the platform are gathered — nothing about the machine's owner, its filesystem or the
 * command line, since those carry credentials often enough that they are not worth the risk.
 */
class RuntimeInformationProvider(
        private val property: (String) -> String? = { key -> runCatching { System.getProperty(key) }.getOrNull() },
        private val availableProcessors: () -> Int = { Runtime.getRuntime().availableProcessors() },
        private val maxHeapInBytes: () -> Long = { Runtime.getRuntime().maxMemory() },
        private val garbageCollectors: () -> List<String> = {
            runCatching { ManagementFactory.getGarbageCollectorMXBeans().map { it.name } }.getOrDefault(emptyList())
        },
        private val startedAt: () -> Long? = { runCatching { ManagementFactory.getRuntimeMXBean().startTime }.getOrNull() },
        private val cgroupLimits: () -> CgroupLimits = { Cgroups.read() },
) {

    fun createReport(): RuntimeInformation {
        val cgroup = cgroupLimits()
        return RuntimeInformation(
                jvmName = property("java.vm.name"),
                jvmVendor = property("java.vm.vendor"),
                jvmVersion = property("java.vm.version"),
                javaVersion = property("java.version"),
                javaRuntimeVersion = property("java.runtime.version"),
                osName = property("os.name"),
                osVersion = property("os.version"),
                osArch = property("os.arch"),
                availableProcessors = availableProcessors(),
                // Long.MAX_VALUE is how the runtime says "no -Xmx was given".
                maxHeapInBytes = maxHeapInBytes().takeIf { it != Long.MAX_VALUE },
                garbageCollectors = garbageCollectors(),
                startedAt = startedAt(),
                cgroupVersion = cgroup.version,
                cpuQuotaInCores = cgroup.cpuQuotaInCores,
                cpuShares = cgroup.cpuShares,
                memoryLimitInBytes = cgroup.memoryLimitInBytes,
        )
    }
}
