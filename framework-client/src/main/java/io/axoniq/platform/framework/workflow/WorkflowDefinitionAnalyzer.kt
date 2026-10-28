/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.workflow

import io.axoniq.platform.framework.api.WorkflowGraph
import io.axoniq.platform.framework.api.WorkflowGraphEdge
import io.axoniq.platform.framework.api.WorkflowGraphEdgeKind
import io.axoniq.platform.framework.api.WorkflowGraphNode
import io.axoniq.platform.framework.api.WorkflowGraphNodeKind
import io.axoniq.platform.framework.api.WorkflowStepPrimitive
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.Frame
import org.objectweb.asm.tree.analysis.SourceInterpreter
import org.objectweb.asm.tree.analysis.SourceValue

/** A method whose bytecode holds a workflow definition, or a part of one it calls. */
internal data class MethodRef(val owner: String, val name: String, val descriptor: String) {
    override fun toString() = "${owner.replace('/', '.')}.$name"
}

internal data class AnalysisResult(val graph: WorkflowGraph, val warnings: List<String>)

/**
 * Reads a workflow definition's bytecode and turns the [WorkflowContext][WORKFLOW_CONTEXT] calls in it into a graph.
 *
 * Every step primitive the definition calls on its context (`execute`, `awaitEvent`, `publish`, …) becomes a step,
 * named by the string constant passed as its name, or by all of them when the name is one of several constants. The
 * control flow between those calls becomes the edges: a step followed by more than one possible next step gets an
 * exclusive gateway, a jump back a loop edge, and a catch block an error edge. A step started without waiting runs on
 * a parallel branch from where it is started to the join that waits for it (`allMatch`, `anyMatch`, `noneMatch`,
 * `await()`); its result is followed through locals, helper parameters and return values. Calls into the
 * application's own methods that take the context are followed, up to [maxCallDepth] deep.
 *
 * It reads the code, it does not run it: a step name computed at runtime has no name, and code it cannot follow (an
 * interface call, a call deeper than [maxCallDepth]) is drawn as [WorkflowGraphNodeKind.UNRECOGNISED] and reported
 * in the warnings, never guessed.
 */
