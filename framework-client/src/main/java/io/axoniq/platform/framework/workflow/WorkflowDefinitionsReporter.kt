/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.workflow

import io.axoniq.framework.workflow.runtime.api.execution.context.WorkflowConfiguration
import io.axoniq.framework.workflow.runtime.execution.WorkflowConfigurationRegistry
import io.axoniq.platform.framework.api.Routes
import io.axoniq.platform.framework.api.WorkflowDefinitionDescription
import io.axoniq.platform.framework.api.WorkflowDefinitionsResult
import io.axoniq.platform.framework.api.WorkflowGraph
import io.axoniq.platform.framework.api.ClientSettingsV2
import io.axoniq.platform.framework.api.ClientStatus
import io.axoniq.platform.framework.client.AxoniqConsoleRSocketClient
import io.axoniq.platform.framework.client.PlatformClientConnectionObserver
import io.axoniq.platform.framework.client.PlatformClientConnectionService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reports every workflow definition registered in the application, with the graph read from its code, on
 * [Routes.Workflow.DEFINITIONS_REPORT] once per connection, so Inspector knows them per node without asking. The
 * analysis runs in the background, so connecting does not wait for it. The graph of a definition is read once; a
 * definition's code does not change while the application runs.
 */
class WorkflowDefinitionsReporter(
        private val client: AxoniqConsoleRSocketClient,
        private val connectionService: PlatformClientConnectionService,
) : PlatformClientConnectionObserver {
    private val logger = KotlinLogging.logger { }
    private val registries = CopyOnWriteArraySet<WorkflowConfigurationRegistry<*>>()
    private val descriptions = ConcurrentHashMap<WorkflowConfiguration<*>, WorkflowDefinitionDescription>()
    private val reported = AtomicInteger(-1)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "axoniq-platform-workflow-definitions").apply { isDaemon = true } }

    fun registerRegistry(registry: WorkflowConfigurationRegistry<*>) {
        registries += registry
    }

    fun start() {
        connectionService.subscribeToSettings(this)
    }

    override fun onConnected(clientStatus: ClientStatus, settings: ClientSettingsV2) {
        val connection = client.connectionNumber()
        if (!clientStatus.enabled || reported.getAndSet(connection) == connection) return
        executor.execute {
            try {
                client.sendReport(Routes.Workflow.DEFINITIONS_REPORT, definitions())
                        .doOnError { e ->
                            reported.compareAndSet(connection, -1)
                            logger.debug { "Failed to report the workflow definitions: ${e.message}" }
                        }
                        .onErrorComplete()
                        .subscribe()
            } catch (e: Exception) {
                reported.compareAndSet(connection, -1)
                logger.debug(e) { "Could not report the workflow definitions" }
            }
        }
    }

    override fun onDisconnected() {
    }

    /** The names of the workflows this application defines, without reading their code. */
    fun definitionNames(): Set<String> = registries.flatMapTo(HashSet()) { registry ->
        registry.supportedEvents().flatMap { event -> registry.getWorkflowsConfigurations(event).map { it.configuration().workflowName() } }
    }

    fun definitions(): WorkflowDefinitionsResult {
        val startEvents = LinkedHashMap<WorkflowConfiguration<*>, MutableSet<String>>()
        registries.forEach { registry ->
            registry.supportedEvents().forEach { event ->
                registry.getWorkflowsConfigurations(event).forEach {
                    startEvents.getOrPut(it.configuration()) { linkedSetOf() } += event.toString()
                }
            }
        }
        return WorkflowDefinitionsResult(startEvents.map { (configuration, events) ->
            descriptions.computeIfAbsent(configuration) { describe(it, events.toList()) }
        })
    }

    private fun describe(configuration: WorkflowConfiguration<*>, startEvents: List<String>): WorkflowDefinitionDescription {
        val name = configuration.workflowName()
        val version = configuration.workflowVersion()
        val warnings = mutableListOf<String>()
        // Throwable, because a definition the analysis cannot handle must not take the other definitions down with it.
        val read = try {
            WorkflowDefinitionLocator.locate(configuration.workflowDefinition(), name, warnings)
                    ?.let { WorkflowDefinitionAnalyzer(it.classLoader).analyze(it.method) }
                    ?.let { result -> result to WorkflowBpmnWriter.write(name, version, result.graph) }
        } catch (e: Throwable) {
            logger.debug(e) { "Could not read the graph of workflow $name $version" }
            warnings += "Could not read the graph: ${e.message ?: e.javaClass.simpleName}"
            null
        }
        val graph = read?.first?.graph
        return WorkflowDefinitionDescription(
                name = name,
                version = version,
                startEvents = startEvents,
                graph = graph,
                bpmnXml = read?.second,
                warnings = warnings + (read?.first?.warnings ?: emptyList()),
                fingerprint = graph?.let(::fingerprint),
        )
    }

    /** Leaves out where each node is in the source, so moving code around does not change it. */
    private fun fingerprint(graph: WorkflowGraph): String {
        val shape = buildString {
            graph.nodes.forEach { append("${it.id}|${it.kind}|${it.name}|${it.primitive}|${it.awaited}\n") }
            graph.edges.forEach { append("${it.from}>${it.to}|${it.kind}\n") }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(shape.toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
