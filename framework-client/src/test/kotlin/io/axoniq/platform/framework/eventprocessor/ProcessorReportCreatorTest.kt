/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.eventprocessor

import io.axoniq.platform.framework.api.ProcessingGroupStatus
import io.mockk.every
import io.mockk.mockk
import org.axonframework.common.configuration.Configuration
import io.axoniq.platform.framework.tenancy.PlatformTenancy
import org.axonframework.messaging.eventhandling.processing.EventProcessor
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.EventTrackerStatus
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.OptionalLong

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

    @Nested
    inner class ProcessingGroups {

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
    }

    @Nested
    inner class TenantPositions {

        private val token = mockk<TrackingToken>()

        private fun creatorWith(tenancy: PlatformTenancy?): ProcessorReportCreator {
            val processor = mockk<PooledStreamingEventProcessor>(relaxed = true)
            val status = mockk<EventTrackerStatus>(relaxed = true)
            every { status.segment } returns Segment.ROOT_SEGMENT
            every { status.trackingToken } returns token
            every { status.currentPosition } returns OptionalLong.of(5)
            every { status.resetPosition } returns OptionalLong.empty()
            every { processor.processingStatus() } returns mapOf(0 to status)
            every { processor.tokenStoreIdentifier } returns "store"
            val config = baseConfigurationWith(infoSource = Optional.empty())
            every { config.getComponents(EventProcessor::class.java) } returns mapOf("notifier" to processor)
            return ProcessorReportCreator(config) { tenancy }
        }

        @Test
        fun `reports the position of every tenant next to their sum`() {
            val tenancy = mockk<PlatformTenancy>()
            every { tenancy.positionsIn(token) } returns mapOf("tenant-a" to 2L, "tenant-b" to 3L)

            val segment = creatorWith(tenancy).createReport().processors.single().segments.single()

            assertEquals(5L, segment.position)
            assertEquals(mapOf("tenant-a" to 2L, "tenant-b" to 3L), segment.tenantPositions)
        }

        @Test
        fun `reports no tenant positions for a single-tenant application`() {
            val segment = creatorWith(tenancy = null).createReport().processors.single().segments.single()

            assertEquals(5L, segment.position)
            assertNull(segment.tenantPositions)
        }
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
