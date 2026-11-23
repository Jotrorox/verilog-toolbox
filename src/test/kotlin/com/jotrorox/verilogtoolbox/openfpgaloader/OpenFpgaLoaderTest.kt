package com.jotrorox.verilogtoolbox.openfpgaloader

import com.jotrorox.verilogtoolbox.openfpgaloader.OpenFpgaLoaderMessages.Os
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/** Outputs as openFPGALoader 1.2.0 prints them, with a Tang Primer 25K on its Sipeed debugger. */
class OpenFpgaLoaderTest {
    @Test
    fun boardsAreListed() {
        val output = """
            board name                 cable_name         fpga_part
            arty_a7_35t                digilent           xc7a35tcsg324
            tangprimer25k              ft2232             Undefined
            LD-SCHOKO                  Undefined          LFE5U-45F-6CABGA256
        """.trimIndent()
        assertEquals(
            listOf(
                OpenFpgaLoader.Board("arty_a7_35t", "digilent", "xc7a35tcsg324"),
                OpenFpgaLoader.Board("tangprimer25k", "ft2232", null),
                OpenFpgaLoader.Board("LD-SCHOKO", null, "LFE5U-45F-6CABGA256"),
            ),
            OpenFpgaLoader.parseBoards(output),
        )
        assertEquals("digilent, xc7a35tcsg324", OpenFpgaLoader.parseBoards(output).first().details)
    }

    @Test
    fun cablesAreListed() {
        val output = "cable name               vid:pid\nanlogicCable             0x0547:1002\nft2232                   0x0403:6010\ndfu                      0x0000:0000\n"
        assertEquals(
            listOf(
                OpenFpgaLoader.Cable("anlogicCable", "0x0547:1002"),
                OpenFpgaLoader.Cable("ft2232", "0x0403:6010"),
                OpenFpgaLoader.Cable("dfu", "0x0000:0000"),
            ),
            OpenFpgaLoader.parseCables(output),
        )
    }

    @Test
    fun probesAreReadByTheColumnsOfTheHeader() {
        val output = """
            Bus device vid:pid       probe_type manufacturer serial     product
            001 009    0x0403:0x6010 FTDI2232   SIPEED       2025030317 USB Debugger
            002 004    0x0403:0x6014 FTDI232H   Digilent Inc 210319A    Digilent USB Device
        """.trimIndent()
        val probes = OpenFpgaLoader.parseProbes(output)
        assertEquals(OpenFpgaLoader.Probe("0x0403:0x6010", "FTDI2232", "SIPEED", "2025030317", "USB Debugger"), probes[0])
        assertEquals("SIPEED USB Debugger (FTDI2232, serial 2025030317)", probes[0].description)
        // A manufacturer with a space stays whole.
        assertEquals(OpenFpgaLoader.Probe("0x0403:0x6014", "FTDI232H", "Digilent Inc", "210319A", "Digilent USB Device"), probes[1])
    }

    @Test
    fun probeWiderThanItsColumnIsSplitAtSpaces() {
        val output = "Bus device vid:pid       probe_type manufacturer serial     product\n" +
            "001 002    0x1209:0xc0ca dirtyJtagProbe Jean_THOMAS ABC123 DirtyJTAG\n"
        assertEquals(
            listOf(OpenFpgaLoader.Probe("0x1209:0xc0ca", "dirtyJtagProbe", "Jean_THOMAS", "ABC123", "DirtyJTAG")),
            OpenFpgaLoader.parseProbes(output),
        )
    }

    @Test
    fun detectedDevicesAreRead() {
        val output = "No cable or board specified: using direct ft2232 interface\n" +
            "Jtag frequency : requested 6.00MHz    -> real 6.00MHz   \n" +
            "index 0:\n\tidcode 0x1281b\n\tmanufacturer Gowin\n\tfamily GW5A\n\tmodel  GW5A-25\n\tirlength 8\n" +
            "index 1:\n\tidcode 0x4ba00477\n\tmanufacturer xilinx\n\tfamily zynq\n\tmodel  xc7z020\n\tirlength 6\n"
        val devices = OpenFpgaLoader.parseDevices(output)
        assertEquals(
            listOf(
                OpenFpgaLoader.Device(0, "0x1281b", "Gowin", "GW5A", "GW5A-25"),
                OpenFpgaLoader.Device(1, "0x4ba00477", "xilinx", "zynq", "xc7z020"),
            ),
            devices,
        )
        assertEquals("Gowin GW5A-25", devices[0].description)
        assertEquals("Xilinx xc7z020", devices[1].description)
        assertEquals(emptyList<OpenFpgaLoader.Device>(), OpenFpgaLoader.parseDevices("unable to open ftdi device: -3 (device not found)\n"))
    }