internal class WorkflowDefinitionAnalyzer(
        private val classLoader: ClassLoader,
        private val maxCallDepth: Int = 4,
) {
    private val types = WorkflowTypes(classLoader)
    private val classes = HashMap<String, ClassNode?>()

    fun analyze(entry: MethodRef): AnalysisResult {
        val warnings = mutableListOf<String>()
        val builder = GraphBuilder()
        val start = builder.node(WorkflowGraphNodeKind.START)
        val end = builder.node(WorkflowGraphNodeKind.END)
        val body = fragment(entry, builder, warnings, ArrayDeque(), 0, emptyMap())
        if (body == null) {
            builder.edge(start, end)
        } else {
            body.entries.forEach { builder.edge(start, it.target, it.kind) }
            body.exits.forEach { builder.edge(it, end) }
            if (body.passesThrough) builder.edge(start, end)
        }
        builder.insertExclusiveGateways()
        builder.forkStartedSteps()
        builder.removeUnreachable(start)
        return AnalysisResult(builder.build(), warnings.distinct())
    }

    /**
     * A method's part of the graph: where its flow starts, which of its nodes hand control back to the caller, whether
     * some path through it reaches a return without passing any step, and the step nodes whose results it returns.
     */
    private class Fragment(
            val entries: List<Link>,
            val exits: List<String>,
            val passesThrough: Boolean,
            val returned: Set<String>,
    )

    private class Link(val target: String, val kind: WorkflowGraphEdgeKind = WorkflowGraphEdgeKind.NORMAL)

    private sealed interface Mark {
        val index: Int
    }

    private data class StepMark(
            override val index: Int,
            val primitive: WorkflowStepPrimitive,
            val name: String?,
            val awaited: Boolean,
    ) : Mark

    private data class TerminalMark(override val index: Int, val kind: WorkflowGraphNodeKind) : Mark

    /** Waits for the step results in [joined]; `await()` on a combinator's result adds nothing to the combinator. */
    private data class JoinMark(override val index: Int, val name: String, val joined: List<SourceValue>) : Mark

    private data class CallMark(override val index: Int, val callee: MethodRef) : Mark

    private data class ReturnMark(override val index: Int) : Mark

    private fun fragment(
            method: MethodRef,
            builder: GraphBuilder,
            warnings: MutableList<String>,
            callStack: ArrayDeque<MethodRef>,
            depth: Int,
            parameters: Map<Int, Set<String>>,
    ): Fragment? {
        val node = methodNode(method)
        if (node == null || node.instructions.size() == 0) {
            warnings += "Could not read the code of $method"
            return null
        }
        val cfg = ControlFlow(method.owner, node)
        if (!cfg.analyzed) {
            warnings += "Could not analyse the control flow of $method"
            return null
        }
        val found = marksOf(method, node, cfg, warnings)
        val instructions = node.instructions.toArray()
        val indexOf = instructions.withIndex().associate { it.value to it.index }
        val lines = lineNumbers(node)
        val source = { index: Int -> "$method${lines[index]?.let { ":$it" } ?: ""}" }

        // Steps, ends and joins are nodes; calls into the application's own code are expanded in place.
        val ids = HashMap<Int, String>()
        val calls = HashMap<Int, Fragment>()
        val combinators = HashSet<String>()
        val origins = { value: SourceValue ->
            value.insns.flatMapTo(LinkedHashSet()) { insn ->
                cfg.parameters[insn]?.let { parameters[it] }
                        ?: indexOf[insn]?.let { index -> ids[index]?.let(::setOf) ?: calls[index]?.returned }
                        ?: emptySet()
            }
        }
        val marks = sortedMapOf<Int, Mark>()
        found.values.forEach { mark ->
            when (mark) {
                is StepMark -> ids[mark.index] = builder.node(
                        WorkflowGraphNodeKind.STEP, mark.name, mark.primitive, mark.awaited, source(mark.index)
                )
                is TerminalMark -> ids[mark.index] = builder.node(mark.kind, source = source(mark.index))
                is JoinMark -> {
                    val joined = mark.joined.flatMapTo(LinkedHashSet()) { origins(it) }
                    if (mark.name == "await" && joined.any { it in combinators }) return@forEach
                    if (joined.isEmpty()) warnings += "Could not tell which steps ${mark.name} in $method waits for"
                    val join = builder.node(WorkflowGraphNodeKind.PARALLEL_JOIN, mark.name, source = source(mark.index))
                    if (mark.name != "await") combinators += join
                    joined.forEach { builder.joinStartedStep(it, join) }
                    ids[mark.index] = join
                }
                is CallMark -> {
                    val arguments = argumentOrigins(instructions[mark.index] as MethodInsnNode, cfg.frames[mark.index]!!, origins)
                    val expanded = expand(mark.callee, method, builder, warnings, callStack, depth, arguments)
                    if (expanded == null) {
                        ids[mark.index] = builder.node(
                                WorkflowGraphNodeKind.UNRECOGNISED, mark.callee.name, source = source(mark.index)
                        )
                    } else {
                        calls[mark.index] = expanded
                    }
                }
                is ReturnMark -> {}
            }
            marks[mark.index] = mark
        }
        val returned = marks.values.filterIsInstance<ReturnMark>()
                .filter { instructions[it.index].opcode == Opcodes.ARETURN }
                .flatMapTo(LinkedHashSet()) { cfg.frames[it.index]!!.let { frame -> origins(frame.getStack(frame.stackSize - 1)) } }

        val flow = MethodFlow(cfg, marks, ids, calls)
        marks.values.forEach { mark ->
            if (mark is TerminalMark || mark is ReturnMark) return@forEach
            val sources = ids[mark.index]?.let { listOf(it) } ?: calls[mark.index]?.exits ?: return@forEach
            val withExceptions = mark !is CallMark
            flow.next(mark.index, withExceptions).forEach { link ->
                sources.forEach { from -> builder.edge(from, link.target, link.kind) }
            }
        }
        val exits = marks.values.filterIsInstance<ReturnMark>().flatMap { flow.exitsBefore(it.index) }.distinct()
        return Fragment(flow.next(-1, false), exits, flow.reachesReturn(-1), returned)
    }

    /** The step nodes each argument of a call carries, by the local variable the callee receives it in. */
    private fun argumentOrigins(
            insn: MethodInsnNode,
            frame: Frame<SourceValue>,
            origins: (SourceValue) -> Set<String>,
    ): Map<Int, Set<String>> {
        val arguments = Type.getArgumentTypes(insn.desc)
        val firstArgument = frame.stackSize - arguments.size
        var local = if (insn.opcode == Opcodes.INVOKESTATIC) 0 else 1
        val result = HashMap<Int, Set<String>>()
        arguments.forEachIndexed { position, type ->
            origins(frame.getStack(firstArgument + position)).takeIf { it.isNotEmpty() }?.let { result[local] = it }
            local += type.size
        }
        return result
    }

    private fun expand(
            callee: MethodRef,
            caller: MethodRef,
            builder: GraphBuilder,
            warnings: MutableList<String>,
            callStack: ArrayDeque<MethodRef>,
            depth: Int,
            arguments: Map<Int, Set<String>>,
    ): Fragment? {
        if (callee in callStack || callee == caller) {
            warnings += "Did not follow $callee from $caller: it calls itself"
            return null
        }
        if (depth >= maxCallDepth) {
            warnings += "Did not follow $callee from $caller: calls nest deeper than $maxCallDepth"
            return null
        }
        callStack.addLast(caller)
        try {
            return fragment(callee, builder, warnings, callStack, depth + 1, arguments)
        } finally {
            callStack.removeLast()
        }
    }

    /** The flow between the marks of one method. */
    private class MethodFlow(
            private val cfg: ControlFlow,
            private val marks: Map<Int, Mark>,
            private val ids: Map<Int, String>,
            private val calls: Map<Int, Fragment>,
    ) {
        /**
         * The nodes control reaches next from instruction [from] (`-1` = the method's entry), with the kind of the
         * edge. Exception edges are only taken from the mark itself, so a step that can fail gets an error edge into
         * its catch block, but plain instructions around it do not.
         */
        fun next(from: Int, withExceptions: Boolean): List<Link> {
            val result = LinkedHashMap<String, WorkflowGraphEdgeKind>()
            reachedMarks(from, withExceptions).forEach { (index, kind) -> linksAt(index, kind, result, HashSet()) }
            return result.map { Link(it.key, it.value) }
        }

        fun reachesReturn(from: Int): Boolean = reachedMarks(from, false).any { (index, _) ->
            marks[index] is ReturnMark || (calls[index]?.passesThrough == true && reachesReturn(index))
        }

        /** The nodes that hand control to the return at [returnIndex]. */
        fun exitsBefore(returnIndex: Int): List<String> {
            val result = mutableListOf<String>()
            val seen = HashSet<Int>()
            val queue = ArrayDeque(cfg.predecessors[returnIndex])
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!seen.add(current)) continue
                when (val mark = marks[current]) {
                    null, is ReturnMark -> queue.addAll(cfg.predecessors[current])
                    is TerminalMark -> {}
                    is CallMark -> {
                        calls[current]?.let { result += it.exits } ?: ids[current]?.let { result += it }
                        if (calls[current]?.passesThrough == true) queue.addAll(cfg.predecessors[current])
                    }
                    else -> ids[mark.index]?.let { result += it }
                }
            }
            return result
        }

        private fun linksAt(index: Int, kind: WorkflowGraphEdgeKind, result: MutableMap<String, WorkflowGraphEdgeKind>, seen: MutableSet<Int>) {
            if (!seen.add(index)) return
            ids[index]?.let { result.putIfAbsent(it, kind); return }
            val call = calls[index] ?: return
            call.entries.forEach { result.putIfAbsent(it.target, if (kind == WorkflowGraphEdgeKind.NORMAL) it.kind else kind) }
            if (call.passesThrough) {
                reachedMarks(index, false).forEach { (next, nextKind) ->
                    linksAt(next, if (kind == WorkflowGraphEdgeKind.NORMAL) nextKind else kind, result, seen)
                }
            }
        }

        private fun reachedMarks(from: Int, withExceptions: Boolean): List<Pair<Int, WorkflowGraphEdgeKind>> {
            val result = LinkedHashMap<Int, WorkflowGraphEdgeKind>()
            val starts = if (from < 0) listOf(0 to false) else buildList {
                cfg.successors[from].forEach { add(it to false) }
                if (withExceptions) cfg.exceptionSuccessors[from].forEach { add(it to true) }
            }
            starts.forEach { (start, viaException) ->
                val seen = HashSet<Int>()
                val queue = ArrayDeque(listOf(start))
                while (queue.isNotEmpty()) {
                    val current = queue.removeFirst()
                    if (!seen.add(current)) continue
                    if (marks.containsKey(current)) {
                        result.putIfAbsent(current, when {
                            viaException -> WorkflowGraphEdgeKind.ERROR
                            from >= 0 && current <= from -> WorkflowGraphEdgeKind.LOOP
                            else -> WorkflowGraphEdgeKind.NORMAL
                        })
                        continue
                    }
                    queue.addAll(cfg.successors[current])
                }
            }
            return result.map { it.key to it.value }
        }
    }

    private fun marksOf(method: MethodRef, node: MethodNode, cfg: ControlFlow, warnings: MutableList<String>): Map<Int, Mark> {
        val marks = sortedMapOf<Int, Mark>()
        val instructions = node.instructions.toArray()
        instructions.forEachIndexed { index, insn ->
            val frame = cfg.frames[index] ?: return@forEachIndexed
            when {
                insn.opcode in Opcodes.IRETURN..Opcodes.RETURN -> marks[index] = ReturnMark(index)
                insn.opcode == Opcodes.ATHROW && cfg.successors[index].isEmpty() && cfg.exceptionSuccessors[index].isEmpty() ->
                    marks[index] = TerminalMark(index, WorkflowGraphNodeKind.FAIL)
                insn is MethodInsnNode -> methodMark(method, index, insn, frame, cfg, instructions, warnings)?.let { marks[index] = it }
            }
        }
        return marks
    }

    private fun methodMark(
            method: MethodRef,
            index: Int,
            insn: MethodInsnNode,
            frame: Frame<SourceValue>,
            cfg: ControlFlow,
            instructions: Array<AbstractInsnNode>,
            warnings: MutableList<String>,
    ): Mark? {
        if (types.isWorkflowContext(insn.owner)) {
            STEP_PRIMITIVES[insn.name]?.let { (primitive, awaited) ->
                val name = stepName(insn, frame, cfg, instructions)
                if (name == null) warnings += "A ${insn.name} step in $method has a name computed at runtime"
                return StepMark(index, primitive, name, awaited)
            }
            return when (insn.name) {
                "fail" -> TerminalMark(index, WorkflowGraphNodeKind.FAIL)
                "cancel" -> TerminalMark(index, WorkflowGraphNodeKind.CANCEL)
                "allMatch", "anyMatch", "noneMatch" -> JoinMark(index, insn.name, combinedSteps(index, frame, cfg, instructions))
                else -> null
            }
        }
        if (insn.name == "await" && insn.desc == "()V" && types.isStepResult(insn.owner)) {
            return JoinMark(index, "await", listOf(frame.getStack(frame.stackSize - 1)))
        }
        if (takesWorkflowContext(insn) && !isFrameworkType(insn.owner)) {
            return CallMark(index, MethodRef(insn.owner, insn.name, insn.desc))
        }
        return null
    }

    private fun takesWorkflowContext(insn: MethodInsnNode): Boolean =
            Type.getArgumentTypes(insn.desc).any { it.sort == Type.OBJECT && types.isWorkflowContext(it.internalName) }

    /** The step's name: the first string argument, or the first string argument of the step definition it is given. */
    private fun stepName(insn: MethodInsnNode, frame: Frame<SourceValue>, cfg: ControlFlow, instructions: Array<AbstractInsnNode>): String? {
        val arguments = Type.getArgumentTypes(insn.desc)
        val firstArgument = frame.stackSize - arguments.size
        arguments.forEachIndexed { position, type ->
            val value = frame.getStack(firstArgument + position)
            if (type.descriptor == STRING_DESCRIPTOR) return constantString(value, cfg, instructions)
            if (type.sort == Type.OBJECT && type.internalName.startsWith(DSL_API_PACKAGE) && type.internalName.endsWith("Definition")) {
                return definitionStepName(value, cfg, instructions)
            }
        }
        return null
    }

    private fun definitionStepName(value: SourceValue, cfg: ControlFlow, instructions: Array<AbstractInsnNode>): String? {
        val producer = value.insns.singleOrNull() as? MethodInsnNode ?: return null
        val producerIndex = instructions.indexOf(producer)
        val producerFrame = cfg.frames.getOrNull(producerIndex) ?: return null
        return stepName(producer, producerFrame, cfg, instructions)
    }

    /** The name when every value that can reach the call is a constant; several constants are listed together. */
    private fun constantString(value: SourceValue, cfg: ControlFlow, instructions: Array<AbstractInsnNode>): String? {
        val constants = value.insns.sortedBy { instructions.indexOf(it) }.map { insn ->
            when {
                insn is LdcInsnNode && insn.cst is String -> insn.cst as String
                insn is MethodInsnNode && insn.name.startsWith("default") && insn.name.endsWith("Definition") ->
                    cfg.frames.getOrNull(instructions.indexOf(insn))?.let { stepName(insn, it, cfg, instructions) }
                else -> null
            }
        }
        if (constants.isEmpty() || constants.any { it == null }) return null
        return constants.distinct().joinToString(" / ")
    }

    /** The values put into the varargs array of a combinator call. */
    private fun combinedSteps(
            index: Int,
            frame: Frame<SourceValue>,
            cfg: ControlFlow,
            instructions: Array<AbstractInsnNode>,
    ): List<SourceValue> {
        val array = frame.getStack(frame.stackSize - 1)
        val arrayCreators = array.insns.filter { it.opcode == Opcodes.ANEWARRAY }.toSet()
        if (arrayCreators.isEmpty()) return emptyList()
        val result = mutableListOf<SourceValue>()
        for (store in 0 until index) {
            if (instructions[store].opcode != Opcodes.AASTORE) continue
            val storeFrame = cfg.frames[store] ?: continue
            val target = storeFrame.getStack(storeFrame.stackSize - 3)
            if (target.insns.none { it in arrayCreators }) continue
            result += storeFrame.getStack(storeFrame.stackSize - 1)
        }
        return result
    }

    private fun lineNumbers(node: MethodNode): Map<Int, Int> {
        val result = HashMap<Int, Int>()
        var line: Int? = null
        node.instructions.toArray().forEachIndexed { index, insn ->
            if (insn is LineNumberNode) line = insn.line
            line?.let { result[index] = it }
        }
        return result
    }

    private fun methodNode(method: MethodRef): MethodNode? {
        var owner: String? = method.owner
        while (owner != null) {
            val classNode = classNode(owner) ?: return null
            classNode.methods.firstOrNull { it.name == method.name && it.desc == method.descriptor }?.let {
                return if (it.access and Opcodes.ACC_ABSTRACT != 0) null else it
            }
            owner = classNode.superName
        }
        return null
    }

    private fun classNode(internalName: String): ClassNode? = classes.getOrPut(internalName) {
        classLoader.getResourceAsStream("$internalName.class")?.use { stream ->
            ClassNode().also { ClassReader(stream).accept(it, 0) }
        }
    }

    private fun isFrameworkType(internalName: String) = FRAMEWORK_PACKAGES.any { internalName.startsWith(it) }

    /**
     * Control flow of one method, with copy-propagated value sources so locals lead back to the call that made them.
     * A parameter's value has a marker instruction as its source, so [parameters] tells which parameter it came from.
     */
    private class ControlFlow(owner: String, node: MethodNode) {
        val size = node.instructions.size()
        val successors = Array(size) { mutableListOf<Int>() }
        val exceptionSuccessors = Array(size) { mutableListOf<Int>() }
        val predecessors = Array(size) { mutableListOf<Int>() }
        val parameters = HashMap<AbstractInsnNode, Int>()
        val frames: Array<Frame<SourceValue>?>
        val analyzed: Boolean

        init {
            val analyzer = object : Analyzer<SourceValue>(CopyPropagatingInterpreter(parameters)) {
                override fun newControlFlowEdge(insnIndex: Int, successorIndex: Int) {
                    successors[insnIndex] += successorIndex
                    predecessors[successorIndex] += insnIndex
                }

                override fun newControlFlowExceptionEdge(insnIndex: Int, successorIndex: Int): Boolean {
                    exceptionSuccessors[insnIndex] += successorIndex
                    return true
                }
            }
            var result: Array<Frame<SourceValue>?> = arrayOfNulls(size)
            analyzed = try {
                result = analyzer.analyze(owner, node)
                true
            } catch (_: AnalyzerException) {
                false
            }
            frames = result
        }
    }

    private class CopyPropagatingInterpreter(
            private val parameters: MutableMap<AbstractInsnNode, Int>,
    ) : SourceInterpreter(Opcodes.ASM9) {
        override fun copyOperation(insn: AbstractInsnNode, value: SourceValue): SourceValue = value

        override fun newParameterValue(isInstanceMethod: Boolean, local: Int, type: Type): SourceValue {
            val marker = InsnNode(Opcodes.NOP)
            parameters[marker] = local
            return SourceValue(type.size, marker)
        }
    }

    companion object {
        private const val STRING_DESCRIPTOR = "Ljava/lang/String;"
        const val WORKFLOW_CONTEXT = "io/axoniq/framework/workflow/dsl/api/WorkflowContext"
        const val STEP_RESULT = "io/axoniq/framework/workflow/dsl/api/WorkflowStepResult"
        private const val DSL_API_PACKAGE = "io/axoniq/framework/workflow/dsl/api/"
        private val FRAMEWORK_PACKAGES = listOf(
                "io/axoniq/framework/", "org/axonframework/", "java/", "javax/", "jdk/", "kotlin/", "sun/",
        )

        /**
         * Step primitive per context method, and whether the definition waits for it where it is called. `sleep`
         * returns nothing, yet does not wait: the definition carries on and the timer runs on its own.
         */
        private val STEP_PRIMITIVES = mapOf(
                "execute" to (WorkflowStepPrimitive.EXECUTE to false),
                "awaitExecute" to (WorkflowStepPrimitive.EXECUTE to true),
                "waitForEvent" to (WorkflowStepPrimitive.WAIT_FOR_EVENT to false),
                "awaitEvent" to (WorkflowStepPrimitive.WAIT_FOR_EVENT to true),
                "sleep" to (WorkflowStepPrimitive.SLEEP to false),
                "awaitSleep" to (WorkflowStepPrimitive.SLEEP to true),
                "publish" to (WorkflowStepPrimitive.PUBLISH to false),
                "awaitPublish" to (WorkflowStepPrimitive.PUBLISH to true),
                "modifyPayload" to (WorkflowStepPrimitive.MODIFY_PAYLOAD to false),
                "awaitModifyPayload" to (WorkflowStepPrimitive.MODIFY_PAYLOAD to true),
                "migrateVersion" to (WorkflowStepPrimitive.MIGRATE_VERSION to true),
                "cancelStep" to (WorkflowStepPrimitive.CANCEL_STEP to true),
        )
    }
}

