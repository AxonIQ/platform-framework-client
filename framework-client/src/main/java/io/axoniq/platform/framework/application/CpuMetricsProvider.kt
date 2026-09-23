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

package io.axoniq.platform.framework.application

import java.io.File
import java.lang.management.ManagementFactory

/**
 * Provides CPU metrics for the system and the process, as a fraction between 0 and 1, or [UNAVAILABLE]
 * when the platform offers no way to measure it.
 *
 * Both readings are derived from monotonic counters — the JVM's total process CPU time, and the host's
 * cumulative jiffies in `/proc/stat` — divided by the wall-clock time that passed between two samples.
 *
 * `OperatingSystemMXBean.getProcessCpuLoad`/`getCpuLoad` are deliberately *not* used. Under a cgroup CPU
 * quota the JDK divides the CPU consumed by `quota * nr_periods`, where `nr_periods` counts only the
 * scheduling periods in which the cgroup had a runnable task. An idle application is then measured against
 * the slice it was allowed while it happened to be awake, rather than against the time that actually
 * passed, so its reading *rises* as it gets quieter and saturates at the 1.0 the JDK clamps to. Measured
 * against elapsed time instead, an idle application reads as idle.
 */
class CpuMetricsProvider(
        private val nanoTime: () -> Long = { System.nanoTime() },
        private val processCpuTimeNanos: () -> Long? = detectProcessCpuTime(),
        private val hostCpuTicks: () -> HostCpuTicks? = { readHostCpuTicks() },
        private val cpuAllowanceInCores: () -> Double = { Cgroups.cpuAllowanceInCores() },
        private val fallbackSystemCpuLoad: () -> Double = detectSystemCpuLoad(),
) {
    private var lastProcessSample: ProcessSample? = null
    private var lastHostTicks: HostCpuTicks? = null
    private var lastHostSampleAt: Long = 0

    // Until a full interval has been measured there is nothing to report. Saying so beats reporting a 0.0
    // that cannot be told apart from a genuinely idle application — the reporter's first run fires
    // immediately on connect, well inside the first interval.
    private var processUsage = UNAVAILABLE
    private var systemUsage = UNAVAILABLE

    /**
     * Whether each counter exists at all, settled once here rather than per reading. Off Linux `/proc/stat`
     * is absent, and asking for it costs a thrown [java.io.FileNotFoundException] every time — so the
     * question is asked once and the answer kept.
     */
    private val processCounterAvailable: Boolean
    private val hostCountersAvailable: Boolean

    init {
        // The same reads settle availability and prime the baselines, so the first reading measures a real
        // interval rather than everything since the JVM started.
        val cpuTime = processCpuTimeNanos()
        processCounterAvailable = cpuTime != null
        if (cpuTime != null) sampleProcess(cpuTime)

        val ticks = hostCpuTicks()
        hostCountersAvailable = ticks != null
        if (ticks != null) sampleHost(ticks)
    }

    /**
     * CPU consumed by this JVM as a fraction of what it is allowed to use — the cgroup quota where one is
     * enforced, otherwise the processors visible to the runtime.
     */
    @Synchronized
    fun getProcessCpuUsage(): Double {
        if (!processCounterAvailable) return UNAVAILABLE
        // A counter that answered once can still fail later; that is not a reason to stop asking.
        val cpuTime = processCpuTimeNanos() ?: return processUsage
        sampleProcess(cpuTime)
        return processUsage
    }

    /**
     * CPU busy across the host, as a fraction of its total capacity. Off Linux there is no `/proc/stat` to
     * read, and equally no cgroup to distort the bean's own answer, so it is asked directly.
     */
    @Synchronized
    fun getSystemCpuUsage(): Double {
        if (!hostCountersAvailable) return fallbackSystemCpuLoad()
        val ticks = hostCpuTicks() ?: return systemUsage
        sampleHost(ticks)
        return systemUsage
    }

    private fun sampleProcess(cpuTime: Long) {
        val now = nanoTime()
        val previous = lastProcessSample
        if (previous != null) {
            val elapsed = now - previous.takenAt
            if (elapsed < MINIMUM_SAMPLE_INTERVAL_NANOS) return
            val allowance = cpuAllowanceInCores()
            if (allowance > 0) {
                processUsage = ((cpuTime - previous.cpuTimeNanos).toDouble() / elapsed / allowance)
                        .coerceIn(0.0, 1.0)
            }
        }
        lastProcessSample = ProcessSample(cpuTime, now)
    }

    private fun sampleHost(ticks: HostCpuTicks) {
        val now = nanoTime()
        val previous = lastHostTicks
        if (previous != null) {
            if (now - lastHostSampleAt < MINIMUM_SAMPLE_INTERVAL_NANOS) return
            val total = ticks.total - previous.total
            val idle = ticks.idle - previous.idle
            if (total > 0) {
                systemUsage = ((total - idle).toDouble() / total).coerceIn(0.0, 1.0)
            }
        }
        lastHostTicks = ticks
        lastHostSampleAt = now
    }

    private data class ProcessSample(val cpuTimeNanos: Long, val takenAt: Long)

    /** Cumulative host CPU jiffies: [total] across all states, of which [idle] were spent doing nothing. */
    data class HostCpuTicks(val total: Long, val idle: Long)

    companion object {
        /** Returned when the platform offers no way to measure the value. */
        const val UNAVAILABLE = -1.0

        /**
         * Two samples closer together than this are not worth dividing: the counters move in coarse steps
         * (a jiffy is 10ms), so a short interval quantises the result towards 0 or 1. A caller sampling
         * faster than this — a second consumer of the same provider, say — gets the previous reading rather
         * than a noisy one.
         */
        private const val MINIMUM_SAMPLE_INTERVAL_NANOS = 1_000_000_000L

        private const val PROC_STAT = "/proc/stat"

        private fun detectProcessCpuTime(): () -> Long? {
            val osBean = ManagementFactory.getOperatingSystemMXBean()
            val method = listOf(
                    "com.ibm.lang.management.OperatingSystemMXBean",
                    "com.sun.management.OperatingSystemMXBean"
            ).firstExistingClass()?.detectMethod(osBean, "getProcessCpuTime")
            return {
                sanitiseCpuTime(try {
                    method?.invoke(osBean) as Long?
                } catch (e: Exception) {
                    null
                })
            }
        }

        /** The bean answers -1 rather than throwing when it cannot supply the counter. */
        internal fun sanitiseCpuTime(value: Long?): Long? = if (value != null && value >= 0) value else null

        private fun detectSystemCpuLoad(): () -> Double {
            val osBean = ManagementFactory.getOperatingSystemMXBean()
            val beanClass = listOf(
                    "com.ibm.lang.management.OperatingSystemMXBean",
                    "com.sun.management.OperatingSystemMXBean"
            ).firstExistingClass()
            val method = beanClass?.detectMethod(osBean, "getCpuLoad")
                    ?: beanClass?.detectMethod(osBean, "getSystemCpuLoad")
            return {
                try {
                    method?.invoke(osBean) as Double? ?: UNAVAILABLE
                } catch (e: Exception) {
                    UNAVAILABLE
                }
            }
        }

        private fun readHostCpuTicks(): HostCpuTicks? = try {
            parseHostCpuTicks(File(PROC_STAT).bufferedReader().use { it.readLine() })
        } catch (e: Exception) {
            null
        }

        /**
         * Parses the aggregate line of `/proc/stat`, which holds the host's cumulative jiffies per CPU
         * state: `cpu user nice system idle iowait irq softirq steal guest guest_nice`.
         *
         * Only the first eight fields are summed. `guest` and `guest_nice` are *already* included in
         * `user` and `nice` — the kernel's `account_guest_time` adds them to both — so summing everything
         * counts a hypervisor's guest time twice and overstates how busy the host is.
         *
         * `iowait` counts towards idle: the CPU was free to run something else.
         */
        internal fun parseHostCpuTicks(line: String?): HostCpuTicks? {
            val raw = line?.takeIf { it.startsWith("cpu ") }
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.drop(1)
                    ?: return null
            // Anything unparseable would shift every later field, so bail rather than read the wrong column.
            if (raw.size < 5) return null
            val fields = raw.take(8).map { it.toLongOrNull() ?: return null }
            return HostCpuTicks(total = fields.sum(), idle = fields[3] + fields[4])
        }

    }
}
