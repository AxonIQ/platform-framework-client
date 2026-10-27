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
import io.axoniq.platform.framework.client.RSocketHandlerRegistrar
import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Answers [Routes.Workflow.DEFINITIONS] with every workflow definition registered in the application and the graph
 * read from its code. The graph of a definition is read once, the first time it is asked for; a definition's code
 * does not change while the application runs.
 */
class WorkflowDefinitionsResponder(private val registrar: RSocketHandlerRegistrar) {
    private val logger = KotlinLogging.logger { }
    private val registries = CopyOnWriteArraySet<WorkflowConfigurationRegistry<*>>()
    private val descriptions = ConcurrentHashMap<WorkflowConfiguration<*>, WorkflowDefinitionDescription>()

    fun registerRegistry(registry: WorkflowConfigurationRegistry<*>) {
        registries += registry
    }

    fun start() {
        registrar.registerHandlerWithoutPayload(Routes.Workflow.DEFINITIONS) { definitions() }
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
        val result = try {
            WorkflowDefinitionLocator.locate(configuration.workflowDefinition(), name, warnings)
                    ?.let { WorkflowDefinitionAnalyzer(it.classLoader).analyze(it.method) }
        } catch (e: Exception) {
            logger.debug(e) { "Could not read the graph of workflow $name $version" }
            warnings += "Could not read the graph: ${e.message}"
            null
        }
        val graph = result?.graph
        return WorkflowDefinitionDescription(
                name = name,
                version = version,
                startEvents = startEvents,
                graph = graph,
                bpmnXml = graph?.let { WorkflowBpmnWriter.write(name, version, it) },
                warnings = warnings + (result?.warnings ?: emptyList()),
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
