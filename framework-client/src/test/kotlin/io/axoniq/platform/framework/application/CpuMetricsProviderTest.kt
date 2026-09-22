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

import io.axoniq.platform.framework.application.CpuMetricsProvider.HostCpuTicks
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CpuMetricsProviderTest {

    private val second = 1_000_000_000L
    private val millisecond = 1_000_000L

    private var now = 0L
    private var processCpuTime: Long? = 0L
    private var hostTicks: HostCpuTicks? = HostCpuTicks(total = 0, idle = 0)
    private var allowanceInCores = 1.0
    private var fallbackLoad = CpuMetricsProvider.UNAVAILABLE

    private var processReads = 0
    private var hostReads = 0

    private fun provider() = CpuMetricsProvider(
            nanoTime = { now },
            processCpuTimeNanos = { processReads++; processCpuTime },
            hostCpuTicks = { hostReads++; hostTicks },
            cpuAllowanceInCores = { allowanceInCores },
            fallbackSystemCpuLoad = { fallbackLoad },
    )

    @Nested
    inner class ProcessCpuUsage {

        @Test
        fun `reports the share of its allowance that the process consumed`() {
            allowanceInCores = 2.0
            val provider = provider()

            // Half a core over one second, against an allowance of two.
            now += second
            processCpuTime = 500 * millisecond

            assertEquals(0.25, provider.getProcessCpuUsage(), 0.0001)
        }

        @Test
        fun `an idle process reads as idle, however rarely it is scheduled`() {
            // The case that used to saturate: a 200m container waking briefly and sleeping again. The JDK's
            // own getProcessCpuLoad divides by the slice allowed while awake and answers 1.0 here.
            allowanceInCores = 0.2
            val provider = provider()

            now += 10 * second
            processCpuTime = 17 * millisecond

            assertEquals(0.0085, provider.getProcessCpuUsage(), 0.0001)
        }

        @Test
        fun `measures against the quota rather than the visible processors`() {
            allowanceInCores = 0.2
            val provider = provider()

            // A tenth of a core for a second: half of what a 200m container may use.
            now += second
            processCpuTime = 100 * millisecond

            assertEquals(0.5, provider.getProcessCpuUsage(), 0.0001)
        }

        @Test
        fun `never reports more than the allowance`() {
            allowanceInCores = 0.2
            val provider = provider()

            now += second
            processCpuTime = 5 * second

            assertEquals(1.0, provider.getProcessCpuUsage(), 0.0001)
        }

        @Test
        fun `keeps the previous reading when sampled faster than the minimum interval`() {
            val provider = provider()
            now += second
            processCpuTime = 300 * millisecond
            val first = provider.getProcessCpuUsage()
            assertEquals(0.3, first, 0.0001)

            // A second consumer sampling 100ms later must not be handed a quantised reading.
            now += 100 * millisecond
            processCpuTime = 300 * millisecond + 5 * millisecond

            assertEquals(first, provider.getProcessCpuUsage(), 0.0001)
        }

        @Test
        fun `reports unavailable when the platform cannot supply process CPU time`() {
            processCpuTime = null

            assertEquals(CpuMetricsProvider.UNAVAILABLE, provider().getProcessCpuUsage(), 0.0001)
        }

        @Test
        fun `reads the counter once per call`() {
            val provider = provider()
            processReads = 0
            now += second

            provider.getProcessCpuUsage()

            assertEquals(1, processReads)
        }

        @Test
        fun `stops asking once the counter is known to be missing`() {
            processCpuTime = null
            val provider = provider()
            processReads = 0

            repeat(3) {
                now += second
                assertEquals(CpuMetricsProvider.UNAVAILABLE, provider.getProcessCpuUsage(), 0.0001)
            }

            assertEquals(0, processReads)
        }

        @Test
        fun `keeps the last reading when a counter that once answered stops`() {
            val provider = provider()
            now += second
            processCpuTime = 300 * millisecond
            assertEquals(0.3, provider.getProcessCpuUsage(), 0.0001)

            processCpuTime = null
            now += second

            assertEquals(0.3, provider.getProcessCpuUsage(), 0.0001)
        }
    }

    @Nested
    inner class FirstReading {

        @Test
        fun `reports unavailable until a whole interval has been measured`() {
            // The reporter's first run fires immediately on connect, inside the first interval. A 0.0 there
            // would be indistinguishable from an application that really is doing nothing.
            val provider = provider()
            now += 100 * millisecond

            assertEquals(CpuMetricsProvider.UNAVAILABLE, provider.getProcessCpuUsage(), 0.0001)
            assertEquals(CpuMetricsProvider.UNAVAILABLE, provider.getSystemCpuUsage(), 0.0001)
        }

        @Test
        fun `reports a real value once an interval has passed`() {
            val provider = provider()
            now += second
            processCpuTime = 250 * millisecond

            assertEquals(0.25, provider.getProcessCpuUsage(), 0.0001)
        }
    }

    @Nested
    inner class ParsingProcStat {

        private fun parse(line: String?) = CpuMetricsProvider.parseHostCpuTicks(line)

        @Test
        fun `does not count guest time twice`() {
            // The kernel's account_guest_time adds guest into user, and guest_nice into nice, so /proc/stat
            // reports them in both places. Summing all ten fields inflates a hypervisor's busy fraction.
            //        user nice system idle iowait irq softirq steal guest guest_nice
            val ticks = parse("cpu  1000 0 0 1000 0 0 0 0 800 0")!!

            assertEquals(2000L, ticks.total)
            assertEquals(1000L, ticks.idle)
        }

        @Test
        fun `counts iowait towards idle`() {
            val ticks = parse("cpu  100 0 0 700 200 0 0 0 0 0")!!

            assertEquals(1000L, ticks.total)
            assertEquals(900L, ticks.idle)
        }

        @Test
        fun `sums every busy state, not just user and system`() {
            val ticks = parse("cpu  10 20 30 40 50 60 70 80 0 0")!!

            assertEquals(360L, ticks.total)
            assertEquals(90L, ticks.idle)
        }

        @Test
        fun `bails rather than shifting columns when a field will not parse`() {
            // mapNotNull would drop the bad field and silently slide idle/iowait into the wrong columns.
            assertNull(parse("cpu  1000 0 wat 1000 0 0 0 0 0 0"))
        }

        @Test
        fun `rejects a line that is not the aggregate`() {
            assertNull(parse("cpu0 1000 0 0 1000 0 0 0 0 0 0"))
            assertNull(parse("intr 12345"))
            assertNull(parse(null))
        }

        @Test
        fun `tolerates a kernel reporting fewer fields`() {
            val ticks = parse("cpu  100 0 0 700 200")!!

            assertEquals(1000L, ticks.total)
            assertEquals(900L, ticks.idle)
        }
    }

    @Nested
    inner class ProcessCpuTimeSentinel {

        @Test
        fun `treats the beans negative sentinel as no value`() {
            assertNull(CpuMetricsProvider.sanitiseCpuTime(-1))
            assertNull(CpuMetricsProvider.sanitiseCpuTime(null))
            assertEquals(0L, CpuMetricsProvider.sanitiseCpuTime(0))
            assertEquals(42L, CpuMetricsProvider.sanitiseCpuTime(42))
        }
    }

    @Nested
    inner class SystemCpuUsage {

        @Test
        fun `reports the share of host ticks that were not idle`() {
            hostTicks = HostCpuTicks(total = 1_000, idle = 1_000)
            val provider = provider()

            now += second
            // 400 of the next 1000 ticks were busy.
            hostTicks = HostCpuTicks(total = 2_000, idle = 1_600)

            assertEquals(0.4, provider.getSystemCpuUsage(), 0.0001)
        }

        @Test
        fun `keeps the previous reading when sampled faster than the minimum interval`() {
            hostTicks = HostCpuTicks(total = 1_000, idle = 1_000)
            val provider = provider()
            now += second
            hostTicks = HostCpuTicks(total = 2_000, idle = 1_500)
            val first = provider.getSystemCpuUsage()
            assertEquals(0.5, first, 0.0001)

            now += 100 * millisecond
            hostTicks = HostCpuTicks(total = 2_010, idle = 1_500)

            assertEquals(first, provider.getSystemCpuUsage(), 0.0001)
        }

        @Test
        fun `falls back to the bean where there are no host counters to read`() {
            // No /proc/stat means no Linux, and so no cgroup to distort the bean's own answer.
            hostTicks = null
            fallbackLoad = 0.42

            assertEquals(0.42, provider().getSystemCpuUsage(), 0.0001)
        }

        @Test
        fun `reports unavailable when neither the host counters nor the bean can answer`() {
            hostTicks = null
            fallbackLoad = CpuMetricsProvider.UNAVAILABLE

            assertEquals(CpuMetricsProvider.UNAVAILABLE, provider().getSystemCpuUsage(), 0.0001)
        }

        @Test
        fun `reads the counters once per call`() {
            val provider = provider()
            hostReads = 0
            now += second

            provider.getSystemCpuUsage()

            assertEquals(1, hostReads)
        }

        @Test
        fun `keeps the last reading when a file that once read stops reading`() {
            // A short read or a filesystem hiccup should not flip the reported value to UNAVAILABLE.
            hostTicks = HostCpuTicks(total = 1_000, idle = 1_000)
            val provider = provider()
            now += second
            hostTicks = HostCpuTicks(total = 2_000, idle = 1_500)
            assertEquals(0.5, provider.getSystemCpuUsage(), 0.0001)

            hostTicks = null
            now += second

            assertEquals(0.5, provider.getSystemCpuUsage(), 0.0001)
        }

        @Test
        fun `stops reaching for the file once it is known to be absent`() {
            // Off Linux every attempt is a thrown FileNotFoundException, and this runs on every report.
            hostTicks = null
            fallbackLoad = 0.42
            val provider = provider()
            hostReads = 0

            repeat(3) {
                now += second
                assertEquals(0.42, provider.getSystemCpuUsage(), 0.0001)
            }

            assertEquals(0, hostReads)
        }
    }
}
