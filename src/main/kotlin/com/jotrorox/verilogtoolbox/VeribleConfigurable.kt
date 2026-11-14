package com.jotrorox.verilogtoolbox

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.io.IOException
import java.nio.file.Path

/** Settings | Tools | Verilog Toolbox. */
class VeribleConfigurable : BoundConfigurable("Verilog Toolbox") {
    private val settings = VeribleSettings.getInstance()
    private lateinit var pathField: TextFieldWithBrowseButton

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