    @Test
    fun detectionIsDescribed() {
        val probe = OpenFpgaLoader.Probe("0x0403:0x6010", "FTDI2232", "SIPEED", "2025030317", "USB Debugger")
        val found = OpenFpgaLoader.Detection(listOf(probe), listOf(OpenFpgaLoader.Device(0, "0x1281b", "Gowin", "GW5A", "GW5A-25")), null)
        assertEquals(
            "Found Gowin GW5A-25 over board tangprimer25k. Probe: SIPEED USB Debugger (FTDI2232, serial 2025030317).",
            OpenFpgaLoader.describe(found, FpgaTarget(board = "tangprimer25k")),
        )
        val missing = OpenFpgaLoader.Detection(emptyList(), emptyList(), "JTAG init failed with: unable to open ftdi device")
        assertEquals(
            "No FPGA was found: JTAG init failed with: unable to open ftdi device. No USB probe is plugged in.",
            OpenFpgaLoader.describe(missing, FpgaTarget()),
        )
        assertEquals(
            "No FPGA was found over board tangprimer25k with cable digilent_hs2: unable to open ftdi device: -3 (device not found). " +
                "No USB probe is plugged in.",
            OpenFpgaLoader.describe(
                OpenFpgaLoader.Detection(emptyList(), emptyList(), "unable to open ftdi device: -3 (device not found)"),
                FpgaTarget(board = "tangprimer25k", cable = "digilent_hs2"),
            ),
        )
    }

    @Test
    fun versionAndOptionsAreRead() {
        assertEquals("1.2.0", OpenFpgaLoader.parseVersion("openFPGALoader v1.2.0\n"))
        assertEquals("0.12.1", OpenFpgaLoader.parseVersion("openFPGALoader v0.12.1-12-gabcdef\n"))
        assertNull(OpenFpgaLoader.parseVersion("something else"))
        val options = OpenFpgaLoader.parseOptions(
            "  -f, --write-flash             write bitstream in flash (default: false)\n" +
                "      --force-terminal-mode     force progress bar output as if connected to\n"
        )
        assertTrue("--write-flash" in options)
        assertTrue(OpenFpgaLoader.Info("1.2.0", options).hasTerminalMode)
        assertFalse(OpenFpgaLoader.Info("0.10.0", setOf("--write-flash")).hasTerminalMode)
    }

    @Test
    fun argumentsProgramTheChosenDestination() {
        val file = Path.of("build", "top.fs")
        assertEquals(
            listOf("--board", "tangprimer25k", "--force-terminal-mode", file.toString()),
            FpgaTarget(board = "tangprimer25k").programArguments(file, terminalMode = true),
        )
        assertEquals(
            listOf("--cable", "digilent_hs2", "--fpga-part", "xc7a35tcsg324", "--write-flash", "--verify", "--freq", "1000000", file.toString()),
            FpgaTarget(cable = "digilent_hs2", fpgaPart = "xc7a35tcsg324", destination = FpgaDestination.FLASH, verify = true, options = "--freq 1000000")
                .programArguments(file, terminalMode = false),
        )
        assertEquals(
            listOf("--write-flash", "--external-flash", file.toString()),
            FpgaTarget(destination = FpgaDestination.EXTERNAL_FLASH).programArguments(file, terminalMode = false),
        )
        // Verifying is for flash only; openFPGALoader refuses it for SRAM.
        assertEquals(listOf(file.toString()), FpgaTarget(verify = true).programArguments(file, terminalMode = false))
    }

