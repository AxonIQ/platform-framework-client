/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.workflow

import org.axonframework.messaging.core.annotation.MessageHandlingMember
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodNode
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class LocatedDefinition(val method: MethodRef, val classLoader: ClassLoader)

/**
 * Finds the method that holds a registered workflow definition's code, so [WorkflowDefinitionAnalyzer] can read it.
 *
 * - An annotated `@Workflow` method: the engine wraps it in a lambda that captures the method's handler member,
 *   which knows the method.
 * - A class implementing `WorkflowDefinition`: its `accept` method.
 * - A lambda or method reference: the JVM keeps lambdas in hidden classes whose bytecode cannot be read, so the
 *   implementation is found in the class that created the lambda, at the `invokedynamic` that made it.
 */
internal object WorkflowDefinitionLocator {
    private const val DEFINITION_TYPE = "io/axoniq/framework/workflow/runtime/api/execution/context/WorkflowDefinition"
    private const val LAMBDA_FACTORY = "java/lang/invoke/LambdaMetafactory"
    private const val LAMBDA_CLASS_MARKER = "$\$Lambda"

    fun locate(definition: Any, workflowName: String, warnings: MutableList<String>): LocatedDefinition? {
        annotatedMethod(definition)?.let { return it }
        val type = definition.javaClass
        if (!type.name.contains(LAMBDA_CLASS_MARKER)) {
            acceptMethod(type)?.let { return it }
            warnings += "Could not find the code of workflow definition ${type.name}"
            return null
        }
        return lambdaImplementation(type, workflowName, warnings)
    }

    private fun annotatedMethod(definition: Any): LocatedDefinition? = capturedValues(definition)
            .filterIsInstance<MessageHandlingMember<*>>()
            .firstNotNullOfOrNull { member -> member.unwrap(Method::class.java).orElse(null) }
            ?.let { method -> LocatedDefinition(method.toRef(), method.declaringClass.classLoader) }

    private fun capturedValues(definition: Any): List<Any> = definition.javaClass.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && it.trySetAccessible() }
            .mapNotNull { runCatching { it.get(definition) }.getOrNull() }

    private fun acceptMethod(type: Class<*>): LocatedDefinition? = type.methods
            .firstOrNull { it.name == "accept" && it.parameterCount == 1 && !it.isBridge }
            ?.let { LocatedDefinition(it.toRef(), type.classLoader) }

    private fun lambdaImplementation(type: Class<*>, workflowName: String, warnings: MutableList<String>): LocatedDefinition? {
        val host = type.name.substringBefore(LAMBDA_CLASS_MARKER).replace('.', '/')
        val classLoader = type.classLoader
        val hostNode = classLoader.getResourceAsStream("$host.class")?.use { stream ->
            ClassNode().also { ClassReader(stream).accept(it, ClassReader.SKIP_FRAMES) }
        }
        if (hostNode == null) {
            warnings += "Could not read ${host.replace('/', '.')}, where workflow $workflowName is defined"
            return null
        }
        val captured = type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { Type.getInternalName(it.type) }
        val candidates = hostNode.methods.flatMap { enclosing ->
            enclosing.instructions.toArray().filterIsInstance<InvokeDynamicInsnNode>()
                    .filter { it.bsm.owner == LAMBDA_FACTORY && Type.getReturnType(it.desc).internalName == DEFINITION_TYPE }
                    .filter { indy -> Type.getArgumentTypes(indy.desc).map { it.internalName } == captured }
                    .map { indy -> enclosing to (indy.bsmArgs[1] as Handle) }
        }
        val chosen = when {
            candidates.isEmpty() -> null
            candidates.size == 1 -> candidates.single()
            else -> candidates.filter { (enclosing, _) -> mentions(enclosing, workflowName) }.singleOrNull()
                    ?: candidates.first().also {
                        warnings += "${candidates.size} workflow definitions are created in ${host.replace('/', '.')}; " +
                                "the graph of $workflowName may be drawn from another one"
                    }
        }
        if (chosen == null) {
            warnings += "Could not find where workflow $workflowName is defined in ${host.replace('/', '.')}"
            return null
        }
        val implementation = chosen.second
        return LocatedDefinition(MethodRef(implementation.owner, implementation.name, implementation.desc), classLoader)
    }

    private fun mentions(method: MethodNode, text: String) =
            method.instructions.toArray().any { it is LdcInsnNode && it.cst == text }

    private fun Method.toRef() = MethodRef(Type.getInternalName(declaringClass), name, Type.getMethodDescriptor(this))
}
