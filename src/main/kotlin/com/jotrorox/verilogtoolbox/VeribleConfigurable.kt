package com.jotrorox.verilogtoolbox

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import java.io.IOException
import java.nio.file.Path

/** Settings | Tools | Verilog Toolbox. */
class VeribleConfigurable : BoundConfigurable("Verilog Toolbox") {
    private val settings = VeribleSettings.getInstance()
    private val toolchain = ToolchainSettings.getInstance()
    private lateinit var pathField: TextFieldWithBrowseButton
    private lateinit var iverilogField: TextFieldWithBrowseButton

    override fun createPanel(): DialogPanel = panel {
        group("Verible Language Server") {
            row("Path to verible-verilog-ls:") {
                pathField = textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile().withTitle("Select verible-verilog-ls")
                )
                    .bindText(settings::languageServerPath)
                    .align(AlignX.FILL)
                    .comment("Leave empty to use the verible-verilog-ls found on your PATH.")
                    .component
            }
            row {
                button("Download Verible") { download() }
                comment("Fetches the latest prebuilt release from github.com/chipsalliance/verible.")
            }
        }
        group("slang Language Server (Optional)") {
            row("Path to slang-server:") {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile().withTitle("Select slang-server")
                )
                    .bindText(toolchain::slangServerPath)
                    .align(AlignX.FILL)
                    .comment(
                        "Runs next to Verible and takes over go-to-definition, hover and completion. " +
                            "Leave empty to use Verible alone. Releases: " +
                            "<a href=\"https://github.com/hudson-trading/slang-server/releases\">github.com/hudson-trading/slang-server</a>."
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
                    .comment("Leave empty to use the iverilog found on your PATH. vvp is taken from the same directory.")
                    .component
            }
            // Only offered while there is no usable installation.
            row {
                button("Download Icarus Verilog") { downloadIcarus() }
                comment(
                    "Icarus Verilog was not found. It has no official binaries, so this fetches the OSS CAD Suite " +
                        "(500–750 MB) from github.com/YosysHQ/oss-cad-suite-build and keeps only Icarus Verilog."
                )
            }.visible(toolchain.findIverilog() == null)
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

    private fun downloadIcarus() {
        try {
            val executable = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<Path, IOException> {
                    IcarusDownloader.downloadLatest(ProgressManager.getInstance().progressIndicator)
                },
                "Downloading Icarus Verilog", true, null,
            )
            iverilogField.text = executable.toString()
        } catch (e: IOException) {
            Messages.showErrorDialog(iverilogField, e.message ?: e.toString(), "Icarus Verilog Download Failed")
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
    }
}
