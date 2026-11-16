<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Verilog-toolbox Changelog

## [Unreleased]

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
