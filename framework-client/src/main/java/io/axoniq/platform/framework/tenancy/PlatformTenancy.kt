/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.tenancy

import org.axonframework.common.configuration.Configuration
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken

/**
 * The tenants of an application running Axoniq Framework multi-tenancy. Outside message handling the framework
 * resolves the tenant only from the processing context, so every call the Platform makes on a tenant-routed
 * component (dead-letter queue, event store) has to run in a context bound to one tenant.
 *
 * Free of multi-tenancy types, so it can be held on a classpath without `axoniq-multi-tenancy`.
 */
interface PlatformTenancy {

    fun tenants(): List<String>

    fun bind(context: ProcessingContext, tenantId: String)

    /**
     * The position of every tenant inside [token]; -1 for a tenant the token has not reached yet. Null when [token]
     * is not a multi-tenant token, or the framework changed that token in a way this client cannot read.
     */
    fun positionsIn(token: TrackingToken): Map<String, Long>?

    companion object {
        private const val TENANT_PROVIDER_CLASS = "io.axoniq.framework.messaging.multitenancy.api.TenantProvider"

        /** The tenancy of [configuration], or null when the application is not multi-tenant. */
        @JvmStatic
        fun of(configuration: Configuration): PlatformTenancy? =
                if (isOnClasspath()) AxoniqMultiTenancy.of(configuration) else null

        private fun isOnClasspath(): Boolean = try {
            Class.forName(TENANT_PROVIDER_CLASS, false, PlatformTenancy::class.java.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }
}
