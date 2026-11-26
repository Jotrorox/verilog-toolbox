package com.jotrorox.verilogtoolbox.sby

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.execution.TestStateStorage
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class SbyPlatformTest : BasePlatformTestCase() {
    private lateinit var directory: Path

    override fun setUp() {
        super.setUp()
        directory = Files.createTempDirectory("sby test").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.toString())
    }

    override fun tearDown() {
        try {
            NioFiles.deleteRecursively(directory)
            // CLion switches this on at startup, which the test framework reports as a setting the test damaged.
            CodeInsightSettings.getInstance().AUTO_POPUP_JAVADOC_INFO = CodeInsightSettings().AUTO_POPUP_JAVADOC_INFO
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private val counterSby = """
        [tasks]
        bmc
        cover

        [options]
        bmc: mode bmc
        cover: mode cover
        depth 10

        [engines]
        smtbmc yices

        [script]
        read -formal counter.sv
        prep -top counter

        [files]
        rtl/counter.sv
    """.trimIndent() + "\n"

    private val counterSv = """
        module counter (
            input  logic       clk,
            input  logic       rst,
            output logic [3:0] count
        );
            always_ff @(posedge clk)
                if (rst) count <= 0;
                else count <= count + 1;

        `ifdef FORMAL
            logic past_valid = 0;
            always_ff @(posedge clk) past_valid <= 1;
            always_comb if (!past_valid) assume (rst);
            always_ff @(posedge clk) if (past_valid) assert (count != 4'd5);
            always_ff @(posedge clk) cover (count == 4'd3);
        `endif
        endmodule
    """.trimIndent() + "\n"

    private fun write(path: String, text: String): Path {
        val file = directory.resolve(path)
        Files.createDirectories(file.parent)
        return Files.writeString(file, text).also { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
    }

    private fun configuration(sbyFile: Path): SbyRunConfiguration {
        val type = ConfigurationTypeUtil.findConfigurationType(SbyConfigurationType::class.java)
        return (type.createTemplateConfiguration(project) as SbyRunConfiguration).apply {
            name = "counter"
            settings.sbyFile = sbyFile.toString()
        }
    }

    fun testFileType() {
        assertEquals(SbyFileType, FileTypeManager.getInstance().getFileTypeByFileName("counter.sby"))
    }

    fun testHighlighting() {
        myFixture.configureByText("counter.sby", counterSby)
        assertEquals(SbyLanguage, myFixture.file.language)
        assertHighlight("[tasks]", SbyTokenTypes.SECTION, SbyColors.SECTION)
        assertHighlight("bmc\n", SbyTokenTypes.TASK, SbyColors.TASK)
        assertHighlight("bmc: mode", SbyTokenTypes.TAG, SbyColors.TAG)
        assertHighlight("mode bmc", SbyTokenTypes.OPTION, SbyColors.OPTION)
        assertHighlight("smtbmc", SbyTokenTypes.ENGINE, SbyColors.ENGINE)
        assertHighlight("read", SbyTokenTypes.COMMAND, SbyColors.COMMAND)
        assertHighlight("-formal", SbyTokenTypes.FLAG, SbyColors.FLAG)
        assertHighlight("rtl/counter.sv", SbyTokenTypes.PATH, SbyColors.PATH)
    }

    /** The editor's own highlighter, found through the file type, gives the token at [text] its colour. */
    private fun assertHighlight(text: String, type: IElementType, key: TextAttributesKey) {
        val offset = myFixture.editor.document.text.indexOf(text)
        assertTrue(text, offset >= 0)
        val iterator = (myFixture.editor as EditorEx).highlighter.createIterator(offset)
        assertEquals(text, type, iterator.tokenType)
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(myFixture.file.fileType, project, null)!!
        assertTrue(text, key in highlighter.getTokenHighlights(type))
    }

    fun testCommentLineWithHash() {
        myFixture.configureByText("counter.sby", "[options]\n<caret>mode bmc\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        assertEquals("[options]\n#mode bmc\n", myFixture.editor.document.text)
    }

    fun testRunIconsOnTheTasksAndTheTasksHeader() {
        myFixture.configureByText("counter.sby", counterSby)
        assertEquals(listOf(0 to "Run the default tasks", 1 to "Run task 'bmc'", 2 to "Run task 'cover'"), gutters())
        myFixture.configureByText("plain.sby", "# no tasks\n[options]\nmode bmc\n\n[engines]\nsmtbmc\n")
        assertEquals(listOf(1 to "Run the default tasks"), gutters())
    }

    private fun gutters(): List<Pair<Int, String?>> =
        myFixture.findAllGutters()
            .filterIsInstance<LineMarkerInfo.LineMarkerGutterIconRenderer<*>>()
            .filter { it.tooltipText?.startsWith("Run ") == true }
            .map { myFixture.editor.document.getLineNumber(it.lineMarkerInfo.startOffset) to it.tooltipText }
            .sortedBy { it.first }

    fun testProducerRunsATaskFromItsLineAndTheDefaultTasksFromElsewhere() {
        val file = write("counter.sby", counterSby)
        val psi = PsiManager.getInstance(project).findFile(LocalFileSystem.getInstance().findFileByNioFile(file)!!)!!
        val producer = RunConfigurationProducer.getInstance(SbyRunConfigurationProducer::class.java)

        val all = producer.createConfigurationFromContext(ConfigurationContext(psi))!!.configuration as SbyRunConfiguration
        assertEquals("counter", all.name)
        assertEquals(emptyList<String>(), all.taskNames)

        val bmcName = psi.findElementAt(counterSby.indexOf("bmc"))!!
        val bmc = producer.createConfigurationFromContext(ConfigurationContext(bmcName))!!.configuration as SbyRunConfiguration
        assertEquals("counter: bmc", bmc.name)
        assertEquals(listOf("bmc"), bmc.taskNames)
        assertTrue(producer.isConfigurationFromContext(bmc, ConfigurationContext(bmcName)))
        assertFalse(producer.isConfigurationFromContext(all, ConfigurationContext(bmcName)))
        assertTrue(producer.isConfigurationFromContext(all, ConfigurationContext(psi)))
    }

    fun testTasksAreFoundFromTheirDirectories() {
        val configuration = configuration(write("counter.sby", counterSby))
        assertEquals("bmc", configuration.taskName("counter_bmc"))
        assertNull(configuration.taskName("counter"))
        assertEquals("counter", configuration.taskOf("counter").name)
        assertEquals(SbyTestLocator.url(directory.resolve("counter.sby"), "bmc"), configuration.taskOf("counter_bmc").locationUrl)
        configuration.settings.options = "-f --prefix out/run"
        assertEquals("cover", configuration.taskName("out/run_cover"))
        assertEquals("cover", configuration.taskName("out\\run_cover"))
        configuration.settings.options = "-f -d here"
        configuration.settings.tasks = "bmc"
        assertEquals("bmc", configuration.taskName("here"))
        // Run from elsewhere, the file is given with its path, and the directories are next to it.
        configuration.settings.options = "-f"
        configuration.settings.workingDirectory = Files.createDirectories(directory.resolve("elsewhere")).toString()
        assertEquals("bmc", configuration.taskName(directory.resolve("counter_bmc").toString()))
    }

    fun testCheckingTheConfiguration() {
        val configuration = configuration(write("counter.sby", counterSby))
        configuration.settings.tasks = "bmc prove"
        val error = runCatching { configuration.checkConfiguration() }.exceptionOrNull()
        assertEquals("counter.sby has no task 'prove'. It has 'bmc', 'cover'.", error?.message)
    }

    fun testTreeNodesLeadToTheTask() {
        val file = write("counter.sby", counterSby)
        val url = SbyTestLocator.url(file, "cover")
        val location = SbyTestLocator.getLocation(
            url.substringBefore("://"), url.substringAfter("://"), project, GlobalSearchScope.allScope(project),
        ).single()
        assertEquals("cover", location.psiElement.text)
        assertEquals(counterSby.indexOf("cover"), location.psiElement.textRange.startOffset)
    }

    private fun console(handler: ProcessHandler, configuration: SbyRunConfiguration): SMTRunnerConsoleView {
        val properties = SMTRunnerConsoleProperties(configuration, "SymbiYosys", DefaultRunExecutor.getRunExecutorInstance())
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole("SymbiYosys", handler, properties) as SMTRunnerConsoleView
        Disposer.register(testRootDisposable, console)
        return console
    }

    private fun waitUntil(what: String, timeoutMillis: Long = 60_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            assertTrue("Timed out waiting until $what", System.currentTimeMillis() < deadline)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    /** The tree after the run of [handler] has ended: per task its state and failure message. */
    private fun run(handler: ProcessHandler, console: SMTRunnerConsoleView, timeoutMillis: Long = 60_000): String {
        val root = console.resultsViewer.testsRootNode
        handler.startNotify()
        waitUntil("the run has ended", timeoutMillis) { handler.isProcessTerminated && !root.isInProgress }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return describe(root)
    }

    private fun describe(root: SMTestProxy): String =
        root.children.joinToString("") { child ->
            listOfNotNull(child.name + ": " + child.magnitudeInfo.name, child.errorMessage).joinToString(" | ") + "\n"
        }

    /** A command that prints a log SymbiYosys wrote, as if it ran again. */
    private fun replay(log: String): GeneralCommandLine {
        val file = directory.resolve("$log.log")
        javaClass.getResourceAsStream("/sby/$log.log")!!.use { Files.copy(it, file) }
        return if (SystemInfo.isWindows) GeneralCommandLine("cmd", "/c", "type", file.toString())
        else GeneralCommandLine("cat", file.toString())
    }

    fun testEachTaskIsATest() {
        val configuration = configuration(write("counter.sby", counterSby))
        val handler = SbyProcessHandler(replay("tasks"), configuration::taskOf, configuration.runName)
        val console = console(handler, configuration)
        assertEquals(
            """
            bmc: FAILED_INDEX | Assertion failed at counter.sv:14.46-14.68 in step 7
            prove: FAILED_INDEX | Assertion failed at counter.sv:14.46-14.68 in step 7
            cover: PASSED_INDEX

            """.trimIndent(),
            run(handler, console),
        )
        assertEquals("counter_cover\\engine_0\\trace0.vcd", handler.result("cover")!!.mainTrace)
        // The lines of a task are its output.
        val bmc = console.resultsViewer.testsRootNode.children.first()
        assertTrue(bmc.locationUrl, bmc.locationUrl!!.endsWith("counter.sby#bmc"))
    }

    fun testARunThatFailsBeforeAnyTaskIsOneFailedTest() {
        val configuration = configuration(write("counter.sby", counterSby))
        val command = if (SystemInfo.isWindows) GeneralCommandLine("cmd", "/c", "echo ERROR: Task name 'x' didn't match any lines in [tasks].& exit /b 1")
        else GeneralCommandLine("/bin/sh", "-c", "echo \"ERROR: Task name 'x' didn't match any lines in [tasks].\"; exit 1")
        val handler = SbyProcessHandler(command, configuration::taskOf, configuration.runName)
        assertEquals(
            "counter: FAILED_INDEX | Task name 'x' didn't match any lines in [tasks].\n",
            run(handler, console(handler, configuration)),
        )
    }

    fun testATaskCutShortFails() {
        val configuration = configuration(write("counter.sby", counterSby))
        val command = if (SystemInfo.isWindows) GeneralCommandLine("cmd", "/c", "echo SBY 19:11:21 [counter_bmc] engine_0: smtbmc& exit /b 1")
        else GeneralCommandLine("/bin/sh", "-c", "echo 'SBY 19:11:21 [counter_bmc] engine_0: smtbmc'; exit 1")
        val handler = SbyProcessHandler(command, configuration::taskOf, configuration.runName)
        assertEquals(
            "bmc: FAILED_INDEX | SymbiYosys ended before the task was done (exit code 1)\n",
            run(handler, console(handler, configuration)),
        )
    }

    /**
     * Runs [test] with SymbiYosys when it is found, as in the OSS CAD Suite the plugin downloads, or where
     * `VERILOG_TOOLBOX_SBY` points; does nothing otherwise.
     */
    private fun withSby(test: () -> Unit) {
        val toolchain = ToolchainSettings.getInstance()
        val configured = toolchain.sbyPath
        System.getenv("VERILOG_TOOLBOX_SBY")?.let { toolchain.sbyPath = it }
        try {
            if (toolchain.findSby() != null) test()
        } finally {
            toolchain.sbyPath = configured
        }
    }

    private fun execute(configuration: SbyRunConfiguration): Pair<SbyProcessHandler, SMTRunnerConsoleView> {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val result = SbyRunState(configuration).execute(executor, ProgramRunner.getRunner(executor.id, configuration)!!)
        val console = result.executionConsole as SMTRunnerConsoleView
        Disposer.register(testRootDisposable, console)
        return result.processHandler as SbyProcessHandler to console
    }

    fun testRealRun() = withSby {
        write("rtl/counter.sv", counterSv)
        val sbyFile = write("counter.sby", counterSby)
        val configuration = configuration(sbyFile)
        configuration.checkConfiguration()
        val (handler, console) = execute(configuration)
        // The tasks run in parallel, so either may start first.
        assertEquals(
            listOf("bmc: FAILED_INDEX | Assertion failed at counter.sv:14.46-14.68 in step 7", "cover: PASSED_INDEX"),
            run(handler, console, timeoutMillis = 600_000).lines().filter { it.isNotEmpty() }.sorted(),
        )
        assertEquals(2, handler.exitCode)
        assertTrue(Files.isRegularFile(directory.resolve(handler.result("bmc")!!.mainTrace!!)))
        assertTrue(Files.isRegularFile(directory.resolve(handler.result("cover")!!.mainTrace!!)))
        // The gutter icon of a task shows the result remembered under its URL.
        val url = SbyTestLocator.url(sbyFile, "bmc")
        waitUntil("the result is remembered") { TestStateStorage.getInstance(project).getState(url) != null }

        // Rerun Failed Tests runs the task that failed, and only that one.
        val failed = console.resultsViewer.testsRootNode.allTests.filter { it.isLeaf && it.isDefect }.map { it.name }
        val rerun = SbyRunState.rerunConfiguration(configuration, failed)
        assertEquals(listOf("bmc"), rerun.taskNames)
        val (again, againConsole) = execute(rerun)
        assertEquals(
            "bmc: FAILED_INDEX | Assertion failed at counter.sv:14.46-14.68 in step 7\n",
            run(again, againConsole, timeoutMillis = 600_000),
        )
        // The configuration itself still runs the default tasks.
        assertEquals(emptyList<String>(), configuration.taskNames)
    }

    /** Stopping ends the solvers too, which on Windows would otherwise keep the task's directory locked. */
    fun testStoppingARealRun() = withSby {
        // An assertion that holds for 65535 steps, checked to a depth that takes a while.
        write(
            "rtl/counter.sv",
            counterSv.replace("[3:0]", "[15:0]").replace("assert (count != 4'd5)", "assert (count != 16'hFFFF)")
                .replace("cover (count == 4'd3)", "cover (count == 16'd3)"),
        )
        val sbyFile = write("counter.sby", counterSby.replace("depth 10", "depth 2000"))
        val configuration = configuration(sbyFile).apply { settings.tasks = "bmc" }
        val (handler, console) = execute(configuration)
        val root = console.resultsViewer.testsRootNode
        val started = Instant.now()
        val output = StringBuffer()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                output.append(event.text)
            }
        })
        handler.startNotify()
        waitUntil("the engine runs", 600_000) { handler.isProcessTerminated || ENGINE_STARTED in output }
        Thread.sleep(2_000)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue("the check ended before it could be stopped", root.children.singleOrNull()?.isInProgress == true)
        val stop = System.currentTimeMillis()
        handler.destroyProcess()
        waitUntil("the run has ended") { handler.isProcessTerminated && !root.isInProgress }
        assertTrue("stopping took too long", System.currentTimeMillis() - stop < 30_000)
        assertEquals("bmc: TERMINATED_INDEX\n", describe(root))
        val solvers = ProcessHandle.allProcesses().filter { process ->
            val info = process.info()
            val command = info.command().orElse("").replace('\\', '/').substringAfterLast('/')
            info.startInstant().map { !it.isBefore(started) }.orElse(false) &&
                listOf("yosys", "yices", "python").any { it in command }
        }.toList()
        assertEquals("processes left running", emptyList<String>(), solvers.map { it.info().command().orElse("?") })
    }

    private companion object {
        const val ENGINE_STARTED = "engine_0: starting process"
    }
}
