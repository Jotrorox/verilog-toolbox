package com.jotrorox.verilogtoolbox.gowin.eda

import com.jotrorox.verilogtoolbox.gowin.GowinBoards
import com.jotrorox.verilogtoolbox.verilog.VerilogModuleHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GowinEdaPinsTest {
    private val cst = """
        // Tang Nano 9K
        IO_LOC "clk" 52;
        IO_PORT "clk" IO_TYPE=LVCMOS33 PULL_MODE=UP;
        IO_LOC "led[0]" 10;
        IO_LOC "rstn" 39,40;
    """.trimIndent() + "\n"

    @Test
    fun readsStatements() {
        val statements = GowinEdaCstText.statements(cst)
        assertEquals(listOf("IO_LOC", "IO_PORT", "IO_LOC", "IO_LOC"), statements.map { it.keyword })
        assertEquals(listOf("52"), statements[0].locations)
        assertEquals("LVCMOS33", statements[1].attribute("io_type"))
        assertEquals("UP", statements[1].attribute("PULL_MODE"))
        assertEquals(listOf("39", "40"), statements[3].locations)
    }

    @Test
    fun setsAndClearsLocations() {
        val moved = GowinEdaCstText.setLocation(cst, "led[0]", "11")
        assertTrue(moved.contains("IO_LOC \"led[0]\" 11;"))
        assertEquals(cst.lines().size, moved.lines().size)
        val added = GowinEdaCstText.setLocation(cst, "led[1]", "13")
        assertEquals("IO_LOC \"led[1]\" 13;", added.lines()[5])
        val cleared = GowinEdaCstText.setLocation(cst, "rstn", null)
        assertFalse(cleared.contains("rstn"))
        assertEquals(cst.lines().size - 1, cleared.lines().size)
    }

    @Test
    fun setsAndClearsAttributes() {
        val changed = GowinEdaCstText.setAttribute(cst, "clk", "IO_TYPE", "LVCMOS18")
        assertTrue(changed.contains("IO_PORT \"clk\" IO_TYPE=LVCMOS18 PULL_MODE=UP;"))
        val appended = GowinEdaCstText.setAttribute(cst, "clk", "DRIVE", "8")
        assertTrue(appended.contains("IO_PORT \"clk\" IO_TYPE=LVCMOS33 PULL_MODE=UP DRIVE=8;"))
        val removed = GowinEdaCstText.setAttribute(cst, "clk", "PULL_MODE", null)
        assertTrue(removed.contains("IO_PORT \"clk\" IO_TYPE=LVCMOS33;"))
        // The last attribute goes with its statement.
        val gone = GowinEdaCstText.setAttribute(removed, "clk", "IO_TYPE", null)
        assertFalse(gone.contains("IO_PORT"))
        // A new statement goes next to where the port is placed.
        val created = GowinEdaCstText.setAttribute(cst, "led[0]", "IO_TYPE", "LVCMOS18")
        val lines = created.lines()
        assertEquals("IO_PORT \"led[0]\" IO_TYPE=LVCMOS18;", lines[lines.indexOf("IO_LOC \"led[0]\" 10;") + 1])
    }

    @Test
    fun rowsAreTheBitsOfThePortsAndTheConstraintsLeftOver() {
        val module = VerilogModuleHeaders.modules(
            "module blinky #(parameter N = 2) (input clk, input rst_n, output reg [N-1:0] led); endmodule",
        ).single()
        val placed = listOf(GowinEdaReports.PlacedPort("led[1]", "11", "3", false, "out", "LVCMOS18", "UP", "8", "1.8"))
        val rows = GowinEdaPins.rows(module, GowinEdaCstText.statements(cst), placed)
        assertEquals(listOf("clk", "rst_n", "led[1]", "led[0]", "rstn"), rows.map { it.name })
        val clk = rows[0]
        assertEquals("52", clk.location)
        assertEquals("LVCMOS33", clk.ioType)
        assertEquals("UP", clk.pullMode)
        assertTrue(rows[1].isUnassigned)
        assertEquals("11", rows[2].placed?.location)
        assertTrue("constrained but no port", rows[4].node.isOrphan)
        assertEquals("39,40", rows[4].location)
    }

    @Test
    fun findsConflictsAndFreePins() {
        val conflicts = GowinEdaPins.conflicts(mapOf("a" to "52", "b" to "52", "c" to "39,40", "d" to "40", "e" to null))
        assertEquals(listOf("b"), conflicts["a"])
        assertEquals(listOf("c"), conflicts["d"])
        assertNull(conflicts["e"])
        val pins = listOf(
            GowinEdaPackagePin("52", "IOR17A", "I/O", 1, "GCLKT_3", "P", "IOR17B"),
            GowinEdaPackagePin("51", "IOR17B", "I/O", 1, null, "N", "IOR17A"),
            GowinEdaPackagePin("1", "VCC", "POWER", null, null, null, null),
        )
        val rows = GowinEdaPins.rows(null, GowinEdaCstText.statements(cst), emptyList())
        assertEquals(listOf("51"), GowinEdaPins.freePins(pins, rows).map { it.index })
        assertEquals(listOf("52", "51"), GowinEdaPins.freePins(pins, rows, "clk").map { it.index })
        assertTrue(GowinEdaPins.isLocation("B3"))
        assertTrue(GowinEdaPins.isLocation("39, 40"))
        assertFalse(GowinEdaPins.isLocation("PIN_12"))
    }

    @Test
    fun readsTheDeviceTables() {
        val catalog = GowinEdaDevices.parseCatalog(
            """
            gw1nr9-012,GW1NR-LV9QN88PC6/I5,GW1NR,GW1NR-9,GW1NR-9,,QFN88P,LV,C6/I5,6,71,8640,6480,,16Kb,468Kb,608Kb,5,2,
            gw1nr9c-004,GW1NR-LV9QN88PC6/I5,GW1NR,GW1NR-9C,GW1NR-9,C,QFN88P,LV,C6/I5,6,71,8640,6480,,16Kb,468Kb,608Kb,5,2,
            gw2a18-002,GW2A-LV18PG256C8/I7,GW2A,GW2A-18,GW2A-18,,PBGA256,LV,C8/I7,8,207,20736,15552,,40Kb,828Kb,,4,
            gw2a18c-011,GW2A-LV18PG256C8/I7,GW2A,GW2A-18C,GW2A-18,C,PBGA256,LV,C8/I7,8,207,20736,15552,,40Kb,828Kb,,4,
            """.trimIndent(),
        )
        assertEquals(4, catalog.size)
        val nano9k = GowinEdaDevices.forBoard(catalog, GowinBoards.byId("tangnano9k")!!)
        assertEquals("GW1NR-9C", nano9k?.device)
        assertEquals("8,640 LUTs, 6,480 registers, 71 I/Os in QFN88P, speed C6/I5", nano9k?.summary)
        assertEquals("GW2A-18C", GowinEdaDevices.forBoard(catalog, GowinBoards.byId("tangprimer20k")!!)?.device)
        assertNull(GowinEdaDevices.forBoard(catalog, GowinBoards.byId("tangnano")!!))
        assertEquals("gw1nr9c-004", GowinEdaDevices.part(catalog, GowinEdaDevice("GW1NR-9C", "GW1NR-LV9QN88PC6/I5", ""))?.id)

        val pins = GowinEdaDevices.parsePackage(
            """{"NAME":"QFN88P","PIN_DATA":[{"INDEX":"1","NAME":"VCC","TYPE":"POWER"},{"INDEX":"52","NAME":"IOR17A","TYPE":"I/O","BANK":1,"CFG":"GCLKT_3","DIFF":"P","PAIR":"IOR17B"}]}""",
        )
        assertEquals(GowinEdaPackagePin("52", "IOR17A", "I/O", 1, "GCLKT_3", "P", "IOR17B"), pins[1])
        assertFalse(pins[0].isIo)

        val table = "\uFEFFNo.,IO_Type,Inout Mode,SW,BFD\n1,LVDS25,True LVDS,LVDS25 (Not Support),LVDS25 (Not Support)\n" +
            "2,LVCMOS33/LVTTL33,,LVCMOS33/LVTTL33,LVCMOS33\n3,MIPI _LP_D,,MIPI_LP,MIPI_LP\n"
        assertEquals(listOf("LVCMOS33", "LVTTL33", "MIPI_LP_D"), GowinEdaDevices.parseIoTable(table))
        assertEquals(listOf("LVCMOS33"), GowinEdaPins.ioTypes(mapOf("output" to listOf("LVCMOS33")), VerilogModuleHeaders.Direction.OUTPUT))
    }
}
