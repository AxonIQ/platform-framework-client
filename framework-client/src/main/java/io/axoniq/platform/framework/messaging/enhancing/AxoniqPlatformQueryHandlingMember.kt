/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.messaging.enhancing

import org.axonframework.messaging.queryhandling.annotation.QueryHandlingMember
import java.lang.reflect.Type

class AxoniqPlatformQueryHandlingMember<T: Any>(override val delegate: QueryHandlingMember<T>, declaringClassName: String) : AxoniqPlatformMessageHandlingMember<T>(delegate, declaringClassName), QueryHandlingMember<T> {
    override fun queryName(): String {
        return delegate.queryName()
    }

    override fun resultType(): Type {
        return delegate.resultType()
    }
}