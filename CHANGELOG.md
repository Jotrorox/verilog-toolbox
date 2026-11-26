<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Verilog-toolbox Changelog

## [Unreleased]

### Added

- GOWIN EDA support like that for Quartus Prime: a **GOWIN EDA** tool window with an Overview (device, top
  module, the steps of the last build, timing, resources, reports), a Pins tab that edits pins, I/O types, pull
  modes and drive in the `.cst` with the package's pins and the device's I/O types offered, and the messages of
  the last build; **Tools | GOWIN EDA** to synthesize, place and route, build and program with `programmer_cli`
  (SRAM or flash), each as a run configuration with a summary at the end of the console; New GOWIN EDA Project
  for a part or a Sipeed Tang board, Device…, Add to / Remove from GOWIN EDA Project, Set as GOWIN EDA Top
  Module and Open in GOWIN EDA. The `.gprj` is the design of slang-server and the other tools, and GOWIN EDA's
  installation is found by itself or set in the settings
- GOWIN EDA's reports open in the report viewer: the place and route report, the timing report with each path
  of the detailed analysis as a section of its own, the logs with their messages, and the HTML reports of
  synthesis, power and pins, with whether timing is met, the step succeeded or the total power at the top

- A viewer for Quartus reports (`.rpt`) and pin-out files (`.pin`): the sections listed and grouped, with a
  search of titles and contents; tables that sort numerically and filter, with hierarchies set in, a gauge
  for the resources used and negative slack in red; messages by severity that go to the source; whether the
  step succeeded or timing is met at the top. The text is one click away, alone or next to the viewer

- One-click programming of Gowin boards without a Makefile: a project with a `.cst` file is built by the
  plugin with Yosys, nextpnr-himbaechel and gowin_pack from the OSS CAD Suite, then loaded or flashed with
  openFPGALoader. The board, the top module and the sources are found by themselves; the build is skipped
  when nothing changed, and ends with the maximum frequency of each clock
- Board presets for the Sipeed Tang Nano, Nano 1K, 4K, 9K and 20K, Primer 20K and 25K and Mega 138K, with
  the values of apicula's examples
- "Build first" in the openFPGALoader run configuration: nothing, a command, or the built-in Gowin build with
  its board, constraint file, top module and optional file list. A Makefile still wins when there is one
- "Run 'Load …'" on a `.cst` file builds it with the built-in build and loads the bitstream
- Warnings in `.cst` files for names that are not ports of the top module, bits out of range, and ports of
  the top module without an `IO_LOC`

- Highlighting and commenting for Gowin physical constraint files (`.cst`): constraints such as `IO_LOC` and
  `IO_PORT`, port names, pins and sites, attributes and their values, `//` and `/* */` comments, with
  colours of their own under Settings | Editor | Color Scheme | Gowin Constraints

### Fixed

- Verilator lint and Check Synthesis of a file outside the selected file list added the parts of its path
  to the design instead of the file
- Reports with a `°` in them, written by Quartus on Windows in its code page, were not read: the tool window
  left out all of the Fitter's messages. They open with the right characters in the editor too

### Changed

- The code is organised into one package per feature, and `plugin.xml` includes one descriptor per feature
  from `META-INF/features/`, so that support for another tool can be added in one place

## [0.3.0] - 2026-11-24

### Added

- openFPGALoader support: an "openFPGALoader" run configuration and "Load Bitstream into FPGA", "Write
  Bitstream to Flash" and "Detect FPGA" in Tools, "Run 'Load …'" and "Write to FPGA Flash" on bitstream files.
  The board, cable, FPGA part and bitstream are read from the project's Makefile, which also builds the
  bitstream before it is programmed; the build finds the tools of openFPGALoader's suite on its PATH
- The console of a programming run shows openFPGALoader's progress bar in one line, colours its warnings,
  points out a bitstream older than its sources, and ends with the result or with what to do about a cable
  that is not found, missing udev rules or WinUSB driver, an unknown board, a bitstream for another FPGA and
  other common errors
- A searchable list of the boards and cables openFPGALoader knows in the run configuration, and a setting for
  the path of openFPGALoader, which is otherwise taken from the OSS CAD Suite, PATH or Homebrew
- Quartus Prime support, so that Quartus itself need not be opened: the Quartus project (`.qpf`/`.qsf`) of a
  project and the Quartus installation are found without configuration
- A "Quartus Prime" run configuration and actions for Analysis & Elaboration, full compilation, the single
  steps and programming the device with `quartus_pgm`, with messages coloured by severity, links from
  `File: … Line: …` to the source and a closing summary of resources, timing and errors
- A Quartus tool window: device, top-level entity, the steps of the last compilation, Fmax and slacks,
  resource usage and the reports; a table to assign pins and I/O standards, which edits the `.qsf`; and the
  messages of the last compilation by severity
