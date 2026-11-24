package com.jotrorox.verilogtoolbox.gowin

/**
 * A board the built-in Gowin build knows: what Yosys, nextpnr-himbaechel and gowin_pack are told for its FPGA,
 * and the name openFPGALoader knows it by. The values are those of apicula's examples
 * (`examples/Makefile` and `examples/gw5a/Makefile`).
 */
data class GowinBoard(
    /** The board's name for openFPGALoader's `-b`, which also identifies the preset in run configurations. */
    val id: String,
    val displayName: String,
    /** nextpnr's `--device`: the part with its package and speed grade. */
    val device: String,
    /** gowin_pack's `-d`. */
    val packDevice: String,
    /** nextpnr's `--vopt family=`, for the devices whose part number does not tell it; null for none. */
    val nextpnrFamily: String? = null,
    /** synth_gowin's `-family`; null for its default, `gw1n`. */
    val yosysFamily: String? = null,
    val yosysOptions: List<String> = emptyList(),
    /** Further `--vopt` values for nextpnr, such as `sspi_as_gpio`. */
    val nextpnrOptions: List<String> = emptyList(),
    val packOptions: List<String> = emptyList(),
    /** The board's oscillator, which nextpnr checks the timing against. */
    val clockMhz: Int,
    /** Other names of the board, as constraint files are often named; compared without case and punctuation. */
    val aliases: List<String> = emptyList(),
) {
    override fun toString(): String = displayName
}

object GowinBoards {
    val ALL: List<GowinBoard> = listOf(
        GowinBoard(
            "tangnano", "Sipeed Tang Nano", device = "GW1N-LV1QN48C6/I5", packDevice = "GW1N-1",
            packOptions = listOf("-c"), clockMhz = 24,
        ),
        GowinBoard(
            "tangnano1k", "Sipeed Tang Nano 1K", device = "GW1NZ-LV1QN48C6/I5", packDevice = "GW1NZ-1",
            packOptions = listOf("-c"), clockMhz = 27, aliases = listOf("nano1k"),
        ),
        GowinBoard(
            "tangnano4k", "Sipeed Tang Nano 4K", device = "GW1NSR-LV4CQN48PC7/I6", packDevice = "GW1NS-4",
            packOptions = listOf("-c", "--mspi_as_gpio"), clockMhz = 27, aliases = listOf("nano4k"),
        ),
        GowinBoard(
            "tangnano9k", "Sipeed Tang Nano 9K", device = "GW1NR-LV9QN88PC6/I5", packDevice = "GW1N-9C",
            nextpnrFamily = "GW1N-9C", packOptions = listOf("-c"), clockMhz = 27, aliases = listOf("nano9k"),
        ),
        GowinBoard(
            "tangnano20k", "Sipeed Tang Nano 20K", device = "GW2AR-LV18QN88C8/I7", packDevice = "GW2A-18C",
            nextpnrFamily = "GW2A-18C", yosysFamily = "gw2a", packOptions = listOf("-c"), clockMhz = 27,
            aliases = listOf("nano20k"),
        ),
        GowinBoard(
            "tangprimer20k", "Sipeed Tang Primer 20K", device = "GW2A-LV18PG256C8/I7", packDevice = "GW2A-18",
            nextpnrFamily = "GW2A-18", yosysFamily = "gw2a", packOptions = listOf("-c"), clockMhz = 27,
            aliases = listOf("primer20k"),
        ),
        GowinBoard(
            "tangprimer25k", "Sipeed Tang Primer 25K", device = "GW5A-LV25MG121NES", packDevice = "GW5A-25A",
            yosysFamily = "gw5a", yosysOptions = listOf("-nolutram"), nextpnrOptions = listOf("sspi_as_gpio"),
            packOptions = listOf("--sspi_as_gpio", "--cpu_as_gpio"), clockMhz = 50, aliases = listOf("primer25k"),
        ),
        GowinBoard(
            "tangmega138k", "Sipeed Tang Mega 138K", device = "GW5AST-LV138PG484AC1/I0", packDevice = "GW5AST-138C",
            yosysFamily = "gw5a", packOptions = listOf("--cpu_as_gpio"), clockMhz = 50, aliases = listOf("mega138k"),
        ),
    )

    fun byId(id: String?): GowinBoard? = ALL.firstOrNull { it.id == id?.trim() }

    /**
     * The board [text] names, such as a constraint file's name (`primer25k.cst`) or its first comment
     * (`// Tang Primer 25K pin constraints`); null when it names none or more than one.
     */
    fun named(text: String): GowinBoard? {
        val normalized = normalize(text)
        val found = ALL.filter { board -> (listOf(board.id) + board.aliases).any { normalize(it) in normalized } }
        // "tangnano" is in every "tangnanoNk"; the longer name is the one meant.
        val specific = found.filter { board -> found.none { other -> other !== board && normalize(other.id).contains(normalize(board.id)) } }
        return specific.singleOrNull()
    }

    private fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }
}
