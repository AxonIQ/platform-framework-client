/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.messaging

import io.axoniq.platform.framework.messaging.enhancing.AxoniqPlatformCommandHandlingMember
import io.axoniq.platform.framework.messaging.enhancing.AxoniqPlatformEventHandlingMember
import io.axoniq.platform.framework.messaging.enhancing.AxoniqPlatformMessageHandlingMember
import io.axoniq.platform.framework.messaging.enhancing.AxoniqPlatformQueryHandlingMember
import io.github.oshai.kotlinlogging.KotlinLogging
import org.axonframework.common.Priority
import org.axonframework.messaging.commandhandling.annotation.CommandHandlingMember
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition
import org.axonframework.messaging.core.annotation.MessageHandlingMember
import org.axonframework.messaging.eventhandling.annotation.EventHandlingMember
import org.axonframework.messaging.queryhandling.annotation.QueryHandlingMember

@Priority((Int.MIN_VALUE * 0.95).toInt())
class AxoniqPlatformHandlerEnhancerDefinition : HandlerEnhancerDefinition {
    private val logger = KotlinLogging.logger { }


    override fun <T: Any> wrapHandler(original: MessageHandlingMember<T>): MessageHandlingMember<T> {
        if (original.attribute<Any>("EventSourcingHandler.payloadType").isPresent) {
            // Skip event sourcing handlers
            return original;
        }

        val declaringClassName = original.declaringClass().simpleName
        logger.debug { "Wrapping message handling member [$original] of class [$declaringClassName] for Axoniq Platform measurements." }
        if (original is QueryHandlingMember<*>) {
            val axoniqMember = AxoniqPlatformQueryHandlingMember(original, declaringClassName)
            return axoniqMember as MessageHandlingMember<T>
        } else if (original is CommandHandlingMember<*>) {
            return AxoniqPlatformCommandHandlingMember(original as CommandHandlingMember<T>, declaringClassName)
        } else if (original is EventHandlingMember<*>) {
            return AxoniqPlatformEventHandlingMember(original as EventHandlingMember<T>, declaringClassName)
        }
        return AxoniqPlatformMessageHandlingMember(original, declaringClassName)
    }

}
