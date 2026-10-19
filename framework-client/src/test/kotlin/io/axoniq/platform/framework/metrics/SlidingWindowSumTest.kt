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

import io.micrometer.core.instrument.MockClock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.test.assertEquals

class SlidingWindowSumTest {

    private val clock = MockClock()
    private val sum = SlidingWindowSum(Duration.ofMinutes(1), 60, clock)

    @Test
    fun `is zero before anything is added`() {
        assertEquals(0, sum.sum())
    }

    @Test
    fun `leaves out values until their bucket has elapsed`() {
        sum.add(10)
        assertEquals(0, sum.sum())

        clock.add(Duration.ofSeconds(1))
        assertEquals(10, sum.sum())
    }

    @Test
    fun `sums the values added within the window`() {
        sum.add(10)
        clock.add(Duration.ofSeconds(20))
        sum.add(20)
        clock.add(Duration.ofSeconds(30))
        sum.add(30)
        clock.add(Duration.ofSeconds(1))

        assertEquals(60, sum.sum())
    }

    @Test
    fun `sums values added within the same bucket`() {
        sum.add(10)
        sum.add(15)
        clock.add(Duration.ofSeconds(1))

        assertEquals(25, sum.sum())
    }

    @Test
    fun `keeps a value for a full window after its bucket elapsed`() {
        sum.add(10)
        clock.add(Duration.ofSeconds(30))
        sum.add(20)

        clock.add(Duration.ofSeconds(30))
        assertEquals(30, sum.sum())

        clock.add(Duration.ofSeconds(1))
        assertEquals(20, sum.sum())

        clock.add(Duration.ofSeconds(30))
        assertEquals(0, sum.sum())
    }

    @Test
    fun `does not carry values over when a slot is reused`() {
        sum.add(10)
        clock.add(Duration.ofSeconds(61))
        sum.add(20)
        clock.add(Duration.ofSeconds(1))

        assertEquals(20, sum.sum())
    }

    @Test
    fun `is zero after being idle for several windows`() {
        sum.add(10)
        clock.add(Duration.ofMinutes(5))

        assertEquals(0, sum.sum())
    }

    @Test
    fun `rejects a window too short for its bucket count`() {
        assertThrows<IllegalArgumentException> {
            SlidingWindowSum(Duration.ofNanos(10), 60, clock)
        }
    }
}
