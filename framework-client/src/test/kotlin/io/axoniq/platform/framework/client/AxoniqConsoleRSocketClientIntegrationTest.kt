/*
 * Copyright (c) 2022-2026. AxonIQ B.V.
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

package io.axoniq.platform.framework.client

import io.axoniq.platform.framework.AxoniqPlatformConfiguration
import io.axoniq.platform.framework.api.ClientSettingsV2
import io.axoniq.platform.framework.api.ClientStatus
import io.axoniq.platform.framework.api.CommandBusInformation
import io.axoniq.platform.framework.api.EventStoreInformation
import io.axoniq.platform.framework.api.ModuleVersion
import io.axoniq.platform.framework.api.QueryBusInformation
import io.axoniq.platform.framework.api.SetupPayload
import io.axoniq.platform.framework.api.Versions
import io.axoniq.platform.framework.client.strategy.CborJackson3EncodingStrategy
import io.mockk.every
import io.rsocket.exceptions.ApplicationErrorException
import io.mockk.mockk
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class AxoniqConsoleRSocketClientIntegrationTest {

    private lateinit var mockServer: MockConsoleServer
    private lateinit var client: AxoniqConsoleRSocketClient

    @BeforeEach
    fun setUp() {
        mockServer = MockConsoleServer()
        mockServer.start()
    }

    @AfterEach
    fun tearDown() {
        client.disposeClient()
        mockServer.stop()
    }

    // ---- tests ----

    @Test
    fun `connects successfully and receives settings`() {
        client = buildClient()
        client.start()

        await().atMost(5, TimeUnit.SECONDS).until { client.isConnected() }
    }

    @Test
    fun `does not connect with invalid authentication`() {
        mockServer.rejectSetup = true
        client = buildClient()
        client.start()

        // Give the client several retry cycles. It must never report connected.
        Thread.sleep(4000)
        assertFalse(client.isConnected())
    }

    @Test
    fun `notifies observers of INVALID_AUTHENTICATION when setup is rejected`() {
        mockServer.rejectSetup = true
        val settingsService = PlatformClientConnectionService()
        val observer = TestObserver()
        settingsService.subscribeToSettings(observer)

        client = buildClient(platformClientConnectionService = settingsService)
        client.start()

        await().atMost(5, TimeUnit.SECONDS).until { observer.unreachableReasons.isNotEmpty() }
        assertTrue(observer.unreachableReasons.all {
            it == PlatformClientConnectionObserver.UnreachableReason.INVALID_AUTHENTICATION
        })
    }

    @Test
    fun `notifies observers of NO_CONNECTION when server is unreachable`() {
        val settingsService = PlatformClientConnectionService()
        val observer = TestObserver()
        settingsService.subscribeToSettings(observer)

        // Point to a port where nothing is listening
        client = buildClient(platformClientConnectionService = settingsService, port = 1)
        client.start()

        await().atMost(5, TimeUnit.SECONDS).until { observer.unreachableReasons.isNotEmpty() }
        assertTrue(observer.unreachableReasons.all {
            it == PlatformClientConnectionObserver.UnreachableReason.NO_CONNECTION
        })
    }

    @Test
    fun `reconnects after server closes connection`() {
        client = buildClient()
        client.start()

        await().atMost(5, TimeUnit.SECONDS).until { client.isConnected() }

        mockServer.disconnectClients()

        // Client should detect the TCP close and mark itself disconnected.
        await().atMost(5, TimeUnit.SECONDS).until { !client.isConnected() }

        // Backoff for first retry is 2^0 = 1 s; allow generous margin.
        await().atMost(10, TimeUnit.SECONDS).until { client.isConnected() }
    }

    @Test
    fun `requestStream emits every payload from the server in order and completes`() {
        val expected = listOf(
                ModuleVersion(dependency = "axon-messaging", version = "4.9.0"),
                ModuleVersion(dependency = "axon-configuration", version = "4.9.0"),
                ModuleVersion(dependency = "axon-test", version = null),
        )
        mockServer.streamResponses = expected

        client = buildClient()
        client.start()
        await().atMost(5, TimeUnit.SECONDS).until { client.isConnected() }

        val received = client
                .requestStream("request", MockConsoleServer.STREAM_ROUTE, ModuleVersion::class.java)
                .collectList()
                .block(Duration.ofSeconds(5))

        assertEquals(expected, received)
    }

    @Test
    fun `requestStream completes without emissions for an empty stream`() {
        mockServer.streamResponses = emptyList()

        client = buildClient()
        client.start()
        await().atMost(5, TimeUnit.SECONDS).until { client.isConnected() }

        val received = client
                .requestStream("request", MockConsoleServer.STREAM_ROUTE, ModuleVersion::class.java)
                .collectList()
                .block(Duration.ofSeconds(5))

        assertTrue(received!!.isEmpty())
    }

    @Test
    fun `requestStream propagates an error from the server`() {
        client = buildClient()
        client.start()
        await().atMost(5, TimeUnit.SECONDS).until { client.isConnected() }

        val exception = assertThrows(ApplicationErrorException::class.java) {
            client
                    .requestStream("request", "unknown.stream.route", ModuleVersion::class.java)
                    .collectList()
                    .block(Duration.ofSeconds(5))
        }
        assertTrue(exception.message!!.contains("unknown stream route")) {
            "Expected the server's error message to propagate, but was: ${exception.message}"
        }
    }

    @Test
    fun `requestStream picks up the fresh connection when resubscribed after a reconnect`() {
        val expected = listOf(ModuleVersion(dependency = "axon-messaging", version = "4.9.0"))
        mockServer.streamResponses = expected

        client = buildClient()
        client.start()
        await().atMost(5, TimeUnit.SECONDS).until { client.isConnected() }

        // Assemble once while connected, so a stale captured socket would surface on resubscription.
        val stream = client.requestStream("request", MockConsoleServer.STREAM_ROUTE, ModuleVersion::class.java)
        assertEquals(expected, stream.collectList().block(Duration.ofSeconds(5)))

        mockServer.disconnectClients()
        await().atMost(5, TimeUnit.SECONDS).until { !client.isConnected() }
        await().atMost(10, TimeUnit.SECONDS).until { client.isConnected() }

        assertEquals(expected, stream.collectList().block(Duration.ofSeconds(5)))
    }

    @Test
    fun `disconnects when heartbeats from server stop arriving`() {
        mockServer.clientSettings = ClientSettingsV2(
                heartbeatInterval = 200,
                heartbeatTimeout = 1000,
                processorReportInterval = 5000,
                handlerReportInterval = 5000,
                applicationReportInterval = 5000,
        )
        client = buildClient()
        client.start()

        await().atMost(5, TimeUnit.SECONDS).until { client.isConnected() }

        mockServer.stopSendingHeartbeats()

        // heartbeatTimeout=1000ms, checker runs every 1000ms → disconnect within ~2000ms; allow 5s.
        await().atMost(5, TimeUnit.SECONDS).until { !client.isConnected() }
    }

    // ---- helpers ----

    private fun buildClient(
            platformClientConnectionService: PlatformClientConnectionService = PlatformClientConnectionService(),
            port: Int = mockServer.port,
    ): AxoniqConsoleRSocketClient {
        val encodingStrategy = CborJackson3EncodingStrategy()
        val setupPayloadCreator = mockk<SetupPayloadCreator>()
        every { setupPayloadCreator.createReport() } returns minimalSetupPayload()

        val config = AxoniqPlatformConfiguration("test-env", "test-token", "test-app")
                .host("localhost")
                .port(port)
                .secure(false)

        return AxoniqConsoleRSocketClient(
                properties = config,
                setupPayloadCreator = setupPayloadCreator,
                registrar = RSocketHandlerRegistrar(encodingStrategy),
                encodingStrategy = encodingStrategy,
                platformClientConnectionService = platformClientConnectionService,
                instanceName = "test-instance"
        )
    }

    private fun minimalSetupPayload() = SetupPayload(
            commandBus = CommandBusInformation(
                    type = "test", axonServer = false, localSegmentType = null,
                    context = null, messageSerializer = null,
            ),
            queryBus = QueryBusInformation(
                    type = "test", axonServer = false, localSegmentType = null,
                    context = null, messageSerializer = null, serializer = null,
            ),
            eventStore = EventStoreInformation(
                    type = "test", axonServer = false, context = null,
                    eventSerializer = null, snapshotSerializer = null,
            ),
            processors = emptyList(),
            versions = Versions(frameworkVersion = "test", moduleVersions = emptyList<ModuleVersion>()),
            upcasters = emptyList(),
    )

    private class TestObserver : PlatformClientConnectionObserver {
        val unreachableReasons = CopyOnWriteArrayList<PlatformClientConnectionObserver.UnreachableReason>()

        override fun onConnected(clientStatus: ClientStatus, settings: ClientSettingsV2) {}
        override fun onDisconnected() {}
        override fun onUnreachable(reason: PlatformClientConnectionObserver.UnreachableReason) {
            unreachableReasons.add(reason)
        }
    }
}
