/*
 * Copyright (c) 2026. AxonIQ B.V.
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

import io.axoniq.console.framework.api.AxoniqConsoleDlqMode
import io.axoniq.console.framework.api.DomainEventAccessMode
import io.axoniq.console.framework.application.RuntimeInformationProvider
import org.axonframework.config.DefaultConfigurer
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SetupPayloadCreatorTest {

    private val configuration = DefaultConfigurer.defaultConfiguration()
            // Registers the EventProcessingModule the creator casts to; a bare configuration has none.
            .also { it.eventProcessing() }
            .buildConfiguration()

    private fun creator(runtime: RuntimeInformationProvider) = SetupPayloadCreator(
            configuration,
            AxoniqConsoleDlqMode.NONE,
            DomainEventAccessMode.NONE,
            runtime,
    )

    @Test
    fun `describes the runtime it is running on`() {
        val payload = creator(RuntimeInformationProvider()).createReport()

        assertNotNull(payload.runtime)
        assertNotNull(payload.runtime!!.availableProcessors)
    }

    @Test
    fun `leaves the runtime out rather than failing the connection when it cannot be described`() {
        // Stands in for any future accessor that forgets to guard itself. This runs on the connect path,
        // so the rest of the payload has to survive it.
        val exploding = RuntimeInformationProvider(property = { throw IllegalStateException("boom") })

        val payload = creator(exploding).createReport()

        assertNull(payload.runtime)
        assertNotNull(payload.commandBus)
        assertNotNull(payload.versions)
    }
}