/** Answers whether a type is a workflow context or step result, loading it through the definition's class loader. */
internal class WorkflowTypes(private val classLoader: ClassLoader) {
    private val context = load(WorkflowDefinitionAnalyzer.WORKFLOW_CONTEXT)
    private val stepResult = load(WorkflowDefinitionAnalyzer.STEP_RESULT)
    private val cache = HashMap<String, Class<*>?>()

    fun isWorkflowContext(internalName: String) = isA(internalName, context)

    fun isStepResult(internalName: String) = isA(internalName, stepResult)

    private fun isA(internalName: String, type: Class<*>?): Boolean {
        if (type == null) return false
        val candidate = cache.getOrPut(internalName) { load(internalName) } ?: return false
        return type.isAssignableFrom(candidate)
    }

    private fun load(internalName: String): Class<*>? = try {
        Class.forName(internalName.replace('/', '.'), false, classLoader)
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: LinkageError) {
        null
    }
}

/** Collects nodes and edges, then tidies the graph into the shape a reader expects. */
internal class GraphBuilder {
    private val nodes = LinkedHashMap<String, WorkflowGraphNode>()
    private val edges = LinkedHashSet<WorkflowGraphEdge>()
    private val startedSteps = LinkedHashMap<String, String>()
    private var counter = 0

