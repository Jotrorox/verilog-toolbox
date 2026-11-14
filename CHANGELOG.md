<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Verilog-toolbox Changelog

## [Unreleased]

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
- "Set as slang-server Top Level" and "Use as slang-server Build File" to elaborate a whole design

### Changed

- Verible is now optional and adds formatting and style lint next to slang-server
