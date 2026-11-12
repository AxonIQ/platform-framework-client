/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.eventprocessor

import io.axoniq.platform.framework.api.ProcessingGroupStatus
import io.axoniq.platform.framework.api.ProcessorMode
import io.axoniq.platform.framework.api.ProcessorStatus
import io.axoniq.platform.framework.api.ProcessorStatusReport
import io.axoniq.platform.framework.api.SegmentDetails
import io.axoniq.platform.framework.api.SegmentOverview
import io.axoniq.platform.framework.api.SegmentStatus
import io.axoniq.platform.framework.tenancy.PlatformTenancy
import org.axonframework.common.ReflectionUtils.getFieldValue
import org.axonframework.common.configuration.Configuration
import org.axonframework.messaging.eventhandling.processing.EventProcessor
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.EventTrackerStatus
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessor
import org.slf4j.LoggerFactory

class ProcessorReportCreator @JvmOverloads constructor(
        private val processingConfig: Configuration,
        tenancyOf: (Configuration) -> PlatformTenancy? = PlatformTenancy::of,
) {
    private val logger = LoggerFactory.getLogger(ProcessorReportCreator::class.java)
    private val metricsRegistry = processingConfig.getComponent(ProcessorMetricsRegistry::class.java)
    // Optional — only present when an addon (currently only axoniq-dead-letter) registers a source.
    private val processingGroupInfoSource: ProcessingGroupInfoSource? =
            processingConfig.getOptionalComponent(ProcessingGroupInfoSource::class.java).orElse(null)
    private val tenancy: PlatformTenancy? = tenancyOf(processingConfig)

    fun createReport() = ProcessorStatusReport(
        processingConfig.getComponents(EventProcessor::class.java)
            .flatMap { entry ->
                when (val processor = entry.value) {
                    is StreamingEventProcessor -> listOf(streamingStatus(entry.key, processor))
                    is SubscribingEventProcessor -> listOf(subscribingStatus(entry.key, processor))
                    else -> listOf(defaultStatus(entry.key, processor))
                }
            }
    )

    private fun streamingStatus(name: String, processor: StreamingEventProcessor) =
        ProcessorStatus(
            name,
            processingGroupsFor(name),
            processor.tokenStoreIdentifier,
            processor.toType(),
            processor.isRunning,
            processor.isError,
            processor.maxCapacity(),
            processor.processingStatus().filterValues { !it.isErrorState }.size,
            processor.processingStatus().map { (_, segment) -> segment.toStatus(name) },
        )

    private fun processingGroupsFor(processorName: String): List<ProcessingGroupStatus> {
        val source = processingGroupInfoSource ?: return emptyList()
        return try {
            source.infoFor(processorName)
                    .map { ProcessingGroupStatus(it.processingGroup, it.dlqSize, it.dlqSizePerTenant) }
        } catch (e: Exception) {
            // A failing probe must not break processor reporting.
            logger.warn("Failed to collect processing group information for processor [{}]", processorName, e)
            emptyList()
        }
    }

    private fun subscribingStatus(name: String, processor: SubscribingEventProcessor) =
        ProcessorStatus(
            name,
            emptyList(),
            "",
            ProcessorMode.SUBSCRIBING,
            processor.isRunning,
            processor.isError,
            0,
            0,
            emptyList(),
        )

    private fun defaultStatus(name: String, processor: EventProcessor) =
        ProcessorStatus(
            name,
            emptyList(),
            "",
            ProcessorMode.UNKNOWN,
            processor.isRunning,
            processor.isError,
            0,
            0,
            emptyList(),
        )

    private fun StreamingEventProcessor.tokenStore(): TokenStore? {
        return getFieldValue<TokenStore>(getField("tokenStore"), this)
    }


    fun createSegmentOverview(processorName: String): SegmentOverview {
        val processor = processingConfig.getComponents(StreamingEventProcessor::class.java).values.first { it.name() == processorName }
        val tokenStore = processor.tokenStore()
                ?: throw IllegalStateException("Processor [$processorName] does not have a token store.")
        val segments = tokenStore.fetchSegments(processorName, null).join()
        return SegmentOverview(
            segments.map { SegmentDetails(it.segmentId, it.mergeableSegmentId(), it.mask) }
        )
    }

    private fun StreamingEventProcessor.toType(): ProcessorMode {
        return when (this) {
            is PooledStreamingEventProcessor -> ProcessorMode.POOLED
            else -> ProcessorMode.UNKNOWN
        }
    }

    private fun EventTrackerStatus.toStatus(name: String) = SegmentStatus(
        segment = this.segment.segmentId,
        mergeableSegment = this.segment.mergeableSegmentId(),
        mask = this.segment.mask,
        oneOf = this.segment.mask + 1,
        caughtUp = this.isCaughtUp,
        error = this.isErrorState,
        errorType = this.error?.javaClass?.typeName,
        errorMessage = this.error?.message,
        ingestLatency = metricsRegistry.ingestLatencyForProcessor(name, this.segment.segmentId).getValue(),
        commitLatency = metricsRegistry.commitLatencyForProcessor(name, this.segment.segmentId).getValue(),
        processingLatency = metricsRegistry.processingMessageLatencyForProcessor(name, this.segment.segmentId)
            ?.toDouble()
            ?: -1.0,
        position = this.currentPosition?.orElse(-1) ?: -1,
        resetPosition = this.resetPosition?.orElse(-1) ?: -1,
        tenantPositions = this.trackingToken?.let { tenancy?.positionsIn(it) },
    )

    private fun Any.getField(name: String) =
            this::class.java.declaredFields.firstOrNull { it.name == name } ?: throw NoSuchFieldException(name)

}
