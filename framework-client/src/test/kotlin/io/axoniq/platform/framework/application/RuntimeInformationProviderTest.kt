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

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeInformationProviderTest {

    private val properties = mapOf(
            "java.vm.name" to "OpenJDK 64-Bit Server VM",
            "java.vm.vendor" to "Eclipse Adoptium",
            "java.vm.version" to "21.0.10+7",
            "java.version" to "21.0.10",
            "java.runtime.version" to "21.0.10+7-LTS",
            "os.name" to "Linux",
            "os.version" to "6.12.94",
            "os.arch" to "amd64",
    )

    private fun provider(
            cgroup: CgroupLimits = CgroupLimits(),
            maxHeap: Long = 512L * 1024 * 1024,
            processors: Int = 1,
    ) = RuntimeInformationProvider(
            property = { properties[it] },
            availableProcessors = { processors },
            maxHeapInBytes = { maxHeap },
            garbageCollectors = { listOf("G1 Young Generation", "G1 Old Generation") },
            startedAt = { 1_758_000_000_000 },
            cgroupLimits = { cgroup },
    )

    @Test
    fun `describes the JVM and the operating system`() {
        val report = provider().createReport()

        assertEquals("OpenJDK 64-Bit Server VM", report.jvmName)
        assertEquals("Eclipse Adoptium", report.jvmVendor)
        assertEquals("21.0.10+7", report.jvmVersion)
        assertEquals("21.0.10", report.javaVersion)
        assertEquals("21.0.10+7-LTS", report.javaRuntimeVersion)
        assertEquals("Linux", report.osName)
        assertEquals("6.12.94", report.osVersion)
        assertEquals("amd64", report.osArch)
        assertEquals(listOf("G1 Young Generation", "G1 Old Generation"), report.garbageCollectors)
        assertEquals(1_758_000_000_000, report.startedAt)
    }

    @Test
    fun `carries the limits that give the reported metrics a denominator`() {
        val report = provider(
                cgroup = CgroupLimits(
                        version = 2,
                        cpuQuotaInCores = 0.2,
                        cpuShares = 4,
                        memoryLimitInBytes = 536870912,
                ),
        ).createReport()

        assertEquals(2, report.cgroupVersion)
        assertEquals(0.2, report.cpuQuotaInCores!!, 0.0001)
        assertEquals(4L, report.cpuShares)
        assertEquals(536870912L, report.memoryLimitInBytes)
        // A 200m container is allowed a fifth of a core while the runtime still reports a whole one; both
        // numbers are needed to read a CPU percentage.
        assertEquals(1, report.availableProcessors)
    }

    @Test
    fun `reports an unbounded heap as absent rather than as a very large number`() {
        assertNull(provider(maxHeap = Long.MAX_VALUE).createReport().maxHeapInBytes)
        assertEquals(536870912L, provider(maxHeap = 536870912).createReport().maxHeapInBytes)
    }

    @Test
    fun `leaves unknown properties null instead of guessing`() {
        val report = RuntimeInformationProvider(property = { null }).createReport()

        assertNull(report.jvmName)
        assertNull(report.osName)
        assertNotNull(report.availableProcessors)
    }

    @Test
    fun `gathers nothing that identifies the machine or its owner`() {
        // Deliberately the REAL provider, reading real system properties. Injecting a stub property map
        // here would make the assertion unfailable: a newly added user.name field would come back null.
        val rendered = RuntimeInformationProvider().createReport().toString()

        listOf("user.name", "user.home", "user.dir", "java.class.path", "java.io.tmpdir")
                .mapNotNull { System.getProperty(it) }
                .filter { it.isNotBlank() }
                .forEach {
                    assertTrue(!rendered.contains(it), "Runtime information must not carry $it")
                }
    }

    @Test
    fun `reads only the properties it declares`() {
        val asked = mutableListOf<String>()
        RuntimeInformationProvider(property = { asked.add(it); null }).createReport()

        assertEquals(
                listOf(
                        "java.vm.name", "java.vm.vendor", "java.vm.version",
                        "java.version", "java.runtime.version",
                        "os.name", "os.version", "os.arch",
                ),
                asked
        )
    }
}