    fun node(
            kind: WorkflowGraphNodeKind,
            name: String? = null,
            primitive: WorkflowStepPrimitive? = null,
            awaited: Boolean = true,
            source: String? = null,
    ): String {
        val id = "n${counter++}"
        nodes[id] = WorkflowGraphNode(id, kind, name, primitive, awaited, source)
        return id
    }

    fun edge(from: String, to: String, kind: WorkflowGraphEdgeKind = WorkflowGraphEdgeKind.NORMAL) {
        if (from == to && kind != WorkflowGraphEdgeKind.LOOP) return
        if (edges.none { it.from == from && it.to == to }) edges += WorkflowGraphEdge(from, to, kind)
    }

    /** Records that [join] waits for [step], when [step] was started without waiting for it. */
    fun joinStartedStep(step: String, join: String) {
        val node = nodes[step] ?: return
        if (node.kind == WorkflowGraphNodeKind.STEP && !node.awaited) startedSteps.putIfAbsent(step, join)
    }

    /**
     * Puts every step started without waiting on a parallel branch: a fork where the step is started, with the step on
     * one branch running into the join that waits for it, and the flow carrying on along the other. Forks that follow
     * each other directly become one, a branch with nothing on it is dropped, and a fork or join left with a single
     * branch is removed.
     */
    fun forkStartedSteps() {
        startedSteps.forEach { (step, join) ->
            val fork = node(WorkflowGraphNodeKind.PARALLEL_FORK)
            edges.filter { it.to == step }.forEach { replace(it, it.copy(to = fork)) }
            edges.filter { it.from == step && it.kind != WorkflowGraphEdgeKind.ERROR }.forEach { replace(it, it.copy(from = fork)) }
            edge(fork, step)
            edge(step, join)
        }
        while (mergeFollowingFork()) continue
        nodes.values.filter { it.kind == WorkflowGraphNodeKind.PARALLEL_FORK }.forEach { fork ->
            val outgoing = edges.filter { it.from == fork.id }
            val empty = outgoing.filter { nodes[it.to]?.kind == WorkflowGraphNodeKind.PARALLEL_JOIN }
            if (outgoing.size > empty.size) edges -= empty.toSet()
        }
        nodes.values.toList().forEach { node ->
            val single = when (node.kind) {
                WorkflowGraphNodeKind.PARALLEL_FORK -> edges.count { it.from == node.id } == 1
                WorkflowGraphNodeKind.PARALLEL_JOIN -> edges.count { it.to == node.id } == 1
                else -> false
            }
            if (single) bypass(node.id)
        }
    }

