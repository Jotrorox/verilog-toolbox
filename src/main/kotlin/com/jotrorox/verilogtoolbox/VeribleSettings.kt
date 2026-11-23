package com.jotrorox.verilogtoolbox

import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.APP)
@State(name = "VerilogToolboxVerible", storages = [Storage("verilogToolbox.xml")])
class VeribleSettings :
    SerializablePersistentStateComponent<VeribleSettings.State>(State()) {

    /** Fields are `var` so that the platform saves them, see [ToolchainSettings.State]. */
    data class State(@JvmField var languageServerPath: String = "")

    /** Path to `verible-verilog-ls`; empty means "look it up on PATH, or where Homebrew puts it". */
    var languageServerPath: String
        get() = state.languageServerPath
        set(value) {
            updateState { it.copy(languageServerPath = value.trim()) }
        }

    /** The optional Verible language server: the configured path if valid, otherwise the installed one. */
    fun findLanguageServer(): Path? {
        val configured = languageServerPath
        if (configured.isNotEmpty()) {
            return runCatching { Path.of(configured) }.getOrNull()?.takeIf { Files.isExecutable(it) }
        }
        return findInstalledExecutable(LANGUAGE_SERVER_BINARY)
    }

    companion object {
        val LANGUAGE_SERVER_BINARY: String =
            if (SystemInfo.isWindows) "verible-verilog-ls.exe" else "verible-verilog-ls"

        fun getInstance(): VeribleSettings = service()
    }
}
