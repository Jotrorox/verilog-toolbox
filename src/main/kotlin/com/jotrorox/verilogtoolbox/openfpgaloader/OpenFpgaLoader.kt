package com.jotrorox.verilogtoolbox.openfpgaloader

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.execution.ParametersListUtil
import com.jotrorox.verilogtoolbox.settings.ToolchainSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import kotlin.io.path.extension

object OpenFpgaLoaderIcons {
    @JvmField
    val OpenFpgaLoader: Icon = IconLoader.getIcon("/icons/openFpgaLoader.svg", OpenFpgaLoaderIcons::class.java)
}

/** Where openFPGALoader writes a bitstream. */
enum class FpgaDestination(
    val title: String,
    /** Leads the name of a run configuration: `Load top.fs`, `Flash top.fs`. */
    val verb: String,
) {
    SRAM("SRAM: configures the FPGA until it loses power", "Load"),
    FLASH("Flash: configures the FPGA at every power-up", "Flash"),
    EXTERNAL_FLASH("External flash, for FPGAs with internal and external flash", "Flash");

    val writesFlash: Boolean get() = this != SRAM

    /** The options that choose this destination; SRAM is openFPGALoader's default. */
    val arguments: List<String>
        get() = when (this) {
            SRAM -> emptyList()
            FLASH -> listOf("--write-flash")
            EXTERNAL_FLASH -> listOf("--write-flash", "--external-flash")
        }

    override fun toString(): String = title

    companion object {
        fun of(name: String?): FpgaDestination = entries.firstOrNull { it.name == name } ?: SRAM
    }
}

/**
 * How openFPGALoader reaches the FPGA and where it writes the bitstream. A blank field leaves the choice to
 * openFPGALoader: a [board] names its cable and often its FPGA, and without board or cable it uses an FTDI
 * FT2232, as most development boards have.
 */
data class FpgaTarget(
    val board: String = "",
    val cable: String = "",
    val fpgaPart: String = "",
    val destination: FpgaDestination = FpgaDestination.SRAM,
    val verify: Boolean = false,
    val options: String = "",
) {
    /** The options that pick the cable and the FPGA, which `--detect` takes as well. */
    val connectionArguments: List<String>
        get() = buildList {
            if (board.isNotBlank()) addAll(listOf("--board", board.trim()))
            if (cable.isNotBlank()) addAll(listOf("--cable", cable.trim()))
            if (fpgaPart.isNotBlank()) addAll(listOf("--fpga-part", fpgaPart.trim()))
        }

    /**
     * The arguments that write [file]. [terminalMode] has the progress bar redrawn in place, as in a terminal,
     * which the IDE's console shows as one line that fills up.
     */
    fun programArguments(file: Path, terminalMode: Boolean): List<String> = buildList {
        addAll(connectionArguments)
        addAll(destination.arguments)
        if (verify && destination.writesFlash) add("--verify")
        if (terminalMode) add(OpenFpgaLoader.TERMINAL_MODE)
        addAll(ParametersListUtil.parse(options))
        add(file.toString())
    }

    /** Such as `board tangprimer25k`, `board icebreaker with cable ft2232` or `cable digilent_hs2`; null when openFPGALoader picks the cable. */
    val connection: String?
        get() = when {
            board.isNotBlank() -> "board ${board.trim()}" + (if (cable.isNotBlank()) " with cable ${cable.trim()}" else "")
            cable.isNotBlank() -> "cable ${cable.trim()}"
            else -> null
        }
}

/**
 * [openFPGALoader](https://github.com/trabucayre/openFPGALoader), the universal programmer of the open source
 * FPGA tools: it loads a bitstream into the SRAM or the flash of FPGAs of most vendors, over the cables of
 * most boards. This object knows its lists, parses what it detects and builds its command lines.
 */
object OpenFpgaLoader {
    const val TOOL = "openFPGALoader"
    const val TERMINAL_MODE = "--force-terminal-mode"
    const val DOCUMENTATION_URL = "https://trabucayre.github.io/openFPGALoader/"
    const val UDEV_URL = "https://trabucayre.github.io/openFPGALoader/guide/install.html#udev-rules"
    const val ZADIG_URL = "https://trabucayre.github.io/openFPGALoader/guide/install.html#windows"

