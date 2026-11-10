/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.workflow

import org.axonframework.common.configuration.AxonConfiguration
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer
import org.axonframework.eventsourcing.eventstore.AppendCondition
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import org.axonframework.eventsourcing.eventstore.GenericTaggedEventMessage
import org.axonframework.eventsourcing.eventstore.GlobalIndexPosition
import org.axonframework.eventsourcing.eventstore.SourcingCondition
import org.axonframework.eventsourcing.eventstore.TaggedEventMessage
import org.axonframework.messaging.core.MessageStream
import org.axonframework.messaging.core.MessageType
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.axonframework.messaging.eventhandling.EventMessage
import org.axonframework.messaging.eventhandling.GenericEventMessage
import org.axonframework.messaging.eventstreaming.EventCriteria
import org.axonframework.messaging.eventstreaming.Tag
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What an [IncrementalSource] reads next, on each event storage engine. Every engine runs the same scenarios, so a
 * source that restarts from a stored position, as a node does from the snapshot, is checked against each of them.
 */
abstract class IncrementalSourceBoundaryContract {

    protected abstract fun configure(): AxonConfiguration

    protected lateinit var configuration: AxonConfiguration
    private val tag = Tag.of("incrementalSourceTest", UUID.randomUUID().toString())

    @BeforeEach
    fun startConfiguration() {
        configuration = configure()
    }

    @AfterEach
    fun shutdownConfiguration() {
        configuration.shutdown()
    }

    private fun source() = IncrementalSource(configuration, EventCriteria.havingTags(tag))

    /** Appends as the event store does, committing and then finishing the commit, which on PostgreSQL gives the index. */
    private fun append(count: Int): List<String> {
        val events = List(count) { GenericEventMessage(MessageType("IncrementalSourceTestEvent"), "event-$it") }
        configuration.getComponent(EventStorageEngine::class.java)
                .appendEvents(AppendCondition.none(), null, events.map<GenericEventMessage, TaggedEventMessage<*>> { GenericTaggedEventMessage(it, setOf(tag)) })
                .thenCompose { commit(it) }
                .get(30, TimeUnit.SECONDS)
        return events.map { it.identifier() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun commit(transaction: EventStorageEngine.AppendTransaction<*>) =
            (transaction as EventStorageEngine.AppendTransaction<Any?>).let { tx -> tx.commit().thenCompose { tx.afterCommit(it) } }

    private fun IncrementalSource.readIdentifiers(): List<String> = mutableListOf<String>().also { read -> read { read += it.identifier() } }

    @Test
    fun `the first read returns every matching event`() {
        val appended = append(count = 2)

        assertEquals(appended, source().readIdentifiers())
    }

    @Test
    fun `a read right after the previous one returns nothing`() {
        append(count = 2)
        val source = source().also { it.readIdentifiers() }

        assertEquals(emptyList<String>(), source.readIdentifiers())
    }

    @Test
    fun `a read returns the events appended since the previous one`() {
        append(count = 1)
        val source = source().also { it.readIdentifiers() }

        val appended = append(count = 2)

        assertEquals(appended, source.readIdentifiers())
    }

    @Test
    fun `a source that starts from a stored position continues after the last event read`() {
        append(count = 2)
        val stored = source().also { it.readIdentifiers() }.position
        val appended = append(count = 1)

        val restarted = source().also { it.position = stored }

        assertEquals(appended, restarted.readIdentifiers())
    }
}

class IncrementalSourceBoundaryTest {

    @Nested
    inner class InMemory : IncrementalSourceBoundaryContract() {
        override fun configure(): AxonConfiguration = EventSourcingConfigurer.create().start()
    }

    /** An engine that also returns the event at the position it sources from. */
    @Nested
    inner class InclusiveEngine : IncrementalSourceBoundaryContract() {
        override fun configure(): AxonConfiguration = EventSourcingConfigurer.create()
                .componentRegistry { registry ->
                    registry.registerDecorator(EventStorageEngine::class.java, 0) { _, _, delegate: EventStorageEngine ->
                        ControllableEngine(delegate).apply { inclusive = true }
                    }
                }
                .start()
    }
}

/** An engine that can hold its sourcing until released, or source from the event at the position as well. */
internal class ControllableEngine(private val delegate: EventStorageEngine) : EventStorageEngine by delegate {
    @Volatile var inclusive = false
    @Volatile private var hold: CountDownLatch? = null
    val sources = AtomicInteger()

    fun hold() {
        sources.set(0)
        hold = CountDownLatch(1)
    }

    fun release() {
        hold?.countDown()
        hold = null
    }

    override fun source(condition: SourcingCondition, context: ProcessingContext?): MessageStream<EventMessage> {
        hold?.let {
            sources.incrementAndGet()
            it.await(30, TimeUnit.SECONDS)
        }
        val start = condition.start()
        if (inclusive && start is GlobalIndexPosition) {
            val from = GlobalIndexPosition(GlobalIndexPosition.toIndex(start) - 1)
            return delegate.source(SourcingCondition.conditionFor(from, condition.criteria()), context)
        }
        return delegate.source(condition, context)
    }
}
