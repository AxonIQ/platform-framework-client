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

import io.axoniq.platform.framework.api.COMPRESSION_METADATA_MIME
import io.axoniq.platform.framework.api.CompressionCodec
import io.axoniq.platform.framework.client.PlatformClientConnectionService
import io.axoniq.platform.framework.client.addRouteMetadata
import io.netty.buffer.ByteBufAllocator
import io.rsocket.metadata.CompositeMetadata
import io.rsocket.util.DefaultPayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.dataformat.cbor.CBORMapper

class CompressingEncodingStrategyTest {

    private val strategy = CompressingEncodingStrategy(
            delegate = CborJackson3EncodingStrategy(),
            platformClientConnectionService = PlatformClientConnectionService(),
    )

    private data class Sample(val a: String, val b: List<String>)

    @Test
    fun `encoded payload is not compressed when codec is not enabled`() {
        val payload = strategy.encode(largeSample(), routingMetadata())

        assertFalse(compressionEntryPresent(payload.sliceMetadata()))
        // Round-trip works without enabling compression.
        val decoded = strategy.decode(payload, Sample::class.java)
        assertEquals(largeSample(), decoded)
    }

    @Test
    fun `small payload is not compressed even when codec is enabled`() {
        strategy.enableCompression(CompressionCodec.GZIP)
        val payload = strategy.encode(Sample("a", listOf("b")), routingMetadata())

        assertFalse(compressionEntryPresent(payload.sliceMetadata()))
    }

    @Test
    fun `large payload is compressed when codec is enabled and shrunk on the wire`() {
        strategy.enableCompression(CompressionCodec.GZIP)
        val sample = largeSample()

        val rawSize = DefaultPayload.create(
                CBORMapper.builder().build().writeValueAsBytes(sample)
        ).data.remaining()

        val payload = strategy.encode(sample, routingMetadata())

        assertTrue(compressionEntryPresent(payload.sliceMetadata()))
        assertTrue(payload.data.remaining() < rawSize, "compressed=${payload.data.remaining()} raw=$rawSize")
    }

    @Test
    fun `compressed payload round-trips through encode then decode`() {
        strategy.enableCompression(CompressionCodec.GZIP)
        val sample = largeSample()

        val payload = strategy.encode(sample, routingMetadata())
        val decoded = strategy.decode(payload, Sample::class.java)

        assertEquals(sample, decoded)
    }

    @Test
    fun `disableCompression turns off outbound but inbound still decompresses`() {
        strategy.enableCompression(CompressionCodec.GZIP)
        val compressedPayload = strategy.encode(largeSample(), routingMetadata())
        assertTrue(compressionEntryPresent(compressedPayload.sliceMetadata()))

        strategy.disableCompression()
        val plainPayload = strategy.encode(largeSample(), routingMetadata())
        assertFalse(compressionEntryPresent(plainPayload.sliceMetadata()))

        // Even with outbound off, inbound flagged frames are still decoded.
        val decoded = strategy.decode(compressedPayload, Sample::class.java)
        assertEquals(largeSample(), decoded)
    }

    private fun largeSample(): Sample {
        // Repetitive structure so gzip beats CBOR comfortably above the 256-byte threshold.
        val tokens = List(50) { "handler-name-$it-with-padding" }
        return Sample("the-route-key-is-quite-verbose", tokens)
    }

    private fun routingMetadata() = ByteBufAllocator.DEFAULT.compositeBuffer().also {
        it.addRouteMetadata("test-route")
    }

    private fun compressionEntryPresent(metadata: io.netty.buffer.ByteBuf?): Boolean {
        if (metadata == null || metadata.readableBytes() == 0) return false
        for (entry in CompositeMetadata(metadata, false)) {
            if (entry.mimeType == COMPRESSION_METADATA_MIME) return true
        }
        return false
    }
}
