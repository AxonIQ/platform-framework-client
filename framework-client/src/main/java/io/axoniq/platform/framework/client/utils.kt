/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.client

import io.axoniq.platform.framework.api.COMPRESSION_METADATA_MIME
import io.axoniq.platform.framework.api.CompressionCodec
import io.axoniq.platform.framework.api.PlatformClientAuthentication
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.CompositeByteBuf
import io.rsocket.metadata.CompositeMetadata
import io.rsocket.metadata.CompositeMetadataCodec
import io.rsocket.metadata.TaggingMetadataCodec
import io.rsocket.metadata.WellKnownMimeType


fun CompositeByteBuf.addRouteMetadata(route: String) {
    val routingMetadata = TaggingMetadataCodec.createRoutingMetadata(ByteBufAllocator.DEFAULT, listOf(route))
    CompositeMetadataCodec.encodeAndAddMetadata(
        this,
        ByteBufAllocator.DEFAULT,
        WellKnownMimeType.MESSAGE_RSOCKET_ROUTING,
        routingMetadata.content
    )
}

fun CompositeByteBuf.addAuthMetadata(auth: PlatformClientAuthentication) {
    val authMetadata = ByteBufAllocator.DEFAULT.compositeBuffer()
    authMetadata.writeBytes(auth.toBearerToken().toByteArray())
    CompositeMetadataCodec.encodeAndAddMetadata(
        this,
        ByteBufAllocator.DEFAULT,
        WellKnownMimeType.MESSAGE_RSOCKET_AUTHENTICATION,
        authMetadata
    )
}

/**
 * Appends a compression-flag entry to this composite metadata. The peer will read [codec] as a single byte
 * and decompress the data buffer accordingly.
 */
fun CompositeByteBuf.addCompressionMetadata(codec: CompressionCodec) {
    val content = ByteBufAllocator.DEFAULT.buffer(1).writeByte(codec.id.toInt())
    CompositeMetadataCodec.encodeAndAddMetadata(
        this,
        ByteBufAllocator.DEFAULT,
        COMPRESSION_METADATA_MIME,
        content
    )
}

/**
 * Reads the compression flag from RSocket composite metadata, if present. Returns null when the peer did not
 * compress this frame or when the metadata buffer is null.
 */
fun ByteBuf?.readCompressionCodec(): CompressionCodec? {
    if (this == null || this.readableBytes() == 0) return null
    val composite = CompositeMetadata(this, false)
    for (entry in composite) {
        if (entry.mimeType == COMPRESSION_METADATA_MIME) {
            val content = entry.content
            if (content.readableBytes() < 1) return null
            return CompressionCodec.fromId(content.getByte(content.readerIndex()))
        }
    }
    return null
}
