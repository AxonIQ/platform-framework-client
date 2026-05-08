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
