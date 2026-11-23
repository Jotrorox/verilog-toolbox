package com.jotrorox.verilogtoolbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class QuartusProjectTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val root: Path get() = temporary.root.toPath().toRealPath()

    private fun write(name: String, text: String): Path {
        val file = root.resolve(name)
        Files.createDirectories(file.parent)
        return Files.writeString(file, text)
    }

    private val settings = """
        # A comment; with a semicolon
        set_global_assignment -name FAMILY "MAX 10"
        set_global_assignment -name DEVICE 10M50DAF484C7G
        set_global_assignment -name TOP_LEVEL_ENTITY blinky
        set_global_assignment -name PROJECT_OUTPUT_DIRECTORY output_files
        set_global_assignment -name EDA_TIME_SCALE "1 ps" -section_id eda_simulation
        set_global_assignment -name SYSTEMVERILOG_FILE rtl/counter.sv
        set_global_assignment -name SYSTEMVERILOG_FILE "blinky.sv"
        set_global_assignment -name VERILOG_FILE rtl/defs.vh
        set_global_assignment -name SDC_FILE blinky.sdc
        set_global_assignment -name SEARCH_PATH include
        set_global_assignment -name VERILOG_MACRO "WIDTH=8"
        set_location_assignment PIN_P11 -to clk_50
        set_location_assignment PIN_A8 -to led[0]
        set_instance_assignment -name IO_STANDARD "3.3-V LVTTL" -to clk_50
        set_instance_assignment -name IO_STANDARD "3.3-V LVTTL" -to led
        set_instance_assignment -name PARTITION_HIERARCHY root_partition -to | -section_id Top
    """.trimIndent() + "\n"

    private fun project(): QuartusProject {
        write("blinky.qpf", "QUARTUS_VERSION = \"25.1\"\n\n# Revisions\n\nPROJECT_REVISION = \"blinky\"\nPROJECT_REVISION = \"blinky_fast\"\n")
        write("blinky.qsf", settings)
        return QuartusProject.of(root.resolve("blinky.qpf"))!!
    }

    @Test
    fun commandsAreSplitIntoWords() {
        val commands = QuartusScript.commands("set_global_assignment -name FAMILY \"MAX 10\"; set_location_assignment PIN_A8 -to led[0]\n")
        assertEquals(listOf("set_global_assignment", "-name", "FAMILY", "MAX 10"), commands[0].words)
        assertEquals("FAMILY", commands[0].name)
        assertEquals("MAX 10", commands[0].value)
        // Quartus writes a bus bit without quotes, which is not a command substitution to it.
        assertEquals("led[0]", commands[1].to)
        assertEquals("PIN_A8", commands[1].value)
    }

    @Test
    fun commentsContinuationsAndBracesAreRead() {
        val text = "# set_global_assignment -name DEVICE wrong\n" +
            "set_global_assignment \\\n    -name DEVICE {10M50 DA}\n" +
            "if {1} {\n  set_global_assignment -name FAMILY hidden\n}\n"
        val commands = QuartusScript.commands(text)
        assertEquals(listOf("set_global_assignment", "-name", "DEVICE", "10M50 DA"), commands[0].words)
        // Control flow is not run, so what it holds is not seen as an assignment.
        assertEquals("if", commands[1].command)
        assertEquals(2, commands.size)
    }

    @Test
    fun offsetsCoverTheCommand() {
        val text = "  set_global_assignment -name SEED 2  \nset_parameter -name N 4\n"
        val commands = QuartusScript.commands(text)
        assertEquals("set_global_assignment -name SEED 2", text.substring(commands[0].start, commands[0].end))
        assertEquals("set_parameter -name N 4", text.substring(commands[1].start, commands[1].end))
    }

    @Test
    fun optionsAndFlagsAreToldFromTheValue() {
        val command = QuartusScript.commands("set_instance_assignment -name IO_STANDARD -to led -disable \"2.5 V\"").single()
        assertEquals("2.5 V", command.value)
        assertEquals("led", command.to)
        assertTrue(command.isInactive)
        assertNull(command.option("-section_id"))
    }

    @Test
    fun variablesAndFileJoinAreSubstituted() {
        val text = "set dir rtl\nset_global_assignment -name VERILOG_FILE [file join \$::quartus(qip_path) \$dir \"pll.v\"]\n"
        val commands = QuartusScript.commands(text, variables = mapOf("quartus(qip_path)" to "C:/ip"))
        assertEquals("C:/ip/rtl/pll.v", commands[1].value)
    }

    @Test
    fun quotingKeepsPlainWordsPlain() {
        assertEquals("10M50DAF484C7G", QuartusScript.quote("10M50DAF484C7G"))
        assertEquals("rtl/top.sv", QuartusScript.quote("rtl/top.sv"))
        assertEquals("led[0]", QuartusScript.quote("led[0]"))
        assertEquals("\"MAX 10\"", QuartusScript.quote("MAX 10"))
        assertEquals("\"-1\"", QuartusScript.quote("-1"))
        assertEquals("MAX 10", QuartusScript.commands("x " + QuartusScript.quote("MAX 10")).single().words[1])
    }

    @Test
    fun revisionsComeFromTheProjectFile() {
        val project = project()
        assertEquals("blinky", project.name)
        assertEquals(listOf("blinky", "blinky_fast"), project.revisions)
        assertEquals(root.resolve("blinky_fast.qsf"), project.settingsFile("blinky_fast"))
    }

    @Test
    fun settingsFileFindsItsProject() {
        project()
        write("other.qsf", "set_global_assignment -name DEVICE X\n")
        assertEquals(root.resolve("blinky.qpf"), QuartusProject.of(root.resolve("blinky.qsf"))!!.file)
        // A settings file no project names is a project of its own.
        val alone = QuartusProject.of(root.resolve("other.qsf"))!!
        assertEquals(root.resolve("other.qsf"), alone.file)
        assertEquals(listOf("other"), alone.revisions)
        assertNull(QuartusProject.of(root.resolve("missing.qpf")))
    }

    @Test
    fun revisionReadsTheAssignments() {
        val revision = project().revision()
        assertEquals("MAX 10", revision.family)
        assertEquals("10M50DAF484C7G", revision.device)
        assertEquals("blinky", revision.topLevelEntity)
        assertEquals(root.resolve("output_files"), revision.outputDirectory)
        assertEquals(root.resolve("output_files/blinky.sof"), revision.output("sof"))
        assertEquals(listOf(root.resolve("rtl/counter.sv"), root.resolve("blinky.sv")), revision.verilogSources)
        assertEquals(listOf(root.resolve("include")), revision.searchPaths)
        assertEquals(listOf("WIDTH=8"), revision.macros)
        assertEquals(
            listOf(QuartusPin("clk_50", "PIN_P11", "3.3-V LVTTL"), QuartusPin("led[0]", "PIN_A8", null), QuartusPin("led", null, "3.3-V LVTTL")),
            revision.pins,
        )
    }

    @Test
    fun topLevelAndOutputDirectoryHaveDefaults() {
        write("plain.qsf", "set_global_assignment -name DEVICE X\n")
        val revision = QuartusProject.of(root.resolve("plain.qsf"))!!.revision()
        assertEquals("plain", revision.topLevelEntity)
        assertEquals(root, revision.outputDirectory)
    }

    @Test
    fun unsavedTextIsReadInPlaceOfTheFile() {
        val revision = project().revision(text = "set_global_assignment -name DEVICE 10M08\n")
        assertEquals("10M08", revision.device)
    }

    @Test
    fun removedAndDisabledAssignmentsDoNotCount() {
        write("x.qsf", "set_global_assignment -name VERILOG_FILE a.v\nset_global_assignment -name VERILOG_FILE b.v -disable\n")
        assertEquals(listOf(root.resolve("a.v")), QuartusProject.of(root.resolve("x.qsf"))!!.revision().verilogSources)
    }

    @Test
    fun ipFilesAreListedWithTheirSources() {
        write("ip/pll.qip", "set_global_assignment -library \"pll\" -name VERILOG_FILE [file join \$::quartus(qip_path) \"pll.v\"]\n" +
            "set_global_assignment -name SDC_FILE [file join \$::quartus(qip_path) \"pll.sdc\"]\n")
        write("x.qsf", "set_global_assignment -name QIP_FILE ip/pll.qip\nset_global_assignment -name VERILOG_FILE top.v\n")
        val revision = QuartusProject.of(root.resolve("x.qsf"))!!.revision()
        assertEquals(listOf(root.resolve("ip/pll.v"), root.resolve("top.v")), revision.verilogSources)
        assertEquals("pll", revision.files.first { it.path == root.resolve("ip/pll.v") }.library)
        assertEquals(1, revision.listedFiles.count { it.kind == QuartusFileKind.QIP })
    }

    @Test
    fun sourcedScriptsAreRead() {
        write("pins.tcl", "set_location_assignment PIN_B1 -to key\n")
        write("x.qsf", "set_global_assignment -name DEVICE X\nsource pins.tcl\n")
        assertEquals(listOf(QuartusPin("key", "PIN_B1", null)), QuartusProject.of(root.resolve("x.qsf"))!!.revision().pins)
    }

    @Test
    fun fileListDescribesTheDesign() {
        val library = root.resolve("altera_mf.v")
        val design = project().revision().fileList(listOf(library))
        assertEquals(listOf(root.resolve("rtl/counter.sv"), root.resolve("blinky.sv")), design.sources)
        // Headers are not compiled, but where they are is searched for includes.
        assertEquals(listOf(root.resolve("include"), root, root.resolve("rtl")), design.includeDirectories)
        assertEquals(listOf("WIDTH=8"), design.defines)
        assertEquals(listOf("blinky"), design.topModules)
        assertEquals(listOf(library), design.libraryFiles)
        assertTrue(design.errors.isEmpty())
    }

    @Test
    fun globalAssignmentIsReplacedInPlace() {
        val text = QuartusSettingsText.setGlobal(settings, "DEVICE", "10M08SAE144C8G")
        assertTrue(text, "set_global_assignment -name DEVICE 10M08SAE144C8G\nset_global_assignment -name TOP_LEVEL_ENTITY" in text)
        assertEquals(settings.lines().size, text.lines().size)
        assertEquals("# A comment; with a semicolon", text.lines().first())
    }

    @Test
    fun globalAssignmentInASectionIsLeftAlone() {
        val text = QuartusSettingsText.setGlobal(settings, "EDA_TIME_SCALE", "1 ns")
        assertTrue(text, "set_global_assignment -name EDA_TIME_SCALE \"1 ps\" -section_id eda_simulation" in text)
        assertTrue(text, "set_global_assignment -name EDA_TIME_SCALE \"1 ns\"\n" in text)
    }

    @Test
    fun newGlobalAssignmentFollowsTheOthers() {
        val text = QuartusSettingsText.setGlobal(settings, "SEED", "2")
        val lines = text.lines()
        assertEquals("set_global_assignment -name SEED 2", lines[lines.indexOfFirst { it.contains("VERILOG_MACRO") } + 1])
        assertEquals("set_global_assignment -name SEED 2\n", QuartusSettingsText.setGlobal("", "SEED", "2"))
        assertEquals("# only a comment\nset_global_assignment -name SEED 2\n", QuartusSettingsText.setGlobal("# only a comment", "SEED", "2"))
    }

    @Test
    fun globalAssignmentIsRemoved() {
        val text = QuartusSettingsText.setGlobal(settings, "DEVICE", null)
        assertFalse(text, "DEVICE" in text)
        assertEquals(settings.lines().size - 1, text.lines().size)
    }

    @Test
    fun filesAreAddedOnceAndRemoved() {
        val added = QuartusSettingsText.addGlobal(settings, "SYSTEMVERILOG_FILE", "rtl/uart.sv")
        val lines = added.lines()
        assertEquals("set_global_assignment -name SYSTEMVERILOG_FILE rtl/uart.sv", lines[lines.indexOfFirst { it.contains("blinky.sv") } + 1])
        assertEquals(added, QuartusSettingsText.addGlobal(added, "SYSTEMVERILOG_FILE", "rtl/uart.sv"))
        // A kind of file the project has none of yet goes next to the other files.
        val vhdl = QuartusSettingsText.addGlobal(settings, "VHDL_FILE", "rtl/x.vhd").lines()
        assertEquals("set_global_assignment -name VHDL_FILE rtl/x.vhd", vhdl[vhdl.indexOfFirst { it.contains("SDC_FILE") } + 1])
        assertEquals(settings, QuartusSettingsText.removeGlobal(added, "SYSTEMVERILOG_FILE") { it == "rtl/uart.sv" })
    }

    @Test
    fun pinsAreAssignedMovedAndCleared() {
        var text = QuartusSettingsText.setLocation(settings, "led[1]", "PIN_A9")
        val lines = text.lines()
        assertEquals("set_location_assignment PIN_A9 -to led[1]", lines[lines.indexOfFirst { it.contains("PIN_A8") } + 1])
        text = QuartusSettingsText.setLocation(text, "led[1]", "PIN_A10")
        assertFalse(text, "PIN_A9" in text)
        assertTrue(text, "set_location_assignment PIN_A10 -to led[1]" in text)
        assertEquals(settings, QuartusSettingsText.setLocation(text, "led[1]", null))
    }

    @Test
    fun instanceAssignmentsAreSetPerNode() {
        val text = QuartusSettingsText.setInstance(settings, "IO_STANDARD", "led[0]", "2.5 V")
        assertTrue(text, "set_instance_assignment -name IO_STANDARD \"2.5 V\" -to led[0]" in text)
        assertTrue(text, "set_instance_assignment -name IO_STANDARD \"3.3-V LVTTL\" -to led\n" in text)
        val changed = QuartusSettingsText.setInstance(settings, "IO_STANDARD", "clk_50", "2.5 V")
        assertEquals(settings.lines().size, changed.lines().size)
        assertEquals(settings, QuartusSettingsText.setInstance(text, "IO_STANDARD", "led[0]", null))
    }

    @Test
    fun pathsAreRelativeToTheProjectWhereTheyCanBe() {
        assertEquals("rtl/top.sv", QuartusSettingsText.path(root.resolve("rtl").resolve("top.sv"), root))
        val outside = root.parent.resolve("shared.sv")
        assertEquals(outside.toString().replace('\\', '/'), QuartusSettingsText.path(outside, root))
        assertTrue(QuartusSettingsText.sameFile("rtl\\top.sv", root.resolve("rtl/top.sv"), root))
        assertFalse(QuartusSettingsText.sameFile("rtl/other.sv", root.resolve("rtl/top.sv"), root))
    }

    @Test
    fun pinLocationsAreNormalized() {
        assertEquals("PIN_P11", QuartusSettingsText.pinLocation(" p11 "))
        assertEquals("PIN_P11", QuartusSettingsText.pinLocation("pin_p11"))
        assertNull(QuartusSettingsText.pinLocation("  "))
    }

    @Test
    fun newProjectReadsBack() {
        write("new.qpf", QuartusSettingsText.projectFile("new"))
        write(
            "new.qsf",
            QuartusSettingsText.settingsFile("Cyclone V", "5CSEMA5F31C6", "top", listOf(QuartusFileKind.SYSTEMVERILOG to "rtl/top.sv")),
        )
        val revision = QuartusProject.of(root.resolve("new.qpf"))!!.revision()
        assertEquals("new", revision.name)
        assertEquals("Cyclone V", revision.family)
        assertEquals("5CSEMA5F31C6", revision.device)
        assertEquals("top", revision.topLevelEntity)
        assertEquals(listOf(root.resolve("rtl/top.sv")), revision.verilogSources)
    }

    @Test
    fun projectsAreFoundBelowTheRoot() {
        project()
        write("blinky_fast.qsf", "")
        write("boards/de10/de10.qsf", "")
        write("db/stale.qsf", "")
        write(".git/hidden.qpf", "")
        val found = QuartusProjectService.scan(root).map { root.relativize(it.file).toString().replace('\\', '/') }
        // The second revision belongs to the project; tool directories and hidden ones are not searched.
        assertEquals(listOf("blinky.qpf", "boards/de10/de10.qsf"), found)
    }
}
