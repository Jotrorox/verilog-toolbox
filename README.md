# Verilog Toolbox

Verilog and SystemVerilog support for CLion.

## Features

- Syntax highlighting, commenting, brace and quote matching for `.v`, `.vh`, `.sv`, `.svh`
- Code folding, live templates (`always_ff`, `always_comb`, `case`, `module`, …) and
  **New | Verilog/SystemVerilog File**
- Diagnostics, navigation, hover, completion, rename, inlay hints, structure view and breadcrumbs via the
  [slang-server](https://github.com/hudson-trading/slang-server) language server, downloaded automatically
- [Verilator](https://verilator.org) lint as a second source of diagnostics: width mismatches, incomplete cases,
  inferred latches, multiple drivers and the like, as you type
- Optional [Verible](https://github.com/chipsalliance/verible) language server for formatting and style lint
- Run testbenches with [Icarus Verilog](https://github.com/steveicarus/iverilog) from the gutter, or all
  testbenches of a folder at once, with a pass/fail tree
- **Generate Testbench** for a module: instance, clock, reset and waveform dump already written
- **Check Synthesis** of a module with [Yosys](https://yosyshq.net/yosys/): what does not synthesize, multiple
  drivers, logic loops and latches, and the resources it takes, as generic gates or for an FPGA family
- Open waveforms in [GTKWave](https://gtkwave.sourceforge.net) or [Surfer](https://surfer-project.org);
  the viewer reloads after every re-run, and **Add to Waveform** in the editor shows the signal at the caret
  in it

Tool paths live in **Settings → Tools → Verilog Toolbox**. Verible and the OSS CAD Suite can be downloaded
from there if they're not installed. Verible has no prebuilt binary for Intel Macs; install it with
`brew tap chipsalliance/verible && brew install verible` and it is found without setting a path.

### OSS CAD Suite

Icarus Verilog, Verilator, Yosys and GTKWave come together in the nightly
[OSS CAD Suite](https://github.com/YosysHQ/oss-cad-suite-build), which the settings, the run configuration and
the notification about a missing tool offer to download (500–750 MB, 2–2.5 GB unpacked). The whole suite is
kept, the FPGA tools included, and an update removes the build it replaces. A tool whose path is left empty is
taken from the suite, or else from your `PATH`; GTKWave is the exception and comes from the suite only when
no GTKWave of your own is installed.

### Verilator lint

When Verilator is found, each open `.v` and `.sv` file is linted with `verilator --lint-only` as you edit it,
unsaved changes included, and its warnings and errors show up next to slang-server's. Files are linted in the
design file list when one is selected, and otherwise with the project's directories searched for the modules
they use. Extra options, such as `-Wall` for the style warnings, and a switch to turn the lint off are in the
settings. Syntax errors are left to slang-server, which reports them more precisely.

### Check Synthesis

**Check Synthesis** (editor, project view or **Tools**) runs the module at the caret, or the file's module, and
everything below it through Yosys in the Run tool window. The console shows Yosys's warnings and errors with
links to the source, then the cell statistics and a summary such as
`counter synthesizes for Lattice iCE40 to 11 cells: 5 LUTs, 4 flip-flops, 2 other.` Inferred latches are
pointed out. The cells counted are generic gates, or those of iCE40, ECP5, Gowin or Xilinx 7-series, as chosen
in the settings.

The design is read from the design file list when one is selected. SystemVerilog is read with the
[yosys-slang](https://github.com/povik/yosys-slang) front end when Yosys has it, as the OSS CAD Suite's does,
so that Yosys sees the design as slang-server does. Runs happen in `build/synth/<module>/` of the project,
which keeps the script, the full `yosys.log` and the statistics.

### Language server

slang-server (MIT licensed) is fetched from its GitHub releases the first time a Verilog file is opened,
unless one is set in the settings or already on your `PATH`. The automatic download can be switched off.

It works without configuration, checking each file on its own. To have it elaborate a whole design,
right-click a `.f` file list and choose **Use as Design File List**, or a file and
**Set as slang-server Top Level**.

### One design definition

The `.f` file list is the single definition of the design. slang-server elaborates it, and Icarus Verilog
runs take their sources, include directories, defines and libraries from it, so include paths no longer
have to be repeated in `.slang/server.json`. It uses slang's command-file syntax: source files, `+incdir+`
/ `-I`, `+define+` / `-D`, `-y`, `-v`, `+libext+`, `--top`, nested `-f` / `-F`, comments, quotes and
environment variables. Relative paths start at the project root, as in slang-server, or at the file's own
directory when `buildRelativePaths` is set in `.slang/server.json`.

A run configuration can name its own list in **File list**. Without any list, a run compiles the Verilog
files in the testbench's directory and finds other modules by file name, as before. Options iverilog has
no use for, such as `--std`, are listed in the run console and otherwise ignored.

### Testbenches

A module without ports is a testbench and gets a run icon in the gutter. Right-click a folder or the
project root and choose **Run 'Testbenches in …'** to compile and simulate every testbench below it.
Results show in the test runner: a testbench fails when the design does not compile, when `vvp` exits
non-zero, or when the simulation reports `$error`, `$fatal` or a failing assertion. `$warning` and `$info`
do not fail it.

**Generate Testbench** on a module (editor, project view or **Code | Generate**) creates `<module>_tb.sv`
next to it, with a signal for every port, the parameters, the instance, a clock and reset sequence where
those ports are recognised by name, and `$dumpfile`/`$dumpvars`. Interface ports and user-defined types
are marked with `// TODO` lines.

### Waveforms

Simulations run in `build/sim/<top module>/` of the project, so dumps stay out of the sources. Relative
paths in `$readmemh` and `$fopen` start there too; set a working directory in the run configuration to
run somewhere else. Layouts are kept next to the testbench (`<module>.gtkw`, `<module>.surf.ron`) so they
can be committed; the first Surfer layout is created with the testbench's signals. A viewer opened from
the IDE reloads after each re-run (Surfer 0.4 or newer, GTKWave built with Tcl).

**Add to Waveform** in the editor's context menu adds the signal at the caret to that viewer, under the
name it has in the simulation: a signal of `counter` instantiated as `dut` in `counter_tb` is added as
`counter_tb.dut.count`. The name is worked out from the instances in the project's sources and, for a VCD
dump, checked against the scopes the dump has, which also finds instances in generate blocks. A module
instantiated more than once, or a signal in more than one open waveform, offers a list to choose from,
with all of them at the top. The action has no shortcut of its own; give it one in **Settings → Keymap**.

## Development

```bash
./gradlew runIde   # start CLion with the plugin
./gradlew check    # run tests
```

## License

See [LICENSE](LICENSE).
