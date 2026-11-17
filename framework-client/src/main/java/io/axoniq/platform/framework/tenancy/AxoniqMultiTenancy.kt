/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.tenancy

import io.axoniq.framework.messaging.multitenancy.api.TenantDescriptor
import io.axoniq.framework.messaging.multitenancy.api.TenantProvider
import org.axonframework.common.configuration.Configuration
import org.axonframework.messaging.core.unitofwork.ProcessingContext
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken
import org.slf4j.LoggerFactory

internal class AxoniqMultiTenancy(private val provider: TenantProvider) : PlatformTenancy {

    @Volatile
    private var tokensUnreadable = false

    override fun tenants(): List<String> = provider.tenants().map { it.tenantId() }

    override fun bind(context: ProcessingContext, tenantId: String) {
        context.putResource(TenantDescriptor.RESOURCE_KEY, TenantDescriptor.tenantWithId(tenantId))
    }

    override fun positionsIn(token: TrackingToken): Map<String, Long>? {
        if (tokensUnreadable) return null
        return try {
            MultiTenantTokens.positions(token, tenants())
        } catch (e: LinkageError) {
            tokensUnreadable = true
            logger.warn("Cannot read per-tenant positions from this Axoniq Framework version; reporting one position " +
                                "per segment instead.", e)
            null
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AxoniqMultiTenancy::class.java)

        /** Null when multi-tenancy is on the classpath but switched off, which leaves no [TenantProvider]. */
        fun of(configuration: Configuration): PlatformTenancy? =
                configuration.getOptionalComponent(TenantProvider::class.java)
                        .map<PlatformTenancy> { AxoniqMultiTenancy(it) }
                        .orElse(null)
    }
}