    /**
     * What the vendors' and the open tools' flows write and openFPGALoader programs: `.bit` (AMD Xilinx,
     * Lattice ECP5, Anlogic, Efinix), `.bin` (iCE40, raw), `.fs` (Gowin), `.jed` (Lattice MachXO, CPLDs),
     * `.rbf`, `.sof` and `.svf` (Altera), `.mcs` (flash images).
     */
    val BITSTREAM_EXTENSIONS = listOf("bit", "fs", "bin", "jed", "rbf", "sof", "svf", "mcs")

    fun isBitstream(path: Path): Boolean = path.extension.lowercase() in BITSTREAM_EXTENSIONS

    /** A board openFPGALoader knows, from `--list-boards`. */
    data class Board(val name: String, val cable: String?, val fpgaPart: String?) {
        /** Such as `ft2232, xc7a35tcsg324` for the list. */
        val details: String get() = listOfNotNull(cable, fpgaPart).joinToString(", ")
    }

    /** A cable openFPGALoader knows, from `--list-cables`. */
    data class Cable(val name: String, val usbId: String?)

    /** A USB probe that is plugged in, from `--scan-usb`. */
    data class Probe(val usbId: String, val type: String, val manufacturer: String, val serial: String, val product: String) {
        /** Such as `SIPEED USB Debugger (FTDI2232, serial 2025030317)`. */
        val description: String
            get() {
                val name = listOf(manufacturer, product).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { usbId }
                val details = listOfNotNull(type.takeIf { it.isNotBlank() }, serial.takeIf { it.isNotBlank() }?.let { "serial $it" })
                return name + if (details.isEmpty()) "" else " (${details.joinToString(", ")})"
            }
    }

    /** A device on the JTAG chain, from `--detect`. */
    data class Device(val index: Int, val idcode: String, val manufacturer: String?, val family: String?, val model: String?) {
        /** Such as `Gowin GW5A-25`. */
        val description: String
            get() = listOfNotNull(manufacturer?.replaceFirstChar { it.uppercase() }, model ?: family).joinToString(" ")
                .ifEmpty { "a device with IDCODE $idcode" }
    }

    /** What is connected: the probes on USB, the devices the chosen cable reaches, and why it reached none. */
    data class Detection(val probes: List<Probe>, val devices: List<Device>, val error: String?)

    private val COLUMNS = Regex("""\s+""")
    private val USB_ID = Regex("""^0x[0-9A-Fa-f]{4}:(?:0x)?[0-9A-Fa-f]{4}$""")
    private val PROBE = Regex("""^\s*\d+\s+\d+\s+(0x[0-9A-Fa-f]{4}:0x[0-9A-Fa-f]{4})\s+(\S+)\s*(.*)$""")
    private val INDEX = Regex("""^index\s+(\d+):\s*$""")
    private val FIELD = Regex("""^\s+(idcode|manufacturer|family|model)\s+(.+?)\s*$""")
    private val VERSION = Regex("""v?(\d+\.\d+(?:\.\d+)?)""")

