/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.api

/**
 * Custom RSocket composite-metadata MIME type used to flag a payload's data as compressed with a specific codec.
 * The metadata content is a single byte equal to the [CompressionCodec.id] used.
 */
const val COMPRESSION_METADATA_MIME: String = "application/vnd.axoniq.platform.compression.v0"

/**
 * Compression codecs supported on the Axoniq Console wire protocol.
 *
 * The codec is identified on the wire by [id]; the [wireName] is the lowercase token used in
 * [ClientSettingsV2.supportedCompressionCodecs] for capability advertisement.
 */
enum class CompressionCodec(val id: Byte, val wireName: String) {
    GZIP(1, "gzip");

    companion object {
        fun fromId(id: Byte): CompressionCodec? = entries.firstOrNull { it.id == id }
        fun fromWireName(name: String): CompressionCodec? = entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }
}
