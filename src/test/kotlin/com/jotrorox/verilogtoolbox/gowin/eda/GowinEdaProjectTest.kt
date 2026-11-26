package com.jotrorox.verilogtoolbox.gowin.eda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class GowinEdaProjectTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val root: Path get() = temporary.root.toPath().toRealPath()

    /** A project as GOWIN EDA writes it, from the led_water example that comes with it. */
    private val gprj = """
        <?xml version="1" encoding="UTF-8"?>
        <!DOCTYPE gowin-fpga-project>
        <Project>
            <Template>FPGA</Template>
            <Version>5</Version>
            <Device name="GW1NR-9C" pn="GW1NR-LV9QN88PC6/I5">gw1nr9c-004</Device>
            <FileList>
                <File path="src/blinky.v" type="file.verilog" enable="1"/>
                <File path="src/old.v" type="file.verilog" enable="0"/>
                <File path="src/blinky.cst" type="file.cst" enable="1"/>
                <File path="src/blinky.sdc" type="file.sdc" enable="1"/>
            </FileList>
        </Project>
    """.trimIndent() + "\n"

    // An installation as the Windows installer lays it out: <root>/Gowin_V<version>_x64/IDE/bin/gw_sh.
    private fun install(directory: String): Path {
        val bin = Files.createDirectories(root.resolve(directory).resolve("IDE").resolve("bin"))
        Files.writeString(bin.resolve(GowinEda.binary(GowinEda.SHELL)), "")
        return bin.parent
    }

    @Test
    fun configuredPathMayBeAnyDirectoryOfTheInstallation() {
        val ide = install("Gowin/Gowin_V1.9.12.04_x64")
        val bin = ide.resolve("bin")
        for (path in listOf(ide, ide.parent, bin, bin.resolve(GowinEda.binary(GowinEda.SHELL)))) {
            assertEquals(path.toString(), ide, GowinEda.find(path.toString(), { null }, emptyList())?.ideDirectory)
        }
        val installation = GowinEda.find(ide.parent.toString(), { null }, emptyList())!!
        assertEquals("1.9.12.04", installation.version)
        assertEquals("GOWIN EDA 1.9.12.04", installation.displayName)
        assertNull(GowinEda.find(root.resolve("nowhere").toString(), { null }, listOf(root.resolve("Gowin"))))
    }

    @Test
    fun newestVersionOfTheDefaultLocationsWins() {
        install("Gowin/Gowin_V1.9.9_x64")
        val newest = install("Gowin/Gowin_V1.9.12.04_x64")
        install("Gowin/Gowin_V1.9.11.03_Education_x64")
        assertEquals(newest, GowinEda.find("", { null }, listOf(root.resolve("Gowin")))?.ideDirectory)
        // The Linux archive unpacks the installation into the directory itself.
        val linux = install("opt/gowin")
        assertEquals(linux, GowinEda.find("", { null }, listOf(root.resolve("opt/gowin")))?.ideDirectory)
    }

    @Test
    fun readsDeviceFilesAndOutputs() {
        val directory = Files.createDirectories(root.resolve("blinky"))
        val project = GowinEdaProject.parse(directory.resolve("blinky.gprj"), gprj, """{ "TopModule" : "blinky", "IncludePath" : [ "inc" ] }""")
        assertEquals(GowinEdaDevice("GW1NR-9C", "GW1NR-LV9QN88PC6/I5", "gw1nr9c-004"), project.device)
        assertEquals(4, project.files.size)
        assertEquals(listOf(directory.resolve("src/blinky.v")), project.verilogSources)
        assertEquals(directory.resolve("src/blinky.cst"), project.constraintFile)
        assertEquals(listOf(directory.resolve("src/blinky.sdc")), project.timingConstraintFiles)
        assertFalse(project.files.single { it.path.endsWith("old.v") }.enabled)
        assertEquals("blinky", project.topModule)
        assertTrue(project.isTopModuleSet)
        assertEquals(directory.resolve("impl/pnr/blinky.fs"), project.bitstream)
        assertEquals(directory.resolve("impl/gwsynthesis/blinky_syn.rpt.html"), project.synthesisOutput("_syn.rpt.html"))
        assertEquals(directory.resolve("impl/pnr/blinky.rpt.txt"), project.pnrOutput("rpt.txt"))
        assertTrue(directory.resolve("inc") in project.includeDirectories)
        assertEquals(listOf("blinky"), project.fileList().topModules)
    }

    @Test
    fun outputsFollowTheBaseNameOfTheOptions() {
        val directory = root.resolve("p")
        val project = GowinEdaProject.parse(directory.resolve("p.gprj"), gprj, """{ "OUTPUT_BASE_NAME" : "fpga" }""")
        assertEquals(directory.resolve("impl/pnr/fpga.fs"), project.bitstream)
        assertNull(project.options.topModule)
        assertEquals(GowinEdaOptions(), GowinEdaOptions.parse("not json"))
    }

    @Test
    fun fileKindsFollowGowinEda() {
        assertEquals(GowinEdaFileKind.VERILOG, GowinEdaFileKind.ofFile(Path.of("a.sv")))
        assertEquals(GowinEdaFileKind.VHDL, GowinEdaFileKind.ofFile(Path.of("a.vhdl")))
        assertEquals(GowinEdaFileKind.NETLIST, GowinEdaFileKind.ofFile(Path.of("a.vg")))
        assertEquals(GowinEdaFileKind.CST, GowinEdaFileKind.ofFile(Path.of("a.CST")))
        assertNull(GowinEdaFileKind.ofFile(Path.of("a.txt")))
        assertEquals(GowinEdaFileKind.OTHER, GowinEdaFileKind.ofType("file.ipc"))
    }

    @Test
    fun addsFilesAmongThoseOfTheirKind() {
        val withSource = GowinEdaProjectText.addFile(gprj, "src/divider.v", GowinEdaFileKind.VERILOG)
        val lines = withSource.lines().map { it.trim() }
        assertEquals(lines.indexOf("""<File path="src/old.v" type="file.verilog" enable="0"/>""") + 1,
            lines.indexOf("""<File path="src/divider.v" type="file.verilog" enable="1"/>"""))
        val withTiming = GowinEdaProjectText.addFile(withSource, "src/extra.sdc", GowinEdaFileKind.SDC)
        assertTrue(withTiming.lines().last { "file.sdc" in it }.contains("extra.sdc"))
        // A file that is listed already is not listed again.
        assertEquals(withTiming, GowinEdaProjectText.addFile(withTiming, "src/divider.v", GowinEdaFileKind.VERILOG))
        // The indentation is GOWIN EDA's.
        assertTrue(withSource.contains("\n        <File path=\"src/divider.v\""))
    }

    @Test
    fun addsTheFirstFileToAnEmptyList() {
        val empty = GowinEdaProjectText.projectFile(GowinEdaDevice("GW1NR-9C", "GW1NR-LV9QN88PC6/I5", "gw1nr9c-004"), emptyList())
        assertTrue(empty.contains("<FileList/>"))
        val added = GowinEdaProjectText.addFile(empty, "top.v", GowinEdaFileKind.VERILOG)
        val project = GowinEdaProject.parse(root.resolve("p.gprj"), added)
        assertEquals(listOf(root.resolve("top.v")), project.verilogSources)
        assertEquals(empty, GowinEdaProjectText.removeFiles(added) { it == "top.v" })
    }

    @Test
    fun removesEnablesAndChangesTheDevice() {
        val removed = GowinEdaProjectText.removeFiles(gprj) { it == "src/old.v" }
        assertFalse(removed.contains("old.v"))
        assertEquals(gprj.lines().size - 1, removed.lines().size)
        val enabled = GowinEdaProjectText.setEnabled(gprj, true) { it == "src/old.v" }
        assertTrue(GowinEdaProject.parse(root.resolve("p.gprj"), enabled).files.all { it.enabled })
        val device = GowinEdaDevice("GW2AR-18C", "GW2AR-LV18QN88C8/I7", "gw2ar18c-000")
        val changed = GowinEdaProjectText.setDevice(gprj, device)
        assertEquals(device, GowinEdaProject.parse(root.resolve("p.gprj"), changed).device)
        assertEquals(gprj.lines().size, changed.lines().size)
    }

    @Test
    fun pathsAreRelativeBelowTheProject() {
        val directory = root.resolve("p")
        assertEquals("src/top.v", GowinEdaProjectText.path(directory.resolve("src/top.v"), directory))
        assertEquals(root.resolve("lib/x.v").toString().replace('\\', '/'), GowinEdaProjectText.path(root.resolve("lib/x.v"), directory))
        assertTrue(GowinEdaProjectText.sameFile("src\\top.v", directory.resolve("src/top.v"), directory))
    }

    @Test
    fun optionsKeepTheirOrderAndSpacing() {
        val created = GowinEdaOptionsText.setString(null, "TopModule", "blinky")
        assertEquals("blinky", GowinEdaOptions.parse(created).topModule)
        assertTrue("gw_sh ignores an options file without its version", created.contains("\"Process_Configuration_Verion\" : \"1.0\""))

        val gowin = "{\n \"Allow_Duplicate_Modules\" : false,\n \"TopModule\" : \"\",\n \"Verilog_Standard\" : \"Vlg_Std_2001\"\n}"
        val replaced = GowinEdaOptionsText.setString(gowin, "TopModule", "top")
        assertEquals(gowin.replace("\"TopModule\" : \"\"", "\"TopModule\" : \"top\""), replaced)

        val inserted = GowinEdaOptionsText.setString(gowin, "OUTPUT_BASE_NAME", "fpga")
        val keys = Regex("\"(\\w+)\" :").findAll(inserted).map { it.groupValues[1] }.toList()
        assertEquals(listOf("Allow_Duplicate_Modules", "OUTPUT_BASE_NAME", "TopModule", "Verilog_Standard"), keys)
        assertEquals("fpga", GowinEdaOptions.parse(inserted).outputBaseName)

        val appended = GowinEdaOptionsText.setString("{\n \"A\" : 1\n}\n", "Z", "last")
        assertEquals("{\n \"A\" : 1,\n \"Z\" : \"last\"\n}\n", appended)
    }

    @Test
    fun ofReadsTheOptionsBesideTheProject() {
        val directory = Files.createDirectories(root.resolve("q"))
        val file = Files.writeString(directory.resolve("q.gprj"), gprj)
        Files.createDirectories(directory.resolve("impl"))
        Files.writeString(directory.resolve("impl/project_process_config.json"), GowinEdaOptionsText.setString(null, "TopModule", "q_top"))
        assertEquals("q_top", GowinEdaProject.of(file)?.topModule)
        assertNull(GowinEdaProject.of(directory.resolve("missing.gprj")))
        assertEquals(listOf(file), GowinEdaProjectService.scan(root))
    }
}