    /** The boards in the output of `--list-boards`: a header, then name, cable and FPGA part per line. */
    fun parseBoards(output: String): List<Board> =
        output.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("board name") }
            .mapNotNull { line ->
                val columns = line.split(COLUMNS)
                if (columns.size !in 2..3) return@mapNotNull null
                Board(columns[0], columns[1].takeIf { it != "Undefined" }, columns.getOrNull(2)?.takeIf { it != "Undefined" })
            }
            .toList()

    /** The cables in the output of `--list-cables`: a header, then name and USB ID per line. */
    fun parseCables(output: String): List<Cable> =
        output.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("cable name") }
            .mapNotNull { line ->
                val columns = line.split(COLUMNS)
                if (columns.size != 2 || !USB_ID.matches(columns[1])) return@mapNotNull null
                Cable(columns[0], columns[1])
            }
            .toList()

    /**
     * The probes in the output of `--scan-usb`, whose columns are padded to the width of the header's:
     * `001 009    0x0403:0x6010 FTDI2232   SIPEED       2025030317 USB Debugger`. A value too long for
     * its column pushes the others along, and then the columns are told apart by spaces.
     */
    fun parseProbes(output: String): List<Probe> {
        val lines = output.lines()
        val header = lines.firstOrNull { "probe_type" in it && "manufacturer" in it }
        val columns = header?.let { line -> listOf("manufacturer", "serial", "product").map { line.indexOf(it) } }
            ?.takeIf { starts -> starts.all { it > 0 } && starts.zipWithNext().all { (a, b) -> a < b } }
        return lines.mapNotNull { line ->
            val match = PROBE.matchEntire(line.trimEnd()) ?: return@mapNotNull null
            val (usbId, type) = match.destructured
            val (manufacturer, serial, product) = columns
                ?.takeIf { (m, s, p) -> line.length > p && listOf(m, s, p).all { line[it - 1] == ' ' } && match.groups[2]!!.range.last < m - 1 }
                ?.let { (m, s, p) -> Triple(line.substring(m, s).trim(), line.substring(s, p).trim(), line.substring(p).trim()) }
                ?: match.groupValues[3].trim().split(COLUMNS, limit = 3)
                    .let { Triple(it.getOrElse(0) { "" }, it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
            Probe(usbId, type, manufacturer, serial, product)
        }
    }

    /** The devices in the output of `--detect`: an `index N:` line, then one indented line per property. */
    fun parseDevices(output: String): List<Device> {
        val devices = mutableListOf<Device>()
        var index: Int? = null
        val fields = HashMap<String, String>()

        fun flush() {
            val current = index ?: return
            fields["idcode"]?.let { devices += Device(current, it, fields["manufacturer"], fields["family"], fields["model"]) }
            index = null
            fields.clear()
        }

        for (line in output.lineSequence()) {
            val start = INDEX.matchEntire(line.trim())
            if (start != null) {
                flush()
                index = start.groupValues[1].toInt()
                continue
            }
            val field = FIELD.matchEntire(line) ?: continue
            if (index != null) fields[field.groupValues[1]] = field.groupValues[2]
        }
        flush()
        return devices
    }

    /** The version in the output of `--Version`, such as `1.2.0` from `openFPGALoader v1.2.0`. */
    fun parseVersion(output: String): String? =
        output.lineSequence().firstOrNull { it.contains(TOOL, ignoreCase = true) }?.let { VERSION.find(it.substringAfter(TOOL))?.groupValues?.get(1) }

    /** The long options in the output of `--help`, such as `--write-flash`. */
    fun parseOptions(help: String): Set<String> =
        Regex("""(?<![\w-])--[a-zA-Z][\w-]*""").findAll(help).map { it.value }.toSet()

    /** What an installed openFPGALoader is: its version and the options it has, which differ between versions. */
    data class Info(val version: String?, val options: Set<String>) {
        val hasTerminalMode: Boolean get() = TERMINAL_MODE in options

        /** Such as `openFPGALoader 1.2.0`. */
        val displayName: String get() = TOOL + (version?.let { " $it" } ?: "")
    }

    private class Cached<T>(val stamp: Long, val value: T)

    private val infos = ConcurrentHashMap<Path, Cached<Info>>()
    private val boardLists = ConcurrentHashMap<Path, Cached<List<Board>>>()
    private val cableLists = ConcurrentHashMap<Path, Cached<List<Cable>>>()

    private fun stamp(tool: Path): Long = runCatching { Files.getLastModifiedTime(tool).toMillis() }.getOrDefault(0)

    /** Asks [tool] once, and again after it is replaced, for what [compute] works out. */
    private fun <T> cached(cache: ConcurrentHashMap<Path, Cached<T>>, tool: Path, compute: () -> T): T {
        val stamp = stamp(tool)
        cache[tool]?.takeIf { it.stamp == stamp }?.let { return it.value }
        return compute().also { cache[tool] = Cached(stamp, it) }
    }

    /** The version and options of [tool]. Runs it the first time, which takes a moment. */
    @Throws(ExecutionException::class)
    fun info(tool: Path): Info = cached(infos, tool) {
        val help = run(tool, "--help").let { it.stdout + it.stderr }
        val version = run(tool, "--Version").let { parseVersion(it.stdout + it.stderr) }
        Info(version, parseOptions(help))
    }

    /** The boards [tool] knows; empty when it cannot list them. */
    fun boards(tool: Path): List<Board> =
        runCatching { cached(boardLists, tool) { parseBoards(run(tool, "--list-boards").stdout) } }.getOrDefault(emptyList())

    /** The cables [tool] knows; empty when it cannot list them. */
    fun cables(tool: Path): List<Cable> =
        runCatching { cached(cableLists, tool) { parseCables(run(tool, "--list-cables").stdout) } }.getOrDefault(emptyList())

    /** The boards, if [tool] has listed them already; never runs it. */
    fun knownBoards(tool: Path): List<Board>? = boardLists[tool]?.takeIf { it.stamp == stamp(tool) }?.value

    /**
     * What is connected: the USB probes, and the devices on the JTAG chain of the cable [target] chooses.
     * Only detects: `--detect` is never given `--write-flash`, with which it would erase the FPGA's SRAM to
     * reach the flash.
     */
    @Throws(ExecutionException::class)
    fun detect(tool: Path, target: FpgaTarget): Detection {
        val probes = parseProbes(run(tool, "--scan-usb").stdout)
        val output = run(tool, *(target.connectionArguments + "--detect").toTypedArray())
        val devices = parseDevices(output.stdout)
        val error = if (devices.isEmpty()) {
            (output.stderr.lineSequence() + output.stdout.lineSequence()).map { it.trim() }
                .firstOrNull { OpenFpgaLoaderMessages.isError(it) }
                ?: "No FPGA answered."
        } else {
            null
        }
        return Detection(probes, devices, error)
    }

    private fun run(tool: Path, vararg arguments: String): com.intellij.execution.process.ProcessOutput {
        val output = CapturingProcessHandler(ToolchainSettings.toolCommandLine(tool).withParameters(*arguments)).runProcess(TIMEOUT_MS)
        if (output.isTimeout) throw ExecutionException("$TOOL ${arguments.joinToString(" ")} did not answer.")
        return output
    }

    private const val TIMEOUT_MS = 30_000

    /** Says what [detection] found, in a sentence or two. */
    fun describe(detection: Detection, target: FpgaTarget): String {
        val probes = when (detection.probes.size) {
            0 -> "No USB probe is plugged in."
            1 -> "Probe: ${detection.probes.single().description}."
            else -> "Probes: ${detection.probes.joinToString("; ") { it.description }}."
        }
        val over = target.connection?.let { " over $it" }.orEmpty()
        val devices = when (detection.devices.size) {
            0 -> "No FPGA was found$over: " + detection.error.orEmpty().trim().let { if (it.endsWith(".")) it else "$it." }
            1 -> "Found ${detection.devices.single().description}$over."
            else -> "Found$over: " + detection.devices.joinToString(", ") { "${it.index}) ${it.description}" } + "."
        }
        return "$devices $probes"
    }
}

/**
 * Reads openFPGALoader's messages: which are errors and warnings, and what to do about the errors that are
 * common, such as a cable that is not found.
 */
internal object OpenFpgaLoaderMessages {
    private val BOARD = Regex("""cannot find board '([^']*)'""")
    private val UNKNOWN_IDCODE = Regex("""Unknown device with IDCODE: (0x[0-9a-fA-F]+)""")

    fun isError(line: String): Boolean {
        val text = line.trim()
        return text.startsWith("Error", ignoreCase = true) || text.startsWith("JTAG init failed") ||
            text.startsWith("unable to open ftdi device") || text.startsWith("No cable found") ||
            text.startsWith("Couldn't open device") || text.contains("LIBUSB_ERROR")
    }

    fun isWarning(line: String): Boolean {
        val text = line.trim()
        return text.startsWith("Warning", ignoreCase = true) || text.startsWith("No cable or board specified")
    }

    /** The first error of [lines], or null. */
    fun firstError(lines: List<String>): String? = lines.map { it.trim() }.firstOrNull { isError(it) }

    /**
     * What to do about the errors in [lines], each as a sentence or two; empty when none of them is one that
     * has a known remedy. [os] chooses between udev rules, the WinUSB driver and nothing.
     */
    fun hints(lines: List<String>, target: FpgaTarget, os: Os = Os.current()): List<String> {
        val text = lines.joinToString("\n")
        fun has(vararg fragments: String) = fragments.any { text.contains(it, ignoreCase = true) }
        val hints = mutableListOf<String>()

        BOARD.find(text)?.let {
            hints += "openFPGALoader does not know the board '${it.groupValues[1]}'. Choose one of the boards it lists in the run " +
                "configuration, or leave the board empty and set the cable."
        }
        val permission = has("Check permissions", "LIBUSB_ERROR_ACCESS", "Permission denied", "Access denied")
        if (permission) {
            hints += when (os) {
                Os.LINUX -> "The cable is there, but this user may not open it. Install openFPGALoader's udev rules and " +
                    "plug the cable in again: $OPEN_FPGA_LOADER_UDEV"
                Os.WINDOWS -> "The cable is there, but Windows does not let openFPGALoader open it. Give its JTAG interface " +
                    "the WinUSB driver with Zadig: $OPEN_FPGA_LOADER_ZADIG"
                Os.MAC -> "The cable is there, but may not be opened. Close other programs that use it and plug it in again."
            }
        }
        if (!permission && has("unable to open ftdi device", "No cable found", "fail to open device", "can't open device with vid",
                "Can't open device", "dirtyJtag: No device found", "JTAG init failed")
        ) {
            val default = if (target.board.isBlank() && target.cable.isBlank()) {
                " Without a board or cable openFPGALoader looks for an FTDI FT2232; set the board in the run configuration."
            } else {
                ""
            }
            val driver = when (os) {
                Os.LINUX -> " On Linux it also takes openFPGALoader's udev rules: $OPEN_FPGA_LOADER_UDEV"
                Os.WINDOWS -> " On Windows the cable's JTAG interface needs the WinUSB driver, which Zadig installs: $OPEN_FPGA_LOADER_ZADIG"
                Os.MAC -> ""
            }
            hints += "No programming cable was found. Check that the board is plugged in over USB and switched on.$default$driver"
        }
        if (has("fail to claim interface", "Failed to claim cable", "Error claiming interface", "Fail to release interface")) {
            hints += "Another program has the cable open: a programmer, a serial terminal on the board's UART or another " +
                "openFPGALoader. Close it and try again."
        }
        UNKNOWN_IDCODE.find(text)?.let {
            hints += "openFPGALoader does not know the FPGA with IDCODE ${it.groupValues[1]}. Set the FPGA part in the run " +
                "configuration, or update openFPGALoader."
        }
        if (has("no device found", "No device. Can't download file", "IDCODE not found")) {
            hints += "The cable answered, but no FPGA did. Check that the board is powered and the cable is on its JTAG " +
                "connector; some boards have a switch between JTAG and other uses of it."
        }
        if (has("mismatch between target's idcode and bitstream idcode")) {
            hints += "The bitstream was built for another FPGA than the one on the board. Check the device the design is " +
                "built for, such as nextpnr's --device, and the board."
        }
        if (has("missing device-package information")) {
            hints += "To write the flash of this FPGA, openFPGALoader needs its exact part with the package, such as " +
                "xc7a35tcsg324: set the FPGA part in the run configuration."
        }
        if (has("flash vefication failed", "flash verification failed")) {
            hints += "What was read back from the flash differs from the bitstream. Try again, perhaps at a lower JTAG " +
                "frequency (--freq in the options)."
        }
        if (has("block protection is set")) {
            hints += "The flash is write-protected. Add --unprotect-flash to the options to lift the protection."
        }
        if (has("Error parsing options")) {
            hints += "openFPGALoader did not accept its options. Check the options in the run configuration; " +
                "`openFPGALoader --help` lists those of this version."
        }
        return hints
    }

    private const val OPEN_FPGA_LOADER_UDEV = OpenFpgaLoader.UDEV_URL
    private const val OPEN_FPGA_LOADER_ZADIG = OpenFpgaLoader.ZADIG_URL

    enum class Os {
        LINUX, MAC, WINDOWS;

        companion object {
            fun current(): Os = when {
                SystemInfo.isWindows -> WINDOWS
                SystemInfo.isMac -> MAC
                else -> LINUX
            }
        }
    }
}
