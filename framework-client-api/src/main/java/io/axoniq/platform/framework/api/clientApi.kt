/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.api

@Deprecated("Use ClientSettingsV2 instead")
data class ClientSettings(
        val heartbeatInterval: Long,
        val heartbeatTimeout: Long,
        val processorReportInterval: Long,
        val handlerReportInterval: Long,
)

/**
 * Unfortunately the original [ClientSettings] was not backwards compatible :(
 * Unrecognized field "applicationReportInterval" (class io.axoniq.console.framework.api.ClientSettings), not marked as ignorable (4 known properties: "heartbeatInterval", "processorReportInterval", "heartbeatTimeout", "handlerReportInterval"])
 * Made a new version of the class, and will adjust future serializer versions to be lenient
 */
data class ClientSettingsV2(
        val heartbeatInterval: Long,
        val heartbeatTimeout: Long,
        val processorReportInterval: Long,
        val handlerReportInterval: Long,
        val applicationReportInterval: Long,
        /**
         * Codecs the server can decode on inbound frames and produce on outbound. Null when the server is
         * older and predates compression negotiation; clients must treat null as "no compression".
         * Values are [CompressionCodec.wireName] tokens, e.g. "gzip".
         */
        val supportedCompressionCodecs: List<String>? = null,
)