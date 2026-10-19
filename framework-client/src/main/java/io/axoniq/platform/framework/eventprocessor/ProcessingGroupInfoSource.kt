/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.eventprocessor

/**
 * Always-loadable seam used by [ProcessorReportCreator] to learn about processing groups (and their DLQ size, if
 * any) belonging to a processor.
 *
 * Carrying this contract in a class with no references to the optional `axoniq-dead-letter` types lets
 * [ProcessorReportCreator] stay free of those types so it can run on classpaths where the addon is absent.
 */
interface ProcessingGroupInfoSource {

    fun infoFor(processorName: String): List<ProcessingGroupInfo>

    data class ProcessingGroupInfo(
            val processingGroup: String,
            val dlqSize: Long?,
    )
}
