/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.eventprocessor

import io.axoniq.platform.framework.AxoniqPlatformConfiguration
import io.github.oshai.kotlinlogging.KotlinLogging
import org.axonframework.common.ReflectionUtils
import org.axonframework.common.configuration.Configuration
import org.axonframework.messaging.core.MessageStream
import org.axonframework.messaging.eventhandling.processing.EventProcessor
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Remembers, per processor, when it was last at the head of its event stream: no event waiting on the stream, and
 * nothing left in its work packages. This holds whether or not it has handlers for the events it read on the way.
 *
 * Handler latency cannot tell this: a processor that skips events without a handler measures nothing, the same as an
 * idle one. And the `caughtUp` flag of the framework never goes back to `false` once set.
 *
 * The stream is private to the framework's `Coordinator`, so it is read with reflection. The client is built against
 * one framework minor version, which keeps the field names stable.
 */
class ProcessorStreamSampler(
        private val configuration: Configuration,
        properties: AxoniqPlatformConfiguration,
) {
    companion object {
        private const val SAMPLE_INTERVAL_MS = 1000L
    }

    private val logger = KotlinLogging.logger { }
    private val executor = properties.reportingTaskExecutor
    private val lastCaughtUpAt: MutableMap<String, Instant> = ConcurrentHashMap()
    private var sampleTask: ScheduledFuture<*>? = null

    @Volatile
    private var reflectionFailed = false

    fun start() {
        sampleTask = executor.scheduleWithFixedDelay({ sample() }, 0, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        sampleTask?.cancel(false)
        sampleTask = null
    }

    /**
     * When the stream of the processor last had no event waiting, or `null` when that was never seen.
     */
    fun lastCaughtUpAt(processorName: String): Instant? = lastCaughtUpAt[processorName]

    fun sample() {
        if (reflectionFailed) {
            return
        }
        configuration.getComponents(EventProcessor::class.java).values.filterIsInstance<PooledStreamingEventProcessor>().forEach { processor ->
            try {
                if (processor.isAtHead() == true) {
                    lastCaughtUpAt[processor.name()] = Instant.now()
                }
            } catch (e: ReflectiveOperationException) {
                reflectionFailed = true
                logger.warn(e) { "Cannot read the event stream of processor [${processor.name()}]. Stream lag will not be reported." }
            }
        }
    }

    /**
     * The rule the coordinator uses for its own `caughtUp` flag: no event waits on the stream, and every work package
     * handled what it was given. The stream alone is not enough, because it applies the event criteria itself: an
     * event stuck in a handler can be the last one the stream delivers. `null` while the processor has no stream
     * open, because it is stopped or has no claimed segments.
     */
    private fun PooledStreamingEventProcessor.isAtHead(): Boolean? {
        val coordinator = field(PooledStreamingEventProcessor::class.java, "coordinator").get(this) ?: return null
        val taskRef = field(coordinator.javaClass, "coordinationTask").get(coordinator) as AtomicReference<*>
        val task = taskRef.get() ?: return null
        val stream = field(task.javaClass, "eventStream").get(task) as MessageStream<*>? ?: return null
        return !stream.hasNextAvailable() && method(task.javaClass, "isDone").invoke(task) as Boolean
    }

    private val fields: MutableMap<Pair<Class<*>, String>, Field> = ConcurrentHashMap()
    private val methods: MutableMap<Pair<Class<*>, String>, Method> = ConcurrentHashMap()

    private fun field(type: Class<*>, name: String): Field = fields.getOrPut(type to name) {
        ReflectionUtils.ensureAccessible(type.getDeclaredField(name))
    }

    private fun method(type: Class<*>, name: String): Method = methods.getOrPut(type to name) {
        ReflectionUtils.ensureAccessible(type.getDeclaredMethod(name))
    }
}
