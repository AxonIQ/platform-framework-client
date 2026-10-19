/*
 * Copyright (c) 2026. Axoniq B.V.
 *
 * Licensed under the AXONIQ TERMS OF SERVICE,
 * Version 29 April 2026 (the "License");
 *
 * The software is available for evaluation use without registration.
 * Continued use beyond the evaluation period requires registration
 * and a commercial license. See the License for the specific language
 * governing permissions and limitations under the License.
 * You may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at:
 *  https://www.axoniq.io/legal/terms-of-service
 *
 * For licensing information and to register, visit:
 *  https://www.axoniq.io/pricing
 */

package io.axoniq.platform.framework.metrics

import io.micrometer.core.instrument.Clock
import java.time.Duration

/**
 * Keeps the sum of the values added during a sliding window of time, such as the time a thread pool spent busy
 * during the last minute.
 *
 * The window is split into [bucketCount] buckets of equal length, so memory use stays fixed however many values are
 * added. [sum] covers the last [bucketCount] completed buckets, which always span exactly the full window. A value
 * therefore only shows up in [sum] once the bucket it was added to has elapsed.
 */
class SlidingWindowSum(
        window: Duration,
        private val bucketCount: Int,
        private val clock: Clock,
) {
    private val bucketNanos = window.toNanos() / bucketCount

    // One extra slot holds the bucket that is still filling up, outside the window that sum() reports on.
    private val slotCount = bucketCount + 1
    private val sums = LongArray(slotCount)
    private val bucketIds = LongArray(slotCount) { Long.MIN_VALUE }

    init {
        require(bucketCount > 0 && bucketNanos > 0) {
            "The window of $window cannot be split into $bucketCount buckets"
        }
    }

    @Synchronized
    fun add(value: Long) {
        val bucketId = currentBucketId()
        val slot = slotOf(bucketId)
        if (bucketIds[slot] != bucketId) {
            bucketIds[slot] = bucketId
            sums[slot] = 0
        }
        sums[slot] += value
    }

    @Synchronized
    fun sum(): Long {
        val currentBucketId = currentBucketId()
        val oldestBucketInWindow = currentBucketId - bucketCount
        var total = 0L
        for (slot in 0 until slotCount) {
            if (bucketIds[slot] in oldestBucketInWindow until currentBucketId) {
                total += sums[slot]
            }
        }
        return total
    }

    private fun currentBucketId() = Math.floorDiv(clock.monotonicTime(), bucketNanos)

    private fun slotOf(bucketId: Long) = Math.floorMod(bucketId, slotCount)
}
