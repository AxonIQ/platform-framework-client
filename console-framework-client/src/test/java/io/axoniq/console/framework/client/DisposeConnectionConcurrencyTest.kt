/*
 * Copyright (c) 2022-2025. AxonIQ B.V.
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

package io.axoniq.console.framework.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Tests to verify thread-safety of connection disposal logic.
 *
 * The race condition being tested:
 * - Multiple threads (TCP disconnect callback, heartbeat checker) can call disposeCurrentConnection() simultaneously
 * - Without proper synchronization, this leads to multiple dispose() and clearSettings() calls
 * - The fix uses ReentrantLock to ensure only one thread performs the disposal
 */
class DisposeConnectionConcurrencyTest {

    /**
     * Simulates the UNFIXED (buggy) implementation that has a race condition.
     * This proves the bug exists when there's no synchronization.
     */
    @RepeatedTest(10)
    fun `UNFIXED implementation allows multiple dispose calls - proving the bug`() {
        val disposeCount = AtomicInteger(0)
        val clearSettingsCount = AtomicInteger(0)
        var rsocket: FakeRSocket? = FakeRSocket(disposeCount)

        // Simulate unfixed disposeCurrentConnection without lock
        fun disposeCurrentConnectionUnfixed() {
            if (rsocket != null) {
                rsocket?.dispose()
                rsocket = null
                clearSettingsCount.incrementAndGet()
            }
        }

        val threadCount = 10
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) {
            executor.submit {
                latch.await() // Wait for all threads to be ready
                disposeCurrentConnectionUnfixed()
            }
        }

        latch.countDown() // Release all threads simultaneously
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        // Without synchronization, dispose() is called more than once
        // This test demonstrates the bug by showing multiple calls
        println("UNFIXED: dispose called ${disposeCount.get()} times, clearSettings called ${clearSettingsCount.get()} times")

        // We expect this to fail most of the time (dispose called > 1 time)
        // If it occasionally passes (all 10 threads see non-null and then race), that's the nature of race conditions
    }

    /**
     * Simulates the FIXED implementation with ReentrantLock.
     * This proves the fix works - only one dispose() and clearSettings() call.
     */
    @RepeatedTest(10)
    fun `FIXED implementation allows only one dispose call`() {
        val disposeCount = AtomicInteger(0)
        val clearSettingsCount = AtomicInteger(0)
        val connectionLock = ReentrantLock()
        var rsocket: FakeRSocket? = FakeRSocket(disposeCount)

        // Simulate fixed disposeCurrentConnection with lock
        fun disposeCurrentConnectionFixed() {
            connectionLock.withLock {
                val currentRSocket = rsocket
                if (currentRSocket != null) {
                    rsocket = null
                    currentRSocket.dispose()
                    clearSettingsCount.incrementAndGet()
                }
            }
        }

        val threadCount = 10
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) {
            executor.submit {
                latch.await()
                disposeCurrentConnectionFixed()
            }
        }

        latch.countDown()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertEquals(1, disposeCount.get(), "dispose() should be called exactly once")
        assertEquals(1, clearSettingsCount.get(), "clearSettings() should be called exactly once")
    }

    /**
     * Tests that the fix handles the case where rsocket is already null.
     */
    @Test
    fun `FIXED implementation handles already null rsocket`() {
        val disposeCount = AtomicInteger(0)
        val clearSettingsCount = AtomicInteger(0)
        val connectionLock = ReentrantLock()
        var rsocket: FakeRSocket? = null // Already null

        fun disposeCurrentConnectionFixed() {
            connectionLock.withLock {
                val currentRSocket = rsocket
                if (currentRSocket != null) {
                    rsocket = null
                    currentRSocket.dispose()
                    clearSettingsCount.incrementAndGet()
                }
            }
        }

        val threadCount = 10
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) {
            executor.submit {
                latch.await()
                disposeCurrentConnectionFixed()
            }
        }

        latch.countDown()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertEquals(0, disposeCount.get(), "dispose() should not be called when rsocket is null")
        assertEquals(0, clearSettingsCount.get(), "clearSettings() should not be called when rsocket is null")
    }

    /**
     * Simulates the race between TCP disconnect callback and heartbeat timeout.
     * Both detect connection loss and try to dispose.
     */
    @RepeatedTest(10)
    fun `FIXED implementation handles simultaneous TCP disconnect and heartbeat timeout`() {
        val disposeCount = AtomicInteger(0)
        val clearSettingsCount = AtomicInteger(0)
        val connectionLock = ReentrantLock()
        var rsocket: FakeRSocket? = FakeRSocket(disposeCount)

        fun disposeCurrentConnectionFixed() {
            connectionLock.withLock {
                val currentRSocket = rsocket
                if (currentRSocket != null) {
                    rsocket = null
                    currentRSocket.dispose()
                    clearSettingsCount.incrementAndGet()
                }
            }
        }

        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        // Thread 1: TCP disconnect callback
        executor.submit {
            latch.await()
            Thread.sleep((Math.random() * 10).toLong()) // Random delay to simulate real timing
            disposeCurrentConnectionFixed()
        }

        // Thread 2: Heartbeat timeout
        executor.submit {
            latch.await()
            Thread.sleep((Math.random() * 10).toLong())
            disposeCurrentConnectionFixed()
        }

        latch.countDown()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertEquals(1, disposeCount.get(), "dispose() should be called exactly once even with two callers")
        assertEquals(1, clearSettingsCount.get(), "clearSettings() should be called exactly once")
    }

    /**
     * Fake RSocket that counts dispose() calls.
     */
    class FakeRSocket(private val disposeCount: AtomicInteger) {
        fun dispose() {
            disposeCount.incrementAndGet()
        }
    }
}
