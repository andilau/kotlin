/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.daemon

import com.intellij.openapi.Disposable
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.extensions.ReplFactoryExtension
import org.jetbrains.kotlin.cli.common.messages.*
import org.jetbrains.kotlin.cli.common.repl.*
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.plugins.ServiceLoaderLite
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.daemon.common.CompileService
import org.jetbrains.kotlin.daemon.common.CompilerId
import org.jetbrains.kotlin.daemon.common.RemoteOperationsTracer
import org.jetbrains.kotlin.utils.PathUtil
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.logging.Logger
import kotlin.concurrent.read
import kotlin.concurrent.write

abstract class KotlinJvmReplServiceBase(
    protected val replCompiler: ReplCompiler?
) : ReplCompileAction, ReplCheckAction, CreateReplStageStateAction {

    protected val statesLock = ReentrantReadWriteLock()
    protected val stateIdCounter = AtomicInteger()

    override fun createState(lock: ReentrantReadWriteLock): IReplStageState<*> =
        replCompiler?.createState(lock) ?: throw IllegalStateException("repl compiler is not initialized properly")

    protected open fun before(s: String) {}
    protected open fun after(s: String) {}

    override fun check(state: IReplStageState<*>, codeLine: ReplCodeLine): ReplCheckResult {
        before("check")
        try {
            return replCompiler?.check(state, codeLine) ?: ReplCheckResult.Error("Initialization error")
        } finally {
            after("check")
        }
    }

    override fun compile(state: IReplStageState<*>, codeLine: ReplCodeLine): ReplCompileResult {
        before("compile")
        try {
            return replCompiler?.compile(state, codeLine) ?: ReplCompileResult.Error("Initialization error")
        } finally {
            after("compile")
        }
    }

}

fun makeLegacyReplCompiler(
    disposable: Disposable,
    compilerId: CompilerId,
    templateClasspath: List<File>,
    templateClassName: String,
    messageCollector: MessageCollector
): ReplCompiler? {

    val configuration = CompilerConfiguration().apply {
        put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, messageCollector)
        addJvmClasspathRoots(PathUtil.kotlinPathsForCompiler.let { listOf(it.stdlibPath, it.reflectPath, it.scriptRuntimePath) })
        addJvmClasspathRoots(templateClasspath)
        put(CommonConfigurationKeys.MODULE_NAME, "kotlin-script")
        languageVersionSettings = LanguageVersionSettingsImpl(
            LanguageVersion.LATEST_STABLE, ApiVersion.LATEST_STABLE, mapOf(AnalysisFlags.skipMetadataVersionCheck to true)
        )
        configureScripting(compilerId)
    }

    return try {
        val projectEnvironment =
            KotlinCoreEnvironment.ProjectEnvironment(
                disposable,
                KotlinCoreEnvironment.getOrCreateApplicationEnvironmentForProduction(disposable, configuration)
            )
        ReplFactoryExtension.registerExtensionPoint(projectEnvironment.project)
        projectEnvironment.registerExtensionsFromPlugins(configuration)
        val replFactories = ReplFactoryExtension.getInstances(projectEnvironment.project)
        if (replFactories.isEmpty()) {
            throw java.lang.IllegalStateException("no scripting plugin loaded")
        } else if (replFactories.size > 1) {
            throw java.lang.IllegalStateException("several scripting plugins loaded")
        }

        replFactories.first().makeReplCompiler(
            templateClassName,
            templateClasspath,
            KotlinJvmReplServiceBase::class.java.classLoader,
            configuration,
            projectEnvironment
        )
    } catch (ex: Throwable) {
        messageCollector.report(CompilerMessageSeverity.ERROR, "Unable to construct repl compiler: ${ex.message}")
        throw IllegalStateException("Unable to use scripting/REPL in the daemon: ${ex.message}", ex)
    }
}

