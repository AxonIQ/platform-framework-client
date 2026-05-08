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

package io.axoniq.platform.framework.client.strategy

import io.axoniq.platform.framework.api.ClientSettingsV2
import io.axoniq.platform.framework.api.ClientStatus
import io.axoniq.platform.framework.api.CompressionCodec
import io.axoniq.platform.framework.client.PlatformClientConnectionObserver
import io.axoniq.platform.framework.client.PlatformClientConnectionService
import io.axoniq.platform.framework.client.addCompressionMetadata
import io.axoniq.platform.framework.client.readCompressionCodec
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.CompositeByteBuf
import io.rsocket.Payload
import io.rsocket.metadata.WellKnownMimeType
import io.rsocket.util.DefaultPayload
import java.util.concurrent.atomic.AtomicReference

/**
 * Decorator [RSocketPayloadEncodingStrategy] that gzips outbound payloads above a fixed size threshold and
 * transparently decompresses inbound frames flagged with the compression metadata entry. Mime type, payload
 * shape, and the rest of the encoding contract delegate straight through to [delegate].
 *
 * The decorator subscribes itself to [PlatformClientConnectionService] so the active codec follows the
 * server's settings handshake — a server that doesn't advertise compression keeps the connection in raw mode.
 */
class CompressingEncodingStrategy(
        private val delegate: RSocketPayloadEncodingStrategy,
        platformClientConnectionService: PlatformClientConnectionService,
) : RSocketPayloadEncodingStrategy, PlatformClientConnectionObserver {

    init {
        platformClientConnectionService.subscribeToSettings(this)
    }

    private val outboundCodec = AtomicReference<CompressionCodec?>(null)

    override fun getMimeType(): WellKnownMimeType = delegate.getMimeType()

    fun enableCompression(codec: CompressionCodec) = outboundCodec.set(codec)

    fun disableCompression() = outboundCodec.set(null)

    /**
     * Picks the first wire-name from [ClientSettingsV2.supportedCompressionCodecs] that this client knows
     * how to speak and enables it; if the server advertises nothing (older server) compression stays off.
     */
    override fun onConnected(clientStatus: ClientStatus, settings: ClientSettingsV2) {
        val codec = settings.supportedCompressionCodecs
                ?.asSequence()
                ?.mapNotNull { CompressionCodec.fromWireName(it) }
                ?.firstOrNull()
        if (codec != null) enableCompression(codec) else disableCompression()
    }

    override fun onDisconnected() = disableCompression()

    override fun encode(payload: Any, metadata: ByteBuf?): Payload {
        val inner = delegate.encode(payload, metadata)
        val codec = outboundCodec.get() ?: return inner
        if (inner.data.remaining() < COMPRESSION_THRESHOLD) return inner

        val raw = ByteArray(inner.data.remaining()).also { inner.data.duplicate().get(it) }
        val compressed = Compression.compress(codec, raw)
        if (compressed.size >= raw.size) return inner

        val newData = ByteBufAllocator.DEFAULT.buffer(compressed.size).writeBytes(compressed)
        val newMetadata = appendCompressionFlag(inner.sliceMetadata(), codec)
        return DefaultPayload.create(newData, newMetadata)
    }

    override fun <T> decode(payload: Payload, expectedType: Class<T>): T {
        val codec = payload.sliceMetadata().readCompressionCodec()
                ?: return delegate.decode(payload, expectedType)

        val raw = ByteArray(payload.data.remaining()).also { payload.data.duplicate().get(it) }
        val decompressed = Compression.decompress(codec, raw)
        val rebuilt = DefaultPayload.create(
                ByteBufAllocator.DEFAULT.buffer(decompressed.size).writeBytes(decompressed),
                payload.sliceMetadata().copy()
        )
        return delegate.decode(rebuilt, expectedType)
    }

    private fun appendCompressionFlag(existing: ByteBuf, codec: CompressionCodec): CompositeByteBuf {
        val out = ByteBufAllocator.DEFAULT.compositeBuffer()
        if (existing.readableBytes() > 0) out.addComponent(true, existing.copy())
        out.addCompressionMetadata(codec)
        return out
    }

    companion object {
        const val COMPRESSION_THRESHOLD: Int = 256
    }
}
