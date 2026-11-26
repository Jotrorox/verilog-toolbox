package com.jotrorox.verilogtoolbox.verilator

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import com.jotrorox.verilogtoolbox.common.FileList
import com.jotrorox.verilogtoolbox.common.VerilogDesign
import com.jotrorox.verilogtoolbox.icarus.BuildOutput
import com.jotrorox.verilogtoolbox.icarus.IcarusCommandLines
import com.jotrorox.verilogtoolbox.icarus.SimulationStep
import com.jotrorox.verilogtoolbox.icarus.VerilogTestbenches
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import com.jotrorox.verilogtoolbox.waveform.Waveforms
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/**
 * The steps of a Verilator simulation: verilate the design, write the CMake project from what Verilator generated,
 * configure and build it, and run the simulation.
 */
object VerilatorSimulation {
    private val SOURCE_EXTENSIONS = setOf("v", "sv")
    private val DUMP_EXTENSIONS = setOf("vcd", "fst", "saif")

    /** `$dumpfile("tb.vcd")`; the name decides between VCD and FST. */
    private val DUMPFILE = Regex("""\${'$'}dumpfile\s*\(\s*"([^"]+)"""")

    /** Clocks of the file system are coarse; a dump written in the second the simulation started counts. */
    private const val CLOCK_SLACK_MILLIS = 2_000

    /** The waveform a run wrote, once it has ended. */
    class Dump {
        @Volatile
        var file: Path? = null
    }

    /**
     * The steps for [configuration], built when the test starts; [report] takes lines for the console. [dump]
     * learns the waveform once the simulation has ended.
     */
    @Throws(ExecutionException::class)
    fun steps(configuration: VerilatorRunConfiguration, report: (String) -> Unit, dump: Dump): List<SimulationStep> {
        val verilator = ToolchainSettings.getInstance().findVerilator() ?: throw ExecutionException("Verilator was not found.")
        val installation = VerilatorInstallation.of(verilator)
        val toolchain = CppToolchain.find() ?: throw ExecutionException("CMake was not found.")
        val settings = configuration.settings
        val testbench = configuration.testbenchFile?.takeIf { it.isRegularFile() }
            ?: throw ExecutionException("Testbench file '${settings.testbench.orEmpty()}' does not exist.")
        val cpp = configuration.isCppTestbench
        val workingDirectory = configuration.workingDirectory?.takeIf { it.isDirectory() }
            ?: throw ExecutionException("The working directory does not exist.")
        val top = configuration.resolveTopModule() ?: throw ExecutionException(
            if (cpp) "The C++ testbench includes no Verilated model (#include \"V<module>.h\"); set its top module."
            else "'$testbench' has no testbench, a module without ports."
        )
        val name = configuration.simulationName ?: top
        val root = configuration.cmakeRoot ?: throw ExecutionException("The project has no directory for the CMake project.")
        val simulationDirectory = configuration.simulationDirectory ?: throw ExecutionException("No directory to simulate in.")
        val target = VerilatorCMakeProject.target(name)
        val modelDirectory = root.resolve(target).resolve("verilated")
        try {
            Files.createDirectories(modelDirectory)
            Files.createDirectories(simulationDirectory)
        } catch (e: IOException) {
            throw ExecutionException("Cannot create the directories of the simulation: ${e.message}", e)
        }
        if (simulationDirectory != workingDirectory) report("Simulating in $simulationDirectory, where relative paths of the testbench start")

        val testbenchText = runCatching { Files.readString(testbench) }.getOrDefault("")
        val prefix = "V" + VerilatorCMakeProject.target(top)
        val options = ParametersListUtil.parse(settings.verilatorOptions.orEmpty())
        val cppSources = if (!cpp) emptyList() else listOf(testbench.toAbsolutePath().normalize()) +
            ParametersListUtil.parse(settings.cppSources.orEmpty()).map { testbench.resolveSibling(it).normalize() }
        val arguments = buildList {
            addAll(listOf("--cc", "--make", if (installation.writesJson) "json" else "cmake"))
            addAll(listOf("--Mdir", modelDirectory.toString(), "--prefix", prefix, "--top", top))
            // A Verilog testbench needs a main() and its delays; a C++ testbench brings its own main().
            if (!cpp) addAll(listOf("--main", "--timing"))
            if (options.none { it.startsWith("--trace") }) addAll(traceOptions(testbenchText, cpp, installation.writesJson))
            addAll(options)
            addAll(designArguments(configuration, testbench, top, cpp, workingDirectory, report))
        }

        val verilate = SimulationStep(
            ToolchainSettings.toolCommandLine(verilator).withParameters(arguments).withWorkingDirectory(workingDirectory),
            output = BuildOutput.LIVE,
            failure = "Verilation failed",
        ) {
            val manifest = readManifest(installation, modelDirectory, prefix, workingDirectory)
            if (manifest.hierarchical) {
                throw ExecutionException("Hierarchical verilation (--hierarchical) cannot be built by the plugin yet.")
            }
            val simulation = VerilatorCMakeProject.Simulation(
                target, configuration.name, prefix, manifest, modelDirectory, installation, arguments, workingDirectory,
                cppSources, simulationDirectory, ParametersListUtil.parse(settings.simulationArguments.orEmpty()),
            )
            try {
                VerilatorCMakeProject.write(root.resolve(target).resolve("CMakeLists.txt"), VerilatorCMakeProject.simulationText(simulation))
                VerilatorCMakeProject.write(VerilatorCMakeProject.rootFile(root), VerilatorCMakeProject.rootText(root))
            } catch (e: IOException) {
                throw ExecutionException("Cannot write the CMake project in '$root': ${e.message}", e)
            }
        }

        val buildDirectory = root.resolve(VerilatorCMakeProject.BUILD_DIRECTORY)
        val configure = cmake(toolchain).withWorkingDirectory(root).withParameters(configureArguments(toolchain, root, buildDirectory))
        var simulationStarted = 0L
        val build = SimulationStep(
            cmake(toolchain).withWorkingDirectory(root).withParameters("--build", buildDirectory.toString(), "--target", target),
            output = BuildOutput.LIVE,
            failure = "C++ build failed",
        ) { simulationStarted = System.currentTimeMillis() }

        val executable = VerilatorCMakeProject.executable(root, target)
        val expectedDump = if (cpp) null else DUMPFILE.find(testbenchText)?.groupValues?.get(1)
            ?.let { runCatching { simulationDirectory.resolve(it).normalize() }.getOrNull() }
        val simulate = SimulationStep(
            withPath(GeneralCommandLine(executable.toString()), toolchain.pathDirectories)
                .withWorkingDirectory(simulationDirectory)
                .withParameters(ParametersListUtil.parse(settings.simulationArguments.orEmpty())),
        ) {
            val file = findDump(simulationDirectory, expectedDump, simulationStarted - CLOCK_SLACK_MILLIS)
            dump.file = file
            if (file != null) {
                report("$WAVEFORM_ANNOUNCEMENT$file")
                Waveforms.reloadIfOpen(file)
            }
        }
        return listOf(
            verilate,
            SimulationStep(configure, output = BuildOutput.ON_FAILURE, failure = "CMake configuration failed"),
            build,
            simulate,
        )
    }

    /** The console line that names the waveform a run wrote, which a filter turns into a link. */
    const val WAVEFORM_ANNOUNCEMENT = "Waveform written to "

    private fun cmake(toolchain: CppToolchain): GeneralCommandLine =
        withPath(GeneralCommandLine(toolchain.cmake.toString()).withCharset(Charsets.UTF_8), toolchain.pathDirectories)

    /** Configured once more every run: quick when nothing changed, and it takes in a simulation that is new. */
    internal fun configureArguments(toolchain: CppToolchain, root: Path, buildDirectory: Path): List<String> = buildList {
        addAll(listOf("-S", root.toString(), "-B", buildDirectory.toString()))
        toolchain.ninja?.let { addAll(listOf("-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=$it")) }
        // A simulation is worth optimizing: it is what Verilator is chosen for.
        add("-DCMAKE_BUILD_TYPE=Release")
        toolchain.cCompiler?.let { add("-DCMAKE_C_COMPILER=$it") }
        toolchain.cxxCompiler?.let { add("-DCMAKE_CXX_COMPILER=$it") }
    }

    private fun withPath(commandLine: GeneralCommandLine, directories: List<Path>): GeneralCommandLine {
        if (directories.isEmpty()) return commandLine
        val path = (directories.map { it.toString() } + listOfNotNull(System.getenv("PATH"))).joinToString(File.pathSeparator)
        return commandLine.withEnvironment("PATH", path)
    }

    /**
     * The waveform options for what the testbench dumps: a Verilog testbench's `$dumpfile` as FST when it is
     * called so, otherwise as VCD, which needs no library; a C++ testbench the format whose writer it includes.
     */
    internal fun traceOptions(testbench: String, cpp: Boolean, newOptions: Boolean): List<String> {
        val fst = if (cpp) "verilated_fst_c.h" in testbench else DUMPFILE.find(testbench)?.groupValues?.get(1)?.endsWith(".fst") == true
        val vcd = if (cpp) "verilated_vcd_c.h" in testbench else DUMPFILE.containsMatchIn(testbench) && !fst
        return when {
            fst -> listOf("--trace-fst")
            vcd -> listOf(if (newOptions) "--trace-vcd" else "--trace")
            else -> emptyList()
        }
    }

    /**
     * The Verilator arguments for the design: the file list's, with a Verilog [testbench] added when the list
     * leaves it out, or without a list the Verilog files of the testbench's directory, with every project directory
     * searched for the modules they use. A C++ testbench's design is found starting from [top].
     */
    @Throws(ExecutionException::class)
    private fun designArguments(
        configuration: VerilatorRunConfiguration, testbench: Path, top: String, cpp: Boolean, workingDirectory: Path,
        report: (String) -> Unit,
    ): List<String> {
        val project = configuration.project
        val fileList = configuration.fileList
        val design = if (fileList != null) {
            readFileList(fileList, project, report)
        } else {
            val directories = IcarusCommandLines.libraryDirectories(project)
            val sources = if (cpp) {
                moduleFiles(directories, top)
            } else {
                IcarusCommandLines.sources(testbench, workingDirectory, "")
            }
            FileList(
                sources = sources.map { it.toAbsolutePath().normalize() },
                includeDirectories = directories,
                defines = emptyList(),
                libraryDirectories = directories,
                libraryFiles = emptyList(),
                libraryExtensions = VerilogDesign.LIBRARY_EXTENSIONS,
                topModules = emptyList(),
                ignored = emptyList(),
                errors = emptyList(),
            )
        }
        return designArguments(design, if (cpp) null else testbench)
    }

    /**
     * The arguments that describe [design], with the Verilog [testbench] compiled last if the list leaves it out.
     * The top is given apart, so the list's own tops, those of the design, are not passed.
     */
    internal fun designArguments(design: FileList, testbench: Path?): List<String> = buildList {
        val ownDirectory = testbench?.toAbsolutePath()?.normalize()?.parent
        for (directory in (listOfNotNull(ownDirectory) + design.includeDirectories).distinct()) add("-I$directory")
        for (define in design.defines) add("-D$define")
        for (directory in design.libraryDirectories) addAll(listOf("-y", directory.toString()))
        if (design.libraryDirectories.isNotEmpty() || design.libraryFiles.isNotEmpty()) {
            add("+libext+" + (VerilogDesign.LIBRARY_EXTENSIONS + design.libraryExtensions).distinct().joinToString("+"))
        }
        for (file in design.libraryFiles) addAll(listOf("-v", file.toString()))
        val sources = design.sources + listOfNotNull(testbench?.toAbsolutePath()?.normalize())
        sources.distinct().forEach { add(it.toString()) }
    }

    @Throws(ExecutionException::class)
    private fun readFileList(file: Path, project: Project, report: (String) -> Unit): FileList {
        if (!file.isRegularFile()) throw ExecutionException("File list '$file' does not exist.")
        val design = FileList.parse(file, FileList.baseDirectory(file, project.basePath?.let { Path.of(it) }))
        if (design.errors.isNotEmpty()) throw ExecutionException(design.errors.joinToString("\n"))
        report("Verilating the design of file list $file")
        if (design.ignored.isNotEmpty()) report("Options of the file list that are not passed to Verilator: ${design.ignored.joinToString(" ")}")
        return design
    }

    /**
     * The files of [directories] that declare [module]. A module is found by file name through `-y` too, but
     * one in a file named otherwise only like this.
     */
    private fun moduleFiles(directories: List<Path>, module: String): List<Path> =
        directories.flatMap { directory ->
            runCatching { directory.listDirectoryEntries() }.getOrDefault(emptyList())
                .filter { it.isRegularFile() && it.extension.lowercase() in SOURCE_EXTENSIONS }
                .filter { file -> runCatching { VerilogTestbenches.modules(Files.readString(file)).any { it.name == module } }.getOrDefault(false) }
        }.sorted()

    @Throws(ExecutionException::class)
    private fun readManifest(installation: VerilatorInstallation, modelDirectory: Path, prefix: String, workingDirectory: Path): VerilatorManifest {
        val file = modelDirectory.resolve(if (installation.writesJson) "$prefix.json" else "$prefix.cmake")
        val text = try {
            Files.readString(file)
        } catch (e: IOException) {
            throw ExecutionException("Verilator wrote no list of its files to '$file'.", e)
        }
        return try {
            if (installation.writesJson) VerilatorManifest.parseJson(text, workingDirectory)
            else VerilatorManifest.parseCmake(text, prefix, workingDirectory, installation.root)
        } catch (e: RuntimeException) {
            throw ExecutionException("Verilator's list of its files, '$file', cannot be read: ${e.message}", e)
        }
    }

    /** The dump the testbench names if it was written in this run, or else the newest dump written in [directory]. */
    internal fun findDump(directory: Path, expected: Path?, since: Long): Path? {
        fun written(file: Path) = runCatching { file.isRegularFile() && Files.getLastModifiedTime(file).toMillis() >= since }.getOrDefault(false)
        if (expected != null && written(expected)) return expected
        return runCatching { directory.listDirectoryEntries() }.getOrDefault(emptyList())
            .filter { it.extension.lowercase() in DUMP_EXTENSIONS && written(it) }
            .maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
    }
}
