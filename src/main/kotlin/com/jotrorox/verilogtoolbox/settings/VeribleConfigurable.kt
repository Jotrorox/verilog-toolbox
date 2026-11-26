package com.jotrorox.verilogtoolbox.settings

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.GradientViewport
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.util.ui.JBUI
import com.jotrorox.verilogtoolbox.common.OssCadSuite
import com.jotrorox.verilogtoolbox.lsp.SlangServerDownloader
import com.jotrorox.verilogtoolbox.lsp.VeribleDownloader
import com.jotrorox.verilogtoolbox.lsp.VeribleLspIntegrationProvider
import com.jotrorox.verilogtoolbox.openfpgaloader.OpenFpgaLoader
import com.jotrorox.verilogtoolbox.gowin.eda.GowinEda
import com.jotrorox.verilogtoolbox.gowin.eda.GowinEdaProjectService
import com.jotrorox.verilogtoolbox.quartus.Quartus
import com.jotrorox.verilogtoolbox.quartus.QuartusProjectService
import com.jotrorox.verilogtoolbox.waveform.WaveformViewer
import com.jotrorox.verilogtoolbox.yosys.SynthesisTarget
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent

/**
 * Settings | Tools | Verilog Toolbox.
 *
 * Not a BoundConfigurable: the dialog puts such a page into a margin panel and that into a scroll pane, which lays it
 * out at its preferred width and scrolls sideways when the dialog is narrower. This page brings its own margin and
 * scroll pane, NoMargin and NoScroll telling the dialog so, with a [ViewportWidthPanel] in it, so that it takes the
 * dialog's width and its comments wrap.
 */
class VeribleConfigurable : Configurable, Configurable.NoScroll, Configurable.NoMargin {
    private var panel: DialogPanel? = null
    private val settings = VeribleSettings.getInstance()
    private val toolchain = ToolchainSettings.getInstance()
    private lateinit var pathField: TextFieldWithBrowseButton
    private lateinit var slangField: TextFieldWithBrowseButton
    private lateinit var slangStatus: JEditorPane
    private lateinit var iverilogField: TextFieldWithBrowseButton
    private lateinit var suiteStatus: JEditorPane
    private lateinit var quartusField: TextFieldWithBrowseButton
    private lateinit var quartusStatus: JEditorPane
    private lateinit var gowinEdaField: TextFieldWithBrowseButton
    private lateinit var gowinEdaStatus: JEditorPane
    private lateinit var openFpgaLoaderField: TextFieldWithBrowseButton
    private lateinit var openFpgaLoaderStatus: JEditorPane

    override fun getDisplayName(): String = "Verilog Toolbox"

    override fun createComponent(): JComponent {
        val page = createPanel().also { panel = it }
        // The margin and scroll pane the dialog gives a DSL page itself.
        page.border = JBUI.Borders.empty(5, 16, 10, 16)
        return ScrollPaneFactory.createScrollPane(null, true).apply {
            setViewport(GradientViewport(ViewportWidthPanel(page), JBUI.insetsTop(5), true))
            verticalScrollBar.background = JBColor.PanelBackground
            horizontalScrollBar.background = JBColor.PanelBackground
        }
    }

    override fun getPreferredFocusedComponent(): JComponent? = panel?.preferredFocusedComponent

    override fun isModified(): Boolean = panel?.isModified() == true

