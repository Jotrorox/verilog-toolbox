package com.jotrorox.verilogtoolbox

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import java.io.IOException
import java.nio.file.Path
import javax.swing.JEditorPane

/** Settings | Tools | Verilog Toolbox. */
class VeribleConfigurable : BoundConfigurable("Verilog Toolbox") {
    private val settings = VeribleSettings.getInstance()
    private val toolchain = ToolchainSettings.getInstance()
    private lateinit var pathField: TextFieldWithBrowseButton
    private lateinit var slangField: TextFieldWithBrowseButton
    private lateinit var slangStatus: JEditorPane
    private lateinit var iverilogField: TextFieldWithBrowseButton
    private lateinit var suiteStatus: JEditorPane

    override fun createPanel(): DialogPanel = panel {
        group("slang Language Server") {
            row {
                checkBox("Use slang-server")
                    .bindSelected(toolchain::slangServerEnabled)
                    .comment(
                        "The default language server: diagnostics, navigation, hover, completion, rename and " +
                            "inlay hints. A project is configured in <code>.slang/server.json</code>, see the " +
                            "<a href=\"https://hudson-trading.github.io/slang-server/start/config/\">documentation</a>."
                    )
            }
            row("Path to slang-server:") {
                slangField = textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile().withTitle("Select slang-server")
                )
                    .bindText(toolchain::slangServerPath)
                    .align(AlignX.FILL)
                    .comment("Leave empty to use the downloaded slang-server, or else the one found on your PATH.")
                    .component
            }
            row {
                checkBox("Download slang-server automatically when it is not installed")
                    .bindSelected(toolchain::slangServerAutoDownload)
            }
            row {
                button("Download Latest slang-server") { downloadSlang() }
                slangStatus = comment(slangStatusText()).component
            }
        }
        group("Verible Language Server (Optional)") {
            row("Path to verible-verilog-ls:") {
                pathField = textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile().withTitle("Select verible-verilog-ls")
                )
                    .bindText(settings::languageServerPath)
                    .align(AlignX.FILL)
                    .comment(
                        "Adds formatting and style lint, which slang-server does not have. " +
                            "Leave empty to use the verible-verilog-ls found on your PATH."
                    )
                    .component
            }
            row {
                button("Download Verible") { download() }
                comment("Fetches the latest prebuilt release from github.com/chipsalliance/verible.")
            }
        }
        group("OSS CAD Suite") {
            row {
                button("Download OSS CAD Suite") { downloadSuite() }
                suiteStatus = comment(suiteStatusText()).component
            }
            row {
                comment(
                    "The nightly build from github.com/YosysHQ/oss-cad-suite-build (${OssCadSuite.DOWNLOAD_SIZE}). " +
                        "Icarus Verilog, Verilator, Yosys and GTKWave are taken from it when their paths are " +
                        "empty; older builds are removed after an update."
                )
            }
        }
        group("Icarus Verilog") {
            row("Path to iverilog:") {
                iverilogField = textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile().withTitle("Select iverilog")
                )
                    .bindText(toolchain::iverilogPath)
                    .align(AlignX.FILL)
                    .comment(
                        "Leave empty to use the OSS CAD Suite's, or else the iverilog found on your PATH. " +
                            "vvp is taken from the same directory."
                    )
                    .component
            }
        }
        group("Verilator Lint") {
            row {
                checkBox("Lint open files with Verilator")
                    .bindSelected(toolchain::verilatorLintEnabled)
                    .comment(
                        "A second source of diagnostics next to slang-server: width mismatches, incomplete " +
                            "cases, inferred latches and more. Files are linted in the design file list, when " +
                            "one is selected."
                    )
            }
            row("Path to verilator:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Select verilator"))
                    .bindText(toolchain::verilatorPath)
                    .align(AlignX.FILL)
                    .comment("Leave empty to use the OSS CAD Suite's, or else the verilator found on your PATH.")
            }
            row("Options:") {
                textField()
                    .bindText(toolchain::verilatorOptions)
                    .align(AlignX.FILL)
                    .comment(
                        "Added to <code>--lint-only</code>, such as <code>-Wall</code> for the style warnings " +
                            "or <code>-Wno-UNUSEDSIGNAL</code> to switch one off."
                    )
            }
        }
        group("Yosys Synthesis") {
            row("Path to yosys:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Select yosys"))
                    .bindText(toolchain::yosysPath)
                    .align(AlignX.FILL)
                    .comment(
                        "Leave empty to use the OSS CAD Suite's, or else the yosys found on your PATH. " +
                            "SystemVerilog is read with the yosys-slang plugin where Yosys has it."
                    )
            }
            row("Check Synthesis for:") {
                comboBox(SynthesisTarget.entries)
                    .bindItem(toolchain::synthesisTarget.toNullableProperty())
                    .comment("The cells the resource summary counts: generic gates, or those of an FPGA family.")
            }
        }
        group("Waveform Viewer") {
            row("Viewer:") {
                comboBox(WaveformViewer.entries)
                    .bindItem(toolchain::waveformViewer.toNullableProperty())
                    .comment(
                        "Opens the waveform a simulation dumps, with the signals saved for its testbench. " +
                            "If this viewer is not installed, the other one is used."
                    )
            }
            row("Path to gtkwave:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Select gtkwave"))
                    .bindText(toolchain::gtkwavePath)
                    .align(AlignX.FILL)
                    .comment(
                        "Leave empty to use the gtkwave found on your PATH" +
                            (if (SystemInfo.isMac) " or /Applications/gtkwave.app." else ".")
                    )
            }
            row("Path to surfer:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Select surfer"))
                    .bindText(toolchain::surferPath)
                    .align(AlignX.FILL)
                    .comment("Leave empty to use the surfer found on your PATH.")
            }
        }
    }

    private fun slangStatusText(): String {
        val installed = SlangServerDownloader.installed()
            ?: return "Fetches the latest prebuilt release from github.com/hudson-trading/slang-server (MIT license)."
        return "Downloaded: ${installed.parent.fileName}. This fetches a newer release if there is one."
    }

    private fun downloadSlang() {
        try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<Path, IOException> {
                    SlangServerDownloader.downloadLatest(ProgressManager.getInstance().progressIndicator)
                },
                "Downloading slang-server", true, null,
            )
            // The downloaded copy is what an empty path stands for.
            slangField.text = ""
            slangStatus.text = slangStatusText()
        } catch (e: IOException) {
            Messages.showErrorDialog(slangField, e.message ?: e.toString(), "slang-server Download Failed")
        }
    }

    private fun suiteStatusText(): String {
        val version = OssCadSuite.installedVersion() ?: return "Not downloaded."
        return "Downloaded: build $version. This fetches a newer build if there is one."
    }

    private fun downloadSuite() {
        try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<Path, IOException> {
                    OssCadSuite.downloadLatest(ProgressManager.getInstance().progressIndicator)
                },
                "Downloading the OSS CAD Suite", true, null,
            )
            // The suite's copy is what an empty path stands for; one into an older download is dropped.
            if (OssCadSuite.isManaged(iverilogField.text.trim())) iverilogField.text = ""
            suiteStatus.text = suiteStatusText()
        } catch (e: IOException) {
            Messages.showErrorDialog(iverilogField, e.message ?: e.toString(), "OSS CAD Suite Download Failed")
        }
    }

    private fun download() {
        try {
            val executable = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<Path, IOException> {
                    VeribleDownloader.downloadLatest(ProgressManager.getInstance().progressIndicator)
                },
                "Downloading Verible", true, null,
            )
            pathField.text = executable.toString()
        } catch (e: IOException) {
            Messages.showErrorDialog(pathField, e.message ?: e.toString(), "Verible Download Failed")
        }
    }

    override fun apply() {
        super.apply()
        VeribleLspIntegrationProvider.restartEverywhere()
        // Verilator lint follows its settings in the open files.
        for (project in ProjectManager.getInstance().openProjects) {
            DaemonCodeAnalyzer.getInstance(project).restart("Verilog Toolbox settings changed")
        }
    }
}
