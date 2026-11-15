# Verilog Toolbox

Verilog and SystemVerilog support for CLion.

## Features

- Syntax highlighting, commenting, brace and quote matching for `.v`, `.vh`, `.sv`, `.svh`
- Code folding, live templates (`always_ff`, `always_comb`, `case`, `module`, …) and
  **New | Verilog/SystemVerilog File**
- Diagnostics, navigation, hover, completion, rename, inlay hints, structure view and breadcrumbs via the
  [slang-server](https://github.com/hudson-trading/slang-server) language server, downloaded automatically
- Optional [Verible](https://github.com/chipsalliance/verible) language server for formatting and style lint
- Run testbenches with [Icarus Verilog](https://github.com/steveicarus/iverilog) from the gutter, or all
  testbenches of a folder at once, with a pass/fail tree
- **Generate Testbench** for a module: instance, clock, reset and waveform dump already written
- Open waveforms in [GTKWave](https://gtkwave.sourceforge.net) or [Surfer](https://surfer-project.org);
  the viewer reloads after every re-run

Tool paths live in **Settings → Tools → Verilog Toolbox**. Verible and Icarus Verilog can be downloaded
from there if they're not installed.

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

## Development

```bash
./gradlew runIde   # start CLion with the plugin
./gradlew check    # run tests
```

Try it out on the sample project in [`examples/demo`](examples/demo).

## License

See [LICENSE](LICENSE).
