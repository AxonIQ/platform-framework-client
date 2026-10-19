/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.application

import java.lang.reflect.Method


fun Class<*>.detectMethod(bean: Any, name: String): Method? {
    return try {
        this.cast(bean)
        this.getMethod(name)
    } catch (e: Exception) {
        null
    }
}

fun List<String>.firstExistingClass(): Class<*>? {
    for (className in this) {
        try {
            return Class.forName(className)
        } catch (e: ClassNotFoundException) {
            // ignore
        }
    }
    return null
}