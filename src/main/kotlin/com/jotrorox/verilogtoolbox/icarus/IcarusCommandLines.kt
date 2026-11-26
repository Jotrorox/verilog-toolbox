package com.jotrorox.verilogtoolbox.icarus

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.execution.ParametersListUtil
import com.jotrorox.verilogtoolbox.common.FileList
import com.jotrorox.verilogtoolbox.common.VerilogDesign
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/** Builds the compile and simulate command lines of a run configuration. */
object IcarusCommandLines {
    /** Extensions `iverilog` is given to compile; `.vh` and `.svh` are headers that only get included. */
    private val SOURCE_EXTENSIONS = setOf("v", "sv")

    /** slang looks library modules up as `<module>.v` and `<module>.sv`, `iverilog` only as `<module>.v`. */
    private const val SYSTEM_VERILOG_EXTENSION = ".sv"

    /** A package declaration at the start of a line; one in a comment is taken for one too, which only moves the file. */
    private val PACKAGE_DECLARATION = Regex("""^\s*package\s+(?:(?:static|automatic)\s+)?[A-Za-z_]""", RegexOption.MULTILINE)

    /** [report] receives lines about the design that is compiled, for the console. */
    @Throws(ExecutionException::class)
    fun create(configuration: IcarusRunConfiguration, report: (String) -> Unit = {}): List<SimulationStep> {
        val toolchain = ToolchainSettings.getInstance()
        val iverilog = toolchain.findIverilog() ?: throw ExecutionException("iverilog was not found.")
        val vvp = toolchain.findVvp() ?: throw ExecutionException("vvp was not found.")
        val settings = configuration.settings
        val testbench = configuration.testbenchFile?.takeIf { it.isRegularFile() }
            ?: throw ExecutionException("Testbench file '${settings.testbench.orEmpty()}' does not exist.")
        val workingDirectory = configuration.workingDirectory?.takeIf { Files.isDirectory(it) }
            ?: throw ExecutionException("The working directory does not exist.")
        val simulationDirectory = configuration.simulationDirectory ?: workingDirectory
        if (simulationDirectory != workingDirectory) {
            try {
                Files.createDirectories(simulationDirectory)
            } catch (e: IOException) {
                throw ExecutionException("Cannot create '$simulationDirectory': ${e.message}", e)
            }
            // Said because $readmemh and $fopen look here too, which the sources may not expect.
            report("Simulating in $simulationDirectory, where relative paths of the testbench start")
        }

        val topModule = configuration.resolveTopModule()
        val design = compiledDesign(configuration)

        val compile = ToolchainSettings.toolCommandLine(iverilog).withWorkingDirectory(workingDirectory)
        compile.addParameters(ParametersListUtil.parse(settings.compilerOptions.orEmpty()))
        compile.addParameters("-o", design.toString())
        val fileList = configuration.fileList
        if (fileList != null) {
            // The list is the whole design: nothing is added from a scan of the project.
            compile.addParameters(designArguments(readFileList(fileList, configuration.project, report), testbench, topModule))
        } else {
            val libraries = libraryDirectories(configuration.project)
            if (topModule != null) compile.addParameters("-s", topModule)
            for (directory in libraries) compile.addParameters("-I", directory.toString(), "-y", directory.toString())
            // Library modules are looked up as <module>.v; look for <module>.sv as well.
            if (libraries.isNotEmpty()) compile.addParameters("-Y", SYSTEM_VERILOG_EXTENSION)
            compile.addParameters(sources(testbench, workingDirectory, settings.sources.orEmpty()).map { it.toString() })
        }

        // vvp cannot be told where a relative $dumpfile goes, so it runs where the dump belongs.
        val simulate = ToolchainSettings.toolCommandLine(vvp).withWorkingDirectory(simulationDirectory)
        simulate.addParameters(ParametersListUtil.parse(settings.simulatorOptions.orEmpty()))
        simulate.addParameter(design.toString())
        simulate.addParameters(ParametersListUtil.parse(settings.simulationArguments.orEmpty()))
        return listOf(SimulationStep(compile), SimulationStep(simulate))
    }