    private fun mergeFollowingFork(): Boolean {
        nodes.values.filter { it.kind == WorkflowGraphNodeKind.PARALLEL_FORK }.forEach { fork ->
            val incoming = edges.filter { it.to == fork.id }
            val parent = incoming.singleOrNull()?.from ?: return@forEach
            if (nodes[parent]?.kind != WorkflowGraphNodeKind.PARALLEL_FORK) return@forEach
            edges -= incoming.toSet()
            edges.filter { it.from == fork.id }.forEach { replace(it, it.copy(from = parent)) }
            nodes -= fork.id
            return true
        }
        return false
    }

    private fun bypass(id: String) {
        val incoming = edges.filter { it.to == id }
        val outgoing = edges.filter { it.from == id }
        edges -= (incoming + outgoing).toSet()
        incoming.forEach { into ->
            outgoing.forEach { out ->
                val kind = if (out.kind == WorkflowGraphEdgeKind.NORMAL) into.kind else out.kind
                edge(into.from, out.to, kind)
            }
        }
        nodes -= id
    }

    private fun replace(old: WorkflowGraphEdge, new: WorkflowGraphEdge) {
        edges -= old
        edge(new.from, new.to, new.kind)
    }

    /** A node with more than one next node gets an exclusive gateway in front of the choice. */
    fun insertExclusiveGateways() {
        nodes.values.toList().forEach { node ->
            if (node.kind == WorkflowGraphNodeKind.PARALLEL_FORK || node.kind == WorkflowGraphNodeKind.EXCLUSIVE_GATEWAY) return@forEach
            val outgoing = edges.filter { it.from == node.id && it.kind != WorkflowGraphEdgeKind.ERROR }
            if (outgoing.size < 2) return@forEach
            val gateway = node(WorkflowGraphNodeKind.EXCLUSIVE_GATEWAY)
            edges -= outgoing.toSet()
            edge(node.id, gateway)
            outgoing.forEach { edge(gateway, it.to, it.kind) }
        }
    }

    fun removeUnreachable(start: String) {
        val reachable = HashSet<String>()
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!reachable.add(current)) continue
            edges.filter { it.from == current }.forEach { queue.add(it.to) }
        }
        nodes.keys.retainAll(reachable + nodes.filterValues { it.kind == WorkflowGraphNodeKind.END }.keys)
        edges.retainAll { it.from in nodes && it.to in nodes }
    }

    fun build() = WorkflowGraph(nodes.values.toList(), edges.toList())
}
