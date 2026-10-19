/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.messaging

import java.util.concurrent.ConcurrentHashMap

const val BUCKET_SIZE = 1000L

/**
 * Keeps a statistic on the amount of messages received in the last 20 seconds, then tripled to
 * get the message rate per minute.
 */
class RollingCountMeasure {
    private val countMap = ConcurrentHashMap<Long, Int>()

    fun increment() {
        countMap.compute(currentBucket()) { _, v -> (v ?: 0) + 1 }
    }

    fun count(): Double {
        val bucket = currentBucket()
        val minimumBucket = (bucket - 11)
        val toRemove = countMap.keys.filter { it < minimumBucket }
        toRemove.forEach { countMap.remove(it) }

        return countMap.filter { it.key in minimumBucket until bucket }.values.sum().toDouble() * 6
    }

    private fun currentBucket(): Long {
        return System.currentTimeMillis() / BUCKET_SIZE
    }
}
