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

package io.axoniq.platform.framework.eventprocessor

import io.axoniq.platform.framework.api.ProcessingGroupStatus
import io.mockk.every
import io.mockk.mockk
import org.axonframework.common.configuration.Configuration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * Focused unit tests for the [ProcessingGroupInfoSource] integration in [ProcessorReportCreator].
 * The full `createReport()` flow needs a live AF5 configuration with processors and segment
 * tracker statuses — that's integration territory and not what we're testing here. Instead we
 * reach into the private `processingGroupsFor` via reflection so we can exercise the optional
 * source seam in isolation.
 *
 * Reflection is the right tool here because the production class deliberately keeps this
 * method private (it's a callee of `streamingStatus(...)` only), and widening visibility just
 * for tests would leak the seam into the public surface.
 */
class ProcessorReportCreatorTest {

    @Test
    fun `processingGroupsFor returns empty when no ProcessingGroupInfoSource is registered`() {
        val config = baseConfigurationWith(infoSource = Optional.empty())
        val creator = ProcessorReportCreator(config)

        val result = invokeProcessingGroupsFor(creator, "orders")

        assertEquals(emptyList<ProcessingGroupStatus>(), result)
    }

    @Test
    fun `processingGroupsFor maps source infos to ProcessingGroupStatus entries`() {
        val source = mockk<ProcessingGroupInfoSource>()
        every { source.infoFor("orders") } returns listOf(
                ProcessingGroupInfoSource.ProcessingGroupInfo("orders", 3L),
                ProcessingGroupInfoSource.ProcessingGroupInfo("orders::audit", 0L),
        )
        val config = baseConfigurationWith(infoSource = Optional.of(source))
        val creator = ProcessorReportCreator(config)

        val result = invokeProcessingGroupsFor(creator, "orders")

        assertEquals(
                listOf(
                        ProcessingGroupStatus("orders", 3L),
                        ProcessingGroupStatus("orders::audit", 0L),
                ),
                result,
        )
    }

    @Test
    fun `processingGroupsFor swallows source exceptions and returns empty list`() {
        // A failing probe must not break processor reporting — the warning is logged but the
        // caller receives an empty list and the rest of the report still renders.
        val source = mockk<ProcessingGroupInfoSource>()
        every { source.infoFor("orders") } throws RuntimeException("boom")
        val config = baseConfigurationWith(infoSource = Optional.of(source))
        val creator = ProcessorReportCreator(config)

        val result = invokeProcessingGroupsFor(creator, "orders")

        assertEquals(emptyList<ProcessingGroupStatus>(), result)
    }

    /**
     * The constructor calls `getComponent(ProcessorMetricsRegistry::class.java)` and
     * `getOptionalComponent(ProcessingGroupInfoSource::class.java)`. We provide just enough of
     * each so construction succeeds; the metrics registry isn't exercised by the test path
     * (no segments => no metrics lookups), so a relaxed mock is fine.
     */
    private fun baseConfigurationWith(infoSource: Optional<ProcessingGroupInfoSource>): Configuration {
        val config = mockk<Configuration>(relaxed = true)
        every { config.getComponent(ProcessorMetricsRegistry::class.java) } returns ProcessorMetricsRegistry()
        every { config.getOptionalComponent(ProcessingGroupInfoSource::class.java) } returns infoSource
        return config
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeProcessingGroupsFor(creator: ProcessorReportCreator, processorName: String): List<ProcessingGroupStatus> {
        val method = ProcessorReportCreator::class.java.getDeclaredMethod("processingGroupsFor", String::class.java)
        method.isAccessible = true
        return method.invoke(creator, processorName) as List<ProcessingGroupStatus>
    }
}
