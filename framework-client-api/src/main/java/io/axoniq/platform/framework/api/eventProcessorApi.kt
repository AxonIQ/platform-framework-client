/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.api

import java.time.Instant

data class ProcessorStatusReport(
        val processors: List<ProcessorStatus>
)

enum class ProcessorMode {
    POOLED,
    TRACKING,
    SUBSCRIBING,
    UNKNOWN,
}

data class ProcessorStatus(
        val name: String,
        val processingGroups: List<ProcessingGroupStatus>,
        val tokenStoreIdentifier: String,
        val mode: ProcessorMode,
        val started: Boolean,
        val error: Boolean,
        val segmentCapacity: Int,
        val activeSegments: Int,
        val segments: List<SegmentStatus>,
)

data class ProcessingGroupStatus(
        val name: String,
        val dlqSize: Long?,
)

data class SegmentStatus(
        val segment: Int,
        val mergeableSegment: Int,
        val mask: Int = -1,
        val oneOf: Int,
        val caughtUp: Boolean,
        val error: Boolean,
        val errorType: String?,
        val errorMessage: String?,
        val ingestLatency: Double?,
        val commitLatency: Double?,
        val processingLatency: Double?,
        val position: Long? = -1,
        val resetPosition: Long? = -1,
)

data class ProcessorSegmentId(
        val processorName: String,
        val segmentId: Int
)

enum class ResetDecisions {
    HEAD, TAIL, FROM
}

data class ResetDecision(
        val processorName: String,
        val decision: ResetDecisions,
        val from: Instant? = null
)

data class SegmentOverview(
        val segments: List<SegmentDetails>
)

data class SegmentDetails(

        val segment: Int,
        val mergeableSegment: Int,
        val mask: Int,
)