- "New Quartus Project…", "Device…", "Add to / Remove from Quartus Project", "Set as Quartus Top-Level Entity"
  and "Open in Quartus Prime"
- The design is read from the `.qsf` when a project has one: slang-server, Verilator lint, Check Synthesis and
  Icarus Verilog runs use its sources, search paths, macros and `.qip` files, and know Altera's megafunctions
  from Quartus's simulation libraries
- Highlighting, commenting and completion for `.qsf`, `.qpf`, `.qip` and `.sdc` files; Quartus's reports open
  as text
- "Add to Waveform" in the editor: the signal at the caret is added to the GTKWave or Surfer that Open Waveform
  started, under its hierarchical name in the simulation, with a choice when its module has several instances
- Verilator lint as a second source of diagnostics next to slang-server, run on the open file as it is edited,
  in the design file list when one is selected, with extra options and an off switch in the settings
- "Check Synthesis" on a module: Yosys synthesizes it, for generic gates or for iCE40, ECP5, Gowin or Xilinx
  7-series, and the console shows its warnings and errors, the cell statistics and a resource summary that
  points out inferred latches. SystemVerilog is read with yosys-slang when Yosys has it
- Settings for the paths of Verilator and Yosys, and for the synthesis target

### Changed

- The OSS CAD Suite download is kept whole instead of only its Icarus Verilog, so Verilator, Yosys and GTKWave
  come with it. Tools whose path is not set are taken from it, and an update removes the build it replaces as
  well as the Icarus-only copy earlier versions downloaded

### Fixed

- The settings and the selected design are saved. They were written as empty and so were back at their
  defaults, with no design selected, after the IDE was restarted
- Verible on Intel Macs, which has no prebuilt download: the settings show the Homebrew commands instead of a
  download button that fails, and a Verible installed with Homebrew is found even when CLion was started from
  the Dock without the shell's PATH
- The settings page is as wide as the Settings dialog, its comments wrapping. It was wider and scrolled
  sideways, which cut off the ends of the longer comments

## [0.2.1] - 2026-11-16

### Fixed

- Binary incompatibility with CLion 2026.3 (`NoSuchMethodError` on `Diagnostic.getMessage()` when Verible runs
  next to slang-server)

## [0.2.0] - 2026-11-15

### Added

- Icarus Verilog run configuration that compiles a testbench with `iverilog` and simulates it with `vvp`
- Run icon in the gutter next to testbench modules (modules without ports)
- Clickable `file:line` locations in the simulation console
- Download of Icarus Verilog when it is not installed, from the settings, the run configuration dialog or the
  notification shown when a simulation is started without it
- "Open Waveform" in the simulation console, and a link on the dump file vvp reports, that open the dumped
  waveform in GTKWave or Surfer
- Per-testbench waveform layout next to the testbench: `<module>.gtkw` for GTKWave, `<module>.surf.ron` for Surfer
- slang-server as the default language server: diagnostics, navigation, hover, completion, rename and inlay hints
- Automatic download of slang-server when it is not installed, and a button in the settings to update it
- "Set as slang-server Top Level" and "Use as Design File List" to elaborate a whole design
- A `.f` file list as the single definition of the design: Icarus Verilog runs take their sources, include
  directories, defines and libraries from the project's file list, or from the one named in the run configuration
- Test runner for testbench runs, with a pass/fail tree driven by `$error`, `$fatal`, failing assertions and
  the exit code, "Rerun Failed Tests" and navigation from a test to its module
- "Run 'Testbenches in …'" on a folder or the project root, which runs every testbench below it
- "Generate Testbench" on a module, which creates `<module>_tb.sv` with the instance, clock, reset and
  `$dumpfile`/`$dumpvars`
- Reload of the open waveform viewer after a re-run, and when "Open Waveform" is used on a dump that is
  already open
- First Surfer layout created with the signals of the testbench, so it no longer has to be saved by hand
- Code folding for `begin`/`end`, `module`/`endmodule` and the other paired keywords, block comments and
  runs of line comments
- Live templates such as `always_ff`, `always_comb`, `case`, `module`, `genfor`, `function` and `task`
- "New | Verilog/SystemVerilog File" with templates for modules, packages, interfaces and headers
- Structure view and breadcrumbs from slang-server's document symbols

### Changed

- Simulations run in `build/sim/<top module>/` of the project, so waveform dumps no longer land next to the
  sources; relative paths in `$readmemh` and `$fopen` start there too, unless a working directory is set in the
  run configuration
- Files that declare a package are compiled first when the testbench's directory is scanned for sources
- Verible is now optional and adds formatting and style lint next to slang-server