open class KotlinJvmReplService(
    val portForServers: Int,
    replCompiler: ReplCompiler?,
    @Deprecated("drop it")
    protected val operationsTracer: RemoteOperationsTracer?
) : KotlinJvmReplServiceBase(replCompiler) {

    override fun before(s: String) {
        operationsTracer?.before(s)
    }

    override fun after(s: String) {
        operationsTracer?.after(s)
    }

    protected val states = WeakHashMap<RemoteReplStateFacadeServer, Boolean>() // used as (missing) WeakHashSet
    @Deprecated("remove after removal state-less check/compile/eval methods")
    protected val defaultStateFacade: RemoteReplStateFacadeServer by lazy { createRemoteState() }

    @Deprecated("Use check(state, line) instead")
    fun check(codeLine: ReplCodeLine): ReplCheckResult = check(defaultStateFacade.state, codeLine)

    @Deprecated("Use compile(state, line) instead")
    fun compile(codeLine: ReplCodeLine, verifyHistory: List<ReplCodeLine>?): ReplCompileResult = compile(defaultStateFacade.state, codeLine)

    fun createRemoteState(port: Int = portForServers): RemoteReplStateFacadeServer = statesLock.write {
        val id = getValidId(stateIdCounter) { id -> states.none { it.key.getId() == id } }
        val stateFacade = RemoteReplStateFacadeServer(id, createState(), port)
        states.put(stateFacade, true)
        stateFacade
    }

    fun <R> withValidReplState(stateId: Int, body: (IReplStageState<*>) -> R): CompileService.CallResult<R> = statesLock.read {
        states.keys.firstOrNull { it.getId() == stateId }?.let {
            CompileService.CallResult.Good(body(it.state))
        }
            ?: CompileService.CallResult.Error("No REPL state with id $stateId found")
    }
}

internal class KeepFirstErrorMessageCollector(compilerMessagesStream: PrintStream) : MessageCollector {

    private val innerCollector = PrintingMessageCollector(compilerMessagesStream, MessageRenderer.WITHOUT_PATHS, false)

    internal var firstErrorMessage: String? = null
    internal var firstErrorLocation: CompilerMessageLocation? = null

    override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageLocation?) {
        if (firstErrorMessage == null && severity.isError) {
            firstErrorMessage = message
            firstErrorLocation = location
        }
        innerCollector.report(severity, message, location)
    }

    override fun hasErrors(): Boolean = innerCollector.hasErrors()
    override fun clear() {
        innerCollector.clear()
    }
}

val internalRng = Random()

inline fun getValidId(counter: AtomicInteger, check: (Int) -> Boolean): Int {
    // fighting hypothetical integer wrapping
    var newId = counter.incrementAndGet()
    var attemptsLeft = 100
    while (!check(newId)) {
        attemptsLeft -= 1
        if (attemptsLeft <= 0)
            throw IllegalStateException("Invalid state or algorithm error")
        // assuming wrap, jumping to random number to reduce probability of further clashes
        newId = counter.addAndGet(internalRng.nextInt())
    }
    return newId
}

fun CompilerConfiguration.configureScripting(compilerId: CompilerId) {
    val error = try {
        val componentRegistrars =
            (this::class.java.classLoader as? URLClassLoader)?.let {
                ServiceLoaderLite.loadImplementations(ComponentRegistrar::class.java, it)
            } ?: ServiceLoaderLite.loadImplementations(
                ComponentRegistrar::class.java, compilerId.compilerClasspath.map(::File), this::class.java.classLoader
            )
        addAll(ComponentRegistrar.PLUGIN_COMPONENT_REGISTRARS, componentRegistrars)
        null
    } catch (e: NoClassDefFoundError) {
        e
    } catch (e: ClassNotFoundException) {
        e
    }
    if (error != null) {
        throw IllegalStateException(
            "Unable to use scripting/REPL in the daemon, no kotlin-scripting-compiler.jar or its dependencies are found in the compiler classpath",
            error
        )
    }
}