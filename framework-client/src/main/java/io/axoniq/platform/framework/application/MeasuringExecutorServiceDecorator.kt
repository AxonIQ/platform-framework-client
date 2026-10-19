/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.application

import io.axoniq.platform.framework.metrics.SlidingWindowSum
import io.micrometer.core.instrument.Clock
import org.axonframework.common.util.PriorityRunnable
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor

/**
 * Measures an ExecutorService for a bus in Axon Framework. Extracts the current capacity, max capacity and
 * queue timer of tasks.
 */
class MeasuringExecutorServiceDecorator(
        private val busType: BusType,
        private val delegate: ExecutorService,
        private val applicationMetricRegistry: ApplicationMetricRegistry
) : ExecutorService by delegate {
    private val clock = Clock.SYSTEM
    private val busyTime = SlidingWindowSum(WINDOW, 60, clock)

    init {
        applicationMetricRegistry.registerWorkQueueDecorator(busType, this)
    }

    override fun execute(command: Runnable) {
        if (command !is PriorityRunnable) {
            delegate.execute(command)
            return
        }
        val instrumentedRunnable = PriorityRunnable({
            val start: Long = clock.monotonicTime()
            try {
                command.run()
            } finally {
                busyTime.add(clock.monotonicTime() - start)
            }
        }, command.priority(), command.sequence())
        return delegate.execute(instrumentedRunnable)
    }

    fun getMaxCapacity(): Int {
        if (delegate is ThreadPoolExecutor) {
            return delegate.maximumPoolSize
        }

        return 1
    }

    fun getUsedCapacity(): Double {
        return busyTime.sum().toDouble() / WINDOW.toNanos().toDouble()
    }

    private companion object {
        val WINDOW: Duration = Duration.ofMinutes(1)
    }
}