    private fun readFileList(file: Path, project: Project, report: (String) -> Unit): FileList {
        if (!file.isRegularFile()) throw ExecutionException("File list '$file' does not exist.")
        val design = FileList.parse(file, FileList.baseDirectory(file, project.basePath?.let { Path.of(it) }))
        if (design.errors.isNotEmpty()) throw ExecutionException(design.errors.joinToString("\n"))
        report("Compiling the design of file list $file")
        if (design.ignored.isNotEmpty()) {
            report("Options of the file list that are not passed to iverilog: ${design.ignored.joinToString(" ")}")
        }
        return design
    }

    /**
     * The `iverilog` arguments that describe [design]. The [testbench] is compiled even if the list leaves
     * it out, last because it depends on the rest. [topModule], when known, is simulated in place of the
     * tops the list names: those are the tops of the design, which need not be the testbench being run.
     */
    internal fun designArguments(design: FileList, testbench: Path, topModule: String?): List<String> = buildList {
        for (top in topModule?.let { listOf(it) } ?: design.topModules) addAll(listOf("-s", top))
        for (directory in design.includeDirectories) addAll(listOf("-I", directory.toString()))
        for (define in design.defines) addAll(listOf("-D", define))
        for (directory in design.libraryDirectories) addAll(listOf("-y", directory.toString()))
        if (design.libraryDirectories.isNotEmpty()) {
            // -Y adds to the .v that iverilog looks for by default.
            for (extension in (listOf(SYSTEM_VERILOG_EXTENSION) + design.libraryExtensions).distinct() - ".v") {
                addAll(listOf("-Y", extension))
            }
        }
        // Like slang's -v: compiled, but its modules are only used where the design instantiates them.
        for (file in design.libraryFiles) addAll(listOf("-l", file.toString()))
        val testbenchPath = testbench.toAbsolutePath().normalize()
        design.sources.plusElement(testbenchPath).distinct().forEach { add(it.toString()) }
    }

    /**
     * The testbench plus either the [configured] sources, in their order, or the other sources in the
     * testbench's directory. Of those, the ones that declare a package come before everything else:
     * iverilog has to compile a package before a file that imports it.
     */
    internal fun sources(testbench: Path, workingDirectory: Path, configured: String): List<Path> {
        val sources =
            if (configured.isBlank()) {
                val others = testbench.parent?.listDirectoryEntries()
                    ?.filter { it.isRegularFile() && it.extension.lowercase() in SOURCE_EXTENSIONS }?.sorted().orEmpty()
                val (packages, rest) = others.partition { declaresPackage(it) }
                // A Path is itself a list of its names, so it is wrapped to be added as one.
                packages + listOf(testbench) + rest
            } else {
                listOf(testbench) + ParametersListUtil.parse(configured).map { workingDirectory.resolve(it).normalize() }
            }
        return sources.distinctBy { it.toAbsolutePath().normalize() }
    }

    private fun declaresPackage(file: Path): Boolean =
        try {
            PACKAGE_DECLARATION.containsMatchIn(Files.readString(file))
        } catch (_: IOException) {
            false // Not readable as text; the compiler will say so.
        }

    /** The compiled design is an intermediate file, kept out of the project. */
    private fun compiledDesign(configuration: IcarusRunConfiguration): Path {
        val directory = Path.of(FileUtil.getTempDirectory(), "verilog-toolbox", configuration.project.locationHash)
        try {
            Files.createDirectories(directory)
        } catch (e: IOException) {
            throw ExecutionException("Cannot create '$directory': ${e.message}", e)
        }
        return directory.resolve(FileUtil.sanitizeFileName(configuration.name) + ".vvp")
    }

    /** Every project directory with Verilog files, searched for the modules and headers the sources refer to. */
    internal fun libraryDirectories(project: Project): List<Path> =
        ReadAction.nonBlocking(Callable { VerilogDesign.libraryDirectories(project) }).inSmartMode(project).executeSynchronously()
}
