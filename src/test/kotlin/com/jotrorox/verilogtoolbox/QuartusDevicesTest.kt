package com.jotrorox.verilogtoolbox

import org.junit.Assert.assertEquals
import org.junit.Test

class QuartusDevicesTest {
    @Test
    fun readsTheFamiliesAsATclList() {
        assertEquals(listOf("MAX 10"), QuartusDevices.parseList("{MAX 10}\r\n"))
        assertEquals(
            listOf("Cyclone IV E", "Cyclone V", "MAX 10", "MAX II"),
            QuartusDevices.parseList("{Cyclone IV E} {Cyclone V} {MAX 10} {MAX II}\n"),
        )
    }

    @Test
    fun readsThePartsAsWords() {
        val output = "10M08DAF256A7G 10M08DAF256C7G 10M08DAF256I7G  10M50DAF484C7G\r\n"
        assertEquals(
            listOf("10M08DAF256A7G", "10M08DAF256C7G", "10M08DAF256I7G", "10M50DAF484C7G"),
            QuartusDevices.parseList(output),
        )
    }

    @Test
    fun leavesOutWhatTheToolsPrintAroundTheResult() {
        val output = """
            TBBmalloc: skip allocation functions replacement in ucrtbase.dll: unknown prologue for function _msize
            Info: *******************************************************************
            Info: Running Quartus Prime Shell
            {MAX 10} {Cyclone V}
            Info: Quartus Prime Shell was successful. 0 errors, 0 warnings

        """.trimIndent()
        assertEquals(listOf("MAX 10", "Cyclone V"), QuartusDevices.parseList(output))
    }

    @Test
    fun anEmptyAnswerIsAnEmptyList() {
        assertEquals(emptyList<String>(), QuartusDevices.parseList(""))
        assertEquals(emptyList<String>(), QuartusDevices.parseList("\r\n\r\n"))
        assertEquals(emptyList<String>(), QuartusDevices.parseList("TBBmalloc: skip allocation functions replacement\n"))
    }

    @Test
    fun withoutAnInstallationThereAreNoDevices() {
        assertEquals(emptyList<String>(), QuartusDevices.families(null))
        assertEquals(emptyList<String>(), QuartusDevices.parts(null, "MAX 10"))
    }

    private val parts = listOf("10M08DAF256C8G", "10M08SCE144C8G", "10M50DAF484C7G", "10M50DAF484C8G", "10M50SCE144I7G")

    @Test
    fun filtersByEveryTypedWordIgnoringCase() {
        assertEquals(parts, QuartusDevices.filter(parts, ""))
        assertEquals(parts, QuartusDevices.filter(parts, "  "))
        assertEquals(listOf("10M50DAF484C7G", "10M50DAF484C8G"), QuartusDevices.filter(parts, "f484"))
        assertEquals(listOf("10M50DAF484C8G"), QuartusDevices.filter(parts, "f484 c8"))
        assertEquals(emptyList<String>(), QuartusDevices.filter(parts, "5CEBA4"))
    }

    @Test
    fun aTypedPrefixComesFirst() {
        val items = listOf("10M08SCE144C8G", "E144", "E144X")
        assertEquals(listOf("E144", "E144X", "10M08SCE144C8G"), QuartusDevices.filter(items, "e144"))
    }

    @Test
    fun namesAFamilyAsQuartusDoes() {
        val families = listOf("Cyclone V", "MAX 10")
        assertEquals("MAX 10", QuartusDevices.canonicalFamily(families, "max10"))
        assertEquals("MAX 10", QuartusDevices.canonicalFamily(families, " MAX 10 "))
        assertEquals("Cyclone V", QuartusDevices.canonicalFamily(families, "cyclone v"))
        assertEquals("Agilex 7", QuartusDevices.canonicalFamily(families, " Agilex 7"))
        assertEquals("", QuartusDevices.canonicalFamily(families, ""))
    }
}
