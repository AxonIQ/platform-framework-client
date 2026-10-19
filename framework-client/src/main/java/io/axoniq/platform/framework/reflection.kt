/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework

import org.axonframework.common.ReflectionUtils


fun <T> Any.getPropertyValue(fieldName: String): T? {
    val field = ReflectionUtils.fieldsOf(this::class.java).firstOrNull { it.name == fieldName } ?: return null
    return ReflectionUtils.getMemberValue(
            field,
            this
    )
}

fun <T> Any.setPropertyValue(fieldName: String, value: T) {
    val field = ReflectionUtils.fieldsOf(this::class.java).firstOrNull { it.name == fieldName } ?: return
    field.isAccessible = true
    field.set(this, value)
}

fun Any.getPropertyType(fieldName: String): String {
    return ReflectionUtils.getMemberValue<Any>(
            ReflectionUtils.fieldsOf(this::class.java).first { it.name == fieldName },
            this
    ).let { it::class.java.name }
}

fun Any.getPropertyType(fieldName: String, clazz: Class<out Any>): String {
    return ReflectionUtils.getMemberValue<Any>(
            ReflectionUtils.fieldsOf(this::class.java).first { it.name == fieldName },
            this
    )
            .let { it.unwrapPossiblyDecoratedClass(clazz) }
            .let { it::class.java.name }
}
