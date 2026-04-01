package io.axoniq.console.framework.client.strategy

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.CompositeByteBuf
import io.rsocket.Payload
import io.rsocket.metadata.WellKnownMimeType
import io.rsocket.util.DefaultPayload
import tools.jackson.databind.DeserializationFeature
import tools.jackson.dataformat.cbor.CBORMapper

class CborJackson3EncodingStrategy : RSocketPayloadEncodingStrategy {
    private val mapper = CBORMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()

    override fun getMimeType(): WellKnownMimeType {
        return WellKnownMimeType.APPLICATION_CBOR
    }

    override fun encode(payload: Any, metadata: ByteBuf?): Payload {
        val payloadBuffer: CompositeByteBuf = ByteBufAllocator.DEFAULT.compositeBuffer()
        payloadBuffer.writeBytes(mapper.writeValueAsBytes(payload))
        return DefaultPayload.create(payloadBuffer, metadata)
    }

    override fun <T> decode(payload: Payload, expectedType: Class<T>): T {
        if (expectedType == String::class.java) {
            return payload.dataUtf8 as T
        }

        return mapper.readValue(payload.data.array(), expectedType)
    }
}
