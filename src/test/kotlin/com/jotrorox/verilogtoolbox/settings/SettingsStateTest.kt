package com.jotrorox.verilogtoolbox.settings

import com.intellij.util.xmlb.XmlSerializer
import com.jotrorox.verilogtoolbox.lsp.SlangDesign
import com.jotrorox.verilogtoolbox.quartus.QuartusProjectService
import com.jotrorox.verilogtoolbox.waveform.WaveformViewer
import com.jotrorox.verilogtoolbox.yosys.SynthesisTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/** What is set must still be there after a restart: every state is written and read back as the IDE does it. */
class SettingsStateTest {
    private inline fun <reified T : Any> restored(state: T): T = XmlSerializer.deserialize(XmlSerializer.serialize(state), T::class.java)

    @Test
    fun toolchainSettingsAreSaved() {
        val state = ToolchainSettings.State(
            slangServerEnabled = false, iverilogPath = "/opt/iverilog", waveformViewer = WaveformViewer.SURFER,
            verilatorLintEnabled = false, synthesisTarget = SynthesisTarget.ECP5, quartusPath = "/opt/quartus",
            quartusSimulationLibraries = false, openFpgaLoaderPath = "/opt/oss-cad-suite/bin/openFPGALoader",
        )
        assertEquals(state, restored(state))
    }

    @Test
    fun veribleSettingsAreSaved() {
        val state = VeribleSettings.State("/opt/verible-verilog-ls")
        assertEquals(state, restored(state))
    }

    @Test
    fun selectedDesignIsSaved() {
        val state = SlangDesign.State("/work/top.qsf", buildFile = true)
        assertEquals(state, restored(state))
    }

    @Test
    fun quartusProjectIsSaved() {
        val state = QuartusProjectService.State("/work/top.qpf", "rev_b", designDeclined = true)
        assertEquals(state, restored(state))
    }
}
