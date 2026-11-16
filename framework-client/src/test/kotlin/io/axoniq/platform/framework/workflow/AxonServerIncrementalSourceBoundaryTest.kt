/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.workflow

import io.axoniq.framework.testcontainer.AxonServerContainer
import org.axonframework.common.configuration.AxonConfiguration
import org.axonframework.common.configuration.ComponentDefinition
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Runs only with `-P axon-server-tests`, which puts the Axon Server connector on the classpath: the connector makes
 * every application in the other tests connect to Axon Server. Without the profile the connector is absent and these
 * tests are skipped, so its configuration is set through reflection.
 */
@Testcontainers(disabledWithoutDocker = true)
class AxonServerIncrementalSourceBoundaryTest : IncrementalSourceBoundaryContract() {

    companion object {
        private const val CONFIGURATION_TYPE = "io.axoniq.framework.axonserver.connector.api.AxonServerConfiguration"
        private val connector: Class<*>? = runCatching { Class.forName(CONFIGURATION_TYPE) }.getOrNull()
        private val axonServer by lazy { AxonServerContainer().withDevMode(true).withDcbContext(true).apply { start() } }

        @JvmStatic
        @BeforeAll
        fun requireConnector() {
            assumeTrue(connector != null, "The Axon Server connector is only on the classpath with -P axon-server-tests")
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun configure(): AxonConfiguration {
        val type = connector as Class<Any>
        val settings = type.getConstructor().newInstance()
        type.getMethod("setServers", String::class.java).invoke(settings, axonServer.axonServerAddress)
        return EventSourcingConfigurer.create()
                .componentRegistry { it.registerComponent(ComponentDefinition.ofType(type).withInstance(settings)) }
                .start()
    }
}
