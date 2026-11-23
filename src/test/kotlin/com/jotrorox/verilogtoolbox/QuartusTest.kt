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

class QuartusTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val root: Path get() = temporary.root.toPath().toRealPath()

    /** An installation as the installer lays it out: `<root>/<version>/quartus/<bin>` with the tools in it. */
    private fun install(directory: String, version: String, edition: String? = "lite", vararg tools: String = arrayOf("quartus_sh", "quartus_map")): Path {
        val quartus = root.resolve(directory).resolve(version).resolve("quartus")
        val bin = Files.createDirectories(quartus.resolve("bin64"))
        for (tool in tools) Files.writeString(bin.resolve(Quartus.binary(tool)), "").toFile().setExecutable(true)
        if (edition != null) {
            Files.writeString(quartus.resolve("version.txt"), "Version=25.1.0.1129\n[ACDS]\nVersion=$version.0.1129\nEdition=$edition\n")
        }
        return bin
    }

    private fun find(configured: String = "", environment: Map<String, String> = emptyMap(), vararg installRoots: String) =
        Quartus.find(configured, { environment[it] }, installRoots.map { root.resolve(it) })

    @Test
    fun configuredPathMayBeAnyDirectoryOfTheInstallation() {
        val bin = install("altera_lite", "25.1std")
        for (path in listOf(bin, bin.parent, bin.parent.parent, bin.resolve(Quartus.binary("quartus_sh")))) {
            assertEquals(path.toString(), bin, find(path.toString())?.binDirectory)
        }
        // A path that is set but wrong is not replaced by a guess.
        assertNull(find(root.resolve("nowhere").toString(), installRoots = arrayOf("altera_lite")))
    }

    @Test
    fun environmentComesBeforeTheDefaultLocations() {
        val pro = install("altera_pro", "24.3", "pro", "quartus_sh", "quartus_syn")
        install("altera_lite", "25.1std")
        val found = find(environment = mapOf("QUARTUS_ROOTDIR" to pro.parent.toString()), installRoots = arrayOf("altera_lite"))
        assertEquals(pro, found?.binDirectory)
    }

    @Test
    fun toolOnPathIsFound() {
        val bin = install("tools", "23.1std")
        assertEquals(bin, find(environment = mapOf("PATH" to bin.toString()))?.binDirectory)
    }

    @Test
    fun newestVersionOfTheDefaultLocationsIsUsed() {
        install("intelFPGA_lite", "20.1")
        install("intelFPGA_lite", "9.1")
        val newest = install("altera_lite", "25.1std")
        install("altera_lite", "23.1std")
        assertEquals(newest, find(installRoots = arrayOf("intelFPGA_lite", "altera_lite", "missing"))?.binDirectory)
        assertNull(find(installRoots = arrayOf("missing")))
    }

    @Test
    fun versionAndEditionAreRead() {
        val lite = QuartusInstallation(install("altera_lite", "25.1std"))
        assertEquals("25.1std.0", lite.version)
        assertEquals("Lite", lite.edition)
        assertEquals("Quartus Prime 25.1std.0 Lite Edition", lite.displayName)
        assertFalse(lite.isPro)
        assertTrue(lite.tool("quartus_map") != null && lite.tool("quartus_pgm") == null)
    }

    @Test
    fun proEditionIsKnownByItsToolsToo() {
        assertTrue(QuartusInstallation(install("altera_pro", "24.3", "pro", "quartus_sh", "quartus_syn")).isPro)
        val unnamed = QuartusInstallation(install("custom", "24.1", null, "quartus_sh", "quartus_syn"))
        assertTrue(unnamed.isPro)
        assertEquals("Quartus Prime", unnamed.displayName)
    }

    @Test
    fun simulationLibrariesAreThoseThatExist() {
        val bin = install("altera_lite", "25.1std")
        val library = Files.createDirectories(bin.parent.resolve("eda").resolve("sim_lib"))
        Files.writeString(library.resolve("altera_mf.v"), "")
        Files.writeString(library.resolve("220model.v"), "")
        Files.writeString(library.resolve("cyclonev_atoms.v"), "")
        assertEquals(listOf("220model.v", "altera_mf.v"), QuartusInstallation(bin).simulationLibraries.map { it.fileName.toString() })
    }

    @Test
    fun cablesAndDevicesAreParsed() {
        val list = "Info: Command: quartus_pgm -l\n1) USB-Blaster [USB-0]\n2) DE-SoC [USB-1]\nInfo: Quartus Prime Programmer was successful. 0 errors, 0 warnings\n"
        assertEquals(
            listOf(QuartusProgrammer.Cable(1, "USB-Blaster [USB-0]"), QuartusProgrammer.Cable(2, "DE-SoC [USB-1]")),
            QuartusProgrammer.parseCables(list),
        )
        assertTrue(QuartusProgrammer.parseCables("Info: Command: quartus_pgm -l\nInfo: Quartus Prime Programmer was successful.\n").isEmpty())
        val chain = "1) DE-SoC [USB-1]\n  4BA00477   SOCVHPS\n  02D120DD   5CSEBA6(.|ES)/5CSEMA6/..\n\nInfo: Quartus Prime Programmer was successful.\n"
        assertEquals(
            listOf(QuartusProgrammer.Device(1, "4BA00477", "SOCVHPS"), QuartusProgrammer.Device(2, "02D120DD", "5CSEBA6(.|ES)/5CSEMA6/..")),
            QuartusProgrammer.parseDevices(chain),
        )
    }

    @Test
    fun deviceOfTheProjectIsFoundInAChain() {
        val max10 = QuartusProgrammer.Device(1, "031050DD", "10M50DA(.|ES)/10M50DC")
        val hps = QuartusProgrammer.Device(1, "4BA00477", "SOCVHPS")
        val fpga = QuartusProgrammer.Device(2, "02D120DD", "5CSEBA6(.|ES)/5CSEMA6/..")
        // A chain of one needs no position.
        assertNull(QuartusProgrammer.deviceIndex(listOf(max10), "10M50DAF484C7G"))
        assertEquals(2, QuartusProgrammer.deviceIndex(listOf(hps, fpga), "5CSEMA6F31C6"))
        // A part the names do not list is still the one that is not the processor.
        assertEquals(2, QuartusProgrammer.deviceIndex(listOf(hps, fpga), "5CSEMA5F31C6"))
        assertNull(QuartusProgrammer.deviceIndex(listOf(max10, fpga), "EP4CE22F17C6"))
    }

    @Test
    fun programmerArguments() {
        val sof = Path.of("output_files", "blinky.sof")
        assertEquals("p", QuartusProgrammer.defaultOperation(sof))
        assertEquals("pv", QuartusProgrammer.defaultOperation(Path.of("blinky.pof")))
        assertEquals(
            listOf("-c", "USB-Blaster [USB-0]", "-m", "JTAG", "-o", "p;$sof"),
            QuartusProgrammer.arguments("USB-Blaster [USB-0]", "", "p", sof, null, ""),
        )
        assertEquals(
            listOf("-c", "DE-SoC [USB-1]", "-m", "JTAG", "--bgp", "-o", "p;$sof@2"),
            QuartusProgrammer.arguments("DE-SoC [USB-1]", "JTAG", "p", sof, 2, "--bgp"),
        )
    }

    @Test
    fun flowsKnowWhatTheyDo() {
        assertTrue(QuartusFlow.COMPILE_AND_PROGRAM.programs && QuartusFlow.COMPILE_AND_PROGRAM.compiles)
        assertTrue(QuartusFlow.PROGRAM.programs && !QuartusFlow.PROGRAM.compiles)
        assertFalse(QuartusFlow.ANALYSIS.programs)
        assertEquals(QuartusFlow.COMPILE, QuartusFlow.of("no such flow"))
        assertEquals(QuartusImage.POF, QuartusImage.of("POF"))
    }
}