    @Test
    fun connectionIsDescribed() {
        assertEquals("board tangprimer25k", FpgaTarget(board = "tangprimer25k").connection)
        assertEquals("board icebreaker with cable ft2232", FpgaTarget(board = "icebreaker", cable = "ft2232").connection)
        assertEquals("cable digilent", FpgaTarget(cable = "digilent").connection)
        assertNull(FpgaTarget().connection)
    }

    @Test
    fun bitstreamsAreKnownByExtension() {
        for (name in listOf("top.fs", "top.BIT", "top.bin", "top.jed", "top.rbf", "top.sof", "top.svf")) {
            assertTrue(name, OpenFpgaLoader.isBitstream(Path.of(name)))
        }
        // Loading the place-and-route JSON "succeeds", with nonsense in the FPGA.
        for (name in listOf("top-pnr.json", "top.v", "Makefile")) assertFalse(name, OpenFpgaLoader.isBitstream(Path.of(name)))
    }

    @Test
    fun errorsAndWarningsAreRecognized() {
        assertTrue(OpenFpgaLoaderMessages.isError("Error: cannot find board 'x'"))
        assertTrue(OpenFpgaLoaderMessages.isError("JTAG init failed with: unable to open ftdi device"))
        assertTrue(OpenFpgaLoaderMessages.isError("unable to open ftdi device: -3 (device not found)\n"))
        assertFalse(OpenFpgaLoaderMessages.isError("Load SRAM: [=====] 100.00%"))
        assertTrue(OpenFpgaLoaderMessages.isWarning("No cable or board specified: using direct ft2232 interface\n"))
        assertTrue(OpenFpgaLoaderMessages.isWarning("Warning: IDCODE not found"))
        assertEquals(
            "unable to open ftdi device: -3 (device not found)",
            OpenFpgaLoaderMessages.firstError(listOf("Jtag frequency : requested 6.00MHz", "unable to open ftdi device: -3 (device not found)", "JTAG init failed")),
        )
    }

    private fun hints(output: String, target: FpgaTarget = FpgaTarget(), os: Os = Os.MAC) =
        OpenFpgaLoaderMessages.hints(output.lines(), target, os)

    @Test
    fun missingCableIsExplained() {
        val output = "unable to open ftdi device: -3 (device not found)\nJTAG init failed with: unable to open ftdi device"
        val hint = hints(output).single()
        assertTrue(hint, hint.startsWith("No programming cable was found."))
        assertTrue(hint, "set the board" in hint)
        // With a board the default cable is not the question; on Linux udev rules are.
        val linux = hints(output, FpgaTarget(board = "tangprimer25k"), Os.LINUX).single()
        assertFalse(linux, "set the board" in linux)
        assertTrue(linux, OpenFpgaLoader.UDEV_URL in linux)
        assertTrue(OpenFpgaLoader.ZADIG_URL in hints(output, os = Os.WINDOWS).single())
    }

    @Test
    fun permissionProblemIsNotTakenForAMissingCable() {
        val hint = hints("Couldn't open device. Check permissions for 001/009\nJTAG init failed with: fail to open device", os = Os.LINUX).single()
        assertTrue(hint, hint.startsWith("The cable is there, but this user may not open it."))
    }

    @Test
    fun otherCommonErrorsAreExplained() {
        assertTrue(hints("Error: cannot find board 'tangprimer26k'").single().startsWith("openFPGALoader does not know the board 'tangprimer26k'."))
        assertTrue(hints("Error: no device found").single().startsWith("The cable answered, but no FPGA did."))
        assertTrue(hints("mismatch between target's idcode and bitstream idcode").single().startsWith("The bitstream was built for another FPGA"))
        assertTrue(hints("Can't program SPI flash: missing device-package information").single().contains("FPGA part"))
        assertTrue(hints("Error: fail to claim interface with error code -6").single().startsWith("Another program has the cable open"))
        assertTrue(hints("Error: Can't erase flash: block protection is set").single().contains("--unprotect-flash"))
        assertTrue(hints("Error parsing options: Option 'x' does not exist").single().contains("options"))
        assertEquals(emptyList<String>(), hints("Error: something nobody has seen before"))
    }
}
