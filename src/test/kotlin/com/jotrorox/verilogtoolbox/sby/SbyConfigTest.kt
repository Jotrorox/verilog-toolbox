package com.jotrorox.verilogtoolbox.sby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SbyConfigTest {
    private val counter = """
        [tasks]
        bmc
        prove default
        cover # reachability

        [options]
        bmc: mode bmc
        prove: mode prove
        cover: mode cover

        [script]
        read -formal counter.sv
        prove: read -formal extra.sv
        prep -top counter

        [files]
        rtl/counter.sv
        prove: extra.sv ../shared/extra.sv
    """.trimIndent()

    @Test
    fun tasksWithTheirOffsets() {
        val tasks = SbyConfig.tasks(counter)
        assertEquals(listOf("bmc", "prove", "cover"), tasks.map { it.name })
        assertEquals(listOf(false, true, false), tasks.map { it.isDefault })
        for (task in tasks) assertEquals(task.name, counter.substring(task.offset, task.offset + task.name.length))
    }

    @Test
    fun defaultTasksAreTheMarkedOnesElseAll() {
        assertEquals(listOf("prove"), SbyConfig.defaultTasks(counter))
        assertEquals(listOf("a", "b"), SbyConfig.defaultTasks("[tasks]\na\nb\n"))
        assertEquals(emptyList<String>(), SbyConfig.defaultTasks("[options]\nmode bmc\n"))
    }

    @Test
    fun severalTasksOnALineAndGlobs() {
        val tasks = SbyConfig.tasks("[tasks]\nprove_a prove_b: proofs\ncover_a\nprove_*: default\n")
        assertEquals(listOf("prove_a", "prove_b", "cover_a"), tasks.map { it.name })
        assertEquals(listOf(true, true, false), tasks.map { it.isDefault })
    }

    @Test
    fun filesWithTheirNameInTheTaskDirectory() = assertEquals(
        listOf(SbyConfig.FileEntry("counter.sv", "rtl/counter.sv"), SbyConfig.FileEntry("extra.sv", "../shared/extra.sv")),
        SbyConfig.files(counter),
    )

    @Test
    fun topModule() {
        assertEquals("counter", SbyConfig.topModule(counter))
        assertEquals("fifo", SbyConfig.topModule("[script]\nread -formal fifo.sv\nhierarchy -check -top fifo\n"))
        assertNull(SbyConfig.topModule("[script]\nread -formal fifo.sv\nprep\n"))
    }
}
