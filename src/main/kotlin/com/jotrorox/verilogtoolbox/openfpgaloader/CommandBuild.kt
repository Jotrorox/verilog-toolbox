package com.jotrorox.verilogtoolbox.openfpgaloader

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import com.intellij.util.execution.ParametersListUtil
import com.jotrorox.verilogtoolbox.common.BuildPlan
import com.jotrorox.verilogtoolbox.common.BuildStep
import com.jotrorox.verilogtoolbox.settings.findExecutableOnPath
import com.jotrorox.verilogtoolbox.settings.findInstalledExecutable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * A build command of the project's, such as `make build/top.fs`, run in [directory]. [toolDirectories] go
 * first on its PATH, so that it finds the tools of the suite openFPGALoader and Yosys come from, as `make`
 * expects them on PATH.
 */
internal class CommandBuild(
    private val command: String,
    private val directory: Path,
    private val toolDirectories: List<Path>,
) : BuildPlan {
    override fun steps(say: (String, Key<*>) -> Unit): List<BuildStep> = listOf(BuildStep("Building: $command", commandLine()))

    /** The command, with its program looked up on the PATH that it runs with. */
    @Throws(ExecutionException::class)
    private fun commandLine(): GeneralCommandLine {
        val words = ParametersListUtil.parse(command)
        val program = words.firstOrNull() ?: throw ExecutionException("The build command is empty.")
        val path = (toolDirectories.map { it.toString() } + listOfNotNull(EnvironmentUtil.getValue("PATH")))
            .distinct().joinToString(File.pathSeparator)
        val executable = if ('/' in program || '\\' in program) {
            directory.resolve(program).takeIf { Files.isExecutable(it) }
        } else {
            executableNames(program).firstNotNullOfOrNull { findExecutableOnPath(it, path) ?: findInstalledExecutable(it) }
        } ?: throw ExecutionException(
            "$program was not found, so the bitstream cannot be built." +
                (if (program == "make" && SystemInfo.isWindows) " Install make, for example with MSYS2 or Chocolatey, or change the build command." else " Change the build command in the run configuration.")
        )
        return GeneralCommandLine(executable.toString())
            .withParameters(words.drop(1))
            .withWorkingDirectory(directory)
            .withEnvironment("PATH", path)
            .withCharset(Charsets.UTF_8)
    }

    private fun executableNames(program: String): List<String> =
        if (SystemInfo.isWindows && '.' !in program) listOf("$program.exe", "$program.cmd", "$program.bat", program) else listOf(program)
}
