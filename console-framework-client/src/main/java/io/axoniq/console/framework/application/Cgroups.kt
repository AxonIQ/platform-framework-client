/*
 * Copyright (c) 2026. AxonIQ B.V.
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

package io.axoniq.console.framework.application

import java.io.File

/**
 * What the control group this process runs in allows it to use. Every value is null when the limit is not
 * enforced, when the files cannot be read, or when there is no cgroup at all — which is the normal case
 * outside Linux.
 *
 * @param version the cgroup hierarchy in use, 1 or 2
 * @param cpuQuotaInCores the CPU bandwidth limit, in cores; a Kubernetes `limits.cpu` of `200m` reads 0.2
 * @param cpuShares the raw relative weight, from which Kubernetes derives `requests.cpu`; its scale
 *                  differs between the two hierarchies, so it is reported unconverted
 * @param memoryLimitInBytes the memory limit, matching Kubernetes' `limits.memory`
 */
data class CgroupLimits(
        val version: Int? = null,
        val cpuQuotaInCores: Double? = null,
        val cpuShares: Long? = null,
        val memoryLimitInBytes: Long? = null,
)

object Cgroups {
    private const val DEFAULT_ROOT = "/sys/fs/cgroup"
    private const val DEFAULT_SELF = "/proc/self/cgroup"

    /** A v1 hierarchy writes its "no limit" as a number close to [Long.MAX_VALUE] rather than as a word. */
    private const val UNLIMITED_THRESHOLD = Long.MAX_VALUE / 2

    /**
     * Never throws. This runs while the setup payload is built, and an exception there would fail every
     * connection attempt for the life of the process — a locked-down `SecurityManager` denying
     * `/sys/fs/cgroup` is enough to make even [File.exists] throw.
     */
    fun read(root: String = DEFAULT_ROOT, selfCgroup: String = DEFAULT_SELF): CgroupLimits = try {
        val self = readSelfCgroup(selfCgroup)
        when {
            File("$root/cgroup.controllers").exists() -> readV2(root, self.unified)
            File("$root/cpu").isDirectory -> readV1(root, self)
            else -> CgroupLimits()
        }
    } catch (e: Exception) {
        CgroupLimits()
    }

    /** The CPU this process may use, in cores, falling back to the processors the runtime can see. */
    fun cpuAllowanceInCores(root: String = DEFAULT_ROOT, selfCgroup: String = DEFAULT_SELF): Double =
            read(root, selfCgroup).cpuQuotaInCores ?: Runtime.getRuntime().availableProcessors().toDouble()

    /**
     * Where this process sits in each hierarchy, from `/proc/self/cgroup`. Without it only a process whose
     * own cgroup happens to *be* the mount root sees its limits — true of a container with a private cgroup
     * namespace, which is the common case, but not of a systemd unit with `CPUQuota=`, nor of a container
     * started with `--cgroupns=host`, both of which sit in a sub-cgroup and would otherwise look unlimited.
     */
    private data class SelfCgroup(val unified: String, val perController: Map<String, String>)

    private fun readSelfCgroup(path: String): SelfCgroup {
        val perController = HashMap<String, String>()
        var unified = ""
        try {
            File(path).readLines().forEach { line ->
                // "<hierarchy-id>:<controllers>:<path>", with an empty controller list for v2.
                val parts = line.split(":")
                if (parts.size >= 3) {
                    val controllers = parts[1]
                    val cgroupPath = parts.subList(2, parts.size).joinToString(":")
                    if (controllers.isEmpty()) {
                        unified = cgroupPath
                    } else {
                        controllers.split(",").forEach { perController[it] = cgroupPath }
                    }
                }
            }
        } catch (e: Exception) {
            // Not Linux, or unreadable: fall back to treating the mount root as our own cgroup.
        }
        return SelfCgroup(unified, perController)
    }

    private fun readV2(root: String, path: String): CgroupLimits {
        val dirs = chain(root, path)
        return CgroupLimits(
                version = 2,
                cpuQuotaInCores = dirs.mapNotNull { cpuMaxInCores(it) }.minOrNull(),
                cpuShares = dirs.firstNotNullOfOrNull { number(it, "cpu.weight") }?.takeIf { it > 0 },
                memoryLimitInBytes = dirs.mapNotNull { limit(it, "memory.max") }.minOrNull(),
        )
    }

    private fun readV1(root: String, self: SelfCgroup): CgroupLimits {
        val cpuDirs = chain("$root/cpu", self.perController["cpu"] ?: "")
        val memoryDirs = chain("$root/memory", self.perController["memory"] ?: "")
        return CgroupLimits(
                version = 1,
                // The quota and the period have to come from the SAME level: a limit is the ratio of the
                // pair, and levels are free to use different periods. Taking the smallest quota and pairing
                // it with someone else's period is not the smallest limit, it is a different number.
                cpuQuotaInCores = cpuDirs
                        .mapNotNull { quotaInCores(limit(it, "cpu.cfs_quota_us"), number(it, "cpu.cfs_period_us")) }
                        .minOrNull(),
                cpuShares = cpuDirs.firstNotNullOfOrNull { number(it, "cpu.shares") }?.takeIf { it > 0 },
                memoryLimitInBytes = memoryDirs.mapNotNull { limit(it, "memory.limit_in_bytes") }.minOrNull(),
        )
    }

    /** `"<quota> <period>"`, where quota is the literal `max` when unlimited. */
    private fun cpuMaxInCores(dir: String): Double? {
        val parts = readFile("$dir/cpu.max")?.split(Regex("\\s+")) ?: return null
        if (parts.size < 2) return null
        return quotaInCores(parts[0].toLongOrNull(), parts[1].toLongOrNull())
    }

    /**
     * This process's cgroup directory and each of its ancestors, nearest first. A limit set on an ancestor
     * binds just as much as one set here, so the effective limit is the smallest across the chain — but it
     * has to be computed per level, since each level carries its own complete limit.
     */
    private fun chain(base: String, cgroupPath: String): List<String> {
        val dirs = ArrayList<String>()
        var path = cgroupPath.trimEnd('/')
        while (true) {
            dirs.add("$base$path")
            if (path.isEmpty()) return dirs
            path = path.substringBeforeLast('/', "")
        }
    }

    private fun quotaInCores(quota: Long?, period: Long?): Double? =
            if (quota != null && period != null && quota > 0 && period > 0) quota.toDouble() / period else null

    private fun readFile(path: String): String? = try {
        File(path).readText().trim().takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    private fun number(dir: String, name: String): Long? = readFile("$dir/$name")?.toLongOrNull()

    /** A limit as a positive count of bytes or microseconds, or null for any way of spelling "unlimited". */
    private fun limit(dir: String, name: String): Long? =
            number(dir, name)?.takeIf { it in 1 until UNLIMITED_THRESHOLD }
}
