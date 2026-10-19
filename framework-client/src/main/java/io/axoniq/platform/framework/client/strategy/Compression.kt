/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.client.strategy

import io.axoniq.platform.framework.api.CompressionCodec
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal object Compression {
    fun compress(codec: CompressionCodec, data: ByteArray): ByteArray = when (codec) {
        CompressionCodec.GZIP -> gzip(data)
    }

    fun decompress(codec: CompressionCodec, data: ByteArray): ByteArray = when (codec) {
        CompressionCodec.GZIP -> gunzip(data)
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 2)
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray {
        return GZIPInputStream(data.inputStream()).use { it.readBytes() }
    }
}
