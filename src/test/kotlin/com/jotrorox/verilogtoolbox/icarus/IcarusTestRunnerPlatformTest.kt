package com.jotrorox.verilogtoolbox.icarus

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.execution.ExecutionException
import com.intellij.execution.TestStateStorage
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jotrorox.verilogtoolbox.ProjectDirectoryState
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import java.nio.file.Files
import java.nio.file.Path

/** Runs go through the test runner's own event processing, which needs no window. */
class IcarusTestRunnerPlatformTest : BasePlatformTestCase() {
    private lateinit var directory: Path
    private lateinit var projectDirectory: ProjectDirectoryState

    override fun setUp() {
        super.setUp()
        projectDirectory = ProjectDirectoryState(project)
        directory = Files.createTempDirectory("icarus tests")
    }

    override fun tearDown() {
        try {
            projectDirectory.restore()
            NioFiles.deleteRecursively(directory)
            // CLion switches this on at startup, which the test framework reports as a setting the test damaged.
            CodeInsightSettings.getInstance().AUTO_POPUP_JAVADOC_INFO = CodeInsightSettings().AUTO_POPUP_JAVADOC_INFO
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun configuration(): IcarusRunConfiguration {
        val type = ConfigurationTypeUtil.findConfigurationType(IcarusConfigurationType::class.java)
        return (type.createTemplateConfiguration(project) as IcarusRunConfiguration).apply { name = "tests" }
    }

    private fun write(path: String, text: String): Path {
        val file = directory.resolve(path)
        Files.createDirectories(file.parent)
        return Files.writeString(file, text)
    }

    private fun console(handler: ProcessHandler): SMTRunnerConsoleView {
        val properties = SMTRunnerConsoleProperties(configuration(), "Icarus Verilog", DefaultRunExecutor.getRunExecutorInstance())
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole("Icarus Verilog", handler, properties) as SMTRunnerConsoleView
        Disposer.register(testRootDisposable, console)
        return console
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!condition()) {
            assertTrue("Timed out waiting until $what", System.currentTimeMillis() < deadline)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    /** The tree after the run of [handler] has ended, see [describe]. */
    private fun run(handler: ProcessHandler, console: SMTRunnerConsoleView = console(handler)): String {
        val root = console.resultsViewer.testsRootNode
        handler.startNotify()
        waitUntil("the run has ended") { handler.isProcessTerminated && !root.isInProgress }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return describe(root)
    }

    /** One line per node below [proxy]: indented name and, for a test, its state, message and details. */
    private fun describe(proxy: SMTestProxy, indent: String = ""): String =
        proxy.children.joinToString("") { child ->
            val state = when {
                child.isSuite -> ""
                else -> listOfNotNull(": " + child.magnitudeInfo.name, child.errorMessage, child.stacktrace?.trim()?.ifEmpty { null })
                    .joinToString(" | ")
            }
            "$indent${child.name}$state\n" + describe(child, "$indent  ")
        }

    private fun script(vararg steps: String): ((String) -> Unit) -> List<GeneralCommandLine> =
        { steps.map { GeneralCommandLine("/bin/sh", "-c", it) } }

    fun testEachTestIsANodeGroupedBySuite() {
        if (SystemInfo.isWindows) return
        val handler = IcarusTestsProcessHandler {
            listOf(
                IcarusTestbench("one", null, "a.sv", null, script("true", "echo ran")),
                IcarusTestbench("two", null, "a.sv", null, script("true", "printf 'ERROR: a.sv:7: q is 3'")),
                IcarusTestbench("three", null, "b.sv", null, script("true", "exit 3")),
                IcarusTestbench("four", null, null, null, script("true", "echo 'WARNING: a.sv:7: careful'")),
            )
        }
        assertEquals(
            """
            a.sv
              one: PASSED_INDEX
              two: FAILED_INDEX | q is 3 | at a.sv:7
            b.sv
              three: FAILED_INDEX | vvp exited with code 3
            four: PASSED_INDEX

            """.trimIndent(),
            run(handler),
        )
        assertEquals(1, handler.exitCode)
    }

    fun testCompilerOutputIsTheFailureAndTheSimulationIsSkipped() {
        if (SystemInfo.isWindows) return
        val ran = directory.resolve("ran")
        val handler = IcarusTestsProcessHandler {
            listOf(IcarusTestbench("tb", null, null, null, script("echo 'tb.sv:5: syntax error' >&2; exit 2", "touch '$ran'")))
        }
        assertEquals("tb: FAILED_INDEX | Compilation failed | tb.sv:5: syntax error\n", run(handler))
        assertFalse(Files.exists(ran))
    }

    fun testPassingRunExitsWithZero() {
        if (SystemInfo.isWindows) return
        val handler = IcarusTestsProcessHandler { listOf(IcarusTestbench("tb", null, null, null, script("true", "echo ran"))) }
        assertEquals("tb: PASSED_INDEX\n", run(handler))
        assertEquals(0, handler.exitCode)
    }

    fun testStepsThatCannotBeBuiltFailTheTest() {
        val handler = IcarusTestsProcessHandler {
            listOf(IcarusTestbench("tb", null, null, null, { throw ExecutionException("no compiler") }))
        }
        assertEquals("tb: FAILED_INDEX | no compiler\n", run(handler))
        assertEquals(1, handler.exitCode)
    }

    fun testStoppingEndsTheRunningTestAndStartsNoOther() {
        if (SystemInfo.isWindows) return
        val ran = directory.resolve("ran")
        val handler = IcarusTestsProcessHandler {
            listOf(
                IcarusTestbench("slow", null, null, null, script("true", "echo started; sleep 60")),
                IcarusTestbench("next", null, null, null, script("true", "touch '$ran'")),
            )
        }
        val console = console(handler)
        val root = console.resultsViewer.testsRootNode
        handler.startNotify()
        waitUntil("the first test runs") { root.children.singleOrNull()?.isInProgress == true }
        val start = System.currentTimeMillis()
        handler.destroyProcess()
        waitUntil("the run has ended") { handler.isProcessTerminated && !root.isInProgress }
        assertTrue("stopping did not end the simulation", System.currentTimeMillis() - start < 30_000)
        assertEquals("slow: TERMINATED_INDEX\n", describe(root))
        assertFalse(Files.exists(ran))
    }

    private fun runConfiguration(configuration: IcarusRunConfiguration, only: Set<String>? = null): String {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val result = IcarusRunState(configuration, only).execute(executor, ProgramRunner.getRunner(executor.id, configuration)!!)
        val console = result.executionConsole as SMTRunnerConsoleView
        Disposer.register(testRootDisposable, console)
        return run(result.processHandler, console)
    }

    private fun writeTestbenches() {
        write(
            "pass/tb_pass.sv",
            "module tb_first;\n  initial begin \$display(\"first\"); \$finish; end\nendmodule\n" +
                "module tb_second;\n  initial #1 \$warning(\"careful\");\nendmodule\n",
        )
        write("fail/tb_error.sv", "module tb_error;\n  reg a = 0;\n  initial #5 assert (a == 1) else \$error(\"a is %b\", a);\nendmodule\n")
        write("fail/deep/tb_fatal.sv", "module tb_fatal;\n  initial #5 \$fatal(1, \"boom\");\nendmodule\n")
        write("broken/tb_broken.sv", "module tb_broken;\n  initial \$display(\"ran\")\nendmodule\n")
        write("rtl/counter.sv", "module counter(input logic clk);\nendmodule\n")
    }

    fun testDirectoryRunsEveryTestbenchAsATest() {
        if (ToolchainSettings.getInstance().findIverilog() == null) return // Icarus is not installed here.
        writeTestbenches()
        val configuration = configuration()
        configuration.settings.directory = directory.toString()
        configuration.checkConfiguration()
        assertEquals(
            """
            broken/tb_broken.sv
              tb_broken: FAILED_INDEX | Compilation failed | $directory/broken/tb_broken.sv:3: syntax error
            I give up.
            fail/deep/tb_fatal.sv
              tb_fatal: FAILED_INDEX | boom | at $directory/fail/deep/tb_fatal.sv:2
            fail/tb_error.sv
              tb_error: FAILED_INDEX | a is 0 | at $directory/fail/tb_error.sv:3
            pass/tb_pass.sv
              tb_first: PASSED_INDEX
              tb_second: PASSED_INDEX

            """.trimIndent(),
            runConfiguration(configuration),
        )
        // The configuration itself still runs the directory.
        assertEquals(directory, configuration.testbenchDirectory)
        assertEquals("tests", configuration.name)

        val failed = VerilogTestLocator.url(directory.resolve("fail/tb_error.sv"), "tb_error")
        // The gutter icon of a testbench shows the result remembered under its URL.
        assertNotNull(TestStateStorage.getInstance(project).getState(failed))
        assertEquals(
            "fail/tb_error.sv\n  tb_error: FAILED_INDEX | a is 0 | at $directory/fail/tb_error.sv:3\n",
            runConfiguration(configuration, setOf(failed)),
        )
    }

    fun testSingleTestbenchIsOneTestWithoutASuite() {
        if (ToolchainSettings.getInstance().findIverilog() == null) return // Icarus is not installed here.
        writeTestbenches()
        val configuration = configuration()
        configuration.settings.testbench = directory.resolve("pass/tb_pass.sv").toString()
        assertEquals("tb_first: PASSED_INDEX\n", runConfiguration(configuration))
        configuration.settings.topModule = "tb_second"
        assertEquals("tb_second: PASSED_INDEX\n", runConfiguration(configuration))
    }

    fun testDirectoryWithoutTestbenchesFailsTheRun() {
        write("rtl/counter.sv", "module counter(input logic clk);\nendmodule\n")
        val configuration = configuration()
        configuration.settings.directory = directory.toString()
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val result = IcarusRunState(configuration).execute(executor, ProgramRunner.getRunner(executor.id, configuration)!!)
        Disposer.register(testRootDisposable, result.executionConsole)
        assertEquals("", run(result.processHandler, result.executionConsole as SMTRunnerConsoleView))
        assertEquals(1, result.processHandler.exitCode)
    }

    fun testTreeNodesLeadToTheModule() {
        val file = write("tb.sv", "module dut(input a);\nendmodule\nmodule tb;\nendmodule\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)
        val url = VerilogTestLocator.url(file, "tb")
        val location = VerilogTestLocator.getLocation(
            url.substringBefore("://"), url.substringAfter("://"), project, GlobalSearchScope.allScope(project),
        ).single()
        assertEquals("tb", location.psiElement.text)
        assertEquals(Files.readString(file).lastIndexOf("tb"), location.psiElement.textRange.startOffset)
        val suite = VerilogTestLocator.fileUrl(file)
        val fileLocation = VerilogTestLocator.getLocation(
            suite.substringBefore("://"), suite.substringAfter("://"), project, GlobalSearchScope.allScope(project),
        ).single()
        assertEquals("tb.sv", fileLocation.psiElement.containingFile.name)
    }

    private fun contextOf(path: Path): ConfigurationContext {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val manager = PsiManager.getInstance(project)
        return ConfigurationContext(if (file.isDirectory) manager.findDirectory(file)!! else manager.findFile(file)!!)
    }

    fun testDirectoryWithTestbenchesOffersToRunThemAll() {
        writeTestbenches()
        val producer = RunConfigurationProducer.getInstance(IcarusRunConfigurationProducer::class.java)
        val all = producer.createConfigurationFromContext(contextOf(directory.resolve("fail")))!!.configuration as IcarusRunConfiguration
        assertEquals(directory.resolve("fail"), all.testbenchDirectory)
        assertEquals("Testbenches in fail", all.name)
        assertNull(producer.createConfigurationFromContext(contextOf(directory.resolve("rtl"))))

        val single = producer.createConfigurationFromContext(contextOf(directory.resolve("fail/tb_error.sv")))!!
            .configuration as IcarusRunConfiguration
        assertNull(single.testbenchDirectory)
        assertEquals("tb_error", single.settings.topModule)

        assertTrue(producer.isConfigurationFromContext(all, contextOf(directory.resolve("fail"))))
        assertFalse(producer.isConfigurationFromContext(all, contextOf(directory.resolve("pass"))))
        assertFalse(producer.isConfigurationFromContext(all, contextOf(directory.resolve("fail/tb_error.sv"))))
        assertFalse(producer.isConfigurationFromContext(single, contextOf(directory.resolve("fail"))))
        assertTrue(producer.isConfigurationFromContext(single, contextOf(directory.resolve("fail/tb_error.sv"))))
    }
}