    override fun reset() {
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun createPanel(): DialogPanel = panel {
        group("slang Language Server") {
            row {
                checkBox("Use slang-server")
                    .bindSelected(toolchain::slangServerEnabled)
                    .wrappingComment(
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
                    .wrappingComment("Leave empty to use the downloaded slang-server, or else the one found on your PATH.")
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
                    .wrappingComment(
                        "Adds formatting and style lint, which slang-server does not have. " +
                            "Leave empty to use the verible-verilog-ls found on your PATH" +
                            (if (SystemInfo.isMac) " or in Homebrew's directories." else ".")
                    )
                    .component
            }
            if (VeribleDownloader.isAvailable) {
                row {
                    button("Download Verible") { download() }
                    comment("Fetches the latest prebuilt release from github.com/chipsalliance/verible.")
                }
            } else if (VeribleDownloader.useHomebrew) {
                row {
                    comment(
                        "Verible publishes no prebuilt binary for Intel Macs. Install it with " +
                            "<a href=\"https://github.com/chipsalliance/homebrew-verible\">Homebrew</a>, " +
                            "and it is found without setting the path:<br>" +
                            "<code>${VeribleDownloader.HOMEBREW_INSTALL}</code>"
                    )
                }
            } else {
                row {
                    comment(
                        "Verible publishes no prebuilt binary for this system. Build it from " +
                            "<a href=\"https://github.com/chipsalliance/verible\">github.com/chipsalliance/verible</a> " +
                            "and set the path above."
                    )
                }
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
                        "Icarus Verilog, Verilator, Yosys, SymbiYosys, GTKWave and openFPGALoader are taken from it when their paths are " +
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
                    .wrappingComment(
                        "Leave empty to use the OSS CAD Suite's, or else the iverilog found on your PATH. " +
                            "vvp is taken from the same directory."
                    )
                    .component
            }
        }
        group("Simulation") {
            row("Simulate testbenches with:") {
                comboBox(TestbenchSimulator.entries)
                    .bindItem(toolchain::testbenchSimulator.toNullableProperty())
                    .wrappingComment(
                        "What the run icon of a testbench and Run 'Testbenches in …' of a directory use. Verilator " +
                            "is much faster and knows more SystemVerilog; it builds the simulation with CMake in " +
                            "<code>build/verilator</code>, which CLion can load to debug it. Run configurations that " +
                            "exist keep their simulator."
                    )
            }
            row("Path to cmake:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Select cmake"))
                    .bindText(toolchain::cmakePath)
                    .align(AlignX.FILL)
                    .wrappingComment(
                        "Builds Verilator's simulations. Leave empty to use the cmake on your PATH, or else the IDE's " +
                            "own, with its Ninja and, on Windows, its MinGW."
                    )
            }
        }
        group("Verilator") {
            row {
                checkBox("Lint open files with Verilator")
                    .bindSelected(toolchain::verilatorLintEnabled)
                    .wrappingComment(
                        "A second source of diagnostics next to slang-server: width mismatches, incomplete " +
                            "cases, inferred latches and more. Files are linted in the design file list, when " +
                            "one is selected."
                    )
            }
            row("Path to verilator:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Select verilator"))
                    .bindText(toolchain::verilatorPath)
                    .align(AlignX.FILL)
                    .wrappingComment("Leave empty to use the OSS CAD Suite's, or else the verilator found on your PATH.")
            }
            row("Lint options:") {
                textField()
                    .bindText(toolchain::verilatorOptions)
                    .align(AlignX.FILL)
                    .wrappingComment(
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
                    .wrappingComment(
                        "Leave empty to use the OSS CAD Suite's, or else the yosys found on your PATH. " +
                            "SystemVerilog is read with the yosys-slang plugin where Yosys has it."
                    )
            }
            row("Check Synthesis for:") {
                comboBox(SynthesisTarget.entries)
                    .bindItem(toolchain::synthesisTarget.toNullableProperty())
                    .wrappingComment("The cells the resource summary counts: generic gates, or those of an FPGA family.")
            }
        }
        group("SymbiYosys Formal Verification") {
            row("Path to sby:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Select sby"))
                    .bindText(toolchain::sbyPath)
                    .align(AlignX.FILL)
                    .wrappingComment(
                        "Runs the tasks of <code>.sby</code> files. Leave empty to use the OSS CAD Suite's, which comes with " +
                            "Yosys and the solvers, or else the sby found on your PATH."
                    )
            }
        }
        group("openFPGALoader") {
            row("Path to openFPGALoader:") {
                openFpgaLoaderField = textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile().withTitle("Select openFPGALoader")
                )
                    .bindText(toolchain::openFpgaLoaderPath)
                    .align(AlignX.FILL)
                    .wrappingComment(
                        "Programs the FPGA. Leave empty to use the OSS CAD Suite's, or else the openFPGALoader found on your PATH" +
                            (if (SystemInfo.isMac) " or in Homebrew's directories." else ".") +
                            " A build command run before programming finds the other tools of the same directory, such as Yosys and nextpnr."
                    )
                    .component
            }
            row {
                openFpgaLoaderStatus = comment(openFpgaLoaderStatusText()).component
                openFpgaLoaderField.textField.document.addDocumentListener(object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) {
                        openFpgaLoaderStatus.text = openFpgaLoaderStatusText()
                    }
                })
            }
        }
        group("Quartus Prime") {
            row("Installation:") {
                quartusField = textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleDir().withTitle("Select the Quartus Prime Installation")
                )
                    .bindText(toolchain::quartusPath)
                    .align(AlignX.FILL)
                    .wrappingComment(
                        "The directory Quartus Prime is installed in, such as " +
                            (if (SystemInfo.isWindows) "<code>C:\\altera_lite\\25.1std</code>" else "<code>~/altera_lite/25.1std</code>") +
                            ". Leave empty to find it where QUARTUS_ROOTDIR points, on your PATH or where it installs by default."
                    )
                    .component
            }
            row {
                quartusStatus = comment(quartusStatusText()).component
                quartusField.textField.document.addDocumentListener(object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) {
                        quartusStatus.text = quartusStatusText()
                    }
                })
            }
            row {
                checkBox("Take megafunctions and primitives from Quartus's simulation libraries")
                    .bindSelected(toolchain::quartusSimulationLibraries)
                    .wrappingComment(
                        "When the design is read from a Quartus project, modules such as <code>altpll</code> and " +
                            "<code>altsyncram</code> are known to slang-server, Verilator and Icarus Verilog from " +
                            "the models in <code>quartus/eda/sim_lib</code>."
                    )
            }
        }
        group("GOWIN EDA") {
            row("Installation:") {
                gowinEdaField = textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleDir().withTitle("Select the GOWIN EDA Installation")
                )
                    .bindText(toolchain::gowinEdaPath)
                    .align(AlignX.FILL)
                    .wrappingComment(
                        "The directory GOWIN EDA is installed in, such as " +
                            (if (SystemInfo.isWindows) "<code>C:\\Gowin\\Gowin_V1.9.12.04_x64</code>" else "<code>~/gowin</code>") +
                            ". Leave empty to find it on your PATH or where it installs by default."
                    )
                    .component
            }
            row {
                gowinEdaStatus = comment(gowinEdaStatusText()).component
                gowinEdaField.textField.document.addDocumentListener(object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) {
                        gowinEdaStatus.text = gowinEdaStatusText()
                    }
                })
            }
        }
        group("Waveform Viewer") {
            row("Viewer:") {
                comboBox(WaveformViewer.entries)
                    .bindItem(toolchain::waveformViewer.toNullableProperty())
                    .wrappingComment(
                        "Opens the waveform a simulation dumps, with the signals saved for its testbench. " +
                            "If this viewer is not installed, the other one is used."
                    )
            }
            row("Path to gtkwave:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Select gtkwave"))
                    .bindText(toolchain::gtkwavePath)
                    .align(AlignX.FILL)
                    .wrappingComment(
                        "Leave empty to use the gtkwave found on your PATH" +
                            (if (SystemInfo.isMac) " or /Applications/gtkwave.app." else ".")
                    )
            }
            row("Path to surfer:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("Select surfer"))
                    .bindText(toolchain::surferPath)
                    .align(AlignX.FILL)
                    .wrappingComment("Leave empty to use the surfer found on your PATH.")
            }
        }
    }

    /** Says which openFPGALoader the path in the field, which need not be applied yet, stands for; never runs it. */
    private fun openFpgaLoaderStatusText(): String {
        val path = if (::openFpgaLoaderField.isInitialized) openFpgaLoaderField.text.trim() else toolchain.openFpgaLoaderPath
        if (path.isNotEmpty()) {
            val file = runCatching { Path.of(path) }.getOrNull()
            return if (file != null && Files.isExecutable(file) && !Files.isDirectory(file)) "Using $file." else "There is no program at this path."
        }
        val found = OssCadSuite.tool(OpenFpgaLoader.TOOL)?.let { "Found in the OSS CAD Suite: $it." }
            ?: findInstalledExecutable(if (SystemInfo.isWindows) "${OpenFpgaLoader.TOOL}.exe" else OpenFpgaLoader.TOOL)?.let { "Found: $it." }
        return found ?: "openFPGALoader was not found. The OSS CAD Suite has it" +
            (if (SystemInfo.isMac) ", and <code>brew install openfpgaloader</code> installs it alone." else ".")
    }

    /** Says which Quartus the path in the field, which need not be applied yet, stands for. */
    private fun quartusStatusText(): String {
        val path = if (::quartusField.isInitialized) quartusField.text else toolchain.quartusPath
        val installation = Quartus.find(path)
            ?: return if (path.isBlank()) "Quartus Prime was not found." else "There is no Quartus Prime in this directory."
        return "Found ${installation.displayName} in ${installation.quartusDirectory?.parent ?: installation.binDirectory}."
    }

    /** Says which GOWIN EDA the path in the field, which need not be applied yet, stands for. */
    private fun gowinEdaStatusText(): String {
        val path = if (::gowinEdaField.isInitialized) gowinEdaField.text else toolchain.gowinEdaPath
        val installation = GowinEda.find(path)
            ?: return if (path.isBlank()) "GOWIN EDA was not found." else "There is no GOWIN EDA in this directory."
        return "Found ${installation.displayName} in ${installation.rootDirectory}."
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
        panel?.apply()
        VeribleLspIntegrationProvider.restartEverywhere()
        // Verilator lint follows its settings in the open files, and a Quartus design its libraries.
        for (project in ProjectManager.getInstance().openProjects) {
            QuartusProjectService.getInstance(project).changed()
            GowinEdaProjectService.getInstance(project).changed()
            DaemonCodeAnalyzer.getInstance(project).restart("Verilog Toolbox settings changed")
        }
    }
}

/** A comment that wraps to the width it is given, where the DSL's default keeps it 70 characters wide whatever the room. */
private fun <T : JComponent> Cell<T>.wrappingComment(text: String): Cell<T> = comment(text, MAX_LINE_LENGTH_WORD_WRAP)

/**
 * Holds a settings page so that it is laid out at the width of the scroll pane it is the view of, and taller instead of
 * wider where its comments wrap. It scrolls sideways only when the dialog is narrower than the page can be.
 */
private class ViewportWidthPanel(content: JComponent) : JPanel(BorderLayout()), Scrollable {
    init {
        isOpaque = false
        add(content)
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width

    override fun getScrollableTracksViewportWidth(): Boolean =
        (parent as? JViewport)?.let { it.width >= minimumSize.width } == true

    override fun getScrollableTracksViewportHeight(): Boolean = false
}
