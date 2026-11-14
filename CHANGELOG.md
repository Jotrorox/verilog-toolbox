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
- Optional slang-server language server next to Verible, enabled by setting its path
