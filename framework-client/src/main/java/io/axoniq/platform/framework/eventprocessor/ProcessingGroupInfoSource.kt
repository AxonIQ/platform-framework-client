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
