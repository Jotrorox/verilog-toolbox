package com.jotrorox.verilogtoolbox.common

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.Key

/** One command of a build, such as Yosys's synthesis. */
class BuildStep(
    /** Said in the console before the command runs, such as `Synthesizing top with Yosys`. */
    val description: String,
    val commandLine: GeneralCommandLine,
    /** Run after the command succeeded; what it returns, if anything, is said in the console. */
    val afterwards: (() -> String?)? = null,
)

/** How a bitstream is built before it is programmed: a command of the project's, or a built-in flow. */
interface BuildPlan {
    /**
     * The commands to run, in order; none when the bitstream is up to date. [say] writes to the console,
     * for what the plan found out on its way. Runs on a background thread, so it may read files.
     */
    @Throws(ExecutionException::class)
    fun steps(say: (String, Key<*>) -> Unit): List<BuildStep>
}
