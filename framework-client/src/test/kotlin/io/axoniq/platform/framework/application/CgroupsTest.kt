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

package io.axoniq.platform.framework.application

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CgroupsTest {

    @TempDir
    lateinit var root: File

    private fun write(path: String, contents: String) {
        val file = File(root, path)
        file.parentFile.mkdirs()
        file.writeText(contents)
    }

    /** Stands in for `/proc/self/cgroup`; the default says this process sits at the hierarchy root. */
    private fun self(vararg lines: String = arrayOf("0::/")): String {
        val file = File(root, "proc-self-cgroup")
        file.writeText(lines.joinToString("\n"))
        return file.absolutePath
    }

    private fun read(selfPath: String = self()) = Cgroups.read(root.absolutePath, selfPath)

    @Nested
    inner class VersionTwo {

        @Test
        fun `reads the quota, weight and memory limit of a 200m container`() {
            write("cgroup.controllers", "cpu memory")
            write("cpu.max", "20000 100000\n")
            write("cpu.weight", "4\n")
            write("memory.max", "536870912\n")

            val limits = read()

            assertEquals(2, limits.version)
            assertEquals(0.2, limits.cpuQuotaInCores!!, 0.0001)
            assertEquals(4L, limits.cpuShares)
            assertEquals(536870912L, limits.memoryLimitInBytes)
        }

        @Test
        fun `reports no quota when the CPU is unlimited`() {
            write("cgroup.controllers", "cpu memory")
            write("cpu.max", "max 100000\n")
            write("memory.max", "max\n")

            val limits = read()

            assertEquals(2, limits.version)
            assertNull(limits.cpuQuotaInCores)
            assertNull(limits.memoryLimitInBytes)
        }
    }

    @Nested
    inner class VersionOne {

        @Test
        fun `reads the quota, shares and memory limit`() {
            write("cpu/cpu.cfs_quota_us", "50000\n")
            write("cpu/cpu.cfs_period_us", "100000\n")
            write("cpu/cpu.shares", "512\n")
            write("memory/memory.limit_in_bytes", "268435456\n")

            val limits = read()

            assertEquals(1, limits.version)
            assertEquals(0.5, limits.cpuQuotaInCores!!, 0.0001)
            assertEquals(512L, limits.cpuShares)
            assertEquals(268435456L, limits.memoryLimitInBytes)
        }

        @Test
        fun `reports no quota when the value is the unlimited sentinel`() {
            write("cpu/cpu.cfs_quota_us", "-1\n")
            write("cpu/cpu.cfs_period_us", "100000\n")
            // How a v1 hierarchy spells "no memory limit".
            write("memory/memory.limit_in_bytes", "9223372036854771712\n")

            val limits = read()

            assertEquals(1, limits.version)
            assertNull(limits.cpuQuotaInCores)
            assertNull(limits.memoryLimitInBytes)
        }
    }

    @Nested
    inner class ProcessesInASubCgroup {

        @Test
        fun `finds a v2 limit set on the process own cgroup rather than the mount root`() {
            // A systemd unit with CPUQuota=, or a container started with --cgroupns=host. The mount root
            // carries no limit at all, and looking only there would report the process as unlimited.
            write("cgroup.controllers", "cpu memory")
            write("cpu.max", "max 100000\n")
            write("system.slice/app.service/cpu.max", "400000 100000\n")
            write("system.slice/app.service/memory.max", "536870912\n")

            val limits = read(self("0::/system.slice/app.service"))

            assertEquals(2, limits.version)
            assertEquals(4.0, limits.cpuQuotaInCores!!, 0.0001)
            assertEquals(536870912L, limits.memoryLimitInBytes)
        }

        @Test
        fun `pairs each level's quota with its own period`() {
            // v1 keeps quota and period in separate files, and levels may use different periods. Taking the
            // smallest quota and dividing it by somebody else's period yields a number that is not a limit.
            // Own cgroup: 500000/1000000 = 0.5 cores.  Parent: 100000/100000 = 1.0 core.  Effective: 0.5.
            write("cpu/system.slice/app.service/cpu.cfs_quota_us", "500000\n")
            write("cpu/system.slice/app.service/cpu.cfs_period_us", "1000000\n")
            write("cpu/system.slice/cpu.cfs_quota_us", "100000\n")
            write("cpu/system.slice/cpu.cfs_period_us", "100000\n")

            val limits = read(self("4:cpu,cpuacct:/system.slice/app.service"))

            assertEquals(0.5, limits.cpuQuotaInCores!!, 0.0001)
        }

        @Test
        fun `takes the tightest v1 quota when an ancestor is more restrictive`() {
            write("cpu/app/cpu.cfs_quota_us", "400000\n")
            write("cpu/app/cpu.cfs_period_us", "100000\n")
            write("cpu/cpu.cfs_quota_us", "50000\n")
            write("cpu/cpu.cfs_period_us", "100000\n")

            val limits = read(self("4:cpu,cpuacct:/app"))

            assertEquals(0.5, limits.cpuQuotaInCores!!, 0.0001)
        }

        @Test
        fun `takes the tightest memory limit when an ancestor is more restrictive`() {
            write("cgroup.controllers", "cpu memory")
            write("kubepods.slice/memory.max", "268435456\n")
            write("kubepods.slice/pod123/memory.max", "536870912\n")

            val limits = read(self("0::/kubepods.slice/pod123"))

            assertEquals(268435456L, limits.memoryLimitInBytes)
        }

        @Test
        fun `takes the tightest v1 memory limit when an ancestor is more restrictive`() {
            write("cpu/cpu.cfs_quota_us", "-1\n")
            write("memory/docker/abc/memory.limit_in_bytes", "536870912\n")
            write("memory/memory.limit_in_bytes", "268435456\n")

            val limits = read(self("4:cpu:/docker/abc", "3:memory:/docker/abc"))

            assertEquals(268435456L, limits.memoryLimitInBytes)
        }

        @Test
        fun `takes the tightest limit when an ancestor is more restrictive`() {
            // A limit on a parent binds just as much as one set here.
            write("cgroup.controllers", "cpu")
            write("kubepods.slice/cpu.max", "100000 100000\n")
            write("kubepods.slice/pod123/cpu.max", "400000 100000\n")

            val limits = read(self("0::/kubepods.slice/pod123"))

            assertEquals(1.0, limits.cpuQuotaInCores!!, 0.0001)
        }

        @Test
        fun `follows the per-controller path in a v1 hierarchy`() {
            write("cpu/docker/abc123/cpu.cfs_quota_us", "20000\n")
            write("cpu/docker/abc123/cpu.cfs_period_us", "100000\n")
            write("memory/docker/abc123/memory.limit_in_bytes", "268435456\n")

            val limits = read(self(
                    "4:cpu,cpuacct:/docker/abc123",
                    "3:memory:/docker/abc123",
            ))

            assertEquals(1, limits.version)
            assertEquals(0.2, limits.cpuQuotaInCores!!, 0.0001)
            assertEquals(268435456L, limits.memoryLimitInBytes)
        }

        @Test
        fun `falls back to the mount root when the process cgroup cannot be read`() {
            write("cgroup.controllers", "cpu")
            write("cpu.max", "20000 100000\n")

            val limits = read("${root.absolutePath}/does-not-exist")

            assertEquals(0.2, limits.cpuQuotaInCores!!, 0.0001)
        }
    }

    @Nested
    inner class CpuAllowance {

        @Test
        fun `is the quota when one is enforced`() {
            write("cgroup.controllers", "cpu")
            write("cpu.max", "20000 100000\n")

            assertEquals(0.2, Cgroups.cpuAllowanceInCores(root.absolutePath, self()), 0.0001)
        }

        @Test
        fun `falls back to the visible processors when nothing is enforced`() {
            write("cgroup.controllers", "cpu")
            write("cpu.max", "max 100000\n")

            assertEquals(
                    Runtime.getRuntime().availableProcessors().toDouble(),
                    Cgroups.cpuAllowanceInCores(root.absolutePath, self()),
                    0.0001
            )
        }
    }

    @Test
    fun `reports nothing at all when the process is not in a control group`() {
        assertEquals(CgroupLimits(), read())
    }
}
