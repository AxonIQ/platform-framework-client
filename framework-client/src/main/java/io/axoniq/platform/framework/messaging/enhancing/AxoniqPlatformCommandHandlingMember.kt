/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.messaging.enhancing

import org.axonframework.messaging.commandhandling.annotation.CommandHandlingMember

class AxoniqPlatformCommandHandlingMember<T: Any>(
        override val delegate: CommandHandlingMember<T>,
        declaringClassName: String
) : AxoniqPlatformMessageHandlingMember<T>(
        delegate,
        declaringClassName
), CommandHandlingMember<T> {

    override fun commandName(): String {
        return delegate.commandName()
    }

    override fun routingKey(): String {
        return delegate.routingKey()
    }

    override fun isFactoryHandler(): Boolean {
        return delegate.isFactoryHandler()
    }